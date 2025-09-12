package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBPreparedStatement;
import org.duckdb.QueryProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Index {
    private static final Logger log = LoggerFactory.getLogger(Index.class);
    public static final String DEFAULT_DB_FILENAME = "frigate.duckdb";
    private static final String TWEAK_TABLE = "tweak";
    public static final int HISTORY_PAGE_SIZE = 100;

    private final DbManager dbManager;
    private int lastBlockIndexed = -1;

    public Index(int startHeight, boolean inMemory) {
        lastBlockIndexed = Math.max(lastBlockIndexed, startHeight - 1);

        if(inMemory) {
            dbManager = new MemoryDbManager();
        } else {
            String dbUrl = Config.get().getDbUrl();
            List<String> readDbUrls = Config.get().getReadDbUrls();
            if(dbUrl != null && readDbUrls != null && !readDbUrls.isEmpty()) {
                dbManager = new ScalingDbManager(dbUrl, readDbUrls);
            } else if(dbUrl == null) {
                File dbFile = new File(Storage.getFrigateDbDir(), DEFAULT_DB_FILENAME);
                dbManager = new SingleDbManager(DbManager.DB_PREFIX + dbFile.getAbsolutePath());
            } else {
                dbManager = new SingleDbManager(dbUrl);
            }
        }

        try {
            dbManager.executeWrite(connection -> {
                try(Statement stmt = connection.createStatement()) {
                    return stmt.execute("CREATE TABLE IF NOT EXISTS " + TWEAK_TABLE + " (txid BLOB NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, outputs BIGINT[])");
                }
            });
        } catch(Exception e) {
            throw new ConfigurationException("Error initialising index", e);
        }
    }

    public void close() {
        dbManager.close();
    }

    public int getLastBlockIndexed() {
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT MAX(height) from " + TWEAK_TABLE)) {
                    ResultSet resultSet = statement.executeQuery();
                    return resultSet.next() ? Math.max(lastBlockIndexed, resultSet.getInt(1)) : lastBlockIndexed;
                }
            });
        } catch(Exception e) {
            log.error("Error getting last block indexed", e);
            return lastBlockIndexed;
        }
    }

    public void addToIndex(Map<BlockTransaction, byte[]> transactions) {
        if(dbManager.isShutdown()) {
            return;
        }

        int fromBlockHeight = lastBlockIndexed;
        try {
            lastBlockIndexed = dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TWEAK_TABLE + " VALUES (?, ?, ?, ?)")) {
                    int blockHeight = -1;

                    for(BlockTransaction blkTx : transactions.keySet()) {
                        statement.setBytes(1, blkTx.getTransaction().getTxId().getBytes());
                        statement.setInt(2, blkTx.getHeight());
                        statement.setObject(3, transactions.get(blkTx));

                        List<TransactionOutput> outputs = blkTx.getTransaction().getOutputs();
                        List<Long> hashPrefixes = new ArrayList<>();
                        for(TransactionOutput output : outputs) {
                            if(ScriptType.P2TR.isScriptType(output.getScript())) {
                                long hashPrefix = getHashPrefix(ScriptType.P2TR.getPublicKeyFromScript(output.getScript()).getPubKey(), 1);
                                hashPrefixes.add(hashPrefix);
                            }
                        }
                        statement.setArray(4, connection.createArrayOf("BIGINT", hashPrefixes.toArray()));
                        statement.addBatch();

                        blockHeight = Math.max(blockHeight, blkTx.getHeight());
                    }

                    statement.executeBatch();
                    if(fromBlockHeight < 0) {
                        log.info("Indexed " + transactions.size() + " mempool transactions");
                    } else if(blockHeight > 0) {
                        log.info("Indexed " + transactions.size() + " transactions to block height " + blockHeight);
                    }

                    return blockHeight;
                }
            });

            if(lastBlockIndexed <= 0) {
                Frigate.getEventBus().post(new SilentPaymentsMempoolIndexAdded(transactions.keySet().stream().map(blkTx -> blkTx.getTransaction().getTxId()).collect(Collectors.toSet())));
            } else {
                Frigate.getEventBus().post(new SilentPaymentsBlocksIndexUpdate(fromBlockHeight + 1, lastBlockIndexed, transactions.size()));
            }
        } catch(Exception e) {
            log.error("Error adding to index", e);
        }
    }

    public void removeFromIndex(int startHeight) {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE height >= ?")) {
                    statement.setInt(1, startHeight);
                    return statement.execute();
                }
            });
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public void removeFromIndex(Set<Sha256Hash> txIds) {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            dbManager.executeWrite(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE txid = ?")) {
                    for(Sha256Hash txId : txIds) {
                        statement.setBytes(1, txId.getBytes());
                        statement.addBatch();
                    }

                    statement.executeBatch();
                    return txIds.size();
                }
            });

            Frigate.getEventBus().post(new SilentPaymentsMempoolIndexRemoved(txIds));
        } catch(Exception e) {
            log.error("Error removing from index", e);
        }
    }

    public List<TxEntry> getHistoryAsync(SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        ConcurrentLinkedQueue<TxEntry> queue = new ConcurrentLinkedQueue<>();
        AtomicLong rowsProcessedStart = new AtomicLong(0L);

        try {
            dbManager.executeRead(connection -> {
                String sql = "SELECT txid, height FROM " + TWEAK_TABLE +
                        " WHERE list_contains(outputs, hash_prefix_to_int(secp256k1_ec_pubkey_combine([?, secp256k1_ec_pubkey_create(secp256k1_tagged_sha256('BIP0352/SharedSecret', secp256k1_ec_pubkey_tweak_mul(tweak_key, ?) || int_to_big_endian(0)))]), 1))";

                if(startHeight != null) {
                    sql += " AND height >= ?";
                }
                if(endHeight != null) {
                    sql += " AND height <= ?";
                }

                try(DuckDBPreparedStatement statement = connection.prepareStatement(sql).unwrap(DuckDBPreparedStatement.class)) {
                    if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                        return false;
                    }

                    statement.setBytes(1, scanAddress.getSpendKey().getPubKey());
                    statement.setBytes(2, scanAddress.getScanKey().getPrivKeyBytes());
                    if(startHeight != null) {
                        statement.setInt(3, startHeight);
                    }
                    if(endHeight != null) {
                        statement.setInt(startHeight == null ? 3 : 4, endHeight);
                    }
                    statement.setFetchSize(1);

                    try(ScheduledThreadPoolExecutor queryProgressExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQueryProgress-%d").build();
                        Thread t = namedThreadFactory.newThread(r);
                        t.setDaemon(true);
                        return t;
                    })) {
                        queryProgressExecutor.scheduleAtFixedRate(() -> {
                            try {
                                if(dbManager.isShutdown() || isUnsubscribed(scanAddress, subscriptionStatusRef)) {
                                    statement.cancel();
                                    queryProgressExecutor.shutdownNow();
                                    return;
                                }

                                QueryProgress queryProgress = statement.getQueryProgress();
                                if(queryProgress.getRowsProcessed() == queryProgress.getTotalRowsToProcess()) {
                                    return;
                                }

                                double progress = 0.0d;
                                if(rowsProcessedStart.get() == 0L && queryProgress.getRowsProcessed() > 0) {
                                    rowsProcessedStart.set(queryProgress.getRowsProcessed());
                                }
                                if(rowsProcessedStart.get() > 0L) {
                                    progress = (queryProgress.getRowsProcessed() - rowsProcessedStart.get()) / (double)(queryProgress.getTotalRowsToProcess() - rowsProcessedStart.get());
                                }

                                List<TxEntry> history = new ArrayList<>();
                                TxEntry entry;
                                while((entry = queue.poll()) != null) {
                                    history.add(entry);
                                    if(history.size() >= HISTORY_PAGE_SIZE) {
                                        Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                        history.clear();
                                    }
                                }
                                if(!history.isEmpty() || queryProgressExecutor.getTaskCount() % 5 == 0) {
                                    Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                    history.clear();
                                }
                            } catch(SQLException e) {
                                log.error("Error getting query progress", e);
                            }
                        }, 1, 1, TimeUnit.SECONDS);

                        ResultSet resultSet = statement.executeQuery();
                        while(resultSet.next()) {
                            byte[] txid = resultSet.getBytes(1);
                            int height = resultSet.getInt(2);
                            queue.offer(new TxEntry(height, 0, Utils.bytesToHex(txid)));
                        }
                    }
                }

                return true;
            });
        } catch(SQLTimeoutException e) {
            if(e.getMessage().startsWith("INTERRUPT Error")) {
                log.debug("Query cancelled", e);
            } else {
                log.error("Query timeout", e);
            }
            return Collections.emptyList();
        } catch(Exception e) {
            log.error("Error scanning index", e);
            return Collections.emptyList();
        }

        if(isUnsubscribed(scanAddress, subscriptionStatusRef)) {
            return Collections.emptyList();
        }

        List<TxEntry> history = new ArrayList<>();
        TxEntry entry;
        while((entry = queue.poll()) != null) {
            history.add(entry);
        }

        return history;
    }

    private static boolean isUnsubscribed(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        SubscriptionStatus status = subscriptionStatusRef.get();
        return status == null || !status.isConnected() || !status.isSilentPaymentsAddressSubscribed(scanAddress.toString());
    }

    public List<TweakEntry> getTweaksByHeight(int blockHeight) {
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT txid, tweak_key FROM " + TWEAK_TABLE + " WHERE height = ?")) {
                    statement.setInt(1, blockHeight);
                    List<TweakEntry> tweaks = new ArrayList<>();
                    
                    try(ResultSet resultSet = statement.executeQuery()) {
                        while(resultSet.next()) {
                            byte[] txid = resultSet.getBytes(1);
                            byte[] tweakKey = resultSet.getBytes(2);
                            tweaks.add(new TweakEntry(Utils.bytesToHex(txid), Utils.bytesToHex(tweakKey)));
                        }
                    }
                    
                    return tweaks;
                }
            });
        } catch(Exception e) {
            log.error("Error getting tweaks by height " + blockHeight, e);
            return Collections.emptyList();
        }
    }

    public static long getHashPrefix(byte[] hash, int offset) {
        if(hash.length < 8 + offset) {
            throw new IllegalArgumentException("Hash must be at least 8 bytes long from the offset");
        }

        long result = 0;
        // Process 8 bytes from the offset in big-endian order
        for (int i = offset; i < 8 + offset; i++) {
            result = (result << 8) | (hash[i] & 0xFF);
        }
        return result;
    }
}
