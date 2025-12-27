package com.docbench.report;

import com.docbench.metrics.MetricsSummary;
import com.docbench.workload.WorkloadConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encapsulates all data from a benchmark run for reporting.
 * Contains metrics from multiple adapters for comparison.
 */
public final class BenchmarkResult {

    private final String workloadName;
    private final WorkloadConfig config;
    private final Map<String, AdapterResult> adapterResults;
    private final Instant startTime;
    private final Instant endTime;
    private final Duration totalDuration;

    private BenchmarkResult(Builder builder) {
        this.workloadName = builder.workloadName;
        this.config = builder.config;
        this.adapterResults = Map.copyOf(builder.adapterResults);
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.totalDuration = builder.totalDuration;
    }

    public static Builder builder(String workloadName) {
        return new Builder(workloadName);
    }

    public String workloadName() {
        return workloadName;
    }

    public WorkloadConfig config() {
        return config;
    }

    public Map<String, AdapterResult> adapterResults() {
        return adapterResults;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    public Duration totalDuration() {
        return totalDuration;
    }

    /**
     * Results from a single adapter run.
     */
    public record AdapterResult(
            String adapterId,
            String adapterName,
            MetricsSummary metrics,
            int iterations,
            int warmupIterations,
            Duration duration
    ) {
        public AdapterResult {
            Objects.requireNonNull(adapterId);
            Objects.requireNonNull(adapterName);
            Objects.requireNonNull(metrics);
        }
    }

    public static final class Builder {
        private final String workloadName;
        private WorkloadConfig config;
        private final Map<String, AdapterResult> adapterResults = new LinkedHashMap<>();
        private Instant startTime = Instant.now();
        private Instant endTime;
        private Duration totalDuration;

        private Builder(String workloadName) {
            this.workloadName = Objects.requireNonNull(workloadName);
        }

        public Builder config(WorkloadConfig config) {
            this.config = config;
            return this;
        }

        public Builder addAdapterResult(AdapterResult result) {
            this.adapterResults.put(result.adapterId(), result);
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder totalDuration(Duration totalDuration) {
            this.totalDuration = totalDuration;
            return this;
        }

        public BenchmarkResult build() {
            if (endTime == null) {
                endTime = Instant.now();
            }
            if (totalDuration == null && startTime != null && endTime != null) {
                totalDuration = Duration.between(startTime, endTime);
            }
            return new BenchmarkResult(this);
        }
    }
}
