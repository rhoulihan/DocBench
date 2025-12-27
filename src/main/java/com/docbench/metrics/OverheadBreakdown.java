package com.docbench.metrics;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable record capturing decomposed operation timing.
 * All durations are non-negative; unmeasured components default to Duration.ZERO.
 *
 * <p>This is the central data structure for DocBench overhead decomposition,
 * enabling comparison of binary JSON traversal strategies (BSON O(n) vs OSON O(1)).
 */
public record OverheadBreakdown(
        // Total wall-clock time for operation
        Duration totalLatency,

        // Connection lifecycle
        Duration connectionAcquisition,    // Pool checkout or new connection
        Duration connectionRelease,        // Pool return

        // Client-side serialization (REQUEST)
        Duration serializationTime,        // Encode to wire format (BSON/OSON)

        // Network transit (REQUEST)
        Duration wireTransmitTime,         // Send bytes over network

        // Server-side execution
        Duration serverExecutionTime,      // DB-reported total execution
        Duration serverParseTime,          // Query/command parsing
        Duration serverTraversalTime,      // Document field navigation (KEY METRIC)
        Duration serverIndexTime,          // Index lookup time
        Duration serverFetchTime,          // Data retrieval from storage

        // Network transit (RESPONSE)
        Duration wireReceiveTime,          // Receive bytes over network

        // Client-side deserialization (RESPONSE - KEY METRIC)
        Duration deserializationTime,      // Decode from wire format
        Duration clientTraversalTime,      // Client-side field access in parsed doc

        // Platform-specific additional metrics
        Map<String, Duration> platformSpecific
) {

    /**
     * Compact constructor with validation.
     */
    public OverheadBreakdown {
        Objects.requireNonNull(totalLatency, "totalLatency must not be null");
        Objects.requireNonNull(connectionAcquisition, "connectionAcquisition must not be null");
        Objects.requireNonNull(connectionRelease, "connectionRelease must not be null");
        Objects.requireNonNull(serializationTime, "serializationTime must not be null");
        Objects.requireNonNull(wireTransmitTime, "wireTransmitTime must not be null");
        Objects.requireNonNull(serverExecutionTime, "serverExecutionTime must not be null");
        Objects.requireNonNull(serverParseTime, "serverParseTime must not be null");
        Objects.requireNonNull(serverTraversalTime, "serverTraversalTime must not be null");
        Objects.requireNonNull(serverIndexTime, "serverIndexTime must not be null");
        Objects.requireNonNull(serverFetchTime, "serverFetchTime must not be null");
        Objects.requireNonNull(wireReceiveTime, "wireReceiveTime must not be null");
        Objects.requireNonNull(deserializationTime, "deserializationTime must not be null");
        Objects.requireNonNull(clientTraversalTime, "clientTraversalTime must not be null");
        Objects.requireNonNull(platformSpecific, "platformSpecific must not be null");

        validateNonNegative(totalLatency, "totalLatency");
        validateNonNegative(connectionAcquisition, "connectionAcquisition");
        validateNonNegative(connectionRelease, "connectionRelease");
        validateNonNegative(serializationTime, "serializationTime");
        validateNonNegative(wireTransmitTime, "wireTransmitTime");
        validateNonNegative(serverExecutionTime, "serverExecutionTime");
        validateNonNegative(serverParseTime, "serverParseTime");
        validateNonNegative(serverTraversalTime, "serverTraversalTime");
        validateNonNegative(serverIndexTime, "serverIndexTime");
        validateNonNegative(serverFetchTime, "serverFetchTime");
        validateNonNegative(wireReceiveTime, "wireReceiveTime");
        validateNonNegative(deserializationTime, "deserializationTime");
        validateNonNegative(clientTraversalTime, "clientTraversalTime");

        // Defensive copy for immutability
        platformSpecific = Map.copyOf(platformSpecific);
    }

    private static void validateNonNegative(Duration duration, String name) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative: " + duration);
        }
    }

    /**
     * Total overhead = everything except actual data fetch.
     */
    public Duration totalOverhead() {
        return totalLatency.minus(serverFetchTime);
    }

    /**
     * Traversal overhead = server + client navigation time.
     * This is the PRIMARY comparison metric for BSON vs OSON.
     */
    public Duration traversalOverhead() {
        return serverTraversalTime.plus(clientTraversalTime);
    }

    /**
     * Network overhead = transit + protocol framing.
     */
    public Duration networkOverhead() {
        return wireTransmitTime.plus(wireReceiveTime);
    }

    /**
     * Serialization overhead = encode + decode.
     */
    public Duration serializationOverhead() {
        return serializationTime.plus(deserializationTime);
    }

    /**
     * Connection overhead = acquire + release.
     */
    public Duration connectionOverhead() {
        return connectionAcquisition.plus(connectionRelease);
    }

    /**
     * Percentage of total latency spent in traversal.
     */
    public double traversalPercentage() {
        if (totalLatency.isZero()) {
            return 0.0;
        }
        return (double) traversalOverhead().toNanos() / totalLatency.toNanos() * 100;
    }

    /**
     * Percentage of total latency spent in overhead (non-fetch).
     */
    public double overheadPercentage() {
        if (totalLatency.isZero()) {
            return 0.0;
        }
        return (double) totalOverhead().toNanos() / totalLatency.toNanos() * 100;
    }

    /**
     * Percentage of total latency spent in network transit.
     */
    public double networkPercentage() {
        if (totalLatency.isZero()) {
            return 0.0;
        }
        return (double) networkOverhead().toNanos() / totalLatency.toNanos() * 100;
    }

    /**
     * Percentage of total latency spent in serialization/deserialization.
     */
    public double serializationPercentage() {
        if (totalLatency.isZero()) {
            return 0.0;
        }
        return (double) serializationOverhead().toNanos() / totalLatency.toNanos() * 100;
    }

    /**
     * Creates a new builder for constructing OverheadBreakdown instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for OverheadBreakdown with sensible defaults.
     */
    public static final class Builder {
        private Duration totalLatency = Duration.ZERO;
        private Duration connectionAcquisition = Duration.ZERO;
        private Duration connectionRelease = Duration.ZERO;
        private Duration serializationTime = Duration.ZERO;
        private Duration wireTransmitTime = Duration.ZERO;
        private Duration serverExecutionTime = Duration.ZERO;
        private Duration serverParseTime = Duration.ZERO;
        private Duration serverTraversalTime = Duration.ZERO;
        private Duration serverIndexTime = Duration.ZERO;
        private Duration serverFetchTime = Duration.ZERO;
        private Duration wireReceiveTime = Duration.ZERO;
        private Duration deserializationTime = Duration.ZERO;
        private Duration clientTraversalTime = Duration.ZERO;
        private final Map<String, Duration> platformSpecific = new HashMap<>();

        private Builder() {
        }

        public Builder totalLatency(Duration totalLatency) {
            this.totalLatency = Objects.requireNonNull(totalLatency);
            return this;
        }

        public Builder connectionAcquisition(Duration connectionAcquisition) {
            this.connectionAcquisition = Objects.requireNonNull(connectionAcquisition);
            return this;
        }

        public Builder connectionRelease(Duration connectionRelease) {
            this.connectionRelease = Objects.requireNonNull(connectionRelease);
            return this;
        }

        public Builder serializationTime(Duration serializationTime) {
            this.serializationTime = Objects.requireNonNull(serializationTime);
            return this;
        }

        public Builder wireTransmitTime(Duration wireTransmitTime) {
            this.wireTransmitTime = Objects.requireNonNull(wireTransmitTime);
            return this;
        }

        public Builder serverExecutionTime(Duration serverExecutionTime) {
            this.serverExecutionTime = Objects.requireNonNull(serverExecutionTime);
            return this;
        }

        public Builder serverParseTime(Duration serverParseTime) {
            this.serverParseTime = Objects.requireNonNull(serverParseTime);
            return this;
        }

        public Builder serverTraversalTime(Duration serverTraversalTime) {
            this.serverTraversalTime = Objects.requireNonNull(serverTraversalTime);
            return this;
        }

        public Builder serverIndexTime(Duration serverIndexTime) {
            this.serverIndexTime = Objects.requireNonNull(serverIndexTime);
            return this;
        }

        public Builder serverFetchTime(Duration serverFetchTime) {
            this.serverFetchTime = Objects.requireNonNull(serverFetchTime);
            return this;
        }

        public Builder wireReceiveTime(Duration wireReceiveTime) {
            this.wireReceiveTime = Objects.requireNonNull(wireReceiveTime);
            return this;
        }

        public Builder deserializationTime(Duration deserializationTime) {
            this.deserializationTime = Objects.requireNonNull(deserializationTime);
            return this;
        }

        public Builder clientTraversalTime(Duration clientTraversalTime) {
            this.clientTraversalTime = Objects.requireNonNull(clientTraversalTime);
            return this;
        }

        public Builder addPlatformSpecific(String key, Duration duration) {
            this.platformSpecific.put(
                    Objects.requireNonNull(key),
                    Objects.requireNonNull(duration)
            );
            return this;
        }

        public Builder platformSpecific(Map<String, Duration> platformSpecific) {
            this.platformSpecific.clear();
            this.platformSpecific.putAll(Objects.requireNonNull(platformSpecific));
            return this;
        }

        public OverheadBreakdown build() {
            return new OverheadBreakdown(
                    totalLatency,
                    connectionAcquisition,
                    connectionRelease,
                    serializationTime,
                    wireTransmitTime,
                    serverExecutionTime,
                    serverParseTime,
                    serverTraversalTime,
                    serverIndexTime,
                    serverFetchTime,
                    wireReceiveTime,
                    deserializationTime,
                    clientTraversalTime,
                    platformSpecific
            );
        }
    }
}
