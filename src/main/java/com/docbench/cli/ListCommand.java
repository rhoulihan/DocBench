package com.docbench.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * List available resources (workloads, adapters, metrics).
 */
@Command(
        name = "list",
        description = "List available workloads, adapters, and metrics",
        mixinStandardHelpOptions = true
)
public class ListCommand implements Callable<Integer> {

    @ParentCommand
    private DocBenchCommand parent;

    @Parameters(description = "Resource type: workloads, adapters, metrics, all",
            paramLabel = "<type>",
            defaultValue = "all")
    private ResourceType resourceType = ResourceType.ALL;

    @Option(names = {"--format"},
            description = "Output format: table, json",
            paramLabel = "<fmt>",
            defaultValue = "table")
    private String format;

    @Option(names = {"--verbose"},
            description = "Show detailed descriptions")
    private boolean verbose;

    enum ResourceType {
        WORKLOADS, ADAPTERS, METRICS, ALL
    }

    @Override
    public Integer call() {
        switch (resourceType) {
            case WORKLOADS -> listWorkloads();
            case ADAPTERS -> listAdapters();
            case METRICS -> listMetrics();
            case ALL -> {
                listWorkloads();
                System.out.println();
                listAdapters();
                System.out.println();
                listMetrics();
            }
        }
        return 0;
    }

    private void listWorkloads() {
        System.out.println("Available Workloads");
        System.out.println("-".repeat(50));
        System.out.println();

        printWorkload("traverse-shallow", "Single-level field access",
                "Measures field-location overhead at document root level");
        printWorkload("traverse-deep", "Multi-level nested access",
                "Primary workload for O(n) vs O(1) traversal comparison");
        printWorkload("traverse-scale", "Volume amplification",
                "Demonstrates overhead compounding across large document volumes");
        printWorkload("deserialize-full", "Complete document parsing",
                "Measures client-side deserialization overhead for full documents");
        printWorkload("deserialize-partial", "Projection-based parsing",
                "Measures client library ability to skip unneeded fields");
    }

    private void printWorkload(String id, String name, String description) {
        System.out.println("  " + id);
        System.out.println("    " + name);
        if (verbose) {
            System.out.println("    " + description);
        }
    }

    private void listAdapters() {
        System.out.println("Available Adapters");
        System.out.println("-".repeat(50));
        System.out.println();

        printAdapter("mongodb", "MongoDB", "7.0+",
                "BSON format with O(n) field scanning");
        printAdapter("oracle-oson", "Oracle OSON", "23ai+",
                "OSON format with O(1) hash-indexed navigation");
    }

    private void printAdapter(String id, String name, String version, String description) {
        System.out.println("  " + id);
        System.out.println("    " + name + " (" + version + ")");
        if (verbose) {
            System.out.println("    " + description);
        }
    }

    private void listMetrics() {
        System.out.println("Available Metrics");
        System.out.println("-".repeat(50));
        System.out.println();

        System.out.println("  Latency Metrics:");
        printMetric("total_latency", "End-to-end operation time");
        printMetric("server_execution_time", "DB-reported execution time");
        printMetric("server_traversal_time", "Document navigation (server)");
        printMetric("client_deserialization_time", "Response parsing (client)");
        printMetric("client_traversal_time", "Field access after parsing");

        System.out.println();
        System.out.println("  Derived Metrics:");
        printMetric("overhead_ratio", "Percentage spent in overhead");
        printMetric("traversal_ratio", "Traversal overhead percentage");
        printMetric("efficiency_score", "Data retrieval efficiency");
    }

    private void printMetric(String id, String description) {
        System.out.println("    " + id);
        if (verbose) {
            System.out.println("      " + description);
        }
    }
}
