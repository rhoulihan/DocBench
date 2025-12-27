package com.docbench.metrics;

import java.time.Duration;

/**
 * Immutable summary of a histogram's statistical distribution.
 * All values are in nanoseconds.
 */
public record HistogramSummary(
        long count,
        double mean,
        long p50,
        long p90,
        long p95,
        long p99,
        long p999,
        long min,
        long max,
        double stdDev
) {

    /**
     * Returns the count of recorded values.
     */
    @Override
    public long count() {
        return count;
    }

    /**
     * Returns the arithmetic mean in nanoseconds.
     */
    @Override
    public double mean() {
        return mean;
    }

    /**
     * Returns the mean as a Duration.
     */
    public Duration meanDuration() {
        return Duration.ofNanos((long) mean);
    }

    /**
     * Returns the 50th percentile (median) in nanoseconds.
     */
    @Override
    public long p50() {
        return p50;
    }

    /**
     * Returns the 50th percentile as a Duration.
     */
    public Duration p50Duration() {
        return Duration.ofNanos(p50);
    }

    /**
     * Returns the 90th percentile in nanoseconds.
     */
    @Override
    public long p90() {
        return p90;
    }

    /**
     * Returns the 90th percentile as a Duration.
     */
    public Duration p90Duration() {
        return Duration.ofNanos(p90);
    }

    /**
     * Returns the 95th percentile in nanoseconds.
     */
    @Override
    public long p95() {
        return p95;
    }

    /**
     * Returns the 95th percentile as a Duration.
     */
    public Duration p95Duration() {
        return Duration.ofNanos(p95);
    }

    /**
     * Returns the 99th percentile in nanoseconds.
     */
    @Override
    public long p99() {
        return p99;
    }

    /**
     * Returns the 99th percentile as a Duration.
     */
    public Duration p99Duration() {
        return Duration.ofNanos(p99);
    }

    /**
     * Returns the 99.9th percentile in nanoseconds.
     */
    @Override
    public long p999() {
        return p999;
    }

    /**
     * Returns the 99.9th percentile as a Duration.
     */
    public Duration p999Duration() {
        return Duration.ofNanos(p999);
    }

    /**
     * Returns the minimum value in nanoseconds.
     */
    @Override
    public long min() {
        return min;
    }

    /**
     * Returns the minimum value as a Duration.
     */
    public Duration minDuration() {
        return Duration.ofNanos(min);
    }

    /**
     * Returns the maximum value in nanoseconds.
     */
    @Override
    public long max() {
        return max;
    }

    /**
     * Returns the maximum value as a Duration.
     */
    public Duration maxDuration() {
        return Duration.ofNanos(max);
    }

    /**
     * Returns the standard deviation in nanoseconds.
     */
    @Override
    public double stdDev() {
        return stdDev;
    }

    /**
     * Returns the mean in microseconds.
     */
    public double meanMicros() {
        return mean / 1000.0;
    }

    /**
     * Returns the p50 in microseconds.
     */
    public double p50Micros() {
        return p50 / 1000.0;
    }

    /**
     * Returns the p99 in microseconds.
     */
    public double p99Micros() {
        return p99 / 1000.0;
    }

    /**
     * Returns the range (max - min) in nanoseconds.
     */
    public long range() {
        return max - min;
    }

    /**
     * Returns the coefficient of variation (stdDev / mean).
     */
    public double coefficientOfVariation() {
        if (mean == 0) {
            return 0;
        }
        return stdDev / mean;
    }

    /**
     * Returns a formatted summary string.
     */
    public String toFormattedString() {
        return String.format(
                "count=%d, mean=%.2fus, p50=%.2fus, p99=%.2fus, min=%.2fus, max=%.2fus",
                count,
                meanMicros(),
                p50Micros(),
                p99Micros(),
                min / 1000.0,
                max / 1000.0
        );
    }
}
