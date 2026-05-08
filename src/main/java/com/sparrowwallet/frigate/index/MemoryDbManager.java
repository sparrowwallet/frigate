package com.sparrowwallet.frigate.index;

import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class MemoryDbManager implements DbManager {
    private final static Logger log = LoggerFactory.getLogger(MemoryDbManager.class);

    private Connection connection;
    private boolean shutdown = false;

    @Override
    public synchronized <T> T executeRead(ReadOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        createInMemoryConnection();
        return operation.execute(connection);
    }

    @Override
    public synchronized <T> T executeWrite(WriteOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        createInMemoryConnection();
        return operation.execute(connection);
    }

    @Override
    public void close() {
        shutdown = true;

        try {
            if(connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch(SQLException e) {
            log.error("Error closing in-memory connection", e);
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    private void createInMemoryConnection() throws SQLException {
        if(connection != null) {
            return;
        }

        Properties duckDbProperties = new Properties();
        duckDbProperties.setProperty("allow_unsigned_extensions", "true");
        if(Config.get().getScan().getDbThreads() != null) {
            duckDbProperties.setProperty("threads", Config.get().getScan().getDbThreads().toString());
        }
        if(Config.get().getScan().getMemoryLimit() != null) {
            duckDbProperties.setProperty("memory_limit", Config.get().getScan().getMemoryLimit());
        }

        connection = DriverManager.getConnection(DbManager.DB_PREFIX + "memory:", duckDbProperties);

        File ufsecpExtensionFile = Storage.getUfsecpExtensionFile();
        try(Statement statement = connection.createStatement()) {
            statement.execute("LOAD '" + ufsecpExtensionFile.getAbsolutePath() + "';");
        }
    }
}
