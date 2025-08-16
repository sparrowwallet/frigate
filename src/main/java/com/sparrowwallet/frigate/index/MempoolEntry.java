package com.sparrowwallet.frigate.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MempoolEntry(int vsize, int ancestorsize, boolean bip125_replaceable, FeesMempoolEntry fees) {
    public boolean hasUnconfirmedParents() {
        return vsize != ancestorsize;
    }

    public TxEntry getTxEntry(String txid) {
        return new TxEntry(hasUnconfirmedParents() ? -1 : 0, 0, txid, fees().base());
    }

    public VsizeFeerate getVsizeFeerate() {
        return new VsizeFeerate(vsize, fees().base());
    }
}
