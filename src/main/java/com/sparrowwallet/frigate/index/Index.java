package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentUtils;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.electrum.SilentPaymentsTxEntry;
import com.sparrowwallet.frigate.io.ComputeBackend;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBPreparedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class Index {
    private static final Logger log = LoggerFactory.getLogger(Index.class);
    public static final String DEFAULT_DB_FILENAME = "frigate.duckdb";
    private static final String TWEAK_TABLE = "tweak";
    private static final String INDEXED_BLOCK_TABLE = "indexed_block";
    public static final int HISTORY_PAGE_SIZE = 100;

    private static final String AUDIT_SCAN_KEY_ENV = "FRIGATE_AUDIT_SCAN_KEY";
    private static final String AUDIT_SPEND_KEY_ENV = "FRIGATE_AUDIT_SPEND_KEY";

    private final DbManager dbManager;
    private final AtomicInteger lastBlockIndexed = new AtomicInteger(-1);
    private final int batchSize;
    private final ECKey auditScanKey;
    private final ECKey auditSpendKey;
    private volatile boolean steadyState = false;

    public Index(int startHeight, boolean inMemory, int batchSize) {
        lastBlockIndexed.accumulateAndGet(startHeight - 1, Math::max);
        this.batchSize = batchSize;

        String scanKeyHex = System.getenv(AUDIT_SCAN_KEY_ENV);
        String spendKeyHex = System.getenv(AUDIT_SPEND_KEY_ENV);
        if(scanKeyHex != null && spendKeyHex != null) {
            this.auditScanKey = ECKey.fromPrivate(Utils.hexToBytes(scanKeyHex));
            this.auditSpendKey = ECKey.fromPublicOnly(Utils.hexToBytes(spendKeyHex));
            log.warn("Scan audit mode enabled — output prefixes will be computed for the provided wallet keys");
        } else {
            this.auditScanKey = null;
            this.auditSpendKey = null;
        }

        if(inMemory) {
            dbManager = new MemoryDbManager();
        } else {
            String dbUrl = Config.get().getDatabase().getUrl();
            List<String> readDbUrls = Config.get().getDatabase().getReadUrls();
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
                    stmt.execute("CREATE TABLE IF NOT EXISTS " + TWEAK_TABLE + " (txid BLOB NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, outputs BIGINT[])");
                    stmt.execute("CREATE TABLE IF NOT EXISTS " + INDEXED_BLOCK_TABLE + " (height INTEGER NOT NULL, block_hash BLOB NOT NULL, singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton))");
                    return true;
                }
            });
            seedIndexedBlockIfEmpty();
        } catch(Exception e) {
            throw new ConfigurationException("Error initialising index", e);
        }

        if(!inMemory) {
            checkGpuBackend();
        }
    }

    private void seedIndexedBlockIfEmpty() throws SQLException, InterruptedException {
        dbManager.executeWrite(connection -> {
            try(Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + INDEXED_BLOCK_TABLE)) {
                if(rs.next() && rs.getInt(1) == 0) {
                    try(ResultSet maxRs = stmt.executeQuery("SELECT MAX(height) FROM " + TWEAK_TABLE)) {
                        if(maxRs.next() && maxRs.getObject(1) != null) {
                            int maxHeight = maxRs.getInt(1);
                            if(maxHeight > 0) {
                                try(PreparedStatement ins = connection.prepareStatement("INSERT INTO " + INDEXED_BLOCK_TABLE + " (height, block_hash) VALUES (?, ?)")) {
                                    ins.setInt(1, maxHeight);
                                    ins.setBytes(2, new byte[0]);
                                    ins.executeUpdate();
                                }
                            }
                        }
                    }
                }

                return true;
            }
        });
    }

    private void checkGpuBackend() {
        ComputeBackend computeBackend = Config.get().getScan().getComputeBackendEnum();
        if(computeBackend == ComputeBackend.CPU) {
            return;
        }

        try {
            String backend = dbManager.executeRead(connection -> {
                try(Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT ufsecp_backend()")) {
                    return rs.next() ? rs.getString(1) : "unknown";
                }
            });

            if(backend.startsWith("cpu")) {
                if(computeBackend == ComputeBackend.GPU) {
                    throw new ConfigurationException("No GPU detected, but \"computeBackend\" is set to \"GPU\". Set to \"AUTO\" or \"CPU\", or install a supported GPU.");
                }
                log.info("Using CPU backend for scanning (no GPU detected)");
            } else {
                log.info("Using {} backend for scanning", backend);
            }
        } catch(Exception e) {
            log.warn("Could not detect GPU backend", e);
        }
    }

    private double pollScanProgress(byte[] scanKeyBytes) {
        try {
            return dbManager.executeRead(progressConnection -> {
                try(PreparedStatement progressStmt = progressConnection.prepareStatement("SELECT ufsecp_progress(?)")) {
                    progressStmt.setBytes(1, scanKeyBytes);
                    ResultSet rs = progressStmt.executeQuery();
                    if(rs.next()) {
                        double pct = rs.getDouble(1);
                        if(pct < 0.0d) {
                            return 0.0d;
                        }
                        return Math.min(pct / 100.0d, 1.0d);
                    }
                    return 0.0d;
                }
            });
        } catch(Exception e) {
            return 0.0d;
        }
    }

    public void close() {
        dbManager.close();
    }

    public void setSteadyState(boolean steadyState) {
        this.steadyState = steadyState;
    }

    public void repairOrphanTweaks() {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            int deleted = dbManager.executeWrite(connection -> {
                try(PreparedStatement ps = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE height > (SELECT height FROM " + INDEXED_BLOCK_TABLE + ")")) {
                    return ps.executeUpdate();
                }
            });
            if(deleted > 0) {
                log.info("Removed {} orphan tweak rows above the indexed-block marker (interrupted shutdown recovery)", deleted);
            }
        } catch(Exception e) {
            log.error("Error repairing orphan tweak rows", e);
        }
    }

    public int getLastBlockIndexed() {
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT height FROM " + INDEXED_BLOCK_TABLE)) {
                    ResultSet resultSet = statement.executeQuery();
                    return resultSet.next() ? Math.max(lastBlockIndexed.get(), resultSet.getInt(1)) : lastBlockIndexed.get();
                }
            });
        } catch(Exception e) {
            log.error("Error getting last block indexed", e);
            return lastBlockIndexed.get();
        }
    }

    public byte[] getLastBlockHash() {
        try {
            return dbManager.executeRead(connection -> {
                try(PreparedStatement statement = connection.prepareStatement("SELECT block_hash FROM " + INDEXED_BLOCK_TABLE)) {
                    ResultSet resultSet = statement.executeQuery();
                    if(!resultSet.next()) {
                        return null;
                    }
                    byte[] hash = resultSet.getBytes(1);
                    return (hash == null || hash.length == 0) ? null : hash;
                }
            });
        } catch(Exception e) {
            log.error("Error getting last block hash", e);
            return null;
        }
    }

    public void addToIndex(int height, byte[] blockHash, Map<BlockTransaction, byte[]> transactions) {
        if(dbManager.isShutdown()) {
            return;
        }

        int fromBlockHeight = lastBlockIndexed.get();
        try {
            int newLastBlockIndexed = dbManager.executeWrite(connection -> {
                if(!transactions.isEmpty()) {
                    DuckDBConnection duckDBConnection = (DuckDBConnection)connection;
                    try(DuckDBAppender appender = duckDBConnection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, TWEAK_TABLE)) {
                        int blockHeight = -1;

                        for(BlockTransaction blkTx : transactions.keySet()) {
                            appender.beginRow();
                            appender.append(blkTx.getTransaction().getTxId().getBytes());
                            appender.append(blkTx.getHeight());
                            appender.append(transactions.get(blkTx));

                            List<Long> hashPrefixes = new ArrayList<>();
                            if(auditScanKey != null) {
                                long hashPrefix = getAuditHashPrefix(transactions, blkTx);
                                hashPrefixes.add(hashPrefix);
                            } else {
                                List<TransactionOutput> outputs = blkTx.getTransaction().getOutputs();
                                for(TransactionOutput output : outputs) {
                                    if(ScriptType.P2TR.isScriptType(output.getScript())) {
                                        long hashPrefix = getHashPrefix(ScriptType.P2TR.getPublicKeyFromScript(output.getScript()).getPubKey(), 1);
                                        hashPrefixes.add(hashPrefix);
                                    }
                                }
                            }
                            appender.append(hashPrefixes.stream().mapToLong(Long::longValue).toArray());
                            appender.endRow();

                            blockHeight = Math.max(blockHeight, blkTx.getHeight());
                        }

                        if(blockHeight <= 0 && lastBlockIndexed.get() < 0) {
                            log.info("Indexed " + transactions.size() + " mempool transactions");
                        } else if(blockHeight > 0) {
                            String msg = "Indexed " + transactions.size() + " transactions to block height " + blockHeight;
                            if(steadyState) {
                                log.info(msg);
                            } else {
                                log.debug(msg);
                            }
                        }
                    }
                }

                if(height > 0 && blockHash != null) {
                    try(PreparedStatement ps = connection.prepareStatement("INSERT INTO " + INDEXED_BLOCK_TABLE + " (height, block_hash) VALUES (?, ?) " +
                            "ON CONFLICT (singleton) DO UPDATE SET height = excluded.height, block_hash = excluded.block_hash")) {
                        ps.setInt(1, height);
                        ps.setBytes(2, blockHash);
                        ps.executeUpdate();
                    }
                }

                return height;
            });
            lastBlockIndexed.set(newLastBlockIndexed);

            if(transactions.isEmpty()) {
                //empty block: marker advanced, but nothing to notify on
            } else if(newLastBlockIndexed <= 0) {
                Frigate.getEventBus().post(new SilentPaymentsMempoolIndexAdded(transactions.keySet().stream().map(blkTx -> blkTx.getTransaction().getTxId()).collect(Collectors.toSet())));
            } else {
                Frigate.getEventBus().post(new SilentPaymentsBlocksIndexUpdate(fromBlockHeight + 1, newLastBlockIndexed, transactions.size()));
            }
        } catch(Exception e) {
            log.error("Error adding to index", e);
        }
    }

    private long getAuditHashPrefix(Map<BlockTransaction, byte[]> transactions, BlockTransaction blkTx) {
        byte[] tweakKeyBytes = transactions.get(blkTx);
        ECKey tweakKey = ECKey.fromPublicOnly(compressRawKey(tweakKeyBytes));
        ECKey sharedSecret = tweakKey.multiply(auditScanKey.getPrivKey(), true);
        byte[] ser37 = new byte[37];
        System.arraycopy(sharedSecret.getPubKey(true), 0, ser37, 0, 33);
        byte[] t_k = Utils.taggedHash("BIP0352/SharedSecret", ser37);
        ECKey tkG = ECKey.fromPublicOnly(ECKey.publicKeyFromPrivate(new BigInteger(1, t_k), true));
        ECKey P0 = auditSpendKey.add(tkG, true);
        return getHashPrefix(P0.getPubKeyXCoord(), 0);
    }

    public void removeFromIndex(int startHeight) {
        if(dbManager.isShutdown()) {
            return;
        }

        try {
            dbManager.executeWrite(connection -> {
                boolean prevAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    try(PreparedStatement deleteTweak = connection.prepareStatement("DELETE FROM " + TWEAK_TABLE + " WHERE height >= ?")) {
                        deleteTweak.setInt(1, startHeight);
                        deleteTweak.execute();
                    }
                    //hash applied to the original (higher) marker height — clear it so the startup hash check is a no-op until the first re-indexed block writes a real hash
                    try(PreparedStatement updateMarker = connection.prepareStatement("INSERT INTO " + INDEXED_BLOCK_TABLE + " (height, block_hash) VALUES (?, ?) " +
                            "ON CONFLICT (singleton) DO UPDATE SET height = excluded.height, block_hash = excluded.block_hash")) {
                        updateMarker.setInt(1, Math.max(0, startHeight - 1));
                        updateMarker.setBytes(2, new byte[0]);
                        updateMarker.executeUpdate();
                    }
                    connection.commit();
                    return true;
                } catch(SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(prevAutoCommit);
                }
            });
            lastBlockIndexed.accumulateAndGet(startHeight - 1, Math::min);
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

    public List<SilentPaymentsTxEntry> getHistoryAsync(SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, Set<Sha256Hash> mempoolTxids, WeakReference<SubscriptionStatus> subscriptionStatusRef, BooleanSupplier cancelled, boolean isHistorical) {
        if(mempoolTxids != null && mempoolTxids.isEmpty()) {
            return Collections.emptyList();
        }

        ConcurrentLinkedQueue<SilentPaymentsTxEntry> queue = new ConcurrentLinkedQueue<>();
        byte[] scanKeyBytes = Utils.reverseBytes(scanAddress.getScanKey().getPrivKeyBytes());

        try {
            dbManager.executeRead(connection -> {
                String sql = getSql(subscription, startHeight, endHeight, mempoolTxids, isHistorical);

                try(DuckDBPreparedStatement statement = connection.prepareStatement(sql).unwrap(DuckDBPreparedStatement.class)) {
                    if(isUnsubscribed(scanAddress, subscriptionStatusRef) || cancelled.getAsBoolean()) {
                        return false;
                    }

                    Long totalRows = isHistorical ? getInputRowCount(connection, startHeight, endHeight) : null;
                    bindParameters(statement, scanAddress, subscription, startHeight, endHeight, mempoolTxids, isHistorical, totalRows);

                    if(isHistorical) {
                        try(ScheduledThreadPoolExecutor queryProgressExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                            ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQueryProgress-%d").build();
                            Thread t = namedThreadFactory.newThread(r);
                            t.setDaemon(true);
                            return t;
                        })) {
                            queryProgressExecutor.scheduleAtFixedRate(() -> {
                                try {
                                    if(queryProgressExecutor.isShutdown() || dbManager.isShutdown() || isUnsubscribed(scanAddress, subscriptionStatusRef) || cancelled.getAsBoolean()) {
                                        statement.cancel();
                                        queryProgressExecutor.shutdownNow();
                                        return;
                                    }

                                    double progress = pollScanProgress(scanKeyBytes);

                                    if(queryProgressExecutor.isShutdown()) {
                                        return;
                                    }

                                    List<SilentPaymentsTxEntry> history = new ArrayList<>();
                                    SilentPaymentsTxEntry entry;
                                    while((entry = queue.poll()) != null) {
                                        history.add(entry);
                                        if(history.size() >= HISTORY_PAGE_SIZE) {
                                            Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                            history.clear();
                                        }
                                    }
                                    Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, progress, new ArrayList<>(history), subscriptionStatusRef.get()));
                                    history.clear();
                                } catch(Exception e) {
                                    log.error("Error getting query progress", e);
                                }
                            }, 5, 5, TimeUnit.SECONDS);

                            try {
                                drainResultSet(statement.executeQuery(), queue);
                            } finally {
                                //interrupt any parked progress poll - the implicit close() doesn't, which deadlocks against a queued writer
                                queryProgressExecutor.shutdownNow();
                            }
                        }
                    } else {
                        drainResultSet(statement.executeQuery(), queue);
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

        if(isUnsubscribed(scanAddress, subscriptionStatusRef) || cancelled.getAsBoolean()) {
            return Collections.emptyList();
        }

        List<SilentPaymentsTxEntry> history = new ArrayList<>();
        SilentPaymentsTxEntry entry;
        while((entry = queue.poll()) != null) {
            history.add(entry);
        }

        return history;
    }

    private void drainResultSet(ResultSet resultSet, ConcurrentLinkedQueue<SilentPaymentsTxEntry> queue) throws SQLException {
        while(resultSet.next()) {
            byte[] txid = resultSet.getBytes(1);
            byte[] tweak_key = compressRawKey(resultSet.getBytes(2));
            int height = resultSet.getInt(3);
            queue.offer(new SilentPaymentsTxEntry(height, Utils.bytesToHex(txid), Utils.bytesToHex(tweak_key)));
        }
    }

    private String getSql(SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, Set<Sha256Hash> mempoolTxids, boolean isHistorical) {
        String labelsStr = "[" + String.join(", ", Collections.nCopies(subscription.labels().length, "?")) + "]";

        String sql = "SELECT txid, tweak_key, height FROM ufsecp_scan((SELECT txid, height, tweak_key, outputs FROM " + TWEAK_TABLE
                + buildWhereClause(startHeight, endHeight, mempoolTxids)
                + "), ?, ?, " + labelsStr + ", batch_size := ?";

        ComputeBackend backend = resolveBackend(isHistorical);
        if(backend != ComputeBackend.AUTO) {
            sql += ", backend := ?";
        }

        if(isHistorical) {
            sql += ", total_rows := ?";
        }

        sql += ") ORDER BY height";

        return sql;
    }

    private void bindParameters(DuckDBPreparedStatement statement, SilentPaymentScanAddress scanAddress, SilentPaymentsSubscription subscription, Integer startHeight, Integer endHeight, Set<Sha256Hash> mempoolTxids, boolean isHistorical, Long totalRows) throws SQLException {
        int index = bindTweakHeightFilter(statement, 1, startHeight, endHeight);
        index = bindTweakTxidsFilter(statement, index, mempoolTxids);
        statement.setBytes(index++, Utils.reverseBytes(scanAddress.getScanKey().getPrivKeyBytes()));
        statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getSpendKey()));
        for(Integer label : subscription.labels()) {
            statement.setBytes(index++, SilentPaymentUtils.getSecp256k1PubKey(scanAddress.getLabelledTweakKey(label)));
        }
        statement.setInt(index++, batchSize);

        ComputeBackend backend = resolveBackend(isHistorical);
        if(backend != ComputeBackend.AUTO) {
            statement.setString(index++, backend.toSqlValue());
        }

        if(totalRows != null) {
            statement.setLong(index, totalRows);
        }
    }

    private static String buildWhereClause(Integer startHeight, Integer endHeight, Set<Sha256Hash> mempoolTxids) {
        String heightClause = tweakHeightFilter(startHeight, endHeight);
        String txidsClause = tweakTxidsFilter(mempoolTxids);
        if(heightClause.isEmpty() && txidsClause.isEmpty()) {
            return "";
        }
        if(heightClause.isEmpty()) {
            return " WHERE " + txidsClause;
        }
        if(txidsClause.isEmpty()) {
            return " WHERE " + heightClause;
        }
        return " WHERE " + heightClause + " AND " + txidsClause;
    }

    private static String tweakHeightFilter(Integer startHeight, Integer endHeight) {
        if(startHeight == null && endHeight == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        if(startHeight != null) {
            sql.append("height >= ?");
            if(endHeight != null) {
                sql.append(" AND ");
            }
        }
        if(endHeight != null) {
            sql.append("height <= ?");
        }
        return sql.toString();
    }

    private static String tweakTxidsFilter(Set<Sha256Hash> mempoolTxids) {
        if(mempoolTxids == null) {
            return "";
        }
        return "txid IN (SELECT unnest(?))";
    }

    private static int bindTweakHeightFilter(PreparedStatement statement, int startIndex, Integer startHeight, Integer endHeight) throws SQLException {
        int idx = startIndex;
        if(startHeight != null) {
            statement.setInt(idx++, startHeight);
        }
        if(endHeight != null) {
            statement.setInt(idx++, endHeight);
        }
        return idx;
    }

    private static int bindTweakTxidsFilter(PreparedStatement statement, int startIndex, Set<Sha256Hash> mempoolTxids) throws SQLException {
        if(mempoolTxids == null) {
            return startIndex;
        }
        Object[] arr = mempoolTxids.stream().map(Sha256Hash::getBytes).toArray();
        Array array = statement.getConnection().createArrayOf("BLOB", arr);
        statement.setArray(startIndex, array);
        return startIndex + 1;
    }

    private long getInputRowCount(Connection connection, Integer startHeight, Integer endHeight) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + TWEAK_TABLE + buildWhereClause(startHeight, endHeight, null);
        try(PreparedStatement countStmt = connection.prepareStatement(sql)) {
            bindTweakHeightFilter(countStmt, 1, startHeight, endHeight);
            ResultSet rs = countStmt.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static ComputeBackend resolveBackend(boolean isHistorical) {
        if(!isHistorical) {
            return ComputeBackend.CPU;
        }

        return Config.get().getScan().getComputeBackendEnum();
    }

    private static boolean isUnsubscribed(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        SubscriptionStatus status = subscriptionStatusRef.get();
        return status == null || !status.isConnected() || !status.isSilentPaymentsAddressSubscribed(scanAddress.toString());
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

    public static byte[] compressRawKey(byte[] rawUncompressed) {
        byte[] uncompressed = new byte[64];
        System.arraycopy(rawUncompressed, 0, uncompressed, 32, 32);
        System.arraycopy(rawUncompressed, 32, uncompressed, 0, 32);
        uncompressed = Utils.reverseBytes(uncompressed);

        ECKey ecKey = ECKey.fromPublicOnly(Utils.concat(new byte[] {0x04}, uncompressed));
        return ecKey.getPubKey(true);
    }
}
