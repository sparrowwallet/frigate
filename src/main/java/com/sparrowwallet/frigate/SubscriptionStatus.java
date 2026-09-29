package com.sparrowwallet.frigate;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Set;

public interface SubscriptionStatus {
    boolean isConnected();
    boolean isHeadersSubscribed();
    boolean isScriptHashSubscribed(String scriptHash);
    boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress);
    Set<Sha256Hash> getSilentPaymentsMempoolTxids(String silentPaymentsAddress);
    void notifySilentPayments(SilentPaymentsNotification notification);

    /**
     * Delivers a silent payments notification directly to the session that requested the scan, if it is still alive.
     * Delivery failures are logged rather than propagated so that they cannot abort the scan that produced the notification.
     */
    static void notifySilentPayments(WeakReference<SubscriptionStatus> subscriptionStatusRef, SilentPaymentsNotification notification) {
        SubscriptionStatus subscriptionStatus = subscriptionStatusRef.get();
        if(subscriptionStatus == null) {
            return;
        }

        try {
            subscriptionStatus.notifySilentPayments(notification);
        } catch(Exception e) {
            LoggerFactory.getLogger(SubscriptionStatus.class).error("Error delivering silent payments notification", e);
        }
    }
}
