package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentScanAddress;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class SilentPaymentAddressSubscription {
    private final SilentPaymentScanAddress address;
    private final Set<Integer> labels;
    private final int startHeight;
    private final AtomicInteger highestBlockHeight = new AtomicInteger();
    private final Set<Sha256Hash> mempoolTxids = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private volatile boolean historicalComplete;
    private final AtomicBoolean pendingHistoricalRescan = new AtomicBoolean(false);
    private final AtomicLong scanEpoch = new AtomicLong();

    public SilentPaymentAddressSubscription(SilentPaymentScanAddress address, Set<Integer> labels, int startHeight) {
        this.address = address;
        this.labels = labels;
        this.startHeight = startHeight;
    }

    public int getStartHeight() {
        return startHeight;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BooleanSupplier captureScanCancellation() {
        long captured = scanEpoch.get();
        return () -> scanEpoch.get() != captured;
    }

    public void invalidateInFlightScans() {
        scanEpoch.incrementAndGet();
    }

    public boolean isHistoricalComplete() {
        return historicalComplete;
    }

    public void markHistoricalComplete() {
        historicalComplete = true;
    }

    public void markPendingHistoricalRescan() {
        pendingHistoricalRescan.set(true);
    }

    public boolean isPendingHistoricalRescan() {
        return pendingHistoricalRescan.get();
    }

    public boolean consumePendingHistoricalRescan() {
        return pendingHistoricalRescan.getAndSet(false);
    }

    public SilentPaymentScanAddress getAddress() {
        return address;
    }

    public Set<Integer> getLabels() {
        return labels;
    }

    public int getHighestBlockHeight() {
        return highestBlockHeight.get();
    }

    public void accumulateMaxBlockHeight(int candidate) {
        highestBlockHeight.accumulateAndGet(candidate, Math::max);
    }

    public void accumulateMinBlockHeight(int candidate) {
        highestBlockHeight.accumulateAndGet(candidate, Math::min);
    }

    public Set<Sha256Hash> getMempoolTxids() {
        return mempoolTxids;
    }
}
