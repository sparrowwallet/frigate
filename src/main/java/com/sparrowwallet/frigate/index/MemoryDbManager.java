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
    private final static Logger log = LoggerFactory.getLogger(ScalingDbManager.class);

    private Connection connection;
    private boolean shutdown = false;

    @Override
    public <T> T executeRead(ReadOperation<T> operation) throws SQLException {
        if(shutdown) {
            throw new SQLException("Connection manager is shutting down");
        }

        createInMemoryConnection();
        return operation.execute(connection);
    }

    @Override
    public <T> T executeWrite(WriteOperation<T> operation) throws SQLException {
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
        if(Config.get().getDbThreads() != null) {
            duckDbProperties.setProperty("threads", Config.get().getDbThreads().toString());
        }

        connection = DriverManager.getConnection(DbManager.DB_PREFIX + "memory:", duckDbProperties);

        File secp256k1ExtensionFile = Storage.getSecp256k1ExtensionFile();
        try(Statement statement = connection.createStatement()) {
            statement.execute("LOAD '" + secp256k1ExtensionFile.getAbsolutePath() + "';");
        }
    }
}
