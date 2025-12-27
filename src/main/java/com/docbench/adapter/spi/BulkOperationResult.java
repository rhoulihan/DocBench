package com.docbench.adapter.spi;

import com.docbench.metrics.OverheadBreakdown;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Result of a bulk database operation containing multiple individual results.
 */
public record BulkOperationResult(
        List<OperationResult> results
) {

    public BulkOperationResult {
        Objects.requireNonNull(results, "results must not be null");
        results = List.copyOf(results);
    }

    /**
     * Returns the total number of operations.
     */
    public int totalOperations() {
        return results.size();
    }

    /**
     * Returns the number of successful operations.
     */
    public int successCount() {
        return (int) results.stream().filter(OperationResult::isSuccess).count();
    }

    /**
     * Returns the number of failed operations.
     */
    public int failureCount() {
        return (int) results.stream().filter(OperationResult::isFailure).count();
    }

    /**
     * Returns true if all operations succeeded.
     */
    public boolean allSuccessful() {
        return results.stream().allMatch(OperationResult::isSuccess);
    }

    /**
     * Returns true if any operation failed.
     */
    public boolean hasFailures() {
        return results.stream().anyMatch(OperationResult::isFailure);
    }

    /**
     * Returns the total duration of all operations.
     */
    public Duration totalDuration() {
        return results.stream()
                .map(OperationResult::totalDuration)
                .reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * Returns the average duration per operation.
     */
    public Duration averageDuration() {
        if (results.isEmpty()) {
            return Duration.ZERO;
        }
        return totalDuration().dividedBy(results.size());
    }

    /**
     * Returns successful results only.
     */
    public List<OperationResult> successfulResults() {
        return results.stream()
                .filter(OperationResult::isSuccess)
                .toList();
    }

    /**
     * Returns failed results only.
     */
    public List<OperationResult> failedResults() {
        return results.stream()
                .filter(OperationResult::isFailure)
                .toList();
    }

    /**
     * Returns all overhead breakdowns from successful operations.
     */
    public List<OverheadBreakdown> overheadBreakdowns() {
        return results.stream()
                .filter(OperationResult::isSuccess)
                .map(OperationResult::overheadBreakdown)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();
    }

    /**
     * Returns the throughput in operations per second.
     */
    public double throughputOpsPerSecond() {
        Duration total = totalDuration();
        if (total.isZero()) {
            return 0.0;
        }
        return (double) results.size() / total.toMillis() * 1000.0;
    }
}
