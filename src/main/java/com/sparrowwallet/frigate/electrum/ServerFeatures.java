package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerFeatures(Map<String, HostInfo> hosts, String genesis_hash, String hash_function, String server_version, String protocol_max, String protocol_min, Integer pruning, List<Integer> silent_payments) {
    public record HostInfo(Integer tcp_port, Integer ssl_port) {}

    public ServerFeatures withSilentPayments(List<Integer> versions) {
        return new ServerFeatures(hosts, genesis_hash, hash_function, server_version, protocol_max, protocol_min, pruning, versions);
    }
}
