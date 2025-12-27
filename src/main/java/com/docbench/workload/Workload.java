package com.docbench.workload;

import com.docbench.adapter.spi.DatabaseAdapter;
import com.docbench.metrics.MetricsCollector;

/**
 * Defines a benchmark workload that can be executed against a database adapter.
 * Each workload encapsulates a specific access pattern to benchmark.
 */
public interface Workload {

    /**
     * Returns the unique name of this workload.
     */
    String name();

    /**
     * Returns a description of what this workload measures.
     */
    String description();

    /**
     * Initializes the workload with the given configuration.
     * Called once before any iterations are run.
     *
     * @param config the workload configuration
     */
    void initialize(WorkloadConfig config);

    /**
     * Sets up test data in the database.
     * Called once after initialize, before warmup.
     *
     * @param adapter the database adapter to use
     */
    void setupData(DatabaseAdapter adapter);

    /**
     * Runs a single iteration of the workload.
     * Should perform the operation being benchmarked.
     *
     * @param adapter the database adapter to use
     * @param collector metrics collector to record timings
     */
    void runIteration(DatabaseAdapter adapter, MetricsCollector collector);

    /**
     * Cleans up any test data created by the workload.
     * Called after all iterations complete.
     *
     * @param adapter the database adapter to use
     */
    void cleanup(DatabaseAdapter adapter);

    /**
     * Returns the current workload configuration.
     */
    WorkloadConfig config();
}
