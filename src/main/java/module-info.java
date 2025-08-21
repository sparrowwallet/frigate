module com.sparrowwallet.frigate {
    requires com.sparrowwallet.drongo;
    requires org.duckdb.duckdb_jdbc;
    requires com.fasterxml.jackson.annotation;
    requires simple.json.rpc.core;
    requires simple.json.rpc.client;
    requires simple.json.rpc.server;
    requires com.google.gson;
    requires com.google.common;
    requires org.jcommander;
    requires org.slf4j;
    requires java.sql;
    exports com.sparrowwallet.frigate;
    exports com.sparrowwallet.frigate.io;
    exports com.sparrowwallet.frigate.bitcoind;
    exports com.sparrowwallet.frigate.electrum;
    exports com.sparrowwallet.frigate.index;
    exports com.sparrowwallet.frigate.cli;
    opens com.sparrowwallet.frigate.io;
}