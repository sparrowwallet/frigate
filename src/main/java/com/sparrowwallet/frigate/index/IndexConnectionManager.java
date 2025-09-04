package com.sparrowwallet.frigate.index;

import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Semaphore;

public class IndexConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(IndexConnectionManager.class);

    private final String dbPath;
    private final ReadWriteLock rwLock;
    private final Semaphore writerWaiting;

    private Connection writeConnection;
    private HikariDataSource readDataSource;
    private boolean inWriteMode;
    private final String readConnectionInitSql;
    private volatile boolean shutdown = false;
    private volatile boolean writeOperationActive = false;

    public IndexConnectionManager(String dbPath) {
        this.dbPath = dbPath;
        this.rwLock = new ReentrantReadWriteLock(true);
        this.writerWaiting = new Semaphore(1);
        this.inWriteMode = false;
        this.readConnectionInitSql = buildReadConnectionInitSql();
    }

    private String buildReadConnectionInitSql() {
        Properties duckDbProperties = new Properties();
        duckDbProperties.setProperty("enable_progress_bar", "true");
        duckDbProperties.setProperty("enable_progress_bar_print", "false");
        if(Config.get().getDbThreads() != null) {
            duckDbProperties.setProperty("threads", Config.get().getDbThreads().toString());
        }

        StringBuilder sql = new StringBuilder();
        for(String propertyName : duckDbProperties.stringPropertyNames()) {
            String value = duckDbProperties.getProperty(propertyName);
            sql.append("SET ").append(propertyName).append(" = '").append(value).append("'; ");
        }

        File secp256k1ExtensionFile = Storage.getSecp256k1ExtensionFile();
        sql.append("LOAD '").append(secp256k1ExtensionFile.getAbsolutePath()).append("'; ");

        return sql.toString().trim();
    }

    public <T> T executeRead(ReadOperation<T> operation) throws SQLException, InterruptedException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        if(writerWaiting.availablePermits() == 0) {
            synchronized(this) {
                while(writerWaiting.availablePermits() == 0) {
                    wait();
                }
            }
        }

        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        rwLock.readLock().lock();
        try {
            ensureReadMode();
            try(Connection conn = readDataSource.getConnection()) {
                return operation.execute(conn);
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public <T> T executeWrite(WriteOperation<T> operation) throws SQLException, InterruptedException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        writeOperationActive = true;

        try {
            writerWaiting.acquire();

            try {
                rwLock.writeLock().lock();
                try {
                    ensureWriteMode();
                    return operation.execute(writeConnection);
                } finally {
                    rwLock.writeLock().unlock();
                }
            } finally {
                writerWaiting.release();
                synchronized(this) {
                    notifyAll();
                }
            }
        } finally {
            synchronized(this) {
                writeOperationActive = false;
                notifyAll();
            }
        }
    }

    private synchronized void ensureReadMode() throws SQLException {
        if(inWriteMode || readDataSource == null) {
            log.debug("Switching to READ mode");
            waitForWriteOperationToComplete();
            closeWriteConnection();
            createReadDataSource();
            inWriteMode = false;
        }
    }

    private synchronized void ensureWriteMode() throws SQLException {
        if(!inWriteMode || writeConnection == null) {
            log.debug("Switching to WRITE mode");
            closeReadDataSource();
            createWriteConnection();
            inWriteMode = true;
        }
    }

    private void waitForWriteOperationToComplete() {
        synchronized(this) {
            while(writeOperationActive && !shutdown) {
                try {
                    log.debug("Waiting for active write operation to complete...");
                    wait(1000); // Wait with timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void createReadDataSource() {
        if(readDataSource != null) {
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:duckdb:" + dbPath);
        config.setDriverClassName("org.duckdb.DuckDBDriver");
        config.addDataSourceProperty("access_mode", "READ_ONLY");
        config.addDataSourceProperty("allow_unsigned_extensions", "true");
        config.addDataSourceProperty("jdbc_stream_results", "true");
        config.addDataSourceProperty("scheduler_process_partial", "true");
        config.setConnectionInitSql(readConnectionInitSql);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(900000);
        config.setLeakDetectionThreshold(0);

        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(5000);
        config.setPoolName("DuckDB-ReadOnly-Pool");

        readDataSource = new HikariDataSource(config);
        log.debug("Created read connection pool with max size: " + config.getMaximumPoolSize());
    }

    private void createWriteConnection() throws SQLException {
        if(writeConnection != null) {
            return;
        }

        writeConnection = DriverManager.getConnection("jdbc:duckdb:" + dbPath);
        log.debug("Created write connection");
    }

    private void closeWriteConnection() throws SQLException {
        if(writeConnection != null) {
            waitForWriteOperationToComplete();

            try {
                if(!writeConnection.isClosed()) {
                    writeConnection.close();
                }
            } finally {
                writeConnection = null;
                log.debug("Closed write connection");
            }
        }
    }

    private void closeReadDataSource() {
        if(readDataSource != null) {
            try {
                readDataSource.close();
            } finally {
                readDataSource = null;
                log.debug("Closed read connection pool");
            }
        }
    }

    public void close() {
        close(30, TimeUnit.SECONDS);
    }

    public void close(long timeout, TimeUnit timeUnit) {
        log.debug("Starting graceful shutdown...");
        shutdown = true;

        // Wake up any waiting threads
        synchronized(this) {
            notifyAll();
        }

        // Wait for active write operation to complete
        long timeoutMillis = timeUnit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        synchronized(this) {
            while(writeOperationActive && (System.currentTimeMillis() - startTime) < timeoutMillis) {
                try {
                    long remainingTime = timeoutMillis - (System.currentTimeMillis() - startTime);
                    if(remainingTime > 0) {
                        System.out.println("Waiting for active write operation to complete... " + "(" + remainingTime + "ms remaining)");
                        wait(Math.min(1000, remainingTime));
                    }
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if(writeOperationActive) {
            log.error("Timeout exceeded, forcing shutdown with active write operation");
        }

        try {
            closeWriteConnection();
        } catch(Exception e) {
            log.error("Error closing write connection", e);
        }

        try {
            closeReadDataSource();
        } catch(Exception e) {
            log.error("Error closing read data source", e);
        }

        log.debug("Shutdown complete");
    }

    public int getActiveReadConnections() {
        return readDataSource != null ? readDataSource.getHikariPoolMXBean().getActiveConnections() : 0;
    }

    public int getIdleReadConnections() {
        return readDataSource != null ? readDataSource.getHikariPoolMXBean().getIdleConnections() : 0;
    }

    public int getTotalReadConnections() {
        return readDataSource != null ? readDataSource.getHikariPoolMXBean().getTotalConnections() : 0;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public boolean isInWriteMode() {
        return inWriteMode;
    }

    @FunctionalInterface
    public interface ReadOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface WriteOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
