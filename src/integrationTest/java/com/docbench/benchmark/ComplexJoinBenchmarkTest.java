package com.docbench.benchmark;

import com.mongodb.ExplainVerbosity;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complex Join Benchmark Test Suite - Category X (Order 90-119)
 *
 * Tests six subcategories of complex join patterns:
 *   X0 (90-94):  Many-to-Many - Products ↔ Categories via junction table
 *   X1 (95-99):  Hierarchical 1:N - Customer → Orders → Items → Shipments
 *   X2 (100-104): M:N + Child 1:N - Products → OrderItems → Shipments
 *   X3 (105-109): M:N + Both 1:N - Products(Reviews) ↔ Categories(Rules)
 *   X4 (110-114): Self-Referential - $graphLookup vs CTE vs Property Graph
 *   X5 (115-119): Diamond Patterns - Orders → (Products,Customers) → Regions
 *
 * For X0-X3, X5: MongoDB $lookup vs Oracle JSON JOIN vs Oracle Relational JOIN
 * For X4: MongoDB $graphLookup vs Oracle Recursive CTE vs Oracle SQL Property Graph
 */
@DisplayName("Complex Joins: MongoDB $lookup/$graphLookup vs Oracle JSON/Relational/CTE/Graph")
@Tag("benchmark")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplexJoinBenchmarkTest {

    // =========================================================================
    // Configuration Constants
    // =========================================================================

    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;
    private static final int JDBC_FETCH_SIZE = 1000;

    // No-index mode
    private static final boolean noIndexMode = Boolean.getBoolean("benchmark.noindex");
    private static final boolean skipMongoDB = noIndexMode;

    // =========================================================================
    // Database Connections
    // =========================================================================

    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> categoriesCollection;
    private static MongoCollection<Document> productsCollection;
    private static MongoCollection<Document> productCategoriesCollection;
    private static MongoCollection<Document> customersCollection;
    private static MongoCollection<Document> ordersCollection;
    private static MongoCollection<Document> orderItemsCollection;
    private static MongoCollection<Document> shipmentsCollection;
    private static MongoCollection<Document> suppliersCollection;
    private static MongoCollection<Document> regionsCollection;
    private static MongoCollection<Document> reviewsCollection;
    private static MongoCollection<Document> categoryRulesCollection;

    private static Connection oracleJdbcConnection;
    private static String oracleUrl;
    private static String oracleUsername;

    // Property graph support detection
    private static boolean propertyGraphSupported = false;

    // =========================================================================
    // Table / Collection Names
    // =========================================================================

    // X4: Self-referential (existing)
    private static final String CATEGORIES_COLLECTION = "benchmark_categories";
    private static final String CATEGORIES_REL_TABLE = "benchmark_categories_rel";
    private static final String CATEGORY_EDGES_TABLE = "benchmark_category_edges";
    private static final String CATEGORY_GRAPH_NAME = "category_graph";

    // E-commerce entities (X0-X3, X5)
    private static final String PRODUCTS = "benchmark_products";
    private static final String PRODUCTS_REL = "benchmark_products_rel";
    private static final String PRODUCT_CATEGORIES = "benchmark_product_categories";
    private static final String PRODUCT_CATEGORIES_REL = "benchmark_product_categories_rel";
    private static final String CUSTOMERS = "benchmark_customers";
    private static final String CUSTOMERS_REL = "benchmark_customers_rel";
    private static final String ORDERS = "benchmark_orders";
    private static final String ORDERS_REL = "benchmark_orders_rel";
    private static final String ORDER_ITEMS = "benchmark_order_items";
    private static final String ORDER_ITEMS_REL = "benchmark_order_items_rel";
    private static final String SHIPMENTS = "benchmark_shipments";
    private static final String SHIPMENTS_REL = "benchmark_shipments_rel";
    private static final String SUPPLIERS = "benchmark_suppliers";
    private static final String SUPPLIERS_REL = "benchmark_suppliers_rel";
    private static final String REGIONS = "benchmark_regions";
    private static final String REGIONS_REL = "benchmark_regions_rel";
    private static final String REVIEWS = "benchmark_reviews";
    private static final String REVIEWS_REL = "benchmark_reviews_rel";
    private static final String CATEGORY_RULES = "benchmark_category_rules";
    private static final String CATEGORY_RULES_REL = "benchmark_category_rules_rel";

    // All JSON tables for cleanup
    private static final String[] ALL_JSON_TABLES = {
            PRODUCTS, PRODUCT_CATEGORIES, CATEGORIES_COLLECTION, CUSTOMERS, ORDERS,
            ORDER_ITEMS, SHIPMENTS, SUPPLIERS, REGIONS, REVIEWS, CATEGORY_RULES
    };
    // All relational tables for cleanup (order matters for FK constraints)
    private static final String[] ALL_REL_TABLES = {
            CATEGORY_RULES_REL, REVIEWS_REL, SHIPMENTS_REL, ORDER_ITEMS_REL,
            ORDERS_REL, PRODUCT_CATEGORIES_REL, CUSTOMERS_REL, PRODUCTS_REL,
            SUPPLIERS_REL, REGIONS_REL, CATEGORIES_REL_TABLE, CATEGORY_EDGES_TABLE
    };

    // =========================================================================
    // Results Storage
    // =========================================================================

    /**
     * Flexible test result: holds three measurements with configurable labels.
     * For X0-X3,X5: [MongoDB $lookup, Oracle JSON JOIN, Oracle Relational JOIN]
     * For X4:        [MongoDB $graphLookup, Oracle CTE, Oracle Property Graph]
     */
    private record TestResult(
            String testId,
            String description,
            long mongoNanos,
            long oracleANanos,
            long oracleBNanos,
            String category,
            String notes,
            String mongoLabel,
            String oracleALabel,
            String oracleBLabel
    ) {}

    private static final Map<String, TestResult> results = new LinkedHashMap<>();

    /**
     * Diagnostic data captured per test: pipelines, SQL text, explain plans, SQL Monitor file paths.
     */
    private record DiagnosticData(
            String pipelineJson,
            String mongoExplain,
            String oracleASql,
            String oracleBSql,
            String oracleAPlan,
            String oracleBPlan,
            String sqlMonitorAFile,
            String sqlMonitorBFile
    ) {}

    private static final Map<String, DiagnosticData> diagnosticDataMap = new LinkedHashMap<>();

    // AWR fields
    private static boolean awrEnabled = false;
    private static long dbId = 0;
    private static long instanceNumber = 1;
    private static final Map<String, long[]> awrSnapshots = new LinkedHashMap<>();
    private static final Map<String, String> awrReportFiles = new LinkedHashMap<>();

    // Directory constants
    private static final String SQL_MONITOR_DIR = "reports/sql_monitor/complex_join";
    private static final String AWR_REPORT_DIR = "reports/awr/complex_join";

    // Category labels
    private static final String LABEL_LOOKUP = "$lookup";
    private static final String LABEL_GRAPH_LOOKUP = "$graphLookup";
    private static final String LABEL_ORACLE_JSON = "Oracle JSON";
    private static final String LABEL_ORACLE_REL = "Oracle Relational";
    private static final String LABEL_CTE = "Recursive CTE";
    private static final String LABEL_PROP_GRAPH = "Property Graph";

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @BeforeAll
    static void setup() throws SQLException {
        Properties props = loadConfigProperties();

        // MongoDB connection
        String mongoUri = props.getProperty("mongodb.uri");
        String mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);

        WriteConcern durableWriteConcern = WriteConcern.W1.withJournal(true);

        // Drop all existing collections and create fresh
        for (String coll : new String[]{CATEGORIES_COLLECTION, PRODUCTS, PRODUCT_CATEGORIES,
                CUSTOMERS, ORDERS, ORDER_ITEMS, SHIPMENTS, SUPPLIERS, REGIONS, REVIEWS, CATEGORY_RULES}) {
            try { mongoDatabase.getCollection(coll).drop(); } catch (Exception ignored) {}
        }

        categoriesCollection = mongoDatabase.getCollection(CATEGORIES_COLLECTION).withWriteConcern(durableWriteConcern);
        productsCollection = mongoDatabase.getCollection(PRODUCTS).withWriteConcern(durableWriteConcern);
        productCategoriesCollection = mongoDatabase.getCollection(PRODUCT_CATEGORIES).withWriteConcern(durableWriteConcern);
        customersCollection = mongoDatabase.getCollection(CUSTOMERS).withWriteConcern(durableWriteConcern);
        ordersCollection = mongoDatabase.getCollection(ORDERS).withWriteConcern(durableWriteConcern);
        orderItemsCollection = mongoDatabase.getCollection(ORDER_ITEMS).withWriteConcern(durableWriteConcern);
        shipmentsCollection = mongoDatabase.getCollection(SHIPMENTS).withWriteConcern(durableWriteConcern);
        suppliersCollection = mongoDatabase.getCollection(SUPPLIERS).withWriteConcern(durableWriteConcern);
        regionsCollection = mongoDatabase.getCollection(REGIONS).withWriteConcern(durableWriteConcern);
        reviewsCollection = mongoDatabase.getCollection(REVIEWS).withWriteConcern(durableWriteConcern);
        categoryRulesCollection = mongoDatabase.getCollection(CATEGORY_RULES).withWriteConcern(durableWriteConcern);

        // Oracle JDBC connection
        oracleUrl = props.getProperty("oracle.url");
        oracleUsername = props.getProperty("oracle.username");
        String oracleUser = oracleUsername;
        String oraclePass = props.getProperty("oracle.password");

        OracleDataSource ods = new OracleDataSource();
        ods.setURL(oracleUrl);
        ods.setUser(oracleUser);
        ods.setPassword(oraclePass);
        ods.setImplicitCachingEnabled(true);

        oracleJdbcConnection = ods.getConnection();
        oracleJdbcConnection.setAutoCommit(true);

        createOracleTables();
        detectPropertyGraphSupport();
        initializeAwr();

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  COMPLEX JOIN BENCHMARK: Category X (Order 90-119)");
        System.out.println("  " + "-".repeat(84));
        System.out.println("  X0: M:N      | X1: Hierarchical | X2: M:N+Child | X3: M:N+Both");
        System.out.println("  X4: Self-Ref | X5: Diamond");
        System.out.println("  " + "-".repeat(84));
        if (skipMongoDB) {
            System.out.println("  MongoDB: SKIPPED (no-index mode)");
        } else {
            System.out.println("  MongoDB: $lookup / $graphLookup aggregation");
        }
        System.out.println("  Oracle:  JSON JOIN / Relational JOIN / Recursive CTE");
        System.out.println("  Oracle:  SQL Property Graph (SQL/PGQ) - " +
                (propertyGraphSupported ? "SUPPORTED" : "NOT AVAILABLE"));
        System.out.println("=".repeat(90));
    }

    @AfterAll
    static void teardown() {
        generateAwrReports();
        printFinalReport();
        generateHtmlReport();

        // Cleanup MongoDB
        if (mongoClient != null) {
            for (String coll : new String[]{CATEGORIES_COLLECTION, PRODUCTS, PRODUCT_CATEGORIES,
                    CUSTOMERS, ORDERS, ORDER_ITEMS, SHIPMENTS, SUPPLIERS, REGIONS, REVIEWS, CATEGORY_RULES}) {
                try { mongoDatabase.getCollection(coll).drop(); } catch (Exception ignored) {}
            }
            try { mongoClient.close(); } catch (Exception ignored) {}
        }

        // Cleanup Oracle tables
        if (oracleJdbcConnection != null) {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                try { stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME); } catch (SQLException ignored) {}
                for (String table : ALL_REL_TABLES) {
                    try { stmt.execute("DROP TABLE " + table + " PURGE"); } catch (SQLException ignored) {}
                }
                for (String table : ALL_JSON_TABLES) {
                    try { stmt.execute("DROP TABLE " + table + " PURGE"); } catch (SQLException ignored) {}
                }
            } catch (SQLException ignored) {}
            try { oracleJdbcConnection.close(); } catch (SQLException ignored) {}
        }
    }

    // =========================================================================
    // Oracle DDL Setup
    // =========================================================================

    private static void createOracleTables() throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Drop property graph first (depends on tables)
            try { stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME); } catch (SQLException ignored) {}

            // Drop all tables (reverse dependency order)
            for (String table : ALL_REL_TABLES) {
                try { stmt.execute("DROP TABLE " + table + " PURGE"); } catch (SQLException ignored) {}
            }
            for (String table : ALL_JSON_TABLES) {
                try { stmt.execute("DROP TABLE " + table + " PURGE"); } catch (SQLException ignored) {}
            }

            // ---- JSON tables (id + data JSON) ----
            for (String table : ALL_JSON_TABLES) {
                stmt.execute("CREATE TABLE " + table + " (id VARCHAR2(100) PRIMARY KEY, data JSON)");
            }

            if (!noIndexMode) {
                // Categories JSON indexes
                stmt.execute("CREATE INDEX idx_cat_json_id ON " + CATEGORIES_COLLECTION + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_cat_json_parent ON " + CATEGORIES_COLLECTION + " (JSON_VALUE(data, '$.parent_id'))");
                // Products JSON indexes
                stmt.execute("CREATE INDEX idx_prod_json_id ON " + PRODUCTS + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_prod_json_supp ON " + PRODUCTS + " (JSON_VALUE(data, '$.supplier_id'))");
                stmt.execute("CREATE INDEX idx_prod_json_price ON " + PRODUCTS + " (JSON_VALUE(data, '$.price' RETURNING NUMBER))");
                // Product-Categories junction JSON indexes
                stmt.execute("CREATE INDEX idx_pc_json_prod ON " + PRODUCT_CATEGORIES + " (JSON_VALUE(data, '$.product_id'))");
                stmt.execute("CREATE INDEX idx_pc_json_cat ON " + PRODUCT_CATEGORIES + " (JSON_VALUE(data, '$.category_id'))");
                // Customers JSON indexes
                stmt.execute("CREATE INDEX idx_cust_json_id ON " + CUSTOMERS + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_cust_json_reg ON " + CUSTOMERS + " (JSON_VALUE(data, '$.region_id'))");
                // Orders JSON indexes
                stmt.execute("CREATE INDEX idx_ord_json_id ON " + ORDERS + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_ord_json_cust ON " + ORDERS + " (JSON_VALUE(data, '$.customer_id'))");
                // Order Items JSON indexes
                stmt.execute("CREATE INDEX idx_oi_json_id ON " + ORDER_ITEMS + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_oi_json_ord ON " + ORDER_ITEMS + " (JSON_VALUE(data, '$.order_id'))");
                stmt.execute("CREATE INDEX idx_oi_json_prod ON " + ORDER_ITEMS + " (JSON_VALUE(data, '$.product_id'))");
                // Shipments JSON indexes
                stmt.execute("CREATE INDEX idx_ship_json_item ON " + SHIPMENTS + " (JSON_VALUE(data, '$.item_id'))");
                // Suppliers JSON indexes
                stmt.execute("CREATE INDEX idx_supp_json_id ON " + SUPPLIERS + " (JSON_VALUE(data, '$._id'))");
                stmt.execute("CREATE INDEX idx_supp_json_reg ON " + SUPPLIERS + " (JSON_VALUE(data, '$.region_id'))");
                // Regions JSON indexes
                stmt.execute("CREATE INDEX idx_reg_json_id ON " + REGIONS + " (JSON_VALUE(data, '$._id'))");
                // Reviews JSON indexes
                stmt.execute("CREATE INDEX idx_rev_json_prod ON " + REVIEWS + " (JSON_VALUE(data, '$.product_id'))");
                // Category Rules JSON indexes
                stmt.execute("CREATE INDEX idx_cr_json_cat ON " + CATEGORY_RULES + " (JSON_VALUE(data, '$.category_id'))");
            }

            // ---- Relational tables ----

            // Categories relational (X4)
            stmt.execute("CREATE TABLE " + CATEGORIES_REL_TABLE + " (" +
                    "category_id VARCHAR2(100) PRIMARY KEY, name VARCHAR2(200), " +
                    "parent_id VARCHAR2(100), lvl NUMBER(2))");

            // Category edges (X4 Property Graph)
            stmt.execute("CREATE TABLE " + CATEGORY_EDGES_TABLE + " (" +
                    "edge_id VARCHAR2(100) PRIMARY KEY, " +
                    "parent_id VARCHAR2(100) NOT NULL, child_id VARCHAR2(100) NOT NULL)");

            // Regions
            stmt.execute("CREATE TABLE " + REGIONS_REL + " (" +
                    "region_id VARCHAR2(100) PRIMARY KEY, name VARCHAR2(100), country VARCHAR2(100))");

            // Suppliers
            stmt.execute("CREATE TABLE " + SUPPLIERS_REL + " (" +
                    "supplier_id VARCHAR2(100) PRIMARY KEY, name VARCHAR2(200), " +
                    "region_id VARCHAR2(100), contact_email VARCHAR2(200))");

            // Products
            stmt.execute("CREATE TABLE " + PRODUCTS_REL + " (" +
                    "product_id VARCHAR2(100) PRIMARY KEY, name VARCHAR2(200), " +
                    "price NUMBER(10,2), supplier_id VARCHAR2(100))");

            // Product-Categories junction
            stmt.execute("CREATE TABLE " + PRODUCT_CATEGORIES_REL + " (" +
                    "product_id VARCHAR2(100) NOT NULL, category_id VARCHAR2(100) NOT NULL, " +
                    "PRIMARY KEY (product_id, category_id))");

            // Customers
            stmt.execute("CREATE TABLE " + CUSTOMERS_REL + " (" +
                    "customer_id VARCHAR2(100) PRIMARY KEY, name VARCHAR2(200), " +
                    "email VARCHAR2(200), region_id VARCHAR2(100))");

            // Orders
            stmt.execute("CREATE TABLE " + ORDERS_REL + " (" +
                    "order_id VARCHAR2(100) PRIMARY KEY, customer_id VARCHAR2(100) NOT NULL, " +
                    "order_date DATE, total NUMBER(10,2))");

            // Order Items
            stmt.execute("CREATE TABLE " + ORDER_ITEMS_REL + " (" +
                    "item_id VARCHAR2(100) PRIMARY KEY, order_id VARCHAR2(100) NOT NULL, " +
                    "product_id VARCHAR2(100) NOT NULL, quantity NUMBER(10), " +
                    "unit_price NUMBER(10,2), line_total NUMBER(10,2))");

            // Shipments
            stmt.execute("CREATE TABLE " + SHIPMENTS_REL + " (" +
                    "shipment_id VARCHAR2(100) PRIMARY KEY, item_id VARCHAR2(100) NOT NULL, " +
                    "ship_date DATE, carrier VARCHAR2(100), tracking_num VARCHAR2(100), " +
                    "status VARCHAR2(20))");

            // Reviews
            stmt.execute("CREATE TABLE " + REVIEWS_REL + " (" +
                    "review_id VARCHAR2(100) PRIMARY KEY, product_id VARCHAR2(100) NOT NULL, " +
                    "rating NUMBER(1), review_text VARCHAR2(4000), review_date DATE)");

            // Category Rules
            stmt.execute("CREATE TABLE " + CATEGORY_RULES_REL + " (" +
                    "rule_id VARCHAR2(100) PRIMARY KEY, category_id VARCHAR2(100) NOT NULL, " +
                    "rule_name VARCHAR2(200), rule_value VARCHAR2(1000))");

            if (!noIndexMode) {
                stmt.execute("CREATE INDEX idx_catr_parent ON " + CATEGORIES_REL_TABLE + "(parent_id)");
                stmt.execute("CREATE INDEX idx_ce_parent ON " + CATEGORY_EDGES_TABLE + "(parent_id)");
                stmt.execute("CREATE INDEX idx_ce_child ON " + CATEGORY_EDGES_TABLE + "(child_id)");
                stmt.execute("CREATE INDEX idx_suppr_region ON " + SUPPLIERS_REL + "(region_id)");
                stmt.execute("CREATE INDEX idx_prodr_supplier ON " + PRODUCTS_REL + "(supplier_id)");
                stmt.execute("CREATE INDEX idx_prodr_price ON " + PRODUCTS_REL + "(price)");
                stmt.execute("CREATE INDEX idx_pcr_product ON " + PRODUCT_CATEGORIES_REL + "(product_id)");
                stmt.execute("CREATE INDEX idx_pcr_category ON " + PRODUCT_CATEGORIES_REL + "(category_id)");
                stmt.execute("CREATE INDEX idx_custr_region ON " + CUSTOMERS_REL + "(region_id)");
                stmt.execute("CREATE INDEX idx_ordr_customer ON " + ORDERS_REL + "(customer_id)");
                stmt.execute("CREATE INDEX idx_oir_order ON " + ORDER_ITEMS_REL + "(order_id)");
                stmt.execute("CREATE INDEX idx_oir_product ON " + ORDER_ITEMS_REL + "(product_id)");
                stmt.execute("CREATE INDEX idx_shipr_item ON " + SHIPMENTS_REL + "(item_id)");
                stmt.execute("CREATE INDEX idx_revr_product ON " + REVIEWS_REL + "(product_id)");
                stmt.execute("CREATE INDEX idx_crr_category ON " + CATEGORY_RULES_REL + "(category_id)");
            }
        }
    }

    /**
     * Detect if Oracle SQL Property Graph (SQL/PGQ) is supported.
     * Creates the property graph definition if supported.
     */
    private static void detectPropertyGraphSupport() {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Insert a minimal test row so the graph has something to reference
            stmt.execute("INSERT INTO " + CATEGORIES_REL_TABLE +
                    " VALUES ('test_root', 'Test', NULL, 0)");
            stmt.execute("INSERT INTO " + CATEGORY_EDGES_TABLE +
                    " VALUES ('test_edge', 'test_root', 'test_root')");

            // Try to create a property graph - this will fail on older Oracle versions
            String graphDdl = "CREATE PROPERTY GRAPH " + CATEGORY_GRAPH_NAME +
                    " VERTEX TABLES (" +
                    "  " + CATEGORIES_REL_TABLE + " AS category " +
                    "    KEY (category_id) " +
                    "    PROPERTIES (category_id, name, parent_id, lvl)" +
                    ") " +
                    "EDGE TABLES (" +
                    "  " + CATEGORY_EDGES_TABLE + " AS child_of " +
                    "    KEY (edge_id) " +
                    "    SOURCE KEY (child_id) REFERENCES category (category_id) " +
                    "    DESTINATION KEY (parent_id) REFERENCES category (category_id) " +
                    "    NO PROPERTIES" +
                    ")";
            stmt.execute(graphDdl);

            // Test a simple GRAPH_TABLE query
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM GRAPH_TABLE (" + CATEGORY_GRAPH_NAME +
                    " MATCH (a IS category) COLUMNS (a.category_id)) WHERE ROWNUM = 1")) {
                rs.next();
            }

            propertyGraphSupported = true;
            System.out.println("  SQL Property Graph: SUPPORTED (Oracle 23ai+)");

            // Clean up test data and drop graph for fresh creation
            stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME);
            stmt.execute("DELETE FROM " + CATEGORY_EDGES_TABLE);
            stmt.execute("DELETE FROM " + CATEGORIES_REL_TABLE);

        } catch (SQLException e) {
            propertyGraphSupported = false;
            System.out.println("  SQL Property Graph: NOT SUPPORTED (" + e.getMessage() + ")");
            // Clean up test data
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                try { stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME); } catch (SQLException ignored) {}
                stmt.execute("DELETE FROM " + CATEGORY_EDGES_TABLE);
                stmt.execute("DELETE FROM " + CATEGORIES_REL_TABLE);
            } catch (SQLException ignored) {}
        }
    }

    // =========================================================================
    // Generic Measurement Helpers
    // =========================================================================

    /** Measure MongoDB aggregate pipeline (average over MEASUREMENT_ITERATIONS). */
    private long measureMongo(MongoCollection<Document> collection, List<Bson> pipeline) {
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : collection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    /** Measure Oracle SQL query (average over MEASUREMENT_ITERATIONS). */
    private long measureOracle(String sql) {
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setFetchSize(JDBC_FETCH_SIZE);
            long totalNanos = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) {} }
                totalNanos += System.nanoTime() - start;
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle measurement error: " + e.getMessage(), e);
        }
    }

    /** Warmup: run MongoDB pipeline + Oracle SQLs for WARMUP_ITERATIONS. */
    private void warmup(MongoCollection<Document> collection, List<Bson> pipeline, String... sqls) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) {
                for (Document doc : collection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {}
            }
            for (String sql : sqls) {
                try (Statement stmt = oracleJdbcConnection.createStatement()) {
                    stmt.setFetchSize(JDBC_FETCH_SIZE);
                    try (ResultSet rs = stmt.executeQuery(sql)) { while (rs.next()) {} }
                } catch (SQLException e) { throw new RuntimeException("Warmup error", e); }
            }
        }
    }

    /** Store result with category-specific labels. */
    private void storeJoinResult(String testId, String description, String category,
                                  long mongoNanos, long oracleANanos, long oracleBNanos, String notes) {
        results.put(testId, new TestResult(testId, description, mongoNanos, oracleANanos, oracleBNanos,
                category, notes, LABEL_LOOKUP, LABEL_ORACLE_JSON, LABEL_ORACLE_REL));
    }

    /** Print triple result to console. */
    private void printJoinResult(String label, long mongoNanos, long oracleANanos, long oracleBNanos) {
        String mongoStr = mongoNanos >= 0 ? String.format("%,15d", mongoNanos) : "        SKIPPED";
        System.out.printf("  %-25s $lookup: %s ns | JSON: %,15d ns | Rel: %,15d ns%n",
                label, mongoStr, oracleANanos, oracleBNanos);
    }

    // =========================================================================
    // X0: Many-to-Many (M:N) Tests (Order 90-94)
    // Products ↔ Categories via product_categories junction table
    // =========================================================================

    @Test
    @Order(90)
    @DisplayName("X0_M2M_basic_1K: M:N Products ↔ Categories (1K products, 50 categories)")
    void X0_M2M_basic_1K() {
        awrSnapshotBefore("m2m");
        generateM2MData(1000, 50, 3);
        List<Bson> pipeline = buildM2MLookupPipeline();
        String jsonSql = buildM2MJsonSql();
        String relSql = buildM2MRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X0_M2M_basic_1K", "M:N Basic 1K", "m2m",
                mongoNanos, jsonNanos, relNanos, "1K products, 50 categories, ~3 cats/product");
        printJoinResult("X0: M:N 1K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X0_M2M_basic_1K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(91)
    @DisplayName("X0_M2M_basic_10K: M:N Products ↔ Categories (10K products, 100 categories)")
    void X0_M2M_basic_10K() {
        generateM2MData(10_000, 100, 3);
        List<Bson> pipeline = buildM2MLookupPipeline();
        String jsonSql = buildM2MJsonSql();
        String relSql = buildM2MRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X0_M2M_basic_10K", "M:N Basic 10K", "m2m",
                mongoNanos, jsonNanos, relNanos, "10K products, 100 categories, ~3 cats/product");
        printJoinResult("X0: M:N 10K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X0_M2M_basic_10K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(92)
    @DisplayName("X0_M2M_basic_100K: M:N Products ↔ Categories (100K products, 500 categories)")
    void X0_M2M_basic_100K() {
        generateM2MData(100_000, 500, 3);
        List<Bson> pipeline = buildM2MLookupPipeline();
        String jsonSql = buildM2MJsonSql();
        String relSql = buildM2MRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X0_M2M_basic_100K", "M:N Basic 100K", "m2m",
                mongoNanos, jsonNanos, relNanos, "100K products, 500 categories, ~3 cats/product");
        printJoinResult("X0: M:N 100K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X0_M2M_basic_100K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(93)
    @DisplayName("X0_M2M_dense: Dense M:N (10K products, each in 20 categories)")
    void X0_M2M_dense() {
        generateM2MData(10_000, 100, 20);
        List<Bson> pipeline = buildM2MLookupPipeline();
        String jsonSql = buildM2MJsonSql();
        String relSql = buildM2MRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X0_M2M_dense", "M:N Dense (20 cats/prod)", "m2m",
                mongoNanos, jsonNanos, relNanos, "10K products, 100 categories, 20 cats/product");
        printJoinResult("X0: M:N Dense", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X0_M2M_dense", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(94)
    @DisplayName("X0_M2M_sparse: Sparse M:N (10K products, each in 2 categories)")
    void X0_M2M_sparse() {
        generateM2MData(10_000, 100, 2);
        List<Bson> pipeline = buildM2MLookupPipeline();
        String jsonSql = buildM2MJsonSql();
        String relSql = buildM2MRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X0_M2M_sparse", "M:N Sparse (2 cats/prod)", "m2m",
                mongoNanos, jsonNanos, relNanos, "10K products, 100 categories, 2 cats/product");
        printJoinResult("X0: M:N Sparse", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X0_M2M_sparse", productsCollection, pipeline, jsonSql, relSql);
        awrSnapshotAfter("m2m");
    }

    // =========================================================================
    // X1: Hierarchical Multi-Level 1:N Tests (Order 95-99)
    // Customer → Orders → OrderItems → Shipments
    // =========================================================================

    @Test
    @Order(95)
    @DisplayName("X1_hier_2level: Customer → Orders (500 customers, 4 orders/cust)")
    void X1_hier_2level() {
        awrSnapshotBefore("hier");
        generateHierData(500, 4, 0, 0);
        List<Bson> pipeline = buildHierLookupPipeline(2);
        String jsonSql = buildHierJsonSql(2);
        String relSql = buildHierRelSql(2);
        warmup(customersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(customersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X1_hier_2level", "2-level: Cust→Orders", "hier",
                mongoNanos, jsonNanos, relNanos, "500 customers, 4 orders/cust");
        printJoinResult("X1: 2-level", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X1_hier_2level", customersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(96)
    @DisplayName("X1_hier_3level: Customer → Orders → Items (500 customers)")
    void X1_hier_3level() {
        generateHierData(500, 4, 3, 0);
        List<Bson> pipeline = buildHierLookupPipeline(3);
        String jsonSql = buildHierJsonSql(3);
        String relSql = buildHierRelSql(3);
        warmup(customersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(customersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X1_hier_3level", "3-level: Cust→Ord→Items", "hier",
                mongoNanos, jsonNanos, relNanos, "500 customers, 4 orders, 3 items/order");
        printJoinResult("X1: 3-level", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X1_hier_3level", customersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(97)
    @DisplayName("X1_hier_4level: Customer → Orders → Items → Shipments (500 customers)")
    void X1_hier_4level() {
        generateHierData(500, 4, 3, 1);
        List<Bson> pipeline = buildHierLookupPipeline(4);
        String jsonSql = buildHierJsonSql(4);
        String relSql = buildHierRelSql(4);
        warmup(customersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(customersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X1_hier_4level", "4-level: Cust→Ord→Items→Ship", "hier",
                mongoNanos, jsonNanos, relNanos, "500 customers, 4 orders, 3 items, 1 ship/item");
        printJoinResult("X1: 4-level", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X1_hier_4level", customersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(98)
    @DisplayName("X1_hier_wide: Wide 2-level (100 customers, 100 orders/cust)")
    void X1_hier_wide() {
        generateHierData(100, 100, 0, 0);
        List<Bson> pipeline = buildHierLookupPipeline(2);
        String jsonSql = buildHierJsonSql(2);
        String relSql = buildHierRelSql(2);
        warmup(customersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(customersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X1_hier_wide", "Wide: 100 orders/cust", "hier",
                mongoNanos, jsonNanos, relNanos, "100 customers, 100 orders/cust");
        printJoinResult("X1: Wide", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X1_hier_wide", customersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(99)
    @DisplayName("X1_hier_deep: Deep 4-level with large fan-out (200 customers)")
    void X1_hier_deep() {
        generateHierData(200, 10, 5, 2);
        List<Bson> pipeline = buildHierLookupPipeline(4);
        String jsonSql = buildHierJsonSql(4);
        String relSql = buildHierRelSql(4);
        warmup(customersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(customersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X1_hier_deep", "Deep: 200 cust, 10 ord, 5 items, 2 ship", "hier",
                mongoNanos, jsonNanos, relNanos, "200 customers, 10 orders, 5 items, 2 ship/item");
        printJoinResult("X1: Deep", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X1_hier_deep", customersCollection, pipeline, jsonSql, relSql);
        awrSnapshotAfter("hier");
    }

    // =========================================================================
    // X2: M:N with 1:N on Child Side (Order 100-104)
    // Products → OrderItems → Shipments
    // =========================================================================

    @Test
    @Order(100)
    @DisplayName("X2_M2N_child1N_basic: Products → Items → Shipments (1K products)")
    void X2_M2N_child1N_basic() {
        awrSnapshotBefore("m2n_child");
        generateM2NChildData(1000, 5000, 1);
        List<Bson> pipeline = buildM2NChildLookupPipeline();
        String jsonSql = buildM2NChildJsonSql();
        String relSql = buildM2NChildRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X2_M2N_child1N_basic", "M:N+Child Basic", "m2n_child",
                mongoNanos, jsonNanos, relNanos, "1K products, 5K items, 1 ship/item");
        printJoinResult("X2: Basic", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X2_M2N_child1N_basic", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(101)
    @DisplayName("X2_M2N_child1N_1K: 1K products, 10K items, 2 ship/item")
    void X2_M2N_child1N_1K() {
        generateM2NChildData(1000, 10_000, 2);
        List<Bson> pipeline = buildM2NChildLookupPipeline();
        String jsonSql = buildM2NChildJsonSql();
        String relSql = buildM2NChildRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X2_M2N_child1N_1K", "M:N+Child 1K", "m2n_child",
                mongoNanos, jsonNanos, relNanos, "1K products, 10K items, 2 ship/item");
        printJoinResult("X2: 1K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X2_M2N_child1N_1K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(102)
    @DisplayName("X2_M2N_child1N_10K: 10K products, 50K items")
    void X2_M2N_child1N_10K() {
        generateM2NChildData(10_000, 50_000, 1);
        List<Bson> pipeline = buildM2NChildLookupPipeline();
        String jsonSql = buildM2NChildJsonSql();
        String relSql = buildM2NChildRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X2_M2N_child1N_10K", "M:N+Child 10K", "m2n_child",
                mongoNanos, jsonNanos, relNanos, "10K products, 50K items, 1 ship/item");
        printJoinResult("X2: 10K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X2_M2N_child1N_10K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(103)
    @DisplayName("X2_M2N_child1N_highFanout: High shipment fan-out (5K products, 10 ship/item)")
    void X2_M2N_child1N_highFanout() {
        generateM2NChildData(5000, 25_000, 10);
        List<Bson> pipeline = buildM2NChildLookupPipeline();
        String jsonSql = buildM2NChildJsonSql();
        String relSql = buildM2NChildRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X2_M2N_child1N_highFanout", "M:N+Child High Fan-out", "m2n_child",
                mongoNanos, jsonNanos, relNanos, "5K products, 25K items, 10 ship/item");
        printJoinResult("X2: High Fan-out", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X2_M2N_child1N_highFanout", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(104)
    @DisplayName("X2_M2N_child1N_aggregate: Aggregation (5K products, GROUP BY)")
    void X2_M2N_child1N_aggregate() {
        generateM2NChildData(5000, 25_000, 2);
        List<Bson> pipeline = buildM2NChildAggregatePipeline();
        String jsonSql = buildM2NChildAggregateJsonSql();
        String relSql = buildM2NChildAggregateRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X2_M2N_child1N_aggregate", "M:N+Child Aggregate", "m2n_child",
                mongoNanos, jsonNanos, relNanos, "5K products, 25K items, GROUP BY product");
        printJoinResult("X2: Aggregate", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X2_M2N_child1N_aggregate", productsCollection, pipeline, jsonSql, relSql);
        awrSnapshotAfter("m2n_child");
    }

    // =========================================================================
    // X3: M:N with 1:N on Both Sides (Order 105-109)
    // Products(Reviews) ↔ Categories(Rules)
    // =========================================================================

    @Test
    @Order(105)
    @DisplayName("X3_M2N_both1N_basic: Products(Reviews) ↔ Categories(Rules) (1K products)")
    void X3_M2N_both1N_basic() {
        awrSnapshotBefore("m2n_both");
        generateM2NBothData(1000, 50, 3, 5, 3);
        List<Bson> pipeline = buildM2NBothLookupPipeline();
        String jsonSql = buildM2NBothJsonSql();
        String relSql = buildM2NBothRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X3_M2N_both1N_basic", "M:N+Both Basic", "m2n_both",
                mongoNanos, jsonNanos, relNanos, "1K products, 50 cats, 5 reviews, 3 rules");
        printJoinResult("X3: Basic", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X3_M2N_both1N_basic", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(106)
    @DisplayName("X3_M2N_both1N_1K: 1K products, 5 reviews each")
    void X3_M2N_both1N_1K() {
        generateM2NBothData(1000, 50, 3, 5, 3);
        List<Bson> pipeline = buildM2NBothLookupPipeline();
        String jsonSql = buildM2NBothJsonSql();
        String relSql = buildM2NBothRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X3_M2N_both1N_1K", "M:N+Both 1K", "m2n_both",
                mongoNanos, jsonNanos, relNanos, "1K products, 50 cats, 5 reviews/prod");
        printJoinResult("X3: 1K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X3_M2N_both1N_1K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(107)
    @DisplayName("X3_M2N_both1N_10K: 10K products, 500 categories")
    void X3_M2N_both1N_10K() {
        generateM2NBothData(10_000, 500, 3, 5, 3);
        List<Bson> pipeline = buildM2NBothLookupPipeline();
        String jsonSql = buildM2NBothJsonSql();
        String relSql = buildM2NBothRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X3_M2N_both1N_10K", "M:N+Both 10K", "m2n_both",
                mongoNanos, jsonNanos, relNanos, "10K products, 500 categories");
        printJoinResult("X3: 10K", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X3_M2N_both1N_10K", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(108)
    @DisplayName("X3_M2N_both1N_aggregate: Aggregate reviews per category")
    void X3_M2N_both1N_aggregate() {
        generateM2NBothData(5000, 100, 3, 5, 3);
        List<Bson> pipeline = buildM2NBothAggregatePipeline();
        String jsonSql = buildM2NBothAggregateJsonSql();
        String relSql = buildM2NBothAggregateRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X3_M2N_both1N_aggregate", "M:N+Both Aggregate", "m2n_both",
                mongoNanos, jsonNanos, relNanos, "5K products, 100 cats, avg rating per cat");
        printJoinResult("X3: Aggregate", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X3_M2N_both1N_aggregate", productsCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(109)
    @DisplayName("X3_M2N_both1N_filtered: With price filter (5K products, price > 50)")
    void X3_M2N_both1N_filtered() {
        generateM2NBothData(5000, 100, 3, 5, 3);
        List<Bson> pipeline = buildM2NBothFilteredPipeline();
        String jsonSql = buildM2NBothFilteredJsonSql();
        String relSql = buildM2NBothFilteredRelSql();
        warmup(productsCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(productsCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X3_M2N_both1N_filtered", "M:N+Both Filtered", "m2n_both",
                mongoNanos, jsonNanos, relNanos, "5K products, price > 50 filter");
        printJoinResult("X3: Filtered", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X3_M2N_both1N_filtered", productsCollection, pipeline, jsonSql, relSql);
        awrSnapshotAfter("m2n_both");
    }

    // =========================================================================
    // X4: Self-Referential Hierarchy Tests (Order 110-114)
    // =========================================================================

    @Test
    @Order(110)
    @DisplayName("X4_selfref_2level: 2-level category hierarchy (50 categories)")
    void X4_selfref_2level() {
        awrSnapshotBefore("selfref");
        int categoryCount = 50;
        int maxDepth = 2;

        generateHierarchicalCategories(categoryCount, maxDepth);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoGraphLookup(maxDepth);
            runOracleRecursiveCteRelational(maxDepth);
            if (propertyGraphSupported) runOraclePropertyGraph(maxDepth);
        }

        // Measure
        long mongoNanos = skipMongoDB ? -1 : measureMongoGraphLookup(maxDepth);
        long oracleCteNanos = measureOracleRecursiveCteRelational(maxDepth);
        long oracleGraphNanos = propertyGraphSupported ? measureOraclePropertyGraph(maxDepth) : -1;

        storeResult("X4_selfref_2level", "2-level hierarchy (50 categories)", "selfref",
                mongoNanos, oracleCteNanos, oracleGraphNanos,
                categoryCount + " categories, " + maxDepth + " levels");

        printTripleResult("X4: 2-level (50 cats)", mongoNanos, oracleCteNanos, oracleGraphNanos);
        captureDiagnostics("X4_selfref_2level", categoriesCollection, buildGraphLookupPipeline(maxDepth),
                buildRecursiveCteSql(maxDepth), propertyGraphSupported ? buildPropertyGraphDescendantSql(maxDepth) : "");
    }

    @Test
    @Order(111)
    @DisplayName("X4_selfref_3level: 3-level category hierarchy (100 categories)")
    void X4_selfref_3level() {
        int categoryCount = 100;
        int maxDepth = 3;

        generateHierarchicalCategories(categoryCount, maxDepth);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoGraphLookup(maxDepth);
            runOracleRecursiveCteRelational(maxDepth);
            if (propertyGraphSupported) runOraclePropertyGraph(maxDepth);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoGraphLookup(maxDepth);
        long oracleCteNanos = measureOracleRecursiveCteRelational(maxDepth);
        long oracleGraphNanos = propertyGraphSupported ? measureOraclePropertyGraph(maxDepth) : -1;

        storeResult("X4_selfref_3level", "3-level hierarchy (100 categories)", "selfref",
                mongoNanos, oracleCteNanos, oracleGraphNanos,
                categoryCount + " categories, " + maxDepth + " levels");

        printTripleResult("X4: 3-level (100 cats)", mongoNanos, oracleCteNanos, oracleGraphNanos);
        captureDiagnostics("X4_selfref_3level", categoriesCollection, buildGraphLookupPipeline(maxDepth),
                buildRecursiveCteSql(maxDepth), propertyGraphSupported ? buildPropertyGraphDescendantSql(maxDepth) : "");
    }

    @Test
    @Order(112)
    @DisplayName("X4_selfref_5level: 5-level deep category hierarchy (500 categories)")
    void X4_selfref_5level() {
        int categoryCount = 500;
        int maxDepth = 5;

        generateHierarchicalCategories(categoryCount, maxDepth);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoGraphLookup(maxDepth);
            runOracleRecursiveCteRelational(maxDepth);
            if (propertyGraphSupported) runOraclePropertyGraph(maxDepth);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoGraphLookup(maxDepth);
        long oracleCteNanos = measureOracleRecursiveCteRelational(maxDepth);
        long oracleGraphNanos = propertyGraphSupported ? measureOraclePropertyGraph(maxDepth) : -1;

        storeResult("X4_selfref_5level", "5-level hierarchy (500 categories)", "selfref",
                mongoNanos, oracleCteNanos, oracleGraphNanos,
                categoryCount + " categories, " + maxDepth + " levels");

        printTripleResult("X4: 5-level (500 cats)", mongoNanos, oracleCteNanos, oracleGraphNanos);
        captureDiagnostics("X4_selfref_5level", categoriesCollection, buildGraphLookupPipeline(maxDepth),
                buildRecursiveCteSql(maxDepth), propertyGraphSupported ? buildPropertyGraphDescendantSql(maxDepth) : "");
    }

    @Test
    @Order(113)
    @DisplayName("X4_selfref_wide: Wide hierarchy (100 children/node, 2 levels, 100 categories)")
    void X4_selfref_wide() {
        // Wide tree: 1 root with 99 direct children (2 levels, 100 total)
        int categoryCount = 100;
        int maxDepth = 2;

        generateWideHierarchicalCategories(categoryCount, maxDepth);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoGraphLookup(maxDepth);
            runOracleRecursiveCteRelational(maxDepth);
            if (propertyGraphSupported) runOraclePropertyGraph(maxDepth);
        }

        long mongoNanos = skipMongoDB ? -1 : measureMongoGraphLookup(maxDepth);
        long oracleCteNanos = measureOracleRecursiveCteRelational(maxDepth);
        long oracleGraphNanos = propertyGraphSupported ? measureOraclePropertyGraph(maxDepth) : -1;

        storeResult("X4_selfref_wide", "Wide hierarchy (100 children/node)", "selfref",
                mongoNanos, oracleCteNanos, oracleGraphNanos,
                categoryCount + " categories, " + maxDepth + " levels, wide tree");

        printTripleResult("X4: Wide (100 ch/node)", mongoNanos, oracleCteNanos, oracleGraphNanos);
        captureDiagnostics("X4_selfref_wide", categoriesCollection, buildGraphLookupPipeline(maxDepth),
                buildRecursiveCteSql(maxDepth), propertyGraphSupported ? buildPropertyGraphDescendantSql(maxDepth) : "");
    }

    @Test
    @Order(114)
    @DisplayName("X4_selfref_path: Full path reconstruction (500 categories, variable depth)")
    void X4_selfref_path() {
        int categoryCount = 500;
        int maxDepth = 5;

        generateHierarchicalCategories(categoryCount, maxDepth);

        // Warmup - path reconstruction queries
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (!skipMongoDB) runMongoGraphLookupPathReconstruction();
            runOracleRecursiveCtePathReconstruction();
            if (propertyGraphSupported) runOraclePropertyGraphPathReconstruction();
        }

        // Measure path reconstruction
        long mongoNanos = skipMongoDB ? -1 : measureMongoGraphLookupPathReconstruction();
        long oracleCteNanos = measureOracleRecursiveCtePathReconstruction();
        long oracleGraphNanos = propertyGraphSupported ? measureOraclePropertyGraphPathReconstruction() : -1;

        storeResult("X4_selfref_path", "Full path reconstruction (500 categories)", "selfref",
                mongoNanos, oracleCteNanos, oracleGraphNanos,
                categoryCount + " categories, variable depth, path reconstruction");

        printTripleResult("X4: Path reconstruct", mongoNanos, oracleCteNanos, oracleGraphNanos);
        captureDiagnostics("X4_selfref_path", categoriesCollection, buildGraphLookupPathPipeline(),
                buildRecursiveCtePathSql(), propertyGraphSupported ? buildPropertyGraphPathSql() : "");
        awrSnapshotAfter("selfref");
    }

    // =========================================================================
    // X5: Diamond Pattern Tests (Order 115-119)
    // Orders → (Products, Customers) → Regions (7-table join)
    // =========================================================================

    @Test
    @Order(115)
    @DisplayName("X5_diamond_basic: Basic diamond (1K orders, 500 customers, 1K products)")
    void X5_diamond_basic() {
        awrSnapshotBefore("diamond");
        generateDiamondData(1000, 500, 1000, 50, 10);
        List<Bson> pipeline = buildDiamondLookupPipeline();
        String jsonSql = buildDiamondJsonSql();
        String relSql = buildDiamondRelSql();
        warmup(ordersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(ordersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X5_diamond_basic", "Diamond Basic", "diamond",
                mongoNanos, jsonNanos, relNanos, "1K orders, 500 custs, 1K prods, 50 supps, 10 regions");
        printJoinResult("X5: Basic", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X5_diamond_basic", ordersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(116)
    @DisplayName("X5_diamond_converge: Converging paths (2K orders)")
    void X5_diamond_converge() {
        generateDiamondData(2000, 500, 1000, 50, 10);
        List<Bson> pipeline = buildDiamondLookupPipeline();
        String jsonSql = buildDiamondJsonSql();
        String relSql = buildDiamondRelSql();
        warmup(ordersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(ordersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X5_diamond_converge", "Diamond Converge", "diamond",
                mongoNanos, jsonNanos, relNanos, "2K orders, paths converge to regions");
        printJoinResult("X5: Converge", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X5_diamond_converge", ordersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(117)
    @DisplayName("X5_diamond_aggregate: Sales by region aggregation")
    void X5_diamond_aggregate() {
        generateDiamondData(2000, 500, 1000, 50, 10);
        List<Bson> pipeline = buildDiamondAggregatePipeline();
        String jsonSql = buildDiamondAggregateJsonSql();
        String relSql = buildDiamondAggregateRelSql();
        warmup(ordersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(ordersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X5_diamond_aggregate", "Diamond Aggregate", "diamond",
                mongoNanos, jsonNanos, relNanos, "2K orders, sales by region GROUP BY");
        printJoinResult("X5: Aggregate", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X5_diamond_aggregate", ordersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(118)
    @DisplayName("X5_diamond_filtered: Filtered diamond (price > 50 AND region = specific)")
    void X5_diamond_filtered() {
        generateDiamondData(2000, 500, 1000, 50, 10);
        List<Bson> pipeline = buildDiamondFilteredPipeline();
        String jsonSql = buildDiamondFilteredJsonSql();
        String relSql = buildDiamondFilteredRelSql();
        warmup(ordersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(ordersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X5_diamond_filtered", "Diamond Filtered", "diamond",
                mongoNanos, jsonNanos, relNanos, "2K orders, price>50 filter");
        printJoinResult("X5: Filtered", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X5_diamond_filtered", ordersCollection, pipeline, jsonSql, relSql);
    }

    @Test
    @Order(119)
    @DisplayName("X5_star_schema: Star schema fact + 4 dimensions (5K orders)")
    void X5_star_schema() {
        generateDiamondData(5000, 1000, 2000, 100, 20);
        List<Bson> pipeline = buildDiamondLookupPipeline();
        String jsonSql = buildDiamondJsonSql();
        String relSql = buildDiamondRelSql();
        warmup(ordersCollection, pipeline, jsonSql, relSql);
        long mongoNanos = skipMongoDB ? -1 : measureMongo(ordersCollection, pipeline);
        long jsonNanos = measureOracle(jsonSql);
        long relNanos = measureOracle(relSql);
        storeJoinResult("X5_star_schema", "Star Schema (5K orders)", "diamond",
                mongoNanos, jsonNanos, relNanos, "5K orders, 1K custs, 2K prods, 100 supps, 20 regions");
        printJoinResult("X5: Star", mongoNanos, jsonNanos, relNanos);
        captureDiagnostics("X5_star_schema", ordersCollection, pipeline, jsonSql, relSql);
        awrSnapshotAfter("diamond");
    }

    // =========================================================================
    // Data Generation: E-Commerce Entities (X0-X3, X5)
    // =========================================================================

    /** Clear all e-commerce data from MongoDB and Oracle. */
    private void clearEcommerceData() {
        if (!skipMongoDB) {
            for (MongoCollection<Document> coll : new MongoCollection[]{
                    productsCollection, productCategoriesCollection, customersCollection,
                    ordersCollection, orderItemsCollection, shipmentsCollection,
                    suppliersCollection, regionsCollection, reviewsCollection, categoryRulesCollection}) {
                try { coll.drop(); } catch (Exception ignored) {}
            }
            // Recreate with write concern
            WriteConcern wc = WriteConcern.W1.withJournal(true);
            productsCollection = mongoDatabase.getCollection(PRODUCTS).withWriteConcern(wc);
            productCategoriesCollection = mongoDatabase.getCollection(PRODUCT_CATEGORIES).withWriteConcern(wc);
            customersCollection = mongoDatabase.getCollection(CUSTOMERS).withWriteConcern(wc);
            ordersCollection = mongoDatabase.getCollection(ORDERS).withWriteConcern(wc);
            orderItemsCollection = mongoDatabase.getCollection(ORDER_ITEMS).withWriteConcern(wc);
            shipmentsCollection = mongoDatabase.getCollection(SHIPMENTS).withWriteConcern(wc);
            suppliersCollection = mongoDatabase.getCollection(SUPPLIERS).withWriteConcern(wc);
            regionsCollection = mongoDatabase.getCollection(REGIONS).withWriteConcern(wc);
            reviewsCollection = mongoDatabase.getCollection(REVIEWS).withWriteConcern(wc);
            categoryRulesCollection = mongoDatabase.getCollection(CATEGORY_RULES).withWriteConcern(wc);
        }
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            for (String table : new String[]{CATEGORY_RULES, REVIEWS, SHIPMENTS, ORDER_ITEMS,
                    ORDERS, PRODUCT_CATEGORIES, CUSTOMERS, PRODUCTS, SUPPLIERS, REGIONS,
                    CATEGORY_RULES_REL, REVIEWS_REL, SHIPMENTS_REL, ORDER_ITEMS_REL,
                    ORDERS_REL, PRODUCT_CATEGORIES_REL, CUSTOMERS_REL, PRODUCTS_REL,
                    SUPPLIERS_REL, REGIONS_REL}) {
                try { stmt.execute("DELETE FROM " + table); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            System.out.println("  Warning: Could not clear e-commerce data: " + e.getMessage());
        }
    }

    /** Insert documents into Oracle JSON table. */
    private void insertJsonDocs(String tableName, List<Document> docs) {
        String sql = "INSERT INTO " + tableName + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert JSON docs into " + tableName, e);
        }
    }

    /** Gather Oracle stats for given tables. */
    private void gatherStats(String... tables) {
        if (noIndexMode) return;
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            for (String table : tables) {
                stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + table + "', cascade => TRUE); END;");
            }
        } catch (SQLException e) {
            System.out.println("  Warning: Could not gather stats: " + e.getMessage());
        }
    }

    // --- X0: M:N Data Generation ---

    private void generateM2MData(int productCount, int categoryCount, int avgLinksPerProduct) {
        clearEcommerceData();
        clearCategoryData();
        Random rng = new Random(42);

        // Generate categories (flat, no hierarchy needed for M:N)
        List<Document> catDocs = new ArrayList<>();
        List<Object[]> catRelRows = new ArrayList<>();
        for (int i = 0; i < categoryCount; i++) {
            String id = String.format("cat_%05d", i);
            Document doc = new Document("_id", id).append("name", "Category " + i).append("parent_id", null).append("level", 0);
            catDocs.add(doc);
            catRelRows.add(new Object[]{id, "Category " + i, null, 0});
        }
        if (!skipMongoDB) categoriesCollection.insertMany(catDocs);
        insertCategoriesJson(catDocs);
        batchInsertOracle("INSERT INTO " + CATEGORIES_REL_TABLE + " (category_id, name, parent_id, lvl) VALUES (?, ?, ?, ?)", catRelRows);

        // Generate products
        List<Document> prodDocs = new ArrayList<>();
        List<Object[]> prodRelRows = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            String id = String.format("prod_%05d", i);
            double price = 10.0 + rng.nextDouble() * 990.0;
            Document doc = new Document("_id", id).append("name", "Product " + i).append("price", Math.round(price * 100.0) / 100.0);
            prodDocs.add(doc);
            prodRelRows.add(new Object[]{id, "Product " + i, Math.round(price * 100.0) / 100.0, null});
        }
        if (!skipMongoDB) insertMongoBatched(productsCollection, prodDocs);
        insertJsonDocs(PRODUCTS, prodDocs);
        batchInsertOracle("INSERT INTO " + PRODUCTS_REL + " (product_id, name, price, supplier_id) VALUES (?, ?, ?, ?)", prodRelRows);

        // Generate junction links
        List<Document> linkDocs = new ArrayList<>();
        List<Object[]> linkRelRows = new ArrayList<>();
        int linkId = 0;
        for (int p = 0; p < productCount; p++) {
            String productId = String.format("prod_%05d", p);
            int linkCount = Math.max(1, avgLinksPerProduct + rng.nextInt(3) - 1);
            Set<Integer> used = new HashSet<>();
            for (int l = 0; l < linkCount && used.size() < categoryCount; l++) {
                int catIdx = rng.nextInt(categoryCount);
                if (used.add(catIdx)) {
                    String categoryId = String.format("cat_%05d", catIdx);
                    String id = String.format("pc_%08d", linkId++);
                    linkDocs.add(new Document("_id", id).append("product_id", productId).append("category_id", categoryId));
                    linkRelRows.add(new Object[]{productId, categoryId});
                }
            }
        }
        if (!skipMongoDB) insertMongoBatched(productCategoriesCollection, linkDocs);
        insertJsonDocs(PRODUCT_CATEGORIES, linkDocs);
        batchInsertOracle("INSERT INTO " + PRODUCT_CATEGORIES_REL + " (product_id, category_id) VALUES (?, ?)", linkRelRows);

        if (!skipMongoDB && !noIndexMode) {
            productsCollection.createIndex(new Document("_id", 1));
            productCategoriesCollection.createIndex(new Document("product_id", 1));
            productCategoriesCollection.createIndex(new Document("category_id", 1));
            categoriesCollection.createIndex(new Document("_id", 1));
        }
        gatherStats(PRODUCTS, PRODUCT_CATEGORIES, CATEGORIES_COLLECTION, PRODUCTS_REL, PRODUCT_CATEGORIES_REL, CATEGORIES_REL_TABLE);
        System.out.println("  [Data] M:N: " + productCount + " products, " + categoryCount + " categories, " + linkId + " links");
    }

    // --- X1: Hierarchical Data Generation ---

    private void generateHierData(int customerCount, int ordersPerCust, int itemsPerOrder, int shipmentsPerItem) {
        clearEcommerceData();
        Random rng = new Random(42);

        // Customers
        List<Document> custDocs = new ArrayList<>();
        List<Object[]> custRelRows = new ArrayList<>();
        for (int i = 0; i < customerCount; i++) {
            String id = String.format("cust_%05d", i);
            custDocs.add(new Document("_id", id).append("name", "Customer " + i).append("email", "c" + i + "@test.com"));
            custRelRows.add(new Object[]{id, "Customer " + i, "c" + i + "@test.com", null});
        }
        if (!skipMongoDB) insertMongoBatched(customersCollection, custDocs);
        insertJsonDocs(CUSTOMERS, custDocs);
        batchInsertOracle("INSERT INTO " + CUSTOMERS_REL + " (customer_id, name, email, region_id) VALUES (?, ?, ?, ?)", custRelRows);

        // Orders
        List<Document> ordDocs = new ArrayList<>();
        List<Object[]> ordRelRows = new ArrayList<>();
        int ordIdx = 0;
        for (int c = 0; c < customerCount; c++) {
            String custId = String.format("cust_%05d", c);
            for (int o = 0; o < ordersPerCust; o++) {
                String id = String.format("ord_%07d", ordIdx++);
                double total = 50.0 + rng.nextDouble() * 950.0;
                ordDocs.add(new Document("_id", id).append("customer_id", custId).append("total", Math.round(total * 100.0) / 100.0));
                ordRelRows.add(new Object[]{id, custId, null, Math.round(total * 100.0) / 100.0});
            }
        }
        if (!skipMongoDB) insertMongoBatched(ordersCollection, ordDocs);
        insertJsonDocs(ORDERS, ordDocs);
        batchInsertOracle("INSERT INTO " + ORDERS_REL + " (order_id, customer_id, order_date, total) VALUES (?, ?, ?, ?)", ordRelRows);

        // Order Items (if levels >= 3)
        if (itemsPerOrder > 0) {
            List<Document> itemDocs = new ArrayList<>();
            List<Object[]> itemRelRows = new ArrayList<>();
            int itemIdx = 0;
            for (int oi = 0; oi < ordIdx; oi++) {
                String ordId = String.format("ord_%07d", oi);
                for (int it = 0; it < itemsPerOrder; it++) {
                    String id = String.format("item_%08d", itemIdx++);
                    int qty = 1 + rng.nextInt(10);
                    double price = 5.0 + rng.nextDouble() * 200.0;
                    itemDocs.add(new Document("_id", id).append("order_id", ordId)
                            .append("product_id", String.format("prod_%05d", rng.nextInt(1000)))
                            .append("quantity", qty).append("unit_price", Math.round(price * 100.0) / 100.0));
                    itemRelRows.add(new Object[]{id, ordId, String.format("prod_%05d", rng.nextInt(1000)),
                            qty, Math.round(price * 100.0) / 100.0, Math.round(qty * price * 100.0) / 100.0});
                }
            }
            if (!skipMongoDB) insertMongoBatched(orderItemsCollection, itemDocs);
            insertJsonDocs(ORDER_ITEMS, itemDocs);
            batchInsertOracle("INSERT INTO " + ORDER_ITEMS_REL + " (item_id, order_id, product_id, quantity, unit_price, line_total) VALUES (?, ?, ?, ?, ?, ?)", itemRelRows);

            // Shipments (if levels >= 4)
            if (shipmentsPerItem > 0) {
                List<Document> shipDocs = new ArrayList<>();
                List<Object[]> shipRelRows = new ArrayList<>();
                String[] carriers = {"FedEx", "UPS", "USPS", "DHL"};
                int shipIdx = 0;
                for (int si = 0; si < itemIdx; si++) {
                    String itemId = String.format("item_%08d", si);
                    for (int sh = 0; sh < shipmentsPerItem; sh++) {
                        String id = String.format("ship_%08d", shipIdx++);
                        shipDocs.add(new Document("_id", id).append("item_id", itemId)
                                .append("carrier", carriers[rng.nextInt(carriers.length)])
                                .append("status", "DELIVERED"));
                        shipRelRows.add(new Object[]{id, itemId, null, carriers[rng.nextInt(carriers.length)],
                                "TRK" + shipIdx, "DELIVERED"});
                    }
                }
                if (!skipMongoDB) insertMongoBatched(shipmentsCollection, shipDocs);
                insertJsonDocs(SHIPMENTS, shipDocs);
                batchInsertOracle("INSERT INTO " + SHIPMENTS_REL + " (shipment_id, item_id, ship_date, carrier, tracking_num, status) VALUES (?, ?, ?, ?, ?, ?)", shipRelRows);
            }
        }

        if (!skipMongoDB && !noIndexMode) {
            customersCollection.createIndex(new Document("_id", 1));
            ordersCollection.createIndex(new Document("customer_id", 1));
            if (itemsPerOrder > 0) orderItemsCollection.createIndex(new Document("order_id", 1));
            if (shipmentsPerItem > 0) shipmentsCollection.createIndex(new Document("item_id", 1));
        }
        gatherStats(CUSTOMERS, ORDERS, ORDER_ITEMS, SHIPMENTS, CUSTOMERS_REL, ORDERS_REL, ORDER_ITEMS_REL, SHIPMENTS_REL);
        System.out.println("  [Data] Hier: " + customerCount + " custs, " + ordersPerCust + " ord/cust, " +
                itemsPerOrder + " items/ord, " + shipmentsPerItem + " ship/item");
    }

    // --- X2: M:N + Child 1:N Data Generation ---

    private void generateM2NChildData(int productCount, int itemCount, int shipmentsPerItem) {
        clearEcommerceData();
        Random rng = new Random(42);

        // Products
        List<Document> prodDocs = new ArrayList<>();
        List<Object[]> prodRelRows = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            String id = String.format("prod_%05d", i);
            double price = 10.0 + rng.nextDouble() * 990.0;
            prodDocs.add(new Document("_id", id).append("name", "Product " + i).append("price", Math.round(price * 100.0) / 100.0));
            prodRelRows.add(new Object[]{id, "Product " + i, Math.round(price * 100.0) / 100.0, null});
        }
        if (!skipMongoDB) insertMongoBatched(productsCollection, prodDocs);
        insertJsonDocs(PRODUCTS, prodDocs);
        batchInsertOracle("INSERT INTO " + PRODUCTS_REL + " (product_id, name, price, supplier_id) VALUES (?, ?, ?, ?)", prodRelRows);

        // Order Items (referencing products)
        List<Document> itemDocs = new ArrayList<>();
        List<Object[]> itemRelRows = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            String id = String.format("item_%08d", i);
            String prodId = String.format("prod_%05d", rng.nextInt(productCount));
            String ordId = String.format("ord_%07d", i / 5);
            int qty = 1 + rng.nextInt(10);
            double price = 5.0 + rng.nextDouble() * 200.0;
            itemDocs.add(new Document("_id", id).append("order_id", ordId).append("product_id", prodId)
                    .append("quantity", qty).append("unit_price", Math.round(price * 100.0) / 100.0));
            itemRelRows.add(new Object[]{id, ordId, prodId, qty, Math.round(price * 100.0) / 100.0, Math.round(qty * price * 100.0) / 100.0});
        }
        if (!skipMongoDB) insertMongoBatched(orderItemsCollection, itemDocs);
        insertJsonDocs(ORDER_ITEMS, itemDocs);
        batchInsertOracle("INSERT INTO " + ORDER_ITEMS_REL + " (item_id, order_id, product_id, quantity, unit_price, line_total) VALUES (?, ?, ?, ?, ?, ?)", itemRelRows);

        // Shipments
        List<Document> shipDocs = new ArrayList<>();
        List<Object[]> shipRelRows = new ArrayList<>();
        String[] carriers = {"FedEx", "UPS", "USPS", "DHL"};
        int shipIdx = 0;
        for (int i = 0; i < itemCount; i++) {
            String itemId = String.format("item_%08d", i);
            for (int s = 0; s < shipmentsPerItem; s++) {
                String id = String.format("ship_%08d", shipIdx++);
                shipDocs.add(new Document("_id", id).append("item_id", itemId)
                        .append("carrier", carriers[rng.nextInt(carriers.length)]).append("status", "DELIVERED"));
                shipRelRows.add(new Object[]{id, itemId, null, carriers[rng.nextInt(carriers.length)], "TRK" + shipIdx, "DELIVERED"});
            }
        }
        if (!skipMongoDB) insertMongoBatched(shipmentsCollection, shipDocs);
        insertJsonDocs(SHIPMENTS, shipDocs);
        batchInsertOracle("INSERT INTO " + SHIPMENTS_REL + " (shipment_id, item_id, ship_date, carrier, tracking_num, status) VALUES (?, ?, ?, ?, ?, ?)", shipRelRows);

        if (!skipMongoDB && !noIndexMode) {
            productsCollection.createIndex(new Document("_id", 1));
            orderItemsCollection.createIndex(new Document("product_id", 1));
            shipmentsCollection.createIndex(new Document("item_id", 1));
        }
        gatherStats(PRODUCTS, ORDER_ITEMS, SHIPMENTS, PRODUCTS_REL, ORDER_ITEMS_REL, SHIPMENTS_REL);
        System.out.println("  [Data] M:N+Child: " + productCount + " prods, " + itemCount + " items, " + shipmentsPerItem + " ship/item");
    }

    // --- X3: M:N + Both 1:N Data Generation ---

    private void generateM2NBothData(int productCount, int categoryCount, int avgLinks, int reviewsPerProd, int rulesPerCat) {
        clearEcommerceData();
        clearCategoryData();
        Random rng = new Random(42);

        // Categories
        List<Document> catDocs = new ArrayList<>();
        List<Object[]> catRelRows = new ArrayList<>();
        for (int i = 0; i < categoryCount; i++) {
            String id = String.format("cat_%05d", i);
            catDocs.add(new Document("_id", id).append("name", "Category " + i).append("parent_id", null).append("level", 0));
            catRelRows.add(new Object[]{id, "Category " + i, null, 0});
        }
        if (!skipMongoDB) categoriesCollection.insertMany(catDocs);
        insertCategoriesJson(catDocs);
        batchInsertOracle("INSERT INTO " + CATEGORIES_REL_TABLE + " (category_id, name, parent_id, lvl) VALUES (?, ?, ?, ?)", catRelRows);

        // Products
        List<Document> prodDocs = new ArrayList<>();
        List<Object[]> prodRelRows = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            String id = String.format("prod_%05d", i);
            double price = 10.0 + rng.nextDouble() * 990.0;
            prodDocs.add(new Document("_id", id).append("name", "Product " + i).append("price", Math.round(price * 100.0) / 100.0));
            prodRelRows.add(new Object[]{id, "Product " + i, Math.round(price * 100.0) / 100.0, null});
        }
        if (!skipMongoDB) insertMongoBatched(productsCollection, prodDocs);
        insertJsonDocs(PRODUCTS, prodDocs);
        batchInsertOracle("INSERT INTO " + PRODUCTS_REL + " (product_id, name, price, supplier_id) VALUES (?, ?, ?, ?)", prodRelRows);

        // Product-Category links
        List<Document> linkDocs = new ArrayList<>();
        List<Object[]> linkRelRows = new ArrayList<>();
        int linkId = 0;
        for (int p = 0; p < productCount; p++) {
            String prodId = String.format("prod_%05d", p);
            Set<Integer> used = new HashSet<>();
            for (int l = 0; l < avgLinks; l++) {
                int catIdx = rng.nextInt(categoryCount);
                if (used.add(catIdx)) {
                    linkDocs.add(new Document("_id", String.format("pc_%08d", linkId++))
                            .append("product_id", prodId).append("category_id", String.format("cat_%05d", catIdx)));
                    linkRelRows.add(new Object[]{prodId, String.format("cat_%05d", catIdx)});
                }
            }
        }
        if (!skipMongoDB) insertMongoBatched(productCategoriesCollection, linkDocs);
        insertJsonDocs(PRODUCT_CATEGORIES, linkDocs);
        batchInsertOracle("INSERT INTO " + PRODUCT_CATEGORIES_REL + " (product_id, category_id) VALUES (?, ?)", linkRelRows);

        // Reviews
        List<Document> revDocs = new ArrayList<>();
        List<Object[]> revRelRows = new ArrayList<>();
        int revIdx = 0;
        for (int p = 0; p < productCount; p++) {
            String prodId = String.format("prod_%05d", p);
            for (int r = 0; r < reviewsPerProd; r++) {
                String id = String.format("rev_%08d", revIdx++);
                int rating = 1 + rng.nextInt(5);
                revDocs.add(new Document("_id", id).append("product_id", prodId).append("rating", rating).append("review_text", "Review " + revIdx));
                revRelRows.add(new Object[]{id, prodId, rating, "Review " + revIdx, null});
            }
        }
        if (!skipMongoDB) insertMongoBatched(reviewsCollection, revDocs);
        insertJsonDocs(REVIEWS, revDocs);
        batchInsertOracle("INSERT INTO " + REVIEWS_REL + " (review_id, product_id, rating, review_text, review_date) VALUES (?, ?, ?, ?, ?)", revRelRows);

        // Category Rules
        List<Document> ruleDocs = new ArrayList<>();
        List<Object[]> ruleRelRows = new ArrayList<>();
        int ruleIdx = 0;
        for (int c = 0; c < categoryCount; c++) {
            String catId = String.format("cat_%05d", c);
            for (int r = 0; r < rulesPerCat; r++) {
                String id = String.format("rule_%07d", ruleIdx++);
                ruleDocs.add(new Document("_id", id).append("category_id", catId)
                        .append("rule_name", "rule_" + r).append("rule_value", String.valueOf(rng.nextInt(1000))));
                ruleRelRows.add(new Object[]{id, catId, "rule_" + r, String.valueOf(rng.nextInt(1000))});
            }
        }
        if (!skipMongoDB) insertMongoBatched(categoryRulesCollection, ruleDocs);
        insertJsonDocs(CATEGORY_RULES, ruleDocs);
        batchInsertOracle("INSERT INTO " + CATEGORY_RULES_REL + " (rule_id, category_id, rule_name, rule_value) VALUES (?, ?, ?, ?)", ruleRelRows);

        if (!skipMongoDB && !noIndexMode) {
            productsCollection.createIndex(new Document("_id", 1));
            productsCollection.createIndex(new Document("price", 1));
            productCategoriesCollection.createIndex(new Document("product_id", 1));
            productCategoriesCollection.createIndex(new Document("category_id", 1));
            categoriesCollection.createIndex(new Document("_id", 1));
            reviewsCollection.createIndex(new Document("product_id", 1));
            categoryRulesCollection.createIndex(new Document("category_id", 1));
        }
        gatherStats(PRODUCTS, PRODUCT_CATEGORIES, CATEGORIES_COLLECTION, REVIEWS, CATEGORY_RULES,
                PRODUCTS_REL, PRODUCT_CATEGORIES_REL, CATEGORIES_REL_TABLE, REVIEWS_REL, CATEGORY_RULES_REL);
        System.out.println("  [Data] M:N+Both: " + productCount + " prods, " + categoryCount + " cats, " + revIdx + " reviews, " + ruleIdx + " rules");
    }

    // --- X5: Diamond Data Generation ---

    private void generateDiamondData(int orderCount, int customerCount, int productCount, int supplierCount, int regionCount) {
        clearEcommerceData();
        Random rng = new Random(42);

        // Regions
        List<Document> regDocs = new ArrayList<>();
        List<Object[]> regRelRows = new ArrayList<>();
        String[] countries = {"USA", "Canada", "UK", "Germany", "Japan", "Australia", "France", "Brazil", "India", "China"};
        for (int i = 0; i < regionCount; i++) {
            String id = String.format("reg_%04d", i);
            regDocs.add(new Document("_id", id).append("name", "Region " + i).append("country", countries[i % countries.length]));
            regRelRows.add(new Object[]{id, "Region " + i, countries[i % countries.length]});
        }
        if (!skipMongoDB) regionsCollection.insertMany(regDocs);
        insertJsonDocs(REGIONS, regDocs);
        batchInsertOracle("INSERT INTO " + REGIONS_REL + " (region_id, name, country) VALUES (?, ?, ?)", regRelRows);

        // Suppliers
        List<Document> suppDocs = new ArrayList<>();
        List<Object[]> suppRelRows = new ArrayList<>();
        for (int i = 0; i < supplierCount; i++) {
            String id = String.format("supp_%05d", i);
            String regId = String.format("reg_%04d", rng.nextInt(regionCount));
            suppDocs.add(new Document("_id", id).append("name", "Supplier " + i).append("region_id", regId));
            suppRelRows.add(new Object[]{id, "Supplier " + i, regId, "s" + i + "@test.com"});
        }
        if (!skipMongoDB) suppliersCollection.insertMany(suppDocs);
        insertJsonDocs(SUPPLIERS, suppDocs);
        batchInsertOracle("INSERT INTO " + SUPPLIERS_REL + " (supplier_id, name, region_id, contact_email) VALUES (?, ?, ?, ?)", suppRelRows);

        // Products (with supplier_id)
        List<Document> prodDocs = new ArrayList<>();
        List<Object[]> prodRelRows = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            String id = String.format("prod_%05d", i);
            String suppId = String.format("supp_%05d", rng.nextInt(supplierCount));
            double price = 10.0 + rng.nextDouble() * 990.0;
            prodDocs.add(new Document("_id", id).append("name", "Product " + i)
                    .append("price", Math.round(price * 100.0) / 100.0).append("supplier_id", suppId));
            prodRelRows.add(new Object[]{id, "Product " + i, Math.round(price * 100.0) / 100.0, suppId});
        }
        if (!skipMongoDB) insertMongoBatched(productsCollection, prodDocs);
        insertJsonDocs(PRODUCTS, prodDocs);
        batchInsertOracle("INSERT INTO " + PRODUCTS_REL + " (product_id, name, price, supplier_id) VALUES (?, ?, ?, ?)", prodRelRows);

        // Customers (with region_id)
        List<Document> custDocs = new ArrayList<>();
        List<Object[]> custRelRows = new ArrayList<>();
        for (int i = 0; i < customerCount; i++) {
            String id = String.format("cust_%05d", i);
            String regId = String.format("reg_%04d", rng.nextInt(regionCount));
            custDocs.add(new Document("_id", id).append("name", "Customer " + i).append("region_id", regId));
            custRelRows.add(new Object[]{id, "Customer " + i, "c" + i + "@test.com", regId});
        }
        if (!skipMongoDB) insertMongoBatched(customersCollection, custDocs);
        insertJsonDocs(CUSTOMERS, custDocs);
        batchInsertOracle("INSERT INTO " + CUSTOMERS_REL + " (customer_id, name, email, region_id) VALUES (?, ?, ?, ?)", custRelRows);

        // Orders (with customer_id)
        List<Document> ordDocs = new ArrayList<>();
        List<Object[]> ordRelRows = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            String id = String.format("ord_%07d", i);
            String custId = String.format("cust_%05d", rng.nextInt(customerCount));
            double total = 50.0 + rng.nextDouble() * 950.0;
            ordDocs.add(new Document("_id", id).append("customer_id", custId).append("total", Math.round(total * 100.0) / 100.0));
            ordRelRows.add(new Object[]{id, custId, null, Math.round(total * 100.0) / 100.0});
        }
        if (!skipMongoDB) insertMongoBatched(ordersCollection, ordDocs);
        insertJsonDocs(ORDERS, ordDocs);
        batchInsertOracle("INSERT INTO " + ORDERS_REL + " (order_id, customer_id, order_date, total) VALUES (?, ?, ?, ?)", ordRelRows);

        // Order Items (linking orders to products)
        List<Document> itemDocs = new ArrayList<>();
        List<Object[]> itemRelRows = new ArrayList<>();
        int itemIdx = 0;
        for (int i = 0; i < orderCount; i++) {
            String ordId = String.format("ord_%07d", i);
            int itemCount = 1 + rng.nextInt(5);
            for (int it = 0; it < itemCount; it++) {
                String id = String.format("item_%08d", itemIdx++);
                String prodId = String.format("prod_%05d", rng.nextInt(productCount));
                int qty = 1 + rng.nextInt(10);
                double price = 5.0 + rng.nextDouble() * 200.0;
                itemDocs.add(new Document("_id", id).append("order_id", ordId).append("product_id", prodId)
                        .append("quantity", qty).append("unit_price", Math.round(price * 100.0) / 100.0));
                itemRelRows.add(new Object[]{id, ordId, prodId, qty, Math.round(price * 100.0) / 100.0, Math.round(qty * price * 100.0) / 100.0});
            }
        }
        if (!skipMongoDB) insertMongoBatched(orderItemsCollection, itemDocs);
        insertJsonDocs(ORDER_ITEMS, itemDocs);
        batchInsertOracle("INSERT INTO " + ORDER_ITEMS_REL + " (item_id, order_id, product_id, quantity, unit_price, line_total) VALUES (?, ?, ?, ?, ?, ?)", itemRelRows);

        if (!skipMongoDB && !noIndexMode) {
            ordersCollection.createIndex(new Document("customer_id", 1));
            customersCollection.createIndex(new Document("region_id", 1));
            orderItemsCollection.createIndex(new Document("order_id", 1));
            orderItemsCollection.createIndex(new Document("product_id", 1));
            productsCollection.createIndex(new Document("supplier_id", 1));
            productsCollection.createIndex(new Document("price", 1));
            suppliersCollection.createIndex(new Document("region_id", 1));
        }
        gatherStats(REGIONS, SUPPLIERS, PRODUCTS, CUSTOMERS, ORDERS, ORDER_ITEMS,
                REGIONS_REL, SUPPLIERS_REL, PRODUCTS_REL, CUSTOMERS_REL, ORDERS_REL, ORDER_ITEMS_REL);
        System.out.println("  [Data] Diamond: " + orderCount + " orders, " + customerCount + " custs, " +
                productCount + " prods, " + supplierCount + " supps, " + regionCount + " regions, " + itemIdx + " items");
    }

    /** Batch insert to MongoDB in chunks of 10000 to avoid oversized commands. */
    private void insertMongoBatched(MongoCollection<Document> collection, List<Document> docs) {
        int batchSize = 10_000;
        for (int i = 0; i < docs.size(); i += batchSize) {
            collection.insertMany(docs.subList(i, Math.min(i + batchSize, docs.size())));
        }
    }

    // =========================================================================
    // Data Generation: X4 Category Hierarchies
    // =========================================================================

    /**
     * Generates a balanced hierarchical category tree.
     * ~20% of categories are roots, the rest are distributed across levels 1..maxDepth-1.
     * Inserts into MongoDB, Oracle JSON, Oracle relational, and edge table.
     * Recreates the property graph if supported.
     */
    private void generateHierarchicalCategories(int count, int maxDepth) {
        clearCategoryData();

        int rootCount = Math.max(1, count / 5);
        List<Document> mongoDocs = new ArrayList<>();
        List<Object[]> relRows = new ArrayList<>();
        List<Object[]> edgeRows = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String id = String.format("cat_%05d", i);
            String parentId;
            int level;

            if (i < rootCount) {
                parentId = null;
                level = 0;
            } else {
                // Distribute children across levels, referencing a parent from the previous level
                level = 1 + ((i - rootCount) % (maxDepth - 1));
                // Parent is from previous level - pick deterministically
                if (level == 1) {
                    parentId = String.format("cat_%05d", i % rootCount);
                } else {
                    // Find a node at level-1. Nodes at level L are those where:
                    // For level 1: indices [0, rootCount)
                    // For level > 1: computed from the distribution
                    int parentIdx = rootCount + ((i - rootCount) / (maxDepth - 1)) % (count - rootCount);
                    if (parentIdx >= i) parentIdx = i - 1; // Safety: always reference earlier node
                    parentId = String.format("cat_%05d", parentIdx);
                }
            }

            Document doc = new Document()
                    .append("_id", id)
                    .append("name", "Category " + i)
                    .append("parent_id", parentId)
                    .append("level", level);
            mongoDocs.add(doc);

            relRows.add(new Object[]{id, "Category " + i, parentId, level});

            // Edge: child -> parent (skip roots which have no parent)
            if (parentId != null) {
                edgeRows.add(new Object[]{"edge_" + id, parentId, id});
            }
        }

        // Insert MongoDB
        if (!skipMongoDB) {
            categoriesCollection.insertMany(mongoDocs);
            if (!noIndexMode) {
                categoriesCollection.createIndex(new Document("parent_id", 1));
            }
        }

        // Insert Oracle JSON
        insertCategoriesJson(mongoDocs);

        // Insert Oracle relational
        batchInsertOracle("INSERT INTO " + CATEGORIES_REL_TABLE +
                " (category_id, name, parent_id, lvl) VALUES (?, ?, ?, ?)", relRows);

        // Insert edges
        batchInsertOracle("INSERT INTO " + CATEGORY_EDGES_TABLE +
                " (edge_id, parent_id, child_id) VALUES (?, ?, ?)", edgeRows);

        // Gather stats and recreate property graph
        gatherCategoryStats();
        if (propertyGraphSupported) {
            recreatePropertyGraph();
        }
    }

    /**
     * Generates a wide hierarchy: 1 root with (count-1) direct children.
     * All children are at level 1.
     */
    private void generateWideHierarchicalCategories(int count, int maxDepth) {
        clearCategoryData();

        List<Document> mongoDocs = new ArrayList<>();
        List<Object[]> relRows = new ArrayList<>();
        List<Object[]> edgeRows = new ArrayList<>();

        // Root node
        String rootId = "cat_00000";
        mongoDocs.add(new Document()
                .append("_id", rootId)
                .append("name", "Root Category")
                .append("parent_id", null)
                .append("level", 0));
        relRows.add(new Object[]{rootId, "Root Category", null, 0});

        // All other nodes are direct children of root
        for (int i = 1; i < count; i++) {
            String id = String.format("cat_%05d", i);
            mongoDocs.add(new Document()
                    .append("_id", id)
                    .append("name", "Category " + i)
                    .append("parent_id", rootId)
                    .append("level", 1));
            relRows.add(new Object[]{id, "Category " + i, rootId, 1});
            edgeRows.add(new Object[]{"edge_" + id, rootId, id});
        }

        if (!skipMongoDB) {
            categoriesCollection.insertMany(mongoDocs);
            if (!noIndexMode) {
                categoriesCollection.createIndex(new Document("parent_id", 1));
            }
        }

        insertCategoriesJson(mongoDocs);
        batchInsertOracle("INSERT INTO " + CATEGORIES_REL_TABLE +
                " (category_id, name, parent_id, lvl) VALUES (?, ?, ?, ?)", relRows);
        batchInsertOracle("INSERT INTO " + CATEGORY_EDGES_TABLE +
                " (edge_id, parent_id, child_id) VALUES (?, ?, ?)", edgeRows);

        gatherCategoryStats();
        if (propertyGraphSupported) {
            recreatePropertyGraph();
        }
    }

    private void clearCategoryData() {
        if (!skipMongoDB) {
            categoriesCollection.drop();
            categoriesCollection = mongoDatabase.getCollection(CATEGORIES_COLLECTION)
                    .withWriteConcern(WriteConcern.W1.withJournal(true));
        }

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Drop property graph first (depends on tables)
            if (propertyGraphSupported) {
                try { stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME); } catch (SQLException ignored) {}
            }
            try { stmt.execute("DELETE FROM " + CATEGORY_EDGES_TABLE); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + CATEGORIES_REL_TABLE); } catch (SQLException ignored) {}
            try { stmt.execute("DELETE FROM " + CATEGORIES_COLLECTION); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            System.out.println("  Warning: Could not clear category data: " + e.getMessage());
        }
    }

    private void insertCategoriesJson(List<Document> docs) {
        String sql = "INSERT INTO " + CATEGORIES_COLLECTION + " (id, data) VALUES (?, ?)";
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert categories JSON", e);
        }
    }

    private void batchInsertOracle(String sql, List<Object[]> rows) {
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (row[i] == null) {
                        ps.setNull(i + 1, Types.VARCHAR);
                    } else if (row[i] instanceof Integer) {
                        ps.setInt(i + 1, (Integer) row[i]);
                    } else {
                        ps.setString(i + 1, row[i].toString());
                    }
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed batch insert: " + sql, e);
        }
    }

    private void gatherCategoryStats() {
        if (noIndexMode) return;
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CATEGORIES_COLLECTION + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CATEGORIES_REL_TABLE + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + CATEGORY_EDGES_TABLE + "', cascade => TRUE); END;");
        } catch (SQLException e) {
            System.out.println("  Warning: Could not gather category stats: " + e.getMessage());
        }
    }

    /**
     * Recreates the SQL Property Graph definition after data changes.
     * Must be called after inserting data because the graph references the underlying tables.
     */
    private void recreatePropertyGraph() {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try { stmt.execute("DROP PROPERTY GRAPH " + CATEGORY_GRAPH_NAME); } catch (SQLException ignored) {}

            String graphDdl = "CREATE PROPERTY GRAPH " + CATEGORY_GRAPH_NAME +
                    " VERTEX TABLES (" +
                    "  " + CATEGORIES_REL_TABLE + " AS category " +
                    "    KEY (category_id) " +
                    "    PROPERTIES (category_id, name, parent_id, lvl)" +
                    ") " +
                    "EDGE TABLES (" +
                    "  " + CATEGORY_EDGES_TABLE + " AS child_of " +
                    "    KEY (edge_id) " +
                    "    SOURCE KEY (child_id) REFERENCES category (category_id) " +
                    "    DESTINATION KEY (parent_id) REFERENCES category (category_id) " +
                    "    NO PROPERTIES" +
                    ")";
            stmt.execute(graphDdl);
        } catch (SQLException e) {
            System.out.println("  Warning: Could not recreate property graph: " + e.getMessage());
            propertyGraphSupported = false;
        }
    }

    // =========================================================================
    // MongoDB $graphLookup Measurement
    // =========================================================================

    /**
     * Measures MongoDB $graphLookup: find all descendants of root categories.
     * Pipeline: $match roots -> $graphLookup traversing parent_id -> _id links.
     */
    private long measureMongoGraphLookup(int maxDepth) {
        List<Bson> pipeline = buildGraphLookupPipeline(maxDepth);

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : categoriesCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume results
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private void runMongoGraphLookup(int maxDepth) {
        List<Bson> pipeline = buildGraphLookupPipeline(maxDepth);
        for (Document doc : categoriesCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
            // Consume
        }
    }

    static List<Bson> buildGraphLookupPipeline(int maxDepth) {
        // $match: root categories (parent_id = null)
        // $graphLookup: start from _id, connect _id -> parent_id, collecting descendants
        Document matchStage = new Document("$match",
                new Document("parent_id", null));

        Document graphLookupStage = new Document("$graphLookup",
                new Document("from", CATEGORIES_COLLECTION)
                        .append("startWith", "$_id")
                        .append("connectFromField", "_id")
                        .append("connectToField", "parent_id")
                        .append("as", "descendants")
                        .append("maxDepth", maxDepth)
                        .append("depthField", "level"));

        return Arrays.asList(matchStage, graphLookupStage);
    }

    /**
     * MongoDB path reconstruction: for each leaf, traverse up to root.
     * Uses $graphLookup in reverse: start from leaf, follow parent_id to find ancestors.
     */
    private long measureMongoGraphLookupPathReconstruction() {
        List<Bson> pipeline = buildGraphLookupPathPipeline();

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : categoriesCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
                // Consume
            }
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private void runMongoGraphLookupPathReconstruction() {
        List<Bson> pipeline = buildGraphLookupPathPipeline();
        for (Document doc : categoriesCollection.aggregate(pipeline).batchSize(JDBC_FETCH_SIZE)) {
            // Consume
        }
    }

    static List<Bson> buildGraphLookupPathPipeline() {
        // For each category, find all ancestors (reverse traversal: follow parent_id upward)
        Document graphLookupStage = new Document("$graphLookup",
                new Document("from", CATEGORIES_COLLECTION)
                        .append("startWith", "$parent_id")
                        .append("connectFromField", "parent_id")
                        .append("connectToField", "_id")
                        .append("as", "ancestors")
                        .append("depthField", "depth"));

        return Arrays.asList(graphLookupStage);
    }

    // =========================================================================
    // Oracle Recursive CTE Measurement
    // =========================================================================

    /**
     * Measures Oracle Recursive CTE on relational table.
     * Finds all descendants of root categories using WITH RECURSIVE.
     */
    private long measureOracleRecursiveCteRelational(int maxDepth) {
        String sql = buildRecursiveCteSql(maxDepth);

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Consume
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle Recursive CTE error", e);
        }
    }

    private void runOracleRecursiveCteRelational(int maxDepth) {
        String sql = buildRecursiveCteSql(maxDepth);
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    // Consume
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle Recursive CTE warmup error", e);
        }
    }

    static String buildRecursiveCteSql(int maxDepth) {
        return "WITH category_tree (category_id, name, parent_id, lvl, depth) AS (" +
                "  SELECT category_id, name, parent_id, lvl, 0 AS depth" +
                "  FROM " + CATEGORIES_REL_TABLE +
                "  WHERE parent_id IS NULL" +
                "  UNION ALL" +
                "  SELECT c.category_id, c.name, c.parent_id, c.lvl, ct.depth + 1" +
                "  FROM " + CATEGORIES_REL_TABLE + " c" +
                "  JOIN category_tree ct ON c.parent_id = ct.category_id" +
                "  WHERE ct.depth < " + maxDepth +
                ")" +
                " SELECT * FROM category_tree";
    }

    /**
     * Path reconstruction via Recursive CTE: for each leaf, build path to root.
     * Uses a reverse CTE that starts from all categories and walks up to root.
     */
    private long measureOracleRecursiveCtePathReconstruction() {
        String sql = buildRecursiveCtePathSql();

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Consume
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            throw new RuntimeException("Oracle Recursive CTE path error", e);
        }
    }

    private void runOracleRecursiveCtePathReconstruction() {
        String sql = buildRecursiveCtePathSql();
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            throw new RuntimeException("Oracle CTE path warmup error", e);
        }
    }

    static String buildRecursiveCtePathSql() {
        // Start from each category, walk UP to root via parent_id, then aggregate path.
        // Carry current_parent_id forward so each recursive step needs only a single join.
        return "WITH path_cte (leaf_id, current_id, current_name, current_parent_id, depth) AS (" +
                "  SELECT category_id, category_id, name, parent_id, 0" +
                "  FROM " + CATEGORIES_REL_TABLE +
                "  UNION ALL" +
                "  SELECT p.leaf_id, c.category_id, c.name, c.parent_id, p.depth + 1" +
                "  FROM path_cte p" +
                "  JOIN " + CATEGORIES_REL_TABLE + " c ON c.category_id = p.current_parent_id" +
                "  WHERE p.current_parent_id IS NOT NULL AND p.depth < 10" +
                ")" +
                " SELECT leaf_id," +
                "   LISTAGG(current_name, ' > ') WITHIN GROUP (ORDER BY depth DESC) AS full_path" +
                " FROM path_cte GROUP BY leaf_id";
    }

    // =========================================================================
    // Oracle SQL Property Graph Measurement
    // =========================================================================

    /**
     * Measures Oracle SQL Property Graph (SQL/PGQ) for descendant traversal.
     * Uses GRAPH_TABLE with MATCH pattern for reachability.
     */
    private long measureOraclePropertyGraph(int maxDepth) {
        String sql = buildPropertyGraphDescendantSql(maxDepth);

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Consume
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            System.out.println("  Property Graph query failed: " + e.getMessage());
            return -1;
        }
    }

    private void runOraclePropertyGraph(int maxDepth) {
        String sql = buildPropertyGraphDescendantSql(maxDepth);
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            System.out.println("  Property Graph warmup failed: " + e.getMessage());
        }
    }

    static String buildPropertyGraphDescendantSql(int maxDepth) {
        // Find all descendants of root categories using graph pattern matching
        // Edge direction: child_of goes child -> parent, so we traverse in reverse
        // to find descendants: root <-[child_of]- descendant
        return "SELECT root_id, root_name, descendant_id, descendant_name" +
                " FROM GRAPH_TABLE (" + CATEGORY_GRAPH_NAME +
                "  MATCH (d IS category) -[e IS child_of]->{1," + maxDepth + "} (r IS category)" +
                "  WHERE r.parent_id IS NULL" +
                "  COLUMNS (r.category_id AS root_id, r.name AS root_name," +
                "           d.category_id AS descendant_id, d.name AS descendant_name)" +
                ")";
    }

    /**
     * Path reconstruction via SQL Property Graph.
     * Uses variable-length path patterns to find all ancestors for each category.
     */
    private long measureOraclePropertyGraphPathReconstruction() {
        String sql = buildPropertyGraphPathSql();

        try {
            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                ps.setFetchSize(JDBC_FETCH_SIZE);
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // Consume
                        }
                    }
                    totalNanos += System.nanoTime() - start;
                }
            }
            return totalNanos / MEASUREMENT_ITERATIONS;
        } catch (SQLException e) {
            System.out.println("  Property Graph path query failed: " + e.getMessage());
            return -1;
        }
    }

    private void runOraclePropertyGraphPathReconstruction() {
        String sql = buildPropertyGraphPathSql();
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.setFetchSize(JDBC_FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {}
            }
        } catch (SQLException e) {
            System.out.println("  Property Graph path warmup failed: " + e.getMessage());
        }
    }

    static String buildPropertyGraphPathSql() {
        // For each category, find its path to root via ancestor traversal
        // child_of edges go child -> parent, so following them leads to ancestors
        return "SELECT leaf_id, ancestor_id, ancestor_name" +
                " FROM GRAPH_TABLE (" + CATEGORY_GRAPH_NAME +
                "  MATCH (leaf IS category) -[e IS child_of]->{0,10} (ancestor IS category)" +
                "  COLUMNS (leaf.category_id AS leaf_id," +
                "           ancestor.category_id AS ancestor_id," +
                "           ancestor.name AS ancestor_name)" +
                ")";
    }

    // =========================================================================
    // Query Builders: X0 (M:N)
    // =========================================================================

    static List<Bson> buildM2MLookupPipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", PRODUCT_CATEGORIES)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "pc")),
                new Document("$unwind", "$pc"),
                new Document("$lookup", new Document("from", CATEGORIES_COLLECTION)
                        .append("localField", "pc.category_id").append("foreignField", "_id").append("as", "category")),
                new Document("$unwind", "$category"));
    }

    static String buildM2MJsonSql() {
        return "SELECT p.data, c.data FROM " + PRODUCTS + " p " +
                "JOIN " + PRODUCT_CATEGORIES + " pc ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(pc.data, '$.product_id') " +
                "JOIN " + CATEGORIES_COLLECTION + " c ON JSON_VALUE(pc.data, '$.category_id') = JSON_VALUE(c.data, '$._id')";
    }

    static String buildM2MRelSql() {
        return "SELECT p.product_id, p.name, c.category_id, c.name AS cat_name FROM " + PRODUCTS_REL + " p " +
                "JOIN " + PRODUCT_CATEGORIES_REL + " pc ON p.product_id = pc.product_id " +
                "JOIN " + CATEGORIES_REL_TABLE + " c ON pc.category_id = c.category_id";
    }

    // =========================================================================
    // Query Builders: X1 (Hierarchical)
    // =========================================================================

    static List<Bson> buildHierLookupPipeline(int levels) {
        List<Bson> pipeline = new ArrayList<>();
        // Level 1→2: Customer → Orders
        pipeline.add(new Document("$lookup", new Document("from", ORDERS)
                .append("localField", "_id").append("foreignField", "customer_id").append("as", "orders")));
        pipeline.add(new Document("$unwind", "$orders"));
        if (levels >= 3) {
            pipeline.add(new Document("$lookup", new Document("from", ORDER_ITEMS)
                    .append("localField", "orders._id").append("foreignField", "order_id").append("as", "items")));
            pipeline.add(new Document("$unwind", "$items"));
        }
        if (levels >= 4) {
            pipeline.add(new Document("$lookup", new Document("from", SHIPMENTS)
                    .append("localField", "items._id").append("foreignField", "item_id").append("as", "shipments")));
        }
        return pipeline;
    }

    static String buildHierJsonSql(int levels) {
        StringBuilder sb = new StringBuilder("SELECT c.data, o.data");
        if (levels >= 3) sb.append(", i.data");
        if (levels >= 4) sb.append(", s.data");
        sb.append(" FROM ").append(CUSTOMERS).append(" c ");
        sb.append("JOIN ").append(ORDERS).append(" o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') ");
        if (levels >= 3) {
            sb.append("JOIN ").append(ORDER_ITEMS).append(" i ON JSON_VALUE(o.data, '$._id') = JSON_VALUE(i.data, '$.order_id') ");
        }
        if (levels >= 4) {
            sb.append("LEFT JOIN ").append(SHIPMENTS).append(" s ON JSON_VALUE(i.data, '$._id') = JSON_VALUE(s.data, '$.item_id') ");
        }
        return sb.toString();
    }

    static String buildHierRelSql(int levels) {
        StringBuilder sb = new StringBuilder("SELECT c.customer_id, c.name, o.order_id, o.total");
        if (levels >= 3) sb.append(", i.item_id, i.quantity");
        if (levels >= 4) sb.append(", s.shipment_id, s.status");
        sb.append(" FROM ").append(CUSTOMERS_REL).append(" c ");
        sb.append("JOIN ").append(ORDERS_REL).append(" o ON c.customer_id = o.customer_id ");
        if (levels >= 3) {
            sb.append("JOIN ").append(ORDER_ITEMS_REL).append(" i ON o.order_id = i.order_id ");
        }
        if (levels >= 4) {
            sb.append("LEFT JOIN ").append(SHIPMENTS_REL).append(" s ON i.item_id = s.item_id ");
        }
        return sb.toString();
    }

    // =========================================================================
    // Query Builders: X2 (M:N + Child 1:N)
    // =========================================================================

    static List<Bson> buildM2NChildLookupPipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", ORDER_ITEMS)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "items")),
                new Document("$unwind", "$items"),
                new Document("$lookup", new Document("from", SHIPMENTS)
                        .append("localField", "items._id").append("foreignField", "item_id").append("as", "shipments")));
    }

    static String buildM2NChildJsonSql() {
        return "SELECT p.data, i.data, s.data FROM " + PRODUCTS + " p " +
                "JOIN " + ORDER_ITEMS + " i ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(i.data, '$.product_id') " +
                "LEFT JOIN " + SHIPMENTS + " s ON JSON_VALUE(i.data, '$._id') = JSON_VALUE(s.data, '$.item_id')";
    }

    static String buildM2NChildRelSql() {
        return "SELECT p.product_id, p.name, i.item_id, i.quantity, s.shipment_id, s.status FROM " + PRODUCTS_REL + " p " +
                "JOIN " + ORDER_ITEMS_REL + " i ON p.product_id = i.product_id " +
                "LEFT JOIN " + SHIPMENTS_REL + " s ON i.item_id = s.item_id";
    }

    static List<Bson> buildM2NChildAggregatePipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", ORDER_ITEMS)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "items")),
                new Document("$unwind", "$items"),
                new Document("$lookup", new Document("from", SHIPMENTS)
                        .append("localField", "items._id").append("foreignField", "item_id").append("as", "shipments")),
                new Document("$group", new Document("_id", "$_id")
                        .append("name", new Document("$first", "$name"))
                        .append("item_count", new Document("$sum", 1))
                        .append("total_qty", new Document("$sum", "$items.quantity"))
                        .append("ship_count", new Document("$sum", new Document("$size", "$shipments")))));
    }

    static String buildM2NChildAggregateJsonSql() {
        return "SELECT JSON_VALUE(p.data, '$._id') AS product_id, JSON_VALUE(p.data, '$.name') AS product_name, " +
                "COUNT(DISTINCT JSON_VALUE(i.data, '$._id')) AS item_count, " +
                "SUM(JSON_VALUE(i.data, '$.quantity' RETURNING NUMBER)) AS total_qty, " +
                "COUNT(DISTINCT JSON_VALUE(s.data, '$._id')) AS ship_count " +
                "FROM " + PRODUCTS + " p " +
                "JOIN " + ORDER_ITEMS + " i ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(i.data, '$.product_id') " +
                "LEFT JOIN " + SHIPMENTS + " s ON JSON_VALUE(i.data, '$._id') = JSON_VALUE(s.data, '$.item_id') " +
                "GROUP BY JSON_VALUE(p.data, '$._id'), JSON_VALUE(p.data, '$.name')";
    }

    static String buildM2NChildAggregateRelSql() {
        return "SELECT p.product_id, p.name, COUNT(DISTINCT i.item_id) AS item_count, " +
                "SUM(i.quantity) AS total_qty, COUNT(DISTINCT s.shipment_id) AS ship_count " +
                "FROM " + PRODUCTS_REL + " p " +
                "JOIN " + ORDER_ITEMS_REL + " i ON p.product_id = i.product_id " +
                "LEFT JOIN " + SHIPMENTS_REL + " s ON i.item_id = s.item_id " +
                "GROUP BY p.product_id, p.name";
    }

    // =========================================================================
    // Query Builders: X3 (M:N + Both 1:N)
    // =========================================================================

    static List<Bson> buildM2NBothLookupPipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", REVIEWS)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "reviews")),
                new Document("$lookup", new Document("from", PRODUCT_CATEGORIES)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "pc")),
                new Document("$unwind", "$pc"),
                new Document("$lookup", new Document("from", CATEGORIES_COLLECTION)
                        .append("localField", "pc.category_id").append("foreignField", "_id").append("as", "category")),
                new Document("$unwind", "$category"),
                new Document("$lookup", new Document("from", CATEGORY_RULES)
                        .append("localField", "category._id").append("foreignField", "category_id").append("as", "rules")));
    }

    static String buildM2NBothJsonSql() {
        return "SELECT p.data, r.data, c.data, cr.data FROM " + PRODUCTS + " p " +
                "LEFT JOIN " + REVIEWS + " r ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(r.data, '$.product_id') " +
                "JOIN " + PRODUCT_CATEGORIES + " pc ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(pc.data, '$.product_id') " +
                "JOIN " + CATEGORIES_COLLECTION + " c ON JSON_VALUE(pc.data, '$.category_id') = JSON_VALUE(c.data, '$._id') " +
                "LEFT JOIN " + CATEGORY_RULES + " cr ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(cr.data, '$.category_id')";
    }

    static String buildM2NBothRelSql() {
        return "SELECT p.product_id, p.name, r.review_id, r.rating, c.category_id, c.name AS cat_name, cr.rule_id, cr.rule_name " +
                "FROM " + PRODUCTS_REL + " p " +
                "LEFT JOIN " + REVIEWS_REL + " r ON p.product_id = r.product_id " +
                "JOIN " + PRODUCT_CATEGORIES_REL + " pc ON p.product_id = pc.product_id " +
                "JOIN " + CATEGORIES_REL_TABLE + " c ON pc.category_id = c.category_id " +
                "LEFT JOIN " + CATEGORY_RULES_REL + " cr ON c.category_id = cr.category_id";
    }

    static List<Bson> buildM2NBothAggregatePipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", PRODUCT_CATEGORIES)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "pc")),
                new Document("$unwind", "$pc"),
                new Document("$lookup", new Document("from", REVIEWS)
                        .append("localField", "_id").append("foreignField", "product_id").append("as", "reviews")),
                new Document("$unwind", "$reviews"),
                new Document("$group", new Document("_id", "$pc.category_id")
                        .append("product_count", new Document("$addToSet", "$_id"))
                        .append("avg_rating", new Document("$avg", "$reviews.rating"))
                        .append("review_count", new Document("$sum", 1))),
                new Document("$addFields", new Document("product_count", new Document("$size", "$product_count"))));
    }

    static String buildM2NBothAggregateJsonSql() {
        return "SELECT JSON_VALUE(c.data, '$._id') AS category_id, JSON_VALUE(c.data, '$.name') AS cat_name, " +
                "COUNT(DISTINCT JSON_VALUE(p.data, '$._id')) AS product_count, " +
                "AVG(JSON_VALUE(r.data, '$.rating' RETURNING NUMBER)) AS avg_rating, " +
                "COUNT(DISTINCT JSON_VALUE(r.data, '$._id')) AS review_count " +
                "FROM " + CATEGORIES_COLLECTION + " c " +
                "JOIN " + PRODUCT_CATEGORIES + " pc ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(pc.data, '$.category_id') " +
                "JOIN " + PRODUCTS + " p ON JSON_VALUE(pc.data, '$.product_id') = JSON_VALUE(p.data, '$._id') " +
                "LEFT JOIN " + REVIEWS + " r ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(r.data, '$.product_id') " +
                "GROUP BY JSON_VALUE(c.data, '$._id'), JSON_VALUE(c.data, '$.name')";
    }

    static String buildM2NBothAggregateRelSql() {
        return "SELECT c.category_id, c.name AS cat_name, COUNT(DISTINCT p.product_id) AS product_count, " +
                "AVG(r.rating) AS avg_rating, COUNT(DISTINCT r.review_id) AS review_count " +
                "FROM " + CATEGORIES_REL_TABLE + " c " +
                "JOIN " + PRODUCT_CATEGORIES_REL + " pc ON c.category_id = pc.category_id " +
                "JOIN " + PRODUCTS_REL + " p ON pc.product_id = p.product_id " +
                "LEFT JOIN " + REVIEWS_REL + " r ON p.product_id = r.product_id " +
                "GROUP BY c.category_id, c.name";
    }

    static List<Bson> buildM2NBothFilteredPipeline() {
        List<Bson> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("price", new Document("$gt", 50))));
        pipeline.addAll(buildM2NBothLookupPipeline());
        return pipeline;
    }

    static String buildM2NBothFilteredJsonSql() {
        return "SELECT p.data, r.data, c.data, cr.data FROM " + PRODUCTS + " p " +
                "LEFT JOIN " + REVIEWS + " r ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(r.data, '$.product_id') " +
                "JOIN " + PRODUCT_CATEGORIES + " pc ON JSON_VALUE(p.data, '$._id') = JSON_VALUE(pc.data, '$.product_id') " +
                "JOIN " + CATEGORIES_COLLECTION + " c ON JSON_VALUE(pc.data, '$.category_id') = JSON_VALUE(c.data, '$._id') " +
                "LEFT JOIN " + CATEGORY_RULES + " cr ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(cr.data, '$.category_id') " +
                "WHERE JSON_VALUE(p.data, '$.price' RETURNING NUMBER) > 50";
    }

    static String buildM2NBothFilteredRelSql() {
        return "SELECT p.product_id, p.name, r.review_id, r.rating, c.category_id, c.name AS cat_name, cr.rule_id, cr.rule_name " +
                "FROM " + PRODUCTS_REL + " p " +
                "LEFT JOIN " + REVIEWS_REL + " r ON p.product_id = r.product_id " +
                "JOIN " + PRODUCT_CATEGORIES_REL + " pc ON p.product_id = pc.product_id " +
                "JOIN " + CATEGORIES_REL_TABLE + " c ON pc.category_id = c.category_id " +
                "LEFT JOIN " + CATEGORY_RULES_REL + " cr ON c.category_id = cr.category_id " +
                "WHERE p.price > 50";
    }

    // =========================================================================
    // Query Builders: X5 (Diamond)
    // =========================================================================

    static List<Bson> buildDiamondLookupPipeline() {
        return Arrays.asList(
                new Document("$lookup", new Document("from", CUSTOMERS)
                        .append("localField", "customer_id").append("foreignField", "_id").append("as", "customer")),
                new Document("$unwind", "$customer"),
                new Document("$lookup", new Document("from", REGIONS)
                        .append("localField", "customer.region_id").append("foreignField", "_id").append("as", "cust_region")),
                new Document("$lookup", new Document("from", ORDER_ITEMS)
                        .append("localField", "_id").append("foreignField", "order_id").append("as", "items")),
                new Document("$unwind", "$items"),
                new Document("$lookup", new Document("from", PRODUCTS)
                        .append("localField", "items.product_id").append("foreignField", "_id").append("as", "product")),
                new Document("$unwind", "$product"),
                new Document("$lookup", new Document("from", SUPPLIERS)
                        .append("localField", "product.supplier_id").append("foreignField", "_id").append("as", "supplier")),
                new Document("$unwind", "$supplier"),
                new Document("$lookup", new Document("from", REGIONS)
                        .append("localField", "supplier.region_id").append("foreignField", "_id").append("as", "supp_region")));
    }

    static String buildDiamondJsonSql() {
        return "SELECT o.data, c.data, cr.data, i.data, p.data, s.data, sr.data " +
                "FROM " + ORDERS + " o " +
                "JOIN " + CUSTOMERS + " c ON JSON_VALUE(o.data, '$.customer_id') = JSON_VALUE(c.data, '$._id') " +
                "JOIN " + REGIONS + " cr ON JSON_VALUE(c.data, '$.region_id') = JSON_VALUE(cr.data, '$._id') " +
                "JOIN " + ORDER_ITEMS + " i ON JSON_VALUE(o.data, '$._id') = JSON_VALUE(i.data, '$.order_id') " +
                "JOIN " + PRODUCTS + " p ON JSON_VALUE(i.data, '$.product_id') = JSON_VALUE(p.data, '$._id') " +
                "JOIN " + SUPPLIERS + " s ON JSON_VALUE(p.data, '$.supplier_id') = JSON_VALUE(s.data, '$._id') " +
                "JOIN " + REGIONS + " sr ON JSON_VALUE(s.data, '$.region_id') = JSON_VALUE(sr.data, '$._id')";
    }

    static String buildDiamondRelSql() {
        return "SELECT o.order_id, c.customer_id, cr.name AS cust_region, i.item_id, p.product_id, s.supplier_id, sr.name AS supp_region " +
                "FROM " + ORDERS_REL + " o " +
                "JOIN " + CUSTOMERS_REL + " c ON o.customer_id = c.customer_id " +
                "JOIN " + REGIONS_REL + " cr ON c.region_id = cr.region_id " +
                "JOIN " + ORDER_ITEMS_REL + " i ON o.order_id = i.order_id " +
                "JOIN " + PRODUCTS_REL + " p ON i.product_id = p.product_id " +
                "JOIN " + SUPPLIERS_REL + " s ON p.supplier_id = s.supplier_id " +
                "JOIN " + REGIONS_REL + " sr ON s.region_id = sr.region_id";
    }

    static List<Bson> buildDiamondAggregatePipeline() {
        List<Bson> pipeline = new ArrayList<>(buildDiamondLookupPipeline());
        pipeline.add(new Document("$unwind", new Document("path", "$cust_region").append("preserveNullAndEmptyArrays", true)));
        pipeline.add(new Document("$group", new Document("_id", new Document("cust_region", "$cust_region.name"))
                .append("order_count", new Document("$addToSet", "$_id"))
                .append("total_items", new Document("$sum", 1))
                .append("total_value", new Document("$sum", "$items.unit_price"))));
        pipeline.add(new Document("$addFields", new Document("order_count", new Document("$size", "$order_count"))));
        return pipeline;
    }

    static String buildDiamondAggregateJsonSql() {
        return "SELECT JSON_VALUE(cr.data, '$.name') AS region_name, " +
                "COUNT(DISTINCT JSON_VALUE(o.data, '$._id')) AS order_count, " +
                "COUNT(JSON_VALUE(i.data, '$._id')) AS total_items, " +
                "SUM(JSON_VALUE(i.data, '$.unit_price' RETURNING NUMBER)) AS total_value " +
                "FROM " + ORDERS + " o " +
                "JOIN " + CUSTOMERS + " c ON JSON_VALUE(o.data, '$.customer_id') = JSON_VALUE(c.data, '$._id') " +
                "JOIN " + REGIONS + " cr ON JSON_VALUE(c.data, '$.region_id') = JSON_VALUE(cr.data, '$._id') " +
                "JOIN " + ORDER_ITEMS + " i ON JSON_VALUE(o.data, '$._id') = JSON_VALUE(i.data, '$.order_id') " +
                "GROUP BY JSON_VALUE(cr.data, '$.name')";
    }

    static String buildDiamondAggregateRelSql() {
        return "SELECT cr.name AS region_name, COUNT(DISTINCT o.order_id) AS order_count, " +
                "COUNT(i.item_id) AS total_items, SUM(i.unit_price) AS total_value " +
                "FROM " + ORDERS_REL + " o " +
                "JOIN " + CUSTOMERS_REL + " c ON o.customer_id = c.customer_id " +
                "JOIN " + REGIONS_REL + " cr ON c.region_id = cr.region_id " +
                "JOIN " + ORDER_ITEMS_REL + " i ON o.order_id = i.order_id " +
                "GROUP BY cr.name";
    }

    static List<Bson> buildDiamondFilteredPipeline() {
        List<Bson> pipeline = new ArrayList<>(buildDiamondLookupPipeline());
        pipeline.add(0, new Document("$lookup", new Document("from", ORDER_ITEMS)
                .append("localField", "_id").append("foreignField", "order_id").append("as", "_prefilter_items")));
        // Use the base pipeline but we filter later
        return Arrays.asList(
                new Document("$lookup", new Document("from", CUSTOMERS)
                        .append("localField", "customer_id").append("foreignField", "_id").append("as", "customer")),
                new Document("$unwind", "$customer"),
                new Document("$lookup", new Document("from", REGIONS)
                        .append("localField", "customer.region_id").append("foreignField", "_id").append("as", "cust_region")),
                new Document("$lookup", new Document("from", ORDER_ITEMS)
                        .append("localField", "_id").append("foreignField", "order_id").append("as", "items")),
                new Document("$unwind", "$items"),
                new Document("$lookup", new Document("from", PRODUCTS)
                        .append("localField", "items.product_id").append("foreignField", "_id").append("as", "product")),
                new Document("$unwind", "$product"),
                new Document("$match", new Document("product.price", new Document("$gt", 50))),
                new Document("$lookup", new Document("from", SUPPLIERS)
                        .append("localField", "product.supplier_id").append("foreignField", "_id").append("as", "supplier")),
                new Document("$unwind", "$supplier"),
                new Document("$lookup", new Document("from", REGIONS)
                        .append("localField", "supplier.region_id").append("foreignField", "_id").append("as", "supp_region")));
    }

    static String buildDiamondFilteredJsonSql() {
        return "SELECT o.data, c.data, cr.data, i.data, p.data, s.data, sr.data " +
                "FROM " + ORDERS + " o " +
                "JOIN " + CUSTOMERS + " c ON JSON_VALUE(o.data, '$.customer_id') = JSON_VALUE(c.data, '$._id') " +
                "JOIN " + REGIONS + " cr ON JSON_VALUE(c.data, '$.region_id') = JSON_VALUE(cr.data, '$._id') " +
                "JOIN " + ORDER_ITEMS + " i ON JSON_VALUE(o.data, '$._id') = JSON_VALUE(i.data, '$.order_id') " +
                "JOIN " + PRODUCTS + " p ON JSON_VALUE(i.data, '$.product_id') = JSON_VALUE(p.data, '$._id') " +
                "JOIN " + SUPPLIERS + " s ON JSON_VALUE(p.data, '$.supplier_id') = JSON_VALUE(s.data, '$._id') " +
                "JOIN " + REGIONS + " sr ON JSON_VALUE(s.data, '$.region_id') = JSON_VALUE(sr.data, '$._id') " +
                "WHERE JSON_VALUE(p.data, '$.price' RETURNING NUMBER) > 50";
    }

    static String buildDiamondFilteredRelSql() {
        return "SELECT o.order_id, c.customer_id, cr.name AS cust_region, i.item_id, p.product_id, s.supplier_id, sr.name AS supp_region " +
                "FROM " + ORDERS_REL + " o " +
                "JOIN " + CUSTOMERS_REL + " c ON o.customer_id = c.customer_id " +
                "JOIN " + REGIONS_REL + " cr ON c.region_id = cr.region_id " +
                "JOIN " + ORDER_ITEMS_REL + " i ON o.order_id = i.order_id " +
                "JOIN " + PRODUCTS_REL + " p ON i.product_id = p.product_id " +
                "JOIN " + SUPPLIERS_REL + " s ON p.supplier_id = s.supplier_id " +
                "JOIN " + REGIONS_REL + " sr ON s.region_id = sr.region_id " +
                "WHERE p.price > 50";
    }

    // =========================================================================
    // AWR Snapshot Methods
    // =========================================================================

    private static void ensureAwrGrants() {
        String sysPassword = System.getenv().getOrDefault("ORACLE_PASSWORD", "oracle");
        try {
            Properties sysProps = new Properties();
            sysProps.setProperty("user", "sys");
            sysProps.setProperty("password", sysPassword);
            sysProps.setProperty("internal_logon", "SYSDBA");

            try (Connection sysConn = DriverManager.getConnection(oracleUrl, sysProps);
                 Statement stmt = sysConn.createStatement()) {
                String[] grants = {
                    "GRANT SELECT ON V_$DATABASE TO " + oracleUsername,
                    "GRANT SELECT ON V_$INSTANCE TO " + oracleUsername,
                    "GRANT SELECT ON V_$SQL_MONITOR TO " + oracleUsername,
                    "GRANT SELECT ON GV_$SQL_MONITOR TO " + oracleUsername,
                    "GRANT SELECT ON V_$SQL TO " + oracleUsername,
                    "GRANT EXECUTE ON DBMS_WORKLOAD_REPOSITORY TO " + oracleUsername,
                    "GRANT EXECUTE ON DBMS_SQLTUNE TO " + oracleUsername,
                    "GRANT ADVISOR TO " + oracleUsername
                };
                for (String grant : grants) {
                    try { stmt.execute(grant); } catch (SQLException ignored) {}
                }
                System.out.println("  AWR/SQL Monitor grants applied via SYS");
            }
        } catch (Exception e) {
            System.out.println("  Could not apply AWR grants via SYS: " + e.getMessage());
        }
    }

    private static void initializeAwr() {
        try {
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con_dbid, instance_number FROM v$database, v$instance")) {
                if (rs.next()) {
                    dbId = rs.getLong(1);
                    instanceNumber = rs.getLong(2);
                    awrEnabled = true;
                    System.out.println("  AWR enabled - CON_DBID: " + dbId + ", Instance: " + instanceNumber);

                    Files.createDirectories(Path.of(AWR_REPORT_DIR));
                }
            }
        } catch (Exception e) {
            // V$ views not accessible - try granting privileges via SYS
            ensureAwrGrants();

            // Retry after grants
            try {
                try (Statement stmt = oracleJdbcConnection.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT con_dbid, instance_number FROM v$database, v$instance")) {
                    if (rs.next()) {
                        dbId = rs.getLong(1);
                        instanceNumber = rs.getLong(2);
                        awrEnabled = true;
                        System.out.println("  AWR enabled - CON_DBID: " + dbId + ", Instance: " + instanceNumber);

                        Files.createDirectories(Path.of(AWR_REPORT_DIR));
                    }
                }
            } catch (Exception retryEx) {
                System.out.println("  AWR not available: " + retryEx.getMessage());
                awrEnabled = false;
            }
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

    private static long createAwrSnapshot(String description) {
        if (!awrEnabled) return -1;

        try (CallableStatement cs = oracleJdbcConnection.prepareCall(
                "{ ? = call DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT() }")) {
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.execute();
            long snapId = cs.getLong(1);
            System.out.println("    AWR Snapshot " + snapId + " created: " + description);
            return snapId;
        } catch (SQLException e) {
            System.out.println("    AWR snapshot failed: " + e.getMessage());
            return -1;
        }
    }

    private static void generateAwrReports() {
        if (!awrEnabled || awrSnapshots.isEmpty()) {
            System.out.println("\n  No AWR snapshots to report.");
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
                String safeName = "awr_" + category.replaceAll("[^a-zA-Z0-9]", "_") + ".html";
                String filename = AWR_REPORT_DIR + "/" + safeName;
                String awrHtml = generateAwrHtmlReport(snaps[0], snaps[1], filename);
                if (!awrHtml.isEmpty()) {
                    // Store relative path from reports/ directory (where the main report lives)
                    awrReportFiles.put(category, "awr/complex_join/" + safeName);
                    System.out.println("  Generated: " + filename + " (snaps " + snaps[0] + " - " + snaps[1] + ")");
                }
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

    // =========================================================================
    // Diagnostic Capture Methods
    // =========================================================================

    private static String captureExplainPlan(String sql) {
        if (sql == null || sql.isEmpty()) return "";
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

    private static String captureSqlMonitorHtmlWithSqlId(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        StringBuilder report = new StringBuilder();

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Execute with MONITOR hint to force SQL monitoring
            String monitoredSql;
            if (sql.matches("(?is).*SELECT\\s+/\\*\\+.*\\*/.*")) {
                monitoredSql = sql.replaceFirst("(?i)/\\*\\+\\s*", "/*+ MONITOR ");
            } else {
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
            } catch (SQLException e) {
                // V$SQL_MONITOR may not be accessible - try GV$SQL_MONITOR
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT SQL_ID FROM GV$SQL_MONITOR WHERE SID = SYS_CONTEXT('USERENV', 'SID') " +
                        "AND SQL_TEXT LIKE '%MONITOR%' ORDER BY SQL_EXEC_START DESC FETCH FIRST 1 ROW ONLY")) {
                    if (rs.next()) {
                        sqlId = rs.getString(1);
                    }
                } catch (SQLException ignored) {
                    // GV$ also not available
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
                } catch (SQLException ignored) {
                    // V$SQL_MONITOR not accessible
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
                report.append("<p>SQL Monitor: V$SQL_MONITOR not accessible (").append(escapeHtml(msg)).append(")</p>");
            } else {
                report.append("<p>Could not capture SQL Monitor: ").append(escapeHtml(msg)).append("</p>");
            }
        }
        return report.toString();
    }

    private static String writeSqlMonitorFile(String testId, String protocol, String content) {
        if (content == null || content.isEmpty()
                || content.startsWith("<p>")
                || content.contains("Could not find SQL_ID")
                || content.contains("not available")
                || content.contains("not accessible")) {
            return "";
        }
        try {
            Path dir = Path.of(SQL_MONITOR_DIR);
            Files.createDirectories(dir);
            String safeTestId = testId.replaceAll("[^a-zA-Z0-9_]", "_");
            String filename = "monitor_" + safeTestId + "_" + protocol + ".html";
            Path filePath = dir.resolve(filename);
            Files.writeString(filePath, content);
            return "sql_monitor/complex_join/" + filename;
        } catch (IOException e) {
            System.err.println("Failed to write SQL Monitor file: " + e.getMessage());
            return "";
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String captureMongoExplain(MongoCollection<Document> collection, List<Bson> pipeline) {
        if (skipMongoDB || collection == null || pipeline == null) return "";
        try {
            Document explainDoc = collection.aggregate(pipeline).explain(ExplainVerbosity.EXECUTION_STATS);
            JsonWriterSettings settings = JsonWriterSettings.builder().indent(true).build();
            return explainDoc.toJson(settings);
        } catch (Exception e) {
            return "Could not capture MongoDB explain: " + e.getMessage();
        }
    }

    private static String pipelineToJson(List<Bson> pipeline) {
        if (pipeline == null || pipeline.isEmpty()) return "[]";
        try {
            JsonWriterSettings settings = JsonWriterSettings.builder().indent(true).build();
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < pipeline.size(); i++) {
                BsonDocument bsonDoc = pipeline.get(i).toBsonDocument(BsonDocument.class, mongoDatabase.getCodecRegistry());
                sb.append("  ").append(bsonDoc.toJson(settings));
                if (i < pipeline.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "Could not convert pipeline: " + e.getMessage();
        }
    }

    private static void captureDiagnostics(String testId, MongoCollection<Document> collection,
                                            List<Bson> pipeline, String oracleASql, String oracleBSql) {
        String pipelineJson = pipelineToJson(pipeline);
        String mongoExplain = captureMongoExplain(collection, pipeline);
        String oracleAPlan = captureExplainPlan(oracleASql);
        String oracleBPlan = captureExplainPlan(oracleBSql);

        String monitorAFile = "";
        String monitorBFile = "";
        if (oracleASql != null && !oracleASql.isEmpty()) {
            String content = captureSqlMonitorHtmlWithSqlId(oracleASql);
            monitorAFile = writeSqlMonitorFile(testId, "oracleA", content);
        }
        if (oracleBSql != null && !oracleBSql.isEmpty()) {
            String content = captureSqlMonitorHtmlWithSqlId(oracleBSql);
            monitorBFile = writeSqlMonitorFile(testId, "oracleB", content);
        }

        diagnosticDataMap.put(testId, new DiagnosticData(
                pipelineJson, mongoExplain, oracleASql, oracleBSql,
                oracleAPlan, oracleBPlan, monitorAFile, monitorBFile
        ));
    }

    // =========================================================================
    // Result Storage & Reporting
    // =========================================================================

    /** Store X4 (self-referential) result with CTE/Property Graph labels. */
    private void storeResult(String testId, String description, String category,
                             long mongoNanos, long oracleCteNanos, long oracleGraphNanos,
                             String notes) {
        results.put(testId, new TestResult(testId, description, mongoNanos, oracleCteNanos, oracleGraphNanos,
                category, notes, LABEL_GRAPH_LOOKUP, LABEL_CTE, LABEL_PROP_GRAPH));
    }

    private void printTripleResult(String label, long mongoNanos, long oracleCteNanos, long oracleGraphNanos) {
        String mongoStr = mongoNanos >= 0 ? String.format("%,15d", mongoNanos) : "        SKIPPED";
        String graphStr = oracleGraphNanos >= 0 ? String.format("%,15d", oracleGraphNanos) : "            N/A";
        System.out.printf("  %-25s $graphLookup: %s ns | Recursive CTE: %,15d ns | Property Graph: %s ns%n",
                label, mongoStr, oracleCteNanos, graphStr);
    }

    private static void printFinalReport() {
        System.out.println("\n" + "=".repeat(130));
        System.out.println("  COMPLEX JOIN BENCHMARK RESULTS: Category X (All Subcategories)");
        System.out.println("=".repeat(130));

        // Group results by category
        Map<String, List<TestResult>> grouped = new LinkedHashMap<>();
        for (TestResult r : results.values()) {
            grouped.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
        }

        int mongoWins = 0, oracleAWins = 0, oracleBWins = 0;
        for (var entry : grouped.entrySet()) {
            List<TestResult> catResults = entry.getValue();
            if (catResults.isEmpty()) continue;
            TestResult first = catResults.get(0);
            System.out.printf("%n  %-20s  %-15s  %-15s  %-15s%n",
                    entry.getKey().toUpperCase(), first.mongoLabel(), first.oracleALabel(), first.oracleBLabel());
            System.out.println("  " + "-".repeat(70));

            for (TestResult r : catResults) {
                String mongoStr = r.mongoNanos() >= 0 ? String.format("%,12d", r.mongoNanos()) : "     SKIPPED";
                String aStr = r.oracleANanos() >= 0 ? String.format("%,12d", r.oracleANanos()) : "         N/A";
                String bStr = r.oracleBNanos() >= 0 ? String.format("%,12d", r.oracleBNanos()) : "         N/A";
                System.out.printf("  %-20s  %s ns  %s ns  %s ns%n", r.testId(), mongoStr, aStr, bStr);

                // Count wins
                long best = Long.MAX_VALUE;
                int winIdx = -1;
                if (r.mongoNanos() > 0 && r.mongoNanos() < best) { best = r.mongoNanos(); winIdx = 0; }
                if (r.oracleANanos() > 0 && r.oracleANanos() < best) { best = r.oracleANanos(); winIdx = 1; }
                if (r.oracleBNanos() > 0 && r.oracleBNanos() < best) { winIdx = 2; }
                switch (winIdx) { case 0 -> mongoWins++; case 1 -> oracleAWins++; case 2 -> oracleBWins++; }
            }
        }

        System.out.println("\n" + "=".repeat(130));
        System.out.println("  Overall Wins: MongoDB=" + mongoWins + ", Oracle A=" + oracleAWins +
                ", Oracle B=" + oracleBWins + "  (out of " + results.size() + " tests)");
        System.out.println("=".repeat(130));
    }

    // =========================================================================
    // HTML Report Generation
    // =========================================================================

    private static final String REPORT_FILE = "reports/complex_join_report.html";

    private static void generateHtmlReport() {
        if (results.isEmpty()) {
            System.out.println("  [Report] No results to generate report from");
            return;
        }

        try {
            Path reportDir = Path.of("reports");
            Files.createDirectories(reportDir);

            Map<String, List<TestResult>> grouped = new LinkedHashMap<>();
            for (TestResult r : results.values()) {
                grouped.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
            }

            int mongoWins = 0, oracleAWins = 0, oracleBWins = 0;
            for (TestResult r : results.values()) {
                long best = Long.MAX_VALUE;
                int winIdx = -1;
                if (r.mongoNanos() > 0 && r.mongoNanos() < best) { best = r.mongoNanos(); winIdx = 0; }
                if (r.oracleANanos() > 0 && r.oracleANanos() < best) { best = r.oracleANanos(); winIdx = 1; }
                if (r.oracleBNanos() > 0 && r.oracleBNanos() < best) { winIdx = 2; }
                switch (winIdx) { case 0 -> mongoWins++; case 1 -> oracleAWins++; case 2 -> oracleBWins++; }
            }

            // Category display names and descriptions
            Map<String, String> catNames = Map.of(
                    "m2m", "X0: Many-to-Many", "hier", "X1: Hierarchical 1:N",
                    "m2n_child", "X2: M:N + Child 1:N", "m2n_both", "X3: M:N + Both 1:N",
                    "selfref", "X4: Self-Referential", "diamond", "X5: Diamond Patterns");
            Map<String, String> catDescs = Map.of(
                    "m2m", "Products ↔ Categories via junction table. MongoDB uses two <code>$lookup</code> stages with <code>$unwind</code>. Oracle uses 3-table JOIN.",
                    "hier", "Customer → Orders → Items → Shipments chain. MongoDB uses nested <code>$lookup</code>. Oracle uses multi-table JOIN.",
                    "m2n_child", "Products → OrderItems → Shipments. M:N via items with 1:N shipments on child side.",
                    "m2n_both", "Products(Reviews) ↔ Categories(Rules). M:N with 1:N on both sides of the junction.",
                    "selfref", "Hierarchical category tree. MongoDB <code>$graphLookup</code> vs Oracle Recursive CTE vs SQL Property Graph.",
                    "diamond", "Orders → (Products, Customers) → Regions. 7-table diamond join with converging paths.");

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html>\n<head>\n");
            html.append("    <title>Complex Join Benchmark: Category X (Order 90-119)</title>\n");
            html.append("    <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n");
            html.append("    <style>\n");
            html.append("        :root { --bg-primary: #0f0f23; --bg-secondary: #1a1a3e; --accent: #00d4ff; --accent-glow: rgba(0, 212, 255, 0.6); --text: #eee; --text-muted: #888; --text-dim: #555; --border: rgba(255,255,255,0.08); --border-light: rgba(255,255,255,0.1); --card-bg: rgba(255, 255, 255, 0.05); --green: #4ade80; --blue: #3b82f6; --amber: #f59e0b; }\n");
            html.append("        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 0; padding: 20px; background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%); min-height: 100vh; color: var(--text); }\n");
            html.append("        .container { max-width: 1400px; margin: 0 auto; }\n");
            html.append("        h1 { text-align: center; color: var(--accent); font-size: 2.4em; margin-bottom: 5px; text-shadow: 0 0 30px var(--accent-glow); }\n");
            html.append("        .subtitle { text-align: center; color: var(--text-muted); margin-bottom: 30px; font-size: 1.1em; }\n");
            html.append("        .summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-bottom: 40px; }\n");
            html.append("        .summary-card { background: var(--card-bg); border-radius: 16px; padding: 25px; text-align: center; border: 1px solid var(--border-light); }\n");
            html.append("        .summary-card h3 { margin: 0 0 15px 0; font-size: 1em; color: var(--text-muted); }\n");
            html.append("        .summary-card .value { font-size: 2.2em; font-weight: bold; }\n");
            html.append("        .mongo-color { color: var(--green); } .ora-color { color: var(--blue); } .orb-color { color: var(--amber); }\n");
            html.append("        .chart-section { background: rgba(255, 255, 255, 0.03); border-radius: 20px; padding: 35px; margin-bottom: 35px; border: 1px solid var(--border); }\n");
            html.append("        .chart-title { color: var(--accent); font-size: 1.5em; margin-bottom: 25px; padding-bottom: 15px; border-bottom: 2px solid rgba(0, 212, 255, 0.3); }\n");
            html.append("        table { border-collapse: collapse; width: 100%; margin: 25px 0; background: rgba(0,0,0,0.3); border-radius: 12px; overflow: hidden; }\n");
            html.append("        th, td { border: 1px solid var(--border); padding: 14px 12px; text-align: right; }\n");
            html.append("        th { background: rgba(0, 212, 255, 0.15); color: var(--accent); font-weight: 600; }\n");
            html.append("        td:first-child { text-align: left; font-weight: 500; }\n");
            html.append("        tr:nth-child(even) { background: rgba(255,255,255,0.02); } tr:hover { background: rgba(0, 212, 255, 0.08); }\n");
            html.append("        .winner { font-weight: bold; text-shadow: 0 0 10px currentColor; }\n");
            html.append("        .winner-mongo { color: var(--green); } .winner-ora { color: var(--blue); } .winner-orb { color: var(--amber); }\n");
            html.append("        .na { color: var(--text-dim); }\n");
            html.append("        .legend-box { display: flex; justify-content: center; gap: 40px; margin: 20px 0; padding: 15px; background: rgba(0,0,0,0.2); border-radius: 10px; }\n");
            html.append("        .legend-item { display: flex; align-items: center; gap: 10px; }\n");
            html.append("        .legend-dot { width: 16px; height: 16px; border-radius: 50%; }\n");
            html.append("        .footer { text-align: center; margin-top: 50px; color: var(--text-dim); font-size: 0.9em; padding: 20px; border-top: 1px solid var(--border-light); }\n");
            html.append("        .category-desc { color: #aaa; font-size: 0.95em; line-height: 1.6; margin-bottom: 20px; padding: 15px 20px; background: rgba(0,212,255,0.05); border-left: 3px solid rgba(0,212,255,0.3); border-radius: 0 8px 8px 0; }\n");
            html.append("        .category-desc code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 4px; font-family: 'Fira Code', 'Consolas', monospace; color: var(--accent); }\n");
            html.append("        .tabs { display: flex; gap: 5px; margin-bottom: 20px; border-bottom: 2px solid var(--border-light); padding-bottom: 10px; flex-wrap: wrap; }\n");
            html.append("        .tab-btn { padding: 10px 20px; background: var(--card-bg); border: 1px solid var(--border-light); border-radius: 8px 8px 0 0; color: var(--text-muted); cursor: pointer; transition: all 0.2s; font-size: 0.9em; }\n");
            html.append("        .tab-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }\n");
            html.append("        .tab-btn.active { background: rgba(0, 212, 255, 0.2); border-color: rgba(0, 212, 255, 0.4); color: var(--accent); }\n");
            html.append("        .tab-content { display: none; } .tab-content.active { display: block; }\n");
            html.append("        .subtabs { display: flex; gap: 5px; margin-bottom: 15px; flex-wrap: wrap; }\n");
            html.append("        .subtab-btn { padding: 6px 14px; background: var(--card-bg); border: 1px solid rgba(255,255,255,0.15); border-radius: 6px; color: var(--text-muted); cursor: pointer; transition: all 0.2s; font-size: 0.85em; }\n");
            html.append("        .subtab-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }\n");
            html.append("        .subtab-btn.active { background: rgba(59, 130, 246, 0.3); border-color: rgba(59, 130, 246, 0.5); color: var(--blue); }\n");
            html.append("        .subtab-content { display: none; } .subtab-content.active { display: block; }\n");
            html.append("        .protocol-btn { font-weight: 600; padding: 8px 16px; } .protocol-btn.active { color: #fff !important; }\n");
            html.append("        .protocol-content { display: none; padding: 10px 0; } .protocol-content.active { display: block; }\n");
            html.append("        .test-btn { padding: 4px 10px; font-size: 0.8em; }\n");
            html.append("        .test-content { display: none; margin-top: 10px; } .test-content.active { display: block; }\n");
            html.append("        pre { background: rgba(0,0,0,0.4); border: 1px solid var(--border); border-radius: 8px; padding: 16px; overflow-x: auto; font-family: 'Fira Code', 'Consolas', monospace; font-size: 0.85em; color: #ccc; white-space: pre-wrap; word-wrap: break-word; max-height: 500px; overflow-y: auto; }\n");
            html.append("        .diag-label { color: var(--accent); font-weight: 600; margin: 20px 0 8px 0; font-size: 0.95em; }\n");
            html.append("        .diag-test-header { color: var(--green); font-weight: 600; margin: 25px 0 10px 0; font-size: 1.05em; border-bottom: 1px solid rgba(74,222,128,0.3); padding-bottom: 5px; }\n");
            html.append("    </style>\n</head>\n<body>\n<div class=\"container\">\n");
            html.append("    <h1>Complex Join Benchmark: Category X</h1>\n");
            html.append("    <p class=\"subtitle\">MongoDB $lookup/$graphLookup vs Oracle JSON JOIN / Relational JOIN / CTE / Property Graph</p>\n");

            // Summary cards
            html.append("<div class=\"summary-grid\">\n");
            html.append("    <div class=\"summary-card\"><h3>MongoDB Wins</h3><div class=\"value mongo-color\">").append(mongoWins).append("</div></div>\n");
            html.append("    <div class=\"summary-card\"><h3>Oracle A Wins</h3><div class=\"value ora-color\">").append(oracleAWins).append("</div></div>\n");
            html.append("    <div class=\"summary-card\"><h3>Oracle B Wins</h3><div class=\"value orb-color\">").append(oracleBWins).append("</div></div>\n");
            html.append("</div>\n");

            // Per-category sections with tabs
            int chartIdx = 0;
            for (var entry : grouped.entrySet()) {
                String cat = entry.getKey();
                List<TestResult> catResults = entry.getValue();
                if (catResults.isEmpty()) continue;
                TestResult first = catResults.get(0);

                String catTitle = catNames.getOrDefault(cat, cat);
                String catDesc = catDescs.getOrDefault(cat, "");
                String sectionId = cat.replace("_", "");

                html.append("<div class=\"chart-section\">\n");
                html.append("    <div class=\"chart-title\">").append(catTitle).append("</div>\n");
                if (!catDesc.isEmpty()) {
                    html.append("    <div class=\"category-desc\">").append(catDesc).append("</div>\n");
                }

                // Tabs
                html.append("<div class=\"tabs\">\n");
                html.append("<button class=\"tab-btn active\" onclick=\"openTab(event, 'chart-tab-").append(sectionId).append("')\">Chart</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'mongo-tab-").append(sectionId).append("')\">MongoDB</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'oraA-tab-").append(sectionId).append("')\">").append(first.oracleALabel()).append(" SQL</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'oraB-tab-").append(sectionId).append("')\">").append(first.oracleBLabel()).append(" SQL</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'plan-tab-").append(sectionId).append("')\">Explain Plans</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'monitor-tab-").append(sectionId).append("')\">SQL Monitor</button>\n");
                html.append("<button class=\"tab-btn\" onclick=\"openTab(event, 'awr-tab-").append(sectionId).append("')\">AWR Report</button>\n");
                html.append("</div>\n");

                // ---- Tab 1: Chart ----
                html.append("<div id=\"chart-tab-").append(sectionId).append("\" class=\"tab-content active\">\n");

                // Legend for this category
                html.append("    <div class=\"legend-box\">\n");
                html.append("        <div class=\"legend-item\"><div class=\"legend-dot\" style=\"background: var(--green);\"></div><span>").append(first.mongoLabel()).append("</span></div>\n");
                html.append("        <div class=\"legend-item\"><div class=\"legend-dot\" style=\"background: var(--blue);\"></div><span>").append(first.oracleALabel()).append("</span></div>\n");
                html.append("        <div class=\"legend-item\"><div class=\"legend-dot\" style=\"background: var(--amber);\"></div><span>").append(first.oracleBLabel()).append("</span></div>\n");
                html.append("    </div>\n");

                // Chart
                String chartId = "chart_" + chartIdx;
                html.append("    <canvas id=\"").append(chartId).append("\" height=\"80\"></canvas>\n");

                // Results table
                html.append("    <table>\n        <tr><th style='text-align:left'>Test</th><th>Notes</th>");
                html.append("<th>").append(first.mongoLabel()).append(" (ms)</th>");
                html.append("<th>").append(first.oracleALabel()).append(" (ms)</th>");
                html.append("<th>").append(first.oracleBLabel()).append(" (ms)</th>");
                html.append("<th>Winner</th></tr>\n");

                for (TestResult r : catResults) {
                    long best = Long.MAX_VALUE;
                    int winIdx = -1;
                    if (r.mongoNanos() > 0 && r.mongoNanos() < best) { best = r.mongoNanos(); winIdx = 0; }
                    if (r.oracleANanos() > 0 && r.oracleANanos() < best) { best = r.oracleANanos(); winIdx = 1; }
                    if (r.oracleBNanos() > 0 && r.oracleBNanos() < best) { best = r.oracleBNanos(); winIdx = 2; }
                    String winner = winIdx == 0 ? r.mongoLabel() : winIdx == 1 ? r.oracleALabel() : r.oracleBLabel();
                    String winnerClass = winIdx == 0 ? "winner-mongo" : winIdx == 1 ? "winner-ora" : "winner-orb";

                    String mMs = r.mongoNanos() >= 0 ? String.format("%.2f", r.mongoNanos() / 1_000_000.0) : "SKIPPED";
                    String aMs = r.oracleANanos() >= 0 ? String.format("%.2f", r.oracleANanos() / 1_000_000.0) : "N/A";
                    String bMs = r.oracleBNanos() >= 0 ? String.format("%.2f", r.oracleBNanos() / 1_000_000.0) : "N/A";

                    String mClass = (winIdx == 0) ? "winner winner-mongo" : (r.mongoNanos() < 0 ? "na" : "");
                    String aClass = (winIdx == 1) ? "winner winner-ora" : (r.oracleANanos() < 0 ? "na" : "");
                    String bClass = (winIdx == 2) ? "winner winner-orb" : (r.oracleBNanos() < 0 ? "na" : "");

                    html.append("<tr><td>").append(r.testId()).append("</td>");
                    html.append("<td style='text-align:left;color:#888;font-size:0.9em'>").append(r.notes()).append("</td>");
                    html.append("<td class='").append(mClass).append("'>").append(mMs).append("</td>");
                    html.append("<td class='").append(aClass).append("'>").append(aMs).append("</td>");
                    html.append("<td class='").append(bClass).append("'>").append(bMs).append("</td>");
                    html.append("<td class='winner ").append(winnerClass).append("'>").append(winner).append("</td></tr>\n");
                }
                html.append("    </table>\n</div>\n"); // Close chart tab

                // ---- Tab 2: MongoDB ----
                html.append("<div id=\"mongo-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    html.append("<div class=\"diag-test-header\">").append(r.testId()).append(" &mdash; ").append(r.description()).append("</div>\n");
                    if (diag != null) {
                        html.append("<div class=\"diag-label\">Pipeline JSON</div>\n");
                        html.append("<pre>").append(escapeHtml(diag.pipelineJson())).append("</pre>\n");
                        html.append("<div class=\"diag-label\">Explain (Execution Stats)</div>\n");
                        html.append("<pre>").append(escapeHtml(diag.mongoExplain())).append("</pre>\n");
                    } else {
                        html.append("<p style='color:#555'>No diagnostic data captured</p>\n");
                    }
                }
                html.append("</div>\n");

                // ---- Tab 3: Oracle A SQL ----
                html.append("<div id=\"oraA-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    html.append("<div class=\"diag-test-header\">").append(r.testId()).append(" &mdash; ").append(first.oracleALabel()).append("</div>\n");
                    if (diag != null && diag.oracleASql() != null && !diag.oracleASql().isEmpty()) {
                        html.append("<pre>").append(escapeHtml(diag.oracleASql())).append("</pre>\n");
                    } else {
                        html.append("<p style='color:#555'>No SQL captured</p>\n");
                    }
                }
                html.append("</div>\n");

                // ---- Tab 4: Oracle B SQL ----
                html.append("<div id=\"oraB-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    html.append("<div class=\"diag-test-header\">").append(r.testId()).append(" &mdash; ").append(first.oracleBLabel()).append("</div>\n");
                    if (diag != null && diag.oracleBSql() != null && !diag.oracleBSql().isEmpty()) {
                        html.append("<pre>").append(escapeHtml(diag.oracleBSql())).append("</pre>\n");
                    } else {
                        html.append("<p style='color:#555'>No SQL captured</p>\n");
                    }
                }
                html.append("</div>\n");

                // ---- Tab 5: Explain Plans ----
                html.append("<div id=\"plan-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    html.append("<div class=\"diag-test-header\">").append(r.testId()).append("</div>\n");
                    if (diag != null) {
                        html.append("<div class=\"diag-label\">").append(first.oracleALabel()).append(" Plan</div>\n");
                        if (diag.oracleAPlan() != null && !diag.oracleAPlan().isEmpty()) {
                            html.append("<pre>").append(escapeHtml(diag.oracleAPlan())).append("</pre>\n");
                        } else {
                            html.append("<p style='color:#555'>No plan captured</p>\n");
                        }
                        html.append("<div class=\"diag-label\">").append(first.oracleBLabel()).append(" Plan</div>\n");
                        if (diag.oracleBPlan() != null && !diag.oracleBPlan().isEmpty()) {
                            html.append("<pre>").append(escapeHtml(diag.oracleBPlan())).append("</pre>\n");
                        } else {
                            html.append("<p style='color:#555'>No plan captured</p>\n");
                        }
                    } else {
                        html.append("<p style='color:#555'>No diagnostic data captured</p>\n");
                    }
                }
                html.append("</div>\n");

                // ---- Tab 6: SQL Monitor ----
                html.append("<div id=\"monitor-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                html.append("<div class=\"subtabs\">\n");
                html.append("<button class=\"subtab-btn protocol-btn active\" onclick=\"openProtocolTab(event, 'monitor-oraA-").append(sectionId).append("')\">").append(first.oracleALabel()).append("</button>\n");
                html.append("<button class=\"subtab-btn protocol-btn\" onclick=\"openProtocolTab(event, 'monitor-oraB-").append(sectionId).append("')\">").append(first.oracleBLabel()).append("</button>\n");
                html.append("</div>\n");

                // Oracle A protocol content
                html.append("<div id=\"monitor-oraA-").append(sectionId).append("\" class=\"protocol-content active\">\n");
                html.append("<div class=\"subtabs\">\n");
                boolean firstMonTest = true;
                for (TestResult r : catResults) {
                    String safeId = r.testId().replaceAll("[^a-zA-Z0-9]", "_");
                    html.append("<button class=\"subtab-btn test-btn").append(firstMonTest ? " active" : "").append("\" onclick=\"openTestTab(event, 'mon-A-").append(safeId).append("-").append(sectionId).append("')\">").append(r.testId()).append("</button>\n");
                    firstMonTest = false;
                }
                html.append("</div>\n");
                firstMonTest = true;
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    String safeId = r.testId().replaceAll("[^a-zA-Z0-9]", "_");
                    html.append("<div id=\"mon-A-").append(safeId).append("-").append(sectionId).append("\" class=\"test-content").append(firstMonTest ? " active" : "").append("\">\n");
                    if (diag != null && !diag.sqlMonitorAFile().isEmpty()) {
                        html.append("<p style=\"color: #888;\">").append(first.oracleALabel()).append(" SQL Monitor for ").append(r.testId());
                        html.append(" <a href=\"").append(diag.sqlMonitorAFile()).append("\" target=\"_blank\" style=\"color: #f97316; margin-left: 10px;\">[Open in new tab]</a></p>\n");
                        html.append("<iframe src=\"").append(diag.sqlMonitorAFile()).append("\" style=\"width: 100%; height: 700px; border: 1px solid var(--border-light); border-radius: 8px;\"></iframe>\n");
                    } else {
                        html.append("<p style='color:#555'>SQL Monitor not available for this test</p>\n");
                    }
                    html.append("</div>\n");
                    firstMonTest = false;
                }
                html.append("</div>\n");

                // Oracle B protocol content
                html.append("<div id=\"monitor-oraB-").append(sectionId).append("\" class=\"protocol-content\">\n");
                html.append("<div class=\"subtabs\">\n");
                firstMonTest = true;
                for (TestResult r : catResults) {
                    String safeId = r.testId().replaceAll("[^a-zA-Z0-9]", "_");
                    html.append("<button class=\"subtab-btn test-btn").append(firstMonTest ? " active" : "").append("\" onclick=\"openTestTab(event, 'mon-B-").append(safeId).append("-").append(sectionId).append("')\">").append(r.testId()).append("</button>\n");
                    firstMonTest = false;
                }
                html.append("</div>\n");
                firstMonTest = true;
                for (TestResult r : catResults) {
                    DiagnosticData diag = diagnosticDataMap.get(r.testId());
                    String safeId = r.testId().replaceAll("[^a-zA-Z0-9]", "_");
                    html.append("<div id=\"mon-B-").append(safeId).append("-").append(sectionId).append("\" class=\"test-content").append(firstMonTest ? " active" : "").append("\">\n");
                    if (diag != null && !diag.sqlMonitorBFile().isEmpty()) {
                        html.append("<p style=\"color: #888;\">").append(first.oracleBLabel()).append(" SQL Monitor for ").append(r.testId());
                        html.append(" <a href=\"").append(diag.sqlMonitorBFile()).append("\" target=\"_blank\" style=\"color: #f97316; margin-left: 10px;\">[Open in new tab]</a></p>\n");
                        html.append("<iframe src=\"").append(diag.sqlMonitorBFile()).append("\" style=\"width: 100%; height: 700px; border: 1px solid var(--border-light); border-radius: 8px;\"></iframe>\n");
                    } else {
                        html.append("<p style='color:#555'>SQL Monitor not available for this test</p>\n");
                    }
                    html.append("</div>\n");
                    firstMonTest = false;
                }
                html.append("</div>\n");
                html.append("</div>\n"); // Close monitor tab

                // ---- Tab 7: AWR ----
                html.append("<div id=\"awr-tab-").append(sectionId).append("\" class=\"tab-content\">\n");
                String awrFile = awrReportFiles.get(cat);
                if (awrFile != null && !awrFile.isEmpty()) {
                    html.append("<p style=\"color: #888;\">AWR Report for ").append(catTitle);
                    html.append(" <a href=\"").append(awrFile).append("\" target=\"_blank\" style=\"color: #f97316; margin-left: 10px;\">[Open in new tab]</a></p>\n");
                    html.append("<iframe src=\"").append(awrFile).append("\" style=\"width: 100%; height: 700px; border: 1px solid var(--border-light); border-radius: 8px;\"></iframe>\n");
                } else {
                    html.append("<p style='color:#555'>AWR report not available (requires Oracle Enterprise Edition)</p>\n");
                }
                html.append("</div>\n");

                html.append("</div>\n"); // Close chart-section

                // Chart.js for this category
                html.append("<script>\n");
                html.append("new Chart(document.getElementById('").append(chartId).append("'), {\n");
                html.append("    type: 'bar', data: { labels: [");
                for (int i = 0; i < catResults.size(); i++) {
                    if (i > 0) html.append(",");
                    html.append("'").append(catResults.get(i).testId()).append("'");
                }
                html.append("],\n    datasets: [\n");
                html.append("        { label: '").append(first.mongoLabel()).append("', data: [");
                for (int i = 0; i < catResults.size(); i++) {
                    if (i > 0) html.append(",");
                    html.append(catResults.get(i).mongoNanos() >= 0 ? String.format("%.2f", catResults.get(i).mongoNanos() / 1_000_000.0) : "null");
                }
                html.append("], backgroundColor: 'rgba(74,222,128,0.7)', borderColor: '#4ade80', borderWidth: 2 },\n");
                html.append("        { label: '").append(first.oracleALabel()).append("', data: [");
                for (int i = 0; i < catResults.size(); i++) {
                    if (i > 0) html.append(",");
                    html.append(catResults.get(i).oracleANanos() >= 0 ? String.format("%.2f", catResults.get(i).oracleANanos() / 1_000_000.0) : "null");
                }
                html.append("], backgroundColor: 'rgba(59,130,246,0.7)', borderColor: '#3b82f6', borderWidth: 2 },\n");
                html.append("        { label: '").append(first.oracleBLabel()).append("', data: [");
                for (int i = 0; i < catResults.size(); i++) {
                    if (i > 0) html.append(",");
                    html.append(catResults.get(i).oracleBNanos() >= 0 ? String.format("%.2f", catResults.get(i).oracleBNanos() / 1_000_000.0) : "null");
                }
                html.append("], backgroundColor: 'rgba(245,158,11,0.7)', borderColor: '#f59e0b', borderWidth: 2 }\n");
                html.append("    ]},\n    options: { responsive: true,\n");
                html.append("        plugins: { legend: { labels: { color: '#ccc', font: { size: 14 } } }, tooltip: { callbacks: { label: ctx => ctx.dataset.label + ': ' + ctx.parsed.y + ' ms' } } },\n");
                html.append("        scales: { x: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)' } },\n");
                html.append("                  y: { ticks: { color: '#aaa', callback: v => v + ' ms' }, grid: { color: 'rgba(255,255,255,0.05)' }, title: { display: true, text: 'Latency (ms)', color: '#888' } } }\n");
                html.append("    }\n});\n</script>\n");
                chartIdx++;
            }

            // Tab navigation JavaScript
            html.append("<script>\n");
            html.append("function openTab(evt, tabId) {\n");
            html.append("    const container = evt.target.closest('.chart-section');\n");
            html.append("    container.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));\n");
            html.append("    container.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));\n");
            html.append("    container.querySelector('#' + tabId).classList.add('active');\n");
            html.append("    evt.target.classList.add('active');\n");
            html.append("}\n");
            html.append("function openProtocolTab(evt, protocolId) {\n");
            html.append("    const tabContent = evt.target.closest('.tab-content');\n");
            html.append("    tabContent.querySelectorAll('.protocol-content').forEach(c => c.classList.remove('active'));\n");
            html.append("    tabContent.querySelectorAll('.protocol-btn').forEach(b => b.classList.remove('active'));\n");
            html.append("    const el = tabContent.querySelector('#' + protocolId);\n");
            html.append("    if (el) el.classList.add('active');\n");
            html.append("    evt.target.classList.add('active');\n");
            html.append("}\n");
            html.append("function openTestTab(evt, testId) {\n");
            html.append("    const protocolContent = evt.target.closest('.protocol-content');\n");
            html.append("    protocolContent.querySelectorAll('.test-content').forEach(c => c.classList.remove('active'));\n");
            html.append("    protocolContent.querySelectorAll('.test-btn').forEach(b => b.classList.remove('active'));\n");
            html.append("    const el = protocolContent.querySelector('#' + testId);\n");
            html.append("    if (el) el.classList.add('active');\n");
            html.append("    evt.target.classList.add('active');\n");
            html.append("}\n");
            html.append("</script>\n");

            // Footer
            html.append("<div class=\"footer\">Generated by DocBench Complex Join Benchmark &bull; Category X (Order 90-119) &bull; ");
            html.append(java.time.LocalDateTime.now().toString().substring(0, 19));
            html.append("</div>\n</div>\n</body>\n</html>\n");

            Files.writeString(Path.of(REPORT_FILE), html.toString());
            System.out.println("\n  [Report] HTML report generated: " + REPORT_FILE);

        } catch (IOException e) {
            System.out.println("  [Report] Error generating HTML report: " + e.getMessage());
        }
    }

    // =========================================================================
    // Config Loading
    // =========================================================================

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
}
