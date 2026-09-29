package com.sparrowwallet.frigate;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SubscriptionStatusTest {
    private static final SilentPaymentsNotification NOTIFICATION = new SilentPaymentsNotification(new SilentPaymentsSubscription("sp1test", new Integer[] {0}, 0), 1.0, List.of());

    @Test
    public void clearedReferenceIsNoOp() {
        assertDoesNotThrow(() -> SubscriptionStatus.notifySilentPayments(new WeakReference<>(null), NOTIFICATION));
    }

    @Test
    public void deliveryFailureDoesNotPropagateToScan() {
        FailingStatus failing = new FailingStatus();
        assertDoesNotThrow(() -> SubscriptionStatus.notifySilentPayments(new WeakReference<>(failing), NOTIFICATION));
        assertEquals(List.of(NOTIFICATION), failing.received);
    }

    private static class FailingStatus implements SubscriptionStatus {
        private final List<SilentPaymentsNotification> received = new ArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isHeadersSubscribed() {
            return false;
        }

        @Override
        public boolean isScriptHashSubscribed(String scriptHash) {
            return false;
        }

        @Override
        public boolean isSilentPaymentsAddressSubscribed(String silentPaymentsAddress) {
            return true;
        }

        @Override
        public Set<Sha256Hash> getSilentPaymentsMempoolTxids(String silentPaymentsAddress) {
            return Set.of();
        }

        @Override
        public void notifySilentPayments(SilentPaymentsNotification notification) {
            received.add(notification);
            throw new IllegalStateException("delivery failed");
        }
    }
}
