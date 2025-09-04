package com.sparrowwallet.frigate;

public interface SubscriptionStatus {
    boolean isConnected();
    boolean isHeadersSubscribed();
    boolean isScriptHashSubscribed(String scriptHash);
    boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress);
}
