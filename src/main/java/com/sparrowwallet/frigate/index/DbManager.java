package com.sparrowwallet.frigate.index;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public interface DbManager {
    String DB_PREFIX = "jdbc:duckdb:";

    <T> T executeRead(ReadOperation<T> operation) throws SQLException, InterruptedException;
    <T> T executeWrite(WriteOperation<T> operation) throws SQLException, InterruptedException;
    void close();
    boolean isShutdown();

    @FunctionalInterface
    public interface ReadOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface WriteOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
