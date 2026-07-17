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
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriterSettings;
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

    // JDBC fetch size (default is 10, MongoDB increases to 1000 after first batch)
    private static final int JDBC_FETCH_SIZE = 1000;

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

    // Oracle SODA collection names (prefixed to avoid conflict with JDBC tables)
    private static final String SODA_CUSTOMERS_COLLECTION = "soda_customers";
    private static final String SODA_ORDERS_COLLECTION = "soda_orders";
    private static final String SODA_PRODUCTS_COLLECTION = "soda_products";
    private static final String SODA_LARGE_ORDERS_COLLECTION = "soda_large_orders";

    // Relational tables (traditional SQL - no JSON)
    private static final String CUSTOMERS_REL_TABLE = "benchmark_customers_rel";
    private static final String ORDERS_REL_TABLE = "benchmark_orders_rel";
    private static final String PRODUCTS_REL_TABLE = "benchmark_products_rel";
    private static final String LARGE_ORDERS_REL_TABLE = "benchmark_large_orders_rel";

    // Hybrid tables (JSON + relational virtual columns for joins)
    private static final String CUSTOMERS_HYBRID_TABLE = "benchmark_customers_hyb";
    private static final String ORDERS_HYBRID_TABLE = "benchmark_orders_hyb";

    // ==========================================================================
    // Oracle Edition & Mode Detection
    // ==========================================================================

    private static boolean oracleFreeEdition = false;
    private static int maxParallelDegree = 16;

    // Oracle connectivity mode: JDBC (default) or MongoDB API ($sql)
    private static boolean useOracleMongoApi = false;

    // No-index mode: skip creating indexes to measure impact of indexes on performance
    // Also skips MongoDB tests entirely (MongoDB performance without indexes is extremely poor)
    // Set via system property: -Dbenchmark.noindex=true
    private static final boolean noIndexMode = Boolean.getBoolean("benchmark.noindex");

    // Skip MongoDB in no-index mode (controlled by noIndexMode flag)
    private static final boolean skipMongoDB = noIndexMode;

    // ==========================================================================
    // Report Data Persistence (for incremental test runs)
    // ==========================================================================

    // File to persist test results between runs - allows running tests individually
    // and accumulating results in the report
    private static final String RESULTS_PERSISTENCE_FILE = "build/benchmark-results.json";

    // ==========================================================================
    // Results Storage
    // ==========================================================================

    private static final Map<String, TestResult> results = new LinkedHashMap<>();

    // Triple results storage: [mongoNanos, oracleApiNanos, oracleJdbcNanos]
    private static final Map<String, long[]> tripleResults = new LinkedHashMap<>();

    // Quad results storage: [mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos]
    private static final Map<String, long[]> quadResults = new LinkedHashMap<>();

    // Quint results storage for scan tests: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos]
    private static final Map<String, long[]> quintResults = new LinkedHashMap<>();

    // Sextet results storage for hybrid tests: [mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos]
    private static final Map<String, long[]> sextetResults = new LinkedHashMap<>();

    // AWR snapshot tracking
    private static final Map<String, long[]> awrSnapshots = new LinkedHashMap<>();
    private static final Map<String, String> awrReportContent = new LinkedHashMap<>();  // category -> AWR HTML
    private static final String AWR_REPORT_DIR = "build/reports/awr";
    private static final String SQL_MONITOR_DIR = "reports/sql_monitor";
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

    // SQL details for each test (for report tabs)
    private record SqlDetails(
            String jdbcSql,
            String apiSql,
            String explainPlan,
            String sqlMonitorJdbcJson,      // SQL Monitor for JDBC JSON queries
            String sqlMonitorApiSql,         // SQL Monitor for Oracle API $sql queries
            String sqlMonitorRelational,     // SQL Monitor for JDBC Relational queries
            String sqlMonitorOracleNative,   // SQL Monitor for Oracle Native MongoDB API queries
            String sqlMonitorHybrid,         // SQL Monitor for JDBC Hybrid queries
            String mongoPipeline,
            String mongoExplain,
            String relationalSql,
            String relationalExplainPlan,
            String hybridSql,                // Hybrid JOIN SQL (virtual columns)
            String hybridExplainPlan,        // Explain plan for hybrid query
            String oracleNativePipeline,     // Oracle MongoDB API native pipeline (no $sql)
            String oracleNativeExplain       // Oracle MongoDB API native explain plan
    ) {}

    private static final Map<String, SqlDetails> sqlDetailsMap = new LinkedHashMap<>();

    // Track last SQL/pipeline executed for capture
    private static String lastJdbcSql = "";
    private static String lastApiSql = "";
    private static String lastRelationalSql = "";
    private static String lastHybridSql = "";
    private static String lastMongoPipeline = "";
    private static String lastMongoExplain = "";
    private static String lastOracleNativePipeline = "";
    private static String lastOracleNativeExplain = "";

    private static void storeTripleResult(String testId, String description, String category,
                                          long mongoNanos, long oracleApiNanos, long oracleJdbcNanos, String notes) {
        tripleResults.put(testId, new long[]{mongoNanos, oracleApiNanos, oracleJdbcNanos});
        // Store best Oracle result for backward compatibility
        long bestOracleNanos = oracleApiNanos > 0 ? Math.min(oracleApiNanos, oracleJdbcNanos) : oracleJdbcNanos;
        results.put(testId, new TestResult(testId, description, mongoNanos, bestOracleNanos, category, notes));

        // Capture SQL details if we have SQL to capture
        if (!lastJdbcSql.isEmpty() || !lastMongoPipeline.isEmpty() || !lastRelationalSql.isEmpty() || !lastOracleNativePipeline.isEmpty() || !lastHybridSql.isEmpty()) {
            storeSqlDetails(testId, lastJdbcSql, lastApiSql, lastMongoPipeline, lastMongoExplain, lastRelationalSql,
                    lastOracleNativePipeline, lastOracleNativeExplain, lastHybridSql);
            // Reset for next test
            lastJdbcSql = "";
            lastApiSql = "";
            lastRelationalSql = "";
            lastHybridSql = "";
            lastMongoPipeline = "";
            lastMongoExplain = "";
            lastOracleNativePipeline = "";
            lastOracleNativeExplain = "";
        }

        // Persist results after each test to support incremental test runs
        persistResults();
    }

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

        // Create Oracle tables for JDBC mode (uses 'data' column to match MongoDB API format)
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

                // Connection successful - set up collections (using SODA-specific names)
                oracleCustomersCollection = oracleMongoDatabase.getCollection(SODA_CUSTOMERS_COLLECTION);
                oracleOrdersCollection = oracleMongoDatabase.getCollection(SODA_ORDERS_COLLECTION);
                oracleProductsCollection = oracleMongoDatabase.getCollection(SODA_PRODUCTS_COLLECTION);
                oracleLargeOrdersCollection = oracleMongoDatabase.getCollection(SODA_LARGE_ORDERS_COLLECTION);

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
        if (skipMongoDB) {
            System.out.println("  MongoDB: SKIPPED (no-index mode - performance without indexes is extremely poor)");
        } else {
            System.out.println("  MongoDB: $lookup aggregation operator (single-threaded)");
        }
        if (useOracleMongoApi) {
            System.out.println("  Oracle:  $sql aggregation operator with PARALLEL hints (MongoDB API)");
        } else {
            System.out.println("  Oracle:  SQL JOIN with PARALLEL hints (JDBC)");
        }
        System.out.println("  " + "-".repeat(84));
        System.out.println("  Oracle Mode: " + (useOracleMongoApi ? "MongoDB API ($sql)" : "JDBC (default)"));
        System.out.println("  Oracle Edition: " + (oracleFreeEdition ? "Free (2 CPU limit)" : "Enterprise/Standard"));
        System.out.println("  Max Parallel Degree: " + maxParallelDegree);
        System.out.println("  Index Mode: " + (noIndexMode ? "NO INDEXES (baseline comparison)" : "WITH INDEXES (default)"));
        if (skipMongoDB) {
            System.out.println("  MongoDB: SKIPPED (no-index mode)");
        }
        System.out.println("=".repeat(90));

        if (!useOracleMongoApi) {
            System.out.println("\n  INFO: Using JDBC for Oracle queries (default mode).");
            System.out.println("  To enable $sql aggregation, configure Oracle MongoDB API:");
            System.out.println("    1. Install ORDS with MongoDB API support");
            System.out.println("    2. Add 'oracle.mongodb.uri' to config/local.properties\n");
        }

        // Load previously saved test results for incremental reporting
        loadPersistedResults();
    }

    @AfterAll
    static void teardown() {
        printFinalReport();

        // Generate AWR reports BEFORE HTML report so content is available for embedding
        if (awrEnabled) {
            generateAwrReports();
        }

        generateHtmlReport();

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

        // Cleanup Oracle MongoDB API collections (SODA)
        if (oracleMongoClient != null) {
            try {
                oracleMongoDatabase.getCollection(SODA_CUSTOMERS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(SODA_ORDERS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(SODA_PRODUCTS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(SODA_LARGE_ORDERS_COLLECTION).drop();
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
            // Drop indexes first (they might be left over from failed runs)
            String[] indexNames = {"idx_cust_id", "idx_orders_custid", "idx_orders_prodid",
                    "idx_large_orders_custid", "idx_prod_id", "idx_orders_rel_custid",
                    "idx_products_rel_id", "idx_large_orders_rel_custid",
                    "idx_cust_hyb_id", "idx_orders_hyb_custid"};
            for (String idx : indexNames) {
                try { stmt.execute("DROP INDEX " + idx); } catch (SQLException ignored) {}
            }

            // Drop existing tables (ignore errors if they don't exist)
            for (String table : new String[]{ORDERS_COLLECTION, LARGE_ORDERS_COLLECTION, CUSTOMERS_COLLECTION, PRODUCTS_COLLECTION,
                    CUSTOMERS_REL_TABLE, ORDERS_REL_TABLE, PRODUCTS_REL_TABLE, LARGE_ORDERS_REL_TABLE,
                    CUSTOMERS_HYBRID_TABLE, ORDERS_HYBRID_TABLE}) {
                try { stmt.execute("DROP TABLE " + table + " PURGE"); } catch (SQLException ignored) {}
            }

            // Create tables with JSON columns - use 'data' column to match MongoDB API format
            executeWithDiag(stmt, "CREATE TABLE " + CUSTOMERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, data JSON)");
            executeWithDiag(stmt, "CREATE TABLE " + ORDERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, data JSON)");
            executeWithDiag(stmt, "CREATE TABLE " + PRODUCTS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, data JSON)");
            executeWithDiag(stmt, "CREATE TABLE " + LARGE_ORDERS_COLLECTION + " (id VARCHAR2(100) PRIMARY KEY, data JSON)");

            // Create function-based indexes using JSON_VALUE for scalar extraction
            // These enable index lookups for nested loop joins on small result sets
            if (!noIndexMode) {
                executeWithDiag(stmt, "CREATE INDEX idx_cust_id ON " + CUSTOMERS_COLLECTION + " (JSON_VALUE(data, '$._id'))");
                executeWithDiag(stmt, "CREATE INDEX idx_orders_custid ON " + ORDERS_COLLECTION + " (JSON_VALUE(data, '$.customer_id'))");
                executeWithDiag(stmt, "CREATE INDEX idx_orders_prodid ON " + ORDERS_COLLECTION + " (JSON_VALUE(data, '$.product_id'))");
                executeWithDiag(stmt, "CREATE INDEX idx_large_orders_custid ON " + LARGE_ORDERS_COLLECTION + " (JSON_VALUE(data, '$.customer_id'))");
                executeWithDiag(stmt, "CREATE INDEX idx_prod_id ON " + PRODUCTS_COLLECTION + " (JSON_VALUE(data, '$._id'))");
            } else {
                System.out.println("  NO-INDEX MODE: Skipping JSON function-based indexes");
            }

            // =================================================================
            // Create RELATIONAL tables (traditional SQL - no JSON overhead)
            // =================================================================
            executeWithDiag(stmt, "CREATE TABLE " + CUSTOMERS_REL_TABLE + " (" +
                    "customer_id VARCHAR2(100) PRIMARY KEY, " +
                    "name VARCHAR2(200), " +
                    "email VARCHAR2(200), " +
                    "region VARCHAR2(20), " +
                    "created_at VARCHAR2(20))");

            executeWithDiag(stmt, "CREATE TABLE " + ORDERS_REL_TABLE + " (" +
                    "order_id VARCHAR2(100) PRIMARY KEY, " +
                    "customer_id VARCHAR2(100) NOT NULL, " +
                    "product_id VARCHAR2(100), " +
                    "order_date VARCHAR2(20), " +
                    "total NUMBER(10,2), " +
                    "status VARCHAR2(20))");

            // Create indexes for relational join
            if (!noIndexMode) {
                executeWithDiag(stmt, "CREATE INDEX idx_orders_rel_custid ON " + ORDERS_REL_TABLE + "(customer_id)");
                executeWithDiag(stmt, "CREATE INDEX idx_orders_rel_prodid ON " + ORDERS_REL_TABLE + "(product_id)");
            } else {
                System.out.println("  NO-INDEX MODE: Skipping relational orders indexes");
            }

            // Products relational table (for G3 chained lookups)
            executeWithDiag(stmt, "CREATE TABLE " + PRODUCTS_REL_TABLE + " (" +
                    "product_id VARCHAR2(100) PRIMARY KEY, " +
                    "name VARCHAR2(200), " +
                    "category VARCHAR2(100), " +
                    "price NUMBER(10,2))");

            // Large orders relational table (for D document size tests)
            executeWithDiag(stmt, "CREATE TABLE " + LARGE_ORDERS_REL_TABLE + " (" +
                    "order_id VARCHAR2(100) PRIMARY KEY, " +
                    "customer_id VARCHAR2(100) NOT NULL, " +
                    "order_date VARCHAR2(20), " +
                    "total NUMBER(10,2), " +
                    "status VARCHAR2(20), " +
                    "padding CLOB)");

            // Create indexes for new relational tables
            if (!noIndexMode) {
                executeWithDiag(stmt, "CREATE INDEX idx_large_orders_rel_custid ON " + LARGE_ORDERS_REL_TABLE + "(customer_id)");
            } else {
                System.out.println("  NO-INDEX MODE: Skipping large orders relational index");
            }

            // =================================================================
            // Create HYBRID tables (JSON + virtual columns for joins)
            // Virtual columns are computed from JSON, enabling standard B-tree indexes
            // on join columns while preserving full JSON document storage
            // =================================================================
            executeWithDiag(stmt, "CREATE TABLE " + CUSTOMERS_HYBRID_TABLE + " (" +
                    "id VARCHAR2(100) PRIMARY KEY, " +
                    "data JSON, " +
                    "customer_id VARCHAR2(100) GENERATED ALWAYS AS (JSON_VALUE(data, '$._id' RETURNING VARCHAR2(100))) VIRTUAL)");

            executeWithDiag(stmt, "CREATE TABLE " + ORDERS_HYBRID_TABLE + " (" +
                    "id VARCHAR2(100) PRIMARY KEY, " +
                    "data JSON, " +
                    "customer_id VARCHAR2(100) GENERATED ALWAYS AS (JSON_VALUE(data, '$.customer_id' RETURNING VARCHAR2(100))) VIRTUAL, " +
                    "order_id VARCHAR2(100) GENERATED ALWAYS AS (JSON_VALUE(data, '$._id' RETURNING VARCHAR2(100))) VIRTUAL)");

            // Create indexes on hybrid virtual columns (standard B-tree, not function-based)
            if (!noIndexMode) {
                executeWithDiag(stmt, "CREATE INDEX idx_cust_hyb_id ON " + CUSTOMERS_HYBRID_TABLE + "(customer_id)");
                executeWithDiag(stmt, "CREATE INDEX idx_orders_hyb_custid ON " + ORDERS_HYBRID_TABLE + "(customer_id)");
            } else {
                System.out.println("  NO-INDEX MODE: Skipping hybrid table indexes");
            }

            // Note: Statistics gathered after data insertion in gatherOracleStats()
        }
    }

    private static void executeWithDiag(Statement stmt, String sql) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println("SQL Error executing: " + sql);
            System.err.println("  Error code: " + e.getErrorCode());
            System.err.println("  Message: " + e.getMessage());
            throw e;
        }
    }

    private static void analyzeJoinPlan() {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Use dot notation for type-preserving JSON access
            // IMPORTANT: Use subquery with ROWNUM BEFORE the join to match actual benchmark query structure.
            // This allows the optimizer to use NESTED LOOPS + INDEX instead of HASH JOIN.
            // Query structure: (SELECT ... WHERE ROWNUM <= N) subquery joined to orders table
            String sql = "SELECT c.data, o.data FROM " +
                    "(SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= 100) c " +
                    "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            stmt.execute("EXPLAIN PLAN FOR " + sql);

            System.out.println("\n  Oracle Execution Plan for JSON JOIN:");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT LPAD(' ', 2*LEVEL) || operation || ' ' || options || ' ' || object_name || " +
                    "' (Cost: ' || cost || ')' as plan_line FROM PLAN_TABLE START WITH id = 0 " +
                    "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY position")) {
                while (rs.next()) {
                    System.out.println("    " + rs.getString(1));
                }
            }
            stmt.execute("DELETE FROM PLAN_TABLE");

            // Also show relational table plan for comparison
            String relSql = "SELECT c.customer_id, c.name, o.order_id, o.total FROM " +
                    "(SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= 100) c " +
                    "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id";
            stmt.execute("EXPLAIN PLAN FOR " + relSql);

            System.out.println("\n  Oracle Execution Plan for RELATIONAL JOIN:");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT LPAD(' ', 2*LEVEL) || operation || ' ' || options || ' ' || object_name || " +
                    "' (Cost: ' || cost || ')' as plan_line FROM PLAN_TABLE START WITH id = 0 " +
                    "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY position")) {
                while (rs.next()) {
                    System.out.println("    " + rs.getString(1));
                }
            }
            stmt.execute("DELETE FROM PLAN_TABLE");

            // Show hybrid table plan (virtual columns with B-tree indexes)
            String hybridSql = "SELECT c.data, o.data FROM " +
                    "(SELECT * FROM " + CUSTOMERS_HYBRID_TABLE + " WHERE ROWNUM <= 100) c " +
                    "JOIN " + ORDERS_HYBRID_TABLE + " o ON c.customer_id = o.customer_id";
            stmt.execute("EXPLAIN PLAN FOR " + hybridSql);

            System.out.println("\n  Oracle Execution Plan for HYBRID JOIN (virtual columns):");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT LPAD(' ', 2*LEVEL) || operation || ' ' || options || ' ' || object_name || " +
                    "' (Cost: ' || cost || ')' as plan_line FROM PLAN_TABLE START WITH id = 0 " +
                    "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY position")) {
                while (rs.next()) {
                    System.out.println("    " + rs.getString(1));
                }
            }
            stmt.execute("DELETE FROM PLAN_TABLE");
        } catch (SQLException e) {
            System.out.println("  Could not analyze plan: " + e.getMessage());
        }
    }

    /**
     * Captures explain plan for a given SQL statement.
     * Returns formatted plan text for display in report.
     */
    private static String captureExplainPlan(String sql) {
        StringBuilder plan = new StringBuilder();
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("EXPLAIN PLAN FOR " + sql);

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT LPAD(' ', 2*LEVEL) || operation || ' ' || NVL(options, '') || ' ' || NVL(object_name, '') || " +
                    "' (Cost: ' || NVL(TO_CHAR(cost), 'N/A') || ', Rows: ' || NVL(TO_CHAR(cardinality), 'N/A') || ')' as plan_line " +
                    "FROM PLAN_TABLE START WITH id = 0 " +
                    "CONNECT BY PRIOR id = parent_id ORDER SIBLINGS BY position")) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append("\n");
                }
            }
            stmt.execute("DELETE FROM PLAN_TABLE");
        } catch (SQLException e) {
            plan.append("Could not capture plan: ").append(e.getMessage());
        }
        return plan.toString();
    }

    /**
     * Captures SQL Monitor report for a given SQL statement in ACTIVE HTML format.
     * Uses the MONITOR hint to force monitoring, then retrieves the interactive HTML report.
     * Falls back gracefully if SQL Monitor is not available (e.g., Free Edition).
     */
    /**
     * Captures SQL Monitor report for a given SQL statement in ACTIVE HTML format.
     * Executes the SQL with MONITOR hint, retrieves the SQL_ID, then gets the report.
     */
    private static String captureSqlMonitorHtmlWithSqlId(String sql) {
        StringBuilder report = new StringBuilder();

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Execute with MONITOR hint to force SQL monitoring
            String monitoredSql;
            // Check if SQL already has a hint block - if so, insert MONITOR into it
            if (sql.matches("(?is).*SELECT\\s+/\\*\\+.*\\*/.*")) {
                // Insert MONITOR into existing hint block
                monitoredSql = sql.replaceFirst("(?i)/\\*\\+\\s*", "/*+ MONITOR ");
            } else {
                // No existing hint, add new hint block
                monitoredSql = sql.replaceFirst("(?i)SELECT\\s+", "SELECT /*+ MONITOR */ ");
            }

            // Execute the SQL to generate monitoring data
            try (ResultSet rs = stmt.executeQuery(monitoredSql)) {
                while (rs.next()) {
                    // Just consume results to complete execution
                }
            }

            // Get the SQL_ID from V$SQL_MONITOR for this session
            String sqlId = null;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT SQL_ID FROM V$SQL_MONITOR WHERE SID = SYS_CONTEXT('USERENV', 'SID') " +
                    "AND SQL_TEXT LIKE '%MONITOR%' ORDER BY SQL_EXEC_START DESC FETCH FIRST 1 ROW ONLY")) {
                if (rs.next()) {
                    sqlId = rs.getString(1);
                }
            }

            if (sqlId == null) {
                // Fallback: try without the MONITOR filter
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT SQL_ID FROM V$SQL_MONITOR WHERE SID = SYS_CONTEXT('USERENV', 'SID') " +
                        "ORDER BY SQL_EXEC_START DESC FETCH FIRST 1 ROW ONLY")) {
                    if (rs.next()) {
                        sqlId = rs.getString(1);
                    }
                }
            }

            if (sqlId != null) {
                // Get SQL Monitor report for the specific SQL_ID
                try (CallableStatement cs = oracleJdbcConnection.prepareCall(
                        "BEGIN :1 := DBMS_SQLTUNE.REPORT_SQL_MONITOR(sql_id => :2, type => 'ACTIVE', report_level => 'ALL'); END;")) {
                    cs.registerOutParameter(1, java.sql.Types.CLOB);
                    cs.setString(2, sqlId);
                    cs.execute();
                    String monitorReport = cs.getString(1);
                    if (monitorReport != null && !monitorReport.isEmpty()) {
                        report.append(monitorReport);
                    } else {
                        report.append("<p>SQL Monitor: No data available for SQL_ID ").append(sqlId).append("</p>");
                    }
                }
            } else {
                report.append("<p>SQL Monitor: Could not find SQL_ID in V$SQL_MONITOR</p>");
            }
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg.contains("PLS-00201") || msg.contains("DBMS_SQLTUNE")) {
                report.append("<p>SQL Monitor not available (requires Oracle Enterprise Edition with Tuning Pack)</p>");
            } else if (msg.contains("ORA-00942")) {
                report.append("<p>SQL Monitor: Table or view does not exist</p>");
            } else {
                report.append("<p>Could not capture SQL Monitor: ").append(escapeHtml(msg)).append("</p>");
            }
        }
        return report.toString();
    }

    /**
     * Captures SQL Monitor report for Oracle API $sql queries.
     * The $sql query originally runs through MongoDB wire protocol, so we re-execute via JDBC
     * with MONITOR hint to capture the SQL Monitor report.
     *
     * Note: The apiSql parameter contains the raw SQL string (not wrapped in $sql JSON).
     */
    private static String captureSqlMonitorForApiSql(String apiSql) {
        if (apiSql == null || apiSql.isEmpty()) {
            return "";
        }

        // Execute the same SQL via JDBC with MONITOR hint to capture SQL Monitor.
        // This proves server-side execution is identical between JDBC and $sql.
        // The only unmeasured difference is $sql wire protocol overhead.
        return captureSqlMonitorHtmlWithSqlId(apiSql);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * Writes a SQL Monitor report to a file and returns the filename (relative path).
     * Returns empty string if content is empty or contains "not available".
     */
    private static String writeSqlMonitorFile(String testId, String protocol, String content) {
        if (content == null || content.isEmpty() || content.contains("Could not find SQL_ID")) {
            return "";
        }
        try {
            Path dir = Path.of(SQL_MONITOR_DIR);
            Files.createDirectories(dir);
            String safeTestId = testId.replaceAll("[^a-zA-Z0-9_]", "_");
            String filename = "monitor_" + safeTestId + "_" + protocol + ".html";
            Path filePath = dir.resolve(filename);
            Files.writeString(filePath, content);
            // Return path relative to reports/ directory (where main report lives)
            return "sql_monitor/" + filename;
        } catch (IOException e) {
            System.err.println("Failed to write SQL Monitor file: " + e.getMessage());
            return "";
        }
    }

    /**
     * Stores SQL details for a test case (for report generation).
     * SQL Monitor reports are written to separate files to keep main report small.
     */
    private static void storeSqlDetails(String testId, String jdbcSql, String apiSql,
                                        String mongoPipeline, String mongoExplain,
                                        String relationalSql,
                                        String oracleNativePipeline, String oracleNativeExplain) {
        storeSqlDetails(testId, jdbcSql, apiSql, mongoPipeline, mongoExplain, relationalSql,
                       oracleNativePipeline, oracleNativeExplain, "");
    }

    private static void storeSqlDetails(String testId, String jdbcSql, String apiSql,
                                        String mongoPipeline, String mongoExplain,
                                        String relationalSql,
                                        String oracleNativePipeline, String oracleNativeExplain,
                                        String hybridSql) {
        String explainPlan = !jdbcSql.isEmpty() ? captureExplainPlan(jdbcSql) : "";
        String relationalExplainPlan = !relationalSql.isEmpty() ? captureExplainPlan(relationalSql) : "";
        String hybridExplainPlan = !hybridSql.isEmpty() ? captureExplainPlan(hybridSql) : "";

        // Capture SQL Monitor for all Oracle protocols and write to files
        String sqlMonitorJdbcJson = "";
        String sqlMonitorApiSql = "";
        String sqlMonitorRelational = "";
        String sqlMonitorOracleNative = "";
        String sqlMonitorHybrid = "";

        if (!jdbcSql.isEmpty()) {
            String content = captureSqlMonitorHtmlWithSqlId(jdbcSql);
            sqlMonitorJdbcJson = writeSqlMonitorFile(testId, "jdbc_json", content);
        }
        if (!apiSql.isEmpty()) {
            String content = captureSqlMonitorForApiSql(apiSql);
            sqlMonitorApiSql = writeSqlMonitorFile(testId, "api_sql", content);
        }
        if (!relationalSql.isEmpty()) {
            String content = captureSqlMonitorHtmlWithSqlId(relationalSql);
            sqlMonitorRelational = writeSqlMonitorFile(testId, "relational", content);
        }
        if (!hybridSql.isEmpty()) {
            String content = captureSqlMonitorHtmlWithSqlId(hybridSql);
            sqlMonitorHybrid = writeSqlMonitorFile(testId, "hybrid", content);
        }
        // Extract SQL from Oracle Native explain and capture SQL Monitor
        // The Oracle MongoDB API generates SQL that references internal SODA table columns
        // (DATA, RESID, ETAG) which exist in the SODA-managed table structure.
        // We can execute this SQL via JDBC to capture the SQL Monitor report.
        if (!oracleNativeExplain.isEmpty()) {
            String extractedSql = extractSqlFromOracleExplain(oracleNativeExplain);
            if (!extractedSql.isEmpty()) {
                String content = captureSqlMonitorHtmlWithSqlId(extractedSql);
                if (content.isEmpty() || content.contains("Could not find SQL_ID")) {
                    // Fallback: show the SQL if we couldn't capture SQL Monitor
                    content = "<p>SQL Monitor capture failed. Generated SQL:</p>" +
                            "<pre>" + escapeHtml(extractedSql) + "</pre>";
                }
                sqlMonitorOracleNative = writeSqlMonitorFile(testId, "oracle_native", content);
            }
        }

        // Now sqlMonitor fields contain file paths instead of content
        sqlDetailsMap.put(testId, new SqlDetails(jdbcSql, apiSql, explainPlan,
                                                  sqlMonitorJdbcJson, sqlMonitorApiSql, sqlMonitorRelational,
                                                  sqlMonitorOracleNative, sqlMonitorHybrid,
                                                  mongoPipeline, mongoExplain,
                                                  relationalSql, relationalExplainPlan,
                                                  hybridSql, hybridExplainPlan,
                                                  oracleNativePipeline, oracleNativeExplain));
    }

    /**
     * Extracts the generated SQL statement from Oracle MongoDB API explain output.
     * The explain output contains the SQL that Oracle generates internally.
     */
    private static String extractSqlFromOracleExplain(String explainJson) {
        if (explainJson == null || explainJson.isEmpty()) {
            return "";
        }
        try {
            // Oracle's explain output contains a "sql" field with the generated SQL
            // Format varies but typically: {"queryPlanner": {..., "sql": "SELECT ..."}}
            // or directly: {"sql": "SELECT ..."}
            Document explain = Document.parse(explainJson);

            // Try direct "sql" field first
            String sql = explain.getString("sql");
            if (sql != null && !sql.isEmpty()) {
                return sql;
            }

            // Try nested in queryPlanner
            Document queryPlanner = explain.get("queryPlanner", Document.class);
            if (queryPlanner != null) {
                // Try "generatedSql" first (Oracle MongoDB API uses this field)
                sql = queryPlanner.getString("generatedSql");
                if (sql != null && !sql.isEmpty()) {
                    return sql;
                }
                // Fall back to "sql"
                sql = queryPlanner.getString("sql");
                if (sql != null && !sql.isEmpty()) {
                    return sql;
                }
            }

            // Try nested in serverInfo or other common locations
            Document serverInfo = explain.get("serverInfo", Document.class);
            if (serverInfo != null) {
                sql = serverInfo.getString("sql");
                if (sql != null && !sql.isEmpty()) {
                    return sql;
                }
            }

            // Search for any field containing SQL-like content
            for (String key : explain.keySet()) {
                Object value = explain.get(key);
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (strValue.toUpperCase().startsWith("SELECT ") ||
                        strValue.toUpperCase().startsWith("WITH ")) {
                        return strValue;
                    }
                }
            }

            return "";
        } catch (Exception e) {
            System.err.println("Failed to extract SQL from Oracle explain: " + e.getMessage());
            return "";
        }
    }

    /**
     * Backward-compatible overload that doesn't include Oracle native API info.
     */
    private static void storeSqlDetails(String testId, String jdbcSql, String apiSql,
                                        String mongoPipeline, String mongoExplain,
                                        String relationalSql) {
        storeSqlDetails(testId, jdbcSql, apiSql, mongoPipeline, mongoExplain, relationalSql, "", "");
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

    /**
     * Converts a MongoDB aggregation pipeline to a JSON string for display.
     */
    private static String pipelineToJson(List<Bson> pipeline) {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < pipeline.size(); i++) {
            Bson stage = pipeline.get(i);
            BsonDocument bsonDoc = stage.toBsonDocument(BsonDocument.class, mongoDatabase.getCodecRegistry());
            json.append("  ").append(bsonDoc.toJson());
            if (i < pipeline.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("]");
        return json.toString();
    }

    /**
     * Captures MongoDB explain output for an aggregation pipeline.
     */
    private static String captureMongoExplain(MongoCollection<Document> collection, List<Bson> pipeline) {
        try {
            Document explainCmd = new Document("aggregate", collection.getNamespace().getCollectionName())
                    .append("pipeline", pipeline)
                    .append("explain", true)
                    .append("cursor", new Document());
            Document explainResult = mongoDatabase.runCommand(explainCmd);
            return explainResult.toJson(JsonWriterSettings.builder().indent(true).build());
        } catch (Exception e) {
            return "Could not capture explain: " + e.getMessage();
        }
    }

    /**
     * Tracks MongoDB pipeline and explain for later report generation.
     */
    private static void trackMongoPipeline(MongoCollection<Document> collection, List<Bson> pipeline) {
        lastMongoPipeline = pipelineToJson(pipeline);
        lastMongoExplain = captureMongoExplain(collection, pipeline);
    }

    // ==========================================================================
    // Category A: Baseline Join Performance (Order 0-9)
    // ==========================================================================

    @Test
    @Order(0)
    @DisplayName("A0: Protocol overhead baseline - empty operations")
    void protocolOverheadBaseline() {
        awrSnapshotBefore("A0_baseline");

        // Measure MongoDB empty aggregation (skip in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoEmptyAggregation();

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

        String mongoStr = mongoNanos >= 0 ? String.format("%,12d", mongoNanos) : "     SKIPPED";
        System.out.printf("  A0: Protocol overhead      - MongoDB: %s ns | Oracle: %,12d ns%n",
                mongoStr, oracleNanos);

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

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(100);
            if (useOracleMongoApi) runOracleApiJoin(100, 1);
            runOracleJdbcJoin(100, 1);
            runOracleRelationalJoin(100, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("A1_simple_1K", "Simple FK join - 1K customers", "baseline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                customerCount + " customers, " + ordersPerCustomer + " orders each");

        printQuadResult("A1: Simple join 1K", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

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

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            if (useOracleMongoApi) runOracleApiJoin(1000, 1);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("A2_simple_10K", "Simple FK join - 10K customers", "baseline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                customerCount + " customers, " + ordersPerCustomer + " orders each");

        printQuadResult("A2: Simple join 10K", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

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

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 1);
            runOracleJdbcJoin(10000, 1);
            runOracleRelationalJoin(10000, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("A3_simple_100K", "Simple FK join - 100K customers", "baseline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                customerCount + " customers, " + ordersPerCustomer + " orders each");

        printQuadResult("A3: Simple join 100K", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("A3_simple_100K");
    }

    // ==========================================================================
    // Category B: One-to-Many Cardinality (Order 10-19)
    // ==========================================================================

    @Test
    @Order(10)
    @DisplayName("B0: 1:1 join ratio")
    void cardinalityTest_1to1() {
        awrSnapshotBefore("B0_1to1");

        int customerCount = 10_000;
        int ordersPerCustomer = 1;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            if (useOracleMongoApi) runOracleApiJoin(1000, 1);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("B0_1to1", "1:1 join ratio", "cardinality",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "1 order per customer");

        printQuadResult("B0: 1:1 cardinality", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("B0_1to1");
    }

    @Test
    @Order(11)
    @DisplayName("B1: 1:10 join ratio")
    void cardinalityTest_1to10() {
        awrSnapshotBefore("B1_1to10");

        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            if (useOracleMongoApi) runOracleApiJoin(1000, 1);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("B1_1to10", "1:10 join ratio", "cardinality",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "10 orders per customer");

        printQuadResult("B1: 1:10 cardinality", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("B1_1to10");
    }

    @Test
    @Order(12)
    @DisplayName("B2: 1:100 join ratio")
    void cardinalityTest_1to100() {
        awrSnapshotBefore("B2_1to100");

        int customerCount = 1_000;
        int ordersPerCustomer = 100;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(100);
            if (useOracleMongoApi) runOracleApiJoin(100, 1);
            runOracleJdbcJoin(100, 1);
            runOracleRelationalJoin(100, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("B2_1to100", "1:100 join ratio", "cardinality",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "100 orders per customer - MongoDB materializes all");

        printQuadResult("B2: 1:100 cardinality", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("B2_1to100");
    }

    @Test
    @Order(13)
    @DisplayName("B3: 1:1000 join ratio")
    void cardinalityTest_1to1000() {
        awrSnapshotBefore("B3_1to1000");

        int customerCount = 100;
        int ordersPerCustomer = 1000;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10);
            if (useOracleMongoApi) runOracleApiJoin(10, 1);
            runOracleJdbcJoin(10, 1);
            runOracleRelationalJoin(10, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("B3_1to1000", "1:1000 join ratio", "cardinality",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "1000 orders per customer - Memory pressure on MongoDB");

        printQuadResult("B3: 1:1000 cardinality", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("B3_1to1000");
    }

    // ==========================================================================
    // Category C: Oracle Parallel Execution (Order 20-29)
    // ==========================================================================

    @Test
    @Order(20)
    @DisplayName("C0: Large join - no parallel (PARALLEL 1)")
    void parallelJoin_1thread() {
        awrSnapshotBefore("C0_parallel_1");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 1);
            runOracleJdbcJoin(10000, 1);
            runOracleRelationalJoin(10000, 1);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("C0_parallel_1", "Large join - PARALLEL(1)", "parallel",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "Baseline single-threaded execution");

        printQuadResult("C0: Parallel(1)", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("C0_parallel_1");
    }

    @Test
    @Order(21)
    @DisplayName("C1: Large join - 2 threads (PARALLEL 2)")
    void parallelJoin_2threads() {
        awrSnapshotBefore("C1_parallel_2");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 2);
            runOracleJdbcJoin(10000, 2);
            runOracleRelationalJoin(10000, 2);
        }

        // Measure all four modes (skip MongoDB in no-index mode; JDBC uses PARALLEL hint)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 2);

        storeQuadResult("C1_parallel_2", "Large join - PARALLEL(2)", "parallel",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "2 parallel worker threads");

        printQuadResult("C1: Parallel(2)", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("C1_parallel_2");
    }

    @Test
    @Order(22)
    @DisplayName("C2: Large join - 4 threads (PARALLEL 4)")
    void parallelJoin_4threads() {
        assumeTrue(maxParallelDegree >= 4,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        awrSnapshotBefore("C2_parallel_4");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 4);
            runOracleJdbcJoin(10000, 4);
            runOracleRelationalJoin(10000, 4);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 4) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 4);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 4);

        storeQuadResult("C2_parallel_4", "Large join - PARALLEL(4)", "parallel",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "4 parallel worker threads");

        printQuadResult("C2: Parallel(4)", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("C2_parallel_4");
    }

    @Test
    @Order(23)
    @DisplayName("C3: Large join - 8 threads (PARALLEL 8)")
    void parallelJoin_8threads() {
        assumeTrue(maxParallelDegree >= 8,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        awrSnapshotBefore("C3_parallel_8");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 8);
            runOracleJdbcJoin(10000, 8);
            runOracleRelationalJoin(10000, 8);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 8) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 8);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 8);

        storeQuadResult("C3_parallel_8", "Large join - PARALLEL(8)", "parallel",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "8 parallel worker threads");

        printQuadResult("C3: Parallel(8)", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("C3_parallel_8");
    }

    @Test
    @Order(24)
    @DisplayName("C4: Large join - 16 threads (PARALLEL 16)")
    void parallelJoin_16threads() {
        assumeTrue(maxParallelDegree >= 16,
                "Skipping: Oracle Free edition limited to 2 CPUs");

        awrSnapshotBefore("C4_parallel_16");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all four modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            if (useOracleMongoApi) runOracleApiJoin(10000, 16);
            runOracleJdbcJoin(10000, 16);
            runOracleRelationalJoin(10000, 16);
        }

        // Measure all four modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 16) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 16);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 16);

        storeQuadResult("C4_parallel_16", "Large join - PARALLEL(16)", "parallel",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "16 parallel worker threads");

        printQuadResult("C4: Parallel(16)", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("C4_parallel_16");
    }

    // ==========================================================================
    // Category H: Selective/Indexed Joins (Order 25-29)
    // Real-world queries: selective lookups that should benefit from indexes
    // ==========================================================================

    @Test
    @Order(25)
    @DisplayName("H0: Single customer lookup - find all orders for 1 customer")
    void selectiveJoin_singleCustomer() {
        awrSnapshotBefore("H0_single_customer");

        // Create large dataset but query only 1 customer
        int customerCount = LARGE_CUSTOMER_COUNT; // 100K customers
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoSingleCustomerLookup();
        long oracleApiNanos = useOracleMongoApi ? measureOracleSingleCustomerLookupApi() : -1;
        long oracleJdbcNanos = measureOracleSingleCustomerLookupJdbc();
        long oracleRelNanos = measureOracleSingleCustomerLookupRelational();

        storeQuadResult("H0_single_customer", "Single customer lookup", "selective",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "Find all orders for 1 customer in 100K dataset");

        printQuadResult("H0: Single customer", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("H0_single_customer");
    }

    @Test
    @Order(26)
    @DisplayName("H1: Small batch lookup - find orders for 10 customers")
    void selectiveJoin_smallBatch() {
        awrSnapshotBefore("H1_batch_10");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoBatchCustomerLookup(10);
        long oracleApiNanos = useOracleMongoApi ? measureOracleBatchCustomerLookupApi(10) : -1;
        long oracleJdbcNanos = measureOracleBatchCustomerLookupJdbc(10);
        long oracleRelNanos = measureOracleBatchCustomerLookupRelational(10);

        storeQuadResult("H1_batch_10", "Batch lookup (10 customers)", "selective",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "Find orders for 10 specific customers");

        printQuadResult("H1: Batch 10 customers", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("H1_batch_10");
    }

    @Test
    @Order(27)
    @DisplayName("H2: Medium batch lookup - find orders for 100 customers")
    void selectiveJoin_mediumBatch() {
        awrSnapshotBefore("H2_batch_100");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoBatchCustomerLookup(100);
        long oracleApiNanos = useOracleMongoApi ? measureOracleBatchCustomerLookupApi(100) : -1;
        long oracleJdbcNanos = measureOracleBatchCustomerLookupJdbc(100);
        long oracleRelNanos = measureOracleBatchCustomerLookupRelational(100);

        storeQuadResult("H2_batch_100", "Batch lookup (100 customers)", "selective",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "Find orders for 100 specific customers");

        printQuadResult("H2: Batch 100 customers", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("H2_batch_100");
    }

    @Test
    @Order(28)
    @DisplayName("H3: Large batch lookup - find orders for 1000 customers (1%)")
    void selectiveJoin_largeBatch() {
        awrSnapshotBefore("H3_batch_1000");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoBatchCustomerLookup(1000);
        long oracleApiNanos = useOracleMongoApi ? measureOracleBatchCustomerLookupApi(1000) : -1;
        long oracleJdbcNanos = measureOracleBatchCustomerLookupJdbc(1000);
        long oracleRelNanos = measureOracleBatchCustomerLookupRelational(1000);

        storeQuadResult("H3_batch_1000", "Batch lookup (1000 customers)", "selective",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "Find orders for 1000 customers (1% selectivity)");

        printQuadResult("H3: Batch 1000 customers", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("H3_batch_1000");
    }

    // ==========================================================================
    // Category D: Document Size Limit Tests (Order 30-39)
    // ==========================================================================

    @Test
    @Order(30)
    @DisplayName("D0: Small embedded result ~100KB")
    void docSizeLimit_100KB() {
        awrSnapshotBefore("D0_100KB");
        testDocumentSizeLimit("D0_100KB", 100, false);
        awrSnapshotAfter("D0_100KB");
    }

    @Test
    @Order(31)
    @DisplayName("D1: Medium embedded result ~1MB")
    void docSizeLimit_1MB() {
        awrSnapshotBefore("D1_1MB");
        testDocumentSizeLimit("D1_1MB", 1024, false);
        awrSnapshotAfter("D1_1MB");
    }

    @Test
    @Order(32)
    @DisplayName("D2: Large embedded result ~8MB")
    void docSizeLimit_8MB() {
        awrSnapshotBefore("D2_8MB");
        testDocumentSizeLimit("D2_8MB", 8 * 1024, false);
        awrSnapshotAfter("D2_8MB");
    }

    @Test
    @Order(33)
    @DisplayName("D3: Near limit embedded result ~15MB")
    void docSizeLimit_15MB() {
        awrSnapshotBefore("D3_15MB");
        testDocumentSizeLimit("D3_15MB", 15 * 1024, false);
        awrSnapshotAfter("D3_15MB");
    }

    @Test
    @Order(34)
    @DisplayName("D4: Exceed limit embedded result ~20MB - EXPECT MONGODB FAILURE")
    void docSizeLimit_20MB_expectFailure() {
        awrSnapshotBefore("D4_20MB");
        testDocumentSizeLimit("D4_20MB", 20 * 1024, true);
        awrSnapshotAfter("D4_20MB");
    }

    @Test
    @Order(35)
    @DisplayName("D5: Far exceed limit ~50MB - EXPECT MONGODB FAILURE")
    void docSizeLimit_50MB_expectFailure() {
        awrSnapshotBefore("D5_50MB");
        testDocumentSizeLimit("D5_50MB", 50 * 1024, true);
        awrSnapshotAfter("D5_50MB");
    }

    private void testDocumentSizeLimit(String testId, int targetSizeKB, boolean expectMongoFailure) {
        // Generate large orders that will exceed document limit when embedded
        generateLargeOrders(1, targetSizeKB);

        long mongoNanos;
        String mongoNotes;

        if (skipMongoDB) {
            mongoNanos = -1;
            mongoNotes = "SKIPPED (no-index mode)";
        } else if (expectMongoFailure) {
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

        // Oracle should always succeed - measure API, JDBC JSON, and JDBC Relational
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinLargeOrdersMongoApi(1, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinLargeOrdersJdbc(1, 1);
        long oracleRelNanos = measureOracleJoinLargeOrdersRelational(1, 1);

        String description = "Doc size ~" + (targetSizeKB >= 1024 ? (targetSizeKB / 1024) + "MB" : targetSizeKB + "KB");
        storeQuadResult(testId, description, "doc_size", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, mongoNotes);

        if (mongoNanos >= 0) {
            printQuadResult(testId + ": Doc " + targetSizeKB + "KB", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        } else {
            String oracleApiStr = oracleApiNanos >= 0 ? String.format("%,15d", oracleApiNanos) : "            N/A";
            System.out.printf("  %s: Doc %dKB - MongoDB: FAILED (16MB) | Oracle API: %s ns | Oracle JSON: %,15d ns | Oracle REL: %,15d ns%n",
                    testId, targetSizeKB, oracleApiStr, oracleJdbcNanos, oracleRelNanos);
        }
    }

    // ==========================================================================
    // Category E: Aggregation Memory Limit Tests (Order 40-49)
    // ==========================================================================

    @Test
    @Order(40)
    @DisplayName("E0: Under memory limit - 50MB working set")
    void memoryLimit_50MB() {
        awrSnapshotBefore("E0_50MB");
        testMemoryLimit("E0_50MB", 50, false, false);
        awrSnapshotAfter("E0_50MB");
    }

    @Test
    @Order(41)
    @DisplayName("E1: At memory limit - 100MB working set")
    void memoryLimit_100MB() {
        awrSnapshotBefore("E1_100MB");
        testMemoryLimit("E1_100MB", 100, false, false);
        awrSnapshotAfter("E1_100MB");
    }

    @Test
    @Order(42)
    @DisplayName("E2: Over memory limit - 150MB (no allowDiskUse) - EXPECT FAILURE")
    void memoryLimit_150MB_noAllowDisk() {
        awrSnapshotBefore("E2_150MB_noDisk");
        testMemoryLimit("E2_150MB_noDisk", 150, false, true);
        awrSnapshotAfter("E2_150MB_noDisk");
    }

    @Test
    @Order(43)
    @DisplayName("E3: Over memory limit - 150MB (with allowDiskUse)")
    void memoryLimit_150MB_allowDisk() {
        awrSnapshotBefore("E3_150MB_disk");
        testMemoryLimit("E3_150MB_disk", 150, true, false);
        awrSnapshotAfter("E3_150MB_disk");
    }

    @Test
    @Order(44)
    @DisplayName("E4: 2x memory limit - 200MB (with allowDiskUse)")
    void memoryLimit_200MB_allowDisk() {
        awrSnapshotBefore("E4_200MB_disk");
        testMemoryLimit("E4_200MB_disk", 200, true, false);
        awrSnapshotAfter("E4_200MB_disk");
    }

    @Test
    @Order(45)
    @DisplayName("E5: 5x memory limit - 500MB (with allowDiskUse)")
    void memoryLimit_500MB_allowDisk() {
        awrSnapshotBefore("E5_500MB_disk");
        testMemoryLimit("E5_500MB_disk", 500, true, false);
        awrSnapshotAfter("E5_500MB_disk");
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

        if (skipMongoDB) {
            mongoNanos = -1;
            mongoNotes = "SKIPPED (no-index mode)";
        } else if (expectFailure) {
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

        // Measure all Oracle modes
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinWithSortMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinWithSortJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinWithSortRelational(customerCount, 2);

        String description = "Memory " + workingSetMB + "MB" + (allowDiskUse ? " (disk)" : "");
        storeQuadResult(testId, description, "memory", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, mongoNotes);

        if (mongoNanos >= 0) {
            printQuadResult(testId + ": Memory " + workingSetMB + "MB", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        } else {
            String oracleApiStr = oracleApiNanos >= 0 ? String.format("%,12d", oracleApiNanos) : "         N/A";
            System.out.printf("  %s: Memory %dMB - MongoDB: FAILED (memory limit) | Oracle API: %s ns | Oracle JSON: %,12d ns | Oracle REL: %,12d ns%n",
                    testId, workingSetMB, oracleApiStr, oracleJdbcNanos, oracleRelNanos);
        }
    }

    // ==========================================================================
    // Category F: Sort Spillover Tests (Order 50-59)
    // ==========================================================================

    @Test
    @Order(50)
    @DisplayName("F0: Small sort - 10K documents")
    void sortTest_10K() {
        awrSnapshotBefore("F0_sort_10K");
        testSortPerformance("F0_sort_10K", 10_000);
        awrSnapshotAfter("F0_sort_10K");
    }

    @Test
    @Order(51)
    @DisplayName("F1: Medium sort - 100K documents")
    void sortTest_100K() {
        awrSnapshotBefore("F1_sort_100K");
        testSortPerformance("F1_sort_100K", 100_000);
        awrSnapshotAfter("F1_sort_100K");
    }

    @Test
    @Order(52)
    @DisplayName("F2: Large sort - 500K documents")
    void sortTest_500K() {
        awrSnapshotBefore("F2_sort_500K");
        testSortPerformance("F2_sort_500K", 500_000);
        awrSnapshotAfter("F2_sort_500K");
    }

    @Test
    @Order(53)
    @DisplayName("F3: Very large sort - 1M documents")
    void sortTest_1M() {
        awrSnapshotBefore("F3_sort_1M");
        testSortPerformance("F3_sort_1M", 1_000_000);
        awrSnapshotAfter("F3_sort_1M");
    }

    private void testSortPerformance(String testId, int documentCount) {
        // Generate orders for sorting
        int customerCount = Math.min(documentCount / 10, 10000);
        int ordersPerCustomer = documentCount / customerCount;
        generateTestData(customerCount, ordersPerCustomer);

        int parallelDegree = Math.min(maxParallelDegree, 4);
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookupWithSort(customerCount, true);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinWithSortMongoApi(customerCount, parallelDegree) : -1;
        long oracleJdbcNanos = measureOracleJoinWithSortJdbc(customerCount, parallelDegree);
        long oracleRelNanos = measureOracleJoinWithSortRelational(customerCount, parallelDegree);

        storeQuadResult(testId, "Sort " + formatCount(documentCount) + " docs", "sort",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "allowDiskUse=true for MongoDB, PARALLEL for Oracle");

        printQuadResult(testId + ": Sort " + formatCount(documentCount), mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
    }

    // ==========================================================================
    // Category G: Multi-Stage Pipeline Stress (Order 60-69)
    // ==========================================================================

    @Test
    @Order(60)
    @DisplayName("G0: 2-stage pipeline ($lookup -> $sort)")
    void pipeline_2stage() {
        awrSnapshotBefore("G0_2stage");

        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoPipeline2Stage(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinWithSortMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinWithSortJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinWithSortRelational(customerCount, 2);

        storeQuadResult("G0_2stage", "2-stage: $lookup -> $sort", "pipeline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "2 potential spill points");

        printQuadResult("G0: 2-stage pipeline", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        awrSnapshotAfter("G0_2stage");
    }

    @Test
    @Order(61)
    @DisplayName("G1: 3-stage pipeline ($lookup -> $unwind -> $group)")
    void pipeline_3stage() {
        awrSnapshotBefore("G1_3stage");
        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoPipeline3Stage(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinGroupByMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinGroupByJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinGroupByRelational(customerCount, 2);

        storeQuadResult("G1_3stage", "3-stage: $lookup -> $unwind -> $group", "pipeline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "3 potential spill points");

        printQuadResult("G1: 3-stage pipeline", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        awrSnapshotAfter("G1_3stage");
    }

    @Test
    @Order(62)
    @DisplayName("G2: 4-stage pipeline ($lookup -> $unwind -> $group -> $sort)")
    void pipeline_4stage() {
        awrSnapshotBefore("G2_4stage");
        int customerCount = 10_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        long mongoNanos = skipMongoDB ? -1 : measureMongoPipeline4Stage(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinGroupBySortMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinGroupBySortJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinGroupBySortRelational(customerCount, 2);

        storeQuadResult("G2_4stage", "4-stage: $lookup -> $unwind -> $group -> $sort", "pipeline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "4 potential spill points");

        printQuadResult("G2: 4-stage pipeline", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        awrSnapshotAfter("G2_4stage");
    }

    @Test
    @Order(63)
    @DisplayName("G3: Chained lookups ($lookup -> $lookup)")
    void pipeline_chainedLookups() {
        awrSnapshotBefore("G3_chained");
        int customerCount = 1_000;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);
        generateProductData(1_000);

        long mongoNanos = skipMongoDB ? -1 : measureMongoChainedLookups(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleMultiTableJoinMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleMultiTableJoinJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleMultiTableJoinRelational(customerCount, 2);

        storeQuadResult("G3_chained", "Chained: $lookup -> $lookup", "pipeline",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, "Nested materialization in MongoDB");

        printQuadResult("G3: Chained lookups", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);
        awrSnapshotAfter("G3_chained");
    }

    // ==========================================================================
    // Category R: Relational vs JSON Comparison (Order 70-79)
    // Tests Oracle pure relational performance to isolate JSON overhead
    // ==========================================================================

    @Test
    @Order(70)
    @DisplayName("R0: Relational vs JSON - 1K customers")
    void relationalComparison_1K() {
        awrSnapshotBefore("R0_rel_1K");

        int customerCount = SMALL_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(100);
            runOracleJdbcJoin(100, 1);
            runOracleRelationalJoin(100, 1);
        }

        // Measure all modes (skip MongoDB in no-index mode)
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("R0_rel_1K", "Relational vs JSON - 1K", "relational",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "1K customers - isolating JSON overhead");

        printQuadResult("R0: 1K join", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("R0_rel_1K");
    }

    @Test
    @Order(71)
    @DisplayName("R1: Relational vs JSON - 10K customers")
    void relationalComparison_10K() {
        awrSnapshotBefore("R1_rel_10K");

        int customerCount = MEDIUM_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("R1_rel_10K", "Relational vs JSON - 10K", "relational",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "10K customers - JSON parsing overhead accumulates");

        printQuadResult("R1: 10K join", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("R1_rel_10K");
    }

    @Test
    @Order(72)
    @DisplayName("R2: Relational vs JSON - 100K customers")
    void relationalComparison_100K() {
        awrSnapshotBefore("R2_rel_100K");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            runOracleJdbcJoin(10000, 1);
            runOracleRelationalJoin(10000, 1);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);

        storeQuadResult("R2_rel_100K", "Relational vs JSON - 100K", "relational",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "100K customers - large scale comparison");

        printQuadResult("R2: 100K join", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("R2_rel_100K");
    }

    @Test
    @Order(73)
    @DisplayName("R3: Relational with PARALLEL(2) - 100K customers")
    void relationalComparison_parallel() {
        awrSnapshotBefore("R3_rel_parallel");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(10000);
            runOracleJdbcJoin(10000, 2);
            runOracleRelationalJoin(10000, 2);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 2) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 2);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 2);

        storeQuadResult("R3_rel_parallel", "Relational PARALLEL(2) - 100K", "relational",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos,
                "100K customers with Oracle parallel execution");

        printQuadResult("R3: 100K parallel", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("R3_rel_parallel");
    }

    // ==========================================================================
    // HY: Hybrid Schema Tests (JSON + Virtual Columns for Joins)
    // ==========================================================================

    @Test
    @Order(75)
    @DisplayName("HY1: Hybrid schema - 1K customers")
    void hybridSchema_1K() {
        awrSnapshotBefore("HY1_hybrid_1K");

        int customerCount = SMALL_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all modes including hybrid
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(customerCount);
            runOracleJdbcJoin(customerCount, 1);
            runOracleRelationalJoin(customerCount, 1);
            runOracleHybridJoin(customerCount, 1);
        }

        // Measure all modes
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);
        long oracleHybridNanos = measureOracleJoinHybrid(customerCount, 1);
        long oracleHybridApiNanos = useOracleMongoApi ? measureOracleJoinHybridMongoApi(customerCount, 1) : -1;

        storeSextetResult("HY1_hybrid_1K", "Hybrid schema - 1K customers", "hybrid",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos,
                "1K customers - hybrid JSON + virtual column join");

        printSextetResult("HY1: 1K hybrid", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos);

        awrSnapshotAfter("HY1_hybrid_1K");
    }

    @Test
    @Order(76)
    @DisplayName("HY2: Hybrid schema - 10K customers")
    void hybridSchema_10K() {
        awrSnapshotBefore("HY2_hybrid_10K");

        int customerCount = MEDIUM_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all modes including hybrid
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
            runOracleHybridJoin(1000, 1);
        }

        // Measure all modes
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);
        long oracleHybridNanos = measureOracleJoinHybrid(customerCount, 1);
        long oracleHybridApiNanos = useOracleMongoApi ? measureOracleJoinHybridMongoApi(customerCount, 1) : -1;

        storeSextetResult("HY2_hybrid_10K", "Hybrid schema - 10K customers", "hybrid",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos,
                "10K customers - hybrid JSON + virtual column join");

        printSextetResult("HY2: 10K hybrid", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos);

        awrSnapshotAfter("HY2_hybrid_10K");
    }

    @Test
    @Order(77)
    @DisplayName("HY3: Hybrid schema - 100K customers")
    void hybridSchema_100K() {
        awrSnapshotBefore("HY3_hybrid_100K");

        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Warmup all modes including hybrid
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoLookup(1000);
            runOracleJdbcJoin(1000, 1);
            runOracleRelationalJoin(1000, 1);
            runOracleHybridJoin(1000, 1);
        }

        // Measure all modes
        long mongoNanos = skipMongoDB ? -1 : measureMongoLookup(customerCount);
        long oracleApiNanos = useOracleMongoApi ? measureOracleJoinMongoApi(customerCount, 1) : -1;
        long oracleJdbcNanos = measureOracleJoinJdbc(customerCount, 1);
        long oracleRelNanos = measureOracleJoinRelational(customerCount, 1);
        long oracleHybridNanos = measureOracleJoinHybrid(customerCount, 1);
        long oracleHybridApiNanos = useOracleMongoApi ? measureOracleJoinHybridMongoApi(customerCount, 1) : -1;

        storeSextetResult("HY3_hybrid_100K", "Hybrid schema - 100K customers", "hybrid",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos,
                "100K customers - hybrid JSON + virtual column join");

        printSextetResult("HY3: 100K hybrid", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos);

        awrSnapshotAfter("HY3_hybrid_100K");
    }

    @Test
    @Order(78)
    @DisplayName("HY4: Hybrid selective - single customer lookup")
    void hybridSchema_Selective() {
        awrSnapshotBefore("HY4_hybrid_selective");

        // Use 100K dataset to have realistic index selectivity
        int customerCount = LARGE_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        // Test selective lookup for a specific customer in the middle of the dataset
        String targetCustomerId = "cust_50000";

        // Warmup all modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            measureOracleJoinJsonSelective(targetCustomerId);
            measureOracleJoinRelationalSelective(targetCustomerId);
            measureOracleJoinHybridSelective(targetCustomerId);
        }

        // Measure all modes (MongoDB $match + $lookup would be equivalent but complex to set up)
        long mongoNanos = -1; // Skip MongoDB for this test
        long oracleApiNanos = -1; // Skip API for this test
        long oracleJdbcNanos = measureOracleJoinJsonSelective(targetCustomerId);
        long oracleRelNanos = measureOracleJoinRelationalSelective(targetCustomerId);
        long oracleHybridNanos = measureOracleJoinHybridSelective(targetCustomerId);
        long oracleHybridApiNanos = -1;

        storeSextetResult("HY4_hybrid_selective", "Hybrid selective - single customer", "hybrid",
                mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos,
                "Single customer lookup - should trigger index usage");

        printSextetResult("HY4: Selective", mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos);

        awrSnapshotAfter("HY4_hybrid_selective");
    }

    // ==========================================================================
    // S: Simple Scan Tests (No Join)
    // ==========================================================================

    @Test
    @Order(80)
    @DisplayName("S0: Simple scan - no join, 1K documents")
    void simpleScan_1K() {
        awrSnapshotBefore("S0_scan_1K");

        int customerCount = SMALL_CUSTOMER_COUNT;
        int ordersPerCustomer = 10;
        generateTestData(customerCount, ordersPerCustomer);

        int limit = 1000;

        // Warmup all five modes
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoSimpleScan(limit);
            if (useOracleMongoApi) runOracleNativeApiScan(limit);
            runOracleSimpleScanJdbc(limit);
            runOracleSimpleScanRelational(limit);
        }

        // Measure all five modes
        long mongoNanos = skipMongoDB ? -1 : measureMongoSimpleScan(limit);
        long oracleNativeApiNanos = useOracleMongoApi ? measureOracleNativeApiScan(limit) : -1;
        long oracleApiSqlNanos = useOracleMongoApi ? measureOracleSimpleScanApi(limit) : -1;
        long oracleJdbcNanos = measureOracleSimpleScanJdbc(limit);
        long oracleRelNanos = measureOracleSimpleScanRelational(limit);

        storeQuintResult("S0_scan_1K", "Simple scan - 1K documents", "scan",
                mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos,
                "1K documents - no join, pure scan performance");

        printQuintResult("S0: 1K scan", mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos);

        awrSnapshotAfter("S0_scan_1K");
    }

    private void runMongoSimpleScan(int limit) {
        for (Document doc : ordersCollection.find().limit(limit).batchSize(JDBC_FETCH_SIZE)) {
            // Consume
        }
    }

    private void runOracleSimpleScanJdbc(int limit) {
        String sql = "SELECT data FROM " + ORDERS_COLLECTION + " WHERE ROWNUM <= " + limit;
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void runOracleSimpleScanRelational(int limit) {
        String sql = "SELECT * FROM " + ORDERS_REL_TABLE + " WHERE ROWNUM <= " + limit;
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void runOracleRelationalJoin(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.customer_id, c.name, o.order_id, o.total " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void runOracleHybridJoin(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        // Join on virtual relational columns, return full JSON data
        String sql = "SELECT " + hint + " c.data, o.data " +
                "FROM (SELECT * FROM " + CUSTOMERS_HYBRID_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_HYBRID_TABLE + " o ON c.customer_id = o.customer_id";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void storeQuadResult(String testId, String description, String category,
                                  long mongoNanos, long oracleApiNanos, long oracleJdbcNanos,
                                  long oracleRelNanos, String notes) {
        // Store quad result with relational
        quadResults.put(testId, new long[]{mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos});
        // Also store as triple for backward compatibility
        storeTripleResult(testId, description, category, mongoNanos, oracleApiNanos, oracleJdbcNanos, notes);
    }

    private void printQuadResult(String label, long mongoNanos, long oracleApiNanos,
                                  long oracleJdbcNanos, long oracleRelNanos) {
        String mongoStr = mongoNanos >= 0 ? String.format("%,15d", mongoNanos) : "        SKIPPED";
        String oracleApiStr = oracleApiNanos >= 0 ? String.format("%,15d", oracleApiNanos) : "            N/A";
        System.out.printf("  %s - MongoDB: %s ns | Oracle API: %s ns | Oracle JSON: %,15d ns | Oracle REL: %,15d ns%n",
                String.format("%-25s", label), mongoStr, oracleApiStr, oracleJdbcNanos, oracleRelNanos);
    }

    private void storeQuintResult(String testId, String description, String category,
                                   long mongoNanos, long oracleNativeApiNanos, long oracleApiSqlNanos,
                                   long oracleJdbcNanos, long oracleRelNanos, String notes) {
        // Store quint result for scan tests: [mongo, oracleNativeApi, oracleApiSql, oracleJdbc, oracleRel]
        quintResults.put(testId, new long[]{mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos});
        // Also store as quad for backward compatibility (using $sql API value)
        quadResults.put(testId, new long[]{mongoNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos});
        // Store as triple for further backward compatibility
        storeTripleResult(testId, description, category, mongoNanos, oracleApiSqlNanos, oracleJdbcNanos, notes);
    }

    private void printQuintResult(String label, long mongoNanos, long oracleNativeApiNanos, long oracleApiSqlNanos,
                                   long oracleJdbcNanos, long oracleRelNanos) {
        String mongoStr = mongoNanos >= 0 ? String.format("%,12d", mongoNanos) : "     SKIPPED";
        String oracleNativeStr = oracleNativeApiNanos >= 0 ? String.format("%,12d", oracleNativeApiNanos) : "         N/A";
        String oracleApiStr = oracleApiSqlNanos >= 0 ? String.format("%,12d", oracleApiSqlNanos) : "         N/A";
        System.out.printf("  %s - MongoDB: %s ns | Oracle Native: %s ns | Oracle $sql: %s ns | JDBC JSON: %,12d ns | JDBC REL: %,12d ns%n",
                String.format("%-20s", label), mongoStr, oracleNativeStr, oracleApiStr, oracleJdbcNanos, oracleRelNanos);
    }

    private void storeSextetResult(String testId, String description, String category,
                                    long mongoNanos, long oracleApiNanos, long oracleJdbcNanos,
                                    long oracleRelNanos, long oracleHybridNanos, long oracleHybridApiNanos, String notes) {
        // Store sextet result for hybrid tests: [mongo, oracleApi, oracleJdbc, oracleRel, oracleHybrid, oracleHybridApi]
        sextetResults.put(testId, new long[]{mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos});
        // Also store as quad for backward compatibility
        quadResults.put(testId, new long[]{mongoNanos, oracleApiNanos, oracleJdbcNanos, oracleRelNanos});
        // Store as triple for further backward compatibility
        storeTripleResult(testId, description, category, mongoNanos, oracleApiNanos, oracleJdbcNanos, notes);
    }

    private void printSextetResult(String label, long mongoNanos, long oracleApiNanos,
                                    long oracleJdbcNanos, long oracleRelNanos,
                                    long oracleHybridNanos, long oracleHybridApiNanos) {
        String mongoStr = mongoNanos >= 0 ? String.format("%,11d", mongoNanos) : "    SKIPPED";
        String oracleApiStr = oracleApiNanos >= 0 ? String.format("%,11d", oracleApiNanos) : "        N/A";
        String hybridApiStr = oracleHybridApiNanos >= 0 ? String.format("%,11d", oracleHybridApiNanos) : "        N/A";
        System.out.printf("  %s - MongoDB: %s | API: %s | JSON: %,11d | REL: %,11d | HYB: %,11d | HYB API: %s%n",
                String.format("%-18s", label), mongoStr, oracleApiStr, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, hybridApiStr);
    }

    // ==========================================================================
    // Data Generation Methods
    // ==========================================================================

    private void generateTestData(int customerCount, int ordersPerCustomer) {
        // Clear existing data - MongoDB native (skip in no-index mode)
        if (!skipMongoDB) {
            customersCollection.drop();
            ordersCollection.drop();
            customersCollection = mongoDatabase.getCollection(CUSTOMERS_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
            ordersCollection = mongoDatabase.getCollection(ORDERS_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
        }

        // Clear existing data - Oracle MongoDB API (SODA)
        if (oracleMongoClient != null) {
            try { oracleCustomersCollection.drop(); } catch (Exception ignored) {}
            try { oracleOrdersCollection.drop(); } catch (Exception ignored) {}
            oracleCustomersCollection = oracleMongoDatabase.getCollection(SODA_CUSTOMERS_COLLECTION);
            oracleOrdersCollection = oracleMongoDatabase.getCollection(SODA_ORDERS_COLLECTION);
        }

        // Clear existing data - Oracle JDBC (JSON, Relational, and Hybrid tables)
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try { stmt.execute("DELETE FROM " + ORDERS_COLLECTION); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + CUSTOMERS_COLLECTION); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + ORDERS_REL_TABLE); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + CUSTOMERS_REL_TABLE); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + ORDERS_HYBRID_TABLE); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + CUSTOMERS_HYBRID_TABLE); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            // Tables might not exist yet, ignore
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
                if (!skipMongoDB) customersCollection.insertMany(customerDocs);
                if (oracleCustomersCollection != null) {
                    oracleCustomersCollection.insertMany(new ArrayList<>(customerDocs));
                }
                insertCustomersJdbc(customerDocs);
                insertCustomersRelational(customerDocs);
                insertCustomersHybrid(customerDocs);
                customerDocs.clear();
            }
        }

        // Insert remaining customers
        if (!customerDocs.isEmpty()) {
            if (!skipMongoDB) customersCollection.insertMany(customerDocs);
            if (oracleCustomersCollection != null) {
                oracleCustomersCollection.insertMany(new ArrayList<>(customerDocs));
            }
            insertCustomersJdbc(customerDocs);
            insertCustomersRelational(customerDocs);
            insertCustomersHybrid(customerDocs);
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
                    if (!skipMongoDB) ordersCollection.insertMany(orderDocs);
                    if (oracleOrdersCollection != null) {
                        oracleOrdersCollection.insertMany(new ArrayList<>(orderDocs));
                    }
                    insertOrdersJdbc(orderDocs);
                    insertOrdersRelational(orderDocs);
                    insertOrdersHybrid(orderDocs);
                    orderDocs.clear();
                }
            }
        }

        // Insert remaining orders
        if (!orderDocs.isEmpty()) {
            if (!skipMongoDB) ordersCollection.insertMany(orderDocs);
            if (oracleOrdersCollection != null) {
                oracleOrdersCollection.insertMany(new ArrayList<>(orderDocs));
            }
            insertOrdersJdbc(orderDocs);
            insertOrdersRelational(orderDocs);
            insertOrdersHybrid(orderDocs);
        }

        // Create indexes for MongoDB native (skip in no-index mode or when skipping MongoDB)
        if (!noIndexMode && !skipMongoDB) {
            ordersCollection.createIndex(new Document("customer_id", 1));

            // Create indexes for Oracle MongoDB API
            if (oracleOrdersCollection != null) {
                try {
                    oracleOrdersCollection.createIndex(new Document("customer_id", 1));
                } catch (Exception ignored) {}
            }
        }

        // Gather Oracle table statistics after data insertion (critical for optimizer)
        gatherOracleStats();
    }

    // ==========================================================================
    // Results Persistence Methods
    // ==========================================================================

    /**
     * Loads previously persisted test results at startup.
     * This allows running tests individually and accumulating results in the report.
     */
    private static void loadPersistedResults() {
        Path resultsFile = Path.of(RESULTS_PERSISTENCE_FILE);
        if (!Files.exists(resultsFile)) {
            System.out.println("  [Results] No previous results found - starting fresh");
            return;
        }

        try {
            String json = Files.readString(resultsFile);
            Document doc = Document.parse(json);

            // Load triple results
            Document tripleDoc = doc.get("tripleResults", Document.class);
            if (tripleDoc != null) {
                for (String key : tripleDoc.keySet()) {
                    List<Long> values = tripleDoc.getList(key, Long.class);
                    if (values != null && values.size() >= 3) {
                        tripleResults.put(key, new long[]{values.get(0), values.get(1), values.get(2)});
                    }
                }
            }

            // Load quad results
            Document quadDoc = doc.get("quadResults", Document.class);
            if (quadDoc != null) {
                for (String key : quadDoc.keySet()) {
                    List<Long> values = quadDoc.getList(key, Long.class);
                    if (values != null && values.size() >= 4) {
                        quadResults.put(key, new long[]{values.get(0), values.get(1), values.get(2), values.get(3)});
                    }
                }
            }

            // Load quint results
            Document quintDoc = doc.get("quintResults", Document.class);
            if (quintDoc != null) {
                for (String key : quintDoc.keySet()) {
                    List<Long> values = quintDoc.getList(key, Long.class);
                    if (values != null && values.size() >= 5) {
                        quintResults.put(key, new long[]{values.get(0), values.get(1), values.get(2), values.get(3), values.get(4)});
                    }
                }
            }

            // Load sextet results
            Document sextetDoc = doc.get("sextetResults", Document.class);
            if (sextetDoc != null) {
                for (String key : sextetDoc.keySet()) {
                    List<Long> values = sextetDoc.getList(key, Long.class);
                    if (values != null && values.size() >= 6) {
                        sextetResults.put(key, new long[]{values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5)});
                    }
                }
            }

            // Load TestResult objects
            Document resultsDoc = doc.get("results", Document.class);
            if (resultsDoc != null) {
                for (String key : resultsDoc.keySet()) {
                    Document r = resultsDoc.get(key, Document.class);
                    if (r != null) {
                        results.put(key, new TestResult(
                                r.getString("testId"),
                                r.getString("description"),
                                r.getLong("mongoNanos"),
                                r.getLong("oracleNanos"),
                                r.getString("category"),
                                r.getString("notes")
                        ));
                    }
                }
            }

            // Load SQL details map
            Document sqlDoc = doc.get("sqlDetails", Document.class);
            if (sqlDoc != null) {
                for (String key : sqlDoc.keySet()) {
                    Document s = sqlDoc.get(key, Document.class);
                    if (s != null) {
                        sqlDetailsMap.put(key, new SqlDetails(
                                s.getString("jdbcSql") != null ? s.getString("jdbcSql") : "",
                                s.getString("apiSql") != null ? s.getString("apiSql") : "",
                                s.getString("explainPlan") != null ? s.getString("explainPlan") : "",
                                s.getString("sqlMonitorJdbcJson") != null ? s.getString("sqlMonitorJdbcJson") : "",
                                s.getString("sqlMonitorApiSql") != null ? s.getString("sqlMonitorApiSql") : "",
                                s.getString("sqlMonitorRelational") != null ? s.getString("sqlMonitorRelational") : "",
                                s.getString("sqlMonitorOracleNative") != null ? s.getString("sqlMonitorOracleNative") : "",
                                s.getString("sqlMonitorHybrid") != null ? s.getString("sqlMonitorHybrid") : "",
                                s.getString("mongoPipeline") != null ? s.getString("mongoPipeline") : "",
                                s.getString("mongoExplain") != null ? s.getString("mongoExplain") : "",
                                s.getString("relationalSql") != null ? s.getString("relationalSql") : "",
                                s.getString("relationalExplainPlan") != null ? s.getString("relationalExplainPlan") : "",
                                s.getString("hybridSql") != null ? s.getString("hybridSql") : "",
                                s.getString("hybridExplainPlan") != null ? s.getString("hybridExplainPlan") : "",
                                s.getString("oracleNativePipeline") != null ? s.getString("oracleNativePipeline") : "",
                                s.getString("oracleNativeExplain") != null ? s.getString("oracleNativeExplain") : ""
                        ));
                    }
                }
            }

            System.out.printf("  [Results] Loaded %d previous test results from %s%n",
                    results.size(), RESULTS_PERSISTENCE_FILE);

        } catch (Exception e) {
            System.out.println("  [Results] Could not load previous results: " + e.getMessage());
        }
    }

    /**
     * Persists current test results to a file.
     * Called after each test completes to save incremental progress.
     */
    private static void persistResults() {
        try {
            Document doc = new Document();

            // Save triple results
            Document tripleDoc = new Document();
            for (Map.Entry<String, long[]> entry : tripleResults.entrySet()) {
                tripleDoc.put(entry.getKey(), Arrays.asList(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]));
            }
            doc.put("tripleResults", tripleDoc);

            // Save quad results
            Document quadDoc = new Document();
            for (Map.Entry<String, long[]> entry : quadResults.entrySet()) {
                quadDoc.put(entry.getKey(), Arrays.asList(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]));
            }
            doc.put("quadResults", quadDoc);

            // Save quint results
            Document quintDoc = new Document();
            for (Map.Entry<String, long[]> entry : quintResults.entrySet()) {
                quintDoc.put(entry.getKey(), Arrays.asList(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3], entry.getValue()[4]));
            }
            doc.put("quintResults", quintDoc);

            // Save sextet results
            Document sextetDoc = new Document();
            for (Map.Entry<String, long[]> entry : sextetResults.entrySet()) {
                sextetDoc.put(entry.getKey(), Arrays.asList(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3], entry.getValue()[4], entry.getValue()[5]));
            }
            doc.put("sextetResults", sextetDoc);

            // Save TestResult objects
            Document resultsDoc = new Document();
            for (Map.Entry<String, TestResult> entry : results.entrySet()) {
                TestResult r = entry.getValue();
                resultsDoc.put(entry.getKey(), new Document()
                        .append("testId", r.testId())
                        .append("description", r.description())
                        .append("mongoNanos", r.mongoNanos())
                        .append("oracleNanos", r.oracleNanos())
                        .append("category", r.category())
                        .append("notes", r.notes()));
            }
            doc.put("results", resultsDoc);

            // Save SQL details map
            Document sqlDoc = new Document();
            for (Map.Entry<String, SqlDetails> entry : sqlDetailsMap.entrySet()) {
                SqlDetails s = entry.getValue();
                sqlDoc.put(entry.getKey(), new Document()
                        .append("jdbcSql", s.jdbcSql())
                        .append("apiSql", s.apiSql())
                        .append("explainPlan", s.explainPlan())
                        .append("sqlMonitorJdbcJson", s.sqlMonitorJdbcJson())
                        .append("sqlMonitorApiSql", s.sqlMonitorApiSql())
                        .append("sqlMonitorRelational", s.sqlMonitorRelational())
                        .append("sqlMonitorOracleNative", s.sqlMonitorOracleNative())
                        .append("sqlMonitorHybrid", s.sqlMonitorHybrid())
                        .append("mongoPipeline", s.mongoPipeline())
                        .append("mongoExplain", s.mongoExplain())
                        .append("relationalSql", s.relationalSql())
                        .append("relationalExplainPlan", s.relationalExplainPlan())
                        .append("hybridSql", s.hybridSql())
                        .append("hybridExplainPlan", s.hybridExplainPlan())
                        .append("oracleNativePipeline", s.oracleNativePipeline())
                        .append("oracleNativeExplain", s.oracleNativeExplain()));
            }
            doc.put("sqlDetails", sqlDoc);

            // Write to file
            Path resultsFile = Path.of(RESULTS_PERSISTENCE_FILE);
            Files.createDirectories(resultsFile.getParent());
            Files.writeString(resultsFile, doc.toJson(JsonWriterSettings.builder().indent(true).build()));

        } catch (Exception e) {
            System.out.println("  [Results] Warning: Could not persist results: " + e.getMessage());
        }
    }

    private static boolean planAnalyzed = false;

    /**
     * Gathers Oracle table statistics after data changes.
     * IMPORTANT: Must be called after each data generation to ensure optimizer has accurate cardinality estimates.
     * Without fresh statistics, the optimizer may choose suboptimal execution plans (e.g., HASH JOIN vs NESTED LOOPS).
     *
     * In no-index mode, we skip stats gathering entirely since:
     * 1. There are no indexes to analyze
     * 2. The DBMS_STATS call is extremely slow on large tables
     * 3. Oracle's default estimates are sufficient for no-index baseline comparison
     */
    private void gatherOracleStats() {
        if (noIndexMode) {
            System.out.println("  Skipping Oracle table statistics (no-index mode)...");
            // Still show execution plan once per session
            if (!planAnalyzed) {
                analyzeJoinPlan();
                planAnalyzed = true;
            }
            return;
        }

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            System.out.println("  Gathering Oracle table statistics...");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CUSTOMERS_COLLECTION + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + ORDERS_COLLECTION + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CUSTOMERS_REL_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + ORDERS_REL_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + PRODUCTS_REL_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + LARGE_ORDERS_REL_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CUSTOMERS_HYBRID_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + ORDERS_HYBRID_TABLE + "', cascade => TRUE); END;");

            // Analyze and print execution plan (once per session for console output)
            if (!planAnalyzed) {
                analyzeJoinPlan();
                planAnalyzed = true;
            }
        } catch (SQLException e) {
            System.out.println("  Warning: Could not gather stats: " + e.getMessage());
        }
    }

    private void insertCustomersJdbc(List<Document> docs) {
        String sql = "INSERT INTO " + CUSTOMERS_COLLECTION + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert customers via JDBC", e);
        }
    }

    private void insertOrdersJdbc(List<Document> docs) {
        String sql = "INSERT INTO " + ORDERS_COLLECTION + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert orders via JDBC", e);
        }
    }

    // ==========================================================================
    // Relational Table Insertion Methods
    // ==========================================================================

    private void insertCustomersRelational(List<Document> docs) {
        String sql = "INSERT INTO " + CUSTOMERS_REL_TABLE +
                " (customer_id, name, email, region, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.getString("name"));
                ps.setString(3, doc.getString("email"));
                ps.setString(4, doc.getString("region"));
                ps.setString(5, doc.getString("created_at"));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert customers into relational table", e);
        }
    }

    private void insertOrdersRelational(List<Document> docs) {
        String sql = "INSERT INTO " + ORDERS_REL_TABLE +
                " (order_id, customer_id, product_id, order_date, total, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.getString("customer_id"));
                ps.setString(3, doc.getString("product_id")); // May be null for some orders
                ps.setString(4, doc.getString("order_date"));
                ps.setDouble(5, doc.getDouble("total"));
                ps.setString(6, doc.getString("status"));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert orders into relational table", e);
        }
    }

    private void insertProductsRelational(List<Document> docs) {
        String sql = "INSERT INTO " + PRODUCTS_REL_TABLE +
                " (product_id, name, category, price) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.getString("name"));
                ps.setString(3, doc.getString("category"));
                ps.setDouble(4, doc.getDouble("price"));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert products into relational table", e);
        }
    }

    private void insertLargeOrdersRelational(List<Document> docs) {
        String sql = "INSERT INTO " + LARGE_ORDERS_REL_TABLE +
                " (order_id, customer_id, order_date, total, status, padding) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.getString("customer_id"));
                ps.setString(3, doc.getString("order_date")); // May be null for large orders
                Double total = doc.getDouble("total");
                if (total != null) {
                    ps.setDouble(4, total);
                } else {
                    ps.setNull(4, java.sql.Types.NUMERIC);
                }
                ps.setString(5, doc.getString("status")); // May be null for large orders
                // Large orders use "data" field for padding
                ps.setString(6, doc.getString("data")); // CLOB for large padding data
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert large orders into relational table", e);
        }
    }

    // ==========================================================================
    // Hybrid Table Insertion Methods (JSON + virtual columns)
    // ==========================================================================

    private void insertCustomersHybrid(List<Document> docs) {
        // Virtual columns are auto-computed from JSON - only insert id and data
        String sql = "INSERT INTO " + CUSTOMERS_HYBRID_TABLE + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert customers into hybrid table", e);
        }
    }

    private void insertOrdersHybrid(List<Document> docs) {
        // Virtual columns are auto-computed from JSON - only insert id and data
        String sql = "INSERT INTO " + ORDERS_HYBRID_TABLE + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert orders into hybrid table", e);
        }
    }

    private void insertLargeOrdersJdbc(List<Document> customerDocs, List<Document> orderDocs) {
        try {
            // Clear large orders table (JSON and Relational)
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                try { stmt.execute("DELETE FROM " + LARGE_ORDERS_COLLECTION); } catch (SQLException ignored) {}
                try { stmt.execute("DELETE FROM " + CUSTOMERS_COLLECTION); } catch (SQLException ignored) {}
                try { stmt.execute("DELETE FROM " + LARGE_ORDERS_REL_TABLE); } catch (SQLException ignored) {}
                try { stmt.execute("DELETE FROM " + CUSTOMERS_REL_TABLE); } catch (SQLException ignored) {}
            }

            // Insert customers (JSON)
            String custSql = "INSERT INTO " + CUSTOMERS_COLLECTION + " (id, data) VALUES (?, ?)";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(custSql)) {
                for (Document doc : customerDocs) {
                    ps.setString(1, doc.getString("_id"));
                    ps.setString(2, doc.toJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Insert customers (Relational)
            insertCustomersRelational(customerDocs);

            // Insert large orders (JSON)
            String orderSql = "INSERT INTO " + LARGE_ORDERS_COLLECTION + " (id, data) VALUES (?, ?)";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(orderSql)) {
                for (Document doc : orderDocs) {
                    ps.setString(1, doc.getString("_id"));
                    ps.setString(2, doc.toJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Insert large orders (Relational)
            insertLargeOrdersRelational(orderDocs);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert large orders via JDBC", e);
        }
    }

    private void generateLargeOrders(int customerCount, int targetSizeKB) {
        // Clear large orders - MongoDB native (skip in no-index mode)
        if (!skipMongoDB) {
            largeOrdersCollection.drop();
            largeOrdersCollection = mongoDatabase.getCollection(LARGE_ORDERS_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
        }

        // Clear large orders - Oracle MongoDB API (SODA)
        if (oracleMongoClient != null) {
            try { oracleLargeOrdersCollection.drop(); } catch (Exception ignored) {}
            oracleLargeOrdersCollection = oracleMongoDatabase.getCollection(SODA_LARGE_ORDERS_COLLECTION);
        }

        // Also need customers (skip MongoDB in no-index mode)
        if (!skipMongoDB) {
            customersCollection.drop();
            customersCollection = mongoDatabase.getCollection(CUSTOMERS_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
        }

        if (oracleMongoClient != null) {
            try { oracleCustomersCollection.drop(); } catch (Exception ignored) {}
            oracleCustomersCollection = oracleMongoDatabase.getCollection(SODA_CUSTOMERS_COLLECTION);
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
        if (!skipMongoDB) customersCollection.insertMany(customerDocs);
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
        if (!skipMongoDB) largeOrdersCollection.insertMany(orderDocs);
        if (oracleLargeOrdersCollection != null) {
            oracleLargeOrdersCollection.insertMany(new ArrayList<>(orderDocs));
        }
        insertLargeOrdersJdbc(customerDocs, orderDocs);

        if (!noIndexMode && !skipMongoDB) {
            largeOrdersCollection.createIndex(new Document("customer_id", 1));
            if (oracleLargeOrdersCollection != null) {
                try {
                    oracleLargeOrdersCollection.createIndex(new Document("customer_id", 1));
                } catch (Exception ignored) {}
            }
        }

        // Gather fresh statistics for accurate optimizer decisions
        gatherOracleStats();
    }

    private void generateProductData(int productCount) {
        // Clear products - MongoDB native (skip in no-index mode)
        if (!skipMongoDB) {
            productsCollection.drop();
            productsCollection = mongoDatabase.getCollection(PRODUCTS_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
        }

        // Clear products - Oracle MongoDB API (SODA)
        if (oracleMongoClient != null) {
            try { oracleProductsCollection.drop(); } catch (Exception ignored) {}
            oracleProductsCollection = oracleMongoDatabase.getCollection(SODA_PRODUCTS_COLLECTION);
        }

        // Clear products - Oracle JDBC (JSON and Relational)
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + PRODUCTS_COLLECTION);
            stmt.execute("TRUNCATE TABLE " + PRODUCTS_REL_TABLE);
        } catch (SQLException ignored) {}

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
                if (!skipMongoDB) productsCollection.insertMany(productDocs);
                if (oracleProductsCollection != null) {
                    oracleProductsCollection.insertMany(new ArrayList<>(productDocs));
                }
                insertProductsJdbc(productDocs);
                insertProductsRelational(productDocs);
                productDocs.clear();
            }
        }

        if (!productDocs.isEmpty()) {
            if (!skipMongoDB) productsCollection.insertMany(productDocs);
            if (oracleProductsCollection != null) {
                oracleProductsCollection.insertMany(new ArrayList<>(productDocs));
            }
            insertProductsJdbc(productDocs);
            insertProductsRelational(productDocs);
        }

        // Gather fresh statistics for accurate optimizer decisions
        gatherOracleStats();
    }

    private void insertProductsJdbc(List<Document> docs) {
        String sql = "INSERT INTO " + PRODUCTS_COLLECTION + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert products via JDBC", e);
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
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders")
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Simple helper to run a MongoDB lookup without tracking (for warmup).
     */
    private void runMongoLookup(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders")
        );
        for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
            // Consume
        }
    }

    private long measureMongoLookupLargeOrders(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_large_orders", "_id", "customer_id", "orders")
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume all results - this will throw if document exceeds 16MB
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoLookupWithSort(int limit, boolean allowDiskUse) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                Aggregates.unwind("$orders"),
                Aggregates.sort(new Document("orders.total", -1))
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            AggregateIterable<Document> result = customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE);
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
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                Aggregates.sort(new Document("name", 1))
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE).allowDiskUse(true)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoPipeline3Stage(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                Aggregates.unwind("$orders"),
                Aggregates.group("$_id",
                        com.mongodb.client.model.Accumulators.sum("totalSpent", "$orders.total"),
                        com.mongodb.client.model.Accumulators.sum("orderCount", 1))
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE).allowDiskUse(true)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoPipeline4Stage(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                Aggregates.unwind("$orders"),
                Aggregates.group("$_id",
                        com.mongodb.client.model.Accumulators.sum("totalSpent", "$orders.total"),
                        com.mongodb.client.model.Accumulators.sum("orderCount", 1)),
                Aggregates.sort(new Document("totalSpent", -1))
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE).allowDiskUse(true)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoChainedLookups(int limit) {
        // Note: MongoDB doesn't have a direct way to lookup from order items to products
        // This simulates chained lookups by doing customer -> orders then another aggregation
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders"),
                Aggregates.lookup("benchmark_products", "orders.product_id", "_id", "products")
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE).allowDiskUse(true)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // Selective Join Measurement Methods (MongoDB)
    // ==========================================================================

    private long measureMongoSingleCustomerLookup() {
        String targetCustomerId = "customer_0"; // First customer

        List<Bson> pipeline = Arrays.asList(
                Aggregates.match(Filters.eq("_id", targetCustomerId)),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders")
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume results
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoBatchCustomerLookup(int batchSize) {
        List<String> targetIds = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            targetIds.add("customer_" + i);
        }

        List<Bson> pipeline = Arrays.asList(
                Aggregates.match(Filters.in("_id", targetIds)),
                Aggregates.lookup("benchmark_orders", "_id", "customer_id", "orders")
        );

        // Track pipeline and explain for report
        trackMongoPipeline(customersCollection, pipeline);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : customersCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume results
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // Selective Join Measurement Methods (Oracle API)
    // ==========================================================================

    private long measureOracleSingleCustomerLookupApi() {
        // Selective lookup - filter customer first, then join (matches MongoDB $match -> $lookup)
        // MongoDB API stitches columns together - use aliases to differentiate
        String sql = "SELECT c.data as customer, o.data as \"order\" " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE JSON_VALUE(c.data, '$._id') = 'customer_0'";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureOracleBatchCustomerLookupApi(int batchSize) {
        StringBuilder inClause = new StringBuilder("(");
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) inClause.append(", ");
            inClause.append("'customer_").append(i).append("'");
        }
        inClause.append(")");

        // Selective lookup - filter customers first, then join (matches MongoDB $match -> $lookup)
        // MongoDB API stitches columns together - use aliases to differentiate
        String sql = "SELECT c.data as customer, o.data as \"order\" " +
                "FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE JSON_VALUE(c.data, '$._id') IN " + inClause;

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // Selective Join Measurement Methods (Oracle JDBC)
    // ==========================================================================

    private long measureOracleSingleCustomerLookupJdbc() {
        String sql = "SELECT c.data, o.data FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE JSON_VALUE(c.data, '$._id') = ?";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", "'customer_0'");

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                ps.setString(1, "customer_0");
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
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
            throw new RuntimeException("JDBC selective join failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleBatchCustomerLookupJdbc(int batchSize) {
        StringBuilder inClause = new StringBuilder("(");
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) inClause.append(", ");
            inClause.append("'customer_").append(i).append("'");
        }
        inClause.append(")");

        String sql = "SELECT c.data, o.data FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE JSON_VALUE(c.data, '$._id') IN " + inClause;

        // Track for SQL details capture
        lastJdbcSql = sql;

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
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
            throw new RuntimeException("JDBC batch join failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleSingleCustomerLookupRelational() {
        String sql = "SELECT c.customer_id, c.name, c.email, o.order_id, o.order_date, o.total " +
                "FROM " + CUSTOMERS_REL_TABLE + " c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "WHERE c.customer_id = ?";

        lastRelationalSql = sql.replace("?", "'customer_0'");

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                ps.setString(1, "customer_0");
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getString(4);
                            rs.getDate(5);
                            rs.getBigDecimal(6);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational selective join failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleBatchCustomerLookupRelational(int batchSize) {
        StringBuilder inClause = new StringBuilder("(");
        for (int i = 0; i < batchSize; i++) {
            if (i > 0) inClause.append(", ");
            inClause.append("'customer_").append(i).append("'");
        }
        inClause.append(")");

        String sql = "SELECT c.customer_id, c.name, c.email, o.order_id, o.order_date, o.total " +
                "FROM " + CUSTOMERS_REL_TABLE + " c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "WHERE c.customer_id IN " + inClause;

        lastRelationalSql = sql;

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getString(4);
                            rs.getDate(5);
                            rs.getBigDecimal(6);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational batch join failed: " + e.getMessage(), e);
        }
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
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
                    }
                }
            }

            // Measure
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
            oracleMongoDatabase.aggregate(pipeline).first();
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            oracleMongoDatabase.aggregate(pipeline).first();
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

        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        String sql = "SELECT " + hint + " c.data, o.data FROM " +
                "(SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);  // Match MongoDB batch size
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    /**
     * Measures Oracle relational JOIN performance (pure SQL, no JSON).
     * This removes all JSON parsing overhead to isolate SQL engine performance.
     */
    private long measureOracleJoinRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        // Pure relational JOIN - no JSON overhead
        String sql = "SELECT " + hint + " c.customer_id, c.name, c.email, c.region, " +
                "o.order_id, o.order_date, o.total, o.status " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id";

        // Track for SQL details capture
        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);  // Match MongoDB batch size
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Read all columns to ensure full data transfer
                            rs.getString(1); rs.getString(2); rs.getString(3); rs.getString(4);
                            rs.getString(5); rs.getString(6); rs.getDouble(7); rs.getString(8);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle relational JDBC error", e);
        }
    }

    /**
     * Measures Oracle hybrid JOIN performance via JDBC.
     * Uses virtual columns for join predicates (standard B-tree indexes) while returning full JSON data.
     * This combines the join efficiency of relational with the flexibility of JSON storage.
     */
    private long measureOracleJoinHybrid(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        // Join on virtual relational columns, return full JSON data
        String sql = "SELECT " + hint + " c.data, o.data FROM " +
                "(SELECT * FROM " + CUSTOMERS_HYBRID_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_HYBRID_TABLE + " o ON c.customer_id = o.customer_id";

        // Track for SQL details capture
        lastHybridSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);  // Match MongoDB batch size
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Read both JSON columns to ensure full data transfer
                            rs.getString(1);
                            rs.getString(2);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle hybrid JDBC error", e);
        }
    }

    /**
     * Measures Oracle hybrid JOIN performance via MongoDB API ($sql).
     * Uses virtual columns for join predicates while returning full JSON data.
     */
    private long measureOracleJoinHybridMongoApi(int limit, int parallelDegree) {
        if (oracleMongoClient == null) {
            return -1;
        }

        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        // Join on virtual relational columns, return full JSON data
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\" FROM " +
                "(SELECT * FROM " + CUSTOMERS_HYBRID_TABLE + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_HYBRID_TABLE + " o ON c.customer_id = o.customer_id";

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle hybrid selective lookup - query for a specific customer.
     * This should trigger index usage since we're filtering by customer_id.
     */
    private long measureOracleJoinHybridSelective(String customerId) {
        // Selective lookup: find orders for a specific customer using the indexed virtual column
        String sql = "SELECT c.data, o.data FROM " + CUSTOMERS_HYBRID_TABLE + " c " +
                "JOIN " + ORDERS_HYBRID_TABLE + " o ON c.customer_id = o.customer_id " +
                "WHERE c.customer_id = ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setString(1, customerId);
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
            throw new RuntimeException("Oracle hybrid selective JDBC error", e);
        }
    }

    /**
     * Measures Oracle JSON selective lookup - query for a specific customer.
     */
    private long measureOracleJoinJsonSelective(String customerId) {
        // Selective lookup using JSON_VALUE function-based index
        String sql = "SELECT c.data, o.data FROM " + CUSTOMERS_COLLECTION + " c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE JSON_VALUE(c.data, '$._id') = ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setString(1, customerId);
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
            throw new RuntimeException("Oracle JSON selective JDBC error", e);
        }
    }

    /**
     * Measures Oracle relational selective lookup - query for a specific customer.
     */
    private long measureOracleJoinRelationalSelective(String customerId) {
        String sql = "SELECT c.customer_id, c.name, o.order_id, o.total FROM " + CUSTOMERS_REL_TABLE + " c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "WHERE c.customer_id = ?";

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setString(1, customerId);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getDouble(4);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle relational selective JDBC error", e);
        }
    }

    private long measureOracleJoinMongoApi(int limit, int parallelDegree) {
        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        // MongoDB API stitches columns together - use aliases to differentiate
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\" FROM " +
                "(SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        // Use database-level aggregation per Josh's example: db.aggregate([{$sql:...}])
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private void runOracleSqlJoin(int limit, int parallelDegree) {
        if (useOracleMongoApi) {
            runOracleApiJoin(limit, parallelDegree);
        } else {
            runOracleJdbcJoin(limit, parallelDegree);
        }
    }

    private void runOracleApiJoin(int limit, int parallelDegree) {
        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        // MongoDB API stitches columns together - use aliases to differentiate
        // Use database-level aggregation per Josh's example: db.aggregate([{$sql:...}])
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\" FROM " +
                "(SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));
        for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
    }

    private void runOracleJdbcJoin(int limit, int parallelDegree) {
        try {
            // Use dot notation for type-preserving JSON access
            // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
            String hint = parallelDegree > 1
                    ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                    : "";
            String sql = "SELECT " + hint + " c.data, o.data FROM " +
                    "(SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                    "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {}
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC error", e);
        }
    }

    private void printTripleResult(String testName, long mongoNanos, long oracleApiNanos, long oracleJdbcNanos) {
        String oracleApiStr = oracleApiNanos >= 0 ? String.format("%,12d", oracleApiNanos) : "         N/A";
        System.out.printf("  %-24s - MongoDB: %,12d ns | Oracle API: %s ns | Oracle JDBC: %,12d ns%n",
                testName, mongoNanos, oracleApiStr, oracleJdbcNanos);
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

        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        String sql = "SELECT " + hint + " c.data, o.data FROM " +
                "(SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + LARGE_ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        // MongoDB API stitches columns together - use aliases to differentiate
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\" FROM " +
                "(SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + LARGE_ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle relational JOIN for large orders (no JSON overhead).
     * Joins CUSTOMERS_REL_TABLE with LARGE_ORDERS_REL_TABLE.
     */
    private long measureOracleJoinLargeOrdersRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        // Pure relational JOIN - no JSON overhead
        // Note: padding is CLOB which may be large
        String sql = "SELECT " + hint + " c.customer_id, c.name, c.email, c.region, " +
                "o.order_id, o.order_date, o.total, o.status, o.padding " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + LARGE_ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id";

        // Track for SQL details capture
        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Read all columns to ensure full data transfer
                            rs.getString(1); rs.getString(2); rs.getString(3); rs.getString(4);
                            rs.getString(5); rs.getString(6); rs.getDouble(7); rs.getString(8);
                            rs.getString(9); // padding CLOB
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle relational JDBC error (large orders)", e);
        }
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

        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        String sql = "SELECT " + hint + " c.data, o.data FROM " +
                "(SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "ORDER BY JSON_VALUE(o.data, '$.total' RETURNING NUMBER) DESC";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
        // Limit customers BEFORE join to match MongoDB $limit -> $lookup behavior
        // MongoDB API stitches columns together - use aliases to differentiate
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\" FROM " +
                "(SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "ORDER BY JSON_VALUE(o.data, '$.total' RETURNING NUMBER) DESC";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
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

        // Limit customers BEFORE join to match MongoDB $limit -> $lookup -> $unwind -> $group behavior
        String sql = "SELECT " + hint + " JSON_VALUE(c.data, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM (SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "GROUP BY JSON_VALUE(c.data, '$._id')";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
        // Limit customers BEFORE join to match MongoDB $limit -> $lookup -> $unwind -> $group behavior
        // MongoDB API stitches columns together into document fields
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " JSON_VALUE(c.data, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM (SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "GROUP BY JSON_VALUE(c.data, '$._id')";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
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

        // Limit customers BEFORE join to match MongoDB behavior
        String sql = "SELECT " + hint + " JSON_VALUE(c.data, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM (SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "GROUP BY JSON_VALUE(c.data, '$._id') ORDER BY total_spent DESC";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
        // Limit customers BEFORE join to match MongoDB behavior
        // MongoDB API stitches columns together into document fields
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " JSON_VALUE(c.data, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) as total_spent, " +
                "COUNT(*) as order_count " +
                "FROM (SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "GROUP BY JSON_VALUE(c.data, '$._id') " +
                "ORDER BY SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)) DESC";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
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

        // Limit customers BEFORE join to match MongoDB behavior
        String sql = "SELECT " + hint + " c.data, o.data, p.data " +
                "FROM (SELECT * FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "LEFT JOIN " + PRODUCTS_COLLECTION + " p ON JSON_VALUE(o.data, '$.product_id') = JSON_VALUE(p.data, '$._id')";

        // Track for SQL details capture
        lastJdbcSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
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
        // Limit customers BEFORE join to match MongoDB behavior
        // MongoDB API stitches columns together - use aliases to differentiate
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") PARALLEL(p, " + parallelDegree + ") */"
                : "";
        String sql = "SELECT " + hint + " c.data as customer, o.data as \"order\", p.data as product " +
                "FROM (SELECT data FROM " + CUSTOMERS_COLLECTION + " WHERE ROWNUM <= " + limit + ") c " +
                "JOIN " + ORDERS_COLLECTION + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "LEFT JOIN " + PRODUCTS_COLLECTION + " p ON JSON_VALUE(o.data, '$.product_id') = JSON_VALUE(p.data, '$._id')";

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // ==========================================================================
    // Oracle Relational Measurement Methods (for E, F, G tests)
    // ==========================================================================

    private long measureOracleJoinWithSortRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.customer_id, c.name, c.email, " +
                "o.order_id, o.order_date, o.total " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "ORDER BY o.total DESC";

        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getString(2);
                            rs.getString(3);
                            rs.getString(4);
                            rs.getDate(5);
                            rs.getBigDecimal(6);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational join with sort failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleJoinGroupByRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.customer_id, SUM(o.total) as total_spent, COUNT(*) as order_count " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "GROUP BY c.customer_id";

        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getBigDecimal(2);
                            rs.getLong(3);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational join with group by failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleJoinGroupBySortRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") */"
                : "";

        String sql = "SELECT " + hint + " c.customer_id, SUM(o.total) as total_spent, COUNT(*) as order_count " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "GROUP BY c.customer_id ORDER BY total_spent DESC";

        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            rs.getBigDecimal(2);
                            rs.getLong(3);
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational join with group by sort failed: " + e.getMessage(), e);
        }
    }

    private long measureOracleMultiTableJoinRelational(int limit, int parallelDegree) {
        String hint = parallelDegree > 1
                ? "/*+ PARALLEL(c, " + parallelDegree + ") PARALLEL(o, " + parallelDegree + ") PARALLEL(p, " + parallelDegree + ") */"
                : "";

        // Full 3-table join: customers -> orders -> products
        String sql = "SELECT " + hint + " c.customer_id, c.name, o.order_id, o.total, " +
                "p.product_id, p.name as product_name, p.category, p.price " +
                "FROM (SELECT * FROM " + CUSTOMERS_REL_TABLE + " WHERE ROWNUM <= ?) c " +
                "JOIN " + ORDERS_REL_TABLE + " o ON c.customer_id = o.customer_id " +
                "LEFT JOIN " + PRODUCTS_REL_TABLE + " p ON o.product_id = p.product_id";

        lastRelationalSql = sql.replace("?", String.valueOf(limit));

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit);
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Read customer columns
                            rs.getString(1); // customer_id
                            rs.getString(2); // name
                            // Read order columns
                            rs.getString(3); // order_id
                            rs.getBigDecimal(4); // total
                            // Read product columns (may be null due to LEFT JOIN)
                            rs.getString(5); // product_id
                            rs.getString(6); // product_name
                            rs.getString(7); // category
                            rs.getBigDecimal(8); // price
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Relational multi-table join failed: " + e.getMessage(), e);
        }
    }

    // ==========================================================================
    // Simple Scan Measurement Methods (No Join)
    // ==========================================================================

    /**
     * Measures MongoDB native simple scan performance (no join).
     * Uses ordersCollection.find().limit(limit) to scan documents.
     */
    private long measureMongoSimpleScan(int limit) {
        // Track for report
        lastMongoPipeline = "db.benchmark_orders.find({}).limit(" + limit + ")";
        try {
            lastMongoExplain = ordersCollection.find().limit(limit).explain().toJson(
                    JsonWriterSettings.builder().indent(true).build());
        } catch (Exception e) {
            lastMongoExplain = "Error: " + e.getMessage();
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : ordersCollection.find().limit(limit).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle API $sql simple scan performance (no join).
     * Uses $sql with simple SELECT from JSON collection.
     */
    private long measureOracleSimpleScanApi(int limit) {
        String sql = "SELECT data FROM " + ORDERS_COLLECTION + " WHERE ROWNUM <= " + limit;

        // Track for SQL details capture
        lastApiSql = sql;

        List<Document> pipeline = Arrays.asList(buildSqlAggregationStage(sql));

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoDatabase.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /**
     * Measures Oracle JDBC JSON simple scan performance (no join).
     * Uses JDBC with simple SELECT from JSON collection.
     */
    private long measureOracleSimpleScanJdbc(int limit) {
        String sql = "SELECT data FROM " + ORDERS_COLLECTION + " WHERE ROWNUM <= " + limit;

        // Track for SQL details capture
        lastJdbcSql = sql;

        try {
            long totalNanos = 0;
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        while (rs.next()) {
                            // Consume
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle JDBC simple scan error", e);
        }
    }

    /**
     * Measures Oracle JDBC relational simple scan performance (no join).
     * Uses JDBC with simple SELECT from relational table (no JSON overhead).
     */
    private long measureOracleSimpleScanRelational(int limit) {
        String sql = "SELECT order_id, customer_id, order_date, total, status, product_id FROM " +
                ORDERS_REL_TABLE + " WHERE ROWNUM <= " + limit;

        // Track for SQL details capture
        lastRelationalSql = sql;

        try {
            long totalNanos = 0;
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        while (rs.next()) {
                            // Read all columns to ensure full data transfer
                            rs.getString(1); // order_id
                            rs.getString(2); // customer_id
                            rs.getString(3); // order_date
                            rs.getDouble(4); // total
                            rs.getString(5); // status
                            rs.getString(6); // product_id
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle relational simple scan error", e);
        }
    }

    /**
     * Warmup for Oracle native MongoDB API scan (no $sql - uses native MongoDB pipeline).
     */
    private void runOracleNativeApiScan(int limit) {
        if (oracleOrdersCollection == null) return;
        for (Document doc : oracleOrdersCollection.find().limit(limit).batchSize(JDBC_FETCH_SIZE)) {
            // Consume
        }
    }

    /**
     * Measures Oracle MongoDB API native pipeline performance (no $sql).
     * Uses oracleOrdersCollection.find().limit(limit) - the same native MongoDB
     * pipeline syntax but executed against Oracle's MongoDB API.
     */
    private long measureOracleNativeApiScan(int limit) {
        if (oracleOrdersCollection == null) return -1;

        // Track for report
        lastOracleNativePipeline = "db.benchmark_orders.find({}).limit(" + limit + ")";
        try {
            lastOracleNativeExplain = oracleOrdersCollection.find().limit(limit).explain().toJson(
                    JsonWriterSettings.builder().indent(true).build());
        } catch (Exception e) {
            lastOracleNativeExplain = "Error: " + e.getMessage();
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleOrdersCollection.find().limit(limit).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
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

    /**
     * Finds AWR report content for a given report category.
     * Maps report categories (baseline, cardinality, etc.) to AWR snapshot categories (A0_baseline, B0_cardinality, etc.)
     */
    private static String findAwrReportForCategory(String reportCategory) {
        // Map report category names to AWR snapshot prefixes
        String awrPrefix = switch (reportCategory) {
            case "baseline" -> "A";
            case "cardinality" -> "B0_cardinality";
            case "parallel" -> "C0_parallel";
            case "selective" -> "H0_selective";
            case "doc_size" -> "D0_doc_size";
            case "memory" -> "E0_memory";
            case "sort" -> "F0_sort";
            case "pipeline" -> "G0_pipeline";
            case "relational" -> "R0_relational";
            case "scan" -> "S0_scan";
            default -> reportCategory;
        };

        // First try exact match
        String content = awrReportContent.get(awrPrefix);
        if (content != null) return content;

        // For baseline, try to find any A* snapshot
        if (reportCategory.equals("baseline")) {
            for (Map.Entry<String, String> entry : awrReportContent.entrySet()) {
                if (entry.getKey().startsWith("A")) {
                    return entry.getValue();
                }
            }
        }

        // Try to find any matching prefix
        for (Map.Entry<String, String> entry : awrReportContent.entrySet()) {
            if (entry.getKey().toLowerCase().contains(reportCategory.toLowerCase())) {
                return entry.getValue();
            }
        }

        return null;
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
                String awrHtml = generateAwrHtmlReport(snaps[0], snaps[1], filename);
                // Store AWR content for embedding in main report (use category prefix for matching)
                String categoryKey = category.split("_")[0];  // e.g., "A0_baseline" -> "A0"
                awrReportContent.put(category, awrHtml);
                System.out.println("  Generated: " + filename + " (snaps " + snaps[0] + " - " + snaps[1] + ")");
            } catch (Exception e) {
                System.out.println("  Failed to generate report for " + category + ": " + e.getMessage());
            }
        }
    }

    private static String generateAwrHtmlReport(long beginSnap, long endSnap, String filename)
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
        return report.toString();
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
                case "selective" -> "SELECTIVE/INDEXED JOINS";
                case "doc_size" -> "DOCUMENT SIZE LIMIT TESTS";
                case "memory" -> "AGGREGATION MEMORY LIMIT";
                case "sort" -> "SORT SPILLOVER TESTS";
                case "pipeline" -> "MULTI-STAGE PIPELINE";
                case "relational" -> "RELATIONAL VS JSON COMPARISON";
                case "hybrid" -> "HYBRID SCHEMA (JSON + VIRTUAL COLUMNS)";
                case "scan" -> "SIMPLE SCAN TESTS (NO JOIN)";
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
            String indexModeLabel = noIndexMode ? " (NO INDEXES)" : "";
            html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>MongoDB vs Oracle - Triple Comparison Benchmark Report""" + indexModeLabel + """
                </title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 0; padding: 20px; background: linear-gradient(135deg, #0f0f23 0%, #1a1a3e 100%); min-height: 100vh; color: #eee; }
                        .container { max-width: 1800px; margin: 0 auto; }
                        h1 { text-align: center; color: #00d4ff; font-size: 2.8em; margin-bottom: 5px; text-shadow: 0 0 30px rgba(0, 212, 255, 0.6); }
                        .subtitle { text-align: center; color: #888; margin-bottom: 30px; font-size: 1.2em; }
                        .summary-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 20px; margin-bottom: 40px; }
                        .summary-card { background: rgba(255, 255, 255, 0.05); border-radius: 16px; padding: 25px; text-align: center; border: 1px solid rgba(255, 255, 255, 0.1); }
                        .summary-card h3 { margin: 0 0 15px 0; font-size: 1em; color: #888; }
                        .summary-card .value { font-size: 2.2em; font-weight: bold; }
                        .mongo-color { color: #4ade80; }
                        .oracle-api-color { color: #f59e0b; }
                        .oracle-jdbc-color { color: #3b82f6; }
                        .failure-color { color: #ef4444; }
                        .chart-section { background: rgba(255, 255, 255, 0.03); border-radius: 20px; padding: 35px; margin-bottom: 35px; border: 1px solid rgba(255, 255, 255, 0.08); }
                        .chart-title { color: #00d4ff; font-size: 1.5em; margin-bottom: 25px; padding-bottom: 15px; border-bottom: 2px solid rgba(0, 212, 255, 0.3); }
                        table { border-collapse: collapse; width: 100%; margin: 25px 0; background: rgba(0,0,0,0.3); border-radius: 12px; overflow: hidden; }
                        th, td { border: 1px solid rgba(255,255,255,0.08); padding: 14px 12px; text-align: right; }
                        th { background: rgba(0, 212, 255, 0.15); color: #00d4ff; font-weight: 600; }
                        td:first-child { text-align: left; font-weight: 500; }
                        tr:nth-child(even) { background: rgba(255,255,255,0.02); }
                        tr:hover { background: rgba(0, 212, 255, 0.08); }
                        .winner-mongo { color: #4ade80; font-weight: bold; }
                        .winner-oracle-native { color: #a855f7; font-weight: bold; }
                        .winner-oracle-api { color: #f59e0b; font-weight: bold; }
                        .winner-oracle-jdbc { color: #3b82f6; font-weight: bold; }
                        .winner-oracle-rel { color: #ec4899; font-weight: bold; }
                        .winner-oracle-hybrid { color: #14b8a6; font-weight: bold; }
                        .failure { color: #ef4444; font-weight: bold; }
                        .legend-box { display: flex; justify-content: center; gap: 40px; margin: 20px 0; padding: 15px; background: rgba(0,0,0,0.2); border-radius: 10px; }
                        .legend-item { display: flex; align-items: center; gap: 10px; }
                        .legend-dot { width: 16px; height: 16px; border-radius: 50%; }
                        .footer { text-align: center; margin-top: 50px; color: #555; font-size: 0.9em; padding: 20px; border-top: 1px solid rgba(255,255,255,0.1); }
                        .category-desc { color: #aaa; font-size: 0.95em; line-height: 1.6; margin-bottom: 20px; padding: 15px 20px; background: rgba(0,212,255,0.05); border-left: 3px solid rgba(0,212,255,0.3); border-radius: 0 8px 8px 0; }
                        .category-desc code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 4px; font-family: 'Fira Code', 'Consolas', monospace; color: #00d4ff; }
                        /* Tab styles */
                        .tabs { display: flex; gap: 5px; margin-bottom: 20px; border-bottom: 2px solid rgba(255,255,255,0.1); padding-bottom: 10px; }
                        .tab-btn { padding: 10px 20px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1); border-radius: 8px 8px 0 0; color: #888; cursor: pointer; transition: all 0.2s; font-size: 0.9em; }
                        .tab-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }
                        .tab-btn.active { background: rgba(0, 212, 255, 0.2); border-color: rgba(0, 212, 255, 0.4); color: #00d4ff; }
                        .tab-content { display: none; }
                        .tab-content.active { display: block; }
                        /* Subtab styles for SQL Monitor and AWR */
                        .subtabs { display: flex; gap: 5px; margin-bottom: 15px; flex-wrap: wrap; }
                        .subtab-btn { padding: 6px 14px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.15); border-radius: 6px; color: #888; cursor: pointer; transition: all 0.2s; font-size: 0.85em; }
                        .subtab-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }
                        .subtab-btn.active { background: rgba(59, 130, 246, 0.3); border-color: rgba(59, 130, 246, 0.5); color: #3b82f6; }
                        .subtab-content { display: none; }
                        .subtab-content.active { display: block; }
                        /* Protocol-level tabs for SQL Monitor */
                        .protocol-tabs { margin-bottom: 15px; border-bottom: 2px solid rgba(255,255,255,0.1); padding-bottom: 10px; }
                        .protocol-btn { font-weight: 600; padding: 8px 16px; }
                        .protocol-btn.active { color: #fff !important; }
                        .protocol-content { display: none; padding: 10px 0; }
                        .protocol-content.active { display: block; }
                        /* Test-level tabs within protocol sections */
                        .test-tabs { background: rgba(0,0,0,0.2); padding: 8px; border-radius: 8px; }
                        .test-btn { padding: 4px 10px; font-size: 0.8em; }
                        .test-content { display: none; margin-top: 10px; }
                        .test-content.active { display: block; }
                        .sql-box { background: rgba(0,0,0,0.4); border: 1px solid rgba(255,255,255,0.1); border-radius: 8px; padding: 15px; font-family: 'Fira Code', 'Consolas', monospace; font-size: 0.85em; color: #ddd; overflow-x: auto; white-space: pre-wrap; word-wrap: break-word; max-height: 400px; overflow-y: auto; }
                        .sql-box .keyword { color: #569cd6; }
                        .sql-box .function { color: #dcdcaa; }
                        .sql-box .string { color: #ce9178; }
                        .sql-box .comment { color: #6a9955; }
                    </style>
                </head>
                <body>
                <div class="container">
                    <h1>MongoDB vs Oracle: Quad Comparison""" + indexModeLabel + """
                </h1>
                    <p class="subtitle">MongoDB $lookup vs Oracle $sql vs JDBC JSON vs JDBC Relational""" +
                    (noIndexMode ? " <span style='color: #ef4444;'>(NO INDEXES - Baseline)</span>" : "") + """
                </p>

                    <div class="legend-box">
                        <div class="legend-item"><div class="legend-dot" style="background: #4ade80;"></div><span>MongoDB Native ($lookup)</span></div>
                        <div class="legend-item"><div class="legend-dot" style="background: #a855f7;"></div><span>Oracle Native (MongoDB API)</span></div>
                        <div class="legend-item"><div class="legend-dot" style="background: #f59e0b;"></div><span>Oracle $sql (MongoDB API)</span></div>
                        <div class="legend-item"><div class="legend-dot" style="background: #3b82f6;"></div><span>Oracle JDBC JSON</span></div>
                        <div class="legend-item"><div class="legend-dot" style="background: #ec4899;"></div><span>Oracle JDBC Relational</span></div>
                        <div class="legend-item"><div class="legend-dot" style="background: #14b8a6;"></div><span>Oracle JDBC Hybrid</span></div>
                    </div>
                """);

            // Combine tripleResults, quadResults, quintResults, and sextetResults into unified 7-value arrays
            // Format: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos]
            Map<String, long[]> combinedResults = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> entry : tripleResults.entrySet()) {
                String testId = entry.getKey();
                long[] triple = entry.getValue();
                // Check if we have sextet, quint, or quad results for this test
                long[] sextet = sextetResults.get(testId);
                long[] quint = quintResults.get(testId);
                long[] quad = quadResults.get(testId);
                if (sextet != null) {
                    // Convert sextet to 7-value format: [mongo, -1, api, jdbc, rel, hybrid, hybridApi]
                    combinedResults.put(testId, new long[]{sextet[0], -1, sextet[1], sextet[2], sextet[3], sextet[4], sextet[5]});
                } else if (quint != null) {
                    combinedResults.put(testId, new long[]{quint[0], quint[1], quint[2], quint[3], quint[4], -1, -1}); // Use quint (has Oracle native API)
                } else if (quad != null) {
                    // Convert quad to 7-value format: insert -1 for oracleNativeApi at position 1
                    combinedResults.put(testId, new long[]{quad[0], -1, quad[1], quad[2], quad[3], -1, -1});
                } else {
                    combinedResults.put(testId, new long[]{triple[0], -1, triple[1], triple[2], -1, -1, -1}); // No native, no relational, no hybrid
                }
            }
            // Add any sextet results that aren't in triple results
            for (Map.Entry<String, long[]> entry : sextetResults.entrySet()) {
                if (!combinedResults.containsKey(entry.getKey())) {
                    long[] sextet = entry.getValue();
                    combinedResults.put(entry.getKey(), new long[]{sextet[0], -1, sextet[1], sextet[2], sextet[3], sextet[4], sextet[5]});
                }
            }
            // Add any quad results that aren't in triple results
            for (Map.Entry<String, long[]> entry : quadResults.entrySet()) {
                if (!combinedResults.containsKey(entry.getKey())) {
                    long[] quad = entry.getValue();
                    // Convert quad to 7-value format
                    combinedResults.put(entry.getKey(), new long[]{quad[0], -1, quad[1], quad[2], quad[3], -1, -1});
                }
            }
            // Add any quint results that aren't already in combined
            for (Map.Entry<String, long[]> entry : quintResults.entrySet()) {
                if (!combinedResults.containsKey(entry.getKey())) {
                    long[] quint = entry.getValue();
                    combinedResults.put(entry.getKey(), new long[]{quint[0], quint[1], quint[2], quint[3], quint[4], -1, -1});
                }
            }

            // Summary counts
            // Format: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos]
            int mongoWins = 0, oracleNativeWins = 0, oracleApiWins = 0, oracleJdbcWins = 0, oracleRelWins = 0, oracleHybridWins = 0, mongoFailures = 0;
            for (Map.Entry<String, long[]> entry : combinedResults.entrySet()) {
                long[] times = entry.getValue();
                long mongoNanos = times[0];
                long oracleNativeNanos = times.length > 1 ? times[1] : -1;
                long oracleApiNanos = times.length > 2 ? times[2] : -1;
                long oracleJdbcNanos = times.length > 3 ? times[3] : -1;
                long oracleRelNanos = times.length > 4 ? times[4] : -1;
                long oracleHybridNanos = times.length > 5 ? times[5] : -1;

                if (mongoNanos < 0) {
                    mongoFailures++;
                } else {
                    long bestTime = mongoNanos;
                    String winner = "mongo";
                    if (oracleNativeNanos > 0 && oracleNativeNanos < bestTime) {
                        bestTime = oracleNativeNanos;
                        winner = "native";
                    }
                    if (oracleApiNanos > 0 && oracleApiNanos < bestTime) {
                        bestTime = oracleApiNanos;
                        winner = "api";
                    }
                    if (oracleJdbcNanos > 0 && oracleJdbcNanos < bestTime) {
                        bestTime = oracleJdbcNanos;
                        winner = "jdbc";
                    }
                    if (oracleHybridNanos > 0 && oracleHybridNanos < bestTime) {
                        bestTime = oracleHybridNanos;
                        winner = "hybrid";
                    }
                    if (oracleRelNanos > 0 && oracleRelNanos < bestTime) {
                        winner = "rel";
                    }
                    switch (winner) {
                        case "mongo" -> mongoWins++;
                        case "native" -> oracleNativeWins++;
                        case "api" -> oracleApiWins++;
                        case "jdbc" -> oracleJdbcWins++;
                        case "rel" -> oracleRelWins++;
                        case "hybrid" -> oracleHybridWins++;
                    }
                }
            }

            html.append(String.format("""
                    <div class="summary-grid">
                        <div class="summary-card"><h3>MongoDB Wins</h3><div class="value mongo-color">%d</div></div>
                        <div class="summary-card"><h3>Oracle Native Wins</h3><div class="value" style="color: #a855f7;">%d</div></div>
                        <div class="summary-card"><h3>Oracle $sql Wins</h3><div class="value oracle-api-color">%d</div></div>
                        <div class="summary-card"><h3>JDBC JSON Wins</h3><div class="value oracle-jdbc-color">%d</div></div>
                        <div class="summary-card"><h3>JDBC Relational Wins</h3><div class="value" style="color: #ec4899;">%d</div></div>
                        <div class="summary-card"><h3>JDBC Hybrid Wins</h3><div class="value" style="color: #14b8a6;">%d</div></div>
                        <div class="summary-card"><h3>MongoDB Failures</h3><div class="value failure-color">%d</div></div>
                    </div>
                """, mongoWins, oracleNativeWins, oracleApiWins, oracleJdbcWins, oracleRelWins, oracleHybridWins, mongoFailures));

            // Group results by category
            Map<String, List<Map.Entry<String, long[]>>> grouped = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> entry : combinedResults.entrySet()) {
                String testId = entry.getKey();
                TestResult result = results.get(testId);
                if (result != null) {
                    grouped.computeIfAbsent(result.category, k -> new ArrayList<>()).add(entry);
                }
            }

            for (Map.Entry<String, List<Map.Entry<String, long[]>>> categoryEntry : grouped.entrySet()) {
                String category = categoryEntry.getKey();
                String header = switch (category) {
                    case "baseline" -> "Baseline Join Performance";
                    case "cardinality" -> "One-to-Many Cardinality";
                    case "parallel" -> "Oracle Parallel Execution";
                    case "selective" -> "Selective/Indexed Joins";
                    case "doc_size" -> "Document Size Limit Tests";
                    case "memory" -> "Aggregation Memory Limit";
                    case "sort" -> "Sort Spillover Tests";
                    case "pipeline" -> "Multi-Stage Pipeline";
                    case "relational" -> "Relational vs JSON Comparison";
                    case "hybrid" -> "Hybrid Schema (JSON + Virtual Columns)";
                    case "scan" -> "Simple Scan Tests (No Join)";
                    default -> category;
                };

                String categoryDesc = switch (category) {
                    case "baseline" -> """
                        Tests standard foreign key join performance using MongoDB's <code>$lookup</code> aggregation stage vs Oracle's
                        <code>$sql</code> operator (via MongoDB API) vs Oracle JDBC with SQL JOIN. Each test joins customers to their
                        orders and measures total execution time including full result retrieval. Dataset sizes scale from 1K to 100K
                        customers with 10 orders per customer.""";
                    case "cardinality" -> """
                        Measures how join cardinality (number of matching records per left-side document) affects performance.
                        Tests range from 1:1 (one order per customer) to 1:1000 (one thousand orders per customer).
                        Higher cardinality stresses the join operator's ability to handle result set explosion.""";
                    case "parallel" -> """
                        Evaluates Oracle's parallel execution capabilities using the <code>/*+ PARALLEL(n) */</code> hint.
                        MongoDB does not support parallel query execution at the query level. Oracle Free Edition is limited
                        to PARALLEL(2) due to CPU constraints. Tests use 100K customers joined to 1M orders.""";
                    case "selective" -> """
                        Tests real-world selective query patterns where indexes provide significant benefit. Unlike full table
                        joins, these queries filter on specific customer IDs before joining, simulating common application
                        patterns like "get orders for customer X". Tests range from single customer lookup to 1% selectivity
                        against a 100K customer dataset with 1M orders.""";
                    case "doc_size" -> """
                        Tests MongoDB's 16MB BSON document size limit by embedding increasingly large arrays in join results.
                        MongoDB's <code>$lookup</code> embeds matched documents as an array within the parent document, which
                        fails when the result exceeds 16MB. Oracle has no such limit since SQL joins return flat result sets.
                        Tests scale from 100KB to 50MB embedded arrays.""";
                    case "memory" -> """
                        Evaluates MongoDB's 100MB aggregation pipeline memory limit vs Oracle's memory management.
                        MongoDB operations that exceed this limit require <code>allowDiskUse: true</code> to spill to disk,
                        which significantly impacts performance. Oracle's SGA/PGA architecture handles large working sets
                        more efficiently. Tests scale from 50MB to 500MB working set sizes.""";
                    case "sort" -> """
                        Tests <code>$sort</code> operations on increasingly large result sets after <code>$lookup</code>.
                        Sorting large datasets may cause MongoDB to spill to disk (even with allowDiskUse), while Oracle
                        uses optimized sort algorithms with TEMP tablespace. Tests sort 10K to 1M documents by order total.""";
                    case "pipeline" -> """
                        Tests complex multi-stage aggregation pipelines combining <code>$lookup</code>, <code>$unwind</code>,
                        <code>$group</code>, and <code>$sort</code> stages. Also tests chained lookups (<code>$lookup</code> →
                        <code>$lookup</code>) for multi-table joins. Oracle SQL can express these as single queries with
                        GROUP BY and ORDER BY clauses.""";
                    case "relational" -> """
                        Compares MongoDB's document-oriented $lookup with Oracle's traditional relational SQL JOIN.
                        Tests use identical data: MongoDB collections vs Oracle JSON tables vs Oracle relational tables.
                        This isolates JSON serialization/deserialization overhead from pure query engine performance.
                        JDBC Relational represents Oracle's optimal performance without JSON overhead.""";
                    case "hybrid" -> """
                        Tests a hybrid schema approach that combines JSON document storage with relational virtual columns.
                        The hybrid tables store full JSON documents in a <code>data JSON</code> column, but project indexed
                        attributes (like <code>customer_id</code>) into <strong>virtual/generated columns</strong> computed from JSON.
                        This enables standard B-tree indexes on the virtual columns for efficient join predicates:
                        <code>JOIN ... ON c.customer_id = o.customer_id</code> instead of
                        <code>JSON_VALUE(c.data, '$.customer_id') = JSON_VALUE(o.data, '$.customer_id')</code>.
                        The hybrid approach should match relational join performance while returning full JSON documents.""";
                    case "scan" -> """
                        Tests simple scan performance without joins to isolate whether performance differences are specific
                        to <code>$sql</code>/join operations or inherent to the MongoDB API in general. Compares five approaches:
                        (1) <strong>MongoDB Native</strong>: <code>db.orders.find().limit(N)</code>,
                        (2) <strong>Oracle Native MongoDB API</strong>: Same pipeline but via Oracle's MongoDB-compatible API,
                        (3) <strong>Oracle $sql</strong>: <code>db.aggregate([{$sql: "SELECT..."}])</code>,
                        (4) <strong>JDBC JSON</strong>: <code>SELECT data FROM orders</code>,
                        (5) <strong>JDBC Relational</strong>: Pure SQL on relational tables.
                        This helps identify if performance differences are due to wire protocol overhead, $sql translation, or join processing.""";
                    default -> "";
                };

                String sectionId = category.replace("_", "");
                html.append("<div class=\"chart-section\">\n");
                html.append("<h2 class=\"chart-title\">").append(header).append("</h2>\n");
                if (!categoryDesc.isEmpty()) {
                    html.append("<div class=\"category-desc\">").append(categoryDesc).append("</div>\n");
                }

                // Add tabs
                html.append("<div class=\"tabs\">\n");
                html.append("<button class=\"tab-btn active\" onclick=\"openTab(event, 'chart-tab-").append(sectionId).append("')\">Chart</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'mongo-tab-").append(sectionId).append("')\">MongoDB</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'sql-tab-").append(sectionId).append("')\">Oracle SQL</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'plan-tab-").append(sectionId).append("')\">Oracle Plan</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'monitor-tab-").append(sectionId).append("')\">SQL Monitor</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'awr-tab-").append(sectionId).append("')\">AWR Report</button>\n");
                html.append("</div>\n");

                // Tab 1: Chart
                html.append("<div id=\"chart-tab-").append(sectionId).append("\" class=\"tab-content active\">\n");
                // Add wrapper div with padding for bar charts (pipeline) to prevent truncation
                if (category.equals("pipeline")) {
                    html.append("<div style=\"padding: 0 50px;\"><canvas id=\"chart-").append(category).append("\" style=\"max-height: 400px; margin-bottom: 30px;\"></canvas></div>\n");
                } else {
                    html.append("<canvas id=\"chart-").append(category).append("\" style=\"max-height: 400px; margin-bottom: 30px;\"></canvas>\n");
                }
                html.append("""
                    <table>
                    <tr><th>Test Case</th><th>MongoDB (ms)</th><th>Oracle Native (ms)</th><th>$sql (ms)</th><th>JDBC JSON (ms)</th><th>JDBC Rel (ms)</th><th>JDBC Hybrid (ms)</th><th>Winner</th></tr>
                    """);

                // Format: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos, oracleHybridApiNanos]
                for (Map.Entry<String, long[]> entry : categoryEntry.getValue()) {
                    String testId = entry.getKey();
                    long[] times = entry.getValue();
                    TestResult result = results.get(testId);

                    long mongoNanos = times[0];
                    long oracleNativeNanos = times.length > 1 ? times[1] : -1;
                    long oracleApiNanos = times.length > 2 ? times[2] : -1;
                    long oracleJdbcNanos = times.length > 3 ? times[3] : -1;
                    long oracleRelNanos = times.length > 4 ? times[4] : -1;
                    long oracleHybridNanos = times.length > 5 ? times[5] : -1;

                    String winner, winnerClass;
                    if (mongoNanos < 0) {
                        winner = "Oracle (Mongo FAIL)";
                        winnerClass = "failure";
                    } else {
                        long bestTime = mongoNanos;
                        winner = "MongoDB";
                        winnerClass = "winner-mongo";
                        if (oracleNativeNanos > 0 && oracleNativeNanos < bestTime) {
                            bestTime = oracleNativeNanos;
                            winner = "Oracle Native";
                            winnerClass = "winner-oracle-native";
                        }
                        if (oracleApiNanos > 0 && oracleApiNanos < bestTime) {
                            bestTime = oracleApiNanos;
                            winner = "$sql";
                            winnerClass = "winner-oracle-api";
                        }
                        if (oracleJdbcNanos > 0 && oracleJdbcNanos < bestTime) {
                            bestTime = oracleJdbcNanos;
                            winner = "JDBC JSON";
                            winnerClass = "winner-oracle-jdbc";
                        }
                        if (oracleHybridNanos > 0 && oracleHybridNanos < bestTime) {
                            bestTime = oracleHybridNanos;
                            winner = "JDBC Hybrid";
                            winnerClass = "winner-oracle-hybrid";
                        }
                        if (oracleRelNanos > 0 && oracleRelNanos < bestTime) {
                            winner = "JDBC Rel";
                            winnerClass = "winner-oracle-rel";
                        }
                    }

                    String mongoMs = mongoNanos >= 0 ? String.format("%.2f", mongoNanos / 1_000_000.0) : "<span class='failure'>FAILED</span>";
                    String oracleNativeMs = oracleNativeNanos >= 0 ? String.format("%.2f", oracleNativeNanos / 1_000_000.0) : "N/A";
                    String oracleApiMs = oracleApiNanos >= 0 ? String.format("%.2f", oracleApiNanos / 1_000_000.0) : "N/A";
                    String oracleJdbcMs = oracleJdbcNanos >= 0 ? String.format("%.2f", oracleJdbcNanos / 1_000_000.0) : "N/A";
                    String oracleRelMs = oracleRelNanos >= 0 ? String.format("%.2f", oracleRelNanos / 1_000_000.0) : "N/A";
                    String oracleHybridMs = oracleHybridNanos >= 0 ? String.format("%.2f", oracleHybridNanos / 1_000_000.0) : "N/A";

                    html.append(String.format(
                            "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class='%s'>%s</td></tr>\n",
                            result != null ? result.description : testId,
                            mongoMs, oracleNativeMs, oracleApiMs, oracleJdbcMs, oracleRelMs, oracleHybridMs,
                            winnerClass, winner));
                }

                html.append("</table>\n");
                html.append("</div>\n"); // Close chart tab

                // Collect SQL details for this category
                StringBuilder jdbcSqlContent = new StringBuilder();
                StringBuilder apiSqlContent = new StringBuilder();
                StringBuilder planContent = new StringBuilder();
                StringBuilder relationalSqlContent = new StringBuilder();
                StringBuilder relationalPlanContent = new StringBuilder();
                StringBuilder hybridSqlContent = new StringBuilder();
                StringBuilder hybridPlanContent = new StringBuilder();
                StringBuilder mongoPipelineContent = new StringBuilder();
                StringBuilder mongoExplainContent = new StringBuilder();
                StringBuilder oracleNativePipelineContent = new StringBuilder();
                StringBuilder oracleNativeExplainContent = new StringBuilder();

                for (Map.Entry<String, long[]> entry : categoryEntry.getValue()) {
                    String testId = entry.getKey();
                    SqlDetails details = sqlDetailsMap.get(testId);
                    if (details != null) {
                        TestResult result = results.get(testId);
                        String testName = result != null ? result.description : testId;

                        if (!details.jdbcSql().isEmpty()) {
                            jdbcSqlContent.append("-- ").append(testName).append("\n");
                            jdbcSqlContent.append(details.jdbcSql()).append("\n\n");
                        }
                        if (!details.apiSql().isEmpty()) {
                            apiSqlContent.append("-- ").append(testName).append("\n");
                            apiSqlContent.append(details.apiSql()).append("\n\n");
                        }
                        if (!details.explainPlan().isEmpty()) {
                            planContent.append("-- ").append(testName).append("\n");
                            planContent.append(details.explainPlan()).append("\n");
                        }
                        if (!details.relationalSql().isEmpty()) {
                            relationalSqlContent.append("-- ").append(testName).append("\n");
                            relationalSqlContent.append(details.relationalSql()).append("\n\n");
                        }
                        if (!details.relationalExplainPlan().isEmpty()) {
                            relationalPlanContent.append("-- ").append(testName).append("\n");
                            relationalPlanContent.append(details.relationalExplainPlan()).append("\n");
                        }
                        if (!details.mongoPipeline().isEmpty()) {
                            mongoPipelineContent.append("// ").append(testName).append("\n");
                            mongoPipelineContent.append(details.mongoPipeline()).append("\n\n");
                        }
                        if (!details.mongoExplain().isEmpty()) {
                            mongoExplainContent.append("// ").append(testName).append("\n");
                            mongoExplainContent.append(details.mongoExplain()).append("\n\n");
                        }
                        if (!details.oracleNativePipeline().isEmpty()) {
                            oracleNativePipelineContent.append("// ").append(testName).append("\n");
                            oracleNativePipelineContent.append(details.oracleNativePipeline()).append("\n\n");
                        }
                        if (!details.oracleNativeExplain().isEmpty()) {
                            oracleNativeExplainContent.append("// ").append(testName).append("\n");
                            oracleNativeExplainContent.append(details.oracleNativeExplain()).append("\n\n");
                        }
                        if (!details.hybridSql().isEmpty()) {
                            hybridSqlContent.append("-- ").append(testName).append("\n");
                            hybridSqlContent.append(details.hybridSql()).append("\n\n");
                        }
                        if (!details.hybridExplainPlan().isEmpty()) {
                            hybridPlanContent.append("-- ").append(testName).append("\n");
                            hybridPlanContent.append(details.hybridExplainPlan()).append("\n");
                        }
                    }
                }

                // Tab 2: MongoDB Pipeline and Explain
                html.append("<div id=\"mongo-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<h3 style=\"color: #4ade80; margin-bottom: 15px;\">MongoDB Native Aggregation Pipeline</h3>\n");
                if (mongoPipelineContent.isEmpty()) {
                    html.append("<p style=\"color: #888;\">No MongoDB pipeline captured for this category.</p>\n");
                } else {
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(mongoPipelineContent.toString())).append("</pre></div>\n");
                }
                html.append("<h3 style=\"color: #4ade80; margin: 20px 0 15px;\">MongoDB Native Explain Plan</h3>\n");
                if (mongoExplainContent.isEmpty()) {
                    html.append("<p style=\"color: #888;\">No MongoDB explain plan captured for this category.</p>\n");
                } else {
                    html.append("<div class=\"sql-box\" style=\"max-height: 600px;\"><pre>").append(escapeHtml(mongoExplainContent.toString())).append("</pre></div>\n");
                }
                // Oracle Native API (MongoDB wire protocol without $sql)
                if (!oracleNativePipelineContent.isEmpty()) {
                    html.append("<h3 style=\"color: #a855f7; margin: 20px 0 15px;\">Oracle Native MongoDB API Pipeline</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(oracleNativePipelineContent.toString())).append("</pre></div>\n");
                }
                if (!oracleNativeExplainContent.isEmpty()) {
                    html.append("<h3 style=\"color: #a855f7; margin: 20px 0 15px;\">Oracle Native MongoDB API Explain Plan</h3>\n");
                    html.append("<div class=\"sql-box\" style=\"max-height: 600px;\"><pre>").append(escapeHtml(oracleNativeExplainContent.toString())).append("</pre></div>\n");
                }
                html.append("</div>\n");

                // Tab 3: Oracle SQL
                html.append("<div id=\"sql-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<h3 style=\"color: #3b82f6; margin-bottom: 15px;\">JDBC JSON SQL</h3>\n");
                html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(jdbcSqlContent.toString())).append("</pre></div>\n");
                if (!relationalSqlContent.isEmpty()) {
                    html.append("<h3 style=\"color: #ec4899; margin: 20px 0 15px;\">JDBC Relational SQL</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(relationalSqlContent.toString())).append("</pre></div>\n");
                }
                if (!hybridSqlContent.isEmpty()) {
                    html.append("<h3 style=\"color: #14b8a6; margin: 20px 0 15px;\">JDBC Hybrid SQL (Virtual Columns)</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(hybridSqlContent.toString())).append("</pre></div>\n");
                }
                if (!apiSqlContent.isEmpty()) {
                    html.append("<h3 style=\"color: #f59e0b; margin: 20px 0 15px;\">MongoDB API SQL ($sql operator)</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(apiSqlContent.toString())).append("</pre></div>\n");
                }
                html.append("</div>\n");

                // Tab 4: Oracle Explain Plan
                html.append("<div id=\"plan-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<h3 style=\"color: #3b82f6; margin-bottom: 15px;\">Oracle JSON Execution Plans</h3>\n");
                if (planContent.isEmpty()) {
                    html.append("<p style=\"color: #888;\">No JSON execution plans captured for this category.</p>\n");
                } else {
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(planContent.toString())).append("</pre></div>\n");
                }
                if (!relationalPlanContent.isEmpty()) {
                    html.append("<h3 style=\"color: #ec4899; margin: 20px 0 15px;\">Oracle Relational Execution Plans</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(relationalPlanContent.toString())).append("</pre></div>\n");
                }
                if (!hybridPlanContent.isEmpty()) {
                    html.append("<h3 style=\"color: #14b8a6; margin: 20px 0 15px;\">Oracle Hybrid Execution Plans (Virtual Columns)</h3>\n");
                    html.append("<div class=\"sql-box\"><pre>").append(escapeHtml(hybridPlanContent.toString())).append("</pre></div>\n");
                }
                html.append("</div>\n");

                // Tab 5: SQL Monitor (Active HTML) - with protocol subtabs, each containing test sub-subtabs
                html.append("<div id=\"monitor-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<h3 style=\"color: #3b82f6; margin-bottom: 15px;\">Oracle SQL Monitor (Active Reports)</h3>\n");

                // Collect tests with SQL Monitor data for each protocol
                List<String[]> jdbcJsonMonitors = new ArrayList<>();
                List<String[]> oracleNativeMonitors = new ArrayList<>();
                List<String[]> apiSqlMonitors = new ArrayList<>();
                List<String[]> relationalMonitors = new ArrayList<>();
                List<String[]> hybridMonitors = new ArrayList<>();

                for (Map.Entry<String, long[]> entry : categoryEntry.getValue()) {
                    String testId = entry.getKey();
                    SqlDetails details = sqlDetailsMap.get(testId);
                    if (details != null) {
                        TestResult result = results.get(testId);
                        String testName = result != null ? result.description : testId;
                        String shortName = testId.split("_")[0]; // e.g., "A1" from "A1_simple_1K"

                        // JDBC JSON SQL Monitor
                        if (!details.sqlMonitorJdbcJson().isEmpty() && !details.sqlMonitorJdbcJson().contains("not available")) {
                            jdbcJsonMonitors.add(new String[]{testId, shortName, testName, details.sqlMonitorJdbcJson()});
                        }
                        // Oracle Native MongoDB API SQL Monitor
                        if (!details.sqlMonitorOracleNative().isEmpty() && !details.sqlMonitorOracleNative().contains("not available")) {
                            oracleNativeMonitors.add(new String[]{testId, shortName, testName, details.sqlMonitorOracleNative()});
                        }
                        // Oracle API $sql SQL Monitor
                        if (!details.sqlMonitorApiSql().isEmpty() && !details.sqlMonitorApiSql().contains("not available")) {
                            apiSqlMonitors.add(new String[]{testId, shortName, testName, details.sqlMonitorApiSql()});
                        }
                        // JDBC Relational SQL Monitor
                        if (!details.sqlMonitorRelational().isEmpty() && !details.sqlMonitorRelational().contains("not available")) {
                            relationalMonitors.add(new String[]{testId, shortName, testName, details.sqlMonitorRelational()});
                        }
                        // JDBC Hybrid SQL Monitor
                        if (!details.sqlMonitorHybrid().isEmpty() && !details.sqlMonitorHybrid().contains("not available")) {
                            hybridMonitors.add(new String[]{testId, shortName, testName, details.sqlMonitorHybrid()});
                        }
                    }
                }

                boolean hasAnyMonitor = !jdbcJsonMonitors.isEmpty() || !oracleNativeMonitors.isEmpty() ||
                                        !apiSqlMonitors.isEmpty() || !relationalMonitors.isEmpty() || !hybridMonitors.isEmpty();

                if (!hasAnyMonitor) {
                    html.append("<p style=\"color: #888;\">SQL Monitor not available. Requires Oracle Enterprise Edition with Tuning Pack.</p>\n");
                } else {
                    // Protocol-level subtabs
                    html.append("<div class=\"subtabs protocol-tabs\">\n");
                    boolean firstProtocol = true;
                    if (!jdbcJsonMonitors.isEmpty()) {
                        html.append("<button class=\"subtab-btn protocol-btn").append(firstProtocol ? " active" : "")
                            .append("\" onclick=\"openProtocolTab(event, 'monitor-jdbc-").append(sectionId)
                            .append("')\" style=\"background: rgba(34, 197, 94, 0.2); border-color: rgba(34, 197, 94, 0.5);\">JDBC JSON (")
                            .append(jdbcJsonMonitors.size()).append(")</button>\n");
                        firstProtocol = false;
                    }
                    if (!oracleNativeMonitors.isEmpty()) {
                        html.append("<button class=\"subtab-btn protocol-btn").append(firstProtocol ? " active" : "")
                            .append("\" onclick=\"openProtocolTab(event, 'monitor-native-").append(sectionId)
                            .append("')\" style=\"background: rgba(168, 85, 247, 0.2); border-color: rgba(168, 85, 247, 0.5);\">Oracle Native (")
                            .append(oracleNativeMonitors.size()).append(")</button>\n");
                        firstProtocol = false;
                    }
                    if (!apiSqlMonitors.isEmpty()) {
                        html.append("<button class=\"subtab-btn protocol-btn").append(firstProtocol ? " active" : "")
                            .append("\" onclick=\"openProtocolTab(event, 'monitor-api-").append(sectionId)
                            .append("')\" style=\"background: rgba(251, 191, 36, 0.2); border-color: rgba(251, 191, 36, 0.5);\">Oracle API $sql (")
                            .append(apiSqlMonitors.size()).append(")</button>\n");
                        firstProtocol = false;
                    }
                    if (!relationalMonitors.isEmpty()) {
                        html.append("<button class=\"subtab-btn protocol-btn").append(firstProtocol ? " active" : "")
                            .append("\" onclick=\"openProtocolTab(event, 'monitor-rel-").append(sectionId)
                            .append("')\" style=\"background: rgba(236, 72, 153, 0.2); border-color: rgba(236, 72, 153, 0.5);\">JDBC Relational (")
                            .append(relationalMonitors.size()).append(")</button>\n");
                        firstProtocol = false;
                    }
                    if (!hybridMonitors.isEmpty()) {
                        html.append("<button class=\"subtab-btn protocol-btn").append(firstProtocol ? " active" : "")
                            .append("\" onclick=\"openProtocolTab(event, 'monitor-hybrid-").append(sectionId)
                            .append("')\" style=\"background: rgba(20, 184, 166, 0.2); border-color: rgba(20, 184, 166, 0.5);\">JDBC Hybrid (")
                            .append(hybridMonitors.size()).append(")</button>\n");
                    }
                    html.append("</div>\n");

                    // Protocol content containers with test-level subtabs
                    firstProtocol = true;

                    // JDBC JSON protocol content
                    if (!jdbcJsonMonitors.isEmpty()) {
                        html.append("<div id=\"monitor-jdbc-").append(sectionId)
                            .append("\" class=\"protocol-content").append(firstProtocol ? " active" : "").append("\">\n");
                        generateTestSubtabs(html, jdbcJsonMonitors, "jdbc", sectionId, "#22c55e");
                        html.append("</div>\n");
                        firstProtocol = false;
                    }

                    // Oracle Native MongoDB API protocol content
                    if (!oracleNativeMonitors.isEmpty()) {
                        html.append("<div id=\"monitor-native-").append(sectionId)
                            .append("\" class=\"protocol-content").append(firstProtocol ? " active" : "").append("\">\n");
                        generateTestSubtabs(html, oracleNativeMonitors, "native", sectionId, "#a855f7");
                        html.append("</div>\n");
                        firstProtocol = false;
                    }

                    // Oracle API $sql protocol content
                    if (!apiSqlMonitors.isEmpty()) {
                        html.append("<div id=\"monitor-api-").append(sectionId)
                            .append("\" class=\"protocol-content").append(firstProtocol ? " active" : "").append("\">\n");
                        generateTestSubtabs(html, apiSqlMonitors, "api", sectionId, "#fbbf24");
                        html.append("</div>\n");
                        firstProtocol = false;
                    }

                    // JDBC Relational protocol content
                    if (!relationalMonitors.isEmpty()) {
                        html.append("<div id=\"monitor-rel-").append(sectionId)
                            .append("\" class=\"protocol-content").append(firstProtocol ? " active" : "").append("\">\n");
                        generateTestSubtabs(html, relationalMonitors, "rel", sectionId, "#ec4899");
                        html.append("</div>\n");
                        firstProtocol = false;
                    }

                    // JDBC Hybrid protocol content
                    if (!hybridMonitors.isEmpty()) {
                        html.append("<div id=\"monitor-hybrid-").append(sectionId)
                            .append("\" class=\"protocol-content").append(firstProtocol ? " active" : "").append("\">\n");
                        generateTestSubtabs(html, hybridMonitors, "hybrid", sectionId, "#14b8a6");
                        html.append("</div>\n");
                    }
                }
                html.append("</div>\n");

                // Tab 6: AWR Report - with subtabs for each AWR snapshot in this category
                html.append("<div id=\"awr-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<h3 style=\"color: #f97316; margin-bottom: 15px;\">Oracle AWR Reports</h3>\n");

                // Collect AWR report file paths for tests in this category
                List<String[]> testsWithAwr = new ArrayList<>();
                for (Map.Entry<String, long[]> entry : categoryEntry.getValue()) {
                    String testId = entry.getKey();
                    // AWR files are stored in build/reports/awr/ with naming awr_<testId>.html
                    String awrFileName = AWR_REPORT_DIR + "/awr_" + testId.replaceAll("[^a-zA-Z0-9]", "_") + ".html";
                    if (Files.exists(Path.of(awrFileName))) {
                        TestResult result = results.get(testId);
                        String testName = result != null ? result.description : testId;
                        String shortName = testId.split("_")[0];
                        testsWithAwr.add(new String[]{testId, shortName, testName, awrFileName});
                    }
                }

                if (testsWithAwr.isEmpty()) {
                    html.append("<p style=\"color: #888;\">AWR reports not available. AWR snapshots may not have been captured for this category.</p>\n");
                } else {
                    // Subtabs for each AWR report
                    html.append("<div class=\"subtabs\">\n");
                    boolean first = true;
                    for (String[] test : testsWithAwr) {
                        html.append("<button class=\"subtab-btn").append(first ? " active" : "").append("\" onclick=\"openSubTab(event, 'awr-")
                            .append(test[0].replaceAll("[^a-zA-Z0-9]", "_")).append("-").append(sectionId).append("')\">").append(test[1]).append("</button>\n");
                        first = false;
                    }
                    html.append("</div>\n");

                    // Subtab content for each AWR report - using file paths instead of embedded base64
                    first = true;
                    for (String[] test : testsWithAwr) {
                        html.append("<div id=\"awr-").append(test[0].replaceAll("[^a-zA-Z0-9]", "_")).append("-").append(sectionId)
                            .append("\" class=\"subtab-content").append(first ? " active" : "").append("\">\n");
                        html.append("<p style=\"color: #888; margin-bottom: 10px;\">").append(test[2])
                            .append(" <a href=\"../").append(test[3]).append("\" target=\"_blank\" style=\"color: #f97316; margin-left: 10px;\">[Open in new tab]</a></p>\n");
                        // Use iframe with file path - reduces main report size significantly
                        html.append("<iframe src=\"../").append(test[3])
                            .append("\" style=\"width: 100%; height: 700px; border: 1px solid rgba(255,255,255,0.1); border-radius: 8px;\"></iframe>\n");
                        html.append("</div>\n");
                        first = false;
                    }
                }
                html.append("</div>\n");

                html.append("</div>\n"); // Close chart-section
            }

            // Generate chart data and JavaScript
            html.append(generateChartScript());

            html.append("""
                <script>
                function openTab(evt, tabId) {
                    const container = evt.target.closest('.chart-section');
                    container.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                    container.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
                    container.querySelector('#' + tabId).classList.add('active');
                    evt.target.classList.add('active');
                }
                function openSubTab(evt, subTabId) {
                    const tabContent = evt.target.closest('.tab-content');
                    tabContent.querySelectorAll('.subtab-content').forEach(c => c.classList.remove('active'));
                    tabContent.querySelectorAll('.subtab-btn').forEach(b => b.classList.remove('active'));
                    tabContent.querySelector('#' + subTabId).classList.add('active');
                    evt.target.classList.add('active');
                }

                function openProtocolTab(evt, protocolId) {
                    const tabContent = evt.target.closest('.tab-content');
                    tabContent.querySelectorAll('.protocol-content').forEach(c => c.classList.remove('active'));
                    tabContent.querySelectorAll('.protocol-btn').forEach(b => b.classList.remove('active'));
                    const protocolContent = tabContent.querySelector('#' + protocolId);
                    if (protocolContent) {
                        protocolContent.classList.add('active');
                    }
                    evt.target.classList.add('active');
                }

                function openTestTab(evt, testId) {
                    const protocolContent = evt.target.closest('.protocol-content');
                    protocolContent.querySelectorAll('.test-content').forEach(c => c.classList.remove('active'));
                    protocolContent.querySelectorAll('.test-btn').forEach(b => b.classList.remove('active'));
                    const testContent = protocolContent.querySelector('#' + testId);
                    if (testContent) {
                        testContent.classList.add('active');
                    }
                    evt.target.classList.add('active');
                }
                </script>
                <div class="footer">
                    Generated by DocBench - MongoDB vs Oracle Quad Comparison Benchmark Suite<br>
                    MongoDB $lookup | Oracle $sql | JDBC JSON | JDBC Relational
                </div>
                </div>
                </body>
                </html>
                """);

            String reportFilename = noIndexMode ? "triple_comparison_no_index_report.html" : "triple_comparison_report.html";
            Path reportPath = reportDir.resolve(reportFilename);
            Files.writeString(reportPath, html.toString());
            System.out.println("\nTriple comparison report generated: " + reportPath.toAbsolutePath());

        } catch (IOException e) {
            System.out.println("Failed to generate HTML report: " + e.getMessage());
        }
    }

    private static String generateChartScript() {
        StringBuilder script = new StringBuilder();
        script.append("""
            <script>
            Chart.defaults.color = '#aaa';
            Chart.defaults.borderColor = 'rgba(255,255,255,0.1)';

            const chartOptions = {
                responsive: true,
                plugins: {
                    legend: { labels: { color: '#eee', font: { size: 13 }, padding: 20 } },
                    tooltip: {
                        callbacks: {
                            label: function(context) {
                                if (context.parsed.y === null) return context.dataset.label + ': FAILED';
                                return context.dataset.label + ': ' + context.parsed.y.toFixed(2) + ' ms';
                            }
                        }
                    }
                },
                scales: {
                    x: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)' } },
                    y: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)' }, title: { display: true, text: 'Time (ms)', color: '#888' } }
                }
            };

            const mongoStyle = { borderColor: '#4ade80', backgroundColor: 'rgba(74, 222, 128, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };
            const oracleNativeStyle = { borderColor: '#a855f7', backgroundColor: 'rgba(168, 85, 247, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };
            const oracleApiStyle = { borderColor: '#f59e0b', backgroundColor: 'rgba(245, 158, 11, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };
            const oracleJdbcStyle = { borderColor: '#3b82f6', backgroundColor: 'rgba(59, 130, 246, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };
            const oracleRelStyle = { borderColor: '#ec4899', backgroundColor: 'rgba(236, 72, 153, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };
            const oracleHybridStyle = { borderColor: '#14b8a6', backgroundColor: 'rgba(20, 184, 166, 0.15)', borderWidth: 3, tension: 0.3, fill: true, pointRadius: 6, pointHoverRadius: 8 };

            """);

        // Combine tripleResults, quadResults, quintResults, and sextetResults for chart generation
        // Format: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos, oracleHybridNanos]
        Map<String, long[]> chartCombinedResults = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : tripleResults.entrySet()) {
            String testId = entry.getKey();
            long[] triple = entry.getValue();
            long[] sextet = sextetResults.get(testId);
            long[] quint = quintResults.get(testId);
            long[] quad = quadResults.get(testId);
            if (sextet != null) {
                // Convert sextet to 6-value format: [mongo, -1, api, jdbc, rel, hybrid]
                chartCombinedResults.put(testId, new long[]{sextet[0], -1, sextet[1], sextet[2], sextet[3], sextet[4]});
            } else if (quint != null) {
                chartCombinedResults.put(testId, new long[]{quint[0], quint[1], quint[2], quint[3], quint[4], -1});
            } else if (quad != null) {
                // Convert quad to 6-value format: insert -1 for oracleNativeApi at position 1, -1 for hybrid
                chartCombinedResults.put(testId, new long[]{quad[0], -1, quad[1], quad[2], quad[3], -1});
            } else {
                chartCombinedResults.put(testId, new long[]{triple[0], -1, triple[1], triple[2], -1, -1});
            }
        }
        for (Map.Entry<String, long[]> entry : sextetResults.entrySet()) {
            if (!chartCombinedResults.containsKey(entry.getKey())) {
                long[] sextet = entry.getValue();
                chartCombinedResults.put(entry.getKey(), new long[]{sextet[0], -1, sextet[1], sextet[2], sextet[3], sextet[4]});
            }
        }
        for (Map.Entry<String, long[]> entry : quadResults.entrySet()) {
            if (!chartCombinedResults.containsKey(entry.getKey())) {
                long[] quad = entry.getValue();
                chartCombinedResults.put(entry.getKey(), new long[]{quad[0], -1, quad[1], quad[2], quad[3], -1});
            }
        }
        for (Map.Entry<String, long[]> entry : quintResults.entrySet()) {
            if (!chartCombinedResults.containsKey(entry.getKey())) {
                long[] quint = entry.getValue();
                chartCombinedResults.put(entry.getKey(), new long[]{quint[0], quint[1], quint[2], quint[3], quint[4], -1});
            }
        }

        // Group data by category
        Map<String, List<Map.Entry<String, long[]>>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : chartCombinedResults.entrySet()) {
            String testId = entry.getKey();
            TestResult result = results.get(testId);
            if (result != null) {
                grouped.computeIfAbsent(result.category, k -> new ArrayList<>()).add(entry);
            }
        }

        // Generate chart for each category
        for (Map.Entry<String, List<Map.Entry<String, long[]>>> categoryEntry : grouped.entrySet()) {
            String category = categoryEntry.getKey();
            List<Map.Entry<String, long[]>> entries = categoryEntry.getValue();

            // Build labels and data arrays
            // Format: [mongoNanos, oracleNativeApiNanos, oracleApiSqlNanos, oracleJdbcNanos, oracleRelNanos]
            StringBuilder labels = new StringBuilder("[");
            StringBuilder mongoData = new StringBuilder("[");
            StringBuilder nativeData = new StringBuilder("[");
            StringBuilder apiData = new StringBuilder("[");
            StringBuilder jdbcData = new StringBuilder("[");
            StringBuilder relData = new StringBuilder("[");
            StringBuilder hybridData = new StringBuilder("[");

            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, long[]> entry = entries.get(i);
                String testId = entry.getKey();
                long[] times = entry.getValue();
                TestResult result = results.get(testId);

                String label = result != null ? result.description : testId;
                // Shorten labels for chart
                label = label.replace("Simple FK join - ", "")
                             .replace(" customers", "")
                             .replace(" join ratio", "")
                             .replace("Large join - ", "")
                             .replace("Doc size ~", "")
                             .replace("Memory ", "")
                             .replace(" (disk)", "")
                             .replace("Sort ", "")
                             .replace(" docs", "")
                             .replace("2-stage: $lookup -> $sort", "2-stage")
                             .replace("3-stage: $lookup -> $unwind -> $group", "3-stage")
                             .replace("4-stage: $lookup -> $unwind -> $group -> $sort", "4-stage")
                             .replace("Chained: $lookup -> $lookup", "Chained")
                             .replace("Relational vs JSON - ", "")
                             .replace("Relational PARALLEL(2) - ", "Parallel ")
                             .replace("Hybrid schema - ", "Hybrid ")
                             .replace("Hybrid selective - ", "Selective ");

                labels.append("'").append(label).append("'");
                mongoData.append(times[0] >= 0 ? String.format("%.2f", times[0] / 1_000_000.0) : "null");
                nativeData.append(times.length > 1 && times[1] >= 0 ? String.format("%.2f", times[1] / 1_000_000.0) : "null");
                apiData.append(times.length > 2 && times[2] >= 0 ? String.format("%.2f", times[2] / 1_000_000.0) : "null");
                jdbcData.append(times.length > 3 && times[3] >= 0 ? String.format("%.2f", times[3] / 1_000_000.0) : "null");
                relData.append(times.length > 4 && times[4] >= 0 ? String.format("%.2f", times[4] / 1_000_000.0) : "null");
                hybridData.append(times.length > 5 && times[5] >= 0 ? String.format("%.2f", times[5] / 1_000_000.0) : "null");

                if (i < entries.size() - 1) {
                    labels.append(", ");
                    mongoData.append(", ");
                    nativeData.append(", ");
                    apiData.append(", ");
                    jdbcData.append(", ");
                    relData.append(", ");
                    hybridData.append(", ");
                }
            }
            labels.append("]");
            mongoData.append("]");
            nativeData.append("]");
            apiData.append("]");
            jdbcData.append("]");
            relData.append("]");
            hybridData.append("]");

            // Use bar chart for pipeline, relational, and hybrid categories
            String chartType = category.equals("pipeline") || category.equals("relational") || category.equals("hybrid") ? "bar" : "line";
            String styleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(74, 222, 128, 0.7)', borderColor: '#4ade80', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "mongoStyle";
            String nativeStyleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(168, 85, 247, 0.7)', borderColor: '#a855f7', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "oracleNativeStyle";
            String apiStyleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(245, 158, 11, 0.7)', borderColor: '#f59e0b', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "oracleApiStyle";
            String jdbcStyleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(59, 130, 246, 0.7)', borderColor: '#3b82f6', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "oracleJdbcStyle";
            String relStyleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(236, 72, 153, 0.7)', borderColor: '#ec4899', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "oracleRelStyle";
            String hybridStyleType = chartType.equals("bar") ?
                "{ backgroundColor: 'rgba(20, 184, 166, 0.7)', borderColor: '#14b8a6', borderWidth: 2, barPercentage: 0.8, categoryPercentage: 0.6 }" : "oracleHybridStyle";

            // Bar charts need explicit options with x-axis offset to prevent bar truncation
            String chartOptionsStr = chartType.equals("bar")
                ? """
                {
                    responsive: true,
                    plugins: {
                        legend: { labels: { color: '#eee', font: { size: 13 }, padding: 20 } },
                        tooltip: { callbacks: { label: function(context) { if (context.parsed.y === null) return context.dataset.label + ': N/A'; return context.dataset.label + ': ' + context.parsed.y.toFixed(2) + ' ms'; } } }
                    },
                    layout: { padding: { left: 40, right: 40 } },
                    scales: {
                        x: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)', offset: true }, offset: true },
                        y: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)' }, title: { display: true, text: 'Time (ms)', color: '#888' } }
                    }
                }"""
                : "chartOptions";

            script.append(String.format("""
            if (document.getElementById('chart-%s')) {
                new Chart(document.getElementById('chart-%s'), {
                    type: '%s',
                    data: {
                        labels: %s,
                        datasets: [
                            { label: 'MongoDB ($lookup)', data: %s, ...%s },
                            { label: 'Oracle Native', data: %s, ...%s },
                            { label: 'Oracle $sql', data: %s, ...%s },
                            { label: 'JDBC JSON', data: %s, ...%s },
                            { label: 'JDBC Relational', data: %s, ...%s },
                            { label: 'JDBC Hybrid', data: %s, ...%s }
                        ]
                    },
                    options: %s
                });
            }
            """, category, category, chartType, labels, mongoData, styleType, nativeData, nativeStyleType, apiData, apiStyleType, jdbcData, jdbcStyleType, relData, relStyleType, hybridData, hybridStyleType, chartOptionsStr));
        }

        script.append("</script>\n");
        return script.toString();
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

    /**
     * Generates test-level subtabs within a protocol section for SQL Monitor reports.
     * @param html The StringBuilder to append HTML to
     * @param tests List of test data: [testId, shortName, testName, filePath]
     * @param protocolPrefix Prefix for tab IDs (jdbc, api, rel)
     * @param sectionId The category section ID
     * @param accentColor The accent color for this protocol
     */
    private static void generateTestSubtabs(StringBuilder html, List<String[]> tests,
                                            String protocolPrefix, String sectionId, String accentColor) {
        if (tests.isEmpty()) {
            html.append("<p style=\"color: #888;\">No SQL Monitor data available for this protocol.</p>\n");
            return;
        }

        // Test-level subtabs
        html.append("<div class=\"subtabs test-tabs\" style=\"margin-top: 10px;\">\n");
        boolean first = true;
        for (String[] test : tests) {
            html.append("<button class=\"subtab-btn test-btn").append(first ? " active" : "")
                .append("\" onclick=\"openTestTab(event, 'monitor-").append(protocolPrefix).append("-")
                .append(test[0]).append("-").append(sectionId).append("')\" style=\"border-left: 3px solid ")
                .append(accentColor).append(";\">").append(test[1]).append("</button>\n");
            first = false;
        }
        html.append("</div>\n");

        // Test content - now using iframe with file path instead of embedded base64
        first = true;
        for (String[] test : tests) {
            html.append("<div id=\"monitor-").append(protocolPrefix).append("-").append(test[0]).append("-").append(sectionId)
                .append("\" class=\"test-content").append(first ? " active" : "").append("\">\n");
            html.append("<p style=\"color: #888; margin-bottom: 10px;\">").append(test[2])
                .append(" <a href=\"").append(test[3]).append("\" target=\"_blank\" style=\"color: #3b82f6; margin-left: 10px;\">[Open in new tab]</a></p>\n");
            // Use iframe with file path - reduces main report size significantly
            html.append("<iframe src=\"").append(test[3])
                .append("\" style=\"width: 100%; height: 700px; border: 1px solid rgba(255,255,255,0.1); border-radius: 8px;\"></iframe>\n");
            html.append("</div>\n");
            first = false;
        }
    }
}
