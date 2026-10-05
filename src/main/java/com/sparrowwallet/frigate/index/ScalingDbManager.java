package com.sparrowwallet.frigate.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ScalingDbManager extends AbstractDbManager {
    private final static Logger log = LoggerFactory.getLogger(ScalingDbManager.class);

    private final String readWriteUrl;
    private Connection writeConnection;
    private final List<DuckDBReadPool> readPools = new ArrayList<>();
    private final AtomicInteger index = new AtomicInteger(0);
    private volatile boolean shutdown = false;

    public ScalingDbManager(String readWriteUrl, List<String> readOnlyUrls) {
        super();
        this.readWriteUrl = readWriteUrl;
        for(String url : readOnlyUrls) {
            try {
                readPools.add(new DuckDBReadPool(url, 10));
            } catch(SQLException e) {
                throw new RuntimeException("Failed to create DuckDB read pool for " + url, e);
            }
        }
    }

    @Override
    public <T> T executeRead(ReadOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        int ind = index.getAndIncrement() % readPools.size();
        DuckDBReadPool pool = readPools.get(ind);
        Connection conn = null;
        try {
            conn = pool.getConnection();
            return operation.execute(conn);
        } finally {
            if(conn != null) {
                pool.releaseConnection(conn);
            }
        }
    }

    @Override
    public synchronized <T> T executeWrite(WriteOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        createWriteConnection();
        return operation.execute(writeConnection);
    }

    @Override
    public synchronized void close() {
        shutdown = true;

        try {
            if(writeConnection != null && !writeConnection.isClosed()) {
                writeConnection.close();
            }
        } catch(SQLException e) {
            log.error("Error closing write connection", e);
        }

        for(DuckDBReadPool pool : readPools) {
            pool.close();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    private void createWriteConnection() throws SQLException {
        if(writeConnection != null) {
            return;
        }

        writeConnection = createWriteConnection(readWriteUrl);
    }
}
