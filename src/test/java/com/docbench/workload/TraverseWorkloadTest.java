package com.docbench.workload;

import com.docbench.adapter.spi.*;
import com.docbench.metrics.MetricsCollector;
import com.docbench.metrics.OverheadBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for TraverseWorkload - benchmarks path traversal operations.
 */
@DisplayName("TraverseWorkload")
class TraverseWorkloadTest {

    private TraverseWorkload workload;
    private DatabaseAdapter adapter;
    private MetricsCollector collector;
    private InstrumentedConnection connection;

    @BeforeEach
    void setUp() {
        workload = new TraverseWorkload();
        adapter = mock(DatabaseAdapter.class);
        collector = mock(MetricsCollector.class);
        connection = mock(InstrumentedConnection.class);

        // Default mock setup
        when(adapter.connect(any(ConnectionConfig.class))).thenReturn(connection);
        when(adapter.getCapabilities()).thenReturn(Set.of(Capability.PARTIAL_DOCUMENT_RETRIEVAL));
    }

    @Nested
    @DisplayName("Initialization")
    class InitializationTests {

        @Test
        @DisplayName("should have correct name")
        void shouldHaveCorrectName() {
            assertThat(workload.name()).isEqualTo("traverse");
        }

        @Test
        @DisplayName("should have description")
        void shouldHaveDescription() {
            assertThat(workload.description()).isNotEmpty();
        }

        @Test
        @DisplayName("should initialize with config")
        void shouldInitializeWithConfig() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("nestingDepth", 5)
                    .parameter("targetPath", "nested.nested.nested.target")
                    .seed(12345L)
                    .build();

            workload.initialize(config);

            assertThat(workload.config()).isEqualTo(config);
        }
    }

    @Nested
    @DisplayName("Data Setup")
    class DataSetupTests {

        @Test
        @DisplayName("should setup test environment")
        void shouldSetupTestEnvironment() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 10)
                    .parameter("nestingDepth", 3)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);

            // Verify test environment was setup
            verify(adapter).setupTestEnvironment(any(TestEnvironmentConfig.class));
        }

        @Test
        @DisplayName("should connect to database")
        void shouldConnectToDatabase() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 10)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);

            verify(adapter).connect(any(ConnectionConfig.class));
        }

        @Test
        @DisplayName("should insert test documents")
        void shouldInsertTestDocuments() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 10)
                    .parameter("nestingDepth", 3)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);

            // Verify 10 insert operations were executed
            verify(adapter, times(10)).execute(
                    eq(connection),
                    any(InsertOperation.class),
                    any(MetricsCollector.class)
            );
        }

        @Test
        @DisplayName("should generate documents with nested structure")
        void shouldGenerateDocumentsWithNestedStructure() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 1)
                    .parameter("nestingDepth", 4)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);

            ArgumentCaptor<InsertOperation> insertCaptor = ArgumentCaptor.forClass(InsertOperation.class);
            workload.setupData(adapter);

            verify(adapter).execute(eq(connection), insertCaptor.capture(), any(MetricsCollector.class));
            JsonDocument doc = insertCaptor.getValue().document();

            // Verify nested structure exists
            assertThat(doc.hasPath("nested")).isTrue();
            assertThat(doc.hasPath("nested.nested")).isTrue();
            assertThat(doc.hasPath("nested.nested.nested")).isTrue();
        }
    }

    @Nested
    @DisplayName("Iteration Execution")
    class IterationExecutionTests {

        @Test
        @DisplayName("should execute read operation with projection")
        void shouldExecuteReadWithProjection() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 5)
                    .parameter("nestingDepth", 3)
                    .parameter("targetPath", "nested.nested.target")
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();
            mockSuccessfulRead();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.runIteration(adapter, collector);

            // Verify read operation was executed
            ArgumentCaptor<ReadOperation> readCaptor = ArgumentCaptor.forClass(ReadOperation.class);
            verify(adapter, atLeastOnce()).execute(
                    eq(connection),
                    readCaptor.capture(),
                    eq(collector)
            );

            ReadOperation readOp = readCaptor.getValue();
            assertThat(readOp.hasProjection()).isTrue();
            assertThat(readOp.projectionPaths()).contains("nested.nested.target");
        }

        @Test
        @DisplayName("should record metrics for traversal")
        void shouldRecordMetricsForTraversal() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 5)
                    .parameter("nestingDepth", 3)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();
            mockSuccessfulRead();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.runIteration(adapter, collector);

            // Verify metrics recording
            verify(collector).recordTiming(eq("traverse"), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("Cleanup")
    class CleanupTests {

        @Test
        @DisplayName("should teardown test environment on cleanup")
        void shouldTeardownTestEnvironmentOnCleanup() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 5)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.cleanup(adapter);

            verify(adapter).teardownTestEnvironment();
        }

        @Test
        @DisplayName("should close connection on cleanup")
        void shouldCloseConnectionOnCleanup() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 5)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.cleanup(adapter);

            verify(connection).close();
        }
    }

    @Nested
    @DisplayName("Configuration Options")
    class ConfigurationOptionsTests {

        @Test
        @DisplayName("should support custom target path")
        void shouldSupportCustomTargetPath() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 1)
                    .parameter("targetPath", "deep.path.to.field")
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();
            mockSuccessfulRead();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.runIteration(adapter, collector);

            ArgumentCaptor<ReadOperation> readCaptor = ArgumentCaptor.forClass(ReadOperation.class);
            verify(adapter, atLeastOnce()).execute(
                    eq(connection),
                    readCaptor.capture(),
                    eq(collector)
            );

            assertThat(readCaptor.getValue().projectionPaths()).contains("deep.path.to.field");
        }

        @Test
        @DisplayName("should support configurable document count")
        void shouldSupportConfigurableDocumentCount() {
            WorkloadConfig config = WorkloadConfig.builder("traverse")
                    .parameter("documentCount", 50)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);

            verify(adapter, times(50)).execute(
                    eq(connection),
                    any(InsertOperation.class),
                    any(MetricsCollector.class)
            );
        }
    }

    // Helper methods for mocking
    private void mockSuccessfulInserts() {
        when(adapter.execute(eq(connection), any(InsertOperation.class), any(MetricsCollector.class)))
                .thenAnswer(invocation -> {
                    InsertOperation op = invocation.getArgument(1);
                    return OperationResult.success(
                            op.operationId(),
                            OperationType.INSERT,
                            Duration.ofMillis(1),
                            OverheadBreakdown.builder()
                                    .totalLatency(Duration.ofMillis(1))
                                    .build()
                    );
                });
    }

    private void mockSuccessfulRead() {
        when(adapter.execute(eq(connection), any(ReadOperation.class), any(MetricsCollector.class)))
                .thenAnswer(invocation -> {
                    ReadOperation op = invocation.getArgument(1);
                    return OperationResult.success(
                            op.operationId(),
                            OperationType.READ,
                            Duration.ofNanos(100_000), // 100 microseconds
                            OverheadBreakdown.builder()
                                    .totalLatency(Duration.ofNanos(100_000))
                                    .build()
                    );
                });
    }
}
