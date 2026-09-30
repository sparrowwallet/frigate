package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.io.AggregateCounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Produces the server's two stats log lines, which are kept apart so that what is logged frequently cannot expose individual clients:
 * <ul>
 * <li>a health line, every few minutes, of the server's state without any counts: whether the backend is connected for all, some or
 * no sessions, whether reconnects or request timeouts occurred, and the index and mempool state. Counts are avoided even for
 * server events, as the number of reconnects after a backend restart, or of sessions without a connection during an outage, is the
 * exact number of sessions.</li>
 * <li>a usage line, hourly, of figures about the server's users, rounded to the nearest ten with counts below ten suppressed, as for
 * the aggregate scan stats: sessions, IPs and subscriptions connected at that moment, and over the hour the notifications delivered,
 * the largest notification backlog (which reflects one client's activity) and the requests delayed by pacing</li>
 * </ul>
 */
public class ServerStatsLog {
    private final Supplier<ServerStats> statsSupplier;
    private final LongSupplier queueHighWaterSupplier;
    private final LongSupplier clock;
    private ServerStats previousHealth;
    private long previousHealthNanos;
    private ServerStats previousUsage;

    /**
     * @param queueHighWaterSupplier the largest notification backlog since the previous call, resetting it
     */
    public ServerStatsLog(Supplier<ServerStats> statsSupplier, LongSupplier queueHighWaterSupplier, LongSupplier clock) {
        this.statsSupplier = statsSupplier;
        this.queueHighWaterSupplier = queueHighWaterSupplier;
        this.clock = clock;
        ServerStats initial = statsSupplier.get();
        this.previousHealth = initial;
        this.previousUsage = initial;
        this.previousHealthNanos = clock.getAsLong();
        queueHighWaterSupplier.getAsLong();
    }

    /**
     * @return the health line, or empty if there is no backend or Bitcoin Core connection to report on
     */
    public synchronized Optional<String> nextHealthLine() {
        ServerStats current = statsSupplier.get();
        long now = clock.getAsLong();
        Optional<String> line = formatHealth(current, previousHealth, TimeUnit.NANOSECONDS.toSeconds(now - previousHealthNanos));
        previousHealth = current;
        previousHealthNanos = now;
        return line;
    }

    /**
     * @return the usage line, or empty if every figure is below the reporting threshold
     */
    public synchronized Optional<String> nextUsageLine() {
        ServerStats current = statsSupplier.get();
        Optional<String> line = formatUsage(current, previousUsage, queueHighWaterSupplier.getAsLong());
        previousUsage = current;
        return line;
    }

    static Optional<String> formatHealth(ServerStats current, ServerStats previous, long intervalSeconds) {
        List<String> parts = new ArrayList<>();
        if(current.backendConfigured()) {
            String state = current.backendState();
            boolean reconnects = current.backendReconnects() > previous.backendReconnects();
            boolean timeouts = current.backendTimeouts() > previous.backendTimeouts();
            String events = reconnects && timeouts ? "reconnects and request timeouts" : reconnects ? "reconnects" : timeouts ? "request timeouts" : "no reconnects or request timeouts";
            parts.add("backend " + state + ", " + events + " in " + intervalSeconds + "s");
        }
        if(current.tipHeight() != null) {
            parts.add("index " + number(current.indexHeight()) + " of tip " + number(current.tipHeight()) + ", mempool " + number(current.mempoolSize())
                    + (current.mempoolSize() == 1 ? " tx" : " txs"));
        }
        if(parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Server health: " + String.join("; ", parts));
    }

    /**
     * Formats the usage figures in two groups: "now", a snapshot of those connected when the line is logged, and "last 1h", those
     * accumulated since the previous usage line.
     */
    static Optional<String> formatUsage(ServerStats current, ServerStats previous, long queueHighWater) {
        StringBuilder now = new StringBuilder();
        append(now, "sessions", current.sessions());
        append(now, "IPs", current.distinctIps());
        append(now, "scripthash subscriptions", current.scriptHashSubscriptions());
        append(now, "silent payments subscriptions", current.silentPaymentsSubscriptions());

        StringBuilder lastHour = new StringBuilder();
        append(lastHour, "notifications", current.notificationsDelivered() - previous.notificationsDelivered());
        append(lastHour, "notification queue high-water", queueHighWater);
        append(lastHour, "requests delayed by pacing", current.requestsPaced() - previous.requestsPaced());

        if(now.isEmpty() && lastHour.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder line = new StringBuilder("Aggregate server stats:");
        if(!now.isEmpty()) {
            line.append(" now [").append(now).append("]");
        }
        if(!lastHour.isEmpty()) {
            line.append(" last 1h [").append(lastHour).append("]");
        }
        return Optional.of(line.toString());
    }

    private static void append(StringBuilder figures, String label, long raw) {
        long rounded = AggregateCounts.round(raw);
        if(rounded > 0) {
            if(!figures.isEmpty()) {
                figures.append(", ");
            }
            figures.append(label).append(":").append(rounded);
        }
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
