package com.sparrowwallet.frigate.index;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.ScriptHashTx;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Index {
    private static final Logger log = LoggerFactory.getLogger(Index.class);
    public static final String DB_FILENAME = "duckdb";
    private static final String TWEAK_TABLE = "tweak";

    private DuckDBConnection connection;

    private static final int MAINNET_TAPROOT_ACTIVATION_HEIGHT = 709632;
    private static final int TESTNET_TAPROOT_ACTIVATION_HEIGHT = 0;
    private int lastBlockIndexed = -1;

    private final Lock writeLock = new ReentrantLock();

    public void initialize() {
        Integer startHeight = Config.get().getIndexStartHeight();
        if(startHeight == null) {
            startHeight = Network.get() == Network.MAINNET ? MAINNET_TAPROOT_ACTIVATION_HEIGHT : TESTNET_TAPROOT_ACTIVATION_HEIGHT;
            Config.get().setIndexStartHeight(startHeight);
        }
        lastBlockIndexed = Math.max(lastBlockIndexed, startHeight - 1);

        try {
            Properties prop = new Properties();
            prop.setProperty("allow_unsigned_extensions", "true");

            File dbFile = new File(Storage.getFrigateDbDir(), DB_FILENAME);
            connection = (DuckDBConnection)DriverManager.getConnection("jdbc:duckdb:" + dbFile.getAbsolutePath(), prop);

            File secp256k1ExtensionFile = Storage.getSecp256k1ExtensionFile();
            Statement loadStmt = connection.createStatement();
            loadStmt.execute("LOAD '" + secp256k1ExtensionFile.getAbsolutePath() + "'");

            Statement createStmt = connection.createStatement();
            createStmt.execute("CREATE TABLE IF NOT EXISTS " + TWEAK_TABLE + " (txid BLOB NOT NULL, height INTEGER NOT NULL, tweak_key BLOB NOT NULL, outputs BIGINT[])");
        } catch(SQLException e) {
            throw new ConfigurationException("Error initialising index", e);
        }
    }

    public void close() {
        try {
            writeLock.lock();
            try {
                connection.close();
            } finally {
                writeLock.unlock();
            }
        } catch(SQLException e) {
            log.error("Error closing index", e);
        }
    }

    public int getLastBlockIndexed() {
        try {
            PreparedStatement statement = connection.prepareStatement("SELECT MAX(height) from " + TWEAK_TABLE);
            ResultSet resultSet = statement.executeQuery();
            if(resultSet.next()) {
                lastBlockIndexed = Math.max(lastBlockIndexed, resultSet.getInt(1));
            }
        } catch(SQLException e) {
            log.error("Error getting last block indexed", e);
        }

        return lastBlockIndexed;
    }

    public void addToIndex(Map<BlockTransaction, byte[]> transactions) {
        int blockHeight = -1;

        try(PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TWEAK_TABLE + " VALUES (?, ?, ?, ?)")) {
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

            writeLock.lock();
            try {
                statement.executeBatch();
            } finally {
                writeLock.unlock();
            }
        } catch(SQLException e) {
            log.error("Error adding to index", e);
        }

        log.info("Indexed " + transactions.size() + " transactions to block height " + blockHeight);
        this.lastBlockIndexed = blockHeight;
    }

    public List<ScriptHashTx> getHistory(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight) {
        List<ScriptHashTx> history = new ArrayList<>();

        String sql = "SELECT txid, height FROM " + TWEAK_TABLE +
                " WHERE list_contains(outputs, hash_prefix_to_int(secp256k1_ec_pubkey_combine([?, secp256k1_ec_pubkey_create(secp256k1_tagged_sha256('BIP0352/SharedSecret', secp256k1_ec_pubkey_tweak_mul(tweak_key, ?) || int_to_big_endian(0)))]), 1))";

        if(startHeight != null) {
            sql += " AND height >= ?";
        }
        if(endHeight != null) {
            sql += " AND height <= ?";
        }

        try(PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, scanAddress.getSpendKey().getPubKey());
            statement.setBytes(2, scanAddress.getScanKey().getPrivKeyBytes());
            if(startHeight != null) {
                statement.setInt(3, startHeight);
            }
            if(endHeight != null) {
                statement.setInt(startHeight == null ? 3 : 4, endHeight);
            }
            ResultSet resultSet = statement.executeQuery();
            while(resultSet.next()) {
                byte[] txid = resultSet.getBytes(1);
                int height = resultSet.getInt(2);
                history.add(new ScriptHashTx(height, Utils.bytesToHex(txid), 0L));
            }
        } catch(SQLException e) {
            log.error("Error scanning index", e);
        }

        return history;
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
