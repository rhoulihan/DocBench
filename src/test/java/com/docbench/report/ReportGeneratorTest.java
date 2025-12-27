package com.docbench.report;

import com.docbench.metrics.HistogramSummary;
import com.docbench.metrics.MetricsSummary;
import com.docbench.workload.WorkloadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for report generators.
 */
@DisplayName("Report Generators")
class ReportGeneratorTest {

    private BenchmarkResult benchmarkResult;

    @BeforeEach
    void setUp() {
        // Create test metrics summaries
        HistogramSummary traverseMetric = new HistogramSummary(
                10000,  // count
                50000,  // mean (50 microseconds in nanos)
                45000,  // p50
                75000,  // p90
                85000,  // p95
                95000,  // p99
                120000, // p999
                20000,  // min
                200000, // max
                15000   // stdDev
        );

        MetricsSummary mongoMetrics = new MetricsSummary(
                Map.of("traverse", traverseMetric),
                Map.of("operations", 10000L)
        );

        MetricsSummary oracleMetrics = new MetricsSummary(
                Map.of("traverse", new HistogramSummary(
                        10000, 32000, 30000, 48000, 55000, 62000, 78000, 15000, 150000, 10000
                )),
                Map.of("operations", 10000L)
        );

        benchmarkResult = BenchmarkResult.builder("traverse")
                .config(WorkloadConfig.builder("traverse")
                        .iterations(10000)
                        .warmupIterations(1000)
                        .parameter("nestingDepth", 5)
                        .build())
                .addAdapterResult(new BenchmarkResult.AdapterResult(
                        "mongodb-bson",
                        "MongoDB (BSON)",
                        mongoMetrics,
                        10000,
                        1000,
                        Duration.ofSeconds(30)
                ))
                .addAdapterResult(new BenchmarkResult.AdapterResult(
                        "oracle-oson",
                        "Oracle (OSON)",
                        oracleMetrics,
                        10000,
                        1000,
                        Duration.ofSeconds(25)
                ))
                .startTime(Instant.parse("2025-01-15T10:00:00Z"))
                .endTime(Instant.parse("2025-01-15T10:01:00Z"))
                .build();
    }

    @Nested
    @DisplayName("ConsoleReportGenerator")
    class ConsoleReportTests {

        private ConsoleReportGenerator generator;

        @BeforeEach
        void setUp() {
            generator = new ConsoleReportGenerator();
        }

        @Test
        @DisplayName("should have correct format name")
        void shouldHaveCorrectFormatName() {
            assertThat(generator.formatName()).isEqualTo("console");
        }

        @Test
        @DisplayName("should have txt extension")
        void shouldHaveTxtExtension() {
            assertThat(generator.fileExtension()).isEqualTo("txt");
        }

        @Test
        @DisplayName("should include workload name in report")
        void shouldIncludeWorkloadName() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("traverse");
        }

        @Test
        @DisplayName("should include adapter names")
        void shouldIncludeAdapterNames() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("MongoDB");
            assertThat(report).contains("Oracle");
        }

        @Test
        @DisplayName("should include latency percentiles")
        void shouldIncludeLatencyPercentiles() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).containsIgnoringCase("p50");
            assertThat(report).containsIgnoringCase("p99");
        }

        @Test
        @DisplayName("should show comparison ratio")
        void shouldShowComparisonRatio() {
            String report = generator.generate(benchmarkResult);
            // Oracle should be faster (32us vs 50us mean)
            assertThat(report).containsPattern("\\d+\\.\\d+x");
        }
    }

    @Nested
    @DisplayName("JsonReportGenerator")
    class JsonReportTests {

        private JsonReportGenerator generator;

        @BeforeEach
        void setUp() {
            generator = new JsonReportGenerator();
        }

        @Test
        @DisplayName("should have correct format name")
        void shouldHaveCorrectFormatName() {
            assertThat(generator.formatName()).isEqualTo("json");
        }

        @Test
        @DisplayName("should have json extension")
        void shouldHaveJsonExtension() {
            assertThat(generator.fileExtension()).isEqualTo("json");
        }

        @Test
        @DisplayName("should produce valid JSON structure")
        void shouldProduceValidJson() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).startsWith("{");
            assertThat(report).endsWith("}");
        }

        @Test
        @DisplayName("should include workload name")
        void shouldIncludeWorkloadName() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("\"workload\"");
            assertThat(report).contains("\"traverse\"");
        }

        @Test
        @DisplayName("should include adapter results")
        void shouldIncludeAdapterResults() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("\"adapters\"");
            assertThat(report).contains("\"mongodb-bson\"");
            assertThat(report).contains("\"oracle-oson\"");
        }

        @Test
        @DisplayName("should include metrics data")
        void shouldIncludeMetricsData() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("\"metrics\"");
            assertThat(report).contains("\"p50\"");
            assertThat(report).contains("\"p99\"");
        }
    }

    @Nested
    @DisplayName("CsvReportGenerator")
    class CsvReportTests {

        private CsvReportGenerator generator;

        @BeforeEach
        void setUp() {
            generator = new CsvReportGenerator();
        }

        @Test
        @DisplayName("should have correct format name")
        void shouldHaveCorrectFormatName() {
            assertThat(generator.formatName()).isEqualTo("csv");
        }

        @Test
        @DisplayName("should have csv extension")
        void shouldHaveCsvExtension() {
            assertThat(generator.fileExtension()).isEqualTo("csv");
        }

        @Test
        @DisplayName("should include header row")
        void shouldIncludeHeaderRow() {
            String report = generator.generate(benchmarkResult);
            String[] lines = report.split("\n");
            assertThat(lines[0]).containsIgnoringCase("adapter");
            assertThat(lines[0]).containsIgnoringCase("metric");
        }

        @Test
        @DisplayName("should include data rows for each adapter")
        void shouldIncludeDataRows() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("mongodb-bson");
            assertThat(report).contains("oracle-oson");
        }

        @Test
        @DisplayName("should use comma separator")
        void shouldUseCommaSeparator() {
            String report = generator.generate(benchmarkResult);
            String[] lines = report.split("\n");
            assertThat(lines[0]).contains(",");
        }
    }

    @Nested
    @DisplayName("HtmlReportGenerator")
    class HtmlReportTests {

        private HtmlReportGenerator generator;

        @BeforeEach
        void setUp() {
            generator = new HtmlReportGenerator();
        }

        @Test
        @DisplayName("should have correct format name")
        void shouldHaveCorrectFormatName() {
            assertThat(generator.formatName()).isEqualTo("html");
        }

        @Test
        @DisplayName("should have html extension")
        void shouldHaveHtmlExtension() {
            assertThat(generator.fileExtension()).isEqualTo("html");
        }

        @Test
        @DisplayName("should produce valid HTML structure")
        void shouldProduceValidHtml() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).containsIgnoringCase("<!doctype html>");
            assertThat(report).containsIgnoringCase("<html");
            assertThat(report).containsIgnoringCase("</html>");
        }

        @Test
        @DisplayName("should include title")
        void shouldIncludeTitle() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).containsIgnoringCase("<title>");
            assertThat(report).contains("DocBench");
        }

        @Test
        @DisplayName("should include results table")
        void shouldIncludeResultsTable() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).containsIgnoringCase("<table");
            assertThat(report).containsIgnoringCase("</table>");
        }

        @Test
        @DisplayName("should include adapter results")
        void shouldIncludeAdapterResults() {
            String report = generator.generate(benchmarkResult);
            assertThat(report).contains("MongoDB");
            assertThat(report).contains("Oracle");
        }
    }
}
