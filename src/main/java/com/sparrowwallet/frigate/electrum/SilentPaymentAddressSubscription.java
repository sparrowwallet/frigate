package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;

public class SilentPaymentAddressSubscription {
    private final SilentPaymentScanAddress address;
    private int highestBlockHeight;

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
}
