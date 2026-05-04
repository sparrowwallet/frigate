package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentAddressSubscription;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;
import com.sparrowwallet.frigate.electrum.SilentPaymentsTxEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

public class IndexQuerier {
    private static final Logger log = LoggerFactory.getLogger(IndexQuerier.class);

    public static final double PROGRESS_COMPLETE = 1.0d;

    private final Index blocksIndex;
    private final Index mempoolIndex;

    public IndexQuerier(Index blocksIndex, Index mempoolIndex) {
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;
    }

    private final ExecutorService queryPool = Executors.newFixedThreadPool(10, r -> {
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQuery-%d").build();
        Thread t = namedThreadFactory.newThread(r);
        t.setDaemon(true);
        return t;
    });

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, SilentPaymentAddressSubscription subscription, WeakReference<SubscriptionStatus> subscriptionStatusRef, boolean isHistorical) {
        BooleanSupplier cancelled = subscription.captureScanCancellation();
        queryPool.submit(() -> {
            SilentPaymentsSubscription notificationSubscription = new SilentPaymentsSubscription(scanAddress.toString(), subscription.getLabels().toArray(new Integer[0]), subscription.getStartHeight());
            List<SilentPaymentsTxEntry> history = blocksIndex.getHistoryAsync(scanAddress, notificationSubscription, startHeight, endHeight, subscriptionStatusRef, cancelled);
            List<SilentPaymentsTxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, notificationSubscription, cancelled);
            history.addAll(mempoolHistory);

            boolean wasCancelled = cancelled.getAsBoolean();
            if(!wasCancelled && (isHistorical || !history.isEmpty())) {
                Frigate.getEventBus().post(new SilentPaymentsNotification(notificationSubscription, PROGRESS_COMPLETE, new ArrayList<>(history), subscriptionStatusRef.get()));
            }
            if(!wasCancelled && isHistorical) {
                try {
                    subscription.markHistoricalComplete();
                } catch(Exception e) {
                    log.error("Error marking historical complete", e);
                }
            }
        });
    }

    public void startMempoolScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, SilentPaymentAddressSubscription subscription, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        BooleanSupplier cancelled = subscription.captureScanCancellation();
        queryPool.submit(() -> {
            SilentPaymentsSubscription notificationSubscription = new SilentPaymentsSubscription(scanAddress.toString(), subscription.getLabels().toArray(new Integer[0]), subscription.getStartHeight());
            List<SilentPaymentsTxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, notificationSubscription, cancelled);

            if(!cancelled.getAsBoolean() && !mempoolHistory.isEmpty()) {
                Frigate.getEventBus().post(new SilentPaymentsNotification(notificationSubscription, PROGRESS_COMPLETE, new ArrayList<>(mempoolHistory), subscriptionStatusRef.get()));
            }
        });
    }

    private List<SilentPaymentsTxEntry> getMempoolHistory(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef, SilentPaymentsSubscription notificationSubscription, BooleanSupplier cancelled) {
        List<SilentPaymentsTxEntry> mempoolHistory = mempoolIndex.getHistoryAsync(scanAddress, notificationSubscription, null, null, subscriptionStatusRef, cancelled);
        SubscriptionStatus subscriptionStatus = subscriptionStatusRef.get();
        if(subscriptionStatus != null && subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()) != null) {
            mempoolHistory.removeIf(txEntry -> subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()).contains(Sha256Hash.wrap(txEntry.tx_hash)));
        }

        return mempoolHistory;
    }
}
