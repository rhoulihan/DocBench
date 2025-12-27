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
| OSON (Oracle) | Hash-indexed jump navigation via SQL/JSON | O(1) per level |

At scale (millions of documents, deeply nested paths), this difference compounds significantly.

## Benchmark Results

Real benchmark results comparing BSON vs OSON field access performance:

```
================================================================================
  BSON vs OSON Performance Comparison (SQL/JSON)
================================================================================

Test Case                            BSON (μs)  OSON (μs)   Ratio  Winner
--------------------------------------------------------------------------------
Position 1/100 (projection)              1280        572   2.24x  OSON
Position 50/100 (projection)              959        494   1.94x  OSON
Position 100/100 (projection)             726        436   1.67x  OSON
Position 500/500 (projection)             783        443   1.77x  OSON
Depth 1 projection                        858        410   2.09x  OSON
Depth 3 projection                        580        360   1.61x  OSON
Depth 5 projection                        567        347   1.63x  OSON
Depth 8 projection                        622        361   1.72x  OSON
3 fields from 200                         598        380   1.57x  OSON
5 fields from 200                         547        389   1.41x  OSON
50 fields (full read)                     665        706   0.94x  BSON
200 fields (full read)                    690        727   0.95x  BSON
customer.tier (nested)                    520        349   1.49x  OSON
grandTotal (last field)                   530        351   1.51x  OSON
--------------------------------------------------------------------------------
TOTAL                                    9925       6325   1.57x  OSON

Summary:
  BSON wins: 2 (full document reads)
  OSON wins: 12 (field projections)
  Overall: OSON 1.57x faster
================================================================================
```

**Key Finding**: OSON's O(1) hash-indexed access via `JSON_VALUE` is **1.5-2.2x faster** for field projection operations. Full document reads favor BSON slightly (0.94-0.95x).

### Benchmark Test Descriptions

| Test | Description | Purpose |
|------|-------------|---------|
| **Position 1/100** | Extract first field from 100-field document | BSON scans just 1 field; best case for BSON |
| **Position 50/100** | Extract middle field from 100-field document | BSON scans 50 fields; OSON does O(1) lookup |
| **Position 100/100** | Extract last field from 100-field document | Worst case for BSON O(n) scanning |
| **Position 500/500** | Extract last field from 500-field document | Tests scalability with larger documents |
| **Depth 1-8** | Extract nested field at various depths | OSON hash-lookup at each level vs BSON sequential scan |
| **3 fields from 200** | Project 3 scattered fields from 200-field doc | OSON: 3 hash lookups; BSON: potentially full scan |
| **5 fields from 200** | Project 5 scattered fields from 200-field doc | Multi-field extraction comparison |
| **50/200 fields (full)** | Read entire document | Baseline: both must read everything |
| **customer.tier** | Access nested field in e-commerce order | Real-world nested object traversal |
| **grandTotal** | Access last field in complex document | Tests last-field penalty in BSON |

## Quick Start

### Prerequisites

- Java 21+ (Java 23 recommended)
- Gradle 8.5+
- Docker (for integration tests)
- MongoDB 7.0+
- Oracle Database 23ai Free

### Build

```bash
./gradlew build
```

### Run Tests

```bash
# Unit tests (238 tests)
./gradlew test

# Integration tests (48 tests - requires MongoDB and Oracle)
./gradlew integrationTest

# Run BSON vs OSON benchmark comparison (14 tests)
./gradlew integrationTest --tests "*.BsonVsOsonComparisonTest"

# Mutation testing
./gradlew pitest
```

### CLI Usage

```bash
# List available workloads, adapters, and metrics
./gradlew run --args="list --verbose"

# Dry run - validate configuration without executing
./gradlew run --args="run --all-workloads -a mongodb -a oracle-oson --dry-run"

# Run specific workload
./gradlew run --args="run -w traverse -a mongodb --iterations 1000"

# Run with custom document parameters
./gradlew run --args="run -w deserialize -a mongodb --doc-count 50 --nesting-depth 8"
```

### Configuration

Create `config/local.properties`:

```properties
# MongoDB configuration
mongodb.uri=mongodb://user:pass@localhost:27017/docbench
mongodb.database=docbench

# Oracle configuration (23ai with SQL/JSON)
oracle.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1
oracle.username=docbench
oracle.password=your_password
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         DocBench CLI                            │
│   [Command Parser] [Config Loader] [Report Generator] [Progress]│
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                    Benchmark Orchestrator                       │
│   [Workload Registry] [Execution Engine] [Metrics Collector]    │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                  Database Adapter Layer (SPI)                   │
│  ┌────────────────┐  ┌─────────────────┐  ┌──────────────────┐ │
│  │ MongoDBAdapter │  │OracleOSONAdapter│  │ [Future Adapters]│ │
│  │  BSON Metrics  │  │  SQL/JSON O(1)  │  │  PostgreSQL, etc │ │
│  └────────────────┘  └─────────────────┘  └──────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Status

### Completed ✅

- **Phase 1: Core Infrastructure**
  - TimeSource, RandomSource utilities with seeded reproducibility
  - OverheadBreakdown record with timing decomposition
  - MetricsCollector with HdrHistogram integration
  - DatabaseAdapter SPI with full interface hierarchy
  - CLI structure with picocli

- **Phase 2: MongoDB/BSON Adapter**
  - MongoDBAdapter with instrumented connection
  - BsonTimingInterceptor for command timing
  - BsonDeserializationTimer for field access measurement
  - Full CRUD operations with overhead breakdown

- **Phase 3: Oracle/OSON Adapter**
  - OracleOsonAdapter using native SQL/JSON (not SODA)
  - JSON_VALUE for O(1) field extraction
  - JSON_TRANSFORM for O(1) updates
  - Connection pooling via Oracle UCP

- **Phase 4: Workloads and Reporting**
  - WorkloadConfig with builder pattern and validation
  - DocumentGenerator with configurable nesting, arrays, and sizing
  - TraverseWorkload for path traversal benchmarks
  - DeserializeWorkload for full document parsing benchmarks
  - Report generators: Console, JSON, CSV, HTML
  - CLI commands: run, compare, report, list, validate
  - BenchmarkExecutor orchestration with warmup phases

- **Benchmark Comparison**
  - 14 comparison tests across document complexities
  - Field position, nesting depth, multi-field tests
  - E-commerce document real-world scenarios
  - Automated results reporting with statistical analysis

## Metrics

### Core Latency Metrics

| Metric | Description |
|--------|-------------|
| `total_latency` | End-to-end operation time |
| `server_execution_time` | DB-reported execution |
| `server_traversal_time` | Document navigation (server) |
| `client_deserialization_time` | Response parsing (client) |
| `serialization_time` | Request preparation |

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
│   ├── mongodb             # MongoDB/BSON implementation
│   └── oracle              # Oracle SQL/JSON implementation
├── document                # Test document generation
├── report                  # Output generation
└── util                    # Utilities (TimeSource, RandomSource)
```

### Test Summary

| Category | Count | Description |
|----------|-------|-------------|
| Unit tests | 238 | Core functionality, workloads, reporting |
| MongoDB integration | 17 | BSON adapter operations |
| Oracle integration | 17 | SQL/JSON OSON operations |
| Benchmark comparison | 14 | BSON vs OSON performance |
| **Total** | **286** | Full test coverage |

### Code Quality

- **Coverage**: 80%+ line, 70%+ branch
- **Mutation Score**: 60%+ (PIT)
- **Java Version**: 21+ (23 recommended)
- **TDD Methodology**: Red-Green-Refactor cycles

## License

MIT License - see [LICENSE](LICENSE) file.

## Contributing

Contributions are welcome! Please ensure all code follows TDD practices and includes comprehensive tests.
