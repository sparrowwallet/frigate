package com.sparrowwallet.frigate.io;

/**
 * The rounding applied to counts in aggregate log lines, so they describe the server's use without exposing individual clients:
 * counts are rounded to the nearest ten, and counts below ten are suppressed entirely.
 */
public final class AggregateCounts {
    public static final int MIN_COUNT = 10;
    public static final int ROUNDING = 10;

    private AggregateCounts() {
    }

    /**
     * @return the count rounded to the nearest ten, or 0 if it is below ten and should not be shown
     */
    public static long round(long raw) {
        if(raw < MIN_COUNT) {
            return 0;
        }
        return ((raw + ROUNDING / 2) / ROUNDING) * ROUNDING;
    }
}
