package com.sparrowwallet.frigate.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.github.arteam.simplejsonrpc.client.exception.JsonRpcBatchException;
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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BitcoindClient {
    private static final Logger log = LoggerFactory.getLogger(BitcoindClient.class);

    private static final int MAX_REORG_DEPTH = 10;
    private static final int MIN_GETBLOCK_VERBOSE_VERSION = 250000;
    public static final int MIN_SUBMIT_PACKAGE_VERSION = 280000;

    private static final int FLUSH_BATCH_SIZE = 50;
    private static final long FLUSH_DEBOUNCE_MS = 100;
    private static final long ZMQ_MEMPOOL_DIFF_INTERVAL_MS = 30_000;
    private static final long POLL_MEMPOOL_DIFF_INTERVAL_MS = 5_000;
    private static final long ZMQ_STALENESS_THRESHOLD_MS = 60_000;

    private final BitcoindTransport bitcoindTransport;
    private final JsonRpcClient jsonRpcClient;
    private final AtomicLong rpcIdCounter = new AtomicLong();
    private final Timer timer = new Timer(true);
    private final Index blocksIndex;
    private final Index mempoolIndex;

    private NetworkInfo networkInfo;
    private String lastBlock;
    private volatile ElectrumBlockHeader tip;
    private volatile boolean useGetBlockVerbose;

    private Exception lastPollException;

    private final Lock syncingLock = new ReentrantLock();
    private final Condition syncingCondition = syncingLock.newCondition();
    private boolean syncing;

    private volatile boolean stopped;

    private volatile ZContext zmqContext;
    private volatile Thread zmqSubscriberThread;
    private volatile Thread zmqConsumerThread;
    private volatile long lastZmqMessageMs;
    private final BlockingQueue<MempoolSeqEvent> zmqQueue = new LinkedBlockingQueue<>(50_000);
    private final AtomicBoolean pollPending = new AtomicBoolean();
    private long lastMempoolDiffMs;

    private final Map<HashIndex, byte[]> scriptPubKeyCache;
    private final Set<Sha256Hash> mempoolTxIds = ConcurrentHashMap.newKeySet();
    private final RecentBlocksMap recentBlocksMap = new RecentBlocksMap(MAX_REORG_DEPTH);

    public BitcoindClient(Index blocksIndex, Index mempoolIndex) {
        this(blocksIndex, mempoolIndex, buildDefaultTransport());
    }

    BitcoindClient(Index blocksIndex, Index mempoolIndex, BitcoindTransport bitcoindTransport) {
        this.bitcoindTransport = bitcoindTransport;
        this.jsonRpcClient = new JsonRpcClient(bitcoindTransport);
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;

        int cacheSize = Config.get().getIndex().getCacheSizeEntries();
        this.scriptPubKeyCache = Collections.synchronizedMap(lruCache(cacheSize));
    }

    private static BitcoindTransport buildDefaultTransport() {
        Config.CoreConfig coreConfig = Config.get().getCore();

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
            return new BitcoindTransport(coreServer, coreDataDir);
        }

        return new BitcoindTransport(coreServer, coreAuth);
    }

    public void initialize() {
        networkInfo = getBitcoindService().getNetworkInfo();

        BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
        useGetBlockVerbose = networkInfo.version() >= MIN_GETBLOCK_VERBOSE_VERSION && !blockchainInfo.pruned();
        if(useGetBlockVerbose) {
            log.debug("Using getblock verbosity=3 (Bitcoin Core {})", networkInfo.version());
        }

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

        blocksIndex.repairOrphanTweaks();

        int markerHeight = blocksIndex.getLastBlockIndexed();
        byte[] storedHash = blocksIndex.getLastBlockHash();
        if(markerHeight > 0 && storedHash != null) {
            String currentHashHex = getBitcoindService().getBlockHash(markerHeight);
            byte[] currentHash = Sha256Hash.wrap(currentHashHex).getBytes();
            if(!Arrays.equals(storedHash, currentHash)) {
                int rollbackTo = Math.max(0, markerHeight - MAX_REORG_DEPTH);
                log.info("Stored block hash at height {} does not match bitcoind - rolling back to height {} and re-indexing", markerHeight, rollbackTo);
                blocksIndex.removeFromIndex(rollbackTo + 1);
            }
        }

        int startHeight = blocksIndex.getLastBlockIndexed() + 1;
        int endHeight = tip.height();
        int blocksToIndex = endHeight - startHeight + 1;
        if(blocksToIndex > 0) {
            log.info("Indexing {} blocks ({} to {})...", blocksToIndex, startHeight, endHeight);
        } else {
            log.info("Block index is up to date");
        }
        long startTime = System.currentTimeMillis();
        updateBlocksIndex();
        if(blocksToIndex > 0) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            double blocksPerSec = blocksToIndex / (elapsedMs / 1000.0);
            log.info("Indexed {} blocks in {}.{}s ({} blocks/sec)", blocksToIndex, elapsedMs / 1000, String.format(Locale.ROOT, "%03d", elapsedMs % 1000), String.format(Locale.ROOT, "%.1f", blocksPerSec));
        }
        blocksIndex.setSteadyState(true);
        updateMempoolIndex();
        lastMempoolDiffMs = System.currentTimeMillis();
        Frigate.getEventBus().post(tip);

        String zmqEndpoint = Config.get().getCore().getZmqSequenceEndpoint();
        boolean autoDiscoveryAttempted = false;
        boolean zmqUnsupported = false;
        if((zmqEndpoint == null || zmqEndpoint.isBlank()) && isLoopbackBitcoind()) {
            autoDiscoveryAttempted = true;
            ZmqDiscovery discovery = discoverZmqSequenceEndpoint();
            zmqEndpoint = discovery.endpoint();
            zmqUnsupported = discovery.unsupported();
        }
        if(zmqEndpoint != null && !zmqEndpoint.isBlank()) {
            startZmqSequenceSubscriber(zmqEndpoint);
        } else {
            long pollSeconds = POLL_MEMPOOL_DIFF_INTERVAL_MS / 1000;
            String fix = zmqUnsupported ? "This Bitcoin Core binary was built without ZMQ support — install a release build, or rebuild with ZeroMQ (-DWITH_ZMQ=ON)"
                    : autoDiscoveryAttempted ? "Add -zmqpubsequence=tcp://127.0.0.1:28336 to bitcoin.conf" : "Enable -zmqpubsequence in bitcoin.conf and set zmqSequenceEndpoint in config.toml to match";
            if(Config.get().getServer().getBackendElectrumServer() != null) {
                log.warn("Polling bitcoind every {}s with backendElectrumServer configured — clients may briefly display incorrect amounts for silent payments transactions. {}", pollSeconds, fix);
            } else {
                log.warn("Polling bitcoind every {}s, for more responsive updates {}", pollSeconds, fix);
            }
        }
    }

    private synchronized void updateBlocksIndex() {
        HexFormat hexFormat = HexFormat.of();

        int startHeight = blocksIndex.getLastBlockIndexed() + 1;
        int maxHeight = tip.height();
        int totalBlocks = maxHeight - startHeight + 1;
        long lastLogTime = System.currentTimeMillis();

        for(int i = startHeight; i <= maxHeight; i++) {
            if(useGetBlockVerbose) {
                indexBlockVerbose(i, hexFormat);
            } else {
                indexBlockLegacy(i, hexFormat);
            }

            long now = System.currentTimeMillis();
            if(now - lastLogTime >= 30_000) {
                int blocksProcessed = i - startHeight + 1;
                double percent = 100.0 * blocksProcessed / totalBlocks;
                log.info("Indexing progress: {} / {} blocks ({}%, height {})", blocksProcessed, totalBlocks, String.format(Locale.ROOT, "%.1f", percent), i);
                lastLogTime = now;
            }
        }
    }

    private void indexBlockVerbose(int height, HexFormat hexFormat) {
        BitcoindClientService bitcoindService = getBitcoindService();
        String blockHash = bitcoindService.getBlockHash(height);
        if(height > tip.height() - MAX_REORG_DEPTH) {
            recentBlocksMap.put(height, blockHash);
        }

        VerboseBlock vb = bitcoindService.getVerboseBlock(blockHash, 3);

        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        for(VerboseBlock.VerboseTransaction vtx : vb.tx()) {
            //fast path: populate the scriptPubKey cache and check the taproot-output gate directly from the JSON
            Sha256Hash txid = Sha256Hash.wrap(vtx.txid());
            boolean hasTaprootOutput = false;
            for(VerboseBlock.VerboseVout vout : vtx.vout()) {
                byte[] spkBytes = hexFormat.parseHex(vout.scriptPubKey().hex());
                addtoScriptPubKeyCache(txid, vout.n(), spkBytes);
                if(isP2tr(spkBytes)) {
                    hasTaprootOutput = true;
                }
            }
            boolean isCoinbase = !vtx.vin().isEmpty() && vtx.vin().getFirst().isCoinbase();
            if(isCoinbase || !hasTaprootOutput) {
                continue;
            }

            //eligible: parse the hex now so getInputPubKeys has witness / scriptSig data (not carried in v3 JSON)
            Transaction tx = new Transaction(hexFormat.parseHex(vtx.hex()));
            Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
            boolean missingPrevout = false;
            for(int k = 0; k < vtx.vin().size(); k++) {
                VerboseBlock.VerboseVin vin = vtx.vin().get(k);
                if(vin.prevout() == null || vin.prevout().scriptPubKey() == null || vin.prevout().scriptPubKey().hex() == null) {
                    missingPrevout = true;
                    break;
                }
                TransactionInput in = tx.getInputs().get(k);
                HashIndex hi = new HashIndex(in.getOutpoint().getHash(), in.getOutpoint().getIndex());
                byte[] spkBytes = hexFormat.parseHex(vin.prevout().scriptPubKey().hex());
                spentScriptPubKeys.put(hi, new Script(spkBytes));
                addtoScriptPubKeyCache(hi.getHash(), (int)hi.getIndex(), spkBytes);
            }

            if(missingPrevout) {
                log.warn("getblock verbosity=3 returned no prevout for an input at height {} - falling back to legacy per-input fetch for this block", height);
                indexBlockLegacy(height, hexFormat);
                return;
            }

            byte[] tweak = SilentPaymentUtils.getTweak(tx, spentScriptPubKeys, false);
            if(tweak != null) {
                BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), height, new Date(vb.time() * 1000L), 0L, tx, Sha256Hash.wrap(vb.hash()));
                eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
            }
        }

        blocksIndex.addToIndex(height, Sha256Hash.wrap(blockHash).getBytes(), eligibleTransactions);
    }

    private void indexBlockLegacy(int height, HexFormat hexFormat) {
        BitcoindClientService bitcoindService = getBitcoindService();
        String blockHash = bitcoindService.getBlockHash(height);
        if(height > tip.height() - MAX_REORG_DEPTH) {
            recentBlocksMap.put(height, blockHash);
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
                    BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), height, block.getBlockHeader().getTimeAsDate(), 0L, tx, block.getHash());
                    eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
                }
            }
        }

        blocksIndex.addToIndex(height, Sha256Hash.wrap(blockHash).getBytes(), eligibleTransactions);
    }

    private synchronized void updateMempoolIndex() {
        HexFormat hexFormat = HexFormat.of();

        //snapshot before the RPC: if the ZMQ consumer removes a txid (R stream) after this point, this diff at worst re-issues a no-op removeFromIndex;
        //if an RBF re-broadcast lands in getRawMempool() in the same window, the re-broadcast's own A event re-ingests it - the diff is not what re-indexes an RBF re-broadcast
        Set<Sha256Hash> knownTxids = new HashSet<>(mempoolTxIds);
        Set<Sha256Hash> currentMempoolTxids = getBitcoindService().getRawMempool();
        Set<Sha256Hash> removedTxids = new HashSet<>(knownTxids);
        removedTxids.removeAll(currentMempoolTxids);
        Set<Sha256Hash> addedTxids = new HashSet<>(currentMempoolTxids);
        addedTxids.removeAll(knownTxids);

        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();

        try {
            Map<Sha256Hash, String> hexByTxid = fetchRawTxBatch(addedTxids);
            for(Sha256Hash addedTxid : addedTxids) {
                String txHex = hexByTxid.get(addedTxid);
                if(txHex == null) {
                    continue;
                }
                ingestMempoolTxFromHex(addedTxid, txHex, spentScriptPubKeys, eligibleTransactions, hexFormat);
            }

            if(!removedTxids.isEmpty()) {
                mempoolIndex.removeFromIndex(removedTxids);
            }
            if(!eligibleTransactions.isEmpty()) {
                mempoolIndex.addToIndex(0, null, eligibleTransactions);
            }
        } catch(RuntimeException e) {
            //nothing was committed to the index - drop the would-be-indexed txids so a later diff retries them
            eligibleTransactions.keySet().forEach(blkTx -> mempoolTxIds.remove(blkTx.getHash()));
            throw e;
        }

        mempoolTxIds.removeAll(removedTxids);
    }

    @SuppressWarnings("unchecked")
    private Map<Sha256Hash, String> fetchRawTxBatch(Collection<Sha256Hash> txids) {
        if(txids.isEmpty()) {
            return Map.of();
        }
        PagedBatchRequestBuilder<Sha256Hash, String> builder = PagedBatchRequestBuilder.create(bitcoindTransport, rpcIdCounter)
                .keysType(Sha256Hash.class).returnType(String.class);
        for(Sha256Hash txid : txids) {
            builder.add(txid, "getrawtransaction", txid.toString(), false);
        }
        try {
            return builder.execute();
        } catch(JsonRpcBatchException e) {
            return (Map<Sha256Hash, String>)e.getSuccesses();
        } catch(Exception e) {
            if(e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Error executing batched getrawtransaction request", e);
        }
    }

    private void ingestMempoolTx(Sha256Hash txid, Map<HashIndex, Script> spentScriptPubKeys, Map<BlockTransaction, byte[]> eligibleTransactions, HexFormat hexFormat) {
        String txHex;
        try {
            txHex = (String)getBitcoindService().getRawTransaction(txid.toString(), false);
        } catch(JsonRpcException e) {
            //transaction removed from mempool before we could fetch it
            return;
        }

        ingestMempoolTxFromHex(txid, txHex, spentScriptPubKeys, eligibleTransactions, hexFormat);
    }

    /**
     * Ingest an already-fetched mempool transaction. Idempotent — gated on an atomic insert into {@link #mempoolTxIds},
     * so a txid already ingested (via ZMQ or the safety-net diff) is skipped.
     */
    private void ingestMempoolTxFromHex(Sha256Hash txid, String txHex, Map<HashIndex, Script> spentScriptPubKeys, Map<BlockTransaction, byte[]> eligibleTransactions, HexFormat hexFormat) {
        if(!mempoolTxIds.add(txid)) {
            return;
        }

        try {
            Transaction tx = new Transaction(hexFormat.parseHex(txHex));
            for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                addtoScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
            }

            if(!tx.isCoinBase() && containsTaprootOutput(tx)) {
                BitcoindClientService bitcoindService = getBitcoindService();
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
        } catch(RuntimeException e) {
            //transient failure - drop the txid so a later diff re-ingests it
            mempoolTxIds.remove(txid);
            throw e;
        }
    }

    private boolean isLoopbackBitcoind() {
        Server coreServer = Config.get().getCore().getServerObj();
        String host = coreServer != null ? coreServer.getHost() : "127.0.0.1";
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch(UnknownHostException e) {
            return false;
        }
    }

    private ZmqDiscovery discoverZmqSequenceEndpoint() {
        try {
            for(ZmqNotification notification : getBitcoindService().getZmqNotifications()) {
                if("pubsequence".equals(notification.type()) && notification.address() != null) {
                    return new ZmqDiscovery(normaliseZmqAddress(notification.address()), false);
                }
            }
        } catch(JsonRpcException e) {
            if(e.getErrorMessage() != null && e.getErrorMessage().getCode() == -32601) {
                return new ZmqDiscovery(null, true);
            }
            log.debug("Could not auto-discover ZMQ endpoint from Bitcoin Core", e);
        } catch(Exception e) {
            log.debug("Could not auto-discover ZMQ endpoint from Bitcoin Core", e);
        }

        return new ZmqDiscovery(null, false);
    }

    private static String normaliseZmqAddress(String address) {
        //wildcard binds aren't usable as connect targets; substitute loopback
        if(address.startsWith("tcp://0.0.0.0:")) {
            return "tcp://127.0.0.1:" + address.substring("tcp://0.0.0.0:".length());
        }
        if(address.startsWith("tcp://[::]:")) {
            return "tcp://[::1]:" + address.substring("tcp://[::]:".length());
        }

        return address;
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
                log.info("Subscribed to ZMQ sequence publisher at {}", endpoint);

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
                    if(label == 'A' || label == 'R') {
                        Sha256Hash txid = Sha256Hash.wrap(Arrays.copyOf(body, 32));
                        if(!zmqQueue.offer(new MempoolSeqEvent(txid, label == 'R'))) {
                            log.warn("ZMQ mempool queue full, dropping {} for txid {} (safety-net diff will recover it)", label, txid);
                        }
                    } else if(label == 'C' || label == 'D') {
                        //block connect/disconnect: don't ingest here, just kick an immediate PollTask run - reorg detection, tip update and the forced mempool diff all live there
                        //pollPending coalesces bursts (testnet3 difficulty-reset storms, multi-block reorgs, post-outage catch-up) down to ~1 in-flight + 1 queued
                        if(!stopped && pollPending.compareAndSet(false, true)) {
                            timer.schedule(new PollTask(), 0);
                        }
                    }
                    //'R' fires for non-block removals only (RBF/eviction/expiry); mined txs produce 'C' (block connect) with no per-tx 'R'
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
                if(!stopped && lastZmqMessageMs == 0L && Network.get() == Network.MAINNET) {
                    log.warn("No ZMQ messages received from Bitcoin Core within 60s at {} - verify -zmqpubsequence is configured on this endpoint. " +
                            "Mempool ingestion latency will be up to {}s until ZMQ messages arrive", endpoint, ZMQ_MEMPOOL_DIFF_INTERVAL_MS / 1000);
                }
            }
        }, 60_000);
    }

    private void zmqConsumerLoop() {
        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        Set<Sha256Hash> removedTxids = new HashSet<>();
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();
        HexFormat hexFormat = HexFormat.of();

        while(!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                MempoolSeqEvent first = zmqQueue.poll(1, TimeUnit.SECONDS);
                if(first == null) {
                    continue;
                }

                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FLUSH_DEBOUNCE_MS);
                processSeqEvent(first, spentScriptPubKeys, eligibleTransactions, removedTxids, hexFormat);
                while(eligibleTransactions.size() + removedTxids.size() < FLUSH_BATCH_SIZE) {
                    long remaining = deadline - System.nanoTime();
                    if(remaining <= 0) {
                        break;
                    }
                    MempoolSeqEvent next = zmqQueue.poll(remaining, TimeUnit.NANOSECONDS);
                    if(next == null) {
                        break;
                    }
                    processSeqEvent(next, spentScriptPubKeys, eligibleTransactions, removedTxids, hexFormat);
                }

                //add before remove: if a txid somehow ended up in both batches (it shouldn't - see processSeqEvent), the net result is "absent", correct for an A-then-R
                if(!eligibleTransactions.isEmpty()) {
                    mempoolIndex.addToIndex(0, null, eligibleTransactions);
                    eligibleTransactions.clear();
                }
                if(!removedTxids.isEmpty()) {
                    mempoolIndex.removeFromIndex(removedTxids);
                    removedTxids.clear();
                }
                spentScriptPubKeys.clear();
            } catch(InterruptedException e) {
                return;
            } catch(Throwable t) {
                log.error("Error processing ZMQ mempool transactions", t);
                eligibleTransactions.keySet().forEach(blkTx -> mempoolTxIds.remove(blkTx.getHash()));
                eligibleTransactions.clear();
                mempoolTxIds.addAll(removedTxids);
                removedTxids.clear();
                spentScriptPubKeys.clear();
            }
        }
    }

    private void processSeqEvent(MempoolSeqEvent event, Map<HashIndex, Script> spentScriptPubKeys, Map<BlockTransaction, byte[]> eligibleTransactions, Set<Sha256Hash> removedTxids, HexFormat hexFormat) {
        if(event.removed()) {
            if(mempoolTxIds.remove(event.txid())) {
                removedTxids.add(event.txid());
                eligibleTransactions.keySet().removeIf(blkTx -> blkTx.getHash().equals(event.txid()));
            }
        } else {
            ingestMempoolTx(event.txid(), spentScriptPubKeys, eligibleTransactions, hexFormat);
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
            //clear at the start: a 'C' arriving while this run is in flight re-arms pollPending and schedules exactly one follow-up poll (which picks up anything connected during this run)
            pollPending.set(false);

            if(stopped) {
                timer.cancel();
            }

            try {
                boolean newBlock = false;
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
                        newBlock = true;
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
                    newBlock = true;
                }

                boolean zmqHealthy = zmqContext != null && lastZmqMessageMs != 0L && System.currentTimeMillis() - lastZmqMessageMs < ZMQ_STALENESS_THRESHOLD_MS;
                long mempoolDiffInterval = zmqHealthy ? ZMQ_MEMPOOL_DIFF_INTERVAL_MS : POLL_MEMPOOL_DIFF_INTERVAL_MS;
                //force the diff when a block was just connected/reorged: a batch of txids left the mempool at once (mined txs get no per-tx 'R'), evict them now rather than up to ZMQ_MEMPOOL_DIFF_INTERVAL_MS later
                if(newBlock || System.currentTimeMillis() - lastMempoolDiffMs >= mempoolDiffInterval) {
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
        if(isP2tr(scriptPubKey)) {
            return ScriptType.P2TR;
        }

        return null;
    }

    private static boolean isP2tr(byte[] scriptPubKey) {
        return scriptPubKey != null
                && scriptPubKey.length == 34
                && scriptPubKey[0] == (byte) 0x51   // OP_1
                && scriptPubKey[1] == (byte) 0x20;  // Push 32 bytes
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

    private record MempoolSeqEvent(Sha256Hash txid, boolean removed) {}

    private record ZmqDiscovery(String endpoint, boolean unsupported) {}
}
