package com.sparrowwallet.frigate.electrum;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ServerStatsLogTest {
    private static ServerStats stats(int sessions, int backendConnected, long notifications, long reconnects, long timeouts) {
        return new ServerStats(sessions, 94, 3402, 27, 4, notifications, true, sessions, backendConnected, reconnects, timeouts, 915_000, 914_999, 45_678, 0);
    }

    @Test
    public void formatsHealthLine() {
        assertEquals(Optional.of("Server health: backend connected; index 914,999 of tip 915,000, mempool 45,678 txs"),
                ServerStatsLog.formatHealth(stats(12, 12, 0, 4, 2), stats(12, 12, 0, 4, 2)));
    }

    @Test
    public void healthLineReportsBackendStateWithoutCounts() {
        //a backend restart: every session reconnects and some requests time out, which as counts would give the session count
        String restarted = ServerStatsLog.formatHealth(stats(12, 12, 0, 16, 5), stats(12, 12, 0, 4, 2)).orElseThrow();
        assertTrue(restarted.startsWith("Server health: backend connected, reconnects and request timeouts;"), restarted);

        String partly = ServerStatsLog.formatHealth(stats(12, 5, 0, 4, 3), stats(12, 12, 0, 4, 2)).orElseThrow();
        assertTrue(partly.startsWith("Server health: backend partly disconnected, request timeouts;"), partly);

        String down = ServerStatsLog.formatHealth(stats(12, 0, 0, 5, 2), stats(12, 12, 0, 4, 2)).orElseThrow();
        assertTrue(down.startsWith("Server health: backend disconnected, reconnects;"), down);

        //no figure about the server's users appears in any health line
        for(String line : List.of(restarted, partly, down)) {
            assertFalse(line.matches(".*\\b(12|5|7|3)\\b.*"), line);
        }
    }

    @Test
    public void healthLineOmitsAbsentParts() {
        ServerStats noBackend = new ServerStats(3, 2, 5, 0, 2, 9, false, 0, 0, 0, 0, 915_000, 915_000, 1, 0);
        assertEquals(Optional.of("Server health: index at tip 915,000, mempool 1 tx"), ServerStatsLog.formatHealth(noBackend, noBackend));

        ServerStats nothing = new ServerStats(3, 2, 5, 0, 2, 9, false, 0, 0, 0, 0, null, null, null, 0);
        assertEquals(Optional.empty(), ServerStatsLog.formatHealth(nothing, nothing));
    }

    @Test
    public void usageLineIsRoundedAndSuppressesSmallCounts() {
        ServerStats previous = new ServerStats(0, 0, 0, 0, 0, 1000, true, 0, 0, 0, 0, null, null, null, 0);
        ServerStats current = new ServerStats(124, 94, 3402, 7, 0, 13_206, true, 124, 124, 0, 0, null, null, null, 0);

        //7 silent payments subscriptions are below the threshold and left out; the rest are rounded to the nearest ten
        assertEquals(Optional.of("Aggregate server stats: now [sessions:120, IPs:90, scripthash subscriptions:3400] "
                + "last 1h [notifications:12210, notification queue high-water:40]"), ServerStatsLog.formatUsage(current, previous, 37));
    }

    @Test
    public void usageLineReportsPacedRequestsOverTheHour() {
        ServerStats previous = new ServerStats(0, 0, 0, 0, 0, 1000, true, 0, 0, 0, 0, null, null, null, 250);
        ServerStats current = new ServerStats(124, 94, 3402, 0, 0, 1004, true, 124, 124, 0, 0, null, null, null, 4_318);

        assertEquals(Optional.of("Aggregate server stats: now [sessions:120, IPs:90, scripthash subscriptions:3400] "
                + "last 1h [silent payments subscribes delayed by pacing:4070]"), ServerStatsLog.formatUsage(current, previous, 0));
    }

    @Test
    public void usageLineOmitsEmptyGroup() {
        ServerStats previous = new ServerStats(0, 0, 0, 0, 0, 13_000, true, 0, 0, 0, 0, null, null, null, 0);
        ServerStats quiet = new ServerStats(124, 94, 3402, 0, 0, 13_004, true, 124, 124, 0, 0, null, null, null, 0);
        assertEquals(Optional.of("Aggregate server stats: now [sessions:120, IPs:90, scripthash subscriptions:3400]"), ServerStatsLog.formatUsage(quiet, previous, 2));

        ServerStats emptied = new ServerStats(3, 2, 5, 0, 0, 13_500, true, 3, 3, 0, 0, null, null, null, 0);
        assertEquals(Optional.of("Aggregate server stats: last 1h [notifications:500]"), ServerStatsLog.formatUsage(emptied, previous, 0));
    }

    @Test
    public void usageLineIsOmittedWhenAllCountsAreSmall() {
        ServerStats previous = new ServerStats(0, 0, 0, 0, 0, 100, true, 0, 0, 0, 0, null, null, null, 0);
        ServerStats current = new ServerStats(2, 1, 9, 1, 0, 104, true, 2, 2, 0, 0, null, null, null, 0);

        assertEquals(Optional.empty(), ServerStatsLog.formatUsage(current, previous, 3));
    }

    @Test
    public void linesReportChangeSinceTheirOwnPreviousLine() {
        Deque<ServerStats> snapshots = new ArrayDeque<>(List.of(stats(20, 20, 100, 1, 0), stats(20, 20, 150, 1, 0), stats(20, 20, 400, 3, 1), stats(20, 20, 600, 3, 1)));
        Deque<Long> highWaters = new ArrayDeque<>(List.of(99L, 25L));

        ServerStatsLog statsLog = new ServerStatsLog(snapshots::poll, highWaters::poll);
        //the high-water from before the log started is discarded
        assertEquals(1, highWaters.size());

        String firstHealth = statsLog.nextHealthLine().orElseThrow();
        assertTrue(firstHealth.startsWith("Server health: backend connected;"), firstHealth);

        String secondHealth = statsLog.nextHealthLine().orElseThrow();
        assertTrue(secondHealth.startsWith("Server health: backend connected, reconnects and request timeouts;"), secondHealth);

        //the usage line's notifications are counted since the previous usage line, independently of the health lines
        assertEquals(Optional.of("Aggregate server stats: now [sessions:20, IPs:90, scripthash subscriptions:3400, silent payments subscriptions:30] "
                + "last 1h [notifications:500, notification queue high-water:30]"), statsLog.nextUsageLine());
    }
}
