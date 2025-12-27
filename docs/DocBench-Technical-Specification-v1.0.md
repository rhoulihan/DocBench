# DocBench Technical Specification

## Extensible Database Document Performance Benchmarking Framework

**Version:** 1.0.0  
**Date:** December 2025  
**Author:** Senior Java Architect  
**Status:** Draft for Review  
**Target Runtime:** Java 21+ (Virtual Threads Required)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [System Architecture](#3-system-architecture)
4. [Service Provider Interface (SPI)](#4-service-provider-interface-spi)
5. [Workload Definitions](#5-workload-definitions)
6. [Test Document Schema](#6-test-document-schema)
7. [Metrics and Measurement](#7-metrics-and-measurement)
8. [Command-Line Interface](#8-command-line-interface)
9. [Configuration](#9-configuration)
10. [Reporting](#10-reporting)
11. [Initial Adapter Implementations](#11-initial-adapter-implementations)
12. [Testing Strategy](#12-testing-strategy)
13. [Implementation Phases](#13-implementation-phases)
14. [Appendices](#appendices)

---

## 1. Executive Summary

DocBench is a command-line benchmarking utility designed to provide empirical, reproducible measurements of document database performance characteristics with particular emphasis on **operational overhead decomposition**—isolating and measuring the distinct cost components that comprise total request latency beyond raw data access time.

The initial implementation targets MongoDB BSON and Oracle OSON to empirically demonstrate the performance differential in binary JSON traversal strategies:

- **BSON O(n) sequential field-name scanning** at each document level
- **OSON O(1) hash-indexed jump navigation** via precompiled path expressions
- **Client-side deserialization overhead amplification**
- **Scale impact across millions of document operations**

The architecture employs a **Strategy + Plugin** pattern enabling future extension to PostgreSQL JSONB, DynamoDB, Couchbase, and other document-capable platforms without core framework modification.

### 1.1 Key Differentiators

| Feature | YCSB | sysbench | DocBench |
|---------|------|----------|----------|
| Overhead Decomposition | ❌ | ❌ | ✅ |
| Traversal Timing | ❌ | ❌ | ✅ |
| Client Deserialization Metrics | ❌ | ❌ | ✅ |
| Binary Format Comparison | ❌ | ❌ | ✅ |
| Path Depth Analysis | ❌ | ❌ | ✅ |
| Field Position Impact | ❌ | ❌ | ✅ |

---

## 2. Problem Statement

Traditional database benchmarks measure aggregate throughput and latency but fail to decompose **where time is actually spent**. The observation that *"70% of request overhead is not getting the data—it's managing the connection, marshaling the data, and moving it across the TCP/IP stack"* demands instrumentation that isolates these components.

### 2.1 The Binary JSON Traversal Problem

Both MongoDB and Oracle use binary JSON formats optimized for different access patterns. The critical distinction lies in how each format navigates to target fields within nested document structures.

#### 2.1.1 BSON (MongoDB): Length-Prefixed Traversal

BSON is "traversable"—each element includes a length prefix enabling sub-document skipping. However, **within each level**, field location requires sequential field-name scanning until the target match is found.

**Example: Find `order.items[5].product.sku` in a document:**

```
Level 1: Scan field names → "user"... "meta"... "order" ✓
Level 2: Scan field names → "id"... "date"... "items" ✓
Level 3: Skip to array index 5
Level 4: Scan field names → "qty"... "price"... "product" ✓
Level 5: Scan field names → "name"... "sku" ✓
```

**Time complexity: O(n) per level** where n = fields before target at that level.

- ✅ Skips sub-trees efficiently via length prefixes
- ❌ Still scans field names sequentially at each level
- ❌ This happens on server during queries AND on client during deserialization

#### 2.1.2 OSON (Oracle): Hash-Indexed Jump Navigation

OSON compiles path expressions into hash codes at query parse time, then jumps directly to field offsets via dictionary lookup.

**Same path `order.items[5].product.sku`:**

```
→ Hash path segments → Lookup dictionary → Jump to offset → FOUND ✓
```

From Oracle's documentation: *"If you want to retrieve only the field 'name' of the second element of the array entry 'items'... the database will 'jump' directly to the value within the OSON buffer, instead of linearly parsing and scanning."*

**Time complexity: O(1) per level**—hash computation plus offset jump.

- ✅ No field-name comparisons
- ✅ Direct navigation via precomputed offsets
- ✅ Client libraries use same jump navigation for partial access

### 2.2 Scale Impact Analysis

| Scenario | BSON | OSON |
|----------|------|------|
| 20 fields, target at position 15 | 15 name comparisons | 1 hash + jump |
| 5 levels deep × 20 fields each | Up to 100 comparisons | ≤5 hash + jumps |
| 1M documents queried | 1M × O(n) per level | 1M × O(1) per level |
| Client deserialization | Full field scanning | Direct offset access |

### 2.3 The Client-Side Amplification Problem

The traversal cost impacts clients twice:

1. **Server-side**: During query execution and projection
2. **Client-side**: During response deserialization

Every time a MongoDB driver deserializes a document, it scans field names. Oracle's client libraries decode OSON with jump navigation—enabling partial access without full parsing.

**"Traversable" sounds fast until you realize it still means "scannable."**  
**"Indexed" is actually fast because it means "jumpable."**

---

## 3. System Architecture

### 3.1 High-Level Component Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              DocBench CLI                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │   Command    │  │    Config    │  │    Report    │  │    Progress     │  │
│  │   Parser     │  │    Loader    │  │   Generator  │  │    Monitor      │  │
│  │  (picocli)   │  │   (YAML)     │  │  (multiple)  │  │   (async)       │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
└─────────┼─────────────────┼─────────────────┼───────────────────┼───────────┘
          │                 │                 │                   │
          ▼                 ▼                 ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Benchmark Orchestrator                               │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────────────────┐   │
│  │    Workload     │  │    Execution     │  │       Metrics             │   │
│  │   Definition    │  │     Engine       │  │      Collector            │   │
│  │    Registry     │  │  (Virtual        │  │   (Overhead               │   │
│  │                 │  │   Threads)       │  │    Decomposition)         │   │
│  └────────┬────────┘  └────────┬─────────┘  └───────────┬───────────────┘   │
└───────────┼────────────────────┼────────────────────────┼───────────────────┘
            │                    │                        │
            ▼                    ▼                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Database Adapter Layer (SPI)                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                     DatabaseAdapter Interface                            ││
│  │  + getAdapterId(): String                                               ││
│  │  + connect(config): InstrumentedConnection                              ││
│  │  + execute(conn, operation, collector): OperationResult                 ││
│  │  + getOverheadBreakdown(result): OverheadBreakdown                      ││
│  │  + getCapabilities(): Set<Capability>                                   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────────┐ │
│  │  MongoDBAdapter    │  │  OracleOSONAdapter │  │   [Future Adapters]    │ │
│  │                    │  │                    │  │                        │ │
│  │  - BSON Metrics    │  │  - OSON Metrics    │  │  - PostgreSQL JSONB    │ │
│  │  - Sharded/RS      │  │  - JSON Duality    │  │  - DynamoDB            │ │
│  │  - Wire Protocol   │  │  - SODA API        │  │  - Couchbase           │ │
│  │    Instrumentation │  │  - SQL/JSON        │  │  - CosmosDB            │ │
│  └────────────────────┘  └────────────────────┘  └────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Core Design Principles

1. **Test-Driven Development**: All components developed with comprehensive unit and integration test suites using JUnit 5, TestContainers for database instances, and mutation testing (PIT) for coverage verification.

2. **Dependency Injection**: Constructor injection throughout; Guice for wiring with explicit module definitions enabling test double substitution.

3. **Immutability**: All configuration, metrics, and result objects are immutable records or use Builder pattern with `build()` producing unmodifiable instances.

4. **Instrumentation-First Design**: Overhead measurement is not an afterthought—the adapter SPI mandates overhead decomposition hooks.

5. **Reproducibility**: Seeded random generation, deterministic document structures, and statistical validation of results.

6. **Separation of Concerns**: Clean boundaries between orchestration, execution, measurement, and reporting.

### 3.3 Package Structure

```
com.oracle.docbench
├── cli                          # Command-line interface (picocli)
│   ├── DocBenchCommand.java     # Main entry point
│   ├── RunCommand.java          # Execute benchmarks
│   ├── CompareCommand.java      # Compare results
│   └── ReportCommand.java       # Generate reports
├── config                       # Configuration management
│   ├── BenchmarkConfig.java     # Immutable config record
│   ├── ConfigLoader.java        # YAML/JSON parsing
│   └── validation               # Config validators
├── orchestrator                 # Benchmark execution
│   ├── BenchmarkOrchestrator.java
│   ├── ExecutionEngine.java     # Virtual thread management
│   └── WorkloadExecutor.java
├── workload                     # Workload definitions
│   ├── Workload.java            # Base interface
│   ├── WorkloadRegistry.java    # Discovery and registration
│   ├── traversal                # Traversal-specific workloads
│   │   ├── TraverseShallowWorkload.java
│   │   ├── TraverseDeepWorkload.java
│   │   └── TraverseScaleWorkload.java
│   └── deserialization          # Client-side workloads
│       ├── DeserializeFullWorkload.java
│       └── DeserializePartialWorkload.java
├── metrics                      # Measurement and collection
│   ├── MetricsCollector.java
│   ├── OverheadBreakdown.java   # Key decomposition record
│   ├── StatisticalAggregator.java
│   └── TimingInstrumentation.java
├── adapter                      # Database adapter SPI
│   ├── spi
│   │   ├── DatabaseAdapter.java
│   │   ├── InstrumentedConnection.java
│   │   ├── Operation.java
│   │   ├── OperationResult.java
│   │   └── Capability.java
│   ├── mongodb                  # MongoDB implementation
│   │   ├── MongoDBAdapter.java
│   │   ├── BsonTimingInterceptor.java
│   │   └── MongoDBOverheadCalculator.java
│   └── oracle                   # Oracle OSON implementation
│       ├── OracleOSONAdapter.java
│       ├── OsonTimingInterceptor.java
│       └── OracleOverheadCalculator.java
├── document                     # Test document generation
│   ├── DocumentGenerator.java
│   ├── DocumentSchema.java
│   └── templates                # Predefined schemas
├── report                       # Output generation
│   ├── ReportGenerator.java
│   ├── ConsoleReporter.java
│   ├── JsonReporter.java
│   ├── CsvReporter.java
│   └── HtmlReporter.java
└── util                         # Utilities
    ├── TimeSource.java          # Testable time abstraction
    └── RandomSource.java        # Seeded random for reproducibility
```

---

## 4. Service Provider Interface (SPI)

The SPI defines the contract for database platform adapters, ensuring consistent measurement capabilities across all supported databases.

### 4.1 DatabaseAdapter Interface

```java
package com.oracle.docbench.adapter.spi;

import java.util.Set;

/**
 * Core extension point for database platform support.
 * Implementations must be thread-safe and support concurrent execution.
 */
public interface DatabaseAdapter extends AutoCloseable {
    
    /**
     * Unique adapter identifier for registry and reporting.
     * Convention: lowercase, hyphenated (e.g., "mongodb-sharded", "oracle-oson")
     */
    String getAdapterId();
    
    /**
     * Human-readable name for reports.
     */
    String getDisplayName();
    
    /**
     * Platform capabilities determining workload compatibility.
     */
    Set<Capability> getCapabilities();
    
    /**
     * Establish connection with instrumentation hooks enabled.
     * 
     * @param config Connection parameters
     * @return Instrumented connection wrapper
     * @throws ConnectionException if connection fails
     */
    InstrumentedConnection connect(ConnectionConfig config);
    
    /**
     * Execute single operation with full overhead decomposition.
     * 
     * @param conn Active instrumented connection
     * @param operation Operation to execute
     * @param collector Metrics collection target
     * @return Operation result with timing breakdown
     */
    OperationResult execute(
        InstrumentedConnection conn,
        Operation operation,
        MetricsCollector collector
    );
    
    /**
     * Bulk operation support for throughput testing.
     * 
     * @param conn Active instrumented connection
     * @param operations Batch of operations
     * @param collector Metrics collection target
     * @return Aggregated results
     */
    BulkOperationResult executeBulk(
        InstrumentedConnection conn,
        List<Operation> operations,
        MetricsCollector collector
    );
    
    /**
     * Extract platform-specific overhead breakdown from result.
     * This is where BSON vs OSON differences are captured.
     */
    OverheadBreakdown getOverheadBreakdown(OperationResult result);
    
    /**
     * Prepare test collection/table with required indexes.
     */
    void setupTestEnvironment(TestEnvironmentConfig config);
    
    /**
     * Clean up test data.
     */
    void teardownTestEnvironment();
}
```

### 4.2 OverheadBreakdown Record

The central data structure for capturing decomposed timing measurements:

```java
package com.oracle.docbench.metrics;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable record capturing decomposed operation timing.
 * All durations are non-negative; unmeasured components are Duration.ZERO.
 */
public record OverheadBreakdown(
    // Total wall-clock time for operation
    Duration totalLatency,
    
    // Connection lifecycle
    Duration connectionAcquisition,    // Pool checkout or new connection
    Duration connectionRelease,        // Pool return
    
    // Client-side serialization (REQUEST)
    Duration serializationTime,        // Encode to wire format (BSON/OSON)
    
    // Network transit (REQUEST)
    Duration wireTransmitTime,         // Send bytes over network
    
    // Server-side execution
    Duration serverExecutionTime,      // DB-reported total execution
    Duration serverParseTime,          // Query/command parsing
    Duration serverTraversalTime,      // Document field navigation (KEY METRIC)
    Duration serverIndexTime,          // Index lookup time
    Duration serverFetchTime,          // Data retrieval from storage
    
    // Network transit (RESPONSE)
    Duration wireReceiveTime,          // Receive bytes over network
    
    // Client-side deserialization (RESPONSE - KEY METRIC)
    Duration deserializationTime,      // Decode from wire format
    Duration clientTraversalTime,      // Client-side field access in parsed doc
    
    // Platform-specific additional metrics
    Map<String, Duration> platformSpecific
) {
    /**
     * Total overhead = everything except actual data fetch.
     */
    public Duration totalOverhead() {
        return totalLatency.minus(serverFetchTime);
    }
    
    /**
     * Traversal overhead = server + client navigation time.
     * This is the PRIMARY comparison metric for BSON vs OSON.
     */
    public Duration traversalOverhead() {
        return serverTraversalTime.plus(clientTraversalTime);
    }
    
    /**
     * Network overhead = transit + protocol framing.
     */
    public Duration networkOverhead() {
        return wireTransmitTime.plus(wireReceiveTime);
    }
    
    /**
     * Serialization overhead = encode + decode.
     */
    public Duration serializationOverhead() {
        return serializationTime.plus(deserializationTime);
    }
    
    /**
     * Connection overhead = acquire + release.
     */
    public Duration connectionOverhead() {
        return connectionAcquisition.plus(connectionRelease);
    }
    
    /**
     * Percentage of total latency spent in traversal.
     */
    public double traversalPercentage() {
        if (totalLatency.isZero()) return 0.0;
        return (double) traversalOverhead().toNanos() / totalLatency.toNanos() * 100;
    }
}
```

### 4.3 Capability Enumeration

```java
package com.oracle.docbench.adapter.spi;

/**
 * Capabilities that determine workload compatibility.
 * Adapters declare supported capabilities; workloads require specific capabilities.
 */
public enum Capability {
    // Document access patterns
    NESTED_DOCUMENT_ACCESS,       // Deep path queries (a.b.c.d)
    ARRAY_INDEX_ACCESS,           // Array element projection (items[5])
    PARTIAL_DOCUMENT_RETRIEVAL,   // Projection/field selection
    WILDCARD_PATH_ACCESS,         // items[*].sku patterns
    
    // Operations
    BULK_INSERT,                  // Batch document insertion
    BULK_UPDATE,                  // Batch document updates
    BULK_READ,                    // Batch document retrieval
    
    // Topology
    SHARDING,                     // Distributed data partitioning
    REPLICATION,                  // Read replicas
    
    // Indexing
    SECONDARY_INDEXES,            // Non-primary key indexes
    COMPOUND_INDEXES,             // Multi-field indexes
    JSON_PATH_INDEXES,            // Indexes on nested paths
    
    // Transactions
    SINGLE_DOCUMENT_ATOMICITY,    // Atomic single-doc operations
    MULTI_DOCUMENT_TRANSACTIONS,  // ACID across documents
    
    // Instrumentation (CRITICAL for DocBench)
    SERVER_EXECUTION_TIME,        // DB-reported execution timing
    SERVER_TRAVERSAL_TIME,        // Format-specific navigation timing
    EXPLAIN_PLAN,                 // Query plan access
    PROFILING,                    // Operation-level profiling
    CLIENT_TIMING_HOOKS,          // Driver-level instrumentation
    DESERIALIZATION_METRICS       // Client-side decode timing
}
```

### 4.4 Operation Types

```java
package com.oracle.docbench.adapter.spi;

/**
 * Sealed interface for type-safe operation definitions.
 */
public sealed interface Operation permits 
    InsertOperation, 
    ReadOperation, 
    UpdateOperation, 
    DeleteOperation,
    AggregateOperation {
    
    String operationId();
    OperationType type();
}

public record ReadOperation(
    String operationId,
    String documentId,
    List<String> projectionPaths,  // Empty = full document
    ReadPreference readPreference
) implements Operation {
    @Override
    public OperationType type() { return OperationType.READ; }
}

public record InsertOperation(
    String operationId,
    JsonDocument document
) implements Operation {
    @Override
    public OperationType type() { return OperationType.INSERT; }
}

// Additional operation types...
```

### 4.5 InstrumentedConnection

```java
package com.oracle.docbench.adapter.spi;

/**
 * Connection wrapper providing timing hooks at protocol boundaries.
 */
public interface InstrumentedConnection extends AutoCloseable {
    
    /**
     * Underlying platform connection (for adapter-specific operations).
     */
    <T> T unwrap(Class<T> connectionType);
    
    /**
     * Connection state.
     */
    boolean isValid();
    
    /**
     * Register listener for operation timing events.
     */
    void addTimingListener(TimingListener listener);
    
    /**
     * Get accumulated timing metrics since last reset.
     */
    ConnectionTimingMetrics getTimingMetrics();
    
    /**
     * Reset timing accumulators.
     */
    void resetTimingMetrics();
}

/**
 * Callback interface for fine-grained timing capture.
 */
public interface TimingListener {
    void onSerializationStart(String operationId);
    void onSerializationComplete(String operationId, int bytesSerialized);
    void onWireTransmitStart(String operationId);
    void onWireTransmitComplete(String operationId, int bytesSent);
    void onWireReceiveStart(String operationId);
    void onWireReceiveComplete(String operationId, int bytesReceived);
    void onDeserializationStart(String operationId);
    void onDeserializationComplete(String operationId, int fieldsDeserialized);
}
```

---

## 5. Workload Definitions

Each workload isolates specific overhead characteristics. Traversal-focused workloads directly demonstrate BSON vs OSON navigation differences.

### 5.1 Workload Interface

```java
package com.oracle.docbench.workload;

public interface Workload {
    
    /**
     * Unique workload identifier.
     */
    String getWorkloadId();
    
    /**
     * Human-readable description.
     */
    String getDescription();
    
    /**
     * Required adapter capabilities.
     */
    Set<Capability> getRequiredCapabilities();
    
    /**
     * Generate operations for this workload.
     */
    List<Operation> generateOperations(WorkloadConfig config, RandomSource random);
    
    /**
     * Generate test documents for this workload.
     */
    List<JsonDocument> generateDocuments(WorkloadConfig config, RandomSource random);
    
    /**
     * Validate adapter compatibility.
     */
    default boolean isCompatible(DatabaseAdapter adapter) {
        return adapter.getCapabilities().containsAll(getRequiredCapabilities());
    }
}
```

### 5.2 Traversal Overhead Workloads

#### 5.2.1 TRAVERSE_SHALLOW: Single-Level Field Access

Measures field-location overhead at document root level with varying field counts and target positions.

| Parameter | Values | Purpose |
|-----------|--------|---------|
| `fieldCount` | 5, 10, 20, 50, 100 | Total fields at root level |
| `targetPosition` | FIRST, MIDDLE, LAST, RANDOM | Position of target field |
| `fieldValueSize` | 100B, 1KB, 10KB | Size of each field value |
| `iterations` | 1000, 10000, 100000 | Statistical significance |

**Expected Outcome:**
- BSON: Latency grows linearly with target position
- OSON: Latency remains constant regardless of position

```java
@WorkloadDefinition(id = "traverse-shallow")
public class TraverseShallowWorkload implements Workload {
    
    @Override
    public Set<Capability> getRequiredCapabilities() {
        return Set.of(
            Capability.PARTIAL_DOCUMENT_RETRIEVAL,
            Capability.SERVER_EXECUTION_TIME
        );
    }
    
    @Override
    public List<JsonDocument> generateDocuments(WorkloadConfig config, RandomSource random) {
        int fieldCount = config.getInt("fieldCount", 20);
        int targetPosition = config.getEnum("targetPosition", Position.class, Position.LAST)
                                   .toIndex(fieldCount);
        int fieldValueSize = config.getBytes("fieldValueSize", 1024);
        
        return List.of(
            DocumentGenerator.builder()
                .withFieldCount(fieldCount)
                .withTargetFieldAt(targetPosition, "target_field")
                .withFieldValueSize(fieldValueSize)
                .withSeed(config.getSeed())
                .build()
                .generate()
        );
    }
    
    @Override
    public List<Operation> generateOperations(WorkloadConfig config, RandomSource random) {
        int iterations = config.getInt("iterations", 10000);
        
        return IntStream.range(0, iterations)
            .mapToObj(i -> new ReadOperation(
                "traverse-shallow-" + i,
                "doc-1",
                List.of("target_field"),  // Project only target field
                ReadPreference.PRIMARY
            ))
            .toList();
    }
}
```

#### 5.2.2 TRAVERSE_DEEP: Multi-Level Nested Access

**The primary workload for demonstrating O(n) vs O(1) per-level traversal difference.**

| Parameter | Values | Purpose |
|-----------|--------|---------|
| `nestingDepth` | 1, 3, 5, 7, 10 | Levels of nesting |
| `fieldsPerLevel` | 5, 10, 20 | Fields at each level |
| `targetPath` | Configurable | e.g., `order.items[5].product.sku` |
| `includeArrayAccess` | true/false | Include array index navigation |
| `arraySize` | 10, 50, 100 | Elements in arrays |

**Expected Outcome:**
- BSON: O(depth × fieldsPerLevel) comparisons
- OSON: O(depth) hash+jump operations

```java
@WorkloadDefinition(id = "traverse-deep")
public class TraverseDeepWorkload implements Workload {
    
    @Override
    public Set<Capability> getRequiredCapabilities() {
        return Set.of(
            Capability.NESTED_DOCUMENT_ACCESS,
            Capability.ARRAY_INDEX_ACCESS,
            Capability.PARTIAL_DOCUMENT_RETRIEVAL,
            Capability.SERVER_TRAVERSAL_TIME  // Critical for this workload
        );
    }
    
    @Override
    public List<JsonDocument> generateDocuments(WorkloadConfig config, RandomSource random) {
        int depth = config.getInt("nestingDepth", 5);
        int fieldsPerLevel = config.getInt("fieldsPerLevel", 20);
        int arraySize = config.getInt("arraySize", 10);
        String targetPath = config.getString("targetPath", "level1.level2.level3.level4.target");
        
        return List.of(
            DocumentGenerator.builder()
                .withNestingDepth(depth)
                .withFieldsPerLevel(fieldsPerLevel)
                .withArrayAtLevels(config.getIntList("arrayLevels", List.of(2)))
                .withArraySize(arraySize)
                .withTargetPath(targetPath, "TARGET_VALUE")
                .withSeed(config.getSeed())
                .build()
                .generate()
        );
    }
    
    @Override
    public List<Operation> generateOperations(WorkloadConfig config, RandomSource random) {
        String targetPath = config.getString("targetPath", "level1.level2.level3.level4.target");
        int iterations = config.getInt("iterations", 10000);
        
        return IntStream.range(0, iterations)
            .mapToObj(i -> new ReadOperation(
                "traverse-deep-" + i,
                "doc-1",
                List.of(targetPath),
                ReadPreference.PRIMARY
            ))
            .toList();
    }
}
```

#### 5.2.3 TRAVERSE_SCALE: Volume Amplification

Demonstrates how traversal overhead compounds across large document volumes.

| Parameter | Values | Purpose |
|-----------|--------|---------|
| `documentCount` | 1K, 10K, 100K, 1M | Collection size |
| `accessPattern` | SEQUENTIAL, RANDOM, BATCH | Read pattern |
| `batchSize` | 10, 100, 1000 | Docs per batch (if BATCH) |
| `projectionType` | FULL, SINGLE_FIELD, MULTI_FIELD | Return data volume |

**Expected Outcome:**
- Linear amplification of per-document overhead
- Batch operations show amortization differences

### 5.3 Client Deserialization Workloads

#### 5.3.1 DESERIALIZE_FULL: Complete Document Parsing

Measures client-side deserialization overhead when parsing entire documents.

| Parameter | Values | Purpose |
|-----------|--------|---------|
| `documentComplexity` | SIMPLE, MEDIUM, COMPLEX | Field count and nesting |
| `fieldCount` | 10, 50, 200 | Root-level fields |
| `nestingDepth` | 0, 3, 5 | Nesting levels |
| `dataTypes` | STRING, NUMERIC, MIXED, BINARY | Type distribution |

#### 5.3.2 DESERIALIZE_PARTIAL: Projection-Based Parsing

**Key differentiator workload**: Measures whether client libraries can skip unneeded fields.

| Parameter | Values | Purpose |
|-----------|--------|---------|
| `projectionRatio` | 1%, 10%, 25%, 50% | Percentage of fields requested |
| `targetFieldLocations` | ROOT, NESTED, ARRAY | Where projected fields exist |
| `documentSize` | 1KB, 10KB, 100KB, 1MB | Total document size |

**Expected Outcome:**
- OSON: Deserialization time proportional to projection ratio
- BSON: Near-full parsing regardless of projection (driver-dependent)

### 5.4 Workload Configuration Schema

```yaml
# workload-config.yaml
workload: traverse-deep
parameters:
  nestingDepth: 5
  fieldsPerLevel: 20
  targetPath: "order.items[5].product.sku"
  includeArrayAccess: true
  arraySize: 10
  iterations: 10000

execution:
  warmupIterations: 1000
  concurrency: 1          # Single-threaded for latency isolation
  seed: 12345             # Reproducibility
  
measurement:
  capturePercentiles: [50, 90, 95, 99, 99.9]
  captureHistogram: true
  histogramPrecision: 3   # Significant digits
```

---

## 6. Test Document Schema

### 6.1 E-Commerce Order Document

The primary test document models a realistic e-commerce order with controllable complexity parameters:

```json
{
  "_id": "ord_20251226_000001",
  "version": 1,
  "created": "2025-12-26T10:30:00Z",
  "modified": "2025-12-26T10:35:00Z",
  "status": "processing",
  
  // === PADDING FIELDS (configurable count for position testing) ===
  "padding_field_01": "value_with_consistent_size_for_testing_purposes_01",
  "padding_field_02": "value_with_consistent_size_for_testing_purposes_02",
  // ... up to padding_field_N
  
  // === CUSTOMER (2 levels deep) ===
  "customer": {
    "id": "cust_12345",
    "email": "customer@example.com",
    "phone": "+1-555-0123",
    "profile": {
      "tier": "gold",
      "since": "2020-01-15",
      "lifetimeValue": 15420.50,
      "preferences": {
        "notifications": true,
        "currency": "USD",
        "language": "en-US"
      }
    },
    "addresses": [
      {
        "type": "billing",
        "street": "123 Main St",
        "city": "Austin",
        "state": "TX",
        "zip": "78701",
        "country": "US"
      },
      {
        "type": "shipping",
        "street": "456 Oak Ave",
        "city": "Austin", 
        "state": "TX",
        "zip": "78702",
        "country": "US"
      }
    ]
  },
  
  // === ORDER ITEMS (array with nested objects - PRIMARY TEST TARGET) ===
  "items": [
    {
      "lineNumber": 1,
      "quantity": 2,
      "unitPrice": { "amount": 29.99, "currency": "USD" },
      "totalPrice": { "amount": 59.98, "currency": "USD" },
      "product": {
        "sku": "PROD-ABC-123",           // <-- COMMON TARGET FIELD
        "name": "Widget Pro",
        "description": "Professional grade widget",
        "category": {
          "primary": "Electronics",
          "secondary": "Gadgets",
          "tertiary": "Widgets"
        },
        "attributes": {
          "color": "blue",
          "size": "medium",
          "weight": { "value": 0.5, "unit": "kg" }
        }
      },
      "fulfillment": {
        "warehouse": "WH-AUSTIN-01",
        "status": "picked",
        "tracking": null
      }
    }
    // ... additional items (configurable count)
  ],
  
  // === ORDER TOTALS ===
  "totals": {
    "subtotal": { "amount": 299.90, "currency": "USD" },
    "tax": { "amount": 24.74, "currency": "USD" },
    "shipping": { "amount": 0.00, "currency": "USD" },
    "discount": { "amount": 29.99, "currency": "USD" },
    "total": { "amount": 294.65, "currency": "USD" }
  },
  
  // === METADATA ===
  "metadata": {
    "source": "web",
    "campaign": "holiday2025",
    "sessionId": "sess_abc123",
    "userAgent": "Mozilla/5.0...",
    "ipAddress": "192.168.1.100"
  }
}
```

### 6.2 Document Generator Configuration

```java
public class DocumentGeneratorConfig {
    
    // Structure control
    private int paddingFieldCount = 0;        // Fields before target
    private int itemCount = 10;               // Array elements
    private int nestingDepth = 5;             // Maximum nesting
    private int fieldsPerLevel = 15;          // Avg fields at each level
    
    // Target field placement
    private String targetPath = "items[5].product.sku";
    private int targetArrayIndex = 5;
    private FieldPosition targetPosition = FieldPosition.MIDDLE;
    
    // Size control
    private int stringFieldLength = 50;       // Avg string value length
    private int totalTargetSizeKb = 10;       // Approximate doc size
    
    // Reproducibility
    private long seed = System.currentTimeMillis();
    
    // Data type distribution
    private Map<DataType, Double> typeDistribution = Map.of(
        DataType.STRING, 0.40,
        DataType.NUMBER, 0.25,
        DataType.BOOLEAN, 0.10,
        DataType.DATE, 0.10,
        DataType.NESTED_OBJECT, 0.10,
        DataType.ARRAY, 0.05
    );
}
```

### 6.3 Path Complexity Levels

Predefined path configurations for testing different traversal scenarios:

| Level | Path | Depth | Arrays | Fields to Scan (BSON) |
|-------|------|-------|--------|----------------------|
| L1 | `status` | 1 | 0 | ~5 |
| L2 | `customer.email` | 2 | 0 | ~10 |
| L3 | `customer.profile.tier` | 3 | 0 | ~15 |
| L4 | `items[0].product.sku` | 4 | 1 | ~25 |
| L5 | `items[5].product.category.primary` | 5 | 1 | ~35 |
| L6 | `items[9].product.attributes.weight.value` | 6 | 1 | ~50 |

---

## 7. Metrics and Measurement

### 7.1 Core Metrics

#### 7.1.1 Latency Metrics

| Metric | Description | Unit |
|--------|-------------|------|
| `total_latency` | End-to-end operation time | nanoseconds |
| `server_execution_time` | DB-reported execution | nanoseconds |
| `server_traversal_time` | Document navigation (server) | nanoseconds |
| `client_deserialization_time` | Response parsing (client) | nanoseconds |
| `client_traversal_time` | Field access after parsing | nanoseconds |
| `serialization_time` | Request encoding | nanoseconds |
| `connection_acquisition_time` | Pool checkout | nanoseconds |
| `wire_round_trip_time` | Network transit | nanoseconds |

#### 7.1.2 Derived Metrics

| Metric | Formula | Purpose |
|--------|---------|---------|
| `overhead_ratio` | `(total - server_fetch) / total` | Percentage spent in overhead |
| `traversal_ratio` | `(server_trav + client_trav) / total` | Traversal overhead percentage |
| `serialization_ratio` | `(ser + deser) / total` | Encode/decode overhead |
| `efficiency_score` | `server_fetch / total` | Data retrieval efficiency |

#### 7.1.3 Statistical Aggregations

For each metric, capture:

- **Count**: Total operations
- **Mean**: Arithmetic average
- **Median (p50)**: 50th percentile
- **p90, p95, p99, p99.9**: Tail latencies
- **Min/Max**: Range bounds
- **Standard Deviation**: Variance measure
- **Histogram**: Full distribution (HdrHistogram)

### 7.2 MetricsCollector Implementation

```java
public class MetricsCollector {
    
    private final ConcurrentMap<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final TimeSource timeSource;
    
    public MetricsCollector(TimeSource timeSource) {
        this.timeSource = timeSource;
    }
    
    /**
     * Record a timing measurement with nanosecond precision.
     */
    public void recordTiming(String metricName, Duration duration) {
        histograms
            .computeIfAbsent(metricName, k -> createHistogram())
            .recordValue(duration.toNanos());
    }
    
    /**
     * Record complete overhead breakdown from single operation.
     */
    public void recordOverheadBreakdown(OverheadBreakdown breakdown) {
        recordTiming("total_latency", breakdown.totalLatency());
        recordTiming("connection_acquisition", breakdown.connectionAcquisition());
        recordTiming("serialization", breakdown.serializationTime());
        recordTiming("wire_transmit", breakdown.wireTransmitTime());
        recordTiming("server_execution", breakdown.serverExecutionTime());
        recordTiming("server_traversal", breakdown.serverTraversalTime());
        recordTiming("wire_receive", breakdown.wireReceiveTime());
        recordTiming("deserialization", breakdown.deserializationTime());
        recordTiming("client_traversal", breakdown.clientTraversalTime());
        recordTiming("connection_release", breakdown.connectionRelease());
        
        // Derived metrics
        recordTiming("total_traversal", breakdown.traversalOverhead());
        recordTiming("total_overhead", breakdown.totalOverhead());
    }
    
    /**
     * Generate statistical summary for all metrics.
     */
    public MetricsSummary summarize() {
        return new MetricsSummary(
            histograms.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> summarizeHistogram(e.getValue())
                ))
        );
    }
    
    private Histogram createHistogram() {
        // HdrHistogram with 3 significant digits, max value 1 hour
        return new Histogram(TimeUnit.HOURS.toNanos(1), 3);
    }
    
    private HistogramSummary summarizeHistogram(Histogram h) {
        return new HistogramSummary(
            h.getTotalCount(),
            h.getMean(),
            h.getValueAtPercentile(50),
            h.getValueAtPercentile(90),
            h.getValueAtPercentile(95),
            h.getValueAtPercentile(99),
            h.getValueAtPercentile(99.9),
            h.getMinValue(),
            h.getMaxValue(),
            h.getStdDeviation()
        );
    }
}
```

### 7.3 Instrumentation Points

#### 7.3.1 Client-Side Instrumentation

```
┌────────────────────────────────────────────────────────────────────┐
│                        Application Layer                            │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T1] Connection Acquisition                                        │
│       └─> Pool checkout or new connection establishment            │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T2] Request Serialization                                         │
│       └─> Document/query → BSON/OSON encoding                      │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T3] Wire Transmission                                             │
│       └─> Send bytes to server                                      │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
                            ══════════════  (Network)
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T4] Server Execution (from DB profiling/explain)                  │
│       ├─> Parse                                                     │
│       ├─> Plan                                                      │
│       ├─> Traverse/Navigate  ◄── KEY METRIC                        │
│       └─> Fetch/Return                                             │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
                            ══════════════  (Network)
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T5] Wire Reception                                                │
│       └─> Receive bytes from server                                │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T6] Response Deserialization  ◄── KEY METRIC                      │
│       └─> BSON/OSON → Application objects                          │
└─────────────────────────────────┬──────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────┐
│  [T7] Connection Release                                            │
│       └─> Return to pool                                            │
└────────────────────────────────────────────────────────────────────┘
```

#### 7.3.2 MongoDB-Specific Instrumentation

```java
public class MongoDBTimingInterceptor implements CommandListener {
    
    private final MetricsCollector collector;
    private final ConcurrentMap<Integer, Long> startTimes = new ConcurrentHashMap<>();
    
    @Override
    public void commandStarted(CommandStartedEvent event) {
        startTimes.put(event.getRequestId(), System.nanoTime());
    }
    
    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        Long startTime = startTimes.remove(event.getRequestId());
        if (startTime != null) {
            long clientDuration = System.nanoTime() - startTime;
            long serverDuration = event.getElapsedTime(TimeUnit.NANOSECONDS);
            
            collector.recordTiming("mongodb.client_round_trip", 
                Duration.ofNanos(clientDuration));
            collector.recordTiming("mongodb.server_execution", 
                Duration.ofNanos(serverDuration));
            collector.recordTiming("mongodb.overhead", 
                Duration.ofNanos(clientDuration - serverDuration));
        }
    }
}
```

#### 7.3.3 Oracle-Specific Instrumentation

```java
public class OracleOSONTimingInterceptor {
    
    private final MetricsCollector collector;
    
    /**
     * Wrap SODA operations with timing capture.
     */
    public OracleDocument executeWithTiming(
            OracleCollection collection, 
            String key,
            List<String> projectionPaths) {
        
        long t0 = System.nanoTime();
        
        // Build operation with projection
        OracleOperationBuilder builder = collection.find().key(key);
        if (!projectionPaths.isEmpty()) {
            // OSON projection - enables jump navigation
            builder.project(buildProjection(projectionPaths));
        }
        
        long t1 = System.nanoTime();
        OracleDocument result = builder.getOne();
        long t2 = System.nanoTime();
        
        // Access projected field to trigger client-side navigation
        if (result != null && !projectionPaths.isEmpty()) {
            JsonValue value = navigateToPath(result, projectionPaths.get(0));
            long t3 = System.nanoTime();
            
            collector.recordTiming("oracle.query_build", Duration.ofNanos(t1 - t0));
            collector.recordTiming("oracle.server_execution", Duration.ofNanos(t2 - t1));
            collector.recordTiming("oracle.client_navigation", Duration.ofNanos(t3 - t2));
        }
        
        return result;
    }
}
```

---

## 8. Command-Line Interface

### 8.1 Command Structure

```
docbench <command> [options]

Commands:
  run         Execute benchmark workload(s)
  compare     Compare results across adapters/runs
  report      Generate formatted report from results
  list        List available workloads/adapters
  validate    Validate configuration file
  version     Show version information

Global Options:
  -c, --config <file>     Configuration file (YAML/JSON)
  -v, --verbose           Increase output verbosity
  -q, --quiet             Suppress non-essential output
  --no-color              Disable colored output
  --log-level <level>     Set log level (DEBUG, INFO, WARN, ERROR)
```

### 8.2 Run Command

```
docbench run [options]

Execute benchmark workloads against configured database adapters.

Options:
  -w, --workload <id>         Workload to execute (required, or use --all)
  -a, --adapter <id>          Database adapter (required, repeatable)
  --all-workloads             Run all compatible workloads
  
  -i, --iterations <n>        Operation count (default: 10000)
  --warmup <n>                Warmup iterations (default: 1000)
  --concurrency <n>           Concurrent threads (default: 1)
  --duration <time>           Run for duration instead of iterations
  
  -o, --output <dir>          Output directory for results
  -f, --format <fmt>          Output format: json, csv, console (repeatable)
  --tag <key=value>           Add metadata tag to results (repeatable)
  
  --seed <n>                  Random seed for reproducibility
  --dry-run                   Validate config without executing

Connection Options:
  --mongodb-uri <uri>         MongoDB connection string
  --oracle-jdbc <url>         Oracle JDBC URL
  --oracle-user <user>        Oracle username
  --oracle-password <pass>    Oracle password (or use env var)

Examples:
  # Compare deep traversal on MongoDB and Oracle
  docbench run -w traverse-deep \
    -a mongodb -a oracle-oson \
    --mongodb-uri "mongodb://localhost:27017/bench" \
    --oracle-jdbc "jdbc:oracle:thin:@localhost:1521/FREEPDB1" \
    -i 50000 -o ./results
  
  # Run all traversal workloads
  docbench run --all-workloads \
    -a mongodb -a oracle-oson \
    --tag environment=dev --tag run=baseline
```

### 8.3 Compare Command

```
docbench compare [options] <result-file>...

Compare benchmark results across multiple runs or adapters.

Options:
  --baseline <file>       Baseline result for comparison
  --metric <name>         Metrics to compare (repeatable, default: all)
  --format <fmt>          Output format: table, json, csv, markdown
  --sort <metric>         Sort by metric (default: adapter)
  --threshold <pct>       Highlight differences above threshold %

Examples:
  # Compare MongoDB vs Oracle results
  docbench compare \
    results/mongodb-traverse-deep.json \
    results/oracle-traverse-deep.json \
    --metric total_latency --metric traversal_overhead \
    --format markdown
  
  # Regression check against baseline
  docbench compare \
    --baseline results/baseline.json \
    results/current.json \
    --threshold 10
```

### 8.4 Report Command

```
docbench report [options] <result-file>...

Generate formatted reports from benchmark results.

Options:
  -f, --format <fmt>      Report format: html, pdf, markdown, json
  -o, --output <file>     Output file (default: stdout for markdown/json)
  --template <file>       Custom report template
  --include-charts        Generate embedded charts (html/pdf only)
  --include-raw           Include raw data tables
  --title <title>         Report title

Examples:
  # Generate HTML report with charts
  docbench report results/*.json \
    -f html -o benchmark-report.html \
    --include-charts \
    --title "BSON vs OSON Traversal Performance"
```

### 8.5 List Command

```
docbench list [resource-type]

List available resources.

Resource Types:
  workloads     Available workload definitions
  adapters      Registered database adapters
  metrics       Available metrics
  all           All resources (default)

Options:
  --format <fmt>    Output format: table, json
  --verbose         Show detailed descriptions

Examples:
  docbench list workloads --verbose
  docbench list adapters --format json
```

---

## 9. Configuration

### 9.1 Configuration File Schema

```yaml
# docbench-config.yaml
version: "1.0"

# Global settings
global:
  outputDirectory: "./results"
  seed: 12345                    # Reproducibility
  logLevel: INFO
  
# Database connections
connections:
  mongodb:
    uri: "mongodb://localhost:27017/docbench"
    options:
      maxPoolSize: 10
      minPoolSize: 2
      connectTimeoutMs: 5000
      serverSelectionTimeoutMs: 5000
      
  oracle:
    jdbcUrl: "jdbc:oracle:thin:@localhost:1521/FREEPDB1"
    username: "docbench"
    password: "${ORACLE_PASSWORD}"  # Environment variable reference
    options:
      initialPoolSize: 2
      maxPoolSize: 10
      connectionWaitTimeout: 5

# Workload definitions
workloads:
  traverse-deep:
    enabled: true
    parameters:
      nestingDepth: 5
      fieldsPerLevel: 20
      targetPath: "order.items[5].product.sku"
      arraySize: 10
    execution:
      iterations: 50000
      warmupIterations: 5000
      concurrency: 1
      
  traverse-shallow:
    enabled: true
    parameters:
      fieldCount: [5, 10, 20, 50, 100]  # Parameter sweep
      targetPosition: LAST
    execution:
      iterations: 10000
      warmupIterations: 1000

  traverse-scale:
    enabled: true
    parameters:
      documentCount: 100000
      accessPattern: RANDOM
      projectionType: SINGLE_FIELD
    execution:
      iterations: 10000

# Adapter-specific settings
adapters:
  mongodb:
    enabled: true
    collectionName: "benchmark_docs"
    writeConcern: "majority"
    readPreference: "primary"
    
  oracle-oson:
    enabled: true
    collectionName: "BENCHMARK_DOCS"
    useOSON: true
    enableTraversalMetrics: true

# Measurement settings
measurement:
  percentiles: [50, 90, 95, 99, 99.9]
  histogramPrecision: 3
  captureRawTimings: false        # Memory intensive for large runs
  outlierDetection:
    enabled: true
    method: "IQR"
    threshold: 3.0

# Report settings
reporting:
  formats: ["json", "markdown", "html"]
  includeSystemInfo: true
  includeConfiguration: true
  chartOptions:
    type: "bar"
    showPercentiles: true
```

### 9.2 Environment Variable Substitution

Configuration values can reference environment variables:

```yaml
connections:
  oracle:
    password: "${ORACLE_PASSWORD}"           # Required
    username: "${ORACLE_USER:-docbench}"     # With default
    jdbcUrl: "${ORACLE_JDBC_URL}"
```

### 9.3 Configuration Validation

```java
public class ConfigValidator {
    
    public ValidationResult validate(BenchmarkConfig config) {
        List<ValidationError> errors = new ArrayList<>();
        
        // Required fields
        if (config.connections().isEmpty()) {
            errors.add(new ValidationError("connections", "At least one connection required"));
        }
        
        // Workload-adapter compatibility
        for (WorkloadConfig workload : config.workloads()) {
            for (String adapterId : config.enabledAdapters()) {
                DatabaseAdapter adapter = adapterRegistry.get(adapterId);
                if (!workload.isCompatibleWith(adapter)) {
                    errors.add(new ValidationError(
                        "workloads." + workload.id(),
                        "Incompatible with adapter: " + adapterId + 
                        ". Missing capabilities: " + workload.missingCapabilities(adapter)
                    ));
                }
            }
        }
        
        // Parameter validation
        for (WorkloadConfig workload : config.workloads()) {
            errors.addAll(workload.validateParameters());
        }
        
        return new ValidationResult(errors);
    }
}
```

---

## 10. Reporting

### 10.1 Report Structure

```
DocBench Benchmark Report
=========================

## Executive Summary
- Total workloads: 3
- Total adapters: 2
- Total operations: 150,000
- Total duration: 00:12:34

## Key Findings
1. OSON traversal overhead 87% lower than BSON at depth 5
2. Client deserialization shows 3.2x improvement with OSON projection
3. Overhead ratio: MongoDB 68%, Oracle 31%

## Detailed Results

### Workload: traverse-deep
[Comparison tables and charts]

### Workload: traverse-shallow  
[Comparison tables and charts]

## Appendix
- System configuration
- Full parameter settings
- Raw data tables
```

### 10.2 Comparison Table Format

```
┌────────────────────────┬─────────────────┬─────────────────┬────────────┐
│ Metric                 │ MongoDB (BSON)  │ Oracle (OSON)   │ Δ%         │
├────────────────────────┼─────────────────┼─────────────────┼────────────┤
│ Total Latency (p50)    │ 1,245 μs        │ 423 μs          │ -66.0%     │
│ Total Latency (p99)    │ 3,891 μs        │ 892 μs          │ -77.1%     │
│ Traversal Time (p50)   │ 847 μs          │ 112 μs          │ -86.8%     │
│ Deserialization (p50)  │ 234 μs          │ 45 μs           │ -80.8%     │
│ Overhead Ratio         │ 68.0%           │ 31.2%           │ -36.8pp    │
│ Throughput (ops/sec)   │ 8,032           │ 23,641          │ +194.4%    │
└────────────────────────┴─────────────────┴─────────────────┴────────────┘
```

### 10.3 JSON Report Schema

```json
{
  "reportVersion": "1.0",
  "generatedAt": "2025-12-26T15:30:00Z",
  "docbenchVersion": "1.0.0",
  
  "summary": {
    "totalWorkloads": 3,
    "totalAdapters": 2,
    "totalOperations": 150000,
    "totalDurationMs": 754000
  },
  
  "systemInfo": {
    "os": "Linux 5.15.0",
    "javaVersion": "21.0.1",
    "availableProcessors": 8,
    "maxMemoryMb": 8192
  },
  
  "results": [
    {
      "workloadId": "traverse-deep",
      "parameters": {
        "nestingDepth": 5,
        "fieldsPerLevel": 20,
        "targetPath": "order.items[5].product.sku"
      },
      "adapterResults": [
        {
          "adapterId": "mongodb",
          "displayName": "MongoDB 7.0 (BSON)",
          "metrics": {
            "total_latency": {
              "count": 50000,
              "mean": 1312.45,
              "p50": 1245.0,
              "p90": 1892.0,
              "p95": 2341.0,
              "p99": 3891.0,
              "p999": 5234.0,
              "min": 423.0,
              "max": 12453.0,
              "stdDev": 567.23,
              "unit": "microseconds"
            },
            "server_traversal_time": { /* ... */ },
            "client_deserialization_time": { /* ... */ },
            "traversal_overhead": { /* ... */ }
          }
        },
        {
          "adapterId": "oracle-oson",
          "displayName": "Oracle 23ai (OSON)",
          "metrics": { /* ... */ }
        }
      ],
      "comparison": {
        "baseline": "mongodb",
        "metrics": {
          "total_latency_p50": {
            "baseline": 1245.0,
            "comparison": 423.0,
            "absoluteDelta": -822.0,
            "percentDelta": -66.0
          }
        }
      }
    }
  ]
}
```

---

## 11. Initial Adapter Implementations

### 11.1 MongoDB Adapter

#### 11.1.1 Architecture

```java
@AdapterDefinition(id = "mongodb", displayName = "MongoDB")
public class MongoDBAdapter implements DatabaseAdapter {
    
    private final MongoClient client;
    private final MongoDBTimingInterceptor timingInterceptor;
    private final BsonDeserializationTimer deserializationTimer;
    
    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(
            Capability.NESTED_DOCUMENT_ACCESS,
            Capability.ARRAY_INDEX_ACCESS,
            Capability.PARTIAL_DOCUMENT_RETRIEVAL,
            Capability.BULK_INSERT,
            Capability.BULK_READ,
            Capability.SHARDING,
            Capability.SECONDARY_INDEXES,
            Capability.SERVER_EXECUTION_TIME,
            Capability.EXPLAIN_PLAN,
            Capability.CLIENT_TIMING_HOOKS
            // Note: No SERVER_TRAVERSAL_TIME - BSON doesn't expose this
        );
    }
    
    @Override
    public InstrumentedConnection connect(ConnectionConfig config) {
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(config.uri()))
            .addCommandListener(timingInterceptor)
            .codecRegistry(createInstrumentedCodecRegistry())
            .build();
            
        return new MongoDBInstrumentedConnection(
            MongoClients.create(settings),
            config.database(),
            deserializationTimer
        );
    }
    
    @Override
    public OperationResult execute(
            InstrumentedConnection conn,
            Operation operation,
            MetricsCollector collector) {
        
        MongoDBInstrumentedConnection mongoConn = (MongoDBInstrumentedConnection) conn;
        
        return switch (operation) {
            case ReadOperation read -> executeRead(mongoConn, read, collector);
            case InsertOperation insert -> executeInsert(mongoConn, insert, collector);
            default -> throw new UnsupportedOperationException();
        };
    }
    
    private OperationResult executeRead(
            MongoDBInstrumentedConnection conn,
            ReadOperation operation,
            MetricsCollector collector) {
        
        long t0 = System.nanoTime();
        
        MongoCollection<Document> collection = conn.getCollection();
        FindIterable<Document> find = collection.find(
            Filters.eq("_id", operation.documentId())
        );
        
        // Apply projection if specified
        if (!operation.projectionPaths().isEmpty()) {
            find.projection(buildProjection(operation.projectionPaths()));
        }
        
        long t1 = System.nanoTime();
        
        // Execute with explain to get server timing
        Document explainResult = collection.find(
            Filters.eq("_id", operation.documentId())
        ).explain(ExplainVerbosity.EXECUTION_STATS);
        
        long t2 = System.nanoTime();
        
        // Actual fetch
        Document result = find.first();
        
        long t3 = System.nanoTime();
        
        // Force client-side field access to measure traversal
        if (result != null && !operation.projectionPaths().isEmpty()) {
            for (String path : operation.projectionPaths()) {
                navigateToPath(result, path);
            }
        }
        
        long t4 = System.nanoTime();
        
        // Extract server execution stats
        Document execStats = explainResult.get("executionStats", Document.class);
        long serverExecNanos = execStats.getInteger("executionTimeMillisEstimate", 0) * 1_000_000L;
        
        return new MongoDBOperationResult(
            operation.operationId(),
            result,
            Duration.ofNanos(t4 - t0),           // total
            Duration.ofNanos(t1 - t0),           // build
            Duration.ofNanos(serverExecNanos),   // server exec
            Duration.ofNanos(t3 - t2),           // fetch
            Duration.ofNanos(t4 - t3),           // client traversal
            conn.getDeserializationTime()        // from interceptor
        );
    }
}
```

#### 11.1.2 BSON Deserialization Instrumentation

```java
/**
 * Custom codec that instruments BSON deserialization.
 * Tracks time spent scanning field names during decode.
 */
public class InstrumentedDocumentCodec implements Codec<Document> {
    
    private final DocumentCodec delegate;
    private final ThreadLocal<DeserializationMetrics> metrics = new ThreadLocal<>();
    
    @Override
    public Document decode(BsonReader reader, DecoderContext context) {
        metrics.set(new DeserializationMetrics());
        
        long startTime = System.nanoTime();
        Document result = decodeWithInstrumentation(reader, context);
        long endTime = System.nanoTime();
        
        DeserializationMetrics m = metrics.get();
        m.totalTimeNanos = endTime - startTime;
        
        return result;
    }
    
    private Document decodeWithInstrumentation(BsonReader reader, DecoderContext context) {
        Document document = new Document();
        DeserializationMetrics m = metrics.get();
        
        reader.readStartDocument();
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            long fieldStart = System.nanoTime();
            String fieldName = reader.readName();  // <-- FIELD NAME SCAN
            m.fieldNameReadTimeNanos += System.nanoTime() - fieldStart;
            m.fieldCount++;
            
            Object value = readValue(reader, context);
            document.put(fieldName, value);
        }
        reader.readEndDocument();
        
        return document;
    }
    
    public DeserializationMetrics getLastMetrics() {
        return metrics.get();
    }
    
    public static class DeserializationMetrics {
        public long totalTimeNanos;
        public long fieldNameReadTimeNanos;  // Time scanning field names
        public int fieldCount;
        public int nestedDocumentCount;
    }
}
```

### 11.2 Oracle OSON Adapter

#### 11.2.1 Architecture

```java
@AdapterDefinition(id = "oracle-oson", displayName = "Oracle OSON")
public class OracleOSONAdapter implements DatabaseAdapter {
    
    private final OracleDataSource dataSource;
    private final boolean useOSON;
    
    @Override
    public Set<Capability> getCapabilities() {
        return Set.of(
            Capability.NESTED_DOCUMENT_ACCESS,
            Capability.ARRAY_INDEX_ACCESS,
            Capability.PARTIAL_DOCUMENT_RETRIEVAL,
            Capability.BULK_INSERT,
            Capability.BULK_READ,
            Capability.SECONDARY_INDEXES,
            Capability.JSON_PATH_INDEXES,
            Capability.MULTI_DOCUMENT_TRANSACTIONS,
            Capability.SERVER_EXECUTION_TIME,
            Capability.SERVER_TRAVERSAL_TIME,      // OSON provides this!
            Capability.EXPLAIN_PLAN,
            Capability.CLIENT_TIMING_HOOKS,
            Capability.DESERIALIZATION_METRICS
        );
    }
    
    @Override
    public InstrumentedConnection connect(ConnectionConfig config) {
        OracleConnection conn = dataSource.getConnection();
        
        // Enable OSON format
        if (useOSON) {
            conn.setJdbcJsonDataFormat(JsonDataFormat.OSON);
        }
        
        return new OracleInstrumentedConnection(conn);
    }
    
    @Override
    public OperationResult execute(
            InstrumentedConnection conn,
            Operation operation,
            MetricsCollector collector) {
        
        OracleInstrumentedConnection oracleConn = (OracleInstrumentedConnection) conn;
        
        return switch (operation) {
            case ReadOperation read -> executeRead(oracleConn, read, collector);
            case InsertOperation insert -> executeInsert(oracleConn, insert, collector);
            default -> throw new UnsupportedOperationException();
        };
    }
    
    private OperationResult executeRead(
            OracleInstrumentedConnection conn,
            ReadOperation operation,
            MetricsCollector collector) {
        
        long t0 = System.nanoTime();
        
        // Build SQL/JSON query with projection
        String sql = buildProjectionQuery(operation);
        
        long t1 = System.nanoTime();
        
        try (PreparedStatement stmt = conn.unwrap().prepareStatement(sql)) {
            stmt.setString(1, operation.documentId());
            
            // Enable statement timing
            ((OraclePreparedStatement) stmt).setQueryTimeout(30);
            
            long t2 = System.nanoTime();
            
            try (ResultSet rs = stmt.executeQuery()) {
                long t3 = System.nanoTime();
                
                OracleJsonValue result = null;
                if (rs.next()) {
                    // Get OSON value - uses jump navigation internally
                    result = rs.getObject(1, OracleJsonValue.class);
                }
                
                long t4 = System.nanoTime();
                
                // Access projected fields to measure client navigation
                if (result != null && !operation.projectionPaths().isEmpty()) {
                    for (String path : operation.projectionPaths()) {
                        navigateOsonPath(result, path);
                    }
                }
                
                long t5 = System.nanoTime();
                
                // Get server execution statistics
                OracleStatement oraStmt = (OracleStatement) stmt;
                long serverExecMicros = oraStmt.getServerCpuTime();
                
                return new OracleOperationResult(
                    operation.operationId(),
                    result,
                    Duration.ofNanos(t5 - t0),           // total
                    Duration.ofNanos(t1 - t0),           // build
                    Duration.ofMicros(serverExecMicros), // server exec
                    Duration.ofNanos(t3 - t2),           // network + parse
                    Duration.ofNanos(t4 - t3),           // deserialize
                    Duration.ofNanos(t5 - t4)            // client traversal
                );
            }
        }
    }
    
    /**
     * Build SQL/JSON query with efficient projection.
     * Uses JSON_VALUE for scalar paths, JSON_QUERY for object/array paths.
     */
    private String buildProjectionQuery(ReadOperation operation) {
        if (operation.projectionPaths().isEmpty()) {
            // Full document retrieval
            return """
                SELECT data FROM benchmark_docs 
                WHERE JSON_VALUE(data, '$._id') = ?
                """;
        }
        
        // Projected retrieval - enables OSON jump navigation
        StringBuilder sql = new StringBuilder("SELECT JSON_OBJECT(");
        
        for (int i = 0; i < operation.projectionPaths().size(); i++) {
            if (i > 0) sql.append(", ");
            String path = operation.projectionPaths().get(i);
            String jsonPath = convertToJsonPath(path);
            sql.append("'").append(path).append("' VALUE JSON_VALUE(data, '")
               .append(jsonPath).append("')");
        }
        
        sql.append(" RETURNING JSON) FROM benchmark_docs WHERE JSON_VALUE(data, '$._id') = ?");
        
        return sql.toString();
    }
    
    /**
     * Navigate OSON document using hash-indexed jump.
     * This is O(1) per level unlike BSON's O(n) scan.
     */
    private OracleJsonValue navigateOsonPath(OracleJsonValue root, String path) {
        String[] segments = path.split("\\.");
        OracleJsonValue current = root;
        
        for (String segment : segments) {
            if (current == null) break;
            
            // Array access
            if (segment.contains("[")) {
                String fieldName = segment.substring(0, segment.indexOf('['));
                int index = Integer.parseInt(
                    segment.substring(segment.indexOf('[') + 1, segment.indexOf(']'))
                );
                
                current = ((OracleJsonObject) current).get(fieldName);
                if (current != null && current instanceof OracleJsonArray array) {
                    current = array.get(index);
                }
            } else {
                // Direct field access - OSON hash lookup
                current = ((OracleJsonObject) current).get(segment);
            }
        }
        
        return current;
    }
}
```

#### 11.2.2 SODA API Alternative

```java
/**
 * Alternative implementation using Oracle SODA API for document operations.
 * Provides simpler document-oriented interface while still using OSON internally.
 */
public class OracleSODAAdapter implements DatabaseAdapter {
    
    private OperationResult executeReadSODA(
            OracleInstrumentedConnection conn,
            ReadOperation operation,
            MetricsCollector collector) {
        
        OracleDatabase db = conn.getSODADatabase();
        OracleCollection collection = db.openCollection("BENCHMARK_DOCS");
        
        long t0 = System.nanoTime();
        
        OracleOperationBuilder builder = collection.find()
            .key(operation.documentId());
        
        // Apply projection for partial document retrieval
        if (!operation.projectionPaths().isEmpty()) {
            // SODA projection with OSON - enables hash-indexed access
            builder.project(createProjectionSpec(operation.projectionPaths()));
        }
        
        long t1 = System.nanoTime();
        
        OracleDocument result = builder.getOne();
        
        long t2 = System.nanoTime();
        
        // Measure client-side access
        if (result != null) {
            JsonValue content = result.getContentAs(JsonValue.class);
            // Access triggers OSON navigation
            for (String path : operation.projectionPaths()) {
                navigatePath(content, path);
            }
        }
        
        long t3 = System.nanoTime();
        
        return new OracleOperationResult(
            operation.operationId(),
            result,
            Duration.ofNanos(t3 - t0),
            Duration.ofNanos(t1 - t0),
            getServerExecutionTime(collection),
            Duration.ofNanos(t2 - t1),
            Duration.ofNanos(t3 - t2)
        );
    }
}
```

---

## 12. Testing Strategy

### 12.1 Test Categories

| Category | Scope | Tools | Purpose |
|----------|-------|-------|---------|
| Unit | Individual classes | JUnit 5, Mockito | Logic correctness |
| Integration | Adapter + Real DB | TestContainers | Database interaction |
| Contract | SPI compliance | JUnit 5 | Adapter conformance |
| Performance | Timing accuracy | JMH | Measurement validation |
| End-to-End | Full workflow | JUnit 5 | System verification |

### 12.2 Unit Test Requirements

- **Minimum coverage**: 80% line, 70% branch
- **Mutation testing**: PIT with >60% mutation score
- **Test isolation**: No shared state, no external dependencies

```java
@ExtendWith(MockitoExtension.class)
class MetricsCollectorTest {
    
    @Mock
    private TimeSource timeSource;
    
    private MetricsCollector collector;
    
    @BeforeEach
    void setup() {
        collector = new MetricsCollector(timeSource);
    }
    
    @Test
    void recordTiming_shouldAccumulateValues() {
        collector.recordTiming("test_metric", Duration.ofMicros(100));
        collector.recordTiming("test_metric", Duration.ofMicros(200));
        collector.recordTiming("test_metric", Duration.ofMicros(150));
        
        MetricsSummary summary = collector.summarize();
        HistogramSummary stats = summary.get("test_metric");
        
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.mean()).isCloseTo(150.0, within(0.1));
        assertThat(stats.p50()).isEqualTo(150_000); // nanoseconds
    }
    
    @Test
    void recordOverheadBreakdown_shouldDecomposeAllComponents() {
        OverheadBreakdown breakdown = new OverheadBreakdown(
            Duration.ofMicros(1000),  // total
            Duration.ofMicros(50),    // conn acquisition
            Duration.ofMicros(100),   // serialization
            Duration.ofMicros(75),    // wire transmit
            Duration.ofMicros(400),   // server execution
            Duration.ofMicros(200),   // server traversal
            Duration.ofMicros(75),    // wire receive
            Duration.ofMicros(80),    // deserialization
            Duration.ofMicros(20),    // conn release
            Map.of()
        );
        
        collector.recordOverheadBreakdown(breakdown);
        MetricsSummary summary = collector.summarize();
        
        assertThat(summary.get("total_latency").mean()).isCloseTo(1000_000.0, within(1.0));
        assertThat(summary.get("server_traversal").mean()).isCloseTo(200_000.0, within(1.0));
        assertThat(summary.get("total_traversal").mean())
            .isCloseTo(220_000.0, within(1.0)); // server + client
    }
}
```

### 12.3 Integration Tests with TestContainers

```java
@Testcontainers
@IntegrationTest
class MongoDBAdapterIntegrationTest {
    
    @Container
    static MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");
    
    private MongoDBAdapter adapter;
    private InstrumentedConnection connection;
    
    @BeforeEach
    void setup() {
        adapter = new MongoDBAdapter();
        connection = adapter.connect(new ConnectionConfig(
            mongodb.getReplicaSetUrl("docbench")
        ));
        
        // Insert test document
        adapter.setupTestEnvironment(new TestEnvironmentConfig());
    }
    
    @AfterEach
    void teardown() {
        adapter.teardownTestEnvironment();
        connection.close();
    }
    
    @Test
    void execute_readWithProjection_shouldMeasureTraversalOverhead() {
        // Insert document with known structure
        InsertOperation insert = new InsertOperation(
            "insert-1",
            DocumentGenerator.withNesting(5).withFieldsPerLevel(20).generate()
        );
        adapter.execute(connection, insert, new MetricsCollector());
        
        // Read with deep projection
        ReadOperation read = new ReadOperation(
            "read-1",
            "doc-1",
            List.of("level1.level2.level3.level4.target"),
            ReadPreference.PRIMARY
        );
        
        MetricsCollector collector = new MetricsCollector();
        OperationResult result = adapter.execute(connection, read, collector);
        
        OverheadBreakdown breakdown = adapter.getOverheadBreakdown(result);
        
        assertThat(breakdown.totalLatency()).isPositive();
        assertThat(breakdown.serverExecutionTime()).isPositive();
        // Client traversal should be measurable for BSON
        assertThat(breakdown.clientTraversalTime()).isNotNegative();
    }
    
    @Test
    void execute_traversalOverhead_shouldScaleWithFieldPosition() {
        // Test that BSON traversal time increases with field position
        List<Duration> traversalTimes = new ArrayList<>();
        
        for (int position : List.of(1, 10, 20, 50, 100)) {
            JsonDocument doc = DocumentGenerator.builder()
                .withFieldCount(100)
                .withTargetFieldAt(position, "target")
                .build()
                .generate();
            
            adapter.execute(connection, new InsertOperation("insert-" + position, doc), 
                new MetricsCollector());
            
            ReadOperation read = new ReadOperation(
                "read-" + position,
                doc.getId(),
                List.of("target"),
                ReadPreference.PRIMARY
            );
            
            // Average over multiple iterations
            Duration avgTraversal = measureAverageTraversal(read, 100);
            traversalTimes.add(avgTraversal);
        }
        
        // Verify traversal time increases (BSON characteristic)
        for (int i = 1; i < traversalTimes.size(); i++) {
            assertThat(traversalTimes.get(i))
                .isGreaterThan(traversalTimes.get(i - 1));
        }
    }
}
```

### 12.4 Contract Tests for Adapter SPI

```java
@ParameterizedTest
@MethodSource("allAdapters")
void adapterContract_execute_shouldReturnValidOverheadBreakdown(DatabaseAdapter adapter) {
    // Given
    InstrumentedConnection conn = adapter.connect(testConfig);
    ReadOperation operation = createStandardReadOperation();
    MetricsCollector collector = new MetricsCollector();
    
    // When
    OperationResult result = adapter.execute(conn, operation, collector);
    OverheadBreakdown breakdown = adapter.getOverheadBreakdown(result);
    
    // Then - Contract requirements
    assertThat(breakdown.totalLatency()).isPositive();
    assertThat(breakdown.totalLatency())
        .isGreaterThanOrEqualTo(breakdown.serverExecutionTime());
    
    // All components should be non-negative
    assertThat(breakdown.connectionAcquisition()).isNotNegative();
    assertThat(breakdown.serializationTime()).isNotNegative();
    assertThat(breakdown.wireTransmitTime()).isNotNegative();
    assertThat(breakdown.serverExecutionTime()).isNotNegative();
    assertThat(breakdown.wireReceiveTime()).isNotNegative();
    assertThat(breakdown.deserializationTime()).isNotNegative();
    assertThat(breakdown.connectionRelease()).isNotNegative();
    
    // Sum of components should approximate total (within tolerance for unmeasured gaps)
    Duration sumOfComponents = breakdown.connectionAcquisition()
        .plus(breakdown.serializationTime())
        .plus(breakdown.wireTransmitTime())
        .plus(breakdown.serverExecutionTime())
        .plus(breakdown.wireReceiveTime())
        .plus(breakdown.deserializationTime())
        .plus(breakdown.connectionRelease());
    
    assertThat(sumOfComponents)
        .isLessThanOrEqualTo(breakdown.totalLatency().multipliedBy(110).dividedBy(100)); // 10% tolerance
}

static Stream<DatabaseAdapter> allAdapters() {
    return Stream.of(
        new MongoDBAdapter(),
        new OracleOSONAdapter()
    );
}
```

### 12.5 Performance Test Validation

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class TimingInstrumentationBenchmark {
    
    @Benchmark
    public long systemNanoTime() {
        return System.nanoTime();
    }
    
    @Benchmark
    public long instrumentedTiming(TimingState state) {
        long start = System.nanoTime();
        state.collector.recordTiming("test", Duration.ofNanos(100));
        return System.nanoTime() - start;
    }
    
    /**
     * Verify instrumentation overhead is <1% of typical operation latency.
     * Typical operation: ~1ms = 1,000,000ns
     * Max acceptable overhead: 10,000ns
     */
    @Test
    void instrumentationOverhead_shouldBeLessThan1Percent() {
        Options opt = new OptionsBuilder()
            .include(TimingInstrumentationBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(3)
            .measurementIterations(5)
            .build();
        
        Collection<RunResult> results = new Runner(opt).run();
        
        double nanoTimeOverhead = getResult(results, "systemNanoTime");
        double instrumentedOverhead = getResult(results, "instrumentedTiming");
        
        assertThat(instrumentedOverhead - nanoTimeOverhead)
            .isLessThan(10_000); // 10μs max overhead
    }
}
```

---

## 13. Implementation Phases

### Phase 1: Foundation (Weeks 1-3)

**Deliverables:**
- Project structure and build configuration (Gradle)
- Core interfaces: `DatabaseAdapter`, `Operation`, `OperationResult`, `OverheadBreakdown`
- `MetricsCollector` with HdrHistogram integration
- Configuration loading and validation
- Basic CLI structure (picocli)

**Test Coverage:**
- Unit tests for all core classes
- Contract test framework for adapters

**Exit Criteria:**
- All interfaces defined and documented
- Metrics collection proven accurate via JMH benchmarks
- CI/CD pipeline operational

### Phase 2: MongoDB Adapter (Weeks 4-6)

**Deliverables:**
- `MongoDBAdapter` implementation
- BSON deserialization instrumentation
- Command listener for server timing
- TestContainers integration tests

**Test Coverage:**
- Integration tests with MongoDB 7.0
- Contract test compliance
- Timing accuracy validation

**Exit Criteria:**
- All capabilities implemented
- Traversal timing captures field-name scanning
- Deserialization metrics accurate within 5%

### Phase 3: Oracle OSON Adapter (Weeks 7-9)

**Deliverables:**
- `OracleOSONAdapter` implementation (SQL/JSON and SODA)
- OSON format instrumentation
- Server execution time extraction
- TestContainers integration (Oracle Free)

**Test Coverage:**
- Integration tests with Oracle 23ai Free
- Comparison tests against MongoDB adapter
- Contract test compliance

**Exit Criteria:**
- All capabilities implemented
- Hash-indexed navigation verified via timing
- Projection efficiency demonstrated

### Phase 4: Workloads and Reporting (Weeks 10-12)

**Deliverables:**
- `TraverseShallowWorkload`
- `TraverseDeepWorkload`
- `TraverseScaleWorkload`
- `DeserializeFullWorkload`
- `DeserializePartialWorkload`
- JSON, CSV, Markdown, HTML reporters
- Comparison report generation

**Test Coverage:**
- Workload correctness tests
- Report format validation
- End-to-end workflow tests

**Exit Criteria:**
- All workloads produce statistically valid results
- Reports clearly show BSON vs OSON differences
- Comparison metrics accurate

### Phase 5: Polish and Documentation (Weeks 13-14)

**Deliverables:**
- User documentation
- API documentation (Javadoc)
- Example configurations
- Tutorial: "Demonstrating BSON vs OSON Traversal Overhead"
- Performance tuning guide

**Exit Criteria:**
- Documentation complete and reviewed
- Sample benchmark runs produce expected results
- Ready for external release

---

## Appendices

### Appendix A: Glossary

| Term | Definition |
|------|------------|
| **BSON** | Binary JSON format used by MongoDB; length-prefixed, traversable |
| **OSON** | Oracle's binary JSON format; hash-indexed, jump-navigable |
| **Traversal** | The process of navigating to a specific field within a document |
| **Overhead** | Time spent on operations other than actual data retrieval |
| **Projection** | Requesting only specific fields from a document |
| **Deserialization** | Converting wire format bytes to application objects |
| **SPI** | Service Provider Interface; extension point for plugins |

### Appendix B: References

1. MongoDB BSON Specification: https://bsonspec.org/
2. Oracle JSON Developer's Guide: https://docs.oracle.com/en/database/oracle/oracle-database/23/adjsn/
3. Oracle OSON Format: https://docs.oracle.com/en/database/oracle/oracle-database/23/adjsn/oson.html
4. HdrHistogram: https://github.com/HdrHistogram/HdrHistogram
5. TestContainers: https://testcontainers.com/
6. picocli: https://picocli.info/

### Appendix C: Sample Benchmark Output

```
DocBench v1.0.0 - Database Document Performance Benchmark
=========================================================

Workload: traverse-deep
Configuration:
  Nesting Depth: 5
  Fields Per Level: 20
  Target Path: order.items[5].product.sku
  Iterations: 50,000

Running MongoDB adapter... ████████████████████ 100%
Running Oracle OSON adapter... ████████████████████ 100%

Results Summary
---------------

┌─────────────────────────┬──────────────────┬──────────────────┬───────────┐
│ Metric                  │ MongoDB (BSON)   │ Oracle (OSON)    │ Δ%        │
├─────────────────────────┼──────────────────┼──────────────────┼───────────┤
│ Total Latency (p50)     │ 1,245 μs         │ 423 μs           │ -66.0%    │
│ Total Latency (p99)     │ 3,891 μs         │ 892 μs           │ -77.1%    │
│ Server Traversal (p50)  │ 847 μs           │ 112 μs           │ -86.8%    │
│ Client Deser. (p50)     │ 234 μs           │ 45 μs            │ -80.8%    │
│ Total Traversal (p50)   │ 1,081 μs         │ 157 μs           │ -85.5%    │
│ Overhead Ratio          │ 68.0%            │ 31.2%            │ -36.8pp   │
│ Throughput (ops/sec)    │ 8,032            │ 23,641           │ +194.4%   │
└─────────────────────────┴──────────────────┴──────────────────┴───────────┘

Key Finding: OSON traversal overhead is 85.5% lower than BSON at depth 5.

Detailed breakdown saved to: ./results/traverse-deep-20251226-153000.json
HTML report generated: ./results/report.html
```

### Appendix D: Future Adapter Roadmap

| Adapter | Priority | Capabilities | Notes |
|---------|----------|--------------|-------|
| PostgreSQL JSONB | High | Nested access, GIN indexes | Compare with Oracle |
| DynamoDB | Medium | Document ops, GSI | Partition key patterns |
| Couchbase | Medium | N1QL, sub-doc API | SDK instrumentation |
| CosmosDB | Low | SQL API, MongoDB API | Multi-API comparison |
| Elasticsearch | Low | JSON documents | Search-optimized |

---

*End of Specification*
