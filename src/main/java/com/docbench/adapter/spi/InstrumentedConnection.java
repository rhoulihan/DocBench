package com.docbench.adapter.spi;

import com.docbench.metrics.MetricsCollector;

/**
 * Connection wrapper providing timing hooks at protocol boundaries.
 * Implementations capture detailed timing for overhead decomposition.
 */
public interface InstrumentedConnection extends AutoCloseable {

    /**
     * Returns the underlying platform connection.
     *
     * @param connectionType the expected connection type
     * @param <T>            the connection type
     * @return the unwrapped connection
     * @throws ClassCastException if the connection is not of the expected type
     */
    <T> T unwrap(Class<T> connectionType);

    /**
     * Returns true if this connection is valid and usable.
     *
     * @return true if valid
     */
    boolean isValid();

    /**
     * Registers a listener for operation timing events.
     *
     * @param listener the timing listener
     */
    void addTimingListener(TimingListener listener);

    /**
     * Removes a timing listener.
     *
     * @param listener the listener to remove
     */
    void removeTimingListener(TimingListener listener);

    /**
     * Returns accumulated timing metrics since last reset.
     *
     * @return the timing metrics
     */
    ConnectionTimingMetrics getTimingMetrics();

    /**
     * Resets timing accumulators.
     */
    void resetTimingMetrics();

    /**
     * Returns the associated metrics collector.
     *
     * @return the metrics collector
     */
    MetricsCollector getMetricsCollector();

    /**
     * Returns the connection ID for correlation.
     *
     * @return the connection ID
     */
    String getConnectionId();

    /**
     * Closes this connection and releases resources.
     */
    @Override
    void close();
}

/**
 * Callback interface for fine-grained timing capture.
 */
interface TimingListener {

    /**
     * Called when serialization starts.
     */
    void onSerializationStart(String operationId);

    /**
     * Called when serialization completes.
     */
    void onSerializationComplete(String operationId, int bytesSerialized);

    /**
     * Called when wire transmission starts.
     */
    void onWireTransmitStart(String operationId);

    /**
     * Called when wire transmission completes.
     */
    void onWireTransmitComplete(String operationId, int bytesSent);

    /**
     * Called when wire reception starts.
     */
    void onWireReceiveStart(String operationId);

    /**
     * Called when wire reception completes.
     */
    void onWireReceiveComplete(String operationId, int bytesReceived);

    /**
     * Called when deserialization starts.
     */
    void onDeserializationStart(String operationId);

    /**
     * Called when deserialization completes.
     */
    void onDeserializationComplete(String operationId, int fieldsDeserialized);
}

/**
 * Accumulated connection timing metrics.
 */
record ConnectionTimingMetrics(
        long serializationTimeNanos,
        long wireTransmitTimeNanos,
        long wireReceiveTimeNanos,
        long deserializationTimeNanos,
        long totalBytesSent,
        long totalBytesReceived,
        long operationCount
) {
    public static ConnectionTimingMetrics empty() {
        return new ConnectionTimingMetrics(0, 0, 0, 0, 0, 0, 0);
    }

    public ConnectionTimingMetrics add(ConnectionTimingMetrics other) {
        return new ConnectionTimingMetrics(
                serializationTimeNanos + other.serializationTimeNanos,
                wireTransmitTimeNanos + other.wireTransmitTimeNanos,
                wireReceiveTimeNanos + other.wireReceiveTimeNanos,
                deserializationTimeNanos + other.deserializationTimeNanos,
                totalBytesSent + other.totalBytesSent,
                totalBytesReceived + other.totalBytesReceived,
                operationCount + other.operationCount
        );
    }
}
