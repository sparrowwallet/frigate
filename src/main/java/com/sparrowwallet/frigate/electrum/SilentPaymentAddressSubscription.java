package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;

import java.util.HashSet;
import java.util.Set;

public class SilentPaymentAddressSubscription {
    private final SilentPaymentScanAddress address;
    private int highestBlockHeight;
    private final Set<Sha256Hash> mempoolTxids = new HashSet<>();

    public SilentPaymentAddressSubscription(SilentPaymentScanAddress address) {
        this.address = address;
    }

    public SilentPaymentScanAddress getAddress() {
        return address;
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
