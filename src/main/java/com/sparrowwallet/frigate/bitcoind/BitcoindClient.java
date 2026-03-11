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
import com.sparrowwallet.frigate.bitcoind.reader.FlatFileBlockDataSource;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BitcoindClient {
    private static final Logger log = LoggerFactory.getLogger(BitcoindClient.class);

    public static final int DEFAULT_SCRIPT_PUB_KEY_CACHE_SIZE = 10000000;
    private static final int MAX_REORG_DEPTH = 10;
    private static final int CONCURRENT_THRESHOLD = 100;
    public static final int MIN_SUBMIT_PACKAGE_VERSION = 280000;

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

    private final BlockDataSource blockDataSource;
    private final Map<HashIndex, byte[]> scriptPubKeyCache;
    private final Set<Sha256Hash> mempoolTxIds = new HashSet<>();
    private final RecentBlocksMap recentBlocksMap = new RecentBlocksMap(MAX_REORG_DEPTH);

    public BitcoindClient(Index blocksIndex, Index mempoolIndex) {
        BitcoindTransport bitcoindTransport;

        Config config = Config.get();
        Server coreServer = config.getCoreServer();
        if(coreServer == null) {
            coreServer = new Server("http://127.0.0.1:" + Network.get().getDefaultPort());
            Config.get().setCoreServer(coreServer);
        }

        CoreAuthType coreAuthType = config.getCoreAuthType();
        if(coreAuthType == null) {
            coreAuthType = CoreAuthType.COOKIE;
            Config.get().setCoreAuthType(coreAuthType);
        }

        File coreDataDir = config.getCoreDataDir();
        if(coreDataDir == null) {
            coreDataDir = getDefaultCoreDataDir();
            Config.get().setCoreDataDir(coreDataDir);
        }

        String coreAuth = config.getCoreAuth();
        if(coreAuth == null) {
            coreAuth = "user:password";
            Config.get().setCoreAuth(coreAuth);
        }

        if(coreAuthType == CoreAuthType.COOKIE || coreAuth.length() < 2) {
            bitcoindTransport = new BitcoindTransport(coreServer, coreDataDir);
        } else {
            bitcoindTransport = new BitcoindTransport(coreServer, coreAuth);
        }

        this.jsonRpcClient = new JsonRpcClient(bitcoindTransport);
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;

        Integer cacheSize = Config.get().getScriptPubKeyCacheSize();
        if(cacheSize == null) {
            cacheSize = DEFAULT_SCRIPT_PUB_KEY_CACHE_SIZE;
            Config.get().setScriptPubKeyCacheSize(cacheSize);
        }
        this.scriptPubKeyCache = lruCache(cacheSize);

        BlockDataSource dataSource = null;
        Path coreDataDirPath = coreDataDir.toPath();
        if(FlatFileBlockDataSource.isAvailable(coreDataDirPath)) {
            try {
                Path blocksDir = FlatFileBlockDataSource.resolveBlocksDir(coreDataDirPath);
                Path indexDir = blocksDir.resolve("index");
                dataSource = new FlatFileBlockDataSource(blocksDir, indexDir, scriptPubKeyCache);
                log.info("Using flat file block data source");
            } catch(IOException e) {
                log.warn("Flat file block index available but failed to load, falling back to RPC", e);
            }
        }
        if(dataSource == null) {
            dataSource = new RpcBlockDataSource(getBitcoindService(), scriptPubKeyCache);
        }
        this.blockDataSource = dataSource;
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
        int startHeight = blocksIndex.getLastBlockIndexed() + 1;
        int endHeight = Math.min(tip.height(), blockDataSource.getAvailableHeight());
        log.info("Initializing indexes...");
        long startTime = System.nanoTime();
        updateBlocksIndex();
        int blocksIndexed = endHeight - startHeight + 1;
        if(blocksIndexed > 0) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            double blocksPerSec = blocksIndexed / (elapsedMs / 1000.0);
            log.info("Indexed {} blocks ({} to {}) in {}.{}s ({} blocks/sec)", blocksIndexed, startHeight, endHeight, elapsedMs / 1000, String.format("%03d", elapsedMs % 1000), String.format("%.1f", blocksPerSec));
        }
        updateMempoolIndex();
    }

    private synchronized void updateBlocksIndex() {
        int startHeight = blocksIndex.getLastBlockIndexed() + 1;
        int maxHeight = Math.min(tip.height(), blockDataSource.getAvailableHeight());

        if(maxHeight - startHeight + 1 >= CONCURRENT_THRESHOLD && blockDataSource instanceof FlatFileBlockDataSource) {
            updateBlocksIndexConcurrent(startHeight, maxHeight);
        } else {
            updateBlocksIndexSequential(startHeight, maxHeight);
        }
    }

    private void updateBlocksIndexSequential(int startHeight, int maxHeight) {
        for(int i = startHeight; i <= maxHeight; i++) {
            BlockWithSpentOutputs blockData = blockDataSource.getBlockForIndexing(i);
            blockDataSource.populateCache(blockData);
            Block block = blockData.block();
            Map<HashIndex, Script> spentScriptPubKeys = blockData.spentScriptPubKeys();

            if(i > maxHeight - MAX_REORG_DEPTH) {
                recentBlocksMap.put(i, blockData.blockHash());
            }

            Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
            for(Transaction tx : block.getTransactions()) {
                if(!tx.isCoinBase() && ScriptUtils.containsTaprootOutput(tx)) {
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

        if(maxHeight >= startHeight) {
            blocksIndex.setLastBlockIndexed(maxHeight);
        }
    }

    private void updateBlocksIndexConcurrent(int startHeight, int maxHeight) {
        int threads = Runtime.getRuntime().availableProcessors();
        int batchSize = threads * 2;
        AtomicInteger counter = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "block-indexer-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        try {
            int totalBlocks = maxHeight - startHeight + 1;
            int blocksProcessed = 0;
            long lastLogTime = System.nanoTime();

            for(int batchStart = startHeight; batchStart <= maxHeight; batchStart += batchSize) {
                int batchEnd = Math.min(batchStart + batchSize - 1, maxHeight);

                List<Future<BlockIndexResult>> futures = new ArrayList<>();
                for(int h = batchStart; h <= batchEnd; h++) {
                    final int height = h;
                    futures.add(executor.submit(() -> processBlock(height)));
                }

                for(int idx = 0; idx < futures.size(); idx++) {
                    int height = batchStart + idx;
                    try {
                        BlockIndexResult result = futures.get(idx).get();

                        blockDataSource.populateCache(result.blockData());
                        if(height > maxHeight - MAX_REORG_DEPTH) {
                            recentBlocksMap.put(height, result.blockHash());
                        }
                        if(!result.eligibleTransactions().isEmpty()) {
                            blocksIndex.addToIndex(result.eligibleTransactions());
                        }
                    } catch(ExecutionException e) {
                        throw new RuntimeException("Failed to index block " + height, e.getCause());
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during block indexing", e);
                    }
                }

                blocksProcessed += (batchEnd - batchStart + 1);
                long now = System.nanoTime();
                if(now - lastLogTime >= 30_000_000_000L) {
                    log.info("Indexing progress: {} / {} blocks (height {})", blocksProcessed, totalBlocks, batchEnd);
                    lastLogTime = now;
                }
            }

            blocksIndex.setLastBlockIndexed(maxHeight);
        } finally {
            executor.shutdownNow();
        }
    }

    private BlockIndexResult processBlock(int height) {
        BlockWithSpentOutputs blockData = blockDataSource.getBlockForIndexing(height);
        Block block = blockData.block();
        Map<HashIndex, Script> spentScriptPubKeys = blockData.spentScriptPubKeys();

        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        for(Transaction tx : block.getTransactions()) {
            if(!tx.isCoinBase() && ScriptUtils.containsTaprootOutput(tx)) {
                byte[] tweak = SilentPaymentUtils.getTweak(tx, spentScriptPubKeys, false);
                if(tweak != null) {
                    BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), height, block.getBlockHeader().getTimeAsDate(), 0L, tx, block.getHash());
                    eligibleTransactions.put(blkTx, SilentPaymentUtils.getSecp256k1PubKey(tweak));
                }
            }
        }

        return new BlockIndexResult(height, blockData.blockHash(), blockData, eligibleTransactions);
    }

    private synchronized void updateMempoolIndex() {
        BitcoindClientService bitcoindService = getBitcoindService();
        HexFormat hexFormat = HexFormat.of();

        Set<Sha256Hash> currentMempoolTxids = bitcoindService.getRawMempool();
        Set<Sha256Hash> removedTxids = new HashSet<>(mempoolTxIds);
        removedTxids.removeAll(currentMempoolTxids);
        Set<Sha256Hash> addedTxids = new HashSet<>(currentMempoolTxids);
        addedTxids.removeAll(mempoolTxIds);

        Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
        Map<HashIndex, Script> spentScriptPubKeys = new HashMap<>();

        for(Sha256Hash addedTxid : addedTxids) {
            try {
                String txHex = (String)getBitcoindService().getRawTransaction(addedTxid.toString(), false);
                Transaction tx = new Transaction(hexFormat.parseHex(txHex));
                for(int outputIndex = 0; outputIndex < tx.getOutputs().size(); outputIndex++) {
                    byte[] scriptPubKeyBytes = tx.getOutputs().get(outputIndex).getScriptBytes();
                    addtoScriptPubKeyCache(tx.getTxId(), outputIndex, scriptPubKeyBytes);
                }

                if(!tx.isCoinBase() && ScriptUtils.containsTaprootOutput(tx)) {
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
                //ignore, transaction removed from mempool
            }
        }

        if(!removedTxids.isEmpty()) {
            mempoolIndex.removeFromIndex(removedTxids);
        }
        if(!eligibleTransactions.isEmpty()) {
            mempoolIndex.addToIndex(eligibleTransactions);
        }

        mempoolTxIds.removeAll(removedTxids);
        mempoolTxIds.addAll(addedTxids);
    }

    public void stop() {
        timer.cancel();
        stopped = true;
        try {
            blockDataSource.close();
        } catch(IOException e) {
            log.warn("Error closing block data source", e);
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

                        Frigate.getEventBus().post(new BlockReorgEvent(reorgStartHeight));
                        blocksIndex.removeFromIndex(reorgStartHeight + 1);
                        updateBlocksIndex();

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

                updateMempoolIndex();

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
        if(ScriptUtils.getValidScriptType(scriptPubKeyBytes) != null) {
            scriptPubKeyCache.put(hashIndex, scriptPubKeyBytes);
        } else {
            scriptPubKeyCache.put(hashIndex, new byte[0]);
        }
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
