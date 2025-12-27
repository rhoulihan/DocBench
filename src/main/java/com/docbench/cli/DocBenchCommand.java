package com.docbench.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Main entry point for DocBench CLI.
 * Extensible Database Document Performance Benchmarking Framework.
 */
@Command(
        name = "docbench",
        description = "Database Document Performance Benchmarking Framework",
        version = "DocBench 1.0.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                RunCommand.class,
                CompareCommand.class,
                ReportCommand.class,
                ListCommand.class,
                ValidateCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class DocBenchCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"},
            description = "Configuration file (YAML/JSON)",
            paramLabel = "<file>")
    private String configFile;

    @Option(names = {"-v", "--verbose"},
            description = "Increase output verbosity")
    private boolean verbose;

    @Option(names = {"-q", "--quiet"},
            description = "Suppress non-essential output")
    private boolean quiet;

    @Option(names = {"--no-color"},
            description = "Disable colored output")
    private boolean noColor;

    @Option(names = {"--log-level"},
            description = "Set log level (DEBUG, INFO, WARN, ERROR)",
            paramLabel = "<level>",
            defaultValue = "INFO")
    private String logLevel;

    /**
     * Entry point for the application.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new DocBenchCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // No subcommand specified, show help
        CommandLine.usage(this, System.out);
        return 0;
    }

    // Accessor methods for subcommands
    public String getConfigFile() {
        return configFile;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public boolean isNoColor() {
        return noColor;
    }

    public String getLogLevel() {
        return logLevel;
    }
}
