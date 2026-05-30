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
import com.sparrowwallet.frigate.io.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class IndexQuerier {
    private static final Logger log = LoggerFactory.getLogger(IndexQuerier.class);

    public static final double PROGRESS_COMPLETE = 1.0d;

    private final Index blocksIndex;
    private final Index mempoolIndex;
    private final HistoricalScanMetrics metrics;
    private final ScheduledExecutorService metricsExecutor;

    public IndexQuerier(Index blocksIndex, Index mempoolIndex) {
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;

        if(Config.get().getScan().isMetricsEnabled()) {
            this.metrics = new HistoricalScanMetrics();
            this.metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new ThreadFactoryBuilder().setNameFormat("IndexQueryMetrics-%d").build().newThread(r);
                t.setDaemon(true);
                return t;
            });
            this.metricsExecutor.scheduleAtFixedRate(this::emitMetrics, 1, 1, TimeUnit.HOURS);
        } else {
            this.metrics = null;
            this.metricsExecutor = null;
        }
    }

    private final ExecutorService queryPool = Executors.newFixedThreadPool(10, r -> {
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQuery-%d").build();
        Thread t = namedThreadFactory.newThread(r);
        t.setDaemon(true);
        return t;
    });

    private void emitMetrics() {
        try {
            metrics.format(metrics.snapshotAndReset()).ifPresent(log::info);
        } catch(Throwable t) {
            log.warn("Failed to emit scan metrics", t);
        }
    }

    public void close() {
        if(metricsExecutor != null) {
            metricsExecutor.shutdownNow();
        }
    }

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, SilentPaymentAddressSubscription subscription, WeakReference<SubscriptionStatus> subscriptionStatusRef, boolean isHistorical) {
        BooleanSupplier cancelled = subscription.captureScanCancellation();
        queryPool.submit(() -> {
            long startMillis = isHistorical ? System.currentTimeMillis() : 0L;
            try {
                SilentPaymentsSubscription notificationSubscription = new SilentPaymentsSubscription(scanAddress.toString(), subscription.getLabels().toArray(new Integer[0]), subscription.getStartHeight());
                List<SilentPaymentsTxEntry> history = blocksIndex.getHistoryAsync(scanAddress, notificationSubscription, startHeight, endHeight, null, subscriptionStatusRef, cancelled, isHistorical);
                List<SilentPaymentsTxEntry> mempoolHistory = getMempoolHistory(scanAddress, null, subscriptionStatusRef, notificationSubscription, cancelled);
                history.addAll(mempoolHistory);
                long scanDurationMillis = isHistorical ? System.currentTimeMillis() - startMillis : 0L;

                boolean wasCancelled = cancelled.getAsBoolean();
                if(!wasCancelled && (isHistorical || !history.isEmpty())) {
                    Frigate.getEventBus().post(new SilentPaymentsNotification(notificationSubscription, PROGRESS_COMPLETE, new ArrayList<>(history), subscriptionStatusRef.get()));
                }
                if(!wasCancelled && isHistorical) {
                    subscription.markHistoricalComplete();
                    if(metrics != null) {
                        metrics.record(history.size(), scanDurationMillis);
                    }
                }
            } catch(Throwable t) {
                log.error("History scan task failed for " + scanAddress + " (start=" + startHeight + ", end=" + endHeight + ", isHistorical=" + isHistorical + ")", t);
            }
        });
    }

    public void startMempoolScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, Set<Sha256Hash> mempoolTxids, SilentPaymentAddressSubscription subscription, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        BooleanSupplier cancelled = subscription.captureScanCancellation();
        queryPool.submit(() -> {
            try {
                SilentPaymentsSubscription notificationSubscription = new SilentPaymentsSubscription(scanAddress.toString(), subscription.getLabels().toArray(new Integer[0]), subscription.getStartHeight());
                List<SilentPaymentsTxEntry> mempoolHistory = getMempoolHistory(scanAddress, mempoolTxids, subscriptionStatusRef, notificationSubscription, cancelled);

                if(!cancelled.getAsBoolean() && !mempoolHistory.isEmpty()) {
                    Frigate.getEventBus().post(new SilentPaymentsNotification(notificationSubscription, PROGRESS_COMPLETE, new ArrayList<>(mempoolHistory), subscriptionStatusRef.get()));
                }
            } catch(Throwable t) {
                log.error("Mempool scan task failed for " + scanAddress, t);
            }
        });
    }

    private List<SilentPaymentsTxEntry> getMempoolHistory(SilentPaymentScanAddress scanAddress, Set<Sha256Hash> mempoolTxids, WeakReference<SubscriptionStatus> subscriptionStatusRef, SilentPaymentsSubscription notificationSubscription, BooleanSupplier cancelled) {
        List<SilentPaymentsTxEntry> mempoolHistory = mempoolIndex.getHistoryAsync(scanAddress, notificationSubscription, null, null, mempoolTxids, subscriptionStatusRef, cancelled, false);
        SubscriptionStatus subscriptionStatus = subscriptionStatusRef.get();
        if(subscriptionStatus != null && subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()) != null) {
            mempoolHistory.removeIf(txEntry -> subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()).contains(Sha256Hash.wrap(txEntry.tx_hash)));
        }

        return mempoolHistory;
    }
}
