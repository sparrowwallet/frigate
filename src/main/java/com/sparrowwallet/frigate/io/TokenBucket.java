package com.sparrowwallet.frigate.io;

import java.util.function.LongSupplier;

/**
 * Paces requests: each takes tokens from a bucket that refills at a steady rate up to its capacity, and waits when too few
 * remain. Waiting rather than failing gives clients backpressure, so a burst of requests is slowed rather than rejected. The
 * bucket starts full.
 *
 * A request costing more than the capacity waits for a full bucket and then takes its whole cost, leaving the bucket in debt.
 * Later requests wait while the debt is repaid, so the long-run rate matches what requests actually cost, rather than capping
 * the price of an expensive request (such as a batch of silent payments subscribes) at the bucket's capacity.
 */
public final class TokenBucket {
    private final long capacity;
    private final double refillPerSecond;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime, Thread::sleep);
    }

    TokenBucket(long capacity, double refillPerSecond, LongSupplier clock, Sleeper sleeper) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
        this.sleeper = sleeper;
        this.tokens = capacity;
        this.lastRefillNanos = clock.getAsLong();
    }

    /**
     * Takes cost tokens, waiting until they are available. A cost above the capacity waits for a full bucket, then takes the whole
     * cost, leaving the bucket in debt. Waits happen outside the monitor, so they never block other users of this bucket.
     * @return true if the caller had to wait
     */
    public boolean acquire(long cost) throws InterruptedException {
        if(cost <= 0) {
            return false;
        }

        long required = Math.min(cost, capacity);
        boolean waited = false;
        while(true) {
            long sleepMillis;
            synchronized(this) {
                refill();
                if(tokens >= required) {
                    tokens -= cost;
                    return waited;
                }
                sleepMillis = Math.max(1, (long)Math.ceil((required - tokens) / refillPerSecond * 1000));
            }
            sleeper.sleep(sleepMillis);
            waited = true;
        }
    }

    public synchronized double getTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = clock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1e9 * refillPerSecond);
        lastRefillNanos = now;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
