package com.sparrowwallet.frigate.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.electrum.ElectrumBlockHeader;
import com.sparrowwallet.frigate.index.Index;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.CoreAuthType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class BitcoindClient {
    private static final Logger log = LoggerFactory.getLogger(BitcoindClient.class);

    private final JsonRpcClient jsonRpcClient;
    private final Timer timer = new Timer(false);
    private final Index index;

    private NetworkInfo networkInfo;
    private String lastBlock;
    private ElectrumBlockHeader tip;

    private Exception lastPollException;

    private final Lock syncingLock = new ReentrantLock();
    private final Condition syncingCondition = syncingLock.newCondition();
    private boolean syncing;

    private final Lock indexingLock = new ReentrantLock();

    private boolean stopped;

    private final Map<Sha256Hash, Transaction> txCache = lruCache(10000);

    public BitcoindClient(Index index) {
        BitcoindTransport bitcoindTransport;

        Config config = Config.get();
        if((config.getCoreAuthType() == CoreAuthType.COOKIE || config.getCoreAuth() == null || config.getCoreAuth().length() < 2) && config.getCoreDataDir() != null) {
            bitcoindTransport = new BitcoindTransport(config.getCoreServer(), config.getCoreDataDir());
        } else if(config.getCoreAuth() != null) {
            bitcoindTransport = new BitcoindTransport(config.getCoreServer(), config.getCoreAuth());
        } else {
            throw new ConfigurationException("Bitcoin Core data folder or user and password is required");
        }

        this.jsonRpcClient = new JsonRpcClient(bitcoindTransport);
        this.index = index;
    }

    public void initialize() {
        networkInfo = getBitcoindService().getNetworkInfo();

        BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
        VerboseBlockHeader blockHeader = getBitcoindService().getBlockHeader(blockchainInfo.bestblockhash());
        tip = blockHeader.getBlockHeader();
        timer.schedule(new PollTask(), 5000, 5000);

        if(blockchainInfo.initialblockdownload() && networkInfo.networkactive()) {
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
        updateIndex();
    }

    private void updateIndex() {
        if(indexingLock.tryLock()) {
            try {
                for(int i = index.getLastBlockIndexed() + 1; i <= tip.height(); i++) {
                    String blockHash = getBitcoindService().getBlockHash(i);
                    String blockHex = (String)getBitcoindService().getBlock(blockHash, 0);
                    Block block = new Block(Utils.hexToBytes(blockHex));

                    Map<BlockTransaction, byte[]> eligibleTransactions = new LinkedHashMap<>();
                    Map<HashIndex, TransactionOutput> spentOutputs = new HashMap<>();
                    for(Transaction tx : block.getTransactions()) {
                        txCache.put(tx.getTxId(), tx);

                        if(!tx.isCoinBase() && SilentPaymentUtils.containsTaprootOutput(tx)) {
                            for(TransactionInput txInput : tx.getInputs()) {
                                HashIndex hashIndex = new HashIndex(txInput.getOutpoint().getHash(), txInput.getOutpoint().getIndex());
                                Transaction spentTx = getTransaction(hashIndex.getHash());
                                spentOutputs.put(hashIndex, spentTx.getOutputs().get((int)hashIndex.getIndex()));
                            }

                            byte[] tweak = SilentPaymentUtils.getTweak(tx, spentOutputs);
                            if(tweak != null) {
                                BlockTransaction blkTx = new BlockTransaction(tx.getTxId(), i, block.getBlockHeader().getTimeAsDate(), 0L, tx, block.getHash());
                                eligibleTransactions.put(blkTx, tweak);
                            }
                        }
                    }

                    if(!eligibleTransactions.isEmpty()) {
                        index.addToIndex(eligibleTransactions);
                    }
                }
            } finally {
                indexingLock.unlock();
            }
        }
    }

    public void stop() {
        timer.cancel();
        stopped = true;
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

    private Transaction getTransaction(Sha256Hash txid) {
        Transaction tx = txCache.get(txid);
        if(tx == null) {
            String txHex = (String)getBitcoindService().getRawTransaction(txid.toString(), false);
            tx = new Transaction(Utils.hexToBytes(txHex));
            txCache.put(txid, tx);
        }

        return tx;
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
                        log.info("Reorg detected, block height " + tip.height() + " was " + lastBlock + " and now is " + blockhash);
                        lastBlock = null;
                    }
                }

                BlockchainInfo blockchainInfo = getBitcoindService().getBlockchainInfo();
                String currentBlock = lastBlock;

                if(currentBlock == null || !currentBlock.equals(blockchainInfo.bestblockhash())) {
                    VerboseBlockHeader blockHeader = getBitcoindService().getBlockHeader(blockchainInfo.bestblockhash());
                    tip = blockHeader.getBlockHeader();
                    log.info("New block height " + tip.height());
                    Frigate.getEventBus().post(tip);
                    updateIndex();
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

    private boolean isEmptyBlockchain(BlockchainInfo blockchainInfo) {
        return blockchainInfo.blocks() == 0 && blockchainInfo.getProgressPercent() == 100;
    }

    public static <K,V> Map<K,V> lruCache(final int maxSize) {
        return new LinkedHashMap<K, V>(maxSize*4/3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }
}
