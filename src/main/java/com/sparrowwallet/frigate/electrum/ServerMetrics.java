package com.sparrowwallet.frigate.electrum;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Server-wide counters, updated directly where events happen, so the counts from sessions that have closed are not lost. They
 * are cumulative for the life of the process; the stats line reports their change over each interval.
 */
public final class ServerMetrics {
    private static final LongAdder NOTIFICATIONS_DELIVERED = new LongAdder();
    private static final LongAdder BACKEND_RECONNECTS = new LongAdder();
    private static final LongAdder BACKEND_TIMEOUTS = new LongAdder();
    private static final LongAccumulator NOTIFIER_QUEUE_HIGH_WATER = new LongAccumulator(Math::max, 0);

    private ServerMetrics() {
    }

    static void notificationDelivered() {
        NOTIFICATIONS_DELIVERED.increment();
    }

    static void backendReconnected() {
        BACKEND_RECONNECTS.increment();
    }

    static void backendTimedOut() {
        BACKEND_TIMEOUTS.increment();
    }

    static void notifierQueueDepth(int depth) {
        NOTIFIER_QUEUE_HIGH_WATER.accumulate(depth);
    }

    public static long getNotificationsDelivered() {
        return NOTIFICATIONS_DELIVERED.sum();
    }

    public static long getBackendReconnects() {
        return BACKEND_RECONNECTS.sum();
    }

    public static long getBackendTimeouts() {
        return BACKEND_TIMEOUTS.sum();
    }

    /**
     * @return the largest notification backlog any session has reached since the last call, resetting it for the next interval
     */
    public static long takeNotifierQueueHighWater() {
        return NOTIFIER_QUEUE_HIGH_WATER.getThenReset();
    }
}
