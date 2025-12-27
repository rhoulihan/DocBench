package com.docbench.metrics;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable summary of all collected metrics.
 * Contains histogram summaries and counter values.
 */
public final class MetricsSummary {

    private final Map<String, HistogramSummary> histogramSummaries;
    private final Map<String, Long> counters;

    /**
     * Creates a new MetricsSummary.
     *
     * @param histogramSummaries the histogram summaries by metric name
     * @param counters           the counter values by counter name
     */
    public MetricsSummary(Map<String, HistogramSummary> histogramSummaries, Map<String, Long> counters) {
        this.histogramSummaries = Map.copyOf(
                Objects.requireNonNull(histogramSummaries, "histogramSummaries must not be null")
        );
        this.counters = Map.copyOf(
                Objects.requireNonNull(counters, "counters must not be null")
        );
    }

    /**
     * Creates a new MetricsSummary with only histogram summaries.
     *
     * @param histogramSummaries the histogram summaries by metric name
     */
    public MetricsSummary(Map<String, HistogramSummary> histogramSummaries) {
        this(histogramSummaries, Map.of());
    }

    /**
     * Returns true if the specified metric exists.
     *
     * @param metricName the metric name
     * @return true if the metric exists
     */
    public boolean hasMetric(String metricName) {
        return histogramSummaries.containsKey(metricName);
    }

    /**
     * Returns the histogram summary for the specified metric.
     *
     * @param metricName the metric name
     * @return the histogram summary
     * @throws IllegalArgumentException if the metric does not exist
     */
    public HistogramSummary get(String metricName) {
        HistogramSummary summary = histogramSummaries.get(metricName);
        if (summary == null) {
            throw new IllegalArgumentException("Metric not found: " + metricName);
        }
        return summary;
    }

    /**
     * Returns the histogram summary for the specified metric, or null if not found.
     *
     * @param metricName the metric name
     * @return the histogram summary, or null
     */
    public HistogramSummary getOrNull(String metricName) {
        return histogramSummaries.get(metricName);
    }

    /**
     * Returns all metric names.
     *
     * @return the set of metric names
     */
    public Set<String> metricNames() {
        return histogramSummaries.keySet();
    }

    /**
     * Returns all histogram summaries.
     *
     * @return the map of metric names to histogram summaries
     */
    public Map<String, HistogramSummary> allHistograms() {
        return histogramSummaries;
    }

    /**
     * Returns true if the specified counter exists.
     *
     * @param counterName the counter name
     * @return true if the counter exists
     */
    public boolean hasCounter(String counterName) {
        return counters.containsKey(counterName);
    }

    /**
     * Returns the value of the specified counter.
     *
     * @param counterName the counter name
     * @return the counter value
     * @throws IllegalArgumentException if the counter does not exist
     */
    public long getCounter(String counterName) {
        Long value = counters.get(counterName);
        if (value == null) {
            throw new IllegalArgumentException("Counter not found: " + counterName);
        }
        return value;
    }

    /**
     * Returns the value of the specified counter, or 0 if not found.
     *
     * @param counterName the counter name
     * @return the counter value, or 0
     */
    public long getCounterOrZero(String counterName) {
        return counters.getOrDefault(counterName, 0L);
    }

    /**
     * Returns all counter names.
     *
     * @return the set of counter names
     */
    public Set<String> counterNames() {
        return counters.keySet();
    }

    /**
     * Returns all counters.
     *
     * @return the map of counter names to values
     */
    public Map<String, Long> allCounters() {
        return counters;
    }

    /**
     * Returns the total number of metrics (histograms + counters).
     *
     * @return the total count
     */
    public int totalMetrics() {
        return histogramSummaries.size() + counters.size();
    }

    @Override
    public String toString() {
        return "MetricsSummary{" +
                "histograms=" + histogramSummaries.keySet() +
                ", counters=" + counters.keySet() +
                '}';
    }
}
