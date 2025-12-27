package com.docbench.workload;

import com.docbench.adapter.spi.*;
import com.docbench.document.DocumentGenerator;
import com.docbench.metrics.MetricsCollector;
import com.docbench.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstract base class for workloads providing common functionality.
 */
public abstract class AbstractWorkload implements Workload {

    protected final String name;
    protected final String description;
    protected WorkloadConfig config;
    protected DocumentGenerator documentGenerator;
    protected List<JsonDocument> testDocuments;
    protected RandomSource randomSource;
    protected String collectionName;
    protected InstrumentedConnection connection;

    protected AbstractWorkload(String name, String description) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public void initialize(WorkloadConfig config) {
        this.config = Objects.requireNonNull(config);
        this.randomSource = config.seed() != null ?
                RandomSource.seeded(config.seed()) :
                RandomSource.random();
        this.collectionName = "bench_" + name + "_" + System.currentTimeMillis();
        this.documentGenerator = createDocumentGenerator();
        this.testDocuments = new ArrayList<>();
    }

    @Override
    public void setupData(DatabaseAdapter adapter) {
        // Setup test environment with our collection
        TestEnvironmentConfig testEnv = TestEnvironmentConfig.builder()
                .collectionName(collectionName)
                .dropExisting(true)
                .build();
        adapter.setupTestEnvironment(testEnv);

        // Connect to the database
        ConnectionConfig connConfig = ConnectionConfig.builder()
                .host(config.getStringParameter("host", "localhost"))
                .port(config.getIntParameter("port", 27017))
                .database(config.getStringParameter("database", "benchmark"))
                .build();
        this.connection = adapter.connect(connConfig);

        // Generate and insert test documents
        int docCount = config.getIntParameter("documentCount", 1000);
        testDocuments = documentGenerator.generateBatch("doc", docCount);

        MetricsCollector setupCollector = new MetricsCollector();
        for (JsonDocument doc : testDocuments) {
            InsertOperation insert = new InsertOperation(
                    "setup-" + UUID.randomUUID(),
                    doc
            );
            adapter.execute(connection, insert, setupCollector);
        }
    }

    @Override
    public void cleanup(DatabaseAdapter adapter) {
        adapter.teardownTestEnvironment();
        if (connection != null) {
            connection.close();
            connection = null;
        }
        testDocuments = null;
    }

    @Override
    public WorkloadConfig config() {
        return config;
    }

    /**
     * Creates the document generator configured for this workload.
     * Subclasses should override to customize document structure.
     */
    protected abstract DocumentGenerator createDocumentGenerator();

    /**
     * Returns a random document from the test set.
     */
    protected JsonDocument randomDocument() {
        if (testDocuments == null || testDocuments.isEmpty()) {
            throw new IllegalStateException("Test documents not initialized");
        }
        return testDocuments.get(randomSource.nextInt(testDocuments.size()));
    }

    /**
     * Generates a unique operation ID.
     */
    protected String nextOperationId() {
        return name + "-" + UUID.randomUUID();
    }
}
