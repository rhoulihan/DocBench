# DocBench

**Extensible Database Document Performance Benchmarking Framework**

DocBench is a command-line benchmarking utility designed to provide empirical, reproducible measurements of document database performance characteristics with particular emphasis on **operational overhead decomposition**—isolating and measuring the distinct cost components that comprise total request latency beyond raw data access time.

## Key Features

- **Overhead Decomposition**: Breaks down operation latency into measurable components (connection, serialization, traversal, network, deserialization)
- **Binary JSON Comparison**: Compares BSON (MongoDB) O(n) traversal vs OSON (Oracle) O(1) hash-indexed navigation
- **Extensible Architecture**: Plugin-based adapter system for adding new database platforms
- **Reproducible Results**: Seeded random generation and deterministic document structures
- **Statistical Rigor**: HdrHistogram-based percentile tracking with high precision

## The Traversal Problem

Traditional benchmarks measure aggregate throughput but fail to show **where time is actually spent**. DocBench specifically measures:

| Format | Traversal Strategy | Complexity |
|--------|-------------------|------------|
| BSON (MongoDB) | Length-prefixed, sequential field-name scanning | O(n) per level |
| OSON (Oracle) | Hash-indexed jump navigation | O(1) per level |

At scale (millions of documents, deeply nested paths), this difference compounds significantly.

## Quick Start

### Prerequisites

- Java 21+
- Gradle 8.5+
- Docker (for TestContainers integration tests)

### Build

```bash
./gradlew build
```

### Run Tests

```bash
# Unit tests
./gradlew test

# Integration tests (requires Docker)
./gradlew integrationTest

# Mutation testing
./gradlew pitest
```

### Usage

```bash
# List available workloads and adapters
./gradlew run --args="list"

# Run benchmark
./gradlew run --args="run -w traverse-deep -a mongodb -a oracle-oson \
  --mongodb-uri 'mongodb://localhost:27017/docbench' \
  --oracle-jdbc 'jdbc:oracle:thin:@localhost:1521/FREEPDB1' \
  -i 50000"

# Compare results
./gradlew run --args="compare results/*.json --format markdown"

# Generate report
./gradlew run --args="report results/*.json -f html -o benchmark-report.html"
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         DocBench CLI                             │
│   [Command Parser] [Config Loader] [Report Generator] [Progress] │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                    Benchmark Orchestrator                        │
│   [Workload Registry] [Execution Engine] [Metrics Collector]     │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                  Database Adapter Layer (SPI)                    │
│  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ MongoDBAdapter │  │ OracleOSONAdapter│  │ [Future Adapters]│  │
│  │  BSON Metrics  │  │   OSON Metrics   │  │  PostgreSQL, etc │  │
│  └────────────────┘  └─────────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Workloads

| Workload | Description | Primary Metric |
|----------|-------------|----------------|
| `traverse-shallow` | Single-level field access | Field position impact |
| `traverse-deep` | Multi-level nested access | O(n) vs O(1) per level |
| `traverse-scale` | Volume amplification | Overhead at scale |
| `deserialize-full` | Complete document parsing | Client-side overhead |
| `deserialize-partial` | Projection-based parsing | Partial access efficiency |

## Configuration

```yaml
# docbench-config.yaml
version: "1.0"

connections:
  mongodb:
    uri: "mongodb://localhost:27017/docbench"

  oracle:
    jdbcUrl: "jdbc:oracle:thin:@localhost:1521/FREEPDB1"
    username: "docbench"
    password: "${ORACLE_PASSWORD}"

workloads:
  traverse-deep:
    enabled: true
    parameters:
      nestingDepth: 5
      fieldsPerLevel: 20
      targetPath: "order.items[5].product.sku"
    execution:
      iterations: 50000
      warmupIterations: 5000
```

## Metrics

### Core Latency Metrics

| Metric | Description |
|--------|-------------|
| `total_latency` | End-to-end operation time |
| `server_execution_time` | DB-reported execution |
| `server_traversal_time` | Document navigation (server) |
| `client_deserialization_time` | Response parsing (client) |
| `client_traversal_time` | Field access after parsing |

### Derived Metrics

| Metric | Formula |
|--------|---------|
| `overhead_ratio` | (total - server_fetch) / total |
| `traversal_ratio` | (server_trav + client_trav) / total |
| `efficiency_score` | server_fetch / total |

## Development

This project follows **strict Test-Driven Development** practices:

1. **Red**: Write failing test first
2. **Green**: Write minimum code to pass
3. **Refactor**: Clean up while keeping tests green

### Project Structure

```
com.docbench
├── cli                     # Command-line interface (picocli)
├── config                  # Configuration management
├── orchestrator            # Benchmark execution
├── workload                # Workload definitions
├── metrics                 # Measurement and collection
├── adapter                 # Database adapter SPI
│   ├── spi                 # Core interfaces
│   ├── mongodb             # MongoDB implementation
│   └── oracle              # Oracle OSON implementation
├── document                # Test document generation
├── report                  # Output generation
└── util                    # Utilities
```

### Code Quality

- **Coverage**: 80%+ line, 70%+ branch
- **Mutation Score**: 60%+ (PIT)
- **Static Analysis**: SpotBugs, Checkstyle

## Sample Output

```
DocBench v1.0.0 - Database Document Performance Benchmark
=========================================================

Workload: traverse-deep
Configuration:
  Nesting Depth: 5
  Fields Per Level: 20
  Target Path: order.items[5].product.sku
  Iterations: 50,000

┌─────────────────────────┬──────────────────┬──────────────────┬───────────┐
│ Metric                  │ MongoDB (BSON)   │ Oracle (OSON)    │ Δ%        │
├─────────────────────────┼──────────────────┼──────────────────┼───────────┤
│ Total Latency (p50)     │ 1,245 μs         │ 423 μs           │ -66.0%    │
│ Server Traversal (p50)  │ 847 μs           │ 112 μs           │ -86.8%    │
│ Client Deser. (p50)     │ 234 μs           │ 45 μs            │ -80.8%    │
│ Overhead Ratio          │ 68.0%            │ 31.2%            │ -36.8pp   │
│ Throughput (ops/sec)    │ 8,032            │ 23,641           │ +194.4%   │
└─────────────────────────┴──────────────────┴──────────────────┴───────────┘

Key Finding: OSON traversal overhead is 85.5% lower than BSON at depth 5.
```

## License

Copyright 2025. All rights reserved.

## Contributing

Contributions are welcome! Please ensure all code follows TDD practices and includes comprehensive tests.
