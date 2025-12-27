package com.docbench.cli;

import com.docbench.adapter.AdapterRegistry;
import com.docbench.workload.WorkloadRegistry;
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

        // List registered workloads
        WorkloadRegistry.describeAll().forEach((id, description) -> {
            System.out.println("  " + id);
            if (verbose) {
                System.out.println("    " + description);
            }
        });

        System.out.println();
        System.out.println("Usage: docbench run -w <workload> -a <adapter>");
    }

    private void listAdapters() {
        System.out.println("Available Adapters");
        System.out.println("-".repeat(50));
        System.out.println();

        // List registered adapters
        AdapterRegistry.describeAll().forEach((id, name) -> {
            System.out.println("  " + id);
            System.out.println("    " + name);
            if (verbose) {
                if (id.equals("mongodb")) {
                    System.out.println("    BSON format with O(n) field scanning");
                } else if (id.equals("oracle-oson")) {
                    System.out.println("    OSON format with O(1) hash-indexed navigation");
                }
            }
        });
    }

    private void listMetrics() {
        System.out.println("Available Metrics");
        System.out.println("-".repeat(50));
        System.out.println();

        System.out.println("  Latency Metrics:");
        printMetric("total_latency", "End-to-end operation time");
        printMetric("traverse", "Path traversal latency");
        printMetric("deserialize", "Full document deserialization latency");

        System.out.println();
        System.out.println("  Overhead Breakdown:");
        printMetric("serialization", "Document serialization time");
        printMetric("deserialization", "Document deserialization time");
        printMetric("server_execution", "Server-side execution time");
        printMetric("server_traversal", "Server-side path navigation");
        printMetric("client_traversal", "Client-side field access");
        printMetric("network_overhead", "Network round-trip overhead");

        System.out.println();
        System.out.println("  Statistical Percentiles:");
        printMetric("p50", "Median latency");
        printMetric("p90", "90th percentile");
        printMetric("p95", "95th percentile");
        printMetric("p99", "99th percentile");
        printMetric("p999", "99.9th percentile");
    }

    private void printMetric(String id, String description) {
        System.out.println("    " + id);
        if (verbose) {
            System.out.println("      " + description);
        }
    }
}
