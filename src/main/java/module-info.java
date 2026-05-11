module com.sparrowwallet.frigate {
    requires com.sparrowwallet.drongo;
    requires duckdb.jdbc;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.toml;
    requires simple.json.rpc.core;
    requires simple.json.rpc.client;
    requires simple.json.rpc.server;
    requires com.google.gson;
    requires com.google.common;
    requires org.jcommander;
    requires org.zeromq.jeromq;
    requires org.slf4j;
    requires java.sql;
    requires static java.desktop;
    exports com.sparrowwallet.frigate;
    exports com.sparrowwallet.frigate.io;
    exports com.sparrowwallet.frigate.bitcoind;
    exports com.sparrowwallet.frigate.electrum;
    exports com.sparrowwallet.frigate.index;
    exports com.sparrowwallet.frigate.cli;
    opens com.sparrowwallet.frigate.control to com.google.common;
    opens com.sparrowwallet.frigate.io to com.fasterxml.jackson.databind;
}