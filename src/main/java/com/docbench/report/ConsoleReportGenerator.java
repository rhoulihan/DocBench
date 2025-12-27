package com.docbench.report;

import com.docbench.metrics.HistogramSummary;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates human-readable benchmark reports for console output.
 * Includes colored output and comparison metrics.
 */
public class ConsoleReportGenerator implements ReportGenerator {

    private static final String SEPARATOR = "═".repeat(80);
    private static final String THIN_SEPARATOR = "─".repeat(80);

    @Override
    public String formatName() {
        return "console";
    }

    @Override
    public String fileExtension() {
        return "txt";
    }

    @Override
    public String generate(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("\n").append(SEPARATOR).append("\n");
        sb.append("                      DocBench Benchmark Results\n");
        sb.append(SEPARATOR).append("\n\n");

        // Summary
        sb.append("Workload: ").append(result.workloadName()).append("\n");
        if (result.startTime() != null) {
            sb.append("Run at:   ").append(
                    DateTimeFormatter.ISO_INSTANT.format(result.startTime())
            ).append("\n");
        }
        if (result.totalDuration() != null) {
            sb.append("Duration: ").append(formatDuration(result.totalDuration().toMillis())).append("\n");
        }
        sb.append("\n");

        // Configuration
        if (result.config() != null) {
            sb.append("Configuration:\n");
            sb.append("  Iterations: ").append(result.config().iterations()).append("\n");
            sb.append("  Warmup:     ").append(result.config().warmupIterations()).append("\n");
            if (!result.config().parameters().isEmpty()) {
                sb.append("  Parameters:\n");
                result.config().parameters().forEach((k, v) ->
                        sb.append("    ").append(k).append(": ").append(v).append("\n")
                );
            }
        }
        sb.append("\n");

        // Results for each adapter
        sb.append(THIN_SEPARATOR).append("\n");
        sb.append("                           Latency Results (nanoseconds)\n");
        sb.append(THIN_SEPARATOR).append("\n\n");

        for (BenchmarkResult.AdapterResult adapterResult : result.adapterResults().values()) {
            sb.append("▸ ").append(adapterResult.adapterName()).append("\n");
            sb.append("  ID: ").append(adapterResult.adapterId()).append("\n");
            sb.append("  Iterations: ").append(adapterResult.iterations()).append("\n\n");

            // Metrics table header
            sb.append(String.format("  %-20s %10s %10s %10s %10s %10s %10s%n",
                    "Metric", "Mean", "P50", "P90", "P95", "P99", "P99.9"));
            sb.append("  ").append("─".repeat(76)).append("\n");

            // Metrics rows
            for (Map.Entry<String, HistogramSummary> entry :
                    adapterResult.metrics().allHistograms().entrySet()) {
                HistogramSummary h = entry.getValue();
                sb.append(String.format("  %-20s %10.0f %10d %10d %10d %10d %10d%n",
                        entry.getKey(),
                        h.mean(),
                        h.p50(),
                        h.p90(),
                        h.p95(),
                        h.p99(),
                        h.p999()
                ));
            }
            sb.append("\n");
        }

        // Comparison section (if multiple adapters)
        if (result.adapterResults().size() > 1) {
            sb.append(THIN_SEPARATOR).append("\n");
            sb.append("                              Comparison\n");
            sb.append(THIN_SEPARATOR).append("\n\n");

            generateComparison(sb, result);
        }

        sb.append(SEPARATOR).append("\n");
        return sb.toString();
    }

    private void generateComparison(StringBuilder sb, BenchmarkResult result) {
        var adapters = result.adapterResults().values().toArray(new BenchmarkResult.AdapterResult[0]);
        if (adapters.length < 2) return;

        BenchmarkResult.AdapterResult first = adapters[0];
        BenchmarkResult.AdapterResult second = adapters[1];

        // Find common metrics
        for (String metricName : first.metrics().allHistograms().keySet()) {
            if (!second.metrics().allHistograms().containsKey(metricName)) continue;

            HistogramSummary h1 = first.metrics().allHistograms().get(metricName);
            HistogramSummary h2 = second.metrics().allHistograms().get(metricName);

            double ratio = h1.mean() / h2.mean();
            String faster = ratio > 1 ? second.adapterName() : first.adapterName();
            double speedup = ratio > 1 ? ratio : 1.0 / ratio;

            sb.append(String.format("  %s: %s is %.2fx faster%n",
                    metricName,
                    faster,
                    speedup
            ));

            sb.append(String.format("    %-25s Mean: %,.0f ns (P99: %,d ns)%n",
                    first.adapterName() + ":",
                    h1.mean(),
                    h1.p99()
            ));
            sb.append(String.format("    %-25s Mean: %,.0f ns (P99: %,d ns)%n",
                    second.adapterName() + ":",
                    h2.mean(),
                    h2.p99()
            ));
            sb.append("\n");
        }
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        }
    }
}
