package com.docbench.report;

import com.docbench.metrics.HistogramSummary;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates benchmark reports in HTML format.
 * Creates a styled, self-contained HTML document with tables and charts.
 */
public class HtmlReportGenerator implements ReportGenerator {

    @Override
    public String formatName() {
        return "html";
    }

    @Override
    public String fileExtension() {
        return "html";
    }

    @Override
    public String generate(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();

        // HTML header
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"UTF-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("  <title>DocBench Report - ").append(escape(result.workloadName())).append("</title>\n");
        sb.append(generateStyles());
        sb.append("</head>\n");
        sb.append("<body>\n");

        // Header section
        sb.append("  <header>\n");
        sb.append("    <h1>DocBench Benchmark Report</h1>\n");
        sb.append("    <p class=\"subtitle\">BSON vs OSON Performance Comparison</p>\n");
        sb.append("  </header>\n\n");

        // Summary section
        sb.append("  <section class=\"summary\">\n");
        sb.append("    <h2>Summary</h2>\n");
        sb.append("    <div class=\"info-grid\">\n");
        sb.append("      <div class=\"info-item\">\n");
        sb.append("        <span class=\"label\">Workload</span>\n");
        sb.append("        <span class=\"value\">").append(escape(result.workloadName())).append("</span>\n");
        sb.append("      </div>\n");
        if (result.startTime() != null) {
            sb.append("      <div class=\"info-item\">\n");
            sb.append("        <span class=\"label\">Run At</span>\n");
            sb.append("        <span class=\"value\">")
                    .append(DateTimeFormatter.ISO_INSTANT.format(result.startTime()))
                    .append("</span>\n");
            sb.append("      </div>\n");
        }
        if (result.totalDuration() != null) {
            sb.append("      <div class=\"info-item\">\n");
            sb.append("        <span class=\"label\">Duration</span>\n");
            sb.append("        <span class=\"value\">")
                    .append(formatDuration(result.totalDuration().toMillis()))
                    .append("</span>\n");
            sb.append("      </div>\n");
        }
        if (result.config() != null) {
            sb.append("      <div class=\"info-item\">\n");
            sb.append("        <span class=\"label\">Iterations</span>\n");
            sb.append("        <span class=\"value\">").append(result.config().iterations()).append("</span>\n");
            sb.append("      </div>\n");
        }
        sb.append("    </div>\n");
        sb.append("  </section>\n\n");

        // Results section
        sb.append("  <section class=\"results\">\n");
        sb.append("    <h2>Latency Results</h2>\n");

        for (BenchmarkResult.AdapterResult adapter : result.adapterResults().values()) {
            sb.append("    <div class=\"adapter-results\">\n");
            sb.append("      <h3>").append(escape(adapter.adapterName())).append("</h3>\n");
            sb.append("      <table>\n");
            sb.append("        <thead>\n");
            sb.append("          <tr>\n");
            sb.append("            <th>Metric</th>\n");
            sb.append("            <th>Count</th>\n");
            sb.append("            <th>Mean</th>\n");
            sb.append("            <th>P50</th>\n");
            sb.append("            <th>P90</th>\n");
            sb.append("            <th>P95</th>\n");
            sb.append("            <th>P99</th>\n");
            sb.append("            <th>P99.9</th>\n");
            sb.append("          </tr>\n");
            sb.append("        </thead>\n");
            sb.append("        <tbody>\n");

            for (Map.Entry<String, HistogramSummary> entry :
                    adapter.metrics().allHistograms().entrySet()) {
                HistogramSummary h = entry.getValue();
                sb.append("          <tr>\n");
                sb.append("            <td>").append(escape(entry.getKey())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNumber(h.count())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.mean())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.p50())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.p90())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.p95())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.p99())).append("</td>\n");
                sb.append("            <td class=\"number\">").append(formatNanos(h.p999())).append("</td>\n");
                sb.append("          </tr>\n");
            }

            sb.append("        </tbody>\n");
            sb.append("      </table>\n");
            sb.append("    </div>\n\n");
        }

        sb.append("  </section>\n\n");

        // Comparison section
        if (result.adapterResults().size() > 1) {
            generateComparisonSection(sb, result);
        }

        // Footer
        sb.append("  <footer>\n");
        sb.append("    <p>Generated by DocBench - Database Document Performance Benchmarking</p>\n");
        sb.append("  </footer>\n");

        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private void generateComparisonSection(StringBuilder sb, BenchmarkResult result) {
        var adapters = result.adapterResults().values().toArray(new BenchmarkResult.AdapterResult[0]);
        if (adapters.length < 2) return;

        BenchmarkResult.AdapterResult first = adapters[0];
        BenchmarkResult.AdapterResult second = adapters[1];

        sb.append("  <section class=\"comparison\">\n");
        sb.append("    <h2>Comparison</h2>\n");
        sb.append("    <table>\n");
        sb.append("      <thead>\n");
        sb.append("        <tr>\n");
        sb.append("          <th>Metric</th>\n");
        sb.append("          <th>").append(escape(first.adapterName())).append("</th>\n");
        sb.append("          <th>").append(escape(second.adapterName())).append("</th>\n");
        sb.append("          <th>Winner</th>\n");
        sb.append("          <th>Speedup</th>\n");
        sb.append("        </tr>\n");
        sb.append("      </thead>\n");
        sb.append("      <tbody>\n");

        for (String metricName : first.metrics().allHistograms().keySet()) {
            if (!second.metrics().allHistograms().containsKey(metricName)) continue;

            HistogramSummary h1 = first.metrics().allHistograms().get(metricName);
            HistogramSummary h2 = second.metrics().allHistograms().get(metricName);

            double ratio = h1.mean() / h2.mean();
            String winner = ratio > 1 ? second.adapterName() : first.adapterName();
            String winnerClass = ratio > 1 ? "winner-second" : "winner-first";
            double speedup = ratio > 1 ? ratio : 1.0 / ratio;

            sb.append("        <tr>\n");
            sb.append("          <td>").append(escape(metricName)).append("</td>\n");
            sb.append("          <td class=\"number\">").append(formatNanos(h1.mean())).append("</td>\n");
            sb.append("          <td class=\"number\">").append(formatNanos(h2.mean())).append("</td>\n");
            sb.append("          <td class=\"").append(winnerClass).append("\">")
                    .append(escape(winner)).append("</td>\n");
            sb.append("          <td class=\"number speedup\">")
                    .append(String.format("%.2fx", speedup)).append("</td>\n");
            sb.append("        </tr>\n");
        }

        sb.append("      </tbody>\n");
        sb.append("    </table>\n");
        sb.append("  </section>\n\n");
    }

    private String generateStyles() {
        return """
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                  line-height: 1.6;
                  color: #333;
                  max-width: 1200px;
                  margin: 0 auto;
                  padding: 2rem;
                  background: #f5f5f5;
                }
                header {
                  text-align: center;
                  margin-bottom: 2rem;
                  padding: 2rem;
                  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                  color: white;
                  border-radius: 8px;
                }
                header h1 { font-size: 2rem; margin-bottom: 0.5rem; }
                .subtitle { opacity: 0.9; }
                section { background: white; padding: 1.5rem; margin-bottom: 1.5rem; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                h2 { color: #333; margin-bottom: 1rem; border-bottom: 2px solid #667eea; padding-bottom: 0.5rem; }
                h3 { color: #555; margin-bottom: 0.75rem; }
                .info-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; }
                .info-item { background: #f8f9fa; padding: 1rem; border-radius: 4px; }
                .info-item .label { display: block; font-size: 0.875rem; color: #666; margin-bottom: 0.25rem; }
                .info-item .value { display: block; font-size: 1.25rem; font-weight: 600; color: #333; }
                table { width: 100%; border-collapse: collapse; margin-top: 1rem; }
                th, td { padding: 0.75rem; text-align: left; border-bottom: 1px solid #eee; }
                th { background: #f8f9fa; font-weight: 600; color: #555; }
                .number { text-align: right; font-family: 'Monaco', 'Menlo', monospace; }
                .adapter-results { margin-bottom: 1.5rem; }
                .winner-first { color: #28a745; font-weight: 600; }
                .winner-second { color: #007bff; font-weight: 600; }
                .speedup { color: #667eea; font-weight: 600; }
                footer { text-align: center; color: #666; padding: 1rem; font-size: 0.875rem; }
              </style>
            """;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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

    private String formatNumber(long value) {
        return String.format("%,d", value);
    }

    private String formatNanos(double nanos) {
        if (nanos < 1000) {
            return String.format("%.0fns", nanos);
        } else if (nanos < 1_000_000) {
            return String.format("%.1fµs", nanos / 1000);
        } else {
            return String.format("%.2fms", nanos / 1_000_000);
        }
    }

    private String formatNanos(long nanos) {
        return formatNanos((double) nanos);
    }
}
