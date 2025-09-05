package com.sparrowwallet.frigate.index;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Semaphore;

public class SingleDbManager extends AbstractDbManager {
    private static final Logger log = LoggerFactory.getLogger(SingleDbManager.class);

    private final String connectionUrl;
    private final ReadWriteLock rwLock;
    private final Semaphore writerWaiting;

    private Connection writeConnection;
    private HikariDataSource readDataSource;
    private boolean inWriteMode;
    private volatile boolean shutdown = false;
    private volatile boolean writeOperationActive = false;

    public SingleDbManager(String connectionUrl) {
        this.connectionUrl = connectionUrl;
        this.rwLock = new ReentrantReadWriteLock(true);
        this.writerWaiting = new Semaphore(1);
        this.inWriteMode = false;
    }

    public <T> T executeRead(DbManager.ReadOperation<T> operation) throws SQLException, InterruptedException {
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

    public <T> T executeWrite(DbManager.WriteOperation<T> operation) throws SQLException, InterruptedException {
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

        readDataSource = createReadDataSource(connectionUrl, 10);
    }

    private void createWriteConnection() throws SQLException {
        if(writeConnection != null) {
            return;
        }

        writeConnection = createWriteConnection(connectionUrl);
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
}
