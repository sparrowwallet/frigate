package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerFeatures(Map<String, HostInfo> hosts, String genesis_hash, String hash_function, String server_version, String protocol_max, String protocol_min, Integer pruning, List<Integer> silent_payments) {
    public record HostInfo(Integer tcp_port, Integer ssl_port) {
        //Some non-conformant Electrum servers return hosts as {"host": <port>} instead of
        //{"host": {"tcp_port": <port>, "ssl_port": <port>}}. Accept that shape and treat the
        //bare number as the tcp_port.
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static HostInfo fromTcpPort(int tcpPort) {
            return new HostInfo(tcpPort, null);
        }
    }

    public ServerFeatures withSilentPayments(List<Integer> versions) {
        return new ServerFeatures(hosts, genesis_hash, hash_function, server_version, protocol_max, protocol_min, pruning, versions);
    }

    public ServerFeatures withHosts(Map<String, HostInfo> hosts) {
        return new ServerFeatures(hosts, genesis_hash, hash_function, server_version, protocol_max, protocol_min, pruning, silent_payments);
    }
}
