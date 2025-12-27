package com.docbench.report;

import com.docbench.metrics.HistogramSummary;

import java.util.Map;

/**
 * Generates benchmark reports in CSV format.
 * Suitable for spreadsheet analysis and data processing.
 */
public class CsvReportGenerator implements ReportGenerator {

    private static final String HEADER = "adapter,metric,count,mean,min,max,stdDev,p50,p90,p95,p99,p999";

    @Override
    public String formatName() {
        return "csv";
    }

    @Override
    public String fileExtension() {
        return "csv";
    }

    @Override
    public String generate(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();

        // Header row
        sb.append(HEADER).append("\n");

        // Data rows for each adapter
        for (BenchmarkResult.AdapterResult adapter : result.adapterResults().values()) {
            for (Map.Entry<String, HistogramSummary> entry :
                    adapter.metrics().allHistograms().entrySet()) {
                String metricName = entry.getKey();
                HistogramSummary h = entry.getValue();

                sb.append(escapeCsv(adapter.adapterId())).append(",");
                sb.append(escapeCsv(metricName)).append(",");
                sb.append(h.count()).append(",");
                sb.append(h.mean()).append(",");
                sb.append(h.min()).append(",");
                sb.append(h.max()).append(",");
                sb.append(h.stdDev()).append(",");
                sb.append(h.p50()).append(",");
                sb.append(h.p90()).append(",");
                sb.append(h.p95()).append(",");
                sb.append(h.p99()).append(",");
                sb.append(h.p999()).append("\n");
            }
        }

        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
