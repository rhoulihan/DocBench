package com.docbench.adapter.spi;

import com.docbench.metrics.OverheadBreakdown;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a single database operation with timing breakdown.
 * Contains both the operation outcome and detailed timing metrics.
 */
public final class OperationResult {

    private final String operationId;
    private final OperationType operationType;
    private final boolean success;
    private final Instant startTime;
    private final Instant endTime;
    private final Duration totalDuration;
    private final Object resultData;
    private final Exception error;
    private final OverheadBreakdown overheadBreakdown;
    private final Map<String, Object> metadata;

    private OperationResult(Builder builder) {
        this.operationId = Objects.requireNonNull(builder.operationId);
        this.operationType = Objects.requireNonNull(builder.operationType);
        this.success = builder.success;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.totalDuration = builder.totalDuration;
        this.resultData = builder.resultData;
        this.error = builder.error;
        this.overheadBreakdown = builder.overheadBreakdown;
        this.metadata = Map.copyOf(builder.metadata);
    }

    /**
     * Creates a successful operation result.
     */
    public static OperationResult success(
            String operationId,
            OperationType type,
            Duration duration,
            OverheadBreakdown breakdown
    ) {
        return builder(operationId, type)
                .success(true)
                .totalDuration(duration)
                .overheadBreakdown(breakdown)
                .build();
    }

    /**
     * Creates a successful operation result with data.
     */
    public static OperationResult success(
            String operationId,
            OperationType type,
            Duration duration,
            OverheadBreakdown breakdown,
            Object resultData
    ) {
        return builder(operationId, type)
                .success(true)
                .totalDuration(duration)
                .overheadBreakdown(breakdown)
                .resultData(resultData)
                .build();
    }

    /**
     * Creates a failed operation result.
     */
    public static OperationResult failure(
            String operationId,
            OperationType type,
            Duration duration,
            Exception error
    ) {
        return builder(operationId, type)
                .success(false)
                .totalDuration(duration)
                .error(error)
                .build();
    }

    /**
     * Returns a new builder.
     */
    public static Builder builder(String operationId, OperationType type) {
        return new Builder(operationId, type);
    }

    public String operationId() {
        return operationId;
    }

    public OperationType operationType() {
        return operationType;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public Optional<Instant> startTime() {
        return Optional.ofNullable(startTime);
    }

    public Optional<Instant> endTime() {
        return Optional.ofNullable(endTime);
    }

    public Duration totalDuration() {
        return totalDuration != null ? totalDuration : Duration.ZERO;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> resultData() {
        return Optional.ofNullable((T) resultData);
    }

    public Optional<Exception> error() {
        return Optional.ofNullable(error);
    }

    public Optional<OverheadBreakdown> overheadBreakdown() {
        return Optional.ofNullable(overheadBreakdown);
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(String key) {
        return Optional.ofNullable((T) metadata.get(key));
    }

    /**
     * Returns the overhead breakdown, throwing if not present.
     */
    public OverheadBreakdown requireOverheadBreakdown() {
        if (overheadBreakdown == null) {
            throw new IllegalStateException("Overhead breakdown not available for operation: " + operationId);
        }
        return overheadBreakdown;
    }

    @Override
    public String toString() {
        return "OperationResult{" +
                "operationId='" + operationId + '\'' +
                ", type=" + operationType +
                ", success=" + success +
                ", duration=" + totalDuration +
                '}';
    }

    /**
     * Builder for OperationResult.
     */
    public static final class Builder {
        private final String operationId;
        private final OperationType operationType;
        private boolean success = true;
        private Instant startTime;
        private Instant endTime;
        private Duration totalDuration;
        private Object resultData;
        private Exception error;
        private OverheadBreakdown overheadBreakdown;
        private final Map<String, Object> metadata = new java.util.HashMap<>();

        private Builder(String operationId, OperationType operationType) {
            this.operationId = operationId;
            this.operationType = operationType;
        }

        public Builder success(boolean success) {
            this.success = success;
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

        public Builder resultData(Object resultData) {
            this.resultData = resultData;
            return this;
        }

        public Builder error(Exception error) {
            this.error = error;
            return this;
        }

        public Builder overheadBreakdown(OverheadBreakdown overheadBreakdown) {
            this.overheadBreakdown = overheadBreakdown;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public OperationResult build() {
            return new OperationResult(this);
        }
    }
}
