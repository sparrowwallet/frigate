package com.sparrowwallet.frigate;

import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.Set;

public interface SubscriptionStatus {
    boolean isConnected();
    boolean isHeadersSubscribed();
    boolean isScriptHashSubscribed(String scriptHash);
    boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress);
    Set<Sha256Hash> getSilentPaymentsMempoolTxids(String silentPaymentsAddress);
}
