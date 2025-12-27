package com.docbench.orchestrator;

import com.docbench.adapter.spi.DatabaseAdapter;
import com.docbench.metrics.MetricsCollector;
import com.docbench.report.BenchmarkResult;
import com.docbench.workload.Workload;
import com.docbench.workload.WorkloadConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Executes benchmarks and collects results.
 * Orchestrates warmup, iteration execution, and metrics collection.
 */
public class BenchmarkExecutor {

    private final boolean verbose;

    public BenchmarkExecutor() {
        this(false);
    }

    public BenchmarkExecutor(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Executes a workload against an adapter and returns the result.
     */
    public BenchmarkResult.AdapterResult execute(
            Workload workload,
            DatabaseAdapter adapter,
            WorkloadConfig config) {

        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(config, "config");

        log("Initializing workload: " + workload.name());
        workload.initialize(config);

        log("Setting up test data...");
        Instant setupStart = Instant.now();
        try {
            workload.setupData(adapter);
        } catch (Exception e) {
            log("Setup failed: " + e.getMessage());
            throw new RuntimeException("Failed to setup test data", e);
        }
        log("Setup completed in " + Duration.between(setupStart, Instant.now()).toMillis() + "ms");

        MetricsCollector collector = new MetricsCollector();

        // Warmup phase
        log("Running " + config.warmupIterations() + " warmup iterations...");
        MetricsCollector warmupCollector = new MetricsCollector();
        for (int i = 0; i < config.warmupIterations(); i++) {
            try {
                workload.runIteration(adapter, warmupCollector);
            } catch (Exception e) {
                log("Warmup iteration " + i + " failed: " + e.getMessage());
            }
        }
        log("Warmup completed");

        // Benchmark phase
        log("Running " + config.iterations() + " benchmark iterations...");
        Instant benchStart = Instant.now();

        int successCount = 0;
        int errorCount = 0;

        for (int i = 0; i < config.iterations(); i++) {
            try {
                workload.runIteration(adapter, collector);
                successCount++;

                // Progress indicator
                if (verbose && (i + 1) % 1000 == 0) {
                    log("  Completed " + (i + 1) + "/" + config.iterations() + " iterations");
                }
            } catch (Exception e) {
                errorCount++;
                if (verbose) {
                    log("Iteration " + i + " failed: " + e.getMessage());
                }
            }
        }

        Instant benchEnd = Instant.now();
        Duration benchDuration = Duration.between(benchStart, benchEnd);

        log("Benchmark completed: " + successCount + " successful, " + errorCount + " errors");
        log("Total time: " + benchDuration.toMillis() + "ms");

        // Cleanup
        log("Cleaning up...");
        try {
            workload.cleanup(adapter);
        } catch (Exception e) {
            log("Cleanup warning: " + e.getMessage());
        }

        return new BenchmarkResult.AdapterResult(
                adapter.getAdapterId(),
                adapter.getDisplayName(),
                collector.summarize(),
                config.iterations(),
                config.warmupIterations(),
                benchDuration
        );
    }

    private void log(String message) {
        if (verbose) {
            System.out.println("[BenchmarkExecutor] " + message);
        }
    }
}
