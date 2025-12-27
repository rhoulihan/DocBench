package com.docbench.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD Tests for OverheadBreakdown record.
 * This is the central data structure for overhead decomposition.
 */
@DisplayName("OverheadBreakdown")
class OverheadBreakdownTest {

    private static final Duration TOTAL = Duration.ofMicros(1000);
    private static final Duration CONN_ACQ = Duration.ofMicros(50);
    private static final Duration CONN_REL = Duration.ofMicros(20);
    private static final Duration SERIALIZATION = Duration.ofMicros(100);
    private static final Duration WIRE_TRANSMIT = Duration.ofMicros(75);
    private static final Duration SERVER_EXEC = Duration.ofMicros(400);
    private static final Duration SERVER_PARSE = Duration.ofMicros(50);
    private static final Duration SERVER_TRAVERSAL = Duration.ofMicros(200);
    private static final Duration SERVER_INDEX = Duration.ofMicros(30);
    private static final Duration SERVER_FETCH = Duration.ofMicros(120);
    private static final Duration WIRE_RECEIVE = Duration.ofMicros(75);
    private static final Duration DESERIALIZATION = Duration.ofMicros(80);
    private static final Duration CLIENT_TRAVERSAL = Duration.ofMicros(25);

    private OverheadBreakdown createStandardBreakdown() {
        return new OverheadBreakdown(
                TOTAL,
                CONN_ACQ,
                CONN_REL,
                SERIALIZATION,
                WIRE_TRANSMIT,
                SERVER_EXEC,
                SERVER_PARSE,
                SERVER_TRAVERSAL,
                SERVER_INDEX,
                SERVER_FETCH,
                WIRE_RECEIVE,
                DESERIALIZATION,
                CLIENT_TRAVERSAL,
                Map.of()
        );
    }

    @Nested
    @DisplayName("Record Properties")
    class RecordPropertiesTests {

        @Test
        @DisplayName("should store all timing components")
        void constructor_shouldStoreAllComponents() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            assertThat(breakdown.totalLatency()).isEqualTo(TOTAL);
            assertThat(breakdown.connectionAcquisition()).isEqualTo(CONN_ACQ);
            assertThat(breakdown.connectionRelease()).isEqualTo(CONN_REL);
            assertThat(breakdown.serializationTime()).isEqualTo(SERIALIZATION);
            assertThat(breakdown.wireTransmitTime()).isEqualTo(WIRE_TRANSMIT);
            assertThat(breakdown.serverExecutionTime()).isEqualTo(SERVER_EXEC);
            assertThat(breakdown.serverParseTime()).isEqualTo(SERVER_PARSE);
            assertThat(breakdown.serverTraversalTime()).isEqualTo(SERVER_TRAVERSAL);
            assertThat(breakdown.serverIndexTime()).isEqualTo(SERVER_INDEX);
            assertThat(breakdown.serverFetchTime()).isEqualTo(SERVER_FETCH);
            assertThat(breakdown.wireReceiveTime()).isEqualTo(WIRE_RECEIVE);
            assertThat(breakdown.deserializationTime()).isEqualTo(DESERIALIZATION);
            assertThat(breakdown.clientTraversalTime()).isEqualTo(CLIENT_TRAVERSAL);
        }

        @Test
        @DisplayName("should be immutable")
        void record_shouldBeImmutable() {
            Map<String, Duration> mutableMap = new java.util.HashMap<>();
            mutableMap.put("custom", Duration.ofMicros(10));

            OverheadBreakdown breakdown = new OverheadBreakdown(
                    TOTAL, CONN_ACQ, CONN_REL, SERIALIZATION, WIRE_TRANSMIT,
                    SERVER_EXEC, SERVER_PARSE, SERVER_TRAVERSAL, SERVER_INDEX,
                    SERVER_FETCH, WIRE_RECEIVE, DESERIALIZATION, CLIENT_TRAVERSAL,
                    mutableMap
            );

            // Original map modification should not affect the record
            mutableMap.put("another", Duration.ofMicros(20));

            assertThat(breakdown.platformSpecific()).doesNotContainKey("another");
        }
    }

    @Nested
    @DisplayName("Derived Metrics")
    class DerivedMetricsTests {

        @Test
        @DisplayName("totalOverhead should exclude serverFetchTime")
        void totalOverhead_shouldExcludeFetchTime() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            Duration expected = TOTAL.minus(SERVER_FETCH);

            assertThat(breakdown.totalOverhead()).isEqualTo(expected);
        }

        @Test
        @DisplayName("traversalOverhead should sum server and client traversal")
        void traversalOverhead_shouldSumTraversalTimes() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            Duration expected = SERVER_TRAVERSAL.plus(CLIENT_TRAVERSAL);

            assertThat(breakdown.traversalOverhead())
                    .isEqualTo(expected)
                    .isEqualTo(Duration.ofMicros(225)); // 200 + 25
        }

        @Test
        @DisplayName("networkOverhead should sum wire transmit and receive")
        void networkOverhead_shouldSumWireTimes() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            Duration expected = WIRE_TRANSMIT.plus(WIRE_RECEIVE);

            assertThat(breakdown.networkOverhead())
                    .isEqualTo(expected)
                    .isEqualTo(Duration.ofMicros(150)); // 75 + 75
        }

        @Test
        @DisplayName("serializationOverhead should sum serialization and deserialization")
        void serializationOverhead_shouldSumSerDesTimes() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            Duration expected = SERIALIZATION.plus(DESERIALIZATION);

            assertThat(breakdown.serializationOverhead())
                    .isEqualTo(expected)
                    .isEqualTo(Duration.ofMicros(180)); // 100 + 80
        }

        @Test
        @DisplayName("connectionOverhead should sum acquisition and release")
        void connectionOverhead_shouldSumConnTimes() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            Duration expected = CONN_ACQ.plus(CONN_REL);

            assertThat(breakdown.connectionOverhead())
                    .isEqualTo(expected)
                    .isEqualTo(Duration.ofMicros(70)); // 50 + 20
        }
    }

    @Nested
    @DisplayName("Percentage Calculations")
    class PercentageCalculationsTests {

        @Test
        @DisplayName("traversalPercentage should calculate correct percentage")
        void traversalPercentage_shouldCalculateCorrectly() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            // Traversal = 225us, Total = 1000us -> 22.5%
            double expected = 22.5;

            assertThat(breakdown.traversalPercentage()).isCloseTo(expected, within(0.01));
        }

        @Test
        @DisplayName("traversalPercentage should return 0 for zero total")
        void traversalPercentage_withZeroTotal_shouldReturnZero() {
            OverheadBreakdown breakdown = new OverheadBreakdown(
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Map.of()
            );

            assertThat(breakdown.traversalPercentage()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("overheadPercentage should calculate correctly")
        void overheadPercentage_shouldCalculateCorrectly() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            // Overhead = 1000 - 120 = 880us -> 88.0%
            double expected = 88.0;

            assertThat(breakdown.overheadPercentage()).isCloseTo(expected, within(0.01));
        }

        @Test
        @DisplayName("networkPercentage should calculate correctly")
        void networkPercentage_shouldCalculateCorrectly() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            // Network = 150us, Total = 1000us -> 15.0%
            double expected = 15.0;

            assertThat(breakdown.networkPercentage()).isCloseTo(expected, within(0.01));
        }

        @Test
        @DisplayName("serializationPercentage should calculate correctly")
        void serializationPercentage_shouldCalculateCorrectly() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            // Serialization = 180us, Total = 1000us -> 18.0%
            double expected = 18.0;

            assertThat(breakdown.serializationPercentage()).isCloseTo(expected, within(0.01));
        }
    }

    @Nested
    @DisplayName("Platform Specific")
    class PlatformSpecificTests {

        @Test
        @DisplayName("should store platform-specific metrics")
        void platformSpecific_shouldStoreMetrics() {
            Map<String, Duration> specific = Map.of(
                    "mongodb.cursor_time", Duration.ofMicros(50),
                    "mongodb.getmore_time", Duration.ofMicros(30)
            );

            OverheadBreakdown breakdown = new OverheadBreakdown(
                    TOTAL, CONN_ACQ, CONN_REL, SERIALIZATION, WIRE_TRANSMIT,
                    SERVER_EXEC, SERVER_PARSE, SERVER_TRAVERSAL, SERVER_INDEX,
                    SERVER_FETCH, WIRE_RECEIVE, DESERIALIZATION, CLIENT_TRAVERSAL,
                    specific
            );

            assertThat(breakdown.platformSpecific())
                    .hasSize(2)
                    .containsEntry("mongodb.cursor_time", Duration.ofMicros(50))
                    .containsEntry("mongodb.getmore_time", Duration.ofMicros(30));
        }

        @Test
        @DisplayName("should handle empty platform-specific metrics")
        void platformSpecific_whenEmpty_shouldReturnEmptyMap() {
            OverheadBreakdown breakdown = createStandardBreakdown();

            assertThat(breakdown.platformSpecific()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all components")
        void builder_shouldBuildWithAllComponents() {
            OverheadBreakdown breakdown = OverheadBreakdown.builder()
                    .totalLatency(TOTAL)
                    .connectionAcquisition(CONN_ACQ)
                    .connectionRelease(CONN_REL)
                    .serializationTime(SERIALIZATION)
                    .wireTransmitTime(WIRE_TRANSMIT)
                    .serverExecutionTime(SERVER_EXEC)
                    .serverParseTime(SERVER_PARSE)
                    .serverTraversalTime(SERVER_TRAVERSAL)
                    .serverIndexTime(SERVER_INDEX)
                    .serverFetchTime(SERVER_FETCH)
                    .wireReceiveTime(WIRE_RECEIVE)
                    .deserializationTime(DESERIALIZATION)
                    .clientTraversalTime(CLIENT_TRAVERSAL)
                    .build();

            assertThat(breakdown.totalLatency()).isEqualTo(TOTAL);
            assertThat(breakdown.serverTraversalTime()).isEqualTo(SERVER_TRAVERSAL);
        }

        @Test
        @DisplayName("should default missing durations to ZERO")
        void builder_withMissingValues_shouldDefaultToZero() {
            OverheadBreakdown breakdown = OverheadBreakdown.builder()
                    .totalLatency(TOTAL)
                    .serverExecutionTime(SERVER_EXEC)
                    .build();

            assertThat(breakdown.connectionAcquisition()).isEqualTo(Duration.ZERO);
            assertThat(breakdown.serializationTime()).isEqualTo(Duration.ZERO);
            assertThat(breakdown.serverTraversalTime()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("should add platform-specific metrics")
        void builder_shouldAddPlatformSpecific() {
            OverheadBreakdown breakdown = OverheadBreakdown.builder()
                    .totalLatency(TOTAL)
                    .addPlatformSpecific("custom.metric", Duration.ofMicros(100))
                    .addPlatformSpecific("another.metric", Duration.ofMicros(50))
                    .build();

            assertThat(breakdown.platformSpecific())
                    .containsEntry("custom.metric", Duration.ofMicros(100))
                    .containsEntry("another.metric", Duration.ofMicros(50));
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should reject negative durations")
        void constructor_withNegativeDuration_shouldThrow() {
            assertThatThrownBy(() -> new OverheadBreakdown(
                    Duration.ofMicros(-1),
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Map.of()
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("should reject null durations")
        void constructor_withNullDuration_shouldThrow() {
            assertThatThrownBy(() -> new OverheadBreakdown(
                    null,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Map.of()
            )).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should accept all zero durations")
        void constructor_withAllZeros_shouldSucceed() {
            OverheadBreakdown breakdown = new OverheadBreakdown(
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Map.of()
            );

            assertThat(breakdown.totalLatency()).isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("Comparison Scenarios")
    class ComparisonScenariosTests {

        @Test
        @DisplayName("BSON-like breakdown should show high traversal ratio")
        void bsonLikeBreakdown_shouldShowHighTraversalRatio() {
            // Simulating BSON O(n) traversal overhead
            OverheadBreakdown bsonBreakdown = OverheadBreakdown.builder()
                    .totalLatency(Duration.ofMicros(1000))
                    .serverTraversalTime(Duration.ofMicros(400)) // High traversal
                    .clientTraversalTime(Duration.ofMicros(150)) // High client traversal
                    .serverFetchTime(Duration.ofMicros(100))
                    .deserializationTime(Duration.ofMicros(200))
                    .build();

            // 55% of latency in traversal
            assertThat(bsonBreakdown.traversalPercentage()).isGreaterThan(50.0);
        }

        @Test
        @DisplayName("OSON-like breakdown should show low traversal ratio")
        void osonLikeBreakdown_shouldShowLowTraversalRatio() {
            // Simulating OSON O(1) traversal overhead
            OverheadBreakdown osonBreakdown = OverheadBreakdown.builder()
                    .totalLatency(Duration.ofMicros(500))
                    .serverTraversalTime(Duration.ofMicros(50))  // Low traversal (hash lookup)
                    .clientTraversalTime(Duration.ofMicros(20))  // Low client traversal
                    .serverFetchTime(Duration.ofMicros(100))
                    .deserializationTime(Duration.ofMicros(50))
                    .build();

            // Only 14% in traversal
            assertThat(osonBreakdown.traversalPercentage()).isLessThan(20.0);
        }

        @Test
        @DisplayName("should accurately compare BSON vs OSON traversal overhead")
        void comparison_shouldShowBsonVsOsonDifference() {
            OverheadBreakdown bson = OverheadBreakdown.builder()
                    .totalLatency(Duration.ofMicros(1245))
                    .serverTraversalTime(Duration.ofMicros(600))
                    .clientTraversalTime(Duration.ofMicros(247))
                    .serverFetchTime(Duration.ofMicros(100))
                    .build();

            OverheadBreakdown oson = OverheadBreakdown.builder()
                    .totalLatency(Duration.ofMicros(423))
                    .serverTraversalTime(Duration.ofMicros(80))
                    .clientTraversalTime(Duration.ofMicros(32))
                    .serverFetchTime(Duration.ofMicros(100))
                    .build();

            Duration bsonTraversal = bson.traversalOverhead();  // 847
            Duration osonTraversal = oson.traversalOverhead();  // 112

            // OSON should be ~7.5x faster for traversal
            double ratio = (double) bsonTraversal.toNanos() / osonTraversal.toNanos();
            assertThat(ratio).isGreaterThan(5.0);
        }
    }
}
