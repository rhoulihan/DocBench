package com.docbench.workload;

import com.docbench.adapter.spi.DatabaseAdapter;
import com.docbench.adapter.spi.OperationResult;
import com.docbench.adapter.spi.ReadOperation;
import com.docbench.adapter.spi.JsonDocument;
import com.docbench.document.DocumentGenerator;
import com.docbench.metrics.MetricsCollector;

import java.util.List;

/**
 * Workload that benchmarks path traversal operations.
 * Measures the time to navigate to deeply nested fields in documents.
 * This is the key benchmark for comparing BSON O(n) vs OSON O(1) access.
 */
public class TraverseWorkload extends AbstractWorkload {

    private static final String DEFAULT_TARGET_PATH = "nested.nested.nested.target";

    private String targetPath;
    private DatabaseAdapter adapter;

    public TraverseWorkload() {
        super("traverse", "Benchmarks deep path traversal operations comparing O(n) BSON vs O(1) OSON field access");
    }

    @Override
    public void initialize(WorkloadConfig config) {
        super.initialize(config);
        this.targetPath = config.getStringParameter("targetPath", DEFAULT_TARGET_PATH);
    }

    @Override
    public void setupData(DatabaseAdapter adapter) {
        this.adapter = adapter;
        super.setupData(adapter);
    }

    @Override
    protected DocumentGenerator createDocumentGenerator() {
        int nestingDepth = config.getIntParameter("nestingDepth", 5);
        int fieldsPerLevel = config.getIntParameter("fieldsPerLevel", 10);

        // Calculate the target path based on nesting depth if not specified
        String path = config.getStringParameter("targetPath", null);
        if (path == null) {
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < nestingDepth - 1; i++) {
                if (i > 0) pathBuilder.append(".");
                pathBuilder.append("nested");
            }
            if (pathBuilder.length() > 0) {
                pathBuilder.append(".");
            }
            pathBuilder.append("target");
            path = pathBuilder.toString();
        }
        this.targetPath = path;

        return DocumentGenerator.builder()
                .randomSource(randomSource)
                .fieldCount(config.getIntParameter("fieldCount", 20))
                .nestingDepth(nestingDepth)
                .fieldsPerLevel(fieldsPerLevel)
                .targetPath(targetPath)
                .targetValue("TARGET_VALUE")
                .build();
    }

    @Override
    public void runIteration(DatabaseAdapter adapter, MetricsCollector collector) {
        JsonDocument doc = randomDocument();

        // Create read operation with projection to target path
        ReadOperation read = ReadOperation.withProjection(
                nextOperationId(),
                doc.getId(),
                List.of(targetPath)
        );

        // Execute the read operation - this is where BSON O(n) vs OSON O(1) matters
        OperationResult result = adapter.execute(connection, read, collector);

        // Record the traversal latency
        collector.recordTiming("traverse", result.totalDuration());

        if (!result.isSuccess()) {
            collector.recordTiming("traverse_error", result.totalDuration());
        }
    }
}
