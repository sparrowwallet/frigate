package com.sparrowwallet.frigate.index;

import com.zaxxer.hikari.HikariDataSource;
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
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final AtomicInteger index = new AtomicInteger(0);
    private boolean shutdown = false;

    public ScalingDbManager(String readWriteUrl, List<String> readOnlyUrls) {
        this.readWriteUrl = readWriteUrl;
        for(String url : readOnlyUrls) {
            HikariDataSource ds = createReadDataSource(url, 1);
            dataSources.add(ds);
        }
    }

    @Override
    public <T> T executeRead(ReadOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        int ind = index.getAndIncrement() % dataSources.size();
        HikariDataSource ds = dataSources.get(ind);
        return operation.execute(ds.getConnection());
    }

    @Override
    public <T> T executeWrite(WriteOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        createWriteConnection();
        return operation.execute(writeConnection);
    }

    @Override
    public void close() {
        shutdown = true;

        try {
            if(writeConnection != null && !writeConnection.isClosed()) {
                writeConnection.close();
            }
        } catch(SQLException e) {
            log.error("Error closing write connection", e);
        }

        for(HikariDataSource ds : dataSources) {
            ds.close();
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
