package com.sparrowwallet.frigate.index;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HistoricalScanMetricsTest {
    @Test
    public void resultBucketBoundaries() {
        assertEquals(0, HistoricalScanMetrics.resultBucket(0));
        assertEquals(1, HistoricalScanMetrics.resultBucket(1));
        assertEquals(1, HistoricalScanMetrics.resultBucket(10));
        assertEquals(2, HistoricalScanMetrics.resultBucket(11));
        assertEquals(2, HistoricalScanMetrics.resultBucket(100));
        assertEquals(3, HistoricalScanMetrics.resultBucket(101));
        assertEquals(3, HistoricalScanMetrics.resultBucket(1000));
        assertEquals(4, HistoricalScanMetrics.resultBucket(1001));
        assertEquals(4, HistoricalScanMetrics.resultBucket(10000));
        assertEquals(5, HistoricalScanMetrics.resultBucket(10001));
    }

    //pair each sample input with the label the log line will use, so a future edit that changes a boundary without updating
    //the corresponding label (or vice versa) fails here instead of silently producing a misleading aggregate line.
    @Test
    public void resultLabelsMatchBoundaries() {
        assertEquals("0", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(0)]);
        assertEquals("1-10", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(5)]);
        assertEquals("11-100", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(50)]);
        assertEquals("101-1000", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(500)]);
        assertEquals("1001-10000", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(5000)]);
        assertEquals("10001+", HistoricalScanMetrics.RESULT_LABELS[HistoricalScanMetrics.resultBucket(50000)]);
    }

    @Test
    public void durationLabelsMatchBoundaries() {
        assertEquals("0-100ms", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(50)]);
        assertEquals("100-500ms", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(250)]);
        assertEquals("500ms-2s", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(1000)]);
        assertEquals("2-10s", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(5000)]);
        assertEquals("10-60s", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(30000)]);
        assertEquals("60s+", HistoricalScanMetrics.DURATION_LABELS[HistoricalScanMetrics.durationBucket(120000)]);
    }

    @Test
    public void durationBucketBoundaries() {
        assertEquals(0, HistoricalScanMetrics.durationBucket(0));
        assertEquals(0, HistoricalScanMetrics.durationBucket(99));
        assertEquals(1, HistoricalScanMetrics.durationBucket(100));
        assertEquals(1, HistoricalScanMetrics.durationBucket(499));
        assertEquals(2, HistoricalScanMetrics.durationBucket(500));
        assertEquals(2, HistoricalScanMetrics.durationBucket(1999));
        assertEquals(3, HistoricalScanMetrics.durationBucket(2000));
        assertEquals(3, HistoricalScanMetrics.durationBucket(9999));
        assertEquals(4, HistoricalScanMetrics.durationBucket(10000));
        assertEquals(4, HistoricalScanMetrics.durationBucket(59999));
        assertEquals(5, HistoricalScanMetrics.durationBucket(60000));
    }

    @Test
    public void thresholdSuppressesBelowTen() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 9; i++) {
            m.record(5, 200);
        }
        assertTrue(m.format(m.snapshotAndReset()).isEmpty());
    }

    @Test
    public void exactlyTenEmitsAsTen() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 10; i++) {
            m.record(5, 200);
        }
        String line = m.format(m.snapshotAndReset()).orElseThrow();
        assertTrue(line.contains("1-10:10"), line);
        assertTrue(line.contains("100-500ms:10"), line);
    }

    @Test
    public void fourteenRoundsDownToTen() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 14; i++) {
            m.record(5, 200);
        }
        assertTrue(m.format(m.snapshotAndReset()).orElseThrow().contains("1-10:10"));
    }

    @Test
    public void fifteenRoundsUpToTwenty() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 15; i++) {
            m.record(5, 200);
        }
        assertTrue(m.format(m.snapshotAndReset()).orElseThrow().contains("1-10:20"));
    }

    @Test
    public void snapshotResetsCounters() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 20; i++) {
            m.record(5, 200);
        }
        m.snapshotAndReset();
        assertTrue(m.format(m.snapshotAndReset()).isEmpty());
    }

    //spread result counts across all five non-zero buckets so no result bucket reaches threshold, while every duration lands in
    //the same bucket so it does — verifies one-sided emission.
    @Test
    public void onlyOneHistogramEmittedWhenOnlyOneClearsThreshold() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        int[] resultsPerBucket = {0, 0, 5, 5, 50, 50, 500, 500, 5000, 5000};
        for(int n : resultsPerBucket) {
            m.record(n, 200);
        }
        String line = m.format(m.snapshotAndReset()).orElseThrow();
        assertFalse(line.contains("results ["), line);
        assertTrue(line.contains("duration ["), line);
    }

    @Test
    public void emptyWhenNoRecords() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        assertTrue(m.format(m.snapshotAndReset()).isEmpty());
    }

    //mechanical guardrail: future edits to the format must not introduce summary statistics, relative timestamps, or absolute
    //ISO timestamps. The forbidden tokens are bare (no colons) so "last value: 123ms" or "min 100ms" both trip the check.
    @Test
    public void formattedLineHasNoForbiddenSubstrings() {
        HistoricalScanMetrics m = new HistoricalScanMetrics();
        for(int i = 0; i < 20; i++) {
            m.record(50, 1500);
        }
        String line = m.format(m.snapshotAndReset()).orElseThrow();
        String lower = line.toLowerCase();
        for(String forbidden : new String[]{"min", "max", "avg", "mean", "last", "ago"}) {
            assertFalse(lower.contains(forbidden), "line must not contain '" + forbidden + "': " + line);
        }
        assertFalse(line.matches(".*\\d{4}-\\d{2}-\\d{2}.*"), "line must not contain ISO date: " + line);
        assertFalse(line.matches(".*\\d{2}:\\d{2}:\\d{2}.*"), "line must not contain ISO time: " + line);
    }
}
