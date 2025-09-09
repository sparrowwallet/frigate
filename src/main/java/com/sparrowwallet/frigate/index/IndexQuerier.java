package com.sparrowwallet.frigate.index;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.SubscriptionStatus;
import com.sparrowwallet.frigate.electrum.SilentPaymentsNotification;
import com.sparrowwallet.frigate.electrum.SilentPaymentsSubscription;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class IndexQuerier {
    public static final double PROGRESS_COMPLETE = 1.0d;

    private final Index blocksIndex;
    private final Index mempoolIndex;

    public IndexQuerier(Index blocksIndex, Index mempoolIndex) {
        this.blocksIndex = blocksIndex;
        this.mempoolIndex = mempoolIndex;
    }

    public List<TweakEntry> getTweaksByHeight(int blockHeight) {
        return blocksIndex.getTweaksByHeight(blockHeight);
    }

    private final ExecutorService queryPool = Executors.newFixedThreadPool(10, r -> {
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("IndexQuery-%d").build();
        Thread t = namedThreadFactory.newThread(r);
        t.setDaemon(true);
        return t;
    });

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        startHistoryScan(scanAddress, startHeight, endHeight, subscriptionStatusRef, true);
    }

    public void startHistoryScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef, boolean postIfEmpty) {
        queryPool.submit(() -> {
            SilentPaymentsSubscription subscription = new SilentPaymentsSubscription(scanAddress.toString(), startHeight == null ? 0 : startHeight);
            List<TxEntry> history = blocksIndex.getHistoryAsync(scanAddress, subscription, startHeight, endHeight, subscriptionStatusRef);
            List<TxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, subscription);
            history.addAll(mempoolHistory);

            if(postIfEmpty || !history.isEmpty()) {
                Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, PROGRESS_COMPLETE, new ArrayList<>(history), subscriptionStatusRef.get()));
            }
        });
    }

    public void startMempoolScan(SilentPaymentScanAddress scanAddress, Integer startHeight, Integer endHeight, WeakReference<SubscriptionStatus> subscriptionStatusRef) {
        queryPool.submit(() -> {
            SilentPaymentsSubscription subscription = new SilentPaymentsSubscription(scanAddress.toString(), startHeight == null ? 0 : startHeight);
            List<TxEntry> mempoolHistory = getMempoolHistory(scanAddress, subscriptionStatusRef, subscription);

            if(!mempoolHistory.isEmpty()) {
                Frigate.getEventBus().post(new SilentPaymentsNotification(subscription, PROGRESS_COMPLETE, new ArrayList<>(mempoolHistory), subscriptionStatusRef.get()));
            }
        });
    }

    private List<TxEntry> getMempoolHistory(SilentPaymentScanAddress scanAddress, WeakReference<SubscriptionStatus> subscriptionStatusRef, SilentPaymentsSubscription subscription) {
        List<TxEntry> mempoolHistory = mempoolIndex.getHistoryAsync(scanAddress, subscription, null, null, subscriptionStatusRef);
        SubscriptionStatus subscriptionStatus = subscriptionStatusRef.get();
        if(subscriptionStatus != null && subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()) != null) {
            mempoolHistory.removeIf(txEntry -> subscriptionStatus.getSilentPaymentsMempoolTxids(scanAddress.toString()).contains(Sha256Hash.wrap(txEntry.tx_hash)));
        }

        return mempoolHistory;
    }
}
