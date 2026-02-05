# DocBench

**BSON vs OSON Performance Benchmark Suite**

DocBench provides comprehensive benchmarks comparing MongoDB's BSON and Oracle's OSON binary JSON formats across multiple dimensions:

1. **Client-Side Field Access**: O(n) vs O(1) algorithmic complexity
2. **Server-Side Updates**: MongoDB `$set` vs Oracle `JSON_TRANSFORM`
3. **Deserialization Overhead**: RawBsonDocument vs BsonDocument/Document break-even analysis
4. **$lookup vs SQL JOIN**: MongoDB aggregation vs Oracle parallel execution (joins, document limits, memory limits)

## Table of Contents

- [Quick Start](#quick-start)
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

## Quick Start

```bash
# 1. Clone and build
git clone https://github.com/rhoulihan/DocBench.git
cd DocBench
./gradlew build -x test

# 2. Configure database connections
cp config/local.properties.example config/local.properties
# Edit config/local.properties with your database credentials

# 3. Run all benchmarks
./gradlew integrationTest --rerun-tasks

# 4. View results
# HTML reports generated in reports/ and build/reports/awr/
```

---

## Benchmark Details

### 1. Client-Side Field Access (O(n) vs O(1))

**What this measures:** The time to access a single field from an already-fetched document on the client side.

| Technology | Method | Complexity | Description |
|------------|--------|------------|-------------|
| **BSON (MongoDB)** | `RawBsonDocument.get()` | O(n) | Sequential scanning through binary document to find field |
| **OSON (Oracle)** | `OracleJsonObject.get()` | O(1) | Hash-indexed lookup directly to field offset |

**Why it matters:** For documents with many fields, BSON's linear scanning becomes increasingly expensive while OSON maintains constant-time access regardless of field position.

#### Test Categories

- **Field Position Tests**: Access fields at different positions (1st, 50th, 100th, etc.) to demonstrate O(n) vs O(1) scaling behavior
- **Nested Field Access Tests**: Access fields at varying depths of nesting (e.g., `doc.level1.level2.level3.field`)

**Running this test:**
```bash
./gradlew integrationTest --tests "*BsonVsOsonClientSideTest" --rerun-tasks
```

---

### 2. Server-Side Update Performance

**What this measures:** The time to update field(s) in a document stored in the database, including network round-trip and durability guarantees.

| Technology | Method | Description |
|------------|--------|-------------|
| **MongoDB** | `$set` operator | Updates with `WriteConcern(w:1, j:true)` for durability parity |
| **Oracle** | `JSON_TRANSFORM` | SQL function for partial OSON updates |

**Why it matters:** Server-side updates are the most common operation in document databases. OSON's partial update capability allows modifying specific fields without rewriting the entire document.

#### Test Categories

| Category | Description |
|----------|-------------|
| **Single Field Update** | Updates a single field in documents of varying sizes (100, 500, 1000 fields) |
| **Multi-Field Update** | Updates multiple fields (3, 5, 10) in a single operation |
| **Large Document Update** | Updates a single field in increasingly large documents (10KB to 4MB) |
| **Array Push Operations** | Appends elements to an array field (`$push` vs `JSON_TRANSFORM APPEND`) |
| **Array Delete Operations** | Removes elements from different array positions (beginning, middle, end) |
| **Large Array Operations** | Appends elements to very large arrays (1MB, 4MB) |

**Running this test:**
```bash
./gradlew integrationTest --tests "*ServerSideUpdateTest" --rerun-tasks
```

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

#### Test Categories

| Category | Description |
|----------|-------------|
| **Deserialization Cost** | One-time cost to convert `RawBsonDocument` to a parsed document type |
| **Single Field Access** | Access time for a single field at various positions |
| **Repeated Access** | Accessing fields multiple times on the same document |
| **Break-Even Analysis** | Total time including deserialization cost |
| **Nested Document Access** | Performance at different nesting depths |

**Running this test:**
```bash
./gradlew integrationTest --tests "*DeserializationOverheadTest" --rerun-tasks
```

---

### 4. $lookup vs SQL JOIN

**What this measures:** Performance comparison between MongoDB's `$lookup` aggregation operator, Oracle's `$sql` aggregation operator (via MongoDB API), and Oracle JDBC (native SQL).

| Technology | Method | Description |
|------------|--------|-------------|
| **MongoDB** | `$lookup` | Single-threaded aggregation operator for document joins |
| **Oracle MongoDB API** | `$sql` aggregation | SQL execution via MongoDB wire protocol (ORDS) |
| **Oracle JDBC** | SQL JOIN with `PARALLEL` hints | Native JDBC with full result transfer |

**Why it matters:** Join operations are critical for querying related data across collections/tables. This benchmark exposes fundamental architectural differences:

- **Parallel Execution**: Oracle leverages PARALLEL hints for multi-core execution; MongoDB `$lookup` is single-threaded
- **16MB Document Limit**: MongoDB fails when `$lookup` results exceed 16MB per document; Oracle has no such limit
- **100MB Memory Limit**: MongoDB aggregation spills to disk above 100MB; Oracle uses optimized temp tablespace

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

**Running this test:**
```bash
# Run full $lookup vs SQL JOIN benchmark suite
./gradlew integrationTest --tests "*LookupVsSqlJoinTest" --rerun-tasks

# Run specific test methods
./gradlew integrationTest --tests "*LookupVsSqlJoinTest.A1*" --rerun-tasks  # Simple 1K join
./gradlew integrationTest --tests "*LookupVsSqlJoinTest.A2*" --rerun-tasks  # Simple 10K join
./gradlew integrationTest --tests "*LookupVsSqlJoinTest.A3*" --rerun-tasks  # Simple 100K join
```

#### Generated Reports

| Report | Description |
|--------|-------------|
| `reports/lookup_vs_sql_report.html` | Interactive HTML report with charts |
| `reports/triple_comparison_report.html` | Full 3-way comparison with Chart.js visualizations |
| `build/reports/awr/` | Oracle AWR reports per test |

---

## Test Environment

### Benchmark Configuration

This benchmark uses **production-like durability settings** on both databases to ensure a fair comparison:

| Database | Configuration | Durability Behavior |
|----------|---------------|---------------------|
| **MongoDB** | Single-member replica set, `w:1`, `j:true` | Waits for journal sync before acknowledging |
| **Oracle** | Standard configuration | Waits for redo log sync before acknowledging (mandatory) |

### Write Concern Details

We use `w:1` with explicit `j:true` to ensure MongoDB waits for journal sync while avoiding the additional latency of replica set acknowledgment, matching Oracle's behavior where every COMMIT waits for redo log flush.

### Hardware & Software Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **Oracle Database** | 23ai Free | 26ai (23.26+) |
| **MongoDB** | 7.0+ | 8.0+ |
| **Java JDK** | 21+ | 21 LTS or 23 |
| **Gradle** | 8.0+ | 8.5+ (wrapper included) |

---

## Prerequisites

### Required Software

| Component | Version | Notes |
|-----------|---------|-------|
| Java JDK | 21+ | Required for modern Java features |
| Gradle | 8.0+ | Wrapper included in project |
| MongoDB | 7.0+ | Must be configured as replica set |
| Oracle Database | 23ai+ | With ORDS for MongoDB API support |

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

1. **Start MongoDB with replica set enabled:**

```bash
docker run -d \
  --name mongodb \
  -p 27017:27017 \
  mongo:8.0 \
  mongod --replSet rs0 --bind_ip_all
```

2. **Initialize the replica set:**

```bash
docker exec -it mongodb mongosh --eval "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:27017'}]})"
```

3. **Create benchmark database:**

```bash
docker exec -it mongodb mongosh --eval "use testdb"
```

4. **Verify replica set status:**

```bash
docker exec -it mongodb mongosh --eval "rs.status().members.map(m => ({name: m.name, state: m.stateStr}))"
# Should show: [ { name: 'localhost:27017', state: 'PRIMARY' } ]
```

### Oracle Setup

#### Docker (Recommended)

```bash
# Pull Oracle 23ai Free
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
CREATE USER docbench IDENTIFIED BY docbench123
  DEFAULT TABLESPACE users
  QUOTA UNLIMITED ON users;

-- Grant basic privileges
GRANT CONNECT, RESOURCE TO docbench;
GRANT CREATE SESSION TO docbench;
GRANT CREATE TABLE TO docbench;
GRANT CREATE PROCEDURE TO docbench;

-- Grant JSON/SODA privileges
GRANT SODA_APP TO docbench;

-- Grant AWR privileges (required for AWR report generation)
GRANT SELECT ON V_$INSTANCE TO docbench;
GRANT SELECT ON V_$DATABASE TO docbench;
GRANT SELECT ON V_$SESSION TO docbench;
GRANT SELECT ON V_$SQLAREA TO docbench;
GRANT EXECUTE ON DBMS_WORKLOAD_REPOSITORY TO docbench;
```

#### Enable ORDS MongoDB API (Required for $lookup vs $sql tests)

The Oracle MongoDB API requires ORDS (Oracle REST Data Services) configured with MongoDB API listener on port 27018. See [Oracle ORDS MongoDB API Documentation](https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/) for setup instructions.

### Oracle Performance Tuning (Recommended)

#### 1. Redo Log Configuration

Small redo logs cause checkpoint waits that skew results. Increase to 500MB each:

```sql
-- Connect as SYSDBA
-- Add new larger log groups
ALTER DATABASE ADD LOGFILE GROUP 4 SIZE 500M;
ALTER DATABASE ADD LOGFILE GROUP 5 SIZE 500M;
ALTER DATABASE ADD LOGFILE GROUP 6 SIZE 500M;

-- Switch logs and drop old small groups
ALTER SYSTEM SWITCH LOGFILE;
ALTER SYSTEM CHECKPOINT;
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

Edit `config/local.properties` with your database connection details:

```properties
# MongoDB Configuration
# Use replicaSet parameter for durability testing (j:true support)
mongodb.uri=mongodb://localhost:27017/testdb?replicaSet=rs0
mongodb.database=testdb

# Oracle JDBC Configuration
# Used for AWR reports, edition detection, and direct SQL benchmarks
oracle.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1
oracle.username=docbench
oracle.password=docbench123

# Oracle MongoDB API Configuration (Optional)
# Enables $sql aggregation operator tests via ORDS MongoDB API
# Port 27018 avoids conflict with native MongoDB on port 27017
# Connection string requires: authMechanism=PLAIN, authSource=$external
oracle.mongodb.uri=mongodb://docbench:docbench123@localhost:27018/docbench?authMechanism=PLAIN&authSource=$external&ssl=false&retryWrites=false&loadBalanced=true
oracle.mongodb.database=docbench
```

### Configuration Parameters Reference

| Property | Required | Description |
|----------|----------|-------------|
| `mongodb.uri` | Yes | MongoDB connection string with `replicaSet` parameter |
| `mongodb.database` | Yes | MongoDB database name for benchmark collections |
| `oracle.url` | Yes | Oracle JDBC thin driver URL |
| `oracle.username` | Yes | Oracle database username |
| `oracle.password` | Yes | Oracle database password |
| `oracle.mongodb.uri` | No* | ORDS MongoDB API connection string |
| `oracle.mongodb.database` | No* | Oracle database name for MongoDB API |

*Required only for `$lookup vs $sql` benchmark tests

### Important Connection String Notes

**MongoDB URI:**
- Must include `?replicaSet=rs0` for single-member replica set
- Enables `j:true` write concern for durability parity

**Oracle MongoDB API URI:**
- Requires `authMechanism=PLAIN` (database authentication)
- Requires `authSource=$external` (external authentication source)
- Requires `loadBalanced=true` (ORDS load balancer mode)
- Use port 27018 to avoid conflict with native MongoDB

---

## Running Benchmarks

### Run All Benchmarks

```bash
./gradlew integrationTest --rerun-tasks
```

### Run Specific Test Suites

```bash
# Client-side field access (O(n) vs O(1))
./gradlew integrationTest --tests "*BsonVsOsonClientSideTest" --rerun-tasks

# Client-side access scaling analysis
./gradlew integrationTest --tests "*ClientSideAccessScalingTest" --rerun-tasks

# Server-side updates (with AWR reports)
./gradlew integrationTest --tests "*ServerSideUpdateTest" --rerun-tasks

# Update efficiency analysis
./gradlew integrationTest --tests "*UpdateEfficiencyTest" --rerun-tasks

# Deserialization overhead analysis
./gradlew integrationTest --tests "*DeserializationOverheadTest" --rerun-tasks

# $lookup vs SQL JOIN (triple comparison)
./gradlew integrationTest --tests "*LookupVsSqlJoinTest" --rerun-tasks

# Oracle MongoDB API SQL test
./gradlew integrationTest --tests "*OracleMongoApiSqlTest" --rerun-tasks
```

### Run Individual Test Methods

```bash
# Run specific test method by pattern
./gradlew integrationTest --tests "*LookupVsSqlJoinTest.A1*" --rerun-tasks

# Run multiple specific tests
./gradlew integrationTest --tests "*LookupVsSqlJoinTest.A1*" --tests "*LookupVsSqlJoinTest.A2*" --rerun-tasks
```

### Available Test Classes

| Test Class | Description |
|------------|-------------|
| `BsonVsOsonClientSideTest` | Client-side field access O(n) vs O(1) comparison |
| `ClientSideAccessScalingTest` | Field position scaling analysis |
| `ServerSideUpdateTest` | Server-side update operations with AWR |
| `UpdateEfficiencyTest` | Update operation efficiency analysis |
| `DeserializationOverheadTest` | Deserialization break-even analysis |
| `LookupVsSqlJoinTest` | $lookup vs SQL JOIN triple comparison |
| `OracleMongoApiSqlTest` | Oracle MongoDB API $sql operator tests |

### Test Output

Test results are displayed in the console and written to:
- `reports/*.html` - Interactive HTML reports
- `build/reports/awr/*.html` - Oracle AWR reports per test

---

## AWR Report Generation

### Prerequisites

AWR reports require Oracle privileges:

```sql
GRANT SELECT ON V_$INSTANCE TO docbench;
GRANT SELECT ON V_$DATABASE TO docbench;
GRANT EXECUTE ON DBMS_WORKLOAD_REPOSITORY TO docbench;
```

### Generated Reports

After running `ServerSideUpdateTest` or `LookupVsSqlJoinTest`, AWR reports are saved to `build/reports/awr/`:

| Report Pattern | Description |
|----------------|-------------|
| `awr_A*_*.html` | Baseline join test AWR reports |
| `awr_*_baseline.html` | Protocol overhead baseline |
| `awr_*_single_field.html` | Single field update tests |
| `awr_*_large_doc_*.html` | Large document update tests |

---

## Understanding Results

### Output Files

| File | Description |
|------|-------------|
| `reports/triple_comparison_report.html` | $lookup vs SQL JOIN interactive report |
| `reports/lookup_vs_sql_report.html` | Legacy comparison report |
| `build/reports/awr/*.html` | Oracle AWR reports per test |

### Console Output Format

```
Test Case               - MongoDB:     X ns | Oracle API:     Y ns | Oracle JDBC:     Z ns
```

- Values in nanoseconds (ns) or milliseconds (ms)
- Lower values indicate better performance
- Winner indicated by ratio comparison

---

## Troubleshooting

### MongoDB Replica Set Issues

```
MongoServerError: not primary
```

**Solution**: Verify replica set is initialized:
```bash
docker exec mongodb mongosh --eval "rs.status()"
```

### MongoDB Connection Issues

```
MongoTimeoutException: Timed out after 30000 ms
```

**Solution**: Verify MongoDB is running and accepting connections:
```bash
docker exec mongodb mongosh --eval "db.runCommand({ping: 1})"
```

### Oracle Connection Issues

```
ORA-12514: TNS:listener does not currently know of service requested
```

**Solution**: Verify Oracle service name:
```bash
docker exec oracle-free lsnrctl status
```

### Oracle MongoDB API Issues

```
MongoSecurityException: Authentication failed
```

**Solution**: Verify ORDS MongoDB API is configured and running on port 27018:
```bash
curl http://localhost:8181/ords/
```

### Oracle Checkpoint Waits

Check for checkpoint waits affecting benchmark accuracy:
```sql
SELECT event, total_waits FROM v$system_event WHERE event LIKE '%checkpoint%';
```

**Solution**: Increase redo log size to 500MB each (see Oracle Performance Tuning).

---

## License

MIT License - see [LICENSE](LICENSE) file.

---

## References

- [MongoDB Write Concern Documentation](https://www.mongodb.com/docs/manual/reference/write-concern/)
- [MongoDB Journaling Documentation](https://www.mongodb.com/docs/manual/core/journaling/)
- [MongoDB Replica Set for Testing](https://www.mongodb.com/docs/manual/tutorial/deploy-replica-set-for-testing/)
- [Oracle JSON_TRANSFORM Documentation](https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/JSON_TRANSFORM.html)
- [Oracle ORDS MongoDB API Documentation](https://docs.oracle.com/en/database/oracle/oracle-rest-data-services/)
