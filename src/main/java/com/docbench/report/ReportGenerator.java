package com.docbench.report;

import com.docbench.metrics.MetricsSummary;

import java.io.IOException;
import java.io.Writer;

/**
 * Interface for generating benchmark reports in various formats.
 * Implementations convert MetricsSummary data into formatted output.
 */
public interface ReportGenerator {

    /**
     * Returns the format name for this generator.
     */
    String formatName();

    /**
     * Returns the default file extension for this format.
     */
    String fileExtension();

    /**
     * Generates a report from the given metrics summary.
     *
     * @param summary the metrics summary to report
     * @return the formatted report as a string
     */
    String generate(BenchmarkResult summary);

    /**
     * Writes the report directly to a writer.
     *
     * @param summary the metrics summary to report
     * @param writer  the target writer
     * @throws IOException if writing fails
     */
    default void write(BenchmarkResult summary, Writer writer) throws IOException {
        writer.write(generate(summary));
    }
}
