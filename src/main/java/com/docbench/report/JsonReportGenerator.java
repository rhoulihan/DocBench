package com.docbench.report;

import com.docbench.metrics.HistogramSummary;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates benchmark reports in JSON format.
 * Suitable for machine processing and integration with other tools.
 */
public class JsonReportGenerator implements ReportGenerator {

    @Override
    public String formatName() {
        return "json";
    }

    @Override
    public String fileExtension() {
        return "json";
    }

    @Override
    public String generate(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // Workload info
        sb.append("  \"workload\": \"").append(escape(result.workloadName())).append("\",\n");

        // Timestamps
        if (result.startTime() != null) {
            sb.append("  \"startTime\": \"")
                    .append(DateTimeFormatter.ISO_INSTANT.format(result.startTime()))
                    .append("\",\n");
        }
        if (result.endTime() != null) {
            sb.append("  \"endTime\": \"")
                    .append(DateTimeFormatter.ISO_INSTANT.format(result.endTime()))
                    .append("\",\n");
        }
        if (result.totalDuration() != null) {
            sb.append("  \"durationMs\": ").append(result.totalDuration().toMillis()).append(",\n");
        }

        // Configuration
        if (result.config() != null) {
            sb.append("  \"config\": {\n");
            sb.append("    \"iterations\": ").append(result.config().iterations()).append(",\n");
            sb.append("    \"warmupIterations\": ").append(result.config().warmupIterations()).append(",\n");
            sb.append("    \"parameters\": {\n");
            var params = result.config().parameters().entrySet().toArray();
            for (int i = 0; i < params.length; i++) {
                @SuppressWarnings("unchecked")
                Map.Entry<String, Object> entry = (Map.Entry<String, Object>) params[i];
                sb.append("      \"").append(escape(entry.getKey())).append("\": ");
                appendValue(sb, entry.getValue());
                if (i < params.length - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }\n");
            sb.append("  },\n");
        }

        // Adapters
        sb.append("  \"adapters\": {\n");
        var adapters = result.adapterResults().entrySet().toArray();
        for (int i = 0; i < adapters.length; i++) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, BenchmarkResult.AdapterResult> entry =
                    (Map.Entry<String, BenchmarkResult.AdapterResult>) adapters[i];
            generateAdapterJson(sb, entry.getValue(), "    ");
            if (i < adapters.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  }\n");

        sb.append("}");
        return sb.toString();
    }

    private void generateAdapterJson(StringBuilder sb, BenchmarkResult.AdapterResult adapter, String indent) {
        sb.append(indent).append("\"").append(escape(adapter.adapterId())).append("\": {\n");
        sb.append(indent).append("  \"name\": \"").append(escape(adapter.adapterName())).append("\",\n");
        sb.append(indent).append("  \"iterations\": ").append(adapter.iterations()).append(",\n");
        sb.append(indent).append("  \"warmupIterations\": ").append(adapter.warmupIterations()).append(",\n");
        sb.append(indent).append("  \"durationMs\": ").append(adapter.duration().toMillis()).append(",\n");

        // Metrics
        sb.append(indent).append("  \"metrics\": {\n");
        var histograms = adapter.metrics().allHistograms().entrySet().toArray();
        for (int i = 0; i < histograms.length; i++) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, HistogramSummary> entry =
                    (Map.Entry<String, HistogramSummary>) histograms[i];
            generateHistogramJson(sb, entry.getKey(), entry.getValue(), indent + "    ");
            if (i < histograms.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(indent).append("  },\n");

        // Counters
        sb.append(indent).append("  \"counters\": {\n");
        var counters = adapter.metrics().allCounters().entrySet().toArray();
        for (int i = 0; i < counters.length; i++) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, Long> entry = (Map.Entry<String, Long>) counters[i];
            sb.append(indent).append("    \"").append(escape(entry.getKey()))
                    .append("\": ").append(entry.getValue());
            if (i < counters.length - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(indent).append("  }\n");

        sb.append(indent).append("}");
    }

    private void generateHistogramJson(StringBuilder sb, String name, HistogramSummary h, String indent) {
        sb.append(indent).append("\"").append(escape(name)).append("\": {\n");
        sb.append(indent).append("  \"count\": ").append(h.count()).append(",\n");
        sb.append(indent).append("  \"mean\": ").append(h.mean()).append(",\n");
        sb.append(indent).append("  \"min\": ").append(h.min()).append(",\n");
        sb.append(indent).append("  \"max\": ").append(h.max()).append(",\n");
        sb.append(indent).append("  \"stdDev\": ").append(h.stdDev()).append(",\n");
        sb.append(indent).append("  \"p50\": ").append(h.p50()).append(",\n");
        sb.append(indent).append("  \"p90\": ").append(h.p90()).append(",\n");
        sb.append(indent).append("  \"p95\": ").append(h.p95()).append(",\n");
        sb.append(indent).append("  \"p99\": ").append(h.p99()).append(",\n");
        sb.append(indent).append("  \"p999\": ").append(h.p999()).append("\n");
        sb.append(indent).append("}");
    }

    private void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append("\"").append(escape((String) value)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append("\"").append(escape(value.toString())).append("\"");
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
