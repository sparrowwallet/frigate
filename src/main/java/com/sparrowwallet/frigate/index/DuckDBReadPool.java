package com.sparrowwallet.frigate.index;

import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class DuckDBReadPool {
    private static final Logger log = LoggerFactory.getLogger(DuckDBReadPool.class);

    private final DuckDBConnection masterConnection;
    private final ArrayBlockingQueue<Connection> pool;
    private final int maxSize;
    private volatile boolean closed = false;

    public DuckDBReadPool(String connectionUrl, int maxSize) throws SQLException {
        this.maxSize = maxSize;

        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");
        props.setProperty("allow_unsigned_extensions", "true");
        this.masterConnection = (DuckDBConnection)DriverManager.getConnection(connectionUrl, props);

        try(Statement stmt = masterConnection.createStatement()) {
            if(Config.get().getScan().getDbThreads() != null) {
                stmt.execute("SET threads = '" + Config.get().getScan().getDbThreads() + "'");
            }
            if(Config.get().getScan().getMemoryLimit() != null) {
                stmt.execute("SET memory_limit = '" + Config.get().getScan().getMemoryLimit() + "'");
            }

            File ufsecpExtensionFile = Storage.getUfsecpExtensionFile();
            stmt.execute("LOAD '" + ufsecpExtensionFile.getAbsolutePath() + "'");
            stmt.execute("SELECT ufsecp_set_cache_dir('" + Storage.getFrigateCacheDir().getAbsolutePath() + "')");
        }

        this.pool = new ArrayBlockingQueue<>(maxSize);
        log.debug("DuckDB read pool created (max size: {})", maxSize);
    }

    public Connection getConnection() throws SQLException {
        if(closed) {
            throw new SQLException("Pool is closed");
        }

        Connection conn = pool.poll();
        if(conn != null && !conn.isClosed()) {
            return conn;
        }

        return masterConnection.duplicate();
    }

    public void releaseConnection(Connection conn) {
        if(closed || conn == null) {
            closeQuietly(conn);
            return;
        }

        try {
            if(conn.isClosed()) {
                return;
            }
        } catch(SQLException e) {
            return;
        }

        if(!pool.offer(conn)) {
            closeQuietly(conn);
        }
    }

    public void close() {
        closed = true;
        Connection conn;
        while((conn = pool.poll()) != null) {
            closeQuietly(conn);
        }
        closeQuietly(masterConnection);
        log.debug("DuckDB read pool closed");
    }

    private static void closeQuietly(Connection conn) {
        if(conn != null) {
            try {
                conn.close();
            } catch(SQLException ignored) {
            }
        }
    }
}
