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
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TDD tests for DeserializeWorkload - benchmarks full document deserialization.
 */
@DisplayName("DeserializeWorkload")
class DeserializeWorkloadTest {

    private DeserializeWorkload workload;
    private DatabaseAdapter adapter;
    private MetricsCollector collector;
    private InstrumentedConnection connection;

    @BeforeEach
    void setUp() {
        workload = new DeserializeWorkload();
        adapter = mock(DatabaseAdapter.class);
        collector = mock(MetricsCollector.class);
        connection = mock(InstrumentedConnection.class);

        when(adapter.connect(any(ConnectionConfig.class))).thenReturn(connection);
        when(adapter.getCapabilities()).thenReturn(Set.of(Capability.NESTED_DOCUMENT_ACCESS));
    }

    @Nested
    @DisplayName("Initialization")
    class InitializationTests {

        @Test
        @DisplayName("should have correct name")
        void shouldHaveCorrectName() {
            assertThat(workload.name()).isEqualTo("deserialize");
        }

        @Test
        @DisplayName("should have description")
        void shouldHaveDescription() {
            assertThat(workload.description()).isNotEmpty();
        }

        @Test
        @DisplayName("should initialize with config")
        void shouldInitializeWithConfig() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 100)
                    .parameter("documentSizeBytes", 5000)
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
        @DisplayName("should insert documents of configured size")
        void shouldInsertDocumentsOfConfiguredSize() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 5)
                    .parameter("documentSizeBytes", 2000)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);
            workload.setupData(adapter);

            // Verify 5 documents were inserted
            verify(adapter, times(5)).execute(
                    eq(connection),
                    any(InsertOperation.class),
                    any(MetricsCollector.class)
            );
        }
    }

    @Nested
    @DisplayName("Iteration Execution")
    class IterationExecutionTests {

        @Test
        @DisplayName("should execute full document read")
        void shouldExecuteFullDocumentRead() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 5)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();
            mockSuccessfulRead();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.runIteration(adapter, collector);

            // Verify read operation was executed without projection
            ArgumentCaptor<ReadOperation> readCaptor = ArgumentCaptor.forClass(ReadOperation.class);
            verify(adapter, atLeastOnce()).execute(
                    eq(connection),
                    readCaptor.capture(),
                    eq(collector)
            );

            ReadOperation readOp = readCaptor.getValue();
            assertThat(readOp.hasProjection()).isFalse();
        }

        @Test
        @DisplayName("should record metrics for deserialization")
        void shouldRecordMetricsForDeserialization() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 5)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();
            mockSuccessfulRead();

            workload.initialize(config);
            workload.setupData(adapter);
            workload.runIteration(adapter, collector);

            // Verify metrics recording
            verify(collector).recordTiming(eq("deserialize"), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("Document Size Variations")
    class DocumentSizeTests {

        @Test
        @DisplayName("should support small documents")
        void shouldSupportSmallDocuments() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 3)
                    .parameter("documentSizeBytes", 500)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);

            ArgumentCaptor<InsertOperation> insertCaptor = ArgumentCaptor.forClass(InsertOperation.class);
            workload.setupData(adapter);

            verify(adapter, times(3)).execute(eq(connection), insertCaptor.capture(), any(MetricsCollector.class));

            // Verify generated documents are approximately the target size
            JsonDocument doc = insertCaptor.getAllValues().get(0).document();
            assertThat(doc.estimatedSizeBytes()).isLessThan(1500); // Allow tolerance
        }

        @Test
        @DisplayName("should support large documents")
        void shouldSupportLargeDocuments() {
            WorkloadConfig config = WorkloadConfig.builder("deserialize")
                    .parameter("documentCount", 2)
                    .parameter("documentSizeBytes", 20000)
                    .seed(12345L)
                    .build();

            mockSuccessfulInserts();

            workload.initialize(config);

            ArgumentCaptor<InsertOperation> insertCaptor = ArgumentCaptor.forClass(InsertOperation.class);
            workload.setupData(adapter);

            verify(adapter, times(2)).execute(eq(connection), insertCaptor.capture(), any(MetricsCollector.class));

            // Verify generated documents are reasonably large
            JsonDocument doc = insertCaptor.getAllValues().get(0).document();
            assertThat(doc.estimatedSizeBytes()).isGreaterThan(10000);
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
                            Duration.ofNanos(500_000), // 500 microseconds
                            OverheadBreakdown.builder()
                                    .totalLatency(Duration.ofNanos(500_000))
                                    .build()
                    );
                });
    }
}
