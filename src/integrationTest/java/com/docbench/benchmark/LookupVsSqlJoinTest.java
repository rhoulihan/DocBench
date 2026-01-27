package com.docbench.benchmark;

import com.mongodb.WriteConcern;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import oracle.jdbc.pool.OracleDataSource;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * $lookup vs $sql Join Benchmark Test Suite
 *
 * Compares MongoDB's $lookup aggregation operator against Oracle's $sql aggregation operator
 * to demonstrate:
 * - Oracle's parallel execution advantage via PARALLEL hints in $sql
 * - MongoDB's 16MB document size limit on $lookup results
 * - MongoDB's 100MB aggregation memory limit and disk spillover performance cliff
 * - Multi-stage pipeline performance differences
 *
 * Oracle's $sql aggregation operator allows embedding SQL statements within MongoDB
 * aggregation pipelines when connecting through Oracle's MongoDB API compatibility layer.
 */
@DisplayName("$lookup vs SQL JOIN: MongoDB Aggregation vs Oracle Parallel Execution")
@Tag("benchmark")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LookupVsSqlJoinTest {

    // ==========================================================================
    // Configuration Constants
    // ==========================================================================

    // Data sizes
    private static final int SMALL_CUSTOMER_COUNT = 1_000;
    private static final int MEDIUM_CUSTOMER_COUNT = 10_000;
    private static final int LARGE_CUSTOMER_COUNT = 100_000;

    // Iteration counts
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;

    // Memory limit tests (MongoDB default is 100MB for aggregation)
    private static final int MONGO_MEMORY_LIMIT_MB = 100;
    private static final int[] MEMORY_TEST_SIZES_MB = {50, 100, 150, 200, 500};

    // Document size limit tests (MongoDB limit is 16MB)
    private static final int MONGO_DOC_LIMIT_MB = 16;
    private static final int[] DOC_SIZE_TESTS_KB = {100, 1024, 8 * 1024, 15 * 1024, 20 * 1024, 50 * 1024};

    // Parallel degrees (full suite - conditionally executed based on Oracle edition)
    private static final int[] PARALLEL_DEGREES = {1, 2, 4, 8, 16};
    private static final int ORACLE_FREE_MAX_PARALLEL = 2;

    // ==========================================================================
    // Database Connections
    // ==========================================================================

    // MongoDB native connection
    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> customersCollection;
    private static MongoCollection<Document> ordersCollection;
    private static MongoCollection<Document> productsCollection;
    private static MongoCollection<Document> largeOrdersCollection;

    // Oracle MongoDB API connection (for $sql aggregation operator)
    private static MongoClient oracleMongoClient;
    private static MongoDatabase oracleMongoDatabase;
    private static MongoCollection<Document> oracleCustomersCollection;
    private static MongoCollection<Document> oracleOrdersCollection;
    private static MongoCollection<Document> oracleProductsCollection;
    private static MongoCollection<Document> oracleLargeOrdersCollection;

    // Oracle JDBC connection (for setup, DDL, and AWR)
    private static Connection oracleJdbcConnection;

    private static final String CUSTOMERS_COLLECTION = "benchmark_customers";
    private static final String ORDERS_COLLECTION = "benchmark_orders";
    private static final String PRODUCTS_COLLECTION = "benchmark_products";
    private static final String LARGE_ORDERS_COLLECTION = "benchmark_large_orders";

    // ==========================================================================
    // Oracle Edition & Mode Detection
    // ==========================================================================

    private static boolean oracleFreeEdition = false;
    private static int maxParallelDegree = 16;

    // Oracle connectivity mode: JDBC (default) or MongoDB API ($sql)
    private static boolean useOracleMongoApi = false;

    // ==========================================================================
    // Results Storage
    // ==========================================================================

    private static final Map<String, TestResult> results = new LinkedHashMap<>();

    // AWR snapshot tracking
    private static final Map<String, long[]> awrSnapshots = new LinkedHashMap<>();
    private static final String AWR_REPORT_DIR = "build/reports/awr";
    private static boolean awrEnabled = false;
    private static long dbId = 0;
    private static long instanceNumber = 1;

    // ==========================================================================
    // Result Record
    // ==========================================================================

    private record TestResult(
            String testId,
            String description,
            long mongoNanos,
            long oracleNanos,
            String category,
            String notes
    ) {}

    // ==========================================================================
    // Lifecycle Methods
    // ==========================================================================

    @BeforeAll
    static void setup() throws SQLException {
        Properties props = loadConfigProperties();

        // =====================================================================
        // MongoDB native connection setup
        // =====================================================================
        String mongoUri = props.getProperty("mongodb.uri");
        String mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);

        // Drop existing collections for clean state (ignore errors if collections don't exist)
        try { mongoDatabase.getCollection(CUSTOMERS_COLLECTION).drop(); } catch (Exception ignored) {}
        try { mongoDatabase.getCollection(ORDERS_COLLECTION).drop(); } catch (Exception ignored) {}
        try { mongoDatabase.getCollection(PRODUCTS_COLLECTION).drop(); } catch (Exception ignored) {}
        try { mongoDatabase.getCollection(LARGE_ORDERS_COLLECTION).drop(); } catch (Exception ignored) {}

        WriteConcern durableWriteConcern = WriteConcern.W1.withJournal(true);
        customersCollection = mongoDatabase.getCollection(CUSTOMERS_COLLECTION).withWriteConcern(durableWriteConcern);
        ordersCollection = mongoDatabase.getCollection(ORDERS_COLLECTION).withWriteConcern(durableWriteConcern);
        productsCollection = mongoDatabase.getCollection(PRODUCTS_COLLECTION).withWriteConcern(durableWriteConcern);
        largeOrdersCollection = mongoDatabase.getCollection(LARGE_ORDERS_COLLECTION).withWriteConcern(durableWriteConcern);

        // =====================================================================
        // Oracle JDBC connection (primary - for data, queries, AWR)
        // =====================================================================
        String oracleUrl = props.getProperty("oracle.url");
        String oracleUser = props.getProperty("oracle.username");
        String oraclePass = props.getProperty("oracle.password");

        OracleDataSource ods = new OracleDataSource();
        ods.setURL(oracleUrl);
        ods.setUser(oracleUser);
        ods.setPassword(oraclePass);
        ods.setImplicitCachingEnabled(true);

        oracleJdbcConnection = ods.getConnection();
        oracleJdbcConnection.setAutoCommit(true);

        if (oracleJdbcConnection.isWrapperFor(oracle.jdbc.OracleConnection.class)) {
            oracle.jdbc.OracleConnection oraConn = oracleJdbcConnection.unwrap(oracle.jdbc.OracleConnection.class);
            oraConn.setStatementCacheSize(50);
        }

        // Create Oracle tables for JDBC mode
        createOracleTables();

        // Detect Oracle edition
        detectOracleEdition();

        // Initialize AWR
        initializeAwr();

        // =====================================================================
        // Oracle MongoDB API connection (optional - for $sql aggregation operator)
        // =====================================================================
        String oracleMongoUri = props.getProperty("oracle.mongodb.uri");
        String oracleMongoDbName = props.getProperty("oracle.mongodb.database", mongoDbName);

        // Oracle MongoDB API - enabled if configured in local.properties
        if (oracleMongoUri != null && !oracleMongoUri.isEmpty()) {
            try {
                oracleMongoClient = MongoClients.create(oracleMongoUri);
                oracleMongoDatabase = oracleMongoClient.getDatabase(oracleMongoDbName);

                // Test connection by listing collections
                oracleMongoDatabase.listCollectionNames().first();

                // Connection successful - set up collections
                oracleCustomersCollection = oracleMongoDatabase.getCollection(CUSTOMERS_COLLECTION);
                oracleOrdersCollection = oracleMongoDatabase.getCollection(ORDERS_COLLECTION);
                oracleProductsCollection = oracleMongoDatabase.getCollection(PRODUCTS_COLLECTION);
                oracleLargeOrdersCollection = oracleMongoDatabase.getCollection(LARGE_ORDERS_COLLECTION);

                useOracleMongoApi = true;
                System.out.println("  Oracle MongoDB API enabled (port 27018)");
            } catch (Exception e) {
                System.out.println("  Oracle MongoDB API connection failed: " + e.getMessage());
                System.out.println("  Falling back to JDBC mode.");
                oracleMongoClient = null;
                useOracleMongoApi = false;
            }
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  $LOOKUP vs SQL JOIN BENCHMARK TEST SUITE");
        System.out.println("  " + "-".repeat(84));
        System.out.println("  MongoDB: $lookup aggregation operator (single-threaded)");
        if (useOracleMongoApi) {
            System.out.println("  Oracle:  $sql aggregation operator with PARALLEL hints (MongoDB API)");
        } else {
            System.out.println("  Oracle:  SQL JOIN with PARALLEL hints (JDBC)");
        }
        System.out.println("  " + "-".repeat(84));
        System.out.println("  Oracle Mode: " + (useOracleMongoApi ? "MongoDB API ($sql)" : "JDBC (default)"));
        System.out.println("  Oracle Edition: " + (oracleFreeEdition ? "Free (2 CPU limit)" : "Enterprise/Standard"));
        System.out.println("  Max Parallel Degree: " + maxParallelDegree);
        System.out.println("=".repeat(90));

        if (!useOracleMongoApi) {
            System.out.println("\n  INFO: Using JDBC for Oracle queries (default mode).");
            System.out.println("  To enable $sql aggregation, configure Oracle MongoDB API:");
            System.out.println("    1. Install ORDS with MongoDB API support");
            System.out.println("    2. Add 'oracle.mongodb.uri' to config/local.properties\n");
        }
    }

    @AfterAll
    static void teardown() {
        printFinalReport();
        generateHtmlReport();

        if (awrEnabled) {
            generateAwrReports();
        }

        // Cleanup MongoDB native collections
        if (mongoClient != null) {
            try {
                mongoDatabase.getCollection(CUSTOMERS_COLLECTION).drop();
                mongoDatabase.getCollection(ORDERS_COLLECTION).drop();
                mongoDatabase.getCollection(PRODUCTS_COLLECTION).drop();
                mongoDatabase.getCollection(LARGE_ORDERS_COLLECTION).drop();
            } catch (Exception e) {
                System.out.println("Warning: MongoDB cleanup failed: " + e.getMessage());
            } finally {
                try {
                    mongoClient.close();
                } catch (Exception ignored) {}
            }
        }

        // Cleanup Oracle MongoDB API collections
        if (oracleMongoClient != null) {
            try {
                oracleMongoDatabase.getCollection(CUSTOMERS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(ORDERS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(PRODUCTS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(LARGE_ORDERS_COLLECTION).drop();
            } catch (Exception e) {
                System.out.println("Warning: Oracle MongoDB API cleanup failed: " + e.getMessage());
            } finally {
                try {
                    oracleMongoClient.close();
                } catch (Exception ignored) {}
            }
        }

        // Close Oracle JDBC connection
        if (oracleJdbcConnection != null) {
            try {
                oracleJdbcConnection.close();
            } catch (SQLException ignored) {}
        }
    }

    // ==========================================================================
    // Configuration Loading
    // ==========================================================================

    private static Properties loadConfigProperties() {
        Path configPath = Path.of("config/local.properties");
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                Properties props = new Properties();
                props.load(is);
                return props;
            } catch (IOException e) {
                throw new RuntimeException("Could not load config", e);
            }
        }
        throw new RuntimeException("config/local.properties not found");
    }

    // ==========================================================================
    // Oracle Setup
    // ==========================================================================

    private static void createOracleTables() throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Drop existing tables (ignore errors if they don't exist)
            stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + ORDERS_COLLECTION + " PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;");
            stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + LARGE_ORDERS_COLLECTION + " PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;");
            stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + CUSTOMERS_COLLECTION + " PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;");
            stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + PRODUCTS_COLLECTION + " PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;");

            // Create tables with JSON columns
            stmt.execute("CREATE TABLE " + CUSTOMERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, doc JSON)");
            stmt.execute("CREATE TABLE " + ORDERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, doc JSON)");
            stmt.execute("CREATE TABLE " + PRODUCTS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, doc JSON)");
            stmt.execute("CREATE TABLE " + LARGE_ORDERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, doc JSON)");

            // Create indexes for join performance
            stmt.execute("CREATE INDEX idx_orders_cust ON " + ORDERS_COLLECTION +
                    " (JSON_VALUE(doc, '$.customer_id'))");
            stmt.execute("CREATE INDEX idx_large_orders_cust ON " + LARGE_ORDERS_COLLECTION +
                    " (JSON_VALUE(doc, '$.customer_id'))");
        }
    }

    private static void detectOracleEdition() {
        try (Statement stmt = oracleJdbcConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT BANNER FROM V$VERSION WHERE ROWNUM = 1")) {
            if (rs.next()) {
                String banner = rs.getString(1);
                oracleFreeEdition = banner != null && banner.toLowerCase().contains("free");
                maxParallelDegree = oracleFreeEdition ? ORACLE_FREE_MAX_PARALLEL : 16;
            }
        } catch (SQLException e) {
            System.out.println("Could not detect Oracle edition: " + e.getMessage());
            maxParallelDegree = 2; // Conservative default
        }
    }

    /**
     * Builds a $sql aggregation pipeline stage for Oracle MongoDB API.
     * The $sql operator allows embedding SQL statements within MongoDB aggregation pipelines.
     *
     * @param sqlStatement The SQL statement to execute
     * @return A Document representing the $sql aggregation stage
     */
    private static Document buildSqlAggregationStage(String sqlStatement) {
        return new Document("$sql", sqlStatement);
    }

    /**
     * Builds a $sql aggregation pipeline with PARALLEL hints for Oracle.
     *
     * @param parallelDegree The degree of parallelism (1 = no parallelism)
     * @param sqlTemplate The SQL template with %PARALLEL% placeholder for hints
     * @return The SQL statement with appropriate PARALLEL hints
     */
    private static String buildParallelSql(int parallelDegree, String sqlTemplate) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(" + parallelDegree + ") */"
                : "";
        return sqlTemplate.replace("%PARALLEL%", hint);
    }

    // ==========================================================================
    // Category A: Baseline Join Performance (Order 0-9)
    // ==========================================================================

    @Test
    @Order(0)
    @DisplayName("A0: Protocol overhead baseline - empty operations")
    void protocolOverheadBaseline() {
        awrSnapshotBefore("A0_baseline");

        // Measure MongoDB empty aggregation
        long mongoNanos = measureMongoEmptyAggregation();

        // Measure Oracle empty SELECT
        long oracleNanos = measureOracleEmptySelect();

        results.put("A0_baseline", new TestResult(
                "A0_baseline",
                "Protocol overhead baseline",
                mongoNanos,
                oracleNanos,
                "baseline",
                "Empty operations to measure protocol overhead"
        ));

        System.out.printf("  A0: Protocol overhead      - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("A0_baseline");
    }

    @Test
    @Order(1)
    @DisplayName("A1: Simple FK join - 1K customers")
    void simpleJoin_1K() {
        awrSnapshotBefore("A1_simple_1K");

        // Generate test data
        int customerCount = SMALL_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runMongoLookup(100);
            runOracleSqlJoin(100, 1);
        }

        // Measure MongoDB $lookup
        long mongoNanos = measureMongoLookup(customerCount);

        // Measure Oracle JOIN
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("A1_simple_1K", new TestResult(
                "A1_simple_1K",
                "Simple FK join - 1K customers",
                mongoNanos,
                oracleNanos,
                "baseline",
                customerCount + " customers, " + ordersPerCustomer + " orders each"
        ));

        System.out.printf("  A1: Simple join 1K         - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("A1_simple_1K");
    }

    @Test
    @Order(2)
    @DisplayName("A2: Simple FK join - 10K customers")
    void simpleJoin_10K() {
        awrSnapshotBefore("A2_simple_10K");

        int customerCount = MEDIUM_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runMongoLookup(1000);
            runOracleSqlJoin(1000, 1);
        }

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("A2_simple_10K", new TestResult(
                "A2_simple_10K",
                "Simple FK join - 10K customers",
                mongoNanos,
                oracleNanos,
                "baseline",
                customerCount + " customers, " + ordersPerCustomer + " orders each"
        ));

        System.out.printf("  A2: Simple join 10K        - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("A2_simple_10K");
    }

    @Test
    @Order(3)
    @DisplayName("A3: Simple FK join - 100K customers")
    void simpleJoin_100K() {
        awrSnapshotBefore("A3_simple_100K");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runMongoLookup(10000);
            runOracleSqlJoin(10000, 1);
        }

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("A3_simple_100K", new TestResult(
                "A3_simple_100K",
                "Simple FK join - 100K customers",
                mongoNanos,
                oracleNanos,
                "baseline",
                customerCount + " customers, " + ordersPerCustomer + " orders each"
        ));

        System.out.printf("  A3: Simple join 100K       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("A3_simple_100K");
    }

    // ==========================================================================
    // Category B: One-to-Many Cardinality (Order 10-19)
    // ==========================================================================

    @Test
    @Order(10)
    @DisplayName("B0: 1:1 join ratio")
    void cardinalityTest_1to1() {
        awrSnapshotBefore("B0_cardinality");

        int customerCount = 10_000;
        int ordersPerCustomer = 1;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("B0_1to1", new TestResult(
                "B0_1to1",
                "1:1 join ratio",
                mongoNanos,
                oracleNanos,
                "cardinality",
                "1 order per customer"
        ));

        System.out.printf("  B0: 1:1 cardinality        - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(11)
    @DisplayName("B1: 1:10 join ratio")
    void cardinalityTest_1to10() {
        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("B1_1to10", new TestResult(
                "B1_1to10",
                "1:10 join ratio",
                mongoNanos,
                oracleNanos,
                "cardinality",
                "10 orders per customer"
        ));

        System.out.printf("  B1: 1:10 cardinality       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(12)
    @DisplayName("B2: 1:100 join ratio")
    void cardinalityTest_1to100() {
        int customerCount = 1_000;
        int ordersPerCustomer = 100;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("B2_1to100", new TestResult(
                "B2_1to100",
                "1:100 join ratio",
                mongoNanos,
                oracleNanos,
                "cardinality",
                "100 orders per customer - MongoDB materializes all"
        ));

        System.out.printf("  B2: 1:100 cardinality      - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(13)
    @DisplayName("B3: 1:1000 join ratio")
    void cardinalityTest_1to1000() {
        int customerCount = 100;
        int ordersPerCustomer = 1000;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("B3_1to1000", new TestResult(
                "B3_1to1000",
                "1:1000 join ratio",
                mongoNanos,
                oracleNanos,
                "cardinality",
                "1000 orders per customer - Memory pressure on MongoDB"
        ));

        System.out.printf("  B3: 1:1000 cardinality     - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("B0_cardinality");
    }

    // ==========================================================================
    // Category C: Oracle Parallel Execution (Order 20-29)
    // ==========================================================================

    @Test
    @Order(20)
    @DisplayName("C0: Large join - no parallel (PARALLEL 1)")
    void parallelJoin_1thread() {
        awrSnapshotBefore("C0_parallel");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 1);

        results.put("C0_parallel_1", new TestResult(
                "C0_parallel_1",
                "Large join - PARALLEL(1)",
                mongoNanos,
                oracleNanos,
                "parallel",
                "Baseline single-threaded execution"
        ));

        System.out.printf("  C0: Parallel(1)            - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(21)
    @DisplayName("C1: Large join - 2 threads (PARALLEL 2)")
    void parallelJoin_2threads() {
        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 2);

        results.put("C1_parallel_2", new TestResult(
                "C1_parallel_2",
                "Large join - PARALLEL(2)",
                mongoNanos,
                oracleNanos,
                "parallel",
                "2 parallel worker threads"
        ));

        System.out.printf("  C1: Parallel(2)            - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(22)
    @DisplayName("C2: Large join - 4 threads (PARALLEL 4)")
    void parallelJoin_4threads() {
        assumeTrue(maxParallelDegree >= 4,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 4);

        results.put("C2_parallel_4", new TestResult(
                "C2_parallel_4",
                "Large join - PARALLEL(4)",
                mongoNanos,
                oracleNanos,
                "parallel",
                "4 parallel worker threads"
        ));

        System.out.printf("  C2: Parallel(4)            - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(23)
    @DisplayName("C3: Large join - 8 threads (PARALLEL 8)")
    void parallelJoin_8threads() {
        assumeTrue(maxParallelDegree >= 8,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 8);

        results.put("C3_parallel_8", new TestResult(
                "C3_parallel_8",
                "Large join - PARALLEL(8)",
                mongoNanos,
                oracleNanos,
                "parallel",
                "8 parallel worker threads"
        ));

        System.out.printf("  C3: Parallel(8)            - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(24)
    @DisplayName("C4: Large join - 16 threads (PARALLEL 16)")
    void parallelJoin_16threads() {
        assumeTrue(maxParallelDegree >= 16,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookup(customerCount);
        long oracleNanos = measureOracleJoin(customerCount, 16);

        results.put("C4_parallel_16", new TestResult(
                "C4_parallel_16",
                "Large join - PARALLEL(16)",
                mongoNanos,
                oracleNanos,
                "parallel",
                "16 parallel worker threads"
        ));

        System.out.printf("  C4: Parallel(16)           - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("C0_parallel");
    }

    // ==========================================================================
    // Category D: Document Size Limit Tests (Order 30-39)
    // ==========================================================================

    @Test
    @Order(30)
    @DisplayName("D0: Small embedded result ~100KB")
    void docSizeLimit_100KB() {
        awrSnapshotBefore("D0_doc_size");
        testDocumentSizeLimit("D0_100KB", 100, false);
    }

    @Test
    @Order(31)
    @DisplayName("D1: Medium embedded result ~1MB")
    void docSizeLimit_1MB() {
        testDocumentSizeLimit("D1_1MB", 1024, false);
    }

    @Test
    @Order(32)
    @DisplayName("D2: Large embedded result ~8MB")
    void docSizeLimit_8MB() {
        testDocumentSizeLimit("D2_8MB", 8 * 1024, false);
    }

    @Test
    @Order(33)
    @DisplayName("D3: Near limit embedded result ~15MB")
    void docSizeLimit_15MB() {
        testDocumentSizeLimit("D3_15MB", 15 * 1024, false);
    }

    @Test
    @Order(34)
    @DisplayName("D4: Exceed limit embedded result ~20MB - EXPECT MONGODB FAILURE")
    void docSizeLimit_20MB_expectFailure() {
        testDocumentSizeLimit("D4_20MB", 20 * 1024, true);
    }

    @Test
    @Order(35)
    @DisplayName("D5: Far exceed limit ~50MB - EXPECT MONGODB FAILURE")
    void docSizeLimit_50MB_expectFailure() {
        testDocumentSizeLimit("D5_50MB", 50 * 1024, true);
        awrSnapshotAfter("D0_doc_size");
    }

    private void testDocumentSizeLimit(String testId, int targetSizeKB, boolean expectMongoFailure) {
        // Generate large orders that will exceed document limit when embedded
        generateLargeOrders(1, targetSizeKB);

        long mongoNanos;
        String mongoNotes;

        if (expectMongoFailure) {
            // We expect MongoDB to fail with BSONObjectTooLarge
            try {
                mongoNanos = measureMongoLookupLargeOrders(1);
                mongoNotes = "UNEXPECTED SUCCESS - should have failed";
            } catch (Exception e) {
                mongoNanos = -1; // Indicates failure
                mongoNotes = "Expected failure: " + e.getClass().getSimpleName();
                System.out.printf("  %s: MongoDB FAILED as expected - %s%n", testId, e.getMessage());
            }
        } else {
            mongoNanos = measureMongoLookupLargeOrders(1);
            mongoNotes = "Success";
        }

        // Oracle should always succeed
        long oracleNanos = measureOracleJoinLargeOrders(1, 1);

        results.put(testId, new TestResult(
                testId,
                "Doc size ~" + (targetSizeKB >= 1024 ? (targetSizeKB / 1024) + "MB" : targetSizeKB + "KB"),
                mongoNanos,
                oracleNanos,
                "doc_size",
                mongoNotes
        ));

        if (mongoNanos >= 0) {
            System.out.printf("  %s: Doc size %dKB       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                    testId, targetSizeKB, mongoNanos, oracleNanos);
        } else {
            System.out.printf("  %s: Doc size %dKB       - MongoDB: FAILED (16MB limit) | Oracle: %,12d ns%n",
                    testId, targetSizeKB, oracleNanos);
        }
    }

    // ==========================================================================
    // Category E: Aggregation Memory Limit Tests (Order 40-49)
    // ==========================================================================

    @Test
    @Order(40)
    @DisplayName("E0: Under memory limit - 50MB working set")
    void memoryLimit_50MB() {
        awrSnapshotBefore("E0_memory");
        testMemoryLimit("E0_50MB", 50, false, false);
    }

    @Test
    @Order(41)
    @DisplayName("E1: At memory limit - 100MB working set")
    void memoryLimit_100MB() {
        testMemoryLimit("E1_100MB", 100, false, false);
    }

    @Test
    @Order(42)
    @DisplayName("E2: Over memory limit - 150MB (no allowDiskUse) - EXPECT FAILURE")
    void memoryLimit_150MB_noAllowDisk() {
        testMemoryLimit("E2_150MB_noDisk", 150, false, true);
    }

    @Test
    @Order(43)
    @DisplayName("E3: Over memory limit - 150MB (with allowDiskUse)")
    void memoryLimit_150MB_allowDisk() {
        testMemoryLimit("E3_150MB_disk", 150, true, false);
    }

    @Test
    @Order(44)
    @DisplayName("E4: 2x memory limit - 200MB (with allowDiskUse)")
    void memoryLimit_200MB_allowDisk() {
        testMemoryLimit("E4_200MB_disk", 200, true, false);
    }

    @Test
    @Order(45)
    @DisplayName("E5: 5x memory limit - 500MB (with allowDiskUse)")
    void memoryLimit_500MB_allowDisk() {
        testMemoryLimit("E5_500MB_disk", 500, true, false);
        awrSnapshotAfter("E0_memory");
    }

    private void testMemoryLimit(String testId, int workingSetMB, boolean allowDiskUse, boolean expectFailure) {
        // Generate enough data to create the specified working set
        // Estimate ~200 bytes per order document
        int orderCount = (workingSetMB * 1024 * 1024) / 200;
        int customerCount = Math.min(orderCount / 10, 10000);
        int ordersPerCustomer = orderCount / customerCount;

        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos;
        String mongoNotes;

        if (expectFailure) {
            try {
                mongoNanos = measureMongoLookupWithSort(customerCount, allowDiskUse);
                mongoNotes = "UNEXPECTED SUCCESS";
            } catch (Exception e) {
                mongoNanos = -1;
                mongoNotes = "Expected failure: " + e.getClass().getSimpleName();
                System.out.printf("  %s: MongoDB FAILED as expected - %s%n", testId, e.getMessage());
            }
        } else {
            mongoNanos = measureMongoLookupWithSort(customerCount, allowDiskUse);
            mongoNotes = allowDiskUse ? "allowDiskUse=true" : "In-memory";
        }

        long oracleNanos = measureOracleJoinWithSort(customerCount, 2);

        results.put(testId, new TestResult(
                testId,
                "Memory " + workingSetMB + "MB" + (allowDiskUse ? " (disk)" : ""),
                mongoNanos,
                oracleNanos,
                "memory",
                mongoNotes
        ));

        if (mongoNanos >= 0) {
            System.out.printf("  %s: Memory %dMB         - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                    testId, workingSetMB, mongoNanos, oracleNanos);
        } else {
            System.out.printf("  %s: Memory %dMB         - MongoDB: FAILED (memory limit) | Oracle: %,12d ns%n",
                    testId, workingSetMB, oracleNanos);
        }
    }

    // ==========================================================================
    // Category F: Sort Spillover Tests (Order 50-59)
    // ==========================================================================

    @Test
    @Order(50)
    @DisplayName("F0: Small sort - 10K documents")
    void sortTest_10K() {
        awrSnapshotBefore("F0_sort");
        testSortPerformance("F0_sort_10K", 10_000);
    }

    @Test
    @Order(51)
    @DisplayName("F1: Medium sort - 100K documents")
    void sortTest_100K() {
        testSortPerformance("F1_sort_100K", 100_000);
    }

    @Test
    @Order(52)
    @DisplayName("F2: Large sort - 500K documents")
    void sortTest_500K() {
        testSortPerformance("F2_sort_500K", 500_000);
    }

    @Test
    @Order(53)
    @DisplayName("F3: Very large sort - 1M documents")
    void sortTest_1M() {
        testSortPerformance("F3_sort_1M", 1_000_000);
        awrSnapshotAfter("F0_sort");
    }

    private void testSortPerformance(String testId, int documentCount) {
        // Generate orders for sorting
        int customerCount = Math.min(documentCount / 10, 10000);
        int ordersPerCustomer = documentCount / customerCount;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoLookupWithSort(customerCount, true);
        long oracleNanos = measureOracleJoinWithSort(customerCount, Math.min(maxParallelDegree, 4));

        results.put(testId, new TestResult(
                testId,
                "Sort " + formatCount(documentCount) + " docs",
                mongoNanos,
                oracleNanos,
                "sort",
                "allowDiskUse=true for MongoDB, PARALLEL for Oracle"
        ));

        System.out.printf("  %s: Sort %-8s      - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                testId, formatCount(documentCount), mongoNanos, oracleNanos);
    }

    // ==========================================================================
    // Category G: Multi-Stage Pipeline Stress (Order 60-69)
    // ==========================================================================

    @Test
    @Order(60)
    @DisplayName("G0: 2-stage pipeline ($lookup -> $sort)")
    void pipeline_2stage() {
        awrSnapshotBefore("G0_pipeline");

        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoPipeline2Stage(customerCount);
        long oracleNanos = measureOracleJoinWithSort(customerCount, 2);

        results.put("G0_2stage", new TestResult(
                "G0_2stage",
                "2-stage: $lookup -> $sort",
                mongoNanos,
                oracleNanos,
                "pipeline",
                "2 potential spill points"
        ));

        System.out.printf("  G0: 2-stage pipeline       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(61)
    @DisplayName("G1: 3-stage pipeline ($lookup -> $unwind -> $group)")
    void pipeline_3stage() {
        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoPipeline3Stage(customerCount);
        long oracleNanos = measureOracleJoinGroupBy(customerCount, 2);

        results.put("G1_3stage", new TestResult(
                "G1_3stage",
                "3-stage: $lookup -> $unwind -> $group",
                mongoNanos,
                oracleNanos,
                "pipeline",
                "3 potential spill points"
        ));

        System.out.printf("  G1: 3-stage pipeline       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(62)
    @DisplayName("G2: 4-stage pipeline ($lookup -> $unwind -> $group -> $sort)")
    void pipeline_4stage() {
        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = measureMongoPipeline4Stage(customerCount);
        long oracleNanos = measureOracleJoinGroupBySort(customerCount, 2);

        results.put("G2_4stage", new TestResult(
                "G2_4stage",
                "4-stage: $lookup -> $unwind -> $group -> $sort",
                mongoNanos,
                oracleNanos,
                "pipeline",
                "4 potential spill points"
        ));

        System.out.printf("  G2: 4-stage pipeline       - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);
    }

    @Test
    @Order(63)
    @DisplayName("G3: Chained lookups ($lookup -> $lookup)")
    void pipeline_chainedLookups() {
        int customerCount = 1_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);
        generateProductData(1_000);

        long mongoNanos = measureMongoChainedLookups(customerCount);
        long oracleNanos = measureOracleMultiTableJoin(customerCount, 2);

        results.put("G3_chained", new TestResult(
                "G3_chained",
                "Chained: $lookup -> $lookup",
                mongoNanos,
                oracleNanos,
                "pipeline",
                "Nested materialization in MongoDB"
        ));

        System.out.printf("  G3: Chained lookups        - MongoDB: %,12d ns | Oracle: %,12d ns%n",
                mongoNanos, oracleNanos);

        awrSnapshotAfter("G0_pipeline");
    }

    // ==========================================================================
    // Data Generation Methods
    // ==========================================================================

    private void generateTestData(int customerCount, int ordersPerCustomer) {
        // Clear existing data - MongoDB native
        customersCollection.drop();
        ordersCollection.drop();
        customersCollection = mongoDatabase.getCollection(CUSTOMERS_COLLECTION)
                .withWriteConcern(WriteConcern.W1.withJournal(true));
        ordersCollection = mongoDatabase.getCollection(ORDERS_COLLECTION)
                .withWriteConcern(WriteConcern.W1.withJournal(true));

        // Clear existing data - Oracle MongoDB API
        if (oracleMongoClient != null) {
            try { oracleCustomersCollection.drop(); } catch (Exception ignored) {}
            try { oracleOrdersCollection.drop(); } catch (Exception ignored) {}
            oracleCustomersCollection = oracleMongoDatabase.getCollection(CUSTOMERS_COLLECTION);
            oracleOrdersCollection = oracleMongoDatabase.getCollection(ORDERS_COLLECTION);
        }

        // Generate customers
        List<Document> customerDocs = new ArrayList<>();
        String[] regions = {"NORTH", "SOUTH", "EAST", "WEST"};

        for (int i = 0; i < customerCount; i++) {
            String customerId = "cust_" + i;
            Document doc = new Document()
                    .append("_id", customerId)
                    .append("name", "Customer " + i)
                    .append("email", "customer" + i + "@example.com")
                    .append("region", regions[i % 4])
                    .append("created_at", "2024-01-" + String.format("%02d", (i % 28) + 1));
            customerDocs.add(doc);

            // Batch insert every 10000 records
            if (customerDocs.size() >= 10000) {
                customersCollection.insertMany(customerDocs);
                if (oracleCustomersCollection != null) {
                    oracleCustomersCollection.insertMany(new ArrayList<>(customerDocs));
                }
                customerDocs.clear();
            }
        }

        // Insert remaining customers
        if (!customerDocs.isEmpty()) {
            customersCollection.insertMany(customerDocs);
            if (oracleCustomersCollection != null) {
                oracleCustomersCollection.insertMany(new ArrayList<>(customerDocs));
            }
        }

        // Generate orders
        List<Document> orderDocs = new ArrayList<>();
        String[] statuses = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"};

        int orderId = 0;
        for (int c = 0; c < customerCount; c++) {
            String customerId = "cust_" + c;
            for (int o = 0; o < ordersPerCustomer; o++) {
                String orderIdStr = "order_" + orderId++;
                double total = 10.0 + (orderId % 1000);

                Document doc = new Document()
                        .append("_id", orderIdStr)
                        .append("customer_id", customerId)
                        .append("order_date", "2024-01-" + String.format("%02d", (o % 28) + 1))
                        .append("total", total)
                        .append("status", statuses[o % 4]);
                orderDocs.add(doc);

                // Batch insert every 10000 records
                if (orderDocs.size() >= 10000) {
                    ordersCollection.insertMany(orderDocs);
                    if (oracleOrdersCollection != null) {
                        oracleOrdersCollection.insertMany(new ArrayList<>(orderDocs));
                    }
                    orderDocs.clear();
                }
            }
        }

        // Insert remaining orders
        if (!orderDocs.isEmpty()) {
            ordersCollection.insertMany(orderDocs);
            if (oracleOrdersCollection != null) {
                oracleOrdersCollection.insertMany(new ArrayList<>(orderDocs));
            }
        }

        // Create indexes for MongoDB native
        ordersCollection.createIndex(new Document("customer_id", 1));

        // Create indexes for Oracle MongoDB API
        if (oracleOrdersCollection != null) {
            try {
                oracleOrdersCollection.createIndex(new Document("customer_id", 1));
            } catch (Exception ignored) {}
        }
    }

    private void generateLargeOrders(int customerCount, int targetSizeKB) {
        // Clear large orders - MongoDB native
        largeOrdersCollection.drop();
        largeOrdersCollection = mongoDatabase.getCollection(LARGE_ORDERS_COLLECTION)
                .withWriteConcern(WriteConcern.W1.withJournal(true));

        // Clear large orders - Oracle MongoDB API
        if (oracleMongoClient != null) {
            try { oracleLargeOrdersCollection.drop(); } catch (Exception ignored) {}
            oracleLargeOrdersCollection = oracleMongoDatabase.getCollection(LARGE_ORDERS_COLLECTION);
        }

        // Also need customers
        customersCollection.drop();
        customersCollection = mongoDatabase.getCollection(CUSTOMERS_COLLECTION)
                .withWriteConcern(WriteConcern.W1.withJournal(true));

        if (oracleMongoClient != null) {
            try { oracleCustomersCollection.drop(); } catch (Exception ignored) {}
            oracleCustomersCollection = oracleMongoDatabase.getCollection(CUSTOMERS_COLLECTION);
        }

        // Generate minimal customers
        List<Document> customerDocs = new ArrayList<>();
        for (int i = 0; i < customerCount; i++) {
            String customerId = "cust_" + i;
            Document customerDoc = new Document()
                    .append("_id", customerId)
                    .append("name", "Customer " + i);
            customerDocs.add(customerDoc);
        }
        customersCollection.insertMany(customerDocs);
        if (oracleCustomersCollection != null) {
            oracleCustomersCollection.insertMany(new ArrayList<>(customerDocs));
        }

        // Generate large orders with padding to reach target size
        int targetSizeBytes = targetSizeKB * 1024;
        int orderPerCustomer = 10;
        int paddingSize = (targetSizeBytes / orderPerCustomer) - 200; // Account for base fields

        String padding = "X".repeat(Math.max(100, paddingSize));

        List<Document> orderDocs = new ArrayList<>();
        for (int c = 0; c < customerCount; c++) {
            String customerId = "cust_" + c;
            for (int o = 0; o < orderPerCustomer; o++) {
                String orderId = "large_order_" + c + "_" + o;
                Document doc = new Document()
                        .append("_id", orderId)
                        .append("customer_id", customerId)
                        .append("data", padding)
                        .append("total", 100.0 + o);
                orderDocs.add(doc);
            }
        }
        largeOrdersCollection.insertMany(orderDocs);
        if (oracleLargeOrdersCollection != null) {
            oracleLargeOrdersCollection.insertMany(new ArrayList<>(orderDocs));
        }

        largeOrdersCollection.createIndex(new Document("customer_id", 1));
        if (oracleLargeOrdersCollection != null) {
            try {
                oracleLargeOrdersCollection.createIndex(new Document("customer_id", 1));
            } catch (Exception ignored) {}
        }
    }

    private void generateProductData(int productCount) {
        // Clear products - MongoDB native
        productsCollection.drop();
        productsCollection = mongoDatabase.getCollection(PRODUCTS_COLLECTION)
                .withWriteConcern(WriteConcern.W1.withJournal(true));

        // Clear products - Oracle MongoDB API
        if (oracleMongoClient != null) {
            try { oracleProductsCollection.drop(); } catch (Exception ignored) {}
            oracleProductsCollection = oracleMongoDatabase.getCollection(PRODUCTS_COLLECTION);
        }

        String[] categories = {"Electronics", "Clothing", "Books", "Home", "Sports"};

        List<Document> productDocs = new ArrayList<>();

        for (int i = 0; i < productCount; i++) {
            String productId = "prod_" + i;
            Document doc = new Document()
                    .append("_id", productId)
                    .append("name", "Product " + i)
                    .append("category", categories[i % 5])
                    .append("price", 10.0 + (i % 100))
                    .append("description", "Description for product " + i);
            productDocs.add(doc);

            if (productDocs.size() >= 5000) {
                productsCollection.insertMany(productDocs);
                if (oracleProductsCollection != null) {
                    oracleProductsCollection.insertMany(new ArrayList<>(productDocs));
                }
                productDocs.clear();
            }
        }

        if (!productDocs.isEmpty()) {
            productsCollection.insertMany(productDocs);
            if (oracleProductsCollection != null) {
                oracleProductsCollection.insertMany(new ArrayList<>(productDocs));
            }
        }
    }

    // ==========================================================================
    // MongoDB Measurement Methods
    // ==========================================================================

    private long measureMongoEmptyAggregation() {
        // Warmup - use limit(1) since MongoDB requires positive limit
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            customersCollection.aggregate(List.of(Aggregates.limit(1))).first();
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            customersCollection.aggregate(List.of(Aggregates.limit(1))).first();
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoLookup(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            runMongoLookup(limit);
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private void runMongoLookup(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders")
        );

        AggregateIterable<Document> result = customersCollection.aggregate(pipeline);
        for (Document doc : result) {
            // Consume all results
        }
    }

    private long measureMongoLookupLargeOrders(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_large_orders", "_id", "customer_id", "orders")
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline);
            for (Document doc : result) {
                // Consume all results - this will throw if document exceeds 16MB
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoLookupWithSort(int limit, boolean allowDiskUse) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();

            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                    Aggregates.unwind("$orders"),
                    Aggregates.sort(new Document("orders.total", -1))
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline);
            if (allowDiskUse) {
                result = result.allowDiskUse(true);
            }

            for (Document doc : result) {
                // Consume all results
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoPipeline2Stage(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();

            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                    Aggregates.sort(new Document("name", 1))
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline).allowDiskUse(true);
            for (Document doc : result) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoPipeline3Stage(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();

            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                    Aggregates.unwind("$orders"),
                    Aggregates.group("$_id",
                            com.mongodb.client.model.Accumulators.sum("totalSpent", "$orders.total"),
                            com.mongodb.client.model.Accumulators.sum("orderCount", 1))
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline).allowDiskUse(true);
            for (Document doc : result) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoPipeline4Stage(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();

            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                    Aggregates.unwind("$orders"),
                    Aggregates.group("$_id",
                            com.mongodb.client.model.Accumulators.sum("totalSpent", "$orders.total"),
                            com.mongodb.client.model.Accumulators.sum("orderCount", 1)),
                    Aggregates.sort(new Document("totalSpent", -1))
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline).allowDiskUse(true);
            for (Document doc : result) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoChainedLookups(int limit) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();

            // Note: MongoDB doesn't have a direct way to lookup from order items to products
            // This simulates chained lookups by doing customer -> orders then another aggregation
            List<Bson> pipeline = Arrays.asList(
                    Aggregates.limit(limit),
                    Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                    Aggregates.lookup("benchmark_products", "orders.product_id", "_id", "products")
            );

            AggregateIterable<Document> result = customersCollection.aggregate(pipeline).allowDiskUse(true);
            for (Document doc : result) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // Oracle Measurement Methods (JDBC default, MongoDB API optional)
    // ==========================================================================

    /**
     * Measures Oracle empty query (protocol overhead baseline).
     * Uses JDBC by default, $sql aggregation if MongoDB API is available.
     */
    private long measureOracleEmptySelect() {
        if (useOracleMongoApi) {
            return measureOracleEmptySelectMongoApi();
        } else {
            return measureOracleEmptySelectJdbc();
        }
    }

    private long measureOracleEmptySelectJdbc() {
        String sql = "SELECT 1 FROM DUAL WHERE 1=0";
        try {
            // Warmup
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                }
            }

            // Measure
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getInt(1);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleEmptySelectMongoApi() {
        String sql = "SELECT 1 FROM DUAL WHERE 1=0";
        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            oracleCustomersCollection.aggregate(pipeline).first();
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            oracleCustomersCollection.aggregate(pipeline).first();
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle JOIN performance.
     */
    private long measureOracleJoin(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleJoinMongoApi(limit, parallelDegree);
        } else {
            return measureOracleJoinJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleJoinJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.doc, o.doc FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Actually read the data to include transfer time
                            rs.getString(1);
                            rs.getString(2);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleJoinMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Only single column SELECT works with JOINs
        // Use JSON_MERGEPATCH to combine customer and order documents
        String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10);

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private void runOracleSqlJoin(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            // Oracle MongoDB API $sql: Only single column SELECT works with JOINs
            String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) FROM " + CUSTOMERS_COLLECTION + " c " +
                    "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                    "WHERE ROWNUM <= " + (limit * 10);
            List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
        } else {
            try {
                String hint = parallelDegree > 1
                        ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                        : "";
                String sql = "SELECT " + hint + " c.doc, o.doc FROM " + CUSTOMERS_COLLECTION + " c " +
                        "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                        "WHERE ROWNUM <= ?";
                try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                    ps.setInt(1, limit * 10);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Oracle JDBC error", e);
            }
        }
    }

    /**
     * Measures Oracle JOIN with large orders (document size limit test).
     */
    private long measureOracleJoinLargeOrders(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleJoinLargeOrdersMongoApi(limit, parallelDegree);
        } else {
            return measureOracleJoinLargeOrdersJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleJoinLargeOrdersJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.doc, o.doc FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + LARGE_ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleJoinLargeOrdersMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Use JSON_MERGEPATCH for combined results
        String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + LARGE_ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10);

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle JOIN with ORDER BY.
     */
    private long measureOracleJoinWithSort(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleJoinWithSortMongoApi(limit, parallelDegree);
        } else {
            return measureOracleJoinWithSortJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleJoinWithSortJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.doc, o.doc FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ? " +
                "ORDER BY JSON_VALUE(o.doc, '$.total' RETURNING NUMBER) DESC";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleJoinWithSortMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Use JSON_MERGEPATCH and JSON_VALUE for ORDER BY
        String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10) + " ORDER BY JSON_VALUE(o.data, '$.total' RETURNING NUMBER) DESC";

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle JOIN with GROUP BY.
     */
    private long measureOracleJoinGroupBy(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleJoinGroupByMongoApi(limit, parallelDegree);
        } else {
            return measureOracleJoinGroupByJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleJoinGroupByJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " JSON_VALUE(c.doc, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.doc, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ? " +
                "GROUP BY JSON_VALUE(c.doc, '$._id')";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getDouble(2);
                            rs.getLong(3);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleJoinGroupByMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Use JSON_OBJECT to return single JSON column
        String sql = "SELECT JSON_OBJECT('customer_id' VALUE JSON_VALUE(c.data, '$._id'), " +
                "'total_spent' VALUE SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)), " +
                "'order_count' VALUE COUNT(*)) " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10) + " GROUP BY JSON_VALUE(c.data, '$._id')";

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle JOIN with GROUP BY and ORDER BY.
     */
    private long measureOracleJoinGroupBySort(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleJoinGroupBySortMongoApi(limit, parallelDegree);
        } else {
            return measureOracleJoinGroupBySortJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleJoinGroupBySortJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " JSON_VALUE(c.doc, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.doc, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ? " +
                "GROUP BY JSON_VALUE(c.doc, '$._id') ORDER BY total_spent DESC";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getDouble(2);
                            rs.getLong(3);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleJoinGroupBySortMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Use JSON_OBJECT and ORDER BY with alias
        String sql = "SELECT JSON_OBJECT('customer_id' VALUE JSON_VALUE(c.data, '$._id'), " +
                "'total_spent' VALUE SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)), " +
                "'order_count' VALUE COUNT(*)) " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10) + " GROUP BY JSON_VALUE(c.data, '$._id') " +
                "ORDER BY SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) DESC";

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle multi-table JOIN.
     */
    private long measureOracleMultiTableJoin(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            return measureOracleMultiTableJoinMongoApi(limit, parallelDegree);
        } else {
            return measureOracleMultiTableJoinJdbc(limit, parallelDegree);
        }
    }

    private long measureOracleMultiTableJoinJdbc(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") PARALLEL(p, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.doc, o.doc, p.doc " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "LEFT JOIN " + PRODUCTS_COLLECTION + " p ON JSON_VALUE(o.doc, '$.product_id') = JSON_VALUE(p.doc, '$._id') " +
                "WHERE ROWNUM <= ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                            rs.getString(3);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private long measureOracleMultiTableJoinMongoApi(int limit, int parallelDegree) {
        // Oracle MongoDB API $sql: Use JSON_OBJECT to combine three tables
        String sql = "SELECT JSON_OBJECT('customer' VALUE c.data, 'order' VALUE o.data, 'product' VALUE p.data) " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "LEFT JOIN " + PRODUCTS_COLLECTION + " p ON JSON_VALUE(o.data, '$.product_id') = JSON_VALUE(p.data, '$._id') " +
                "WHERE ROWNUM <= " + (limit * 10);

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleCustomersCollection.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // AWR Infrastructure
    // ==========================================================================

    private static void initializeAwr() {
        try {
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con_dbid, instance_number FROM v$database, v$instance")) {
                if (rs.next()) {
                    dbId = rs.getLong(1);
                    instanceNumber = rs.getLong(2);
                    awrEnabled = true;
                    System.out.println("AWR enabled - CON_DBID: " + dbId + ", Instance: " + instanceNumber);

                    Path reportDir = Path.of(AWR_REPORT_DIR);
                    Files.createDirectories(reportDir);
                }
            }
        } catch (Exception e) {
            System.out.println("AWR not available: " + e.getMessage());
            awrEnabled = false;
        }
    }

    private static long createAwrSnapshot(String description) {
        if (!awrEnabled) return -1;

        try (CallableStatement cs = oracleJdbcConnection.prepareCall(
                "{ ? = call DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT() }")) {
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.execute();
            long snapId = cs.getLong(1);
            System.out.println("  AWR Snapshot " + snapId + " created: " + description);
            return snapId;
        } catch (SQLException e) {
            System.out.println("  AWR snapshot failed: " + e.getMessage());
            return -1;
        }
    }

    private static void awrSnapshotBefore(String category) {
        if (!awrEnabled) return;
        long snapId = createAwrSnapshot("Before " + category);
        if (snapId > 0) {
            awrSnapshots.put(category, new long[]{snapId, -1});
        }
    }

    private static void awrSnapshotAfter(String category) {
        if (!awrEnabled) return;
        long[] snaps = awrSnapshots.get(category);
        if (snaps != null && snaps[0] > 0) {
            long snapId = createAwrSnapshot("After " + category);
            snaps[1] = snapId;
        }
    }

    private static void generateAwrReports() {
        if (!awrEnabled || awrSnapshots.isEmpty()) {
            System.out.println("\nNo AWR snapshots to report.");
            return;
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  GENERATING AWR REPORTS");
        System.out.println("=".repeat(90));

        for (Map.Entry<String, long[]> entry : awrSnapshots.entrySet()) {
            String category = entry.getKey();
            long[] snaps = entry.getValue();

            if (snaps[0] <= 0 || snaps[1] <= 0) {
                System.out.println("  Skipping " + category + " - incomplete snapshots");
                continue;
            }

            try {
                String filename = AWR_REPORT_DIR + "/awr_" + category.replaceAll("[^a-zA-Z0-9]", "_") + ".html";
                generateAwrHtmlReport(snaps[0], snaps[1], filename);
                System.out.println("  Generated: " + filename + " (snaps " + snaps[0] + " - " + snaps[1] + ")");
            } catch (Exception e) {
                System.out.println("  Failed to generate report for " + category + ": " + e.getMessage());
            }
        }
    }

    private static void generateAwrHtmlReport(long beginSnap, long endSnap, String filename)
            throws SQLException, IOException {
        String sql = """
            SELECT output FROM TABLE(
                DBMS_WORKLOAD_REPOSITORY.AWR_REPORT_HTML(
                    l_dbid => ?,
                    l_inst_num => ?,
                    l_bid => ?,
                    l_eid => ?
                )
            )
            """;

        StringBuilder report = new StringBuilder();
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setLong(1, dbId);
            ps.setLong(2, instanceNumber);
            ps.setLong(3, beginSnap);
            ps.setLong(4, endSnap);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) {
                        report.append(line).append("\n");
                    }
                }
            }
        }

        Files.writeString(Path.of(filename), report.toString());
    }

    // ==========================================================================
    // Report Generation
    // ==========================================================================

    private static void printFinalReport() {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  FINAL RESULTS: $LOOKUP vs SQL JOIN PERFORMANCE");
        System.out.println("=".repeat(90));

        Map<String, List<TestResult>> grouped = new LinkedHashMap<>();
        for (TestResult result : results.values()) {
            grouped.computeIfAbsent(result.category, k -> new ArrayList<>()).add(result);
        }

        int mongoWins = 0;
        int oracleWins = 0;
        int mongoFailures = 0;

        for (Map.Entry<String, List<TestResult>> entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<TestResult> categoryResults = entry.getValue();

            String header = switch (category) {
                case "baseline" -> "BASELINE JOIN PERFORMANCE";
                case "cardinality" -> "ONE-TO-MANY CARDINALITY";
                case "parallel" -> "ORACLE PARALLEL EXECUTION";
                case "doc_size" -> "DOCUMENT SIZE LIMIT TESTS";
                case "memory" -> "AGGREGATION MEMORY LIMIT";
                case "sort" -> "SORT SPILLOVER TESTS";
                case "pipeline" -> "MULTI-STAGE PIPELINE";
                default -> category.toUpperCase();
            };

            System.out.println("\n" + header + ":");
            System.out.printf("%-40s %15s %15s %10s %s%n",
                    "Test Case", "MongoDB (ns)", "Oracle (ns)", "Ratio", "Winner");
            System.out.println("-".repeat(90));

            for (TestResult result : categoryResults) {
                String winner;
                String ratioStr;

                if (result.mongoNanos < 0) {
                    // MongoDB failed
                    winner = "Oracle (Mongo FAIL)";
                    ratioStr = "FAIL";
                    mongoFailures++;
                    oracleWins++;
                } else {
                    double ratio = (double) result.oracleNanos / Math.max(1, result.mongoNanos);
                    ratioStr = String.format("%.2fx", ratio);
                    if (ratio > 1.0) {
                        winner = "MongoDB";
                        mongoWins++;
                    } else {
                        winner = "Oracle";
                        oracleWins++;
                    }
                }

                System.out.printf("%-40s %15s %15s %10s %s%n",
                        result.description,
                        result.mongoNanos >= 0 ? String.format("%,d", result.mongoNanos) : "FAILED",
                        String.format("%,d", result.oracleNanos),
                        ratioStr,
                        winner);
            }
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("SUMMARY:");
        System.out.println("  MongoDB wins: " + mongoWins);
        System.out.println("  Oracle wins: " + oracleWins);
        System.out.println("  MongoDB failures (limits exceeded): " + mongoFailures);
        System.out.println("=".repeat(90) + "\n");
    }

    private static void generateHtmlReport() {
        try {
            Path reportDir = Path.of("reports");
            Files.createDirectories(reportDir);

            StringBuilder html = new StringBuilder();
            html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>$lookup vs SQL JOIN Benchmark Report</title>
                    <style>
                        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 40px; background: #f5f5f5; }
                        .container { max-width: 1400px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                        h1 { color: #333; border-bottom: 3px solid #0066cc; padding-bottom: 10px; }
                        h2 { color: #0066cc; margin-top: 30px; }
                        table { border-collapse: collapse; width: 100%; margin: 20px 0; }
                        th, td { border: 1px solid #ddd; padding: 12px; text-align: right; }
                        th { background: #0066cc; color: white; }
                        td:first-child { text-align: left; font-weight: 500; }
                        tr:nth-child(even) { background: #f9f9f9; }
                        tr:hover { background: #f0f7ff; }
                        .winner-mongo { color: #cc6600; font-weight: bold; }
                        .winner-oracle { color: #006600; font-weight: bold; }
                        .failure { color: #cc0000; font-weight: bold; }
                        .summary-box { background: #f0f7ff; border: 1px solid #0066cc; border-radius: 8px; padding: 20px; margin: 20px 0; }
                        .metric { font-size: 24px; font-weight: bold; color: #0066cc; }
                        .note { color: #666; font-style: italic; }
                    </style>
                </head>
                <body>
                <div class="container">
                    <h1>$lookup vs SQL JOIN Benchmark Report</h1>
                    <p class="note">MongoDB $lookup aggregation vs Oracle SQL JOIN with PARALLEL execution</p>
                """);

            // Summary
            int mongoWins = 0, oracleWins = 0, mongoFailures = 0;
            for (TestResult r : results.values()) {
                if (r.mongoNanos < 0) {
                    mongoFailures++;
                    oracleWins++;
                } else if ((double) r.oracleNanos / r.mongoNanos > 1.0) {
                    mongoWins++;
                } else {
                    oracleWins++;
                }
            }

            html.append(String.format("""
                <div class="summary-box">
                    <h2>Executive Summary</h2>
                    <p><span class="metric">MongoDB wins: %d</span> | <span class="metric">Oracle wins: %d</span></p>
                    <p>MongoDB failures due to limits: %d</p>
                </div>
                """, mongoWins, oracleWins, mongoFailures));

            // Group results by category
            Map<String, List<TestResult>> grouped = new LinkedHashMap<>();
            for (TestResult result : results.values()) {
                grouped.computeIfAbsent(result.category, k -> new ArrayList<>()).add(result);
            }

            for (Map.Entry<String, List<TestResult>> entry : grouped.entrySet()) {
                String category = entry.getKey();
                String header = switch (category) {
                    case "baseline" -> "Baseline Join Performance";
                    case "cardinality" -> "One-to-Many Cardinality";
                    case "parallel" -> "Oracle Parallel Execution";
                    case "doc_size" -> "Document Size Limit Tests";
                    case "memory" -> "Aggregation Memory Limit";
                    case "sort" -> "Sort Spillover Tests";
                    case "pipeline" -> "Multi-Stage Pipeline";
                    default -> category;
                };

                html.append("<h2>").append(header).append("</h2>\n");
                html.append("""
                    <table>
                    <tr><th>Test Case</th><th>MongoDB (ns)</th><th>Oracle (ns)</th><th>Ratio</th><th>Winner</th><th>Notes</th></tr>
                    """);

                for (TestResult result : entry.getValue()) {
                    String winner, winnerClass, ratioStr;
                    if (result.mongoNanos < 0) {
                        winner = "Oracle";
                        winnerClass = "failure";
                        ratioStr = "FAIL";
                    } else {
                        double ratio = (double) result.oracleNanos / Math.max(1, result.mongoNanos);
                        ratioStr = String.format("%.2fx", ratio);
                        if (ratio > 1.0) {
                            winner = "MongoDB";
                            winnerClass = "winner-mongo";
                        } else {
                            winner = "Oracle";
                            winnerClass = "winner-oracle";
                        }
                    }

                    html.append(String.format(
                            "<tr><td>%s</td><td>%s</td><td>%,d</td><td>%s</td><td class='%s'>%s</td><td>%s</td></tr>\n",
                            result.description,
                            result.mongoNanos >= 0 ? String.format("%,d", result.mongoNanos) : "<span class='failure'>FAILED</span>",
                            result.oracleNanos,
                            ratioStr,
                            winnerClass,
                            winner,
                            result.notes != null ? result.notes : ""));
                }

                html.append("</table>\n");
            }

            html.append("""
                <div class="note" style="margin-top: 40px; text-align: center;">
                    Generated by DocBench - $lookup vs SQL JOIN Benchmark Suite
                </div>
                </div>
                </body>
                </html>
                """);

            Path reportPath = reportDir.resolve("lookup_vs_sql_report.html");
            Files.writeString(reportPath, html.toString());
            System.out.println("\nHTML report generated: " + reportPath.toAbsolutePath());

        } catch (IOException e) {
            System.out.println("Failed to generate HTML report: " + e.getMessage());
        }
    }

    // ==========================================================================
    // Utility Methods
    // ==========================================================================

    private static String formatCount(int count) {
        if (count >= 1_000_000) {
            return (count / 1_000_000) + "M";
        } else if (count >= 1_000) {
            return (count / 1_000) + "K";
        }
        return String.valueOf(count);
    }
}
