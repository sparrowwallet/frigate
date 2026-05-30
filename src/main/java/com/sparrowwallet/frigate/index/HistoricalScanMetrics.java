package com.sparrowwallet.frigate.index;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Privacy-preserving aggregate counters for historical scan throughput.
 */
class HistoricalScanMetrics {
    private static final int MIN_SAMPLES_PER_BUCKET = 10;
    private static final int COUNT_ROUNDING = 10;

    static final String[] RESULT_LABELS = {"0", "1-10", "11-100", "101-1000", "1001-10000", "10001+"};
    static final String[] DURATION_LABELS = {"0-100ms", "100-500ms", "500ms-2s", "2-10s", "10-60s", "60s+"};

    private final AtomicIntegerArray resultBuckets = new AtomicIntegerArray(RESULT_LABELS.length);
    private final AtomicIntegerArray durationBuckets = new AtomicIntegerArray(DURATION_LABELS.length);

    record Snapshot(int[] results, int[] durations) {}

    void record(int resultCount, long durationMillis) {
        resultBuckets.incrementAndGet(resultBucket(resultCount));
        durationBuckets.incrementAndGet(durationBucket(durationMillis));
    }

    //a record() concurrent with snapshotAndReset() may have its two increments split across windows. The per-bucket drift is at
    //most one sample per emission and never crosses subscription identity, so it is fine for an aggregate stat.
    Snapshot snapshotAndReset() {
        int[] results = new int[RESULT_LABELS.length];
        int[] durations = new int[DURATION_LABELS.length];
        for(int i = 0; i < results.length; i++) {
            results[i] = resultBuckets.getAndSet(i, 0);
        }
        for(int i = 0; i < durations.length; i++) {
            durations[i] = durationBuckets.getAndSet(i, 0);
        }
        return new Snapshot(results, durations);
    }

    Optional<String> format(Snapshot snapshot) {
        String resultsStr = formatHist(snapshot.results(), RESULT_LABELS);
        String durationsStr = formatHist(snapshot.durations(), DURATION_LABELS);
        if(resultsStr.isEmpty() && durationsStr.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder("Aggregate SP scan stats (1h window):");
        if(!resultsStr.isEmpty()) {
            sb.append(" results [").append(resultsStr).append("]");
        }
        if(!durationsStr.isEmpty()) {
            sb.append(" duration [").append(durationsStr).append("]");
        }
        return Optional.of(sb.toString());
    }

    static int resultBucket(int n) {
        if(n <= 0) return 0;
        if(n <= 10) return 1;
        if(n <= 100) return 2;
        if(n <= 1000) return 3;
        if(n <= 10000) return 4;
        return 5;
    }

    static int durationBucket(long ms) {
        if(ms < 100) return 0;
        if(ms < 500) return 1;
        if(ms < 2000) return 2;
        if(ms < 10000) return 3;
        if(ms < 60000) return 4;
        return 5;
    }

    private static String formatHist(int[] counts, String[] labels) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < counts.length; i++) {
            int rounded = roundCount(counts[i]);
            if(rounded > 0) {
                if(sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(labels[i]).append(":").append(rounded);
            }
        }
        return sb.toString();
    }

    private static int roundCount(int raw) {
        if(raw < MIN_SAMPLES_PER_BUCKET) {
            return 0;
        }
        return ((raw + COUNT_ROUNDING / 2) / COUNT_ROUNDING) * COUNT_ROUNDING;
    }
}
