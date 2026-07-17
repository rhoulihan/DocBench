# Vector Search Benchmark Expansion - Implementation Plan

## Overview

This plan extends the VectorSearchBenchmarkTest suite with comprehensive tests covering:
- Recall accuracy measurement
- Filtered vector search
- Hybrid search (vector + full-text)
- Quantization performance
- Scale and concurrency tests
- Index type comparisons
- Multi-stage retrieval pipelines
- RAG latency requirements
- Multi-tenancy isolation
- Distance metric comparisons

All tests will:
- Capture AWR reports (before/after snapshots) **[Enterprise Edition only]**
- Generate SQL Monitor reports for Oracle queries **[Enterprise Edition only]**
- Support configurable protocols: MongoDB Native, Oracle $sql, Oracle JDBC
- Generate comprehensive HTML reports with charts
- Respect Oracle Free edition resource limits

---

## Oracle 23ai Free Edition Limitations

### Resource Constraints

| Resource | Free Limit | Enterprise | Impact on Tests |
|----------|------------|------------|-----------------|
| **CPU** | 2 foreground | Unlimited | High concurrency tests limited |
| **RAM (SGA+PGA)** | 2 GB | Configurable | HNSW indexes at scale limited |
| **User Data** | 12 GB | Unlimited | 1M scale tests borderline |
| **AWR Reports** | ✓ Available | ✓ Available | Full support |
| **SQL Monitor** | ✓ Available | ✓ Available | Full support |

**Note:** Oracle ADB-Free container includes AWR and SQL Monitor capabilities.

### HNSW Vector Index Memory Requirements

Formula: `1.3 × vectors × dimensions × 4 bytes (float32)`

| Scale | 384-dim | 768-dim | 1536-dim | Fits in 2GB? |
|-------|---------|---------|----------|--------------|
| 1K | ~2 MB | ~4 MB | ~8 MB | ✓ |
| 10K | ~20 MB | ~40 MB | ~80 MB | ✓ |
| 100K | ~200 MB | ~400 MB | ~800 MB | ✓ (tight) |
| 1M | ~2 GB | ~4 GB | ~8 GB | ✗ |

### Storage Requirements

| Scale | Vectors (384-dim) | JSON Data | Total | Fits in 12GB? |
|-------|-------------------|-----------|-------|---------------|
| 10K | ~15 MB | ~10 MB | ~25 MB | ✓ |
| 100K | ~150 MB | ~100 MB | ~250 MB | ✓ |
| 1M | ~1.5 GB | ~1 GB | ~2.5 GB | ✓ |

### Edition Detection

```java
/**
 * Detect Oracle edition and available features
 */
public record OracleEditionInfo(
    String edition,           // "FREE", "ADB_FREE", "ENTERPRISE", "STANDARD"
    boolean isEnterprise,
    boolean awrAvailable,     // Available in ADB-Free and Enterprise
    boolean sqlMonitorAvailable, // Available in ADB-Free and Enterprise
    long maxMemoryMB,
    long maxStorageGB,
    int maxCpus
) {
    public static OracleEditionInfo detect(Connection conn) throws SQLException {
        String edition = "UNKNOWN";
        boolean isAdbFree = false;

        // Check for ADB-Free first (has different capabilities than standard Free)
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT SYS_CONTEXT('USERENV', 'CLOUD_SERVICE') FROM dual")) {
            if (rs.next()) {
                String cloudService = rs.getString(1);
                if (cloudService != null && cloudService.contains("ATP")) {
                    edition = "ADB_FREE";
                    isAdbFree = true;
                }
            }
        } catch (SQLException ignored) {}

        // Fallback to banner check
        if (!isAdbFree) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT banner FROM v$version WHERE banner LIKE '%Edition%'")) {
                if (rs.next()) {
                    String banner = rs.getString(1);
                    if (banner.contains("Free")) edition = "FREE";
                    else if (banner.contains("Enterprise")) edition = "ENTERPRISE";
                    else if (banner.contains("Standard")) edition = "STANDARD";
                }
            }
        }

        boolean isEnterprise = "ENTERPRISE".equals(edition);
        boolean isFree = "FREE".equals(edition) || "ADB_FREE".equals(edition);

        // ADB-Free includes AWR and SQL Monitor
        boolean hasAwrSqlMonitor = isEnterprise || isAdbFree;

        return new OracleEditionInfo(
            edition,
            isEnterprise,
            hasAwrSqlMonitor,  // AWR available in ADB-Free and Enterprise
            hasAwrSqlMonitor,  // SQL Monitor available in ADB-Free and Enterprise
            isFree ? 2048 : Long.MAX_VALUE,
            isFree ? 12 : Long.MAX_VALUE,
            isFree ? 2 : Integer.MAX_VALUE
        );
    }
}
```

### MongoDB Atlas Limitations (Local Dev)

MongoDB Atlas local development environment (used via `mongodb-atlas-local` Docker image) has similar considerations:

| Feature | Local Dev | Atlas Cloud |
|---------|-----------|-------------|
| Vector Search | ✓ Full support | ✓ Full support |
| $vectorSearch | ✓ | ✓ |
| numCandidates max | ~10,000 | Configurable |
| Index size | Memory limited | Configurable |

### Test Conditional Execution Matrix

Tests are categorized by resource requirements and edition compatibility:

| Test | Free Edition | Enterprise | MongoDB Local | MongoDB Atlas |
|------|--------------|------------|---------------|---------------|
| **VS3 (Recall)** | ✓ | ✓ | ✓ | ✓ |
| **VS4 (Filtered)** | ✓ | ✓ | ✓ | ✓ |
| **VS5 (Hybrid)** | ✓ | ✓ | ✓ | ✓ |
| **VS6 (Quantization)** | ✓ | ✓ | ✗ (no int8) | ✓ |
| **VS7_1K-10K (Scale)** | ✓ | ✓ | ✓ | ✓ |
| **VS7_100K (Scale)** | ⚠️ IVF only | ✓ | ✓ | ✓ |
| **VS7_1M (Scale)** | ✗ | ✓ | ⚠️ | ✓ |
| **VS8_1-10 (Concurrency)** | ✓ | ✓ | ✓ | ✓ |
| **VS8_50-100 (Concurrency)** | ⚠️ Limited | ✓ | ✓ | ✓ |
| **VS9 (HNSW Index)** | ⚠️ 10K max | ✓ | N/A | N/A |
| **VS9 (IVF Index)** | ✓ | ✓ | N/A | N/A |
| **AWR Reports** | ✓ | ✓ | N/A | N/A |
| **SQL Monitor** | ✓ | ✓ | N/A | N/A |

Legend: ✓ = Full support, ⚠️ = Limited/Conditional, ✗ = Not supported

**Resource-based limitations (Free Edition):**
- HNSW indexes: Limited to ~10K vectors due to 2GB memory constraint
- Concurrency: 50+ clients will hit 2 CPU bottleneck
- Scale: 1M vectors not recommended (memory pressure)

### Configuration Properties

```properties
# config/local.properties additions

# Edition detection (auto-detected if not specified)
oracle.edition=FREE
# oracle.edition=ENTERPRISE

# Feature flags (auto-set based on edition)
oracle.awr.enabled=false
oracle.sqlmonitor.enabled=false
oracle.hnsw.max.vectors=10000
oracle.max.concurrency=2

# MongoDB edition
mongodb.edition=LOCAL
# mongodb.edition=ATLAS

# Scale test limits
test.scale.max.accounts=100000
test.concurrency.max.clients=10
```

## TDD Methodology

### Test-First Development Cycle
```
1. Write failing unit test (Red)
2. Write minimal implementation to pass (Green)
3. Refactor for quality (Refactor)
4. Write integration test
5. Verify with real databases
```

### Test Naming Convention
```
T{category}_{number}: Unit test for component
VS{category}_{config}: Integration benchmark test
```

---

## Phase 1: Infrastructure & Data Model

### 1.1 Enhanced Data Model

#### Account Document Schema
```java
public record AccountDocument(
    String id,
    String accountId,
    String tenantId,           // NEW: Multi-tenancy
    String holderName,
    String email,
    String accountType,        // CHECKING, SAVINGS, MONEY_MARKET, CD
    double balance,
    String region,             // NORTHEAST, SOUTHEAST, MIDWEST, SOUTHWEST, WEST
    String state,              // NEW: 2-letter state code
    String city,               // NEW: City name
    Date openedDate,
    Date lastActivityDate,     // NEW: For recency filters
    double riskScore,          // NEW: 0.0-1.0 for range queries
    List<String> tags,         // NEW: For array filters
    String description,        // NEW: For full-text/hybrid search
    double[] embedding,        // float32 vector
    byte[] embeddingInt8,      // NEW: Quantized int8 (optional)
    String embeddingModel      // NEW: Model identifier
) {}
```

#### Oracle Table DDL
```sql
CREATE TABLE benchmark_accounts_vec (
    id VARCHAR2(100) PRIMARY KEY,
    tenant_id VARCHAR2(50),
    data JSON,
    embedding VECTOR(384, FLOAT32),
    embedding_int8 VECTOR(384, INT8),
    description CLOB,
    -- Indexed columns for filtered search
    region VARCHAR2(20),
    account_type VARCHAR2(20),
    balance NUMBER(18,2),
    risk_score NUMBER(5,4),
    last_activity_date DATE
);

-- Functional indexes for filtered search
CREATE INDEX idx_accounts_region ON benchmark_accounts_vec(region);
CREATE INDEX idx_accounts_type ON benchmark_accounts_vec(account_type);
CREATE INDEX idx_accounts_balance ON benchmark_accounts_vec(balance);
CREATE INDEX idx_accounts_risk ON benchmark_accounts_vec(risk_score);
CREATE INDEX idx_accounts_tenant ON benchmark_accounts_vec(tenant_id);

-- Vector indexes
CREATE VECTOR INDEX idx_accounts_hnsw ON benchmark_accounts_vec(embedding)
    ORGANIZATION INMEMORY NEIGHBOR GRAPH
    DISTANCE COSINE
    WITH TARGET ACCURACY 95;

CREATE VECTOR INDEX idx_accounts_ivf ON benchmark_accounts_vec(embedding)
    ORGANIZATION NEIGHBOR PARTITIONS
    DISTANCE COSINE
    WITH TARGET ACCURACY 95;

-- Full-text index for hybrid search
CREATE INDEX idx_accounts_text ON benchmark_accounts_vec(description)
    INDEXTYPE IS CTXSYS.CONTEXT;
```

#### MongoDB Indexes
```javascript
// Vector search index
{
  "name": "vector_index",
  "type": "vectorSearch",
  "definition": {
    "fields": [{
      "type": "vector",
      "path": "embedding",
      "numDimensions": 384,
      "similarity": "cosine"
    }]
  }
}

// Compound indexes for filtered search
db.benchmark_accounts_vec.createIndex({ "region": 1, "accountType": 1 })
db.benchmark_accounts_vec.createIndex({ "tenantId": 1 })
db.benchmark_accounts_vec.createIndex({ "balance": 1 })
db.benchmark_accounts_vec.createIndex({ "riskScore": 1 })

// Text index for hybrid search
db.benchmark_accounts_vec.createIndex({ "description": "text", "tags": "text" })
```

### 1.2 TDD Unit Tests for Data Model

```java
// File: src/integrationTest/java/com/docbench/benchmark/VectorSearchBenchmarkTest.java

// ============================================================================
// T1xx: Data Model Unit Tests
// ============================================================================

@Test
@Order(100)
@DisplayName("T100: Generate enhanced account document with all fields")
void testT100_enhancedAccountDocument() {
    AccountDocument doc = generateEnhancedAccountDocument("ACC_TEST_001", DIM_384);

    assertNotNull(doc.tenantId());
    assertNotNull(doc.state());
    assertNotNull(doc.city());
    assertNotNull(doc.lastActivityDate());
    assertTrue(doc.riskScore() >= 0.0 && doc.riskScore() <= 1.0);
    assertNotNull(doc.tags());
    assertFalse(doc.tags().isEmpty());
    assertNotNull(doc.description());
    assertTrue(doc.description().length() > 50);
}

@Test
@Order(101)
@DisplayName("T101: Generate int8 quantized embedding")
void testT101_quantizedEmbedding() {
    double[] embedding = generateNormalizedEmbedding(DIM_384, random);
    byte[] quantized = quantizeToInt8(embedding);

    assertEquals(DIM_384, quantized.length);
    // Verify quantization preserves relative distances
    double[] reconstructed = dequantizeFromInt8(quantized);
    double cosineSim = cosineSimilarity(embedding, reconstructed);
    assertTrue(cosineSim > 0.95, "Quantization should preserve >95% similarity");
}

@Test
@Order(102)
@DisplayName("T102: Create Oracle tables with all indexes")
void testT102_createOracleTablesWithIndexes() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    createEnhancedOracleAccountsTable(DIM_384);

    // Verify all indexes exist
    List<String> indexes = getOracleIndexes(ACCOUNTS_TABLE);
    assertTrue(indexes.contains("IDX_ACCOUNTS_REGION"));
    assertTrue(indexes.contains("IDX_ACCOUNTS_HNSW") || indexes.contains("IDX_ACCOUNTS_IVF"));
}

@Test
@Order(103)
@DisplayName("T103: Create MongoDB indexes including vector and text")
void testT103_createMongoIndexes() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    createEnhancedMongoIndexes(DIM_384);

    List<String> indexes = getMongoIndexNames(ACCOUNTS_COLLECTION);
    assertTrue(indexes.stream().anyMatch(n -> n.contains("vector")));
    assertTrue(indexes.stream().anyMatch(n -> n.contains("text")));
}
```

---

## Phase 2: Test Configuration Framework

### 2.1 Protocol Configuration

```java
/**
 * Enum defining available test protocols
 */
public enum TestProtocol {
    MONGODB_NATIVE("MongoDB Native", true),
    ORACLE_API("Oracle MongoDB API ($sql)", true),
    ORACLE_SQL("Oracle $sql Aggregation", true),
    ORACLE_JDBC("Oracle JDBC", true);

    private final String displayName;
    private final boolean enabledByDefault;

    // Constructor and getters...
}

/**
 * Test configuration record
 */
public record TestConfig(
    String testId,
    String description,
    Set<TestProtocol> protocols,
    int accountCount,
    int dimensions,
    int topK,
    int warmupIterations,
    int measurementIterations,
    Map<String, Object> parameters
) {
    public static TestConfig defaults(String testId, String description) {
        return new TestConfig(
            testId, description,
            EnumSet.allOf(TestProtocol.class),
            10_000, 384, 10, 5, 20,
            new HashMap<>()
        );
    }

    public TestConfig withProtocols(TestProtocol... protocols) {
        return new TestConfig(testId, description,
            EnumSet.copyOf(Arrays.asList(protocols)),
            accountCount, dimensions, topK,
            warmupIterations, measurementIterations, parameters);
    }

    public TestConfig withParameter(String key, Object value) {
        Map<String, Object> newParams = new HashMap<>(parameters);
        newParams.put(key, value);
        return new TestConfig(testId, description, protocols,
            accountCount, dimensions, topK,
            warmupIterations, measurementIterations, newParams);
    }
}
```

### 2.2 Results Storage

```java
/**
 * Comprehensive benchmark result record
 */
public record BenchmarkResult(
    String testId,
    String testCategory,
    String description,
    Map<TestProtocol, ProtocolResult> protocolResults,
    String awrReportPath,
    LocalDateTime timestamp
) {}

public record ProtocolResult(
    TestProtocol protocol,
    boolean executed,
    long avgLatencyNanos,
    long minLatencyNanos,
    long maxLatencyNanos,
    long p50LatencyNanos,
    long p95LatencyNanos,
    long p99LatencyNanos,
    double throughputQps,
    double recallRate,           // For recall tests
    int resultCount,
    String queryText,            // SQL or pipeline JSON
    String explainPlan,
    String sqlMonitorPath,
    String errorMessage
) {}
```

### 2.3 TDD Unit Tests for Configuration

```java
// ============================================================================
// T2xx: Configuration Framework Unit Tests
// ============================================================================

@Test
@Order(200)
@DisplayName("T200: TestConfig builder creates valid configuration")
void testT200_testConfigBuilder() {
    TestConfig config = TestConfig.defaults("VS3_RECALL_10", "Recall@10 test")
        .withProtocols(TestProtocol.MONGODB_NATIVE, TestProtocol.ORACLE_JDBC)
        .withParameter("recallK", 10)
        .withParameter("exactSearch", true);

    assertEquals(2, config.protocols().size());
    assertTrue(config.protocols().contains(TestProtocol.MONGODB_NATIVE));
    assertFalse(config.protocols().contains(TestProtocol.ORACLE_API));
    assertEquals(10, config.parameters().get("recallK"));
}

@Test
@Order(201)
@DisplayName("T201: BenchmarkResult stores all protocol results")
void testT201_benchmarkResultStorage() {
    Map<TestProtocol, ProtocolResult> results = new EnumMap<>(TestProtocol.class);
    results.put(TestProtocol.MONGODB_NATIVE, createMockProtocolResult(5_000_000));
    results.put(TestProtocol.ORACLE_JDBC, createMockProtocolResult(3_000_000));

    BenchmarkResult result = new BenchmarkResult(
        "VS1_384_10K", "VS1", "Vector search test",
        results, "reports/awr/VS1_384_10K.html", LocalDateTime.now()
    );

    assertEquals(2, result.protocolResults().size());
    assertTrue(result.protocolResults().get(TestProtocol.ORACLE_JDBC).avgLatencyNanos()
               < result.protocolResults().get(TestProtocol.MONGODB_NATIVE).avgLatencyNanos());
}

@Test
@Order(202)
@DisplayName("T202: Latency percentile calculation is accurate")
void testT202_percentileCalculation() {
    long[] latencies = new long[100];
    for (int i = 0; i < 100; i++) {
        latencies[i] = (i + 1) * 1_000_000L; // 1ms to 100ms
    }

    LatencyStats stats = calculateLatencyStats(latencies);

    assertEquals(50_500_000L, stats.avg(), 500_000); // ~50.5ms avg
    assertEquals(50_000_000L, stats.p50(), 1_000_000); // ~50ms p50
    assertEquals(95_000_000L, stats.p95(), 1_000_000); // ~95ms p95
    assertEquals(99_000_000L, stats.p99(), 1_000_000); // ~99ms p99
}
```

---

## Phase 3: AWR & SQL Monitor Infrastructure

### 3.1 Edition-Aware Feature Manager

```java
/**
 * Manages Oracle edition-specific features with graceful degradation
 */
public class OracleFeatureManager {
    private final Connection connection;
    private OracleEditionInfo editionInfo;
    private boolean awrEnabled;
    private boolean sqlMonitorEnabled;

    public void initialize() throws SQLException {
        this.editionInfo = OracleEditionInfo.detect(connection);

        // AWR and SQL Monitor require Enterprise Edition + Diagnostics Pack
        this.awrEnabled = editionInfo.isEnterprise() &&
            Boolean.parseBoolean(System.getProperty("oracle.awr.enabled", "true"));
        this.sqlMonitorEnabled = editionInfo.isEnterprise() &&
            Boolean.parseBoolean(System.getProperty("oracle.sqlmonitor.enabled", "true"));

        System.out.println("  Oracle Edition: " + editionInfo.edition());
        System.out.println("  AWR Reports: " + (awrEnabled ? "ENABLED" : "DISABLED (requires Enterprise Edition)"));
        System.out.println("  SQL Monitor: " + (sqlMonitorEnabled ? "ENABLED" : "DISABLED (requires Enterprise Edition)"));
    }

    public boolean isAwrEnabled() { return awrEnabled; }
    public boolean isSqlMonitorEnabled() { return sqlMonitorEnabled; }
    public OracleEditionInfo getEditionInfo() { return editionInfo; }

    /**
     * Check if a scale test should run on this edition
     */
    public boolean canRunScaleTest(int accountCount, int dimensions, boolean requiresHnsw) {
        if (editionInfo.isEnterprise()) return true;

        // Free edition limits
        long hnswMemoryMB = (long) (1.3 * accountCount * dimensions * 4 / 1_000_000);
        if (requiresHnsw && hnswMemoryMB > editionInfo.maxMemoryMB() * 0.5) {
            System.out.println("  Skipping: HNSW index requires ~" + hnswMemoryMB +
                "MB, exceeds Free edition limit");
            return false;
        }

        return true;
    }

    /**
     * Check if concurrency test should run on this edition
     */
    public boolean canRunConcurrencyTest(int concurrentClients) {
        if (editionInfo.isEnterprise()) return true;

        if (concurrentClients > editionInfo.maxCpus() * 5) {
            System.out.println("  Skipping: " + concurrentClients +
                " clients exceeds practical limit for " + editionInfo.maxCpus() + " CPUs");
            return false;
        }
        return true;
    }
}
```

### 3.2 AWR Snapshot Management (Enterprise Edition Only)

```java
/**
 * AWR snapshot and report management
 * NOTE: Requires Oracle Enterprise Edition with Diagnostics Pack license
 */
public class AwrManager {
    private final Connection connection;
    private final OracleFeatureManager featureManager;
    private long dbId;
    private int instanceNumber;

    public AwrManager(Connection connection, OracleFeatureManager featureManager) {
        this.connection = connection;
        this.featureManager = featureManager;
    }

    public void initialize() throws SQLException {
        if (!featureManager.isAwrEnabled()) {
            System.out.println("  AWR Manager: Disabled (Free Edition)");
            return;
        }

        // Get database ID and instance number
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT dbid, instance_number FROM v$database, v$instance")) {
            if (rs.next()) {
                dbId = rs.getLong("dbid");
                instanceNumber = rs.getInt("instance_number");
            }
        }
    }

    /**
     * Create AWR snapshot if enabled, returns -1 if disabled
     */
    public long createSnapshot() throws SQLException {
        if (!featureManager.isAwrEnabled()) {
            return -1; // AWR disabled
        }

        try (CallableStatement cs = connection.prepareCall(
                "BEGIN ? := DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT(); END;")) {
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.execute();
            return cs.getLong(1);
        }
    }

    /**
     * Generate AWR report if enabled, returns null if disabled
     */
    public String generateAwrReport(long beginSnap, long endSnap, String outputPath)
            throws SQLException, IOException {
        if (!featureManager.isAwrEnabled() || beginSnap < 0 || endSnap < 0) {
            return null; // AWR disabled or no valid snapshots
        }

        StringBuilder report = new StringBuilder();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(String.format(
                 "SELECT output FROM TABLE(DBMS_WORKLOAD_REPOSITORY.AWR_REPORT_HTML(%d, %d, %d, %d))",
                 dbId, instanceNumber, beginSnap, endSnap))) {
            while (rs.next()) {
                report.append(rs.getString(1));
            }
        }
        Files.writeString(Path.of(outputPath), report.toString());
        return outputPath;
    }
}
```

### 3.3 SQL Monitor Report Capture (Enterprise Edition Only)

```java
/**
 * SQL Monitor report capture for detailed execution analysis
 * NOTE: Requires Oracle Enterprise Edition with Diagnostics Pack license
 */
public class SqlMonitorManager {
    private final Connection connection;
    private final OracleFeatureManager featureManager;

    public SqlMonitorManager(Connection connection, OracleFeatureManager featureManager) {
        this.connection = connection;
        this.featureManager = featureManager;
    }

    /**
     * Capture SQL Monitor report if enabled, returns null if disabled
     */
    public String captureSqlMonitorReport(String sqlId, String outputPath)
            throws SQLException, IOException {
        if (!featureManager.isSqlMonitorEnabled()) {
            return null; // SQL Monitor disabled
        }

        String report = null;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(String.format(
                 "SELECT DBMS_SQL_MONITOR.REPORT_SQL_MONITOR(" +
                 "  sql_id => '%s', " +
                 "  type => 'HTML', " +
                 "  report_level => 'ALL') AS report FROM dual", sqlId))) {
            if (rs.next()) {
                report = rs.getString("report");
            }
        }
        if (report != null) {
            Files.writeString(Path.of(outputPath), report);
        }
        return outputPath;
    }

    /**
     * Capture SQL Monitor for a statement if enabled
     */
    public String captureSqlMonitorForStatement(String sqlText, String outputPath)
            throws SQLException, IOException {
        if (!featureManager.isSqlMonitorEnabled()) {
            return null;
        }

        String sqlId = findSqlId(sqlText);
        if (sqlId != null) {
            return captureSqlMonitorReport(sqlId, outputPath);
        }
        return null;
    }

    private String findSqlId(String sqlText) throws SQLException {
        String pattern = sqlText.substring(0, Math.min(50, sqlText.length()))
            .replace("'", "''");
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sql_id FROM v$sql WHERE sql_text LIKE ? " +
                "ORDER BY last_active_time DESC FETCH FIRST 1 ROW ONLY")) {
            ps.setString(1, pattern + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("sql_id");
                }
            }
        }
        return null;
    }
}

/**
 * Fallback execution plan capture (works on all editions)
 */
public class ExecutionPlanManager {
    private final Connection connection;

    /**
     * Capture EXPLAIN PLAN output - works on all Oracle editions
     */
    public String captureExplainPlan(String sql) throws SQLException {
        // Use EXPLAIN PLAN which is available in all editions
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("EXPLAIN PLAN FOR " + sql);
        }

        StringBuilder plan = new StringBuilder();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
        }
        return plan.toString();
    }

    /**
     * Capture V$SQL_PLAN for executed query - works on all editions
     */
    public String captureRuntimePlan(String sqlId) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(String.format(
                 "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('%s', NULL, 'ALLSTATS LAST'))",
                 sqlId))) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
        }
        return plan.toString();
    }
}
```

### 3.3 TDD Unit Tests for AWR/SQL Monitor

```java
// ============================================================================
// T3xx: AWR & SQL Monitor Unit Tests
// ============================================================================

@Test
@Order(300)
@DisplayName("T300: AWR manager initializes with database info")
void testT300_awrManagerInit() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    AwrManager awr = new AwrManager(oracleJdbcConnection);
    awr.initialize();

    assertTrue(awr.getDbId() > 0);
    assertTrue(awr.getInstanceNumber() > 0);
}

@Test
@Order(301)
@DisplayName("T301: AWR snapshot creation returns valid snap ID")
void testT301_awrSnapshotCreation() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    AwrManager awr = new AwrManager(oracleJdbcConnection);
    awr.initialize();

    long snapId = awr.createSnapshot();
    assertTrue(snapId > 0);
}

@Test
@Order(302)
@DisplayName("T302: SQL Monitor captures execution details")
void testT302_sqlMonitorCapture() throws SQLException, IOException {
    Assumptions.assumeTrue(oracleVectorSupported);

    // Execute a monitored query
    String sql = "SELECT /*+ MONITOR */ * FROM dual";
    try (Statement stmt = oracleJdbcConnection.createStatement()) {
        stmt.execute(sql);
    }

    SqlMonitorManager monitor = new SqlMonitorManager(oracleJdbcConnection);
    String reportPath = monitor.captureSqlMonitorForStatement(
        sql, REPORTS_DIR + "/test_monitor.html");

    // Report may be null if query was too fast to monitor
    // This is expected behavior
}

@Test
@Order(303)
@DisplayName("T303: AWR report generation produces valid HTML")
void testT303_awrReportGeneration() throws SQLException, IOException {
    Assumptions.assumeTrue(oracleVectorSupported);

    AwrManager awr = new AwrManager(oracleJdbcConnection);
    awr.initialize();

    long snap1 = awr.createSnapshot();
    // Execute some work
    Thread.sleep(1000);
    long snap2 = awr.createSnapshot();

    String reportPath = awr.generateAwrReport(snap1, snap2,
        AWR_REPORT_DIR + "/test_awr.html");

    assertTrue(Files.exists(Path.of(reportPath)));
    String content = Files.readString(Path.of(reportPath));
    assertTrue(content.contains("WORKLOAD REPOSITORY"));
}
```

---

## Phase 4: Test Categories Implementation

### 4.1 Category VS3: Recall Accuracy Tests

```java
// ============================================================================
// VS3: Recall Accuracy Measurement
// ============================================================================

/**
 * VS3 Configuration
 */
private static final TestConfig VS3_CONFIG = TestConfig.defaults("VS3", "Recall Accuracy")
    .withProtocols(TestProtocol.MONGODB_NATIVE, TestProtocol.ORACLE_JDBC)
    .withParameter("recallKValues", List.of(10, 50, 100));

// TDD Unit Tests
@Test
@Order(310)
@DisplayName("T310: Compute exact KNN ground truth")
void testT310_computeGroundTruth() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    int k = 10;
    double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

    // Exact search (no index, brute force)
    List<String> groundTruth = computeExactKnn(queryVector, k);

    assertEquals(k, groundTruth.size());
    // Verify sorted by distance
    assertTrue(isValidGroundTruth(groundTruth, queryVector));
}

@Test
@Order(311)
@DisplayName("T311: Calculate recall rate correctly")
void testT311_calculateRecall() {
    List<String> groundTruth = List.of("A", "B", "C", "D", "E");
    List<String> annResults = List.of("A", "B", "X", "D", "Y");

    double recall = calculateRecall(groundTruth, annResults);

    assertEquals(0.6, recall, 0.001); // 3/5 = 60%
}

@Test
@Order(312)
@DisplayName("T312: MongoDB ENN returns exact results")
void testT312_mongoEnnExactResults() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    // ENN: numCandidates = limit (exact search)
    double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
    List<String> ennResults = executeMongoEnn(queryVector, 10);
    List<String> groundTruth = computeExactKnn(queryVector, 10);

    double recall = calculateRecall(groundTruth, ennResults);
    assertEquals(1.0, recall, 0.001); // ENN should be 100% recall
}

@Test
@Order(313)
@DisplayName("T313: Oracle exact search without vector index")
void testT313_oracleExactSearch() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

    // Use NO_VECTOR_INDEX hint for exact search
    List<String> exactResults = executeOracleExactKnn(queryVector, 10);

    assertEquals(10, exactResults.size());
}

// Integration Benchmark Tests
@Test
@Order(350)
@DisplayName("VS3_RECALL_10: Recall@10 measurement")
void testVS3_recall10() throws SQLException {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    TestConfig config = VS3_CONFIG.withParameter("recallK", 10);
    BenchmarkResult result = runRecallBenchmark(config);

    for (TestProtocol protocol : config.protocols()) {
        ProtocolResult pr = result.protocolResults().get(protocol);
        assertTrue(pr.recallRate() >= 0.85,
            protocol + " recall should be >= 85%");
    }
}

@Test
@Order(351)
@DisplayName("VS3_RECALL_50: Recall@50 measurement")
void testVS3_recall50() throws SQLException {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    TestConfig config = VS3_CONFIG.withParameter("recallK", 50);
    BenchmarkResult result = runRecallBenchmark(config);

    results.put(config.testId() + "_50", result);
}

@Test
@Order(352)
@DisplayName("VS3_RECALL_100: Recall@100 measurement")
void testVS3_recall100() throws SQLException {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    TestConfig config = VS3_CONFIG.withParameter("recallK", 100);
    BenchmarkResult result = runRecallBenchmark(config);

    results.put(config.testId() + "_100", result);
}

// Implementation Methods
private BenchmarkResult runRecallBenchmark(TestConfig config) throws SQLException {
    int k = (int) config.parameters().get("recallK");
    awrSnapshotBefore(config.testId());

    // Generate query vectors
    List<double[]> queryVectors = generateQueryVectors(config.measurementIterations(),
        config.dimensions());

    // Compute ground truth for all queries
    Map<Integer, List<String>> groundTruthMap = new HashMap<>();
    for (int i = 0; i < queryVectors.size(); i++) {
        groundTruthMap.put(i, computeExactKnn(queryVectors.get(i), k));
    }

    Map<TestProtocol, ProtocolResult> protocolResults = new EnumMap<>(TestProtocol.class);

    for (TestProtocol protocol : config.protocols()) {
        if (!isProtocolSupported(protocol)) continue;

        List<Long> latencies = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        String queryText = null;
        String explainPlan = null;

        for (int i = 0; i < config.measurementIterations(); i++) {
            double[] qv = queryVectors.get(i);
            long start = System.nanoTime();
            List<String> results = executeVectorSearch(protocol, qv, k);
            latencies.add(System.nanoTime() - start);
            recalls.add(calculateRecall(groundTruthMap.get(i), results));

            if (i == 0) {
                queryText = captureQueryText(protocol, qv, k);
                explainPlan = captureExplainPlan(protocol, qv, k);
            }
        }

        double avgRecall = recalls.stream().mapToDouble(d -> d).average().orElse(0);
        LatencyStats stats = calculateLatencyStats(latencies);

        String sqlMonitorPath = captureSqlMonitor(protocol, config.testId());

        protocolResults.put(protocol, new ProtocolResult(
            protocol, true,
            stats.avg(), stats.min(), stats.max(),
            stats.p50(), stats.p95(), stats.p99(),
            calculateQps(stats.avg()),
            avgRecall, k,
            queryText, explainPlan, sqlMonitorPath, null
        ));
    }

    awrSnapshotAfter(config.testId());
    String awrPath = generateAwrReport(config.testId());

    return new BenchmarkResult(config.testId(), "VS3", config.description(),
        protocolResults, awrPath, LocalDateTime.now());
}
```

### 4.2 Category VS4: Filtered Vector Search

```java
// ============================================================================
// VS4: Filtered Vector Search
// ============================================================================

/**
 * Filter selectivity configurations
 */
private static final Map<String, Double> FILTER_SELECTIVITIES = Map.of(
    "50PCT", 0.50,
    "10PCT", 0.10,
    "1PCT", 0.01,
    "01PCT", 0.001
);

// TDD Unit Tests
@Test
@Order(400)
@DisplayName("T400: Build MongoDB filter for region")
void testT400_buildMongoRegionFilter() {
    Document filter = buildMongoFilter(Map.of("region", "NORTHEAST"));

    assertEquals("NORTHEAST", filter.getString("region"));
}

@Test
@Order(401)
@DisplayName("T401: Build MongoDB compound filter")
void testT401_buildMongoCompoundFilter() {
    Document filter = buildMongoFilter(Map.of(
        "region", "NORTHEAST",
        "accountType", "CHECKING",
        "balance", Map.of("$gte", 50000, "$lte", 100000)
    ));

    assertTrue(filter.containsKey("region"));
    assertTrue(filter.containsKey("accountType"));
    assertTrue(filter.containsKey("balance"));
}

@Test
@Order(402)
@DisplayName("T402: Build Oracle SQL WHERE clause for filter")
void testT402_buildOracleFilterClause() {
    String whereClause = buildOracleWhereClause(Map.of(
        "region", "NORTHEAST",
        "balance", Map.of("$gte", 50000, "$lte", 100000)
    ));

    assertTrue(whereClause.contains("region = 'NORTHEAST'"));
    assertTrue(whereClause.contains("balance >= 50000"));
    assertTrue(whereClause.contains("balance <= 100000"));
}

@Test
@Order(403)
@DisplayName("T403: MongoDB $vectorSearch with preFilter")
void testT403_mongoVectorSearchWithPreFilter() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    double[] queryVector = generateNormalizedEmbedding(DIM_384, random);
    Document filter = new Document("region", "NORTHEAST");

    List<Document> results = executeMongoFilteredVectorSearch(queryVector, 10, filter);

    // All results should match filter
    for (Document doc : results) {
        assertEquals("NORTHEAST", doc.getString("region"));
    }
}

@Test
@Order(404)
@DisplayName("T404: Oracle filtered vector search via JDBC")
void testT404_oracleFilteredVectorSearch() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    double[] queryVector = generateNormalizedEmbedding(DIM_384, random);
    Map<String, Object> filters = Map.of("region", "NORTHEAST");

    List<Document> results = executeOracleFilteredVectorSearch(queryVector, 10, filters);

    for (Document doc : results) {
        assertEquals("NORTHEAST", doc.getString("region"));
    }
}

@Test
@Order(405)
@DisplayName("T405: Calculate actual filter selectivity")
void testT405_calculateFilterSelectivity() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    Map<String, Object> filters = Map.of("region", "NORTHEAST");
    double selectivity = calculateFilterSelectivity(filters);

    // 5 regions, so ~20% expected
    assertTrue(selectivity > 0.15 && selectivity < 0.25);
}

// Integration Benchmark Tests
@Test
@Order(450)
@DisplayName("VS4_FILTER_50PCT: 50% filter selectivity")
void testVS4_filter50pct() throws SQLException {
    runFilteredSearchBenchmark("VS4_FILTER_50PCT", 0.50,
        Map.of("balance", Map.of("$gte", 25000))); // ~50% above median
}

@Test
@Order(451)
@DisplayName("VS4_FILTER_10PCT: 10% filter selectivity")
void testVS4_filter10pct() throws SQLException {
    runFilteredSearchBenchmark("VS4_FILTER_10PCT", 0.10,
        Map.of("balance", Map.of("$gte", 80000))); // Top ~10%
}

@Test
@Order(452)
@DisplayName("VS4_FILTER_1PCT: 1% filter selectivity")
void testVS4_filter1pct() throws SQLException {
    runFilteredSearchBenchmark("VS4_FILTER_1PCT", 0.01,
        Map.of("balance", Map.of("$gte", 95000), "region", "NORTHEAST"));
}

@Test
@Order(453)
@DisplayName("VS4_FILTER_01PCT: 0.1% filter selectivity")
void testVS4_filter01pct() throws SQLException {
    runFilteredSearchBenchmark("VS4_FILTER_01PCT", 0.001,
        Map.of("balance", Map.of("$gte", 99000),
               "region", "NORTHEAST",
               "accountType", "MONEY_MARKET"));
}

// Implementation
private void runFilteredSearchBenchmark(String testId, double expectedSelectivity,
        Map<String, Object> filters) throws SQLException {
    TestConfig config = TestConfig.defaults(testId,
        "Filtered search at " + (expectedSelectivity * 100) + "% selectivity")
        .withParameter("filters", filters)
        .withParameter("expectedSelectivity", expectedSelectivity);

    setupBenchmarkData(config.accountCount(), config.dimensions(), 0);
    awrSnapshotBefore(testId);

    List<double[]> queryVectors = generateQueryVectors(config.measurementIterations(),
        config.dimensions());

    Map<TestProtocol, ProtocolResult> protocolResults = new EnumMap<>(TestProtocol.class);

    // MongoDB Native with preFilter
    if (mongoVectorSearchSupported && config.protocols().contains(TestProtocol.MONGODB_NATIVE)) {
        List<Long> latencies = measureFilteredMongoSearch(queryVectors, filters, config.topK());
        Document mongoFilter = buildMongoFilter(filters);
        String pipeline = buildMongoFilteredPipeline(queryVectors.get(0), config.topK(), mongoFilter);

        protocolResults.put(TestProtocol.MONGODB_NATIVE, buildProtocolResult(
            TestProtocol.MONGODB_NATIVE, latencies, pipeline));
    }

    // Oracle $sql with WHERE clause
    if (oracleMongoApiSupported && config.protocols().contains(TestProtocol.ORACLE_SQL)) {
        List<Long> latencies = measureFilteredOracleSqlSearch(queryVectors, filters, config);
        String sql = buildOracleFilteredSql(queryVectors.get(0), filters, config);
        String sqlMonitorPath = captureSqlMonitor(sql, testId + "_sql");

        protocolResults.put(TestProtocol.ORACLE_SQL, buildProtocolResult(
            TestProtocol.ORACLE_SQL, latencies, sql, sqlMonitorPath));
    }

    // Oracle JDBC with WHERE clause
    if (oracleVectorSupported && config.protocols().contains(TestProtocol.ORACLE_JDBC)) {
        List<Long> latencies = measureFilteredOracleJdbcSearch(queryVectors, filters, config);
        String sql = buildOracleJdbcFilteredSql(queryVectors.get(0), filters, config);
        String sqlMonitorPath = captureSqlMonitor(sql, testId + "_jdbc");

        protocolResults.put(TestProtocol.ORACLE_JDBC, buildProtocolResult(
            TestProtocol.ORACLE_JDBC, latencies, sql, sqlMonitorPath));
    }

    awrSnapshotAfter(testId);
    String awrPath = generateAwrReport(testId);

    BenchmarkResult result = new BenchmarkResult(testId, "VS4", config.description(),
        protocolResults, awrPath, LocalDateTime.now());
    results.put(testId, result);
}

// MongoDB filtered vector search implementation
private List<Document> executeMongoFilteredVectorSearch(double[] queryVector, int limit,
        Document preFilter) {
    List<Double> queryList = toDoubleList(queryVector);

    Document vectorSearchStage = new Document("$vectorSearch", new Document()
        .append("index", VECTOR_INDEX_NAME)
        .append("path", "embedding")
        .append("queryVector", queryList)
        .append("numCandidates", limit * 20)  // Higher for filtered search
        .append("limit", limit)
        .append("filter", preFilter));  // Pre-filter

    List<Document> results = new ArrayList<>();
    accountsCollection.aggregate(Collections.singletonList(vectorSearchStage))
        .forEach(results::add);
    return results;
}

// Oracle JDBC filtered vector search
private List<Document> executeOracleFilteredVectorSearch(double[] queryVector, int limit,
        Map<String, Object> filters) throws SQLException {
    String whereClause = buildOracleWhereClause(filters);
    String vectorStr = formatVectorString(queryVector);

    String sql = String.format("""
        SELECT id, data,
               VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
        FROM %s
        WHERE %s
        ORDER BY distance
        FETCH FIRST ? ROWS ONLY
        """, queryVector.length, ACCOUNTS_TABLE, whereClause);

    List<Document> results = new ArrayList<>();
    try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
        ps.setString(1, vectorStr);
        ps.setInt(2, limit);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Document doc = Document.parse(rs.getString("data"));
                doc.append("_id", rs.getString("id"));
                doc.append("distance", rs.getDouble("distance"));
                results.add(doc);
            }
        }
    }
    return results;
}
```

### 4.3 Category VS5: Hybrid Search

```java
// ============================================================================
// VS5: Hybrid Search (Vector + Full-Text)
// ============================================================================

// TDD Unit Tests
@Test
@Order(500)
@DisplayName("T500: Execute MongoDB full-text search")
void testT500_mongoFullTextSearch() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    String searchText = "premium international transactions";
    List<Document> results = executeMongoTextSearch(searchText, 10);

    assertFalse(results.isEmpty());
}

@Test
@Order(501)
@DisplayName("T501: Execute Oracle CONTAINS full-text search")
void testT501_oracleFullTextSearch() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    String searchText = "premium international";
    List<Document> results = executeOracleTextSearch(searchText, 10);

    // May be empty if text index not created
}

@Test
@Order(502)
@DisplayName("T502: RRF fusion combines ranked lists correctly")
void testT502_rrfFusion() {
    // Vector search results (ranked)
    List<String> vectorResults = List.of("A", "B", "C", "D", "E");
    // Text search results (ranked)
    List<String> textResults = List.of("C", "A", "F", "G", "B");

    List<String> fused = rrfFusion(vectorResults, textResults, 60); // k=60

    // A and C should be top (appear in both)
    assertTrue(fused.indexOf("A") < 3);
    assertTrue(fused.indexOf("C") < 3);
}

@Test
@Order(503)
@DisplayName("T503: Weighted linear combination fusion")
void testT503_weightedFusion() {
    Map<String, Double> vectorScores = Map.of("A", 0.95, "B", 0.90, "C", 0.85);
    Map<String, Double> textScores = Map.of("C", 0.98, "A", 0.80, "D", 0.75);

    // 0.7 weight to vector, 0.3 to text
    Map<String, Double> fused = weightedFusion(vectorScores, textScores, 0.7, 0.3);

    // A: 0.7*0.95 + 0.3*0.80 = 0.665 + 0.24 = 0.905
    // C: 0.7*0.85 + 0.3*0.98 = 0.595 + 0.294 = 0.889
    assertTrue(fused.get("A") > fused.get("C"));
}

// Integration Benchmark Tests
@Test
@Order(550)
@DisplayName("VS5_VECTOR_ONLY: Pure vector search baseline")
void testVS5_vectorOnly() throws SQLException {
    runHybridSearchBenchmark("VS5_VECTOR_ONLY", HybridMode.VECTOR_ONLY, 0.0, 1.0);
}

@Test
@Order(551)
@DisplayName("VS5_BM25_ONLY: Pure text search baseline")
void testVS5_bm25Only() throws SQLException {
    runHybridSearchBenchmark("VS5_BM25_ONLY", HybridMode.TEXT_ONLY, 1.0, 0.0);
}

@Test
@Order(552)
@DisplayName("VS5_HYBRID_RRF: RRF fusion hybrid search")
void testVS5_hybridRrf() throws SQLException {
    runHybridSearchBenchmark("VS5_HYBRID_RRF", HybridMode.RRF_FUSION, 0.5, 0.5);
}

@Test
@Order(553)
@DisplayName("VS5_HYBRID_WEIGHTED: Weighted fusion (70/30)")
void testVS5_hybridWeighted() throws SQLException {
    runHybridSearchBenchmark("VS5_HYBRID_WEIGHTED", HybridMode.WEIGHTED_FUSION, 0.3, 0.7);
}

enum HybridMode { VECTOR_ONLY, TEXT_ONLY, RRF_FUSION, WEIGHTED_FUSION }

private void runHybridSearchBenchmark(String testId, HybridMode mode,
        double textWeight, double vectorWeight) throws SQLException {
    // Implementation with AWR/SQL Monitor capture...
}

// RRF Implementation
private List<String> rrfFusion(List<String> list1, List<String> list2, int k) {
    Map<String, Double> scores = new HashMap<>();

    for (int i = 0; i < list1.size(); i++) {
        String id = list1.get(i);
        scores.merge(id, 1.0 / (k + i + 1), Double::sum);
    }

    for (int i = 0; i < list2.size(); i++) {
        String id = list2.get(i);
        scores.merge(id, 1.0 / (k + i + 1), Double::sum);
    }

    return scores.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
}
```

### 4.4 Category VS6: Quantization Performance

```java
// ============================================================================
// VS6: Quantization Performance
// ============================================================================

// TDD Unit Tests
@Test
@Order(600)
@DisplayName("T600: Quantize float32 to int8")
void testT600_quantizeToInt8() {
    double[] embedding = {0.5, -0.3, 0.8, -0.9, 0.1};
    byte[] quantized = quantizeToInt8(embedding);

    assertEquals(5, quantized.length);
    // Verify range mapping
    assertTrue(quantized[0] > 0);  // 0.5 -> positive
    assertTrue(quantized[1] < 0);  // -0.3 -> negative
}

@Test
@Order(601)
@DisplayName("T601: Quantize to binary (1-bit)")
void testT601_quantizeToBinary() {
    double[] embedding = {0.5, -0.3, 0.8, -0.9, 0.1, -0.2, 0.7, -0.1};
    byte[] binary = quantizeToBinary(embedding);

    // 8 dimensions -> 1 byte
    assertEquals(1, binary.length);
    // Positive values = 1, negative = 0: 10110010 = 0xB2
}

@Test
@Order(602)
@DisplayName("T602: Oracle VECTOR INT8 column insertion")
void testT602_oracleInt8Insertion() throws SQLException {
    Assumptions.assumeTrue(oracleVectorSupported);

    double[] embedding = generateNormalizedEmbedding(DIM_384, random);
    byte[] quantized = quantizeToInt8(embedding);

    insertOracleInt8Vector("TEST_INT8_001", quantized);

    // Verify retrieval
    byte[] retrieved = retrieveOracleInt8Vector("TEST_INT8_001");
    assertArrayEquals(quantized, retrieved);
}

@Test
@Order(603)
@DisplayName("T603: Measure quantization accuracy loss")
void testT603_quantizationAccuracyLoss() {
    // Generate 100 random embeddings
    List<double[]> embeddings = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
        embeddings.add(generateNormalizedEmbedding(DIM_384, random));
    }

    // Measure cosine similarity preservation
    double avgSimilarityInt8 = measureQuantizationSimilarityPreservation(
        embeddings, QuantizationType.INT8);
    double avgSimilarityBinary = measureQuantizationSimilarityPreservation(
        embeddings, QuantizationType.BINARY);

    assertTrue(avgSimilarityInt8 > 0.98, "INT8 should preserve >98% similarity");
    assertTrue(avgSimilarityBinary > 0.90, "Binary should preserve >90% similarity");
}

// Integration Benchmark Tests
@Test
@Order(650)
@DisplayName("VS6_FLOAT32: Float32 baseline")
void testVS6_float32() throws SQLException {
    runQuantizationBenchmark("VS6_FLOAT32", QuantizationType.FLOAT32);
}

@Test
@Order(651)
@DisplayName("VS6_INT8: Int8 scalar quantization")
void testVS6_int8() throws SQLException {
    runQuantizationBenchmark("VS6_INT8", QuantizationType.INT8);
}

@Test
@Order(652)
@DisplayName("VS6_BINARY: Binary quantization")
void testVS6_binary() throws SQLException {
    // Binary quantization works best with high dimensions
    TestConfig config = TestConfig.defaults("VS6_BINARY", "Binary quantization")
        .withParameter("dimensions", 1536);  // OpenAI dimension
    runQuantizationBenchmark("VS6_BINARY", QuantizationType.BINARY);
}

enum QuantizationType { FLOAT32, INT8, BINARY }
```

### 4.5 Category VS7: Scale Tests

```java
// ============================================================================
// VS7: Scale Tests
// ============================================================================

/**
 * Scale test configuration with edition-aware limits
 */
private static final Map<String, ScaleTestConfig> SCALE_CONFIGS = Map.of(
    "VS7_1K", new ScaleTestConfig(1_000, true, true, "All editions"),
    "VS7_10K", new ScaleTestConfig(10_000, true, true, "All editions"),
    "VS7_100K", new ScaleTestConfig(100_000, true, false, "IVF only on Free (HNSW needs ~200MB)"),
    "VS7_1M", new ScaleTestConfig(1_000_000, false, false, "Enterprise only (HNSW needs ~2GB)")
);

record ScaleTestConfig(
    int accountCount,
    boolean allowedOnFree,
    boolean hnswAllowedOnFree,
    String notes
) {}

// TDD Unit Tests
@Test
@Order(700)
@DisplayName("T700: Data generation scales linearly")
void testT700_dataGenerationPerformance() {
    long start1k = System.nanoTime();
    List<Document> docs1k = generateAccountDocuments(1_000, DIM_384);
    long time1k = System.nanoTime() - start1k;

    long start10k = System.nanoTime();
    List<Document> docs10k = generateAccountDocuments(10_000, DIM_384);
    long time10k = System.nanoTime() - start10k;

    // 10x data should take roughly 10x time (within 2x tolerance)
    assertTrue(time10k < time1k * 20);
}

@Test
@Order(701)
@DisplayName("T701: Batch insertion performance")
void testT701_batchInsertionPerformance() {
    Assumptions.assumeTrue(mongoVectorSearchSupported);

    List<Document> docs = generateAccountDocuments(1_000, DIM_384);

    // Batch insert
    long startBatch = System.nanoTime();
    accountsCollection.insertMany(docs);
    long batchTime = System.nanoTime() - startBatch;

    System.out.println("Batch insert 1K docs: " + batchTime / 1_000_000 + "ms");
    assertTrue(batchTime < 30_000_000_000L); // < 30 seconds
}

@Test
@Order(702)
@DisplayName("T702: Edition-aware scale limit check")
void testT702_editionAwareScaleLimits() {
    ScaleTestConfig config1M = SCALE_CONFIGS.get("VS7_1M");

    // Simulate Free edition check
    OracleEditionInfo freeEdition = new OracleEditionInfo(
        "FREE", false, false, false, 2048, 12, 2);

    assertFalse(config1M.allowedOnFree());
    assertFalse(config1M.hnswAllowedOnFree());

    // Simulate Enterprise edition check
    OracleEditionInfo enterpriseEdition = new OracleEditionInfo(
        "ENTERPRISE", true, true, true, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);

    // Enterprise can run all tests
    assertTrue(featureManager.canRunScaleTest(1_000_000, DIM_384, true));
}

// Integration Benchmark Tests
@Test
@Order(750)
@DisplayName("VS7_1K: 1K accounts scale test")
void testVS7_1k() throws SQLException {
    runScaleBenchmark("VS7_1K", 1_000);
}

@Test
@Order(751)
@DisplayName("VS7_10K: 10K accounts scale test")
void testVS7_10k() throws SQLException {
    runScaleBenchmark("VS7_10K", 10_000);
}

@Test
@Order(752)
@DisplayName("VS7_100K: 100K accounts scale test")
void testVS7_100k() throws SQLException {
    ScaleTestConfig config = SCALE_CONFIGS.get("VS7_100K");

    // Check Oracle edition limits
    if (oracleVectorSupported && !featureManager.getEditionInfo().isEnterprise()) {
        System.out.println("  NOTE: " + config.notes());
        // Run with IVF only, skip HNSW
        runScaleBenchmark("VS7_100K", 100_000, false); // useHnsw=false
    } else {
        runScaleBenchmark("VS7_100K", 100_000);
    }
}

@Test
@Order(753)
@DisplayName("VS7_1M: 1M accounts scale test")
@Tag("large")
@Tag("enterprise")  // Requires Enterprise Edition
void testVS7_1m() throws SQLException {
    ScaleTestConfig config = SCALE_CONFIGS.get("VS7_1M");

    // Skip on Free edition
    Assumptions.assumeTrue(
        featureManager.getEditionInfo().isEnterprise(),
        "Skipping VS7_1M: Requires Enterprise Edition (" + config.notes() + ")"
    );

    // Also check MongoDB - may need Atlas for this scale
    if (mongoVectorSearchSupported) {
        System.out.println("  NOTE: MongoDB 1M scale test may be slow on local dev");
    }

    runScaleBenchmark("VS7_1M", 1_000_000);
}

private void runScaleBenchmark(String testId, int accountCount) throws SQLException {
    TestConfig config = TestConfig.defaults(testId, accountCount + " accounts")
        .withParameter("accountCount", accountCount);

    // Setup data at scale
    long setupStart = System.nanoTime();
    setupBenchmarkData(accountCount, config.dimensions(), 0);
    long setupTime = System.nanoTime() - setupStart;

    awrSnapshotBefore(testId);

    // Run benchmark
    Map<TestProtocol, ProtocolResult> protocolResults = runStandardBenchmark(config);

    // Add setup time to metadata
    protocolResults.values().forEach(pr -> {
        // Store setup time in result metadata
    });

    awrSnapshotAfter(testId);
    String awrPath = generateAwrReport(testId);

    BenchmarkResult result = new BenchmarkResult(testId, "VS7", config.description(),
        protocolResults, awrPath, LocalDateTime.now());
    results.put(testId, result);
}
```

### 4.6 Category VS8: Concurrent Load Tests

```java
// ============================================================================
// VS8: Concurrent Load Tests
// ============================================================================

// TDD Unit Tests
@Test
@Order(800)
@DisplayName("T800: Concurrent query executor works correctly")
void testT800_concurrentExecutor() throws Exception {
    int numThreads = 4;
    int queriesPerThread = 10;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int t = 0; t < numThreads; t++) {
        executor.submit(() -> {
            try {
                for (int q = 0; q < queriesPerThread; q++) {
                    // Simulate query
                    Thread.sleep(10);
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Handle error
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    assertEquals(numThreads * queriesPerThread, successCount.get());
}

@Test
@Order(801)
@DisplayName("T801: Thread-safe latency collection")
void testT801_threadSafeLatencyCollection() throws Exception {
    ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    int numThreads = 10;
    int opsPerThread = 100;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);

    for (int t = 0; t < numThreads; t++) {
        executor.submit(() -> {
            try {
                for (int i = 0; i < opsPerThread; i++) {
                    latencies.add(System.nanoTime());
                }
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await();
    executor.shutdown();

    assertEquals(numThreads * opsPerThread, latencies.size());
}

/**
 * Concurrency test configuration with edition-aware limits
 *
 * Oracle Free Edition: 2 CPUs max
 * - 1-10 clients: Full support
 * - 50 clients: Limited (may hit CPU bottleneck)
 * - 100 clients: Not recommended (will bottleneck on 2 CPUs)
 */
private static final Map<String, ConcurrencyTestConfig> CONCURRENCY_CONFIGS = Map.of(
    "VS8_CONC_1", new ConcurrencyTestConfig(1, true, "Baseline - all editions"),
    "VS8_CONC_10", new ConcurrencyTestConfig(10, true, "Light load - all editions"),
    "VS8_CONC_50", new ConcurrencyTestConfig(50, false, "Medium load - Enterprise recommended"),
    "VS8_CONC_100", new ConcurrencyTestConfig(100, false, "Heavy load - Enterprise only")
);

record ConcurrencyTestConfig(
    int clients,
    boolean allowedOnFree,
    String notes
) {}

// Integration Benchmark Tests
@Test
@Order(850)
@DisplayName("VS8_CONC_1: Serial baseline (1 client)")
void testVS8_conc1() throws SQLException {
    runConcurrencyBenchmark("VS8_CONC_1", 1);
}

@Test
@Order(851)
@DisplayName("VS8_CONC_10: Light load (10 clients)")
void testVS8_conc10() throws SQLException {
    runConcurrencyBenchmark("VS8_CONC_10", 10);
}

@Test
@Order(852)
@DisplayName("VS8_CONC_50: Medium load (50 clients)")
@Tag("enterprise")
void testVS8_conc50() throws SQLException {
    ConcurrencyTestConfig config = CONCURRENCY_CONFIGS.get("VS8_CONC_50");

    // Check edition limits
    if (oracleVectorSupported && !featureManager.getEditionInfo().isEnterprise()) {
        System.out.println("  WARNING: " + config.notes());
        System.out.println("  Running with reduced expectations on Free Edition (2 CPUs)");
        // Still run but expect lower throughput
    }

    runConcurrencyBenchmark("VS8_CONC_50", 50);
}

@Test
@Order(853)
@DisplayName("VS8_CONC_100: Heavy load (100 clients)")
@Tag("enterprise")
void testVS8_conc100() throws SQLException {
    ConcurrencyTestConfig config = CONCURRENCY_CONFIGS.get("VS8_CONC_100");

    // Skip on Free edition - not meaningful with 2 CPUs
    if (oracleVectorSupported) {
        Assumptions.assumeTrue(
            featureManager.canRunConcurrencyTest(100),
            "Skipping VS8_CONC_100: " + config.notes()
        );
    }

    runConcurrencyBenchmark("VS8_CONC_100", 100);
}

private void runConcurrencyBenchmark(String testId, int concurrentClients)
        throws SQLException {
    TestConfig config = TestConfig.defaults(testId, concurrentClients + " concurrent clients")
        .withParameter("concurrentClients", concurrentClients)
        .withParameter("queriesPerClient", 100);

    setupBenchmarkData(config.accountCount(), config.dimensions(), 0);
    awrSnapshotBefore(testId);

    // Pre-generate query vectors
    List<double[]> queryVectors = generateQueryVectors(
        concurrentClients * 100, config.dimensions());

    Map<TestProtocol, ProtocolResult> protocolResults = new EnumMap<>(TestProtocol.class);

    for (TestProtocol protocol : config.protocols()) {
        if (!isProtocolSupported(protocol)) continue;

        ConcurrentLinkedQueue<Long> allLatencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentClients);

        long testStart = System.nanoTime();

        for (int c = 0; c < concurrentClients; c++) {
            final int clientId = c;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    for (int q = 0; q < 100; q++) {
                        int queryIdx = clientId * 100 + q;
                        double[] qv = queryVectors.get(queryIdx);

                        long start = System.nanoTime();
                        try {
                            executeVectorSearch(protocol, qv, config.topK());
                            allLatencies.add(System.nanoTime() - start);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads simultaneously
        doneLatch.await(5, TimeUnit.MINUTES);

        long testDuration = System.nanoTime() - testStart;
        executor.shutdown();

        List<Long> latencyList = new ArrayList<>(allLatencies);
        LatencyStats stats = calculateLatencyStats(latencyList);
        double qps = (double) latencyList.size() / (testDuration / 1_000_000_000.0);

        protocolResults.put(protocol, new ProtocolResult(
            protocol, true,
            stats.avg(), stats.min(), stats.max(),
            stats.p50(), stats.p95(), stats.p99(),
            qps, -1, // No recall for concurrent test
            latencyList.size(),
            null, null, null,
            errorCount.get() > 0 ? errorCount.get() + " errors" : null
        ));
    }

    awrSnapshotAfter(testId);
    String awrPath = generateAwrReport(testId);

    BenchmarkResult result = new BenchmarkResult(testId, "VS8", config.description(),
        protocolResults, awrPath, LocalDateTime.now());
    results.put(testId, result);
}
```

### 4.7-4.10 Remaining Categories (Summarized)

```java
// ============================================================================
// VS9: Index Type Comparison (Oracle HNSW vs IVF)
// ============================================================================
// Tests: VS9_NO_INDEX, VS9_HNSW_DEFAULT, VS9_HNSW_TUNED, VS9_IVF_DEFAULT, VS9_IVF_TUNED
// Focus: Compare query performance with different index configurations
// Protocols: Oracle JDBC only (index-specific)

// ============================================================================
// VS10: MongoDB numCandidates Tuning
// ============================================================================
// Tests: VS10_NC_20, VS10_NC_100, VS10_NC_200, VS10_NC_500
// Focus: Impact of numCandidates on recall and latency
// Protocols: MongoDB Native only

// ============================================================================
// VS11: Multi-Stage Retrieval Pipeline
// ============================================================================
// Tests: VS11_PIPELINE_2STAGE, VS11_PIPELINE_3STAGE
// Focus: Vector search -> Filter -> Rerank pipeline performance
// Protocols: All (simulated reranking)

// ============================================================================
// VS12: Multi-Vector Query
// ============================================================================
// Tests: VS12_MULTI_2VEC, VS12_MULTI_3VEC
// Focus: Query with multiple vectors, average or combine results
// Protocols: All

// ============================================================================
// VS13: RAG Latency Budget
// ============================================================================
// Tests: VS13_P50_50MS, VS13_P95_100MS, VS13_P99_200MS
// Focus: Pass/fail against latency SLAs
// Protocols: All

// ============================================================================
// VS14: Batch Ingestion Performance
// ============================================================================
// Tests: VS14_BATCH_100, VS14_BATCH_1000, VS14_BATCH_BULK
// Focus: Insert throughput at different batch sizes
// Protocols: All

// ============================================================================
// VS15: Multi-Tenancy
// ============================================================================
// Tests: VS15_10_TENANTS, VS15_100_TENANTS, VS15_1000_TENANTS
// Focus: Performance with tenant isolation via filtering
// Protocols: All

// ============================================================================
// VS16: Distance Metrics
// ============================================================================
// Tests: VS16_COSINE, VS16_EUCLIDEAN, VS16_DOT
// Focus: Compare different distance functions
// Protocols: Oracle JDBC (configurable), MongoDB (index-dependent)
```

---

## Phase 5: Report Generator Extension

### 5.1 Report Data Model

```java
/**
 * Complete report data structure
 */
public record BenchmarkReport(
    String title,
    LocalDateTime generatedAt,
    String executionEnvironment,
    Map<String, CategoryReport> categories,
    SummaryStatistics summary
) {}

public record CategoryReport(
    String categoryId,
    String categoryName,
    String description,
    List<BenchmarkResult> results,
    Map<TestProtocol, CategorySummary> protocolSummaries
) {}

public record CategorySummary(
    TestProtocol protocol,
    double avgLatencyMs,
    double minLatencyMs,
    double maxLatencyMs,
    double avgRecall,
    int testsExecuted,
    int testsPassed
) {}

public record SummaryStatistics(
    int totalTests,
    int passedTests,
    int failedTests,
    int skippedTests,
    Map<TestProtocol, ProtocolOverallStats> protocolStats,
    TestProtocol overallWinner
) {}
```

### 5.2 HTML Report Template Structure

```html
<!DOCTYPE html>
<html>
<head>
    <title>Vector Search Benchmark Report</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        /* Report styles */
    </style>
</head>
<body>
    <!-- Executive Summary -->
    <section id="summary">
        <h1>Vector Search Benchmark Report</h1>
        <div class="summary-cards">
            <div class="card">Total Tests: ${totalTests}</div>
            <div class="card">Passed: ${passedTests}</div>
            <div class="card">Overall Winner: ${overallWinner}</div>
        </div>
    </section>

    <!-- Protocol Comparison Chart -->
    <section id="protocol-comparison">
        <h2>Protocol Performance Comparison</h2>
        <canvas id="protocolChart"></canvas>
    </section>

    <!-- Category Sections -->
    ${categories.forEach(category -> """
    <section id="${category.id}">
        <h2>${category.name}</h2>
        <p>${category.description}</p>

        <!-- Category Chart -->
        <canvas id="chart-${category.id}"></canvas>

        <!-- Results Table -->
        <table>
            <thead>
                <tr>
                    <th>Test</th>
                    ${category.protocols.forEach(p -> "<th>${p.name}</th>")}
                    <th>Winner</th>
                </tr>
            </thead>
            <tbody>
                ${category.results.forEach(result -> """
                <tr>
                    <td>${result.testId}</td>
                    ${result.protocolResults.forEach((p, pr) -> """
                    <td>
                        ${pr.executed ? formatLatency(pr.avgLatencyNanos) : "N/A"}
                        ${pr.recallRate > 0 ? "(Recall: " + formatPercent(pr.recallRate) + ")" : ""}
                    </td>
                    """)}
                    <td>${result.winner}</td>
                </tr>
                """)}
            </tbody>
        </table>

        <!-- SQL Details Expandable -->
        ${category.results.forEach(result -> """
        <details>
            <summary>${result.testId} - Query Details</summary>
            ${result.protocolResults.forEach((p, pr) -> """
            <div class="query-details">
                <h4>${p.displayName}</h4>
                <pre>${pr.queryText}</pre>
                <h5>Explain Plan</h5>
                <pre>${pr.explainPlan}</pre>
                ${pr.sqlMonitorPath != null ?
                    "<a href='${pr.sqlMonitorPath}'>SQL Monitor Report</a>" : ""}
            </div>
            """)}
        </details>
        """)}

        <!-- AWR Report Link -->
        <a href="${category.awrReportPath}">AWR Report for ${category.name}</a>
    </section>
    """)}

    <!-- Charts JavaScript -->
    <script>
        // Chart.js initialization for all charts
        ${generateChartScripts()}
    </script>
</body>
</html>
```

### 5.3 Report Generator Implementation

```java
/**
 * HTML Report Generator
 */
public class VectorSearchReportGenerator {

    private static final String REPORT_TEMPLATE = "templates/vector_search_report.html";

    public void generateReport(Map<String, BenchmarkResult> results, String outputPath)
            throws IOException {
        // Group results by category
        Map<String, List<BenchmarkResult>> categorizedResults = results.values().stream()
            .collect(Collectors.groupingBy(BenchmarkResult::testCategory));

        // Build category reports
        Map<String, CategoryReport> categoryReports = new LinkedHashMap<>();
        for (Map.Entry<String, List<BenchmarkResult>> entry : categorizedResults.entrySet()) {
            categoryReports.put(entry.getKey(), buildCategoryReport(entry.getKey(), entry.getValue()));
        }

        // Calculate summary statistics
        SummaryStatistics summary = calculateSummaryStatistics(results.values());

        // Build full report
        BenchmarkReport report = new BenchmarkReport(
            "Vector Search Benchmark Report",
            LocalDateTime.now(),
            getExecutionEnvironment(),
            categoryReports,
            summary
        );

        // Generate HTML
        String html = renderReport(report);
        Files.writeString(Path.of(outputPath), html);
    }

    private String renderReport(BenchmarkReport report) {
        StringBuilder html = new StringBuilder();

        // Header
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>%s</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                %s
            </head>
            <body>
            """.formatted(report.title(), getStyles()));

        // Executive Summary
        html.append(renderSummarySection(report.summary()));

        // Protocol Comparison Chart
        html.append(renderProtocolComparisonChart(report));

        // Category Sections
        for (CategoryReport category : report.categories().values()) {
            html.append(renderCategorySection(category));
        }

        // Charts JavaScript
        html.append(renderChartScripts(report));

        html.append("</body></html>");
        return html.toString();
    }

    private String renderCategorySection(CategoryReport category) {
        StringBuilder section = new StringBuilder();

        section.append("""
            <section id="%s" class="category-section">
                <h2>%s</h2>
                <p>%s</p>
            """.formatted(category.categoryId(), category.categoryName(),
                         category.description()));

        // Chart canvas
        section.append("""
                <div class="chart-container">
                    <canvas id="chart-%s"></canvas>
                </div>
            """.formatted(category.categoryId()));

        // Results table
        section.append(renderResultsTable(category));

        // Query details (collapsible)
        section.append(renderQueryDetails(category));

        // AWR links
        section.append(renderAwrLinks(category));

        section.append("</section>");
        return section.toString();
    }

    private String renderResultsTable(CategoryReport category) {
        StringBuilder table = new StringBuilder();

        // Determine which protocols were used in this category
        Set<TestProtocol> usedProtocols = category.results().stream()
            .flatMap(r -> r.protocolResults().keySet().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        table.append("<table class='results-table'><thead><tr>");
        table.append("<th>Test ID</th><th>Description</th>");

        for (TestProtocol protocol : usedProtocols) {
            table.append("<th>").append(protocol.getDisplayName()).append("</th>");
        }
        table.append("<th>Winner</th></tr></thead><tbody>");

        for (BenchmarkResult result : category.results()) {
            table.append("<tr>");
            table.append("<td>").append(result.testId()).append("</td>");
            table.append("<td>").append(result.description()).append("</td>");

            TestProtocol winner = null;
            long bestLatency = Long.MAX_VALUE;

            for (TestProtocol protocol : usedProtocols) {
                ProtocolResult pr = result.protocolResults().get(protocol);
                if (pr != null && pr.executed()) {
                    table.append("<td class='latency-cell'>");
                    table.append(formatLatency(pr.avgLatencyNanos()));
                    if (pr.recallRate() > 0) {
                        table.append("<br><small>Recall: ")
                             .append(formatPercent(pr.recallRate()))
                             .append("</small>");
                    }
                    table.append("</td>");

                    if (pr.avgLatencyNanos() < bestLatency) {
                        bestLatency = pr.avgLatencyNanos();
                        winner = protocol;
                    }
                } else {
                    table.append("<td class='na-cell'>N/A</td>");
                }
            }

            table.append("<td class='winner-cell'>")
                 .append(winner != null ? winner.getDisplayName() : "-")
                 .append("</td>");
            table.append("</tr>");
        }

        table.append("</tbody></table>");
        return table.toString();
    }

    private String formatLatency(long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms < 1) {
            return String.format("%.2f \u00b5s", nanos / 1000.0);
        } else if (ms < 1000) {
            return String.format("%.2f ms", ms);
        } else {
            return String.format("%.2f s", ms / 1000.0);
        }
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }
}
```

### 5.4 TDD Tests for Report Generator

```java
// ============================================================================
// T9xx: Report Generator Unit Tests
// ============================================================================

@Test
@Order(900)
@DisplayName("T900: Report generator creates valid HTML")
void testT900_reportGeneratorCreatesHtml() throws IOException {
    Map<String, BenchmarkResult> mockResults = createMockBenchmarkResults();

    VectorSearchReportGenerator generator = new VectorSearchReportGenerator();
    String outputPath = REPORTS_DIR + "/test_report.html";
    generator.generateReport(mockResults, outputPath);

    assertTrue(Files.exists(Path.of(outputPath)));
    String content = Files.readString(Path.of(outputPath));
    assertTrue(content.contains("<!DOCTYPE html>"));
    assertTrue(content.contains("Vector Search Benchmark"));
}

@Test
@Order(901)
@DisplayName("T901: Report groups results by category")
void testT901_reportGroupsByCategory() {
    Map<String, BenchmarkResult> results = Map.of(
        "VS3_RECALL_10", createMockResult("VS3_RECALL_10", "VS3"),
        "VS3_RECALL_50", createMockResult("VS3_RECALL_50", "VS3"),
        "VS4_FILTER_10PCT", createMockResult("VS4_FILTER_10PCT", "VS4")
    );

    Map<String, List<BenchmarkResult>> grouped = groupByCategory(results);

    assertEquals(2, grouped.size());
    assertEquals(2, grouped.get("VS3").size());
    assertEquals(1, grouped.get("VS4").size());
}

@Test
@Order(902)
@DisplayName("T902: Chart data is correctly formatted")
void testT902_chartDataFormat() {
    List<BenchmarkResult> results = List.of(
        createMockResult("VS1_384_10K", "VS1", 5_000_000, 6_000_000, 7_000_000),
        createMockResult("VS1_768_10K", "VS1", 8_000_000, 9_000_000, 10_000_000)
    );

    ChartData chartData = generateChartData(results);

    assertEquals(2, chartData.labels().size());
    assertEquals(3, chartData.datasets().size()); // 3 protocols
}

@Test
@Order(903)
@DisplayName("T903: Summary statistics are accurate")
void testT903_summaryStatistics() {
    Map<String, BenchmarkResult> results = createMockBenchmarkResults();

    SummaryStatistics summary = calculateSummaryStatistics(results.values());

    assertEquals(results.size(), summary.totalTests());
    assertNotNull(summary.overallWinner());
}

@Test
@Order(904)
@DisplayName("T904: Latency formatting is correct")
void testT904_latencyFormatting() {
    assertEquals("500.00 \u00b5s", formatLatency(500_000));
    assertEquals("5.00 ms", formatLatency(5_000_000));
    assertEquals("1.50 s", formatLatency(1_500_000_000));
}

@Test
@Order(905)
@DisplayName("T905: Report includes SQL Monitor links")
void testT905_reportIncludesSqlMonitorLinks() throws IOException {
    Map<String, BenchmarkResult> results = createMockResultsWithSqlMonitor();

    VectorSearchReportGenerator generator = new VectorSearchReportGenerator();
    String outputPath = REPORTS_DIR + "/test_monitor_links.html";
    generator.generateReport(results, outputPath);

    String content = Files.readString(Path.of(outputPath));
    assertTrue(content.contains("sql_monitor"));
    assertTrue(content.contains(".html"));
}

@Test
@Order(906)
@DisplayName("T906: Report includes AWR links")
void testT906_reportIncludesAwrLinks() throws IOException {
    Map<String, BenchmarkResult> results = createMockResultsWithAwr();

    VectorSearchReportGenerator generator = new VectorSearchReportGenerator();
    String outputPath = REPORTS_DIR + "/test_awr_links.html";
    generator.generateReport(results, outputPath);

    String content = Files.readString(Path.of(outputPath));
    assertTrue(content.contains("awr"));
    assertTrue(content.contains("AWR Report"));
}
```

---

## Phase 6: Test Execution Order

### 6.1 Complete Test Order

```java
// Test execution order by @Order annotation:

// Phase 1: Infrastructure (T1xx)
// T100-T103: Data model tests

// Phase 2: Configuration (T2xx)
// T200-T202: Config framework tests

// Phase 3: AWR/SQL Monitor (T3xx)
// T300-T303: AWR and monitoring tests

// Phase 4: Category Tests
// T310-T352: VS3 Recall tests
// T400-T453: VS4 Filtered search tests
// T500-T553: VS5 Hybrid search tests
// T600-T652: VS6 Quantization tests
// T700-T753: VS7 Scale tests
// T800-T853: VS8 Concurrency tests
// T860-T869: VS9 Index type tests
// T870-T879: VS10 numCandidates tests
// T880-T889: VS11 Pipeline tests
// T890-T899: VS12-VS16 Additional tests

// Phase 5: Report Generation (T9xx)
// T900-T906: Report generator tests

// Final: Report Generation
// Order 9999: Generate final HTML report
```

### 6.2 Test Tags

```java
@Tag("benchmark")      // All benchmark tests
@Tag("integration")    // Requires database connections
@Tag("vector")         // Vector search specific
@Tag("unit")           // Unit tests (no DB required)
@Tag("large")          // Large scale tests (1M+ records)
@Tag("slow")           // Tests > 1 minute
```

---

## Appendix A: File Structure

```
src/integrationTest/java/com/docbench/benchmark/
├── VectorSearchBenchmarkTest.java      # Main test class (extended)
├── model/
│   ├── AccountDocument.java            # Enhanced data model
│   ├── TestConfig.java                 # Test configuration
│   ├── BenchmarkResult.java            # Result storage
│   └── ProtocolResult.java             # Per-protocol results
├── infrastructure/
│   ├── AwrManager.java                 # AWR snapshot management
│   ├── SqlMonitorManager.java          # SQL Monitor capture
│   └── ConnectionManager.java          # Database connections
├── execution/
│   ├── BenchmarkExecutor.java          # Generic benchmark runner
│   ├── RecallBenchmark.java            # VS3 implementation
│   ├── FilteredSearchBenchmark.java    # VS4 implementation
│   ├── HybridSearchBenchmark.java      # VS5 implementation
│   └── ...                             # Other category implementations
├── reporting/
│   ├── VectorSearchReportGenerator.java # HTML report generator
│   ├── ChartDataBuilder.java           # Chart.js data builder
│   └── templates/
│       └── vector_search_report.html   # Report template
└── util/
    ├── QuantizationUtils.java          # Vector quantization
    ├── FusionUtils.java                # RRF and weighted fusion
    └── LatencyUtils.java               # Percentile calculations

reports/vector_search/
├── vector_search_report.html           # Main HTML report
├── sql_monitor/                        # SQL Monitor reports
│   ├── VS3_RECALL_10_jdbc.html
│   ├── VS4_FILTER_10PCT_jdbc.html
│   └── ...
└── awr/                                # AWR reports
    ├── VS3_awr.html
    ├── VS4_awr.html
    └── ...
```

---

## Appendix B: Execution Commands

### Basic Execution

```bash
# Run all vector search tests (auto-detects edition limits)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest"

# Run specific category
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.testVS3*"
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.testVS4*"

# Run unit tests only (no DB required)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.testT*"
```

### Edition-Specific Execution

```bash
# Run tests suitable for Oracle Free Edition (auto-skips incompatible tests)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Doracle.edition=FREE \
    -Doracle.awr.enabled=false \
    -Doracle.sqlmonitor.enabled=false

# Run all tests including Enterprise-only features
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Doracle.edition=ENTERPRISE \
    -Doracle.awr.enabled=true \
    -Doracle.sqlmonitor.enabled=true

# Run large scale tests (Enterprise only)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Dgroups=large,enterprise

# Run high concurrency tests (Enterprise recommended)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.testVS8*" \
    -Dgroups=enterprise
```

### Scale-Limited Execution

```bash
# Limit scale for Free Edition (avoids memory issues)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Dtest.scale.max.accounts=10000 \
    -Dtest.concurrency.max.clients=10

# Run only small-scale tests
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    --exclude-tags large,enterprise
```

### MongoDB-Only Tests

```bash
# Run only MongoDB tests (no Oracle required)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Dtest.protocols=MONGODB_NATIVE

# Run MongoDB tests without Oracle connection
./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
    -Doracle.skip=true
```

### Report Generation

```bash
# Generate report only (after tests complete)
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.generateFinalReport"

# Generate report with edition info in filename
./gradlew integrationTest --tests "VectorSearchBenchmarkTest.generateFinalReport" \
    -Dreport.suffix=free_edition
```

### CI/CD Pipeline Examples

```yaml
# GitHub Actions example for Free Edition testing
- name: Run Vector Search Tests (Free Edition)
  run: |
    ./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
      -Doracle.edition=FREE \
      -Doracle.awr.enabled=false \
      --exclude-tags large,enterprise

# Separate job for Enterprise Edition
- name: Run Vector Search Tests (Enterprise Edition)
  if: ${{ secrets.ORACLE_ENTERPRISE_LICENSE }}
  run: |
    ./gradlew integrationTest --tests "VectorSearchBenchmarkTest" \
      -Doracle.edition=ENTERPRISE \
      -Dgroups=large,enterprise
```

---

---

## Appendix C: Complete Test Compatibility Matrix

### Test-by-Test Edition Compatibility

| Test ID | Description | Oracle Free | Oracle Enterprise | MongoDB Local | MongoDB Atlas | AWR | SQL Monitor |
|---------|-------------|:-----------:|:-----------------:|:-------------:|:-------------:|:---:|:-----------:|
| **VS3: Recall Accuracy** |
| VS3_RECALL_10 | Recall@10 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS3_RECALL_50 | Recall@50 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS3_RECALL_100 | Recall@100 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **VS4: Filtered Search** |
| VS4_FILTER_50PCT | 50% selectivity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS4_FILTER_10PCT | 10% selectivity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS4_FILTER_1PCT | 1% selectivity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS4_FILTER_01PCT | 0.1% selectivity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **VS5: Hybrid Search** |
| VS5_VECTOR_ONLY | Vector baseline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS5_BM25_ONLY | Text baseline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS5_HYBRID_RRF | RRF fusion | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS5_HYBRID_WEIGHTED | Weighted fusion | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **VS6: Quantization** |
| VS6_FLOAT32 | Float32 baseline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS6_INT8 | Scalar quantization | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ |
| VS6_BINARY | Binary quantization | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ |
| **VS7: Scale Tests** |
| VS7_1K | 1K accounts | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS7_10K | 10K accounts | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS7_100K | 100K accounts | ⚠️¹ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS7_1M | 1M accounts | ✗ | ✓ | ⚠️² | ✓ | ✓ | ✓ |
| **VS8: Concurrency** |
| VS8_CONC_1 | 1 client | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS8_CONC_10 | 10 clients | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS8_CONC_50 | 50 clients | ⚠️³ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS8_CONC_100 | 100 clients | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **VS9: Oracle Index Types** |
| VS9_NO_INDEX | Brute force | ✓ | ✓ | N/A | N/A | ✓ | ✓ |
| VS9_HNSW_DEFAULT | HNSW default | ⚠️⁴ | ✓ | N/A | N/A | ✓ | ✓ |
| VS9_HNSW_TUNED | HNSW tuned | ⚠️⁴ | ✓ | N/A | N/A | ✓ | ✓ |
| VS9_IVF_DEFAULT | IVF default | ✓ | ✓ | N/A | N/A | ✓ | ✓ |
| VS9_IVF_TUNED | IVF tuned | ✓ | ✓ | N/A | N/A | ✓ | ✓ |
| **VS10: numCandidates** |
| VS10_NC_* | All tests | N/A | N/A | ✓ | ✓ | N/A | N/A |
| **VS11-16: Advanced** |
| VS11_PIPELINE_* | Multi-stage | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS12_MULTI_* | Multi-vector | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS13_LATENCY_* | RAG latency | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS14_BATCH_* | Batch ingest | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS15_TENANT_* | Multi-tenancy | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VS16_DIST_* | Distance metrics | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

**Legend:**
- ✓ = Full support
- ⚠️ = Limited support (see notes)
- ✗ = Not supported / Skip
- N/A = Not applicable to this platform

**Notes:**
1. ⚠️¹ VS7_100K on Oracle Free: IVF index only, HNSW requires ~200MB (tight for 2GB limit)
2. ⚠️² VS7_1M on MongoDB Local: May be slow, recommend Atlas for production testing
3. ⚠️³ VS8_CONC_50 on Oracle Free: Will run but expect CPU bottleneck (2 CPU limit)
4. ⚠️⁴ VS9_HNSW on Oracle Free: Limited to ~10K vectors due to memory constraints

### Feature Availability Summary

| Feature | Oracle Free | Oracle Enterprise | MongoDB Local | MongoDB Atlas |
|---------|:-----------:|:-----------------:|:-------------:|:-------------:|
| Vector Search | ✓ | ✓ | ✓ | ✓ |
| HNSW Index | ⚠️ Limited¹ | ✓ | Auto | Auto |
| IVF Index | ✓ | ✓ | N/A | N/A |
| Filtered Search | ✓ | ✓ | ✓ | ✓ |
| Hybrid Search | ✓ | ✓ | ✓ | ✓ |
| AWR Reports | ✓ | ✓ | N/A | N/A |
| SQL Monitor | ✓ | ✓ | N/A | N/A |
| EXPLAIN PLAN | ✓ | ✓ | N/A | N/A |
| explain() | N/A | N/A | ✓ | ✓ |
| Quantization (int8) | ✓ | ✓ | ✗ | ✓ |
| Quantization (binary) | ✓ | ✓ | ✗ | ✓ |
| Max Concurrency | ⚠️ 2 CPUs | Unlimited | Unlimited | Unlimited |
| Max Memory | ⚠️ 2 GB | Configurable | Host limit | Configurable |
| Max Storage | ⚠️ 12 GB | Unlimited | Host limit | Configurable |

¹ HNSW limited to ~10K vectors on Free Edition due to 2GB memory constraint

---

## Approval Checklist

- [ ] Data model enhancements approved
- [ ] Test categories scope approved
- [ ] Protocol coverage approved (MongoDB Native, $sql, JDBC)
- [ ] AWR/SQL Monitor integration approved (Enterprise only)
- [ ] Edition-aware conditional execution approved
- [ ] Report format approved
- [ ] TDD methodology approved
- [ ] Execution order approved
- [ ] Oracle Free Edition limitations documented
- [ ] MongoDB Local limitations documented
