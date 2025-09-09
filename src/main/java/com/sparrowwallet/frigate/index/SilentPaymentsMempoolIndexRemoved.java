package com.sparrowwallet.frigate.index;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.Set;

public class SilentPaymentsMempoolIndexRemoved {
    private final Set<Sha256Hash> txids;

    public SilentPaymentsMempoolIndexRemoved(Set<Sha256Hash> txids) {
        this.txids = txids;
    }

    public Set<Sha256Hash> getTxids() {
        return txids;
    }
}
