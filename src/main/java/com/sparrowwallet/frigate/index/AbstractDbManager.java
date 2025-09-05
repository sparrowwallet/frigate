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

public abstract class AbstractDbManager implements DbManager {
    private static final Logger log = LoggerFactory.getLogger(AbstractDbManager.class);

    protected Connection createWriteConnection(String connectionUrl) throws SQLException {
        log.debug("Creating write connection");
        return DriverManager.getConnection(connectionUrl);
    }

    protected HikariDataSource createReadDataSource(String connectionUrl, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connectionUrl);
        config.setDriverClassName("org.duckdb.DuckDBDriver");
        config.addDataSourceProperty("access_mode", "READ_ONLY");
        config.addDataSourceProperty("allow_unsigned_extensions", "true");
        config.addDataSourceProperty("jdbc_stream_results", "true");
        config.addDataSourceProperty("scheduler_process_partial", "true");
        config.setConnectionInitSql(buildReadConnectionInitSql());

        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(900000);
        config.setLeakDetectionThreshold(0);

        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(5000);
        config.setPoolName("DuckDB-ReadOnly-Pool");

        log.debug("Creating read connection pool with max size: " + config.getMaximumPoolSize());
        return new HikariDataSource(config);
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
}
