package com.sparrowwallet.frigate.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MempoolInfo(double minrelaytxfee) {
}
