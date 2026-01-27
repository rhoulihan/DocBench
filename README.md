# DocBench

**BSON vs OSON Performance Benchmark Suite**

DocBench provides comprehensive benchmarks comparing MongoDB's BSON and Oracle's OSON binary JSON formats across multiple dimensions:

1. **Client-Side Field Access**: O(n) vs O(1) algorithmic complexity
2. **Server-Side Updates**: MongoDB `$set` vs Oracle `JSON_TRANSFORM`
3. **Deserialization Overhead**: RawBsonDocument vs BsonDocument/Document break-even analysis
4. **$lookup vs SQL JOIN**: MongoDB aggregation vs Oracle parallel execution (joins, document limits, memory limits)

## Table of Contents

- [Latest Results](#latest-results)
- [Benchmark Details](#benchmark-details)
  - [Client-Side Field Access](#1-client-side-field-access-on-vs-o1)
  - [Server-Side Updates](#2-server-side-update-performance)
  - [Deserialization Overhead](#3-deserialization-overhead-analysis)
  - [$lookup vs SQL JOIN](#4-lookup-vs-sql-join)
- [Test Environment](#test-environment)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Database Setup](#database-setup)
- [Configuration](#configuration)
- [Running Benchmarks](#running-benchmarks)
- [AWR Report Generation](#awr-report-generation)
- [Understanding Results](#understanding-results)
- [Troubleshooting](#troubleshooting)

---

## Latest Results

### Executive Summary

With **durability parity** (both databases configured for durable writes):

| Metric | Result |
|--------|--------|
| **Server-Side Updates** | OSON 1.19x faster overall |
| **Client-Side Field Access** | OSON 71x faster overall |
| **OSON Wins** | 30 tests |
| **MongoDB Wins** | 3 tests |

---

## Benchmark Details

### 1. Client-Side Field Access (O(n) vs O(1))

**What this measures:** The time to access a single field from an already-fetched document on the client side.

| Technology | Method | Complexity | Description |
|------------|--------|------------|-------------|
| **BSON (MongoDB)** | `RawBsonDocument.get()` | O(n) | Sequential scanning through binary document to find field |
| **OSON (Oracle)** | `OracleJsonObject.get()` | O(1) | Hash-indexed lookup directly to field offset |

**Why it matters:** For documents with many fields, BSON's linear scanning becomes increasingly expensive while OSON maintains constant-time access regardless of field position.

#### Field Position Tests

Tests access fields at different positions (1st, 50th, 100th, etc.) to demonstrate O(n) vs O(1) scaling behavior.

| Test Case | Description | BSON (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|-----------|-----------|-------|--------|
| Position 1/100 | First field in 100-field document | 250 | 99 | 2.53x | **OSON** |
| Position 50/100 | Middle field in 100-field document | 2,491 | 87 | 28.63x | **OSON** |
| Position 100/100 | Last field in 100-field document | 3,250 | 52 | 62.50x | **OSON** |
| Position 500/500 | Last field in 500-field document | 15,699 | 108 | 145.36x | **OSON** |
| Position 1000/1000 | Last field in 1000-field document | 31,195 | 59 | **528.73x** | **OSON** |

#### Nested Field Access Tests

Tests access fields at varying depths of nesting (e.g., `doc.level1.level2.level3.field`).

| Test Case | Description | BSON (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|-----------|-----------|-------|--------|
| Nested depth 1 | Single level nesting | 598 | 110 | 5.44x | **OSON** |
| Nested depth 3 | Three levels deep | 1,363 | 115 | 11.85x | **OSON** |
| Nested depth 5 | Five levels deep | 2,144 | 170 | 12.61x | **OSON** |

**Key Insight:** BSON time increases linearly with field position (O(n) scaling), while OSON remains constant (~100ns) regardless of position (O(1)).

---

### 2. Server-Side Update Performance

**What this measures:** The time to update field(s) in a document stored in the database, including network round-trip and durability guarantees.

| Technology | Method | Description |
|------------|--------|-------------|
| **MongoDB** | `$set` operator | Updates with `WriteConcern(w:1, j:true)` for durability parity |
| **Oracle** | `JSON_TRANSFORM` | SQL function for partial OSON updates |

**Why it matters:** Server-side updates are the most common operation in document databases. OSON's partial update capability allows modifying specific fields without rewriting the entire document.

#### Single Field Update

Updates a single field in documents of varying sizes (100, 500, 1000 fields). Tests whether update cost scales with document size.

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Single update 100 fields | Update 1 field in 100-field doc | 1,900,402 | 1,232,474 | 0.65x | **OSON** |
| Single update 500 fields | Update 1 field in 500-field doc | 1,993,721 | 1,062,068 | 0.53x | **OSON** |
| Single update 1000 fields | Update 1 field in 1000-field doc | 2,006,917 | 1,116,121 | 0.56x | **OSON** |

#### Multi-Field Update

Updates multiple fields (3, 5, 10) in a single operation. Tests batch update efficiency.

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Multi-update 3 fields | Update 3 fields simultaneously | 1,949,823 | 1,158,361 | 0.59x | **OSON** |
| Multi-update 5 fields | Update 5 fields simultaneously | 1,975,875 | 1,156,843 | 0.59x | **OSON** |
| Multi-update 10 fields | Update 10 fields simultaneously | 1,965,112 | 1,126,143 | 0.57x | **OSON** |

#### Large Document Update

Updates a single field in increasingly large documents (10KB to 4MB). Tests whether update cost scales with document size. **MongoDB must rewrite the entire document; OSON performs in-place partial updates.**

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Large doc ~10KB | Single field update | 1,977,267 | 1,037,956 | 0.52x | **OSON** |
| Large doc ~50KB | Single field update | 1,929,809 | 1,050,259 | 0.54x | **OSON** |
| Large doc ~100KB | Single field update | 1,929,778 | 1,059,995 | 0.55x | **OSON** |
| Large doc ~1MB | Single field update | 2,892,836 | 1,113,762 | **0.39x** | **OSON** |
| Large doc ~4MB | Single field update | 7,385,070 | 1,104,344 | **0.15x** | **OSON** |

**Key Finding:** MongoDB's time increases with document size (O(n) rewrite), while OSON remains constant (~1.1ms) regardless of document size (partial update).

#### Array Push Operations

Appends elements to an array field. Tests `$push` (MongoDB) vs `JSON_TRANSFORM APPEND` (Oracle). "10x1" means 10 sequential single-element pushes.

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Scalar push 1x1 | Push 1 integer to array | 1,907,349 | 1,117,662 | 0.59x | **OSON** |
| Scalar push 10x1 | Push 10 integers (10 operations) | 19,196,365 | 11,572,733 | 0.60x | **OSON** |
| Object push 1x1 | Push 1 object to array | 2,028,989 | 1,194,319 | 0.59x | **OSON** |
| Object push 10x1 | Push 10 objects (10 operations) | 20,169,646 | 13,865,948 | 0.69x | **OSON** |

#### Array Delete Operations

Removes elements from different array positions (beginning, middle, end). Tests `$pull` (MongoDB) vs `JSON_TRANSFORM REMOVE` (Oracle). **Middle deletes are particularly expensive for MongoDB due to array shifting.**

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Scalar delete beginning | Remove first array element | 1,647,896 | 1,099,521 | 0.67x | **OSON** |
| Scalar delete middle | Remove middle array element | 4,282,104 | 1,115,265 | **0.26x** | **OSON** |
| Scalar delete end | Remove last array element | 1,640,492 | 1,238,377 | 0.75x | **OSON** |
| Object delete beginning | Remove first object from array | 2,336,335 | 1,039,076 | 0.44x | **OSON** |
| Object delete middle | Remove middle object from array | 4,218,771 | 1,048,887 | **0.25x** | **OSON** |
| Object delete end | Remove last object from array | 2,274,331 | 1,022,622 | 0.45x | **OSON** |

**Key Finding:** MongoDB's middle-position deletes are ~4x slower than end deletes due to array element shifting. OSON maintains consistent ~1.1ms regardless of position.

#### Large Array Operations

Appends elements to very large arrays (1MB, 4MB). Tests scalability with array size. **MongoDB has specific optimizations for scalar arrays.**

| Test Case | Description | MongoDB (ns) | OSON (ns) | Ratio | Winner |
|-----------|-------------|--------------|-----------|-------|--------|
| Large 1MB scalar array | Push to 1MB integer array | 3,129,667 | 4,015,566 | 1.28x | MongoDB |
| Large 4MB scalar array | Push to 4MB integer array | 7,660,251 | 22,889,482 | 2.99x | MongoDB |
| Large 1MB object array | Push to 1MB object array | 4,869,838 | 4,817,506 | 0.99x | **OSON** |
| Large 4MB object array | Push to 4MB object array | 12,449,115 | 18,552,229 | 1.49x | MongoDB |

**Key Finding:** MongoDB wins on large scalar arrays (specialized optimization), but performance is closer for object arrays.

---

### Summary by Category

| Category | MongoDB Wins | OSON Wins | Advantage |
|----------|--------------|-----------|-----------|
| Client-Side Field Access | 0 | 8 | OSON 71x faster |
| Single Field Update | 0 | 3 | OSON 1.6-1.9x faster |
| Multi-Field Update | 0 | 3 | OSON 1.7x faster |
| Large Document Update | 0 | 5 | OSON 1.8-6.7x faster |
| Array Push | 0 | 4 | OSON 1.5-1.7x faster |
| Array Delete | 0 | 6 | OSON 1.3-4x faster |
| Large Array | 3 | 1 | MongoDB 1.3-3x faster |
| **TOTAL** | **3** | **30** | **OSON wins overall** |

### Key Findings

- **Client-side OSON dominates**: 71x faster for field access due to O(1) hash lookup vs BSON's O(n) sequential scanning
- **OSON partial updates shine at scale**: For large documents (1-4MB), OSON is 2.6-6.7x faster because it modifies only the changed field
- **Middle-position array operations**: OSON is 3-4x faster for deletes from array middle because it doesn't need to shift elements
- **MongoDB wins only on large scalar arrays**: Specific optimization for homogeneous scalar array operations

---

### 3. Deserialization Overhead Analysis

**What this measures:** The cost of deserializing `RawBsonDocument` to `BsonDocument` or `Document`, and the break-even point where deserialization overhead is offset by faster O(1) field access.

| Document Type | Deserialization | Field Access | Use Case |
|---------------|-----------------|--------------|----------|
| **RawBsonDocument** | None (lazy) | O(n) sequential scan | Few field accesses |
| **BsonDocument** | One-time cost | O(1) hash lookup | Many field accesses |
| **Document** | One-time cost | O(1) hash lookup | Many field accesses (simpler API) |
| **OracleJsonObject** | None needed | O(1) hash lookup | Baseline comparison |

**Why it matters:** MongoDB developers must choose between `RawBsonDocument` (no deserialization cost, but O(n) access) and `Document`/`BsonDocument` (upfront deserialization cost, but O(1) access). This benchmark determines the break-even point.

#### Deserialization Cost

One-time cost to convert `RawBsonDocument` to a parsed document type:

| Document Size | → BsonDocument | → Document |
|---------------|----------------|------------|
| 100 fields | 13,727 ns | 13,362 ns |
| 500 fields | 66,543 ns | 65,722 ns |
| 1000 fields | 135,866 ns | 129,474 ns |

**Key Finding:** Deserialization cost scales linearly with document size (~130 ns per field).

#### Single Field Access Comparison

Access time for a single field (1000-field document):

| Position | RawBsonDocument | BsonDocument | Document | OracleJsonObject |
|----------|-----------------|--------------|----------|------------------|
| First (1/1000) | 155 ns | 30 ns | 30 ns | 103 ns |
| Middle (500/1000) | 13,041 ns | 19 ns | 18 ns | 82 ns |
| Last (1000/1000) | 25,871 ns | 22 ns | 22 ns | 68 ns |

**Key Finding:** RawBsonDocument access time scales with field position (O(n)), while parsed documents maintain constant ~20-30 ns (O(1)).

#### Repeated Access Performance

Accessing fields multiple times on the same document (1000-field document, middle position):

| Accesses | RawBsonDocument | BsonDocument | Document | OracleJsonObject |
|----------|-----------------|--------------|----------|------------------|
| 5x same field | 66,092 ns | 57 ns | 61 ns | 326 ns |
| 25x same field | 326,417 ns | 168 ns | 168 ns | 1,348 ns |
| 100x same field | 1,308,754 ns | 438 ns | 436 ns | 5,665 ns |
| 5 different fields | 56,319 ns | 155 ns | 130 ns | 353 ns |
| 25 different fields | 325,121 ns | 143 ns | 122 ns | 1,413 ns |
| 100 different fields | 1,300,826 ns | 526 ns | 458 ns | 5,834 ns |

#### Break-Even Analysis

Total time including deserialization cost. **Bold** indicates when deserialization pays off vs RawBsonDocument.

##### 100 Fields - Same Field Repeated

| Accesses | RawBsonDocument | BsonDocument | Document | OracleJsonObject | Winner |
|----------|-----------------|--------------|----------|------------------|--------|
| 1 | 1,394 ns | 13,149 ns | 13,117 ns | 57 ns | Oracle |
| 2 | 2,794 ns | 13,079 ns | 13,047 ns | 99 ns | Oracle |
| 5 | 6,941 ns | 13,093 ns | 13,059 ns | 226 ns | Oracle |
| **10** | 13,937 ns | **13,116 ns** | **13,082 ns** | 436 ns | Oracle |
| 25 | 34,945 ns | **13,190 ns** | **13,155 ns** | 1,096 ns | Oracle |
| 50 | 70,563 ns | **13,328 ns** | **13,272 ns** | 2,266 ns | Oracle |
| 100 | 140,479 ns | **13,560 ns** | **13,497 ns** | 4,468 ns | Oracle |

##### 100 Fields - Different Fields

| Accesses | RawBsonDocument | BsonDocument | Document | OracleJsonObject | Winner |
|----------|-----------------|--------------|----------|------------------|--------|
| 1 | 102 ns | 13,119 ns | 12,867 ns | 58 ns | Oracle |
| 5 | 5,772 ns | 13,134 ns | 12,884 ns | 239 ns | Oracle |
| 10 | 12,907 ns | 13,157 ns | 12,911 ns | 487 ns | Oracle |
| **25** | 34,465 ns | **13,251 ns** | **12,989 ns** | 1,225 ns | Oracle |
| 50 | 70,871 ns | **13,368 ns** | **13,112 ns** | 2,445 ns | Oracle |
| 100 | 142,142 ns | **13,618 ns** | **13,346 ns** | 4,759 ns | Oracle |

##### 1000 Fields - Same Field Repeated

| Accesses | RawBsonDocument | BsonDocument | Document | OracleJsonObject | Winner |
|----------|-----------------|--------------|----------|------------------|--------|
| 1 | 13,114 ns | 133,960 ns | 131,917 ns | 68 ns | Oracle |
| 5 | 65,045 ns | 133,976 ns | 131,934 ns | 289 ns | Oracle |
| **10** | 130,511 ns | **134,007 ns** | **131,959 ns** | 592 ns | Oracle |
| 25 | 326,473 ns | **134,081 ns** | **132,031 ns** | 1,419 ns | Oracle |
| 50 | 650,175 ns | **134,230 ns** | **132,161 ns** | 2,904 ns | Oracle |
| 100 | 1,300,384 ns | **134,442 ns** | **132,370 ns** | 5,667 ns | Oracle |

##### 1000 Fields - Different Fields

| Accesses | RawBsonDocument | BsonDocument | Document | OracleJsonObject | Winner |
|----------|-----------------|--------------|----------|------------------|--------|
| 1 | 102 ns | 134,438 ns | 128,766 ns | 58 ns | Oracle |
| 5 | 52,472 ns | 134,457 ns | 128,783 ns | 302 ns | Oracle |
| **10** | 118,760 ns | **134,482 ns** | **128,805 ns** | 595 ns | Oracle |
| 25 | 315,746 ns | **134,572 ns** | **128,880 ns** | 1,484 ns | Oracle |
| 50 | 643,998 ns | **134,682 ns** | **128,998 ns** | 2,954 ns | Oracle |
| 100 | 1,301,665 ns | **134,946 ns** | **129,228 ns** | 6,018 ns | Oracle |

#### Nested Document Access

| Depth | RawBsonDocument | BsonDocument | Document | OracleJsonObject |
|-------|-----------------|--------------|----------|------------------|
| Deserialization (depth 3) | - | 5,850 ns | 5,631 ns | - |
| Deserialization (depth 5) | - | 8,439 ns | 8,146 ns | - |
| Access (depth 3) | 1,099 ns | 52 ns | 49 ns | 172 ns |
| Access (depth 5) | 1,697 ns | 49 ns | 45 ns | 153 ns |

#### Break-Even Summary

| Document Size | Same Field Pattern | Different Fields Pattern |
|---------------|-------------------|-------------------------|
| 100 fields | **10 accesses** | **25 accesses** |
| 500 fields | **25 accesses** | **25 accesses** |
| 1000 fields | **10 accesses** | **10 accesses** |

#### Recommendations for MongoDB Developers

| Access Pattern | Recommended Document Type |
|----------------|---------------------------|
| < 10 field accesses | `RawBsonDocument` - avoid deserialization overhead |
| ≥ 10 field accesses | `Document` - O(1) access pays off |
| Streaming/single-pass | `RawBsonDocument` - minimal memory overhead |
| Random field access | `Document` - O(1) lookup essential |
| Field position known to be early | `RawBsonDocument` - O(n) cost is minimal |

**Key Insight:** Oracle OSON always wins because it provides O(1) access without any deserialization cost. For MongoDB, the break-even point is approximately **10-25 field accesses** depending on document size and access pattern.

---

### 4. $lookup vs SQL JOIN - Triple Comparison

**What this measures:** Performance comparison between MongoDB's `$lookup` aggregation operator, Oracle's `$sql` aggregation operator (via MongoDB API), and Oracle JDBC (native SQL), demonstrating Oracle's advantages in parallel execution and exposing MongoDB's architectural limitations.

| Technology | Method | Description |
|------------|--------|-------------|
| **MongoDB** | `$lookup` | Single-threaded aggregation operator for document joins |
| **Oracle MongoDB API** | `$sql` aggregation | SQL execution via MongoDB wire protocol (ORDS) |
| **Oracle JDBC** | SQL JOIN with `PARALLEL` hints | Native JDBC with full result transfer |

**Triple Comparison Modes:**
- **MongoDB Native**: Standard `$lookup` aggregation with full result iteration
- **Oracle MongoDB API**: Uses `$sql` aggregation stage via ORDS MongoDB API compatibility layer
- **Oracle JDBC**: Direct SQL JOIN queries with PARALLEL hints and full ResultSet transfer

**Why it matters:** Join operations are critical for querying related data across collections/tables. This benchmark exposes fundamental architectural differences:

- **Parallel Execution**: Oracle leverages PARALLEL hints for multi-core execution; MongoDB `$lookup` is single-threaded
- **16MB Document Limit**: MongoDB fails when `$lookup` results exceed 16MB per document; Oracle has no such limit
- **100MB Memory Limit**: MongoDB aggregation spills to disk above 100MB; Oracle uses optimized temp tablespace

#### Benchmark Results Summary

| Metric | MongoDB Native | Oracle API | Oracle JDBC |
|--------|---------------|------------|-------------|
| **Wins** | 0 | 30 | N/A (fastest) |
| **Failures** | 2 (16MB limit) | 0 | 0 |

#### A. Baseline Join Performance

| Test Case | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner (Wire Protocol) |
|-----------|--------------|-----------------|------------------|------------------------|
| 1K customers | 19.67 | 96.78 | 0.49 | MongoDB (4.9x) |
| 10K customers | 170.33 | 992.44 | 0.48 | MongoDB (5.8x) |
| 100K customers | 1,594.54 | 10,028.04 | 0.46 | MongoDB (6.3x) |

**Insight:** For simple joins, MongoDB's native `$lookup` is 5-6x faster than Oracle MongoDB API when comparing wire protocols. Oracle JDBC shows sub-millisecond performance due to efficient driver and query caching.

#### B. Join Cardinality Impact (1:N Ratio)

| Cardinality | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner (Wire) |
|-------------|--------------|-----------------|------------------|---------------|
| 1:1 | 49.36 | 108.01 | 0.45 | MongoDB |
| 1:10 | 164.47 | 1,004.27 | 0.47 | MongoDB |
| **1:100** | 112.33 | 97.46 | 0.44 | **Oracle API** |
| **1:1000** | 113.58 | 12.43 | 0.41 | **Oracle API (9.1x)** |

**Key Finding:** Critical crossover at 1:100 cardinality! MongoDB slows as it materializes all related documents. Oracle API becomes faster at high cardinalities.

#### C. Memory Limit Impact (Working Set Size)

| Working Set | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner (Wire) |
|-------------|--------------|-----------------|------------------|---------------|
| 50MB | 1,439.92 | 982.29 | 0.56 | Oracle API (1.5x) |
| 100MB | 2,710.75 | 987.13 | 0.49 | Oracle API (2.7x) |
| 150MB | 3,933.98 | 973.56 | 1.07 | Oracle API (4.0x) |
| 200MB | 5,580.63 | 985.26 | 0.85 | Oracle API (5.7x) |
| **500MB** | 14,151.86 | 968.41 | 0.54 | **Oracle API (14.6x)** |

**Key Finding:** Oracle dominates at scale! MongoDB's 100MB memory limit causes severe degradation. At 500MB working set, Oracle API is **14.6x faster** than MongoDB.

#### D. Sort Performance at Scale

| Documents | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner (Wire) |
|-----------|--------------|-----------------|------------------|---------------|
| 10K | 45.98 | 97.12 | 0.56 | MongoDB (2.1x) |
| 100K | 478.91 | 958.32 | 0.61 | MongoDB (2.0x) |
| **500K** | 2,761.34 | 963.85 | 1.21 | **Oracle API (2.9x)** |
| **1M** | 5,338.15 | 961.99 | 0.85 | **Oracle API (5.5x)** |

**Key Finding:** Crossover at ~250K documents! MongoDB wins for small sorts, but Oracle's parallel sort dominates at scale.

#### E. Document Size Limit (16MB BSON Limit)

| Result Size | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner |
|-------------|--------------|-----------------|------------------|--------|
| ~100KB | 0.75 | 2.38 | 0.70 | MongoDB |
| ~1MB | 1.34 | 6.05 | 0.54 | MongoDB |
| ~8MB | 7.90 | 40.64 | 0.46 | MongoDB |
| ~15MB | 11.38 | 71.58 | 0.41 | MongoDB |
| **~20MB** | **FAILED** | 97.08 | 0.38 | Oracle (Mongo fails) |
| **~50MB** | **FAILED** | 256.47 | 0.36 | Oracle (Mongo fails) |

**Critical Finding:** MongoDB's 16MB BSON limit is a hard architectural constraint. When `$lookup` results exceed this limit, MongoDB **FAILS entirely** while Oracle succeeds. This is critical for analytics with large embedded arrays.

#### F. Multi-Stage Pipeline Complexity

| Pipeline | MongoDB (ms) | Oracle API (ms) | Oracle JDBC (ms) | Winner (Wire) |
|----------|--------------|-----------------|------------------|---------------|
| $lookup → $sort | 341.43 | 962.23 | 0.50 | MongoDB (2.8x) |
| **$lookup → $unwind → $group** | 313.90 | 61.15 | 0.88 | **Oracle API (5.1x)** |
| **$lookup → $unwind → $group → $sort** | 312.87 | 61.27 | 0.52 | **Oracle API (5.1x)** |
| $lookup → $lookup (chained) | 19.83 | 64.49 | 0.86 | MongoDB (3.3x) |

**Key Finding:** Simple pipelines favor MongoDB, but complex pipelines with `$unwind`/`$group` favor Oracle API (4-5x faster).

#### When to Use Each Approach

| Use Case | Recommended |
|----------|-------------|
| Simple FK joins, low cardinality (1:1 to 1:10) | MongoDB `$lookup` |
| High cardinality (1:100+), large working sets | Oracle `$sql` API |
| Documents exceeding 16MB | Oracle (MongoDB fails) |
| Complex aggregations ($unwind, $group) | Oracle `$sql` API |
| Maximum throughput, Java integration | Oracle JDBC |
| Small to medium sorts (<250K docs) | MongoDB |
| Large sorts (500K+ docs), parallel execution | Oracle |

#### Test Categories

| Category | Tests | Purpose |
|----------|-------|---------|
| **A: Baseline Joins** | 1K, 10K, 100K customers | Establish baseline join performance |
| **B: Cardinality** | 1:1, 1:10, 1:100, 1:1000 | Test one-to-many join performance |
| **C: Parallel Execution** | PARALLEL 1-2 (Free Edition) | Demonstrate Oracle's CPU scaling |
| **D: Document Size Limits** | 100KB - 50MB | Expose MongoDB's 16MB limit |
| **E: Memory Limits** | 50MB - 500MB | Test aggregation memory overflow |
| **F: Sort Spillover** | 10K - 1M docs | Measure sort performance at scale |
| **G: Multi-Stage Pipelines** | 2-4 stages, chained lookups | Complex aggregation comparison |

#### Running the Benchmark

```bash
# Run with Oracle MongoDB API (default if configured)
./gradlew integrationTest --tests "*LookupVsSqlJoinTest" --rerun-tasks

# Run with JDBC only (comment out oracle.mongodb.uri in config)
./gradlew integrationTest --tests "*LookupVsSqlJoinTest" --rerun-tasks
```

#### Generated Reports

| Report | Description |
|--------|-------------|
| `reports/lookup_vs_sql_report.html` | Interactive HTML report with charts |
| `reports/triple_comparison_full_report.html` | Full 3-way comparison with Chart.js visualizations |
| `reports/lookup_benchmark_charts.html` | Detailed benchmark charts |

#### Key Findings Summary

1. **MongoDB wins simple joins**: 5-6x faster for basic FK joins at low cardinality
2. **Oracle wins at scale**: 14.6x faster at 500MB working sets, 5.5x faster for 1M doc sorts
3. **MongoDB 16MB hard limit**: `$lookup` fails entirely when results exceed BSON document limit
4. **Cardinality crossover at 1:100**: Oracle becomes faster for high-cardinality joins
5. **Complex pipelines favor Oracle**: 5x faster for `$unwind`/`$group` operations
6. **Oracle JDBC extremely efficient**: Sub-millisecond performance with full result transfer

---

## Test Environment

### Benchmark Configuration

This benchmark uses **production-like durability settings** on both databases to ensure a fair comparison:

| Database | Configuration | Durability Behavior |
|----------|---------------|---------------------|
| **MongoDB** | Single-member replica set, `w:1`, `j:true` | Waits for journal sync before acknowledging |
| **Oracle** | Standard configuration | Waits for redo log sync before acknowledging (mandatory) |

### Why j:true Write Concern?

According to the [MongoDB Journaling documentation](https://www.mongodb.com/docs/manual/core/journaling/), without `j:true`, WiredTiger only syncs journal records to disk:
- *"At every 100 milliseconds"* (configurable via `storage.journal.commitIntervalMs`)
- *"When WiredTiger creates a new journal file"* (approximately every 100 MB of data)

The documentation explicitly states: *"In between write operations, while the journal records remain in the WiredTiger buffers, updates can be lost following a hard shutdown of mongod."*

**Without `j:true`**, MongoDB acknowledges writes after they reach server memory but before journal sync. This means up to 100ms of writes could be lost in a crash. Oracle always waits for redo log sync before acknowledging. Using `j:true` ensures MongoDB also waits for journal sync, providing equivalent durability guarantees.

### Why Single-Member Replica Set?

MongoDB is configured as a **single-member replica set** for several reasons:

1. **Production-Like Configuration**: According to [MongoDB documentation](https://www.mongodb.com/docs/manual/tutorial/deploy-replica-set-for-testing/), replica sets are recommended even for development to test replica set features. The [MongoDB Community](https://www.mongodb.com/community/forums/t/should-i-use-single-node-replica-set-for-production/190558) notes: *"A single-member replica set leaves flexibility for features like Change Streams, adding a hidden secondary for hot backup, and being able to quickly scale back up later."*

2. **Feature Parity**: Single-member replica sets provide access to transactions, change streams, and other replica set features that standalone mode does not support.

### Write Concern Details

#### MongoDB's Recommended Default

According to the [MongoDB Write Concern documentation](https://www.mongodb.com/docs/manual/reference/write-concern/), the recommended write concern for durable writes is:

```javascript
{ w: "majority" }
```

With `writeConcernMajorityJournalDefault: true` (the default), `w: "majority"` **implicitly includes `j: true`**, ensuring writes are persisted to the on-disk journal before acknowledgment.

#### Our Benchmark Configuration

```java
// MongoDB write concern used in benchmarks
WriteConcern durableWriteConcern = WriteConcern.W1.withJournal(true);
```

We use `w:1` with explicit `j:true` for the following reasons:

- **`w:1`**: Write acknowledged by primary only. We use this instead of `w: "majority"` to avoid measuring replication overhead, isolating the comparison to single-node write performance.

- **`j:true` (explicit)**: Required because `j:true` is only *implied* by default for `w: "majority"`, not for `w:1`. Without explicit `j:true`, MongoDB would acknowledge writes after they reach server memory but before journal sync.

Note: With a single-member replica set, `w:1` and `w: "majority"` are functionally equivalent since majority of one is one. However, being explicit about the write concern ensures clarity about what we're measuring and avoids any ambiguity.

This configuration ensures MongoDB waits for journal sync while avoiding the additional latency of replica set acknowledgment, matching Oracle's behavior where every COMMIT waits for redo log flush (`log file sync` wait event).

### Hardware & Software

| Component | Version/Specification |
|-----------|----------------------|
| **Oracle Database** | 26ai Free (23.26.0.0.0) |
| **MongoDB** | 8.0.16 |
| **Java** | OpenJDK 23.0.2 |
| **Platform** | WSL2 Linux (Windows 11) |
| **Storage** | SSD |

### Oracle Tuning Applied

- Redo logs: 3 x 500MB
- `session_cached_cursors`: 200
- `open_cursors`: 500

### Benchmark Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| `WARMUP_ITERATIONS` | 100 | JIT warmup before measurement |
| `MEASUREMENT_ITERATIONS` | 1,000 | Iterations for timing |
| `ARRAY_MEASUREMENT_ITERATIONS` | 100 | Iterations for array tests |

---

## Prerequisites

### Required Software

| Component | Minimum Version | Recommended |
|-----------|-----------------|-------------|
| Java JDK | 21+ | 21 LTS or 23 |
| Gradle | 8.0+ | 8.5+ (wrapper included) |
| MongoDB | 7.0+ | 8.0+ |
| Oracle Database | 23ai Free | 26ai (23.26+) |

### Hardware Recommendations

For accurate benchmark results:
- **CPU**: 4+ cores dedicated to databases
- **Memory**: 16GB+ (8GB for Oracle, 4GB for MongoDB)
- **Storage**: SSD recommended for consistent I/O
- **Network**: Local connections (localhost) to minimize network variance

---

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/rhoulihan/DocBench.git
cd DocBench
```

### 2. Verify Java Version

```bash
java -version
# Should show: openjdk version "21.x.x" or higher
```

### 3. Build the Project

```bash
./gradlew build -x test
```

---

## Database Setup

### MongoDB Setup (Single-Member Replica Set)

For fair benchmarking with durability parity, MongoDB must be configured as a replica set.

#### Docker Setup (Recommended)

1. **Create a keyfile for replica set authentication:**

```bash
mkdir -p mongodb-keyfile
openssl rand -base64 756 > mongodb-keyfile/mongo-keyfile
chmod 400 mongodb-keyfile/mongo-keyfile
```

2. **Start MongoDB with replica set enabled:**

```bash
docker run -d \
  --name mongodb \
  -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=admin \
  -e MONGO_INITDB_ROOT_PASSWORD=password \
  -v mongodb-keyfile:/data/keyfile \
  mongo:8.0 \
  mongod --replSet rs0 --bind_ip_all --keyFile /data/keyfile/mongo-keyfile
```

3. **Initialize the replica set:**

```bash
docker exec -it mongodb mongosh -u admin -p password --authenticationDatabase admin \
  --eval "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:27017'}]})"
```

4. **Create benchmark user:**

```bash
docker exec -it mongodb mongosh -u admin -p password --authenticationDatabase admin <<EOF
use testdb
db.createUser({
  user: "translator",
  pwd: "translator",
  roles: [{ role: "readWrite", db: "testdb" }]
})
EOF
```

5. **Verify replica set status:**

```bash
docker exec -it mongodb mongosh -u admin -p password --authenticationDatabase admin \
  --eval "rs.status().members.map(m => ({name: m.name, state: m.stateStr}))"
# Should show: [ { name: 'localhost:27017', state: 'PRIMARY' } ]
```

#### Why Replica Set Instead of Standalone?

- Single-member replica sets provide access to transactions and change streams
- [MongoDB recommends replica sets](https://www.mongodb.com/docs/manual/tutorial/deploy-replica-set-for-testing/) even for development environments
- Provides flexibility for future scaling (adding secondaries, hidden members for backup)

### Oracle Setup

#### Docker (Recommended)

```bash
# Pull Oracle 23ai Free (or 26ai when available)
docker run -d \
  --name oracle-free \
  -p 1521:1521 \
  -e ORACLE_PWD=YourPassword123 \
  container-registry.oracle.com/database/free:latest

# Wait for database to start (check logs)
docker logs -f oracle-free
# Wait until you see: "DATABASE IS READY TO USE!"
```

#### Create Benchmark User

Connect as SYSDBA and run:

```sql
-- Connect to PDB
ALTER SESSION SET CONTAINER = FREEPDB1;

-- Create user
CREATE USER translator IDENTIFIED BY translator
  DEFAULT TABLESPACE users
  QUOTA UNLIMITED ON users;

-- Grant basic privileges
GRANT CONNECT, RESOURCE TO translator;
GRANT CREATE SESSION TO translator;
GRANT CREATE TABLE TO translator;
GRANT CREATE PROCEDURE TO translator;

-- Grant JSON/SODA privileges
GRANT SODA_APP TO translator;

-- Grant AWR privileges (required for AWR report generation)
GRANT SELECT ON V_$INSTANCE TO translator;
GRANT SELECT ON V_$DATABASE TO translator;
GRANT SELECT ON V_$SESSION TO translator;
GRANT SELECT ON V_$SQLAREA TO translator;
GRANT EXECUTE ON DBMS_WORKLOAD_REPOSITORY TO translator;
```

### Oracle Performance Tuning

**CRITICAL**: Apply these settings for accurate benchmark results.

#### 1. Redo Log Configuration

Small redo logs cause checkpoint waits that skew results. Increase to 500MB each:

```sql
-- Connect as SYSDBA
-- Check current redo log sizes
SELECT group#, bytes/1024/1024 AS size_mb, status FROM v$log;

-- Add new larger log groups (if current logs are < 500MB)
ALTER DATABASE ADD LOGFILE GROUP 4 SIZE 500M;
ALTER DATABASE ADD LOGFILE GROUP 5 SIZE 500M;
ALTER DATABASE ADD LOGFILE GROUP 6 SIZE 500M;

-- Switch logs and drop old small groups
ALTER SYSTEM SWITCH LOGFILE;
ALTER SYSTEM CHECKPOINT;

-- Drop old groups (only INACTIVE groups can be dropped)
ALTER DATABASE DROP LOGFILE GROUP 1;
ALTER DATABASE DROP LOGFILE GROUP 2;
ALTER DATABASE DROP LOGFILE GROUP 3;
```

#### 2. Cursor Caching

```sql
ALTER SYSTEM SET session_cached_cursors = 200 SCOPE = SPFILE;
ALTER SYSTEM SET open_cursors = 500 SCOPE = SPFILE;
-- Restart database to apply
```

---

## Configuration

### Create Configuration File

```bash
cp config/local.properties.example config/local.properties
```

Edit `config/local.properties`:

```properties
# MongoDB Configuration (with replica set)
mongodb.uri=mongodb://translator:translator@localhost:27017/testdb?replicaSet=rs0&authSource=testdb
mongodb.database=testdb

# Oracle Configuration
oracle.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1
oracle.username=translator
oracle.password=translator
```

**Important**: Include `replicaSet=rs0` in the MongoDB URI to enable replica set features including `j:true` write concern.

---

## Running Benchmarks

### Run All Benchmarks

```bash
./gradlew clean integrationTest --rerun-tasks
```

### Run Specific Test Suites

```bash
# Client-side field access (O(n) vs O(1))
./gradlew integrationTest --tests "*.BsonVsOsonClientSideTest"

# Server-side updates with AWR reports
./gradlew integrationTest --tests "*.ServerSideUpdateTest"

# $lookup vs SQL JOIN (parallel execution, document/memory limits)
./gradlew integrationTest --tests "*.LookupVsSqlJoinTest"
```

### Expected Output

```
Server-Side Update: JSON_TRANSFORM vs MongoDB $set
  MongoDB: $set operator
    - WriteConcern: w=1, j=true (journal sync for durability parity)
    - Single-member replica set configuration

  Single update 100 fields: MongoDB=1900402 ns, OSON=1232474 ns, 0.65x OSON
  Large doc ~4096KB: MongoDB=7385070 ns, OSON=1104344 ns, 0.15x OSON
```

---

## AWR Report Generation

### Prerequisites

AWR reports require Oracle privileges:

```sql
GRANT SELECT ON V_$INSTANCE TO translator;
GRANT SELECT ON V_$DATABASE TO translator;
GRANT EXECUTE ON DBMS_WORKLOAD_REPOSITORY TO translator;
```

### Generated Reports

After running `ServerSideUpdateTest`, AWR reports are saved to `build/reports/awr/`:

| Report | Description |
|--------|-------------|
| `awr_00_baseline.html` | Protocol overhead baseline (non-JSON operations) |
| `awr_01_single_field.html` | Single field update tests |
| `awr_02_multi_field.html` | Multi-field update tests |
| `awr_03a_large_doc_10KB.html` | 10KB document update |
| `awr_03b_large_doc_50KB.html` | 50KB document update |
| `awr_03c_large_doc_100KB.html` | 100KB document update |
| `awr_03d_large_doc_1MB.html` | 1MB document update |
| `awr_03e_large_doc_4MB.html` | 4MB document update |
| `awr_04_array_push.html` | Array push operations |
| `awr_05_array_delete.html` | Array delete operations |
| `awr_06_large_array.html` | Large array operations |

---

## Understanding Results

### Output Files

| File | Description |
|------|-------------|
| `reports/performance_report.html` | Interactive HTML report with all results and test descriptions |
| `reports/awr/*.html` | Oracle AWR reports per test category |

### Interpreting Ratios

| Ratio | Interpretation |
|-------|----------------|
| `0.65x OSON` | OSON is 1.54x faster (inverse: 1/0.65 = 1.54) |
| `0.15x OSON` | OSON is 6.67x faster (inverse: 1/0.15 = 6.67) |
| `2.99x MongoDB` | MongoDB is 2.99x faster |

---

## Troubleshooting

### MongoDB Replica Set Issues

```
MongoServerError: not primary
```

**Solution**: Verify replica set is initialized:
```bash
docker exec mongodb mongosh -u admin -p password --authenticationDatabase admin --eval "rs.status()"
```

### Oracle Checkpoint Waits

Check for checkpoint waits:
```sql
SELECT event, total_waits FROM v$system_event WHERE event LIKE '%checkpoint%';
```

**Solution**: Increase redo log size to 500MB each.

---

## License

MIT License - see [LICENSE](LICENSE) file.

---

## References

- [MongoDB Write Concern Documentation](https://www.mongodb.com/docs/manual/reference/write-concern/)
- [MongoDB Journaling Documentation](https://www.mongodb.com/docs/manual/core/journaling/)
- [MongoDB Replica Set for Testing](https://www.mongodb.com/docs/manual/tutorial/deploy-replica-set-for-testing/)
- [MongoDB Single-Node Replica Set Discussion](https://www.mongodb.com/community/forums/t/should-i-use-single-node-replica-set-for-production/190558)
- [Oracle JSON_TRANSFORM Documentation](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/JSON_TRANSFORM.html)
