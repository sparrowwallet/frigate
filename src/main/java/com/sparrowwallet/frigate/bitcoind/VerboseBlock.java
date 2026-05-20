package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VerboseBlock(String hash, int height, long time, List<VerboseTransaction> tx) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerboseTransaction(String txid, @JsonProperty("hex") String hex, List<VerboseVin> vin, List<VerboseVout> vout) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerboseVin(String txid, @JsonProperty("vout") Integer voutIndex, VerbosePrevout prevout) {
        public boolean isCoinbase() {
            return txid == null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerbosePrevout(@JsonProperty("scriptPubKey") VerboseScriptPubKey scriptPubKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerboseVout(int n, @JsonProperty("scriptPubKey") VerboseScriptPubKey scriptPubKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerboseScriptPubKey(String hex) {}
}
