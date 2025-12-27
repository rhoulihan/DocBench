package com.docbench.adapter.spi;

import com.docbench.metrics.MetricsCollector;
import com.docbench.metrics.OverheadBreakdown;

import java.util.List;
import java.util.Set;

/**
 * Core extension point for database platform support.
 * Implementations must be thread-safe and support concurrent execution.
 *
 * <p>This SPI defines the contract for database adapters, ensuring consistent
 * measurement capabilities across all supported databases. Each adapter is
 * responsible for:
 * <ul>
 *   <li>Establishing instrumented connections</li>
 *   <li>Executing operations with timing capture</li>
 *   <li>Decomposing overhead into measurable components</li>
 *   <li>Reporting platform capabilities</li>
 * </ul>
 */
public interface DatabaseAdapter extends AutoCloseable {

    /**
     * Unique adapter identifier for registry and reporting.
     * Convention: lowercase, hyphenated (e.g., "mongodb-sharded", "oracle-oson").
     *
     * @return the adapter ID
     */
    String getAdapterId();

    /**
     * Human-readable name for reports.
     *
     * @return the display name
     */
    String getDisplayName();

    /**
     * Returns the version of this adapter implementation.
     *
     * @return the version string
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * Platform capabilities determining workload compatibility.
     * Used by the framework to validate workload-adapter compatibility
     * before execution.
     *
     * @return the set of supported capabilities
     */
    Set<Capability> getCapabilities();

    /**
     * Returns true if this adapter supports the specified capability.
     *
     * @param capability the capability to check
     * @return true if supported
     */
    default boolean hasCapability(Capability capability) {
        return getCapabilities().contains(capability);
    }

    /**
     * Returns true if this adapter supports all specified capabilities.
     *
     * @param capabilities the capabilities to check
     * @return true if all are supported
     */
    default boolean hasAllCapabilities(Set<Capability> capabilities) {
        return getCapabilities().containsAll(capabilities);
    }

    /**
     * Establish connection with instrumentation hooks enabled.
     *
     * @param config connection parameters
     * @return instrumented connection wrapper
     * @throws ConnectionException if connection fails
     */
    InstrumentedConnection connect(ConnectionConfig config);

    /**
     * Execute single operation with full overhead decomposition.
     *
     * @param conn      active instrumented connection
     * @param operation operation to execute
     * @param collector metrics collection target
     * @return operation result with timing breakdown
     * @throws OperationException if execution fails
     */
    OperationResult execute(
            InstrumentedConnection conn,
            Operation operation,
            MetricsCollector collector
    );

    /**
     * Bulk operation support for throughput testing.
     *
     * @param conn       active instrumented connection
     * @param operations batch of operations
     * @param collector  metrics collection target
     * @return aggregated results
     * @throws OperationException if execution fails
     */
    default BulkOperationResult executeBulk(
            InstrumentedConnection conn,
            List<Operation> operations,
            MetricsCollector collector
    ) {
        // Default implementation: execute sequentially
        List<OperationResult> results = operations.stream()
                .map(op -> execute(conn, op, collector))
                .toList();
        return new BulkOperationResult(results);
    }

    /**
     * Extract platform-specific overhead breakdown from result.
     * This is where BSON vs OSON differences are captured.
     *
     * @param result the operation result
     * @return the overhead breakdown
     */
    OverheadBreakdown getOverheadBreakdown(OperationResult result);

    /**
     * Prepare test collection/table with required indexes.
     *
     * @param config test environment configuration
     * @throws SetupException if setup fails
     */
    void setupTestEnvironment(TestEnvironmentConfig config);

    /**
     * Clean up test data.
     *
     * @throws SetupException if teardown fails
     */
    void teardownTestEnvironment();

    /**
     * Validates the connection configuration.
     *
     * @param config the configuration to validate
     * @return validation result
     */
    default ValidationResult validateConfig(ConnectionConfig config) {
        return ValidationResult.success();
    }

    /**
     * Returns adapter-specific configuration options.
     *
     * @return map of option names to descriptions
     */
    default java.util.Map<String, String> getConfigurationOptions() {
        return java.util.Map.of();
    }

    /**
     * Closes this adapter and releases any resources.
     */
    @Override
    default void close() {
        // Default: no-op
    }
}
