package com.docbench.cli;

import com.docbench.adapter.AdapterRegistry;
import com.docbench.adapter.spi.ConnectionConfig;
import com.docbench.adapter.spi.DatabaseAdapter;
import com.docbench.orchestrator.BenchmarkExecutor;
import com.docbench.report.*;
import com.docbench.workload.Workload;
import com.docbench.workload.WorkloadConfig;
import com.docbench.workload.WorkloadRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Execute benchmark workloads against configured database adapters.
 */
@Command(
        name = "run",
        description = "Execute benchmark workloads against database adapters",
        mixinStandardHelpOptions = true
)
public class RunCommand implements Callable<Integer> {

    @ParentCommand
    private DocBenchCommand parent;

    @Option(names = {"-w", "--workload"},
            description = "Workload to execute (repeatable). Available: ${COMPLETION-CANDIDATES}",
            paramLabel = "<id>",
            completionCandidates = WorkloadCandidates.class)
    private List<String> workloads = new ArrayList<>();

    @Option(names = {"-a", "--adapter"},
            description = "Database adapter (repeatable, required). Available: mongodb, oracle-oson",
            paramLabel = "<id>",
            required = true)
    private List<String> adapters = new ArrayList<>();

    @Option(names = {"--all-workloads"},
            description = "Run all compatible workloads")
    private boolean allWorkloads;

    @Option(names = {"-i", "--iterations"},
            description = "Operation count (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "10000")
    private int iterations;

    @Option(names = {"--warmup"},
            description = "Warmup iterations (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "1000")
    private int warmup;

    @Option(names = {"--concurrency"},
            description = "Concurrent threads (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "1")
    private int concurrency;

    @Option(names = {"--nesting-depth"},
            description = "Document nesting depth (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "5")
    private int nestingDepth;

    @Option(names = {"--field-count"},
            description = "Fields per document (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "20")
    private int fieldCount;

    @Option(names = {"--doc-count"},
            description = "Number of test documents (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "100")
    private int documentCount;

    @Option(names = {"--doc-size"},
            description = "Target document size in bytes (default: ${DEFAULT-VALUE})",
            paramLabel = "<n>",
            defaultValue = "5000")
    private int documentSize;

    @Option(names = {"-o", "--output"},
            description = "Output directory for results",
            paramLabel = "<dir>")
    private File outputDir;

    @Option(names = {"-f", "--format"},
            description = "Output format: json, csv, console, html (repeatable)",
            paramLabel = "<fmt>")
    private List<String> formats = new ArrayList<>();

    @Option(names = {"--seed"},
            description = "Random seed for reproducibility",
            paramLabel = "<n>")
    private Long seed;

    @Option(names = {"--dry-run"},
            description = "Validate config without executing")
    private boolean dryRun;

    // Connection options
    @Option(names = {"--mongodb-uri"},
            description = "MongoDB connection string",
            paramLabel = "<uri>",
            defaultValue = "mongodb://localhost:27017")
    private String mongodbUri;

    @Option(names = {"--mongodb-db"},
            description = "MongoDB database name",
            paramLabel = "<db>",
            defaultValue = "docbench")
    private String mongodbDatabase;

    @Option(names = {"--oracle-jdbc"},
            description = "Oracle JDBC URL",
            paramLabel = "<url>")
    private String oracleJdbcUrl;

    @Option(names = {"--oracle-user"},
            description = "Oracle username",
            paramLabel = "<user>")
    private String oracleUser;

    @Option(names = {"--oracle-password"},
            description = "Oracle password",
            paramLabel = "<pass>")
    private String oraclePassword;

    @Override
    public Integer call() {
        if (!allWorkloads && workloads.isEmpty()) {
            System.err.println("Error: Either --workload or --all-workloads must be specified");
            return 1;
        }

        // Determine workloads to run
        List<String> workloadsToRun = allWorkloads ?
                new ArrayList<>(WorkloadRegistry.availableWorkloads()) :
                workloads;

        // Default to console format if none specified
        if (formats.isEmpty()) {
            formats.add("console");
        }

        if (dryRun) {
            System.out.println("Dry run - validating configuration...");
            try {
                validateConfiguration(workloadsToRun);
                System.out.println("Configuration is valid.");
                System.out.println("\nWorkloads: " + workloadsToRun);
                System.out.println("Adapters: " + adapters);
                System.out.println("Iterations: " + iterations);
                System.out.println("Warmup: " + warmup);
                return 0;
            } catch (Exception e) {
                System.err.println("Validation failed: " + e.getMessage());
                return 1;
            }
        }

        try {
            executeBenchmark(workloadsToRun);
            return 0;
        } catch (Exception e) {
            System.err.println("Benchmark execution failed: " + e.getMessage());
            if (parent != null && parent.isVerbose()) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private void validateConfiguration(List<String> workloadsToRun) {
        // Validate adapters exist
        for (String adapter : adapters) {
            if (!AdapterRegistry.exists(adapter)) {
                throw new IllegalArgumentException("Unknown adapter: " + adapter +
                        ". Available: " + AdapterRegistry.availableAdapters());
            }
        }

        // Validate workloads exist
        for (String workload : workloadsToRun) {
            if (!WorkloadRegistry.exists(workload)) {
                throw new IllegalArgumentException("Unknown workload: " + workload +
                        ". Available: " + WorkloadRegistry.availableWorkloads());
            }
        }

        // Validate formats
        Set<String> validFormats = Set.of("console", "json", "csv", "html");
        for (String format : formats) {
            if (!validFormats.contains(format)) {
                throw new IllegalArgumentException("Unknown format: " + format +
                        ". Available: " + validFormats);
            }
        }
    }

    private void executeBenchmark(List<String> workloadsToRun) throws Exception {
        boolean verbose = parent != null && parent.isVerbose();

        printHeader();

        BenchmarkExecutor executor = new BenchmarkExecutor(verbose);
        Instant startTime = Instant.now();

        for (String workloadId : workloadsToRun) {
            System.out.println("\n" + "═".repeat(60));
            System.out.println("Workload: " + workloadId);
            System.out.println("═".repeat(60));

            BenchmarkResult.Builder resultBuilder = BenchmarkResult.builder(workloadId)
                    .startTime(startTime);

            // Build workload config
            WorkloadConfig config = buildWorkloadConfig(workloadId);
            resultBuilder.config(config);

            for (String adapterId : adapters) {
                System.out.println("\n▸ Running on " + adapterId + "...");

                try {
                    DatabaseAdapter adapter = createAdapter(adapterId);
                    Workload workload = WorkloadRegistry.create(workloadId);

                    BenchmarkResult.AdapterResult adapterResult =
                            executor.execute(workload, adapter, config);

                    resultBuilder.addAdapterResult(adapterResult);

                    // Print quick summary
                    System.out.println("  ✓ Completed in " + adapterResult.duration().toMillis() + "ms");

                    adapter.close();
                } catch (Exception e) {
                    System.err.println("  ✗ Failed: " + e.getMessage());
                    if (verbose) {
                        e.printStackTrace();
                    }
                }
            }

            BenchmarkResult result = resultBuilder.build();

            // Generate and output reports
            generateReports(result);
        }
    }

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        DocBench - Database Document Performance Benchmark     ║");
        System.out.println("║                    BSON vs OSON Comparison                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Adapters:    " + adapters);
        System.out.println("  Iterations:  " + iterations);
        System.out.println("  Warmup:      " + warmup);
        System.out.println("  Doc count:   " + documentCount);
        System.out.println("  Nesting:     " + nestingDepth + " levels");
        if (seed != null) {
            System.out.println("  Seed:        " + seed);
        }
    }

    private WorkloadConfig buildWorkloadConfig(String workloadId) {
        WorkloadConfig.Builder builder = WorkloadConfig.builder(workloadId)
                .iterations(iterations)
                .warmupIterations(warmup)
                .concurrency(concurrency)
                .parameter("documentCount", documentCount)
                .parameter("nestingDepth", nestingDepth)
                .parameter("fieldCount", fieldCount)
                .parameter("documentSizeBytes", documentSize)
                .parameter("fieldsPerLevel", 10);

        if (seed != null) {
            builder.seed(seed);
        }

        // Add connection parameters
        builder.parameter("host", "localhost");
        builder.parameter("database", mongodbDatabase);

        return builder.build();
    }

    private DatabaseAdapter createAdapter(String adapterId) {
        DatabaseAdapter adapter = AdapterRegistry.create(adapterId);

        // Configure connection based on adapter type
        ConnectionConfig.Builder connBuilder = ConnectionConfig.builder();

        if ("mongodb".equals(adapterId)) {
            // Parse MongoDB URI
            connBuilder.host("localhost")
                    .port(27017)
                    .database(mongodbDatabase);
            if (mongodbUri != null && !mongodbUri.isEmpty()) {
                connBuilder.option("uri", mongodbUri);
            }
        } else if ("oracle-oson".equals(adapterId)) {
            if (oracleJdbcUrl != null) {
                connBuilder.option("jdbcUrl", oracleJdbcUrl);
            }
            if (oracleUser != null) {
                connBuilder.username(oracleUser);
            }
            if (oraclePassword != null) {
                connBuilder.password(oraclePassword);
            }
        }

        return adapter;
    }

    private void generateReports(BenchmarkResult result) throws IOException {
        for (String format : formats) {
            ReportGenerator generator = createReportGenerator(format);

            if ("console".equals(format)) {
                // Print to console
                System.out.println(generator.generate(result));
            } else if (outputDir != null) {
                // Write to file
                outputDir.mkdirs();
                String filename = result.workloadName() + "_" +
                        System.currentTimeMillis() + "." + generator.fileExtension();
                File outFile = new File(outputDir, filename);

                try (FileWriter writer = new FileWriter(outFile)) {
                    generator.write(result, writer);
                }
                System.out.println("Report written to: " + outFile.getAbsolutePath());
            }
        }
    }

    private ReportGenerator createReportGenerator(String format) {
        return switch (format) {
            case "console" -> new ConsoleReportGenerator();
            case "json" -> new JsonReportGenerator();
            case "csv" -> new CsvReportGenerator();
            case "html" -> new HtmlReportGenerator();
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        };
    }

    // Completion candidates for workloads
    static class WorkloadCandidates implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return WorkloadRegistry.availableWorkloads().iterator();
        }
    }

    // Accessor methods for testing
    public List<String> getWorkloads() {
        return workloads;
    }

    public List<String> getAdapters() {
        return adapters;
    }

    public int getIterations() {
        return iterations;
    }

    public int getWarmup() {
        return warmup;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public Long getSeed() {
        return seed;
    }

    public boolean isDryRun() {
        return dryRun;
    }
}
