package com.sparrowwallet.frigate.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.electrum.ElectrumBlockHeader;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.CoreAuthType;
import com.sparrowwallet.frigate.io.RecentBlocksMap;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.File;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BitcoindClient {
    private static final Logger log = LoggerFactory.getLogger(BitcoindClient.class);

    private static final int MAX_REORG_DEPTH = 10;
    public static final int MIN_SUBMIT_PACKAGE_VERSION = 280000;

    private static final int FLUSH_BATCH_SIZE = 50;
    private static final long FLUSH_DEBOUNCE_MS = 100;
    private static final long ZMQ_MEMPOOL_DIFF_INTERVAL_MS = 30_000;
    private static final long POLL_MEMPOOL_DIFF_INTERVAL_MS = 5_000;
    private static final long ZMQ_STALENESS_THRESHOLD_MS = 60_000;

    private final JsonRpcClient jsonRpcClient;
    private final Timer timer = new Timer(true);
    private final Index blocksIndex;
    private final Index mempoolIndex;

    private NetworkInfo networkInfo;
    private String lastBlock;
    private ElectrumBlockHeader tip;

    private Exception lastPollException;

    private final Lock syncingLock = new ReentrantLock();
    private final Condition syncingCondition = syncingLock.newCondition();
    private boolean syncing;

    private volatile boolean stopped;

    private volatile ZContext zmqContext;
    private volatile Thread zmqSubscriberThread;
    private volatile Thread zmqConsumerThread;
    private volatile long lastZmqMessageMs;
    private final BlockingQueue<Sha256Hash> zmqAddedQueue = new LinkedBlockingQueue<>(50_000);
    private long lastMempoolDiffMs;

    private final Map<HashIndex, byte[]> scriptPubKeyCache;
    private final Set<Sha256Hash> mempoolTxIds = ConcurrentHashMap.newKeySet();
    private final RecentBlocksMap recentBlocksMap = new RecentBlocksMap(MAX_REORG_DEPTH);

    public BitcoindClient(Index blocksIndex, Index mempoolIndex) {
        BitcoindTransport bitcoindTransport;

        Config config = Config.get();
        Config.CoreConfig coreConfig = config.getCore();

        Server coreServer = coreConfig.getServerObj();
        if(coreServer == null) {
            coreServer = new Server("http://127.0.0.1:" + Network.get().getDefaultPort());
        }

        CoreAuthType coreAuthType = coreConfig.getAuthTypeEnum();
        if(coreAuthType == null) {
            coreAuthType = CoreAuthType.COOKIE;
        }

        File coreDataDir = coreConfig.getDataDirFile();
        if(coreDataDir == null) {
            coreDataDir = getDefaultCoreDataDir();
        }

        String coreAuth = coreConfig.getAuth();
        if(coreAuth == null) {
            coreAuth = "user:password";
        }

        if(coreAuthType == CoreAuthType.COOKIE || coreAuth.length() < 2) {
            bitcoindTransport = new BitcoindTransport(coreServer, coreDataDir);
        } else {
            bitcoindTransport = new BitcoindTransport(coreServer, coreAuth);
        }

        this.jsonRpcClient = new JsonRpcClient(bitcoindTransport);
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;

        int cacheSize = config.getIndex().getCacheSizeEntries();
        this.scriptPubKeyCache = Collections.synchronizedMap(lruCache(cacheSize));
    }

    public void initialize() {
        networkInfo = getBitcoindService().getNetworkInfo();

        BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
        VerboseBlockHeader blockHeader = getBitcoindService().getBlockHeader(blockchainInfo.bestblockhash());
        tip = blockHeader.getBlockHeader();
        timer.schedule(new PollTask(), 5000, 5000);

        if(blockchainInfo.initialblockdownload() && networkInfo.networkactive()) {
            log.info("Waiting for sync to complete...");
            syncingLock.lock();
            try {
                syncing = true;
                syncingCondition.await();

                if(syncing) {
                    if(lastPollException instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException("Error while waiting for sync to complete", lastPollException);
                }
            } catch(InterruptedException e) {
                throw new RuntimeException("Interrupted while waiting for sync to complete");
            } finally {
                syncingLock.unlock();
            }

            blockchainInfo = getBitcoindService().getBlockchainInfo();
            blockHeader = getBitcoindService().getBlockHeader(blockchainInfo.bestblockhash());
            tip = blockHeader.getBlockHeader();
        }

        lastBlock = blockchainInfo.bestblockhash();
        Frigate.getEventBus().post(tip);
        log.info("Initializing indexes...");
        updateBlocksIndex();
        updateMempoolIndex();
        lastMempoolDiffMs = System.currentTimeMillis();
        Frigate.getEventBus().post(tip);

        String zmqEndpoint = Config.get().getCore().getZmqSequenceEndpoint();
        if(zmqEndpoint != null && !zmqEndpoint.isBlank()) {
            startZmqSequenceSubscriber(zmqEndpoint);
        }
    }

    private synchronized void updateBlocksIndex() {
        BitcoindClientService bitcoindService = getBitcoindService();
        HexFormat hexFormat = HexFormat.of();

        for(int i = blocksIndex.getLastBlockIndexed() + 1; i <= tip.height(); i++) {
            String blockHash = getBitcoindService().getBlockHash(i);
            if(i > tip.height() - MAX_REORG_DEPTH) {
                recentBlocksMap.put(i, blockHash);
            }
            String blockHex = (String)bitcoindService.getBlock(blockHash, 0);
            Block block = new Block(hexFormat.parseHex(blockHex));

            Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
            Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
            for(Transaction tx : block.getTransactions()) {
                for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                    addtoScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
                }

                if(!tx.isCoinBase() && containsTaprootOutput(tx)) {
                    for(TransactionInput txInput : tx.getInputs()) {
                        HashIndex hashIndex = new HashIndex(txInput.getOutpoint().getHash(), txInput.getOutpoint().getIndex());
                        spentScriptPubKeys.put(hashIndex, getScriptPubKey(bitcoindService, hexFormat, hashIndex));
                    }

                    byte[] tweak = SilentPaymentUtils.getTweak(tx, spentScriptPubKeys, false);
                    if(tweak != null) {
                        BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), i, block.getBlockHeader().getTimeAsDate(), 0L, tx, block.getHash());
                        eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
                    }
                }
            }

            if(!eligibleTransactions.isEmpty()) {
                blocksIndex.addToIndex(eligibleTransactions);
            }
        }
    }

    private synchronized void updateMempoolIndex() {
        HexFormat hexFormat = HexFormat.of();

        Set<Sha256Hash> knownTxids = new HashSet<>(mempoolTxIds);
        Set<Sha256Hash> currentMempoolTxids = getBitcoindService().getRawMempool();
        Set<Sha256Hash> removedTxids = new HashSet<>(knownTxids);
        removedTxids.removeAll(currentMempoolTxids);
        Set<Sha256Hash> addedTxids = new HashSet<>(currentMempoolTxids);
        addedTxids.removeAll(knownTxids);

        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();

        try {
            for(Sha256Hash addedTxid : addedTxids) {
                ingestMempoolTx(addedTxid, spentScriptPubKeys, eligibleTransactions, hexFormat);
            }

            if(!removedTxids.isEmpty()) {
                mempoolIndex.removeFromIndex(removedTxids);
            }
            if(!eligibleTransactions.isEmpty()) {
                mempoolIndex.addToIndex(eligibleTransactions);
            }
        } catch(RuntimeException e) {
            //nothing was committed to the index - drop the would-be-indexed txids so a later diff retries them
            eligibleTransactions.keySet().forEach(blkTx -> mempoolTxIds.remove(blkTx.getHash()));
            throw e;
        }

        mempoolTxIds.removeAll(removedTxids);
    }

    /**
     * Ingest a single mempool transaction: fetch it from bitcoind, cache its output scriptPubKeys, and if it has a taproot
     * output, compute the silent payments tweak and add it to the supplied eligible transactions batch. Idempotent — gated on
     * an atomic insert into {@link #mempoolTxIds}, so a txid already ingested (via ZMQ or the safety-net diff) is skipped.
     */
    private void ingestMempoolTx(Sha256Hash txid, Map<HashIndex, Script> spentScriptPubKeys, Map<BlockTransaction, byte[]> eligibleTransactions, HexFormat hexFormat) {
        if(!mempoolTxIds.add(txid)) {
            return;
        }

        BitcoindClientService bitcoindService = getBitcoindService();
        try {
            String txHex = (String)bitcoindService.getRawTransaction(txid.toString(), false);
            Transaction tx = new Transaction(hexFormat.parseHex(txHex));
            for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                addtoScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
            }

            if(!tx.isCoinBase() && containsTaprootOutput(tx)) {
                for(TransactionInput txInput : tx.getInputs()) {
                    HashIndex hashIndex = new HashIndex(txInput.getOutpoint().getHash(), txInput.getOutpoint().getIndex());
                    spentScriptPubKeys.put(hashIndex, getScriptPubKey(bitcoindService, hexFormat, hashIndex));
                }

                byte[] tweak = SilentPaymentUtils.getTweak(tx, spentScriptPubKeys, false);
                if(tweak != null) {
                    BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), 0, null, 0L, tx, null);
                    eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
                }
            }
        } catch(JsonRpcException e) {
            //transaction removed from mempool before we could fetch it - leave it in mempoolTxIds
        } catch(RuntimeException e) {
            //transient failure - drop the txid so a later diff re-ingests it
            mempoolTxIds.remove(txid);
            throw e;
        }
    }

    private void startZmqSequenceSubscriber(String endpoint) {
        if(stopped) {
            return;
        }

        zmqContext = new ZContext();
        zmqSubscriberThread = Thread.ofPlatform().daemon().name("BitcoindZmqSequence").start(() -> {
            try(ZMQ.Socket socket = zmqContext.createSocket(SocketType.SUB)) {
                socket.setReconnectIVL(100);
                socket.setReconnectIVLMax(10_000);
                socket.subscribe("sequence");
                socket.connect(endpoint);
                log.info("Subscribed to Bitcoin Core ZMQ sequence publisher at {}", endpoint);

                while(!stopped && !Thread.currentThread().isInterrupted()) {
                    String topic = socket.recvStr();
                    byte[] body = socket.hasReceiveMore() ? socket.recv() : null;
                    while(socket.hasReceiveMore()) {
                        socket.recv();  //drain remaining frames (the per-topic sequence number, plus any added in future bitcoind versions)
                    }
                    if(!"sequence".equals(topic) || body == null || body.length < 33) {
                        continue;
                    }
                    lastZmqMessageMs = System.currentTimeMillis();

                    char label = (char)body[32];
                    if(label == 'A') {
                        Sha256Hash txid = Sha256Hash.wrap(Arrays.copyOf(body, 32));
                        if(!zmqAddedQueue.offer(txid)) {
                            log.warn("ZMQ mempool queue full, dropping txid {} (safety-net diff will recover it)", txid);
                        }
                    }
                    //'R' (mempool removal), 'C'/'D' (block connect/disconnect) are handled by the polling loop
                }
            } catch(Throwable t) {
                if(!stopped) {
                    log.error("Bitcoin Core ZMQ sequence subscriber exited", t);
                }
            }
        });

        zmqConsumerThread = Thread.ofPlatform().daemon().name("BitcoindZmqConsumer").start(this::zmqConsumerLoop);

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if(!stopped && lastZmqMessageMs == 0L) {
                    log.warn("No ZMQ messages received from Bitcoin Core within 60s at {} - verify -zmqpubsequence is configured on this endpoint. " +
                            "Mempool ingestion latency will be up to {}s until ZMQ messages arrive", endpoint, ZMQ_MEMPOOL_DIFF_INTERVAL_MS / 1000);
                }
            }
        }, 60_000);
    }

    private void zmqConsumerLoop() {
        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
        HexFormat hexFormat = HexFormat.of();

        while(!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                Sha256Hash first = zmqAddedQueue.poll(1, TimeUnit.SECONDS);
                if(first == null) {
                    continue;
                }

                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FLUSH_DEBOUNCE_MS);
                ingestMempoolTx(first, spentScriptPubKeys, eligibleTransactions, hexFormat);
                while(eligibleTransactions.size() < FLUSH_BATCH_SIZE) {
                    long remaining = deadline - System.nanoTime();
                    if(remaining <= 0) {
                        break;
                    }
                    Sha256Hash next = zmqAddedQueue.poll(remaining, TimeUnit.NANOSECONDS);
                    if(next == null) {
                        break;
                    }
                    ingestMempoolTx(next, spentScriptPubKeys, eligibleTransactions, hexFormat);
                }

                if(!eligibleTransactions.isEmpty()) {
                    mempoolIndex.addToIndex(eligibleTransactions);
                    eligibleTransactions.clear();
                }
                spentScriptPubKeys.clear();
            } catch(InterruptedException e) {
                return;
            } catch(Throwable t) {
                log.error("Error processing ZMQ mempool transactions", t);
                eligibleTransactions.keySet().forEach(blkTx -> mempoolTxIds.remove(blkTx.getHash()));
                eligibleTransactions.clear();
                spentScriptPubKeys.clear();
            }
        }
    }

    public void stop() {
        timer.cancel();
        stopped = true;
        if(zmqSubscriberThread != null) {
            zmqSubscriberThread.interrupt();
        }
        if(zmqConsumerThread != null) {
            zmqConsumerThread.interrupt();
        }
        if(zmqContext != null) {
            zmqContext.close();
        }
    }

    public BitcoindClientService getBitcoindService() {
        return jsonRpcClient.onDemand(BitcoindClientService.class);
    }

    public NetworkInfo getNetworkInfo() {
        return networkInfo;
    }

    public ElectrumBlockHeader getTip() {
        return tip;
    }

    private Script getScriptPubKey(BitcoindClientService bitcoindClientService, HexFormat hexFormat, HashIndex hashIndex) {
        Script scriptPubKey = getFromScriptPubKeyCache(hashIndex);
        if(scriptPubKey == null) {
            try {
                String txHex = (String)bitcoindClientService.getRawTransaction(hashIndex.getHash().toString(), false);
                Transaction tx = new Transaction(hexFormat.parseHex(txHex));
                TransactionOutput txOutput = tx.getOutputs().get((int)hashIndex.getIndex());
                addtoScriptPubKeyCache(hashIndex.getHash(), (int)hashIndex.getIndex(), txOutput.getScriptBytes());
                scriptPubKey = getFromScriptPubKeyCache(hashIndex);
            } catch(Exception e) {
                log.error("Error retrieving scriptPubKey for txid " + hashIndex.getHash() + " output index " + hashIndex.getIndex(), e);
                throw e;
            }
        }

        return scriptPubKey;
    }

    private class PollTask extends TimerTask {
        @Override
        public void run() {
            if(stopped) {
                timer.cancel();
            }

            try {
                if(syncing) {
                    BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
                    if(blockchainInfo.initialblockdownload() && !isEmptyBlockchain(blockchainInfo)) {
                        return;
                    } else {
                        syncing = false;
                        syncingLock.lock();
                        try {
                            syncingCondition.signal();
                        } finally {
                            syncingLock.unlock();
                        }
                    }
                }

                if(lastBlock != null && tip != null) {
                    String blockhash = getBitcoindService().getBlockHash(tip.height());
                    if(!lastBlock.equals(blockhash)) {
                        int reorgStartHeight = tip.height();
                        for(; reorgStartHeight >= tip.height() - MAX_REORG_DEPTH; reorgStartHeight--) {
                            String indexedBlockHash = recentBlocksMap.get(reorgStartHeight);
                            String reorgBlockhash = getBitcoindService().getBlockHash(reorgStartHeight);
                            if(indexedBlockHash == null || indexedBlockHash.equals(reorgBlockhash)) {
                                break;
                            }
                        }

                        int blocksReorged = tip.height() - reorgStartHeight;
                        if(blocksReorged > 1) {
                            log.info("Reorg detected of last " + blocksReorged + " blocks, block height " + tip.height() + " was " + lastBlock + " and now is " + blockhash);
                        } else {
                            log.info("Reorg detected of last block, block height " + tip.height() + " was " + lastBlock + " and now is " + blockhash);
                        }

                        Frigate.getEventBus().post(new BlockReorgSyncStart(reorgStartHeight));
                        blocksIndex.removeFromIndex(reorgStartHeight + 1);
                        updateBlocksIndex();
                        Frigate.getEventBus().post(new BlockReorgSyncComplete(reorgStartHeight));

                        lastBlock = null;
                    }
                }

                BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
                String currentBlock = lastBlock;

                if(currentBlock == null || !currentBlock.equals(blockchainInfo.bestblockhash())) {
                    VerboseBlockHeader blockHeader = getBitcoindService().getBlockHeader(blockchainInfo.bestblockhash());
                    tip = blockHeader.getBlockHeader();
                    log.debug("New block height " + tip.height());
                    Frigate.getEventBus().post(tip);
                    updateBlocksIndex();
                }

                boolean zmqHealthy = zmqContext != null && lastZmqMessageMs != 0L && System.currentTimeMillis() - lastZmqMessageMs < ZMQ_STALENESS_THRESHOLD_MS;
                long mempoolDiffInterval = zmqHealthy ? ZMQ_MEMPOOL_DIFF_INTERVAL_MS : POLL_MEMPOOL_DIFF_INTERVAL_MS;
                if(System.currentTimeMillis() - lastMempoolDiffMs >= mempoolDiffInterval) {
                    updateMempoolIndex();
                    lastMempoolDiffMs = System.currentTimeMillis();
                }

                lastBlock = blockchainInfo.bestblockhash();
            } catch(Exception e) {
                lastPollException = e;
                log.warn("Error polling Bitcoin Core", e);

                if(syncing) {
                    syncingLock.lock();
                    try {
                        syncingCondition.signal();
                    } finally {
                        syncingLock.unlock();
                    }
                }
            }
        }
    }

    public Integer findBlockByTimestamp(long targetTimestamp) {
        if(targetTimestamp < 0) {
            throw new IllegalArgumentException("Target timestamp cannot be negative");
        }

        int low = 0;
        int high = tip.height();
        int bestHeight = 0;

        while(low <= high) {
            int mid = (low + high) / 2;

            try {
                BlockStats blockStats = getBitcoindService().getBlockStats(mid);
                long blockTimestamp = blockStats.time();

                if(blockTimestamp <= targetTimestamp) {
                    bestHeight = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            } catch(Exception e) {
                log.warn("Error getting block stats for block height " + mid, e);
                return bestHeight;
            }
        }

        return bestHeight;
    }

    private boolean isEmptyBlockchain(BlockchainInfo blockchainInfo) {
        return blockchainInfo.blocks() == 0 && blockchainInfo.getProgressPercent() == 100;
    }

    private Script getFromScriptPubKeyCache(HashIndex hashIndex) {
        byte[] scriptPubKeyBytes = scriptPubKeyCache.get(hashIndex);
        if(scriptPubKeyBytes != null) {
            return new Script(scriptPubKeyBytes);
        }

        return null;
    }

    private void addtoScriptPubKeyCache(Sha256Hash txid, int outputIndex, byte[] scriptPubKeyBytes) {
        HashIndex hashIndex = new HashIndex(txid, outputIndex);
        //Only cache if the length of the field matches one of the valid
        if(getValidScriptType(scriptPubKeyBytes) != null) {
            scriptPubKeyCache.put(hashIndex, scriptPubKeyBytes);
        } else {
            scriptPubKeyCache.put(hashIndex, new byte[0]);
        }
    }

    private static boolean containsTaprootOutput(Transaction tx) {
        for(TransactionOutput txOutput : tx.getOutputs()) {
            ScriptType scriptType = getValidScriptType(txOutput.getScriptBytes());
            if(scriptType == ScriptType.P2TR) {
                return true;
            }
        }

        return false;
    }

    private static ScriptType getValidScriptType(byte[] scriptPubKey) {
        if(scriptPubKey == null) {
            return null;
        }

        int length = scriptPubKey.length;

        // P2PKH: 25 bytes - OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG
        if(length == 25 &&
                scriptPubKey[0] == (byte) 0x76 &&  // OP_DUP
                scriptPubKey[1] == (byte) 0xa9 &&  // OP_HASH160
                scriptPubKey[2] == (byte) 0x14 &&  // Push 20 bytes
                scriptPubKey[23] == (byte) 0x88 && // OP_EQUALVERIFY
                scriptPubKey[24] == (byte) 0xac) { // OP_CHECKSIG
            return ScriptType.P2PKH;
        }

        // P2SH-P2WPKH: 23 bytes - OP_HASH160 <20-byte hash> OP_EQUAL
        if(length == 23 &&
                scriptPubKey[0] == (byte) 0xa9 &&  // OP_HASH160
                scriptPubKey[1] == (byte) 0x14 &&  // Push 20 bytes
                scriptPubKey[22] == (byte) 0x87) { // OP_EQUAL
            return ScriptType.P2SH_P2WPKH;
        }

        // P2WPKH: 22 bytes - OP_0 <20-byte hash>
        if(length == 22 &&
                scriptPubKey[0] == (byte) 0x00 &&  // OP_0
                scriptPubKey[1] == (byte) 0x14) {  // Push 20 bytes
            return ScriptType.P2WPKH;
        }

        // P2TR: 34 bytes - OP_1 <32-byte taproot output>
        if(length == 34 &&
                scriptPubKey[0] == (byte) 0x51 &&  // OP_1
                scriptPubKey[1] == (byte) 0x20) {  // Push 32 bytes
            return ScriptType.P2TR;
        }

        return null;
    }

    private static File getDefaultCoreDataDir() {
        OsType osType = OsType.getCurrent();
        if(osType == OsType.MACOS) {
            return new File(System.getProperty("user.home") + "/Library/Application Support/Bitcoin");
        } else if(osType == OsType.WINDOWS) {
            File oldDir = new File(System.getenv("APPDATA") + "/Bitcoin");
            return oldDir.exists() ? oldDir : new File(System.getenv("LOCALAPPDATA") + "/Bitcoin");
        } else {
            return new File(System.getProperty("user.home") + "/.bitcoin");
        }
    }

    public static <K,V> Map<K,V> lruCache(final int maxSize) {
        return new LinkedHashMap<K, V>(maxSize*4/3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }

    public boolean containsSubmitPackage() {
        return networkInfo.version() >= MIN_SUBMIT_PACKAGE_VERSION;
    }
}
