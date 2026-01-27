package com.docbench.benchmark;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Triple comparison benchmark: MongoDB Native vs Oracle MongoDB API vs Oracle JDBC
 * Generates HTML report with line graphs comparing all three modes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Triple Comparison: MongoDB vs Oracle MongoDB API vs Oracle JDBC")
public class TripleComparisonBenchmark {

    // Test configuration
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASUREMENT_ITERATIONS = 10;

    // Collections
    private static final String CUSTOMERS = "triple_customers";
    private static final String ORDERS = "triple_orders";

    // Connections
    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> mongoCustomers;
    private static MongoCollection<Document> mongoOrders;

    private static MongoClient oracleMongoClient;
    private static MongoDatabase oracleMongoDatabase;
    private static MongoCollection<Document> oracleMongoCustomers;
    private static MongoCollection<Document> oracleMongoOrders;

    private static Connection oracleJdbcConnection;

    private static boolean oracleMongoApiAvailable = false;
    private static boolean oracleJdbcAvailable = false;

    // Results storage
    private static final Map<String, List<BenchmarkResult>> allResults = new LinkedHashMap<>();

    static class BenchmarkResult {
        String testName;
        String category;
        int scale;
        long mongoNanos;
        long oracleApiNanos;
        long oracleJdbcNanos;

        BenchmarkResult(String testName, String category, int scale) {
            this.testName = testName;
            this.category = category;
            this.scale = scale;
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config/local.properties")) {
            props.load(fis);
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  TRIPLE COMPARISON BENCHMARK");
        System.out.println("  MongoDB Native vs Oracle MongoDB API vs Oracle JDBC");
        System.out.println("=".repeat(90));

        // MongoDB Native
        String mongoUri = props.getProperty("mongodb.uri");
        String mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);
        mongoCustomers = mongoDatabase.getCollection(CUSTOMERS);
        mongoOrders = mongoDatabase.getCollection(ORDERS);
        System.out.println("  [✓] MongoDB Native connected");

        // Oracle MongoDB API
        String oracleMongoUri = props.getProperty("oracle.mongodb.uri");
        String oracleMongoDbName = props.getProperty("oracle.mongodb.database", "docbench");
        if (oracleMongoUri != null && !oracleMongoUri.isEmpty()) {
            try {
                oracleMongoClient = MongoClients.create(oracleMongoUri);
                oracleMongoDatabase = oracleMongoClient.getDatabase(oracleMongoDbName);
                oracleMongoDatabase.listCollectionNames().first();
                oracleMongoCustomers = oracleMongoDatabase.getCollection(CUSTOMERS);
                oracleMongoOrders = oracleMongoDatabase.getCollection(ORDERS);
                oracleMongoApiAvailable = true;
                System.out.println("  [✓] Oracle MongoDB API connected (port 27018)");
            } catch (Exception e) {
                System.out.println("  [✗] Oracle MongoDB API unavailable: " + e.getMessage());
            }
        }

        // Oracle JDBC
        String oracleUrl = props.getProperty("oracle.url");
        String oracleUser = props.getProperty("oracle.username");
        String oraclePass = props.getProperty("oracle.password");
        if (oracleUrl != null) {
            try {
                oracleJdbcConnection = DriverManager.getConnection(oracleUrl, oracleUser, oraclePass);
                oracleJdbcAvailable = true;
                System.out.println("  [✓] Oracle JDBC connected");
            } catch (Exception e) {
                System.out.println("  [✗] Oracle JDBC unavailable: " + e.getMessage());
            }
        }

        System.out.println("=".repeat(90) + "\n");
    }

    @AfterAll
    static void teardown() {
        // Cleanup collections
        try { mongoCustomers.drop(); } catch (Exception ignored) {}
        try { mongoOrders.drop(); } catch (Exception ignored) {}
        if (oracleMongoApiAvailable) {
            try { oracleMongoCustomers.drop(); } catch (Exception ignored) {}
            try { oracleMongoOrders.drop(); } catch (Exception ignored) {}
        }
        if (oracleJdbcAvailable) {
            try {
                try (Statement stmt = oracleJdbcConnection.createStatement()) {
                    stmt.execute("DROP TABLE " + CUSTOMERS + " PURGE");
                    stmt.execute("DROP TABLE " + ORDERS + " PURGE");
                }
            } catch (Exception ignored) {}
        }

        // Close connections
        if (mongoClient != null) mongoClient.close();
        if (oracleMongoClient != null) oracleMongoClient.close();
        if (oracleJdbcConnection != null) {
            try { oracleJdbcConnection.close(); } catch (Exception ignored) {}
        }

        // Generate report
        generateHtmlReport();
    }

    // =========================================================================
    // Test Categories
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("A: Join Performance by Customer Count")
    void joinPerformanceByScale() {
        int[] customerCounts = {1000, 5000, 10000, 25000, 50000, 100000};
        int ordersPerCustomer = 10;

        List<BenchmarkResult> results = new ArrayList<>();

        for (int customerCount : customerCounts) {
            System.out.println("  Testing with " + customerCount + " customers...");

            // Setup data
            setupTestData(customerCount, ordersPerCustomer);

            BenchmarkResult result = new BenchmarkResult(
                    customerCount + " customers",
                    "Join by Scale",
                    customerCount
            );

            // MongoDB Native
            result.mongoNanos = measureMongoLookup(customerCount);

            // Oracle MongoDB API
            if (oracleMongoApiAvailable) {
                result.oracleApiNanos = measureOracleMongoApiJoin(customerCount);
            }

            // Oracle JDBC
            if (oracleJdbcAvailable) {
                result.oracleJdbcNanos = measureOracleJdbcJoin(customerCount);
            }

            results.add(result);
            printResult(result);
        }

        allResults.put("Join Performance by Scale", results);
    }

    @Test
    @Order(2)
    @DisplayName("B: Join Performance by Cardinality")
    void joinPerformanceByCardinality() {
        int customerCount = 1000;
        int[] ordersPerCustomer = {1, 10, 50, 100, 500, 1000};

        List<BenchmarkResult> results = new ArrayList<>();

        for (int orderCount : ordersPerCustomer) {
            System.out.println("  Testing with 1:" + orderCount + " cardinality...");

            setupTestData(customerCount, orderCount);

            BenchmarkResult result = new BenchmarkResult(
                    "1:" + orderCount,
                    "Join by Cardinality",
                    orderCount
            );

            result.mongoNanos = measureMongoLookup(customerCount);

            if (oracleMongoApiAvailable) {
                result.oracleApiNanos = measureOracleMongoApiJoin(customerCount);
            }

            if (oracleJdbcAvailable) {
                result.oracleJdbcNanos = measureOracleJdbcJoin(customerCount);
            }

            results.add(result);
            printResult(result);
        }

        allResults.put("Join Performance by Cardinality", results);
    }

    @Test
    @Order(3)
    @DisplayName("C: Sort Performance by Document Count")
    void sortPerformanceByScale() {
        int[] documentCounts = {1000, 5000, 10000, 25000, 50000, 100000};

        List<BenchmarkResult> results = new ArrayList<>();

        for (int docCount : documentCounts) {
            System.out.println("  Testing sort with " + docCount + " documents...");

            setupOrdersOnly(docCount);

            BenchmarkResult result = new BenchmarkResult(
                    docCount + " docs",
                    "Sort Performance",
                    docCount
            );

            result.mongoNanos = measureMongoSort(docCount);

            if (oracleMongoApiAvailable) {
                result.oracleApiNanos = measureOracleMongoApiSort(docCount);
            }

            if (oracleJdbcAvailable) {
                result.oracleJdbcNanos = measureOracleJdbcSort(docCount);
            }

            results.add(result);
            printResult(result);
        }

        allResults.put("Sort Performance", results);
    }

    @Test
    @Order(4)
    @DisplayName("D: Aggregation Performance")
    void aggregationPerformance() {
        int[] customerCounts = {100, 500, 1000, 2000, 5000};
        int ordersPerCustomer = 20;

        List<BenchmarkResult> results = new ArrayList<>();

        for (int customerCount : customerCounts) {
            System.out.println("  Testing aggregation with " + customerCount + " customers...");

            setupTestData(customerCount, ordersPerCustomer);

            BenchmarkResult result = new BenchmarkResult(
                    customerCount + " customers",
                    "Aggregation",
                    customerCount
            );

            result.mongoNanos = measureMongoAggregation(customerCount);

            if (oracleMongoApiAvailable) {
                result.oracleApiNanos = measureOracleMongoApiAggregation(customerCount);
            }

            if (oracleJdbcAvailable) {
                result.oracleJdbcNanos = measureOracleJdbcAggregation(customerCount);
            }

            results.add(result);
            printResult(result);
        }

        allResults.put("Aggregation Performance", results);
    }

    // =========================================================================
    // Data Setup
    // =========================================================================

    private void setupTestData(int customerCount, int ordersPerCustomer) {
        // Clear existing data
        mongoCustomers.drop();
        mongoOrders.drop();
        mongoCustomers = mongoDatabase.getCollection(CUSTOMERS);
        mongoOrders = mongoDatabase.getCollection(ORDERS);

        if (oracleMongoApiAvailable) {
            try { oracleMongoCustomers.drop(); } catch (Exception ignored) {}
            try { oracleMongoOrders.drop(); } catch (Exception ignored) {}
            oracleMongoCustomers = oracleMongoDatabase.getCollection(CUSTOMERS);
            oracleMongoOrders = oracleMongoDatabase.getCollection(ORDERS);
        }

        // Generate customer documents
        List<Document> customers = new ArrayList<>();
        List<Document> orders = new ArrayList<>();

        for (int i = 0; i < customerCount; i++) {
            String customerId = "C" + String.format("%06d", i);
            customers.add(new Document("_id", customerId)
                    .append("name", "Customer " + i)
                    .append("region", i % 4 == 0 ? "US" : i % 4 == 1 ? "EU" : i % 4 == 2 ? "APAC" : "LATAM"));

            for (int j = 0; j < ordersPerCustomer; j++) {
                orders.add(new Document("_id", customerId + "_O" + j)
                        .append("customer_id", customerId)
                        .append("total", 100.0 + (i * j) % 1000)
                        .append("status", j % 3 == 0 ? "completed" : j % 3 == 1 ? "pending" : "shipped"));
            }
        }

        // Insert into MongoDB
        mongoCustomers.insertMany(customers);
        mongoOrders.insertMany(orders);
        mongoOrders.createIndex(new Document("customer_id", 1));

        // Insert into Oracle MongoDB API
        if (oracleMongoApiAvailable) {
            oracleMongoCustomers.insertMany(new ArrayList<>(customers));
            oracleMongoOrders.insertMany(new ArrayList<>(orders));
            // Create index with online:false to avoid ORA-00910 (MAX_STRING_SIZE not extended)
            try {
                oracleMongoOrders.createIndex(
                        new Document("customer_id", 1),
                        new IndexOptions().background(false));
            } catch (Exception e) {
                // Index creation may fail on Oracle SODA, continue without index
                System.out.println("    [Note] Oracle MongoDB API index creation skipped: " + e.getMessage());
            }
        }

        // Insert into Oracle JDBC
        if (oracleJdbcAvailable) {
            setupOracleJdbcTables(customers, orders);
        }
    }

    private void setupOrdersOnly(int orderCount) {
        mongoOrders.drop();
        mongoOrders = mongoDatabase.getCollection(ORDERS);

        if (oracleMongoApiAvailable) {
            try { oracleMongoOrders.drop(); } catch (Exception ignored) {}
            oracleMongoOrders = oracleMongoDatabase.getCollection(ORDERS);
        }

        List<Document> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            orders.add(new Document("_id", "O" + String.format("%08d", i))
                    .append("customer_id", "C" + String.format("%06d", i % 1000))
                    .append("total", 100.0 + i % 10000)
                    .append("status", i % 3 == 0 ? "completed" : "pending"));
        }

        mongoOrders.insertMany(orders);

        if (oracleMongoApiAvailable) {
            oracleMongoOrders.insertMany(new ArrayList<>(orders));
        }

        if (oracleJdbcAvailable) {
            setupOracleJdbcOrdersOnly(orders);
        }
    }

    private void setupOracleJdbcTables(List<Document> customers, List<Document> orders) {
        try {
            // Drop indexes and tables
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                try { stmt.execute("DROP INDEX idx_orders_cust"); } catch (Exception ignored) {}
                try { stmt.execute("DROP TABLE " + ORDERS + " PURGE"); } catch (Exception ignored) {}
                try { stmt.execute("DROP TABLE " + CUSTOMERS + " PURGE"); } catch (Exception ignored) {}

                stmt.execute("CREATE TABLE " + CUSTOMERS + " (doc CLOB CHECK (doc IS JSON))");
                stmt.execute("CREATE TABLE " + ORDERS + " (doc CLOB CHECK (doc IS JSON))");
            }

            // Insert customers
            String insertCustomer = "INSERT INTO " + CUSTOMERS + " (doc) VALUES (?)";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(insertCustomer)) {
                for (Document doc : customers) {
                    ps.setString(1, doc.toJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Insert orders
            String insertOrder = "INSERT INTO " + ORDERS + " (doc) VALUES (?)";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(insertOrder)) {
                for (Document doc : orders) {
                    ps.setString(1, doc.toJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Create index
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("CREATE INDEX idx_orders_cust ON " + ORDERS +
                        " (JSON_VALUE(doc, '$.customer_id'))");
            }

            // Note: auto-commit is enabled by default, no explicit commit needed
        } catch (SQLException e) {
            throw new RuntimeException("Failed to setup Oracle JDBC tables", e);
        }
    }

    private void setupOracleJdbcOrdersOnly(List<Document> orders) {
        try {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                try { stmt.execute("DROP INDEX idx_orders_cust"); } catch (Exception ignored) {}
                try { stmt.execute("DROP TABLE " + ORDERS + " PURGE"); } catch (Exception ignored) {}
                stmt.execute("CREATE TABLE " + ORDERS + " (doc CLOB CHECK (doc IS JSON))");
            }

            String insertOrder = "INSERT INTO " + ORDERS + " (doc) VALUES (?)";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(insertOrder)) {
                for (Document doc : orders) {
                    ps.setString(1, doc.toJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // Note: auto-commit is enabled by default, no explicit commit needed
        } catch (SQLException e) {
            throw new RuntimeException("Failed to setup Oracle JDBC orders", e);
        }
    }

    // =========================================================================
    // MongoDB Native Measurements
    // =========================================================================

    private long measureMongoLookup(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup(ORDERS, "_id", "customer_id", "orders")
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : mongoCustomers.aggregate(pipeline)) {}
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : mongoCustomers.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoSort(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.sort(Sorts.descending("total")),
                Aggregates.limit(limit)
        );

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : mongoOrders.aggregate(pipeline).allowDiskUse(true)) {}
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : mongoOrders.aggregate(pipeline).allowDiskUse(true)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureMongoAggregation(int limit) {
        List<Bson> pipeline = Arrays.asList(
                Aggregates.limit(limit),
                Aggregates.lookup(ORDERS, "_id", "customer_id", "orders"),
                Aggregates.unwind("$orders"),
                Aggregates.group("$_id",
                        Accumulators.sum("totalSpent", "$orders.total"),
                        Accumulators.sum("orderCount", 1))
        );

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : mongoCustomers.aggregate(pipeline).allowDiskUse(true)) {}
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : mongoCustomers.aggregate(pipeline).allowDiskUse(true)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // =========================================================================
    // Oracle MongoDB API Measurements
    // =========================================================================

    private long measureOracleMongoApiJoin(int limit) {
        String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) FROM " + CUSTOMERS + " c " +
                "JOIN " + ORDERS + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 10);

        List<Document> pipeline = Arrays.asList(new Document("$sql", sql));

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : oracleMongoCustomers.aggregate(pipeline)) {}
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoCustomers.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureOracleMongoApiSort(int limit) {
        String sql = "SELECT data FROM " + ORDERS +
                " ORDER BY JSON_VALUE(data, '$.total' RETURNING NUMBER) DESC " +
                "FETCH FIRST " + limit + " ROWS ONLY";

        List<Document> pipeline = Arrays.asList(new Document("$sql", sql));

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : oracleMongoOrders.aggregate(pipeline)) {}
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoOrders.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureOracleMongoApiAggregation(int limit) {
        String sql = "SELECT JSON_OBJECT('customer_id' VALUE JSON_VALUE(c.data, '$._id'), " +
                "'totalSpent' VALUE SUM(JSON_VALUE(o.data, '$.total' RETURNING NUMBER)), " +
                "'orderCount' VALUE COUNT(*)) " +
                "FROM " + CUSTOMERS + " c " +
                "JOIN " + ORDERS + " o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                "WHERE ROWNUM <= " + (limit * 20) + " GROUP BY JSON_VALUE(c.data, '$._id')";

        List<Document> pipeline = Arrays.asList(new Document("$sql", sql));

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Document doc : oracleMongoCustomers.aggregate(pipeline)) {}
        }

        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (Document doc : oracleMongoCustomers.aggregate(pipeline)) {}
            totalNanos += System.nanoTime() - start;
        }
        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // =========================================================================
    // Oracle JDBC Measurements
    // =========================================================================

    private long measureOracleJdbcJoin(int limit) {
        String sql = "SELECT c.doc, o.doc FROM " + CUSTOMERS + " c " +
                "JOIN " + ORDERS + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ?";

        try {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                    ps.setInt(1, limit * 10);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
                    }
                }
            }

            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 10);
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

    private long measureOracleJdbcSort(int limit) {
        String sql = "SELECT doc FROM " + ORDERS +
                " ORDER BY JSON_VALUE(doc, '$.total' RETURNING NUMBER) DESC " +
                "FETCH FIRST ? ROWS ONLY";

        try {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
                    }
                }
            }

            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
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

    private long measureOracleJdbcAggregation(int limit) {
        String sql = "SELECT JSON_VALUE(c.doc, '$._id') as customer_id, " +
                "SUM(JSON_VALUE(o.doc, '$.total' RETURNING NUMBER)) as totalSpent, " +
                "COUNT(*) as orderCount " +
                "FROM " + CUSTOMERS + " c " +
                "JOIN " + ORDERS + " o ON JSON_VALUE(c.doc, '$._id') = JSON_VALUE(o.doc, '$.customer_id') " +
                "WHERE ROWNUM <= ? GROUP BY JSON_VALUE(c.doc, '$._id')";

        try {
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                    ps.setInt(1, limit * 20);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {}
                    }
                }
            }

            long totalNanos = 0;
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    ps.setInt(1, limit * 20);
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

    // =========================================================================
    // Reporting
    // =========================================================================

    private void printResult(BenchmarkResult r) {
        System.out.printf("    %-20s | Mongo: %,12d ns | Oracle API: %,12d ns | Oracle JDBC: %,12d ns%n",
                r.testName,
                r.mongoNanos,
                r.oracleApiNanos,
                r.oracleJdbcNanos);
    }

    private static void generateHtmlReport() {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Triple Comparison Benchmark Report</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                <style>
                    body {
                        font-family: 'Segoe UI', Arial, sans-serif;
                        margin: 0;
                        padding: 20px;
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        min-height: 100vh;
                        color: #eee;
                    }
                    .container {
                        max-width: 1400px;
                        margin: 0 auto;
                    }
                    h1 {
                        text-align: center;
                        color: #00d4ff;
                        font-size: 2.5em;
                        margin-bottom: 10px;
                        text-shadow: 0 0 20px rgba(0, 212, 255, 0.5);
                    }
                    .subtitle {
                        text-align: center;
                        color: #888;
                        margin-bottom: 40px;
                    }
                    .chart-container {
                        background: rgba(255, 255, 255, 0.05);
                        border-radius: 16px;
                        padding: 30px;
                        margin-bottom: 30px;
                        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
                        backdrop-filter: blur(10px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                    }
                    .chart-title {
                        color: #00d4ff;
                        font-size: 1.4em;
                        margin-bottom: 20px;
                        padding-bottom: 10px;
                        border-bottom: 2px solid rgba(0, 212, 255, 0.3);
                    }
                    canvas {
                        max-height: 400px;
                    }
                    .legend-custom {
                        display: flex;
                        justify-content: center;
                        gap: 30px;
                        margin-top: 20px;
                    }
                    .legend-item {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }
                    .legend-color {
                        width: 20px;
                        height: 20px;
                        border-radius: 4px;
                    }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 20px;
                        margin-bottom: 40px;
                    }
                    .summary-card {
                        background: rgba(255, 255, 255, 0.05);
                        border-radius: 12px;
                        padding: 25px;
                        text-align: center;
                        border: 1px solid rgba(255, 255, 255, 0.1);
                    }
                    .summary-card h3 {
                        margin: 0 0 10px 0;
                        font-size: 1em;
                        color: #888;
                    }
                    .summary-card .value {
                        font-size: 2.5em;
                        font-weight: bold;
                    }
                    .mongo-color { color: #4ade80; }
                    .oracle-api-color { color: #f59e0b; }
                    .oracle-jdbc-color { color: #3b82f6; }
                    .footer {
                        text-align: center;
                        margin-top: 40px;
                        color: #666;
                        font-size: 0.9em;
                    }
                </style>
            </head>
            <body>
            <div class="container">
                <h1>🚀 Triple Comparison Benchmark</h1>
                <p class="subtitle">MongoDB Native vs Oracle MongoDB API vs Oracle JDBC</p>

                <div class="summary-grid">
                    <div class="summary-card">
                        <h3>MongoDB Native</h3>
                        <div class="value mongo-color">$lookup</div>
                    </div>
                    <div class="summary-card">
                        <h3>Oracle MongoDB API</h3>
                        <div class="value oracle-api-color">$sql</div>
                    </div>
                    <div class="summary-card">
                        <h3>Oracle JDBC</h3>
                        <div class="value oracle-jdbc-color">SQL JOIN</div>
                    </div>
                </div>
            """);

        // Generate chart for each category
        int chartId = 0;
        for (Map.Entry<String, List<BenchmarkResult>> entry : allResults.entrySet()) {
            String category = entry.getKey();
            List<BenchmarkResult> results = entry.getValue();

            html.append("<div class=\"chart-container\">\n");
            html.append("<h2 class=\"chart-title\">").append(category).append("</h2>\n");
            html.append("<canvas id=\"chart").append(chartId).append("\"></canvas>\n");
            html.append("</div>\n");

            chartId++;
        }

        html.append("<script>\n");

        // Generate JavaScript for each chart
        chartId = 0;
        for (Map.Entry<String, List<BenchmarkResult>> entry : allResults.entrySet()) {
            List<BenchmarkResult> results = entry.getValue();

            // Labels
            StringBuilder labels = new StringBuilder("[");
            StringBuilder mongoData = new StringBuilder("[");
            StringBuilder oracleApiData = new StringBuilder("[");
            StringBuilder oracleJdbcData = new StringBuilder("[");

            for (int i = 0; i < results.size(); i++) {
                BenchmarkResult r = results.get(i);
                if (i > 0) {
                    labels.append(",");
                    mongoData.append(",");
                    oracleApiData.append(",");
                    oracleJdbcData.append(",");
                }
                labels.append("'").append(r.testName).append("'");
                mongoData.append(r.mongoNanos / 1_000_000.0); // Convert to ms
                oracleApiData.append(r.oracleApiNanos / 1_000_000.0);
                oracleJdbcData.append(r.oracleJdbcNanos / 1_000_000.0);
            }
            labels.append("]");
            mongoData.append("]");
            oracleApiData.append("]");
            oracleJdbcData.append("]");

            html.append(String.format("""
                new Chart(document.getElementById('chart%d'), {
                    type: 'line',
                    data: {
                        labels: %s,
                        datasets: [
                            {
                                label: 'MongoDB Native',
                                data: %s,
                                borderColor: '#4ade80',
                                backgroundColor: 'rgba(74, 222, 128, 0.1)',
                                borderWidth: 3,
                                tension: 0.3,
                                fill: true,
                                pointRadius: 6,
                                pointHoverRadius: 8
                            },
                            {
                                label: 'Oracle MongoDB API',
                                data: %s,
                                borderColor: '#f59e0b',
                                backgroundColor: 'rgba(245, 158, 11, 0.1)',
                                borderWidth: 3,
                                tension: 0.3,
                                fill: true,
                                pointRadius: 6,
                                pointHoverRadius: 8
                            },
                            {
                                label: 'Oracle JDBC',
                                data: %s,
                                borderColor: '#3b82f6',
                                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                                borderWidth: 3,
                                tension: 0.3,
                                fill: true,
                                pointRadius: 6,
                                pointHoverRadius: 8
                            }
                        ]
                    },
                    options: {
                        responsive: true,
                        plugins: {
                            legend: {
                                labels: { color: '#eee', font: { size: 14 } }
                            },
                            tooltip: {
                                callbacks: {
                                    label: function(context) {
                                        return context.dataset.label + ': ' + context.parsed.y.toFixed(2) + ' ms';
                                    }
                                }
                            }
                        },
                        scales: {
                            x: {
                                ticks: { color: '#aaa' },
                                grid: { color: 'rgba(255,255,255,0.1)' }
                            },
                            y: {
                                ticks: { color: '#aaa' },
                                grid: { color: 'rgba(255,255,255,0.1)' },
                                title: {
                                    display: true,
                                    text: 'Time (milliseconds)',
                                    color: '#aaa'
                                }
                            }
                        }
                    }
                });
                """, chartId, labels, mongoData, oracleApiData, oracleJdbcData));

            chartId++;
        }

        html.append("</script>\n");

        html.append("""
                <div class="footer">
                    Generated by DocBench Triple Comparison Benchmark<br>
                    MongoDB $lookup vs Oracle $sql (MongoDB API) vs Oracle SQL JOIN (JDBC)
                </div>
            </div>
            </body>
            </html>
            """);

        // Write to file
        try {
            Path reportPath = Paths.get("reports/triple_comparison_report.html");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, html.toString());
            System.out.println("\n✅ HTML report generated: " + reportPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write HTML report: " + e.getMessage());
        }
    }
}
