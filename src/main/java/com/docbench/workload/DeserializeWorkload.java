package com.docbench.workload;

import com.docbench.adapter.spi.DatabaseAdapter;
import com.docbench.adapter.spi.OperationResult;
import com.docbench.adapter.spi.ReadOperation;
import com.docbench.adapter.spi.JsonDocument;
import com.docbench.document.DocumentGenerator;
import com.docbench.metrics.MetricsCollector;

/**
 * Workload that benchmarks full document deserialization.
 * Measures the time to retrieve and deserialize complete documents of varying sizes.
 * This tests the overall document handling performance including network transfer
 * and client-side deserialization.
 */
public class DeserializeWorkload extends AbstractWorkload {

    private DatabaseAdapter adapter;

    public DeserializeWorkload() {
        super("deserialize", "Benchmarks full document deserialization comparing BSON vs OSON serialization overhead");
    }

    @Override
    public void setupData(DatabaseAdapter adapter) {
        this.adapter = adapter;
        super.setupData(adapter);
    }

    @Override
    protected DocumentGenerator createDocumentGenerator() {
        int targetSize = config.getIntParameter("documentSizeBytes", 5000);
        int tolerance = config.getIntParameter("sizeTolerance", 20);

        return DocumentGenerator.builder()
                .randomSource(randomSource)
                .targetSizeBytes(targetSize)
                .sizeTolerancePercent(tolerance)
                .numericFieldProbability(0.3)
                .booleanFieldProbability(0.1)
                .nestingDepth(config.getIntParameter("nestingDepth", 3))
                .fieldsPerLevel(config.getIntParameter("fieldsPerLevel", 5))
                .arrayFieldCount(config.getIntParameter("arrayFieldCount", 2))
                .arraySize(5, 15)
                .build();
    }

    @Override
    public void runIteration(DatabaseAdapter adapter, MetricsCollector collector) {
        JsonDocument doc = randomDocument();

        // Create full document read operation (no projection)
        ReadOperation read = ReadOperation.fullDocument(
                nextOperationId(),
                doc.getId()
        );

        // Execute the read operation - measures full deserialization
        OperationResult result = adapter.execute(connection, read, collector);

        // Record the deserialization latency
        collector.recordTiming("deserialize", result.totalDuration());

        if (!result.isSuccess()) {
            collector.recordTiming("deserialize_error", result.totalDuration());
        }

        // Record overhead breakdown if available
        result.overheadBreakdown().ifPresent(breakdown -> {
            collector.recordTiming("deserialize_serialization", breakdown.deserializationTime());
        });
    }
}
