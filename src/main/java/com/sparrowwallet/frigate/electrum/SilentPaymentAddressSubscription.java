package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;

import java.util.HashSet;
import java.util.Set;

public class SilentPaymentAddressSubscription {
    private final SilentPaymentScanAddress address;
    private final Set<Integer> labels;
    private int highestBlockHeight;
    private final Set<Sha256Hash> mempoolTxids = new HashSet<>();
    private volatile boolean active;

    public SilentPaymentAddressSubscription(SilentPaymentScanAddress address, Set<Integer> labels) {
        this.address = address;
        this.labels = labels;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public SilentPaymentScanAddress getAddress() {
        return address;
    }

    public Set<Integer> getLabels() {
        return labels;
    }

    public int getHighestBlockHeight() {
        return highestBlockHeight;
    }

    public void setHighestBlockHeight(int highestBlockHeight) {
        this.highestBlockHeight = highestBlockHeight;
    }

    public Set<Sha256Hash> getMempoolTxids() {
        return mempoolTxids;
    }
}
