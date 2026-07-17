package com.docbench.benchmark;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import oracle.jdbc.pool.OracleDataSource;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trade Annotation Service Benchmark Test Suite
 *
 * Simulates a real-world trade annotation service workflow where:
 * - Orders are received and executed
 * - Trades are annotated with data from various sources post-execution
 * - Status queries retrieve all documents for an order during high-volume processing
 *
 * Data Model:
 * - Single collection with append-only documents
 * - Composite _id: {orderId}#{docType}#{docId}
 * - Documents for same order are physically adjacent (B-tree locality)
 * - Query uses range scan: _id >= "ORD_00001#" AND _id < "ORD_00001$"
 *
 * Protocols tested:
 * 1. MongoDB Native - range query
 * 2. Oracle MongoDB API (native pipeline) - range query
 * 3. Oracle MongoDB API ($sql) - SQL range query
 * 4. Oracle JDBC JSON - SQL range query on JSON table
 * 5. Oracle JDBC Relational - SQL query on relational table
 */
@DisplayName("Trade Annotation Service: Append-Only Workflow Benchmark")
@Tag("benchmark")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TradeAnnotationServiceTest {

    // ==========================================================================
    // Configuration Constants
    // ==========================================================================

    // Payload sizes in KB
    private static final int[] PAYLOAD_SIZES_KB = {4, 6, 8, 10, 12};

    // Order counts
    private static final int SMALL_ORDER_COUNT = 1_000;
    private static final int MEDIUM_ORDER_COUNT = 10_000;

    // Order distribution
    private static final double COMPLEX_ORDER_RATIO = 0.20;  // 20% complex orders

    // Read pattern - 50% of complex orders are read
    private static final double READ_SAMPLE_RATIO = 0.50;
    private static final int MAX_READS_PER_ORDER = 5;

    // Measurement iterations
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASUREMENT_ITERATIONS = 10;

    // JDBC fetch size
    private static final int JDBC_FETCH_SIZE = 1000;

    // ==========================================================================
    // Collection/Table Names
    // ==========================================================================

    // MongoDB collection
    private static final String TRADES_COLLECTION = "trade_documents";

    // Oracle SODA collection (via MongoDB API)
    private static final String SODA_TRADES_COLLECTION = "soda_trade_documents";

    // Oracle relational table
    private static final String TRADES_REL_TABLE = "trade_documents_rel";

    // ==========================================================================
    // Database Connections
    // ==========================================================================

    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> tradesCollection;

    private static MongoClient oracleMongoClient;
    private static MongoDatabase oracleMongoDatabase;
    private static MongoCollection<Document> oracleTradesCollection;

    private static Connection oracleJdbcConnection;

    private static boolean useOracleMongoApi = false;

    // ==========================================================================
    // AWR Configuration
    // ==========================================================================

    private static boolean awrEnabled = false;
    private static long dbId = 0;
    private static long instanceNumber = 1;
    private static final String AWR_REPORT_DIR = "build/reports/awr";
    private static final String SQL_MONITOR_DIR = "reports/sql_monitor";
    private static final Map<String, long[]> awrSnapshots = new LinkedHashMap<>();
    private static final Map<String, String> awrReportContent = new LinkedHashMap<>();

    // ==========================================================================
    // Results Storage
    // ==========================================================================

    private static final Map<String, TradeTestResult> results = new LinkedHashMap<>();
    private static final Map<String, String[]> sqlDetails = new LinkedHashMap<>();  // testId -> [mongoQuery, oracleSql, relationalSql]
    private static final Map<String, String[]> sqlMonitors = new LinkedHashMap<>();  // testId -> [sqlMonitorJson, sqlMonitorRel]

    private record TradeTestResult(
            String testId,
            String description,
            int payloadSizeKB,
            int orderCount,
            int totalDocuments,
            long mongoInsertNanos,
            long oracleApiInsertNanos,
            long oracleJdbcJsonInsertNanos,
            long oracleJdbcRelInsertNanos,
            long mongoReadNanos,
            long oracleNativeApiReadNanos,
            long oracleApiSqlReadNanos,
            long oracleJdbcJsonReadNanos,
            long oracleJdbcRelReadNanos,
            int readQueryCount,
            double avgDocsPerRead
    ) {}

    // ==========================================================================
    // Test Data Storage
    // ==========================================================================

    private static List<Document> currentTestDocuments;
    private static List<String> currentComplexOrderIds;
    private static List<String> currentReadTestOrderIds;
    private static int currentPayloadSizeKB;
    private static int currentOrderCount;

    // ==========================================================================
    // Lifecycle Methods
    // ==========================================================================

    @BeforeAll
    static void setup() throws SQLException, IOException {
        Properties props = loadConfigProperties();

        // MongoDB native connection
        String mongoUri = props.getProperty("mongodb.uri");
        String mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);

        try {
            mongoDatabase.getCollection(TRADES_COLLECTION).drop();
        } catch (Exception ignored) {}

        WriteConcern durableWriteConcern = WriteConcern.W1.withJournal(true);
        tradesCollection = mongoDatabase.getCollection(TRADES_COLLECTION).withWriteConcern(durableWriteConcern);

        // Oracle JDBC connection
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

        createOracleTables();
        initializeAwr();

        // Oracle MongoDB API connection
        String oracleMongoUri = props.getProperty("oracle.mongodb.uri");
        String oracleMongoDbName = props.getProperty("oracle.mongodb.database", mongoDbName);

        if (oracleMongoUri != null && !oracleMongoUri.isEmpty()) {
            try {
                oracleMongoClient = MongoClients.create(oracleMongoUri);
                oracleMongoDatabase = oracleMongoClient.getDatabase(oracleMongoDbName);
                oracleMongoDatabase.listCollectionNames().first();

                oracleTradesCollection = oracleMongoDatabase.getCollection(SODA_TRADES_COLLECTION);
                useOracleMongoApi = true;
                System.out.println("  Oracle MongoDB API enabled (port 27018)");
            } catch (Exception e) {
                System.out.println("  Oracle MongoDB API connection failed: " + e.getMessage());
                oracleMongoClient = null;
                useOracleMongoApi = false;
            }
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  TRADE ANNOTATION SERVICE BENCHMARK TEST SUITE");
        System.out.println("  " + "-".repeat(84));
        System.out.println("  Workflow: Append-only trade documents with composite _id");
        System.out.println("  Query: Range scan on _id prefix for order status lookup");
        System.out.println("  " + "-".repeat(84));
        System.out.println("  Protocols: MongoDB Native, Oracle API (native), Oracle API ($sql),");
        System.out.println("             Oracle JDBC (JSON), Oracle JDBC (Relational)");
        System.out.println("=".repeat(90));
    }

    @AfterAll
    static void teardown() {
        printFinalReport();

        if (awrEnabled) {
            generateAwrReports();
        }

        generateHtmlReport();

        // Cleanup
        if (mongoClient != null) {
            try {
                mongoDatabase.getCollection(TRADES_COLLECTION).drop();
            } catch (Exception ignored) {}
            try { mongoClient.close(); } catch (Exception ignored) {}
        }

        if (oracleMongoClient != null) {
            try {
                oracleMongoDatabase.getCollection(SODA_TRADES_COLLECTION).drop();
            } catch (Exception ignored) {}
            try { oracleMongoClient.close(); } catch (Exception ignored) {}
        }

        if (oracleJdbcConnection != null) {
            try {
                try (Statement stmt = oracleJdbcConnection.createStatement()) {
                    stmt.execute("DROP TABLE " + TRADES_COLLECTION + " PURGE");
                    stmt.execute("DROP TABLE " + TRADES_REL_TABLE + " PURGE");
                }
            } catch (SQLException ignored) {}
            try { oracleJdbcConnection.close(); } catch (SQLException ignored) {}
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
    // Oracle Table Setup
    // ==========================================================================

    private static void createOracleTables() throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Drop existing tables
            try { stmt.execute("DROP TABLE " + TRADES_COLLECTION + " PURGE"); } catch (SQLException ignored) {}
            try { stmt.execute("DROP TABLE " + TRADES_REL_TABLE + " PURGE"); } catch (SQLException ignored) {}

            // JSON table - composite id is the primary key
            stmt.execute("CREATE TABLE " + TRADES_COLLECTION + " (id VARCHAR2(200) PRIMARY KEY, data JSON)");

            // Relational table - use VARCHAR2 for timestamp to match JSON format
            stmt.execute("CREATE TABLE " + TRADES_REL_TABLE + " (" +
                    "id VARCHAR2(200) PRIMARY KEY, " +
                    "order_id VARCHAR2(50) NOT NULL, " +
                    "doc_type VARCHAR2(20) NOT NULL, " +
                    "doc_id VARCHAR2(100) NOT NULL, " +
                    "ts VARCHAR2(50), " +
                    "symbol VARCHAR2(20), " +
                    "side VARCHAR2(10), " +
                    "quantity NUMBER, " +
                    "price NUMBER(18,6), " +
                    "client_id VARCHAR2(50), " +
                    "fill_qty NUMBER, " +
                    "fill_price NUMBER(18,6), " +
                    "venue VARCHAR2(20), " +
                    "source VARCHAR2(50), " +
                    "annotation_type VARCHAR2(50), " +
                    "annotation_value CLOB, " +
                    "payload CLOB)");

            // Index for order_id lookups in relational table
            stmt.execute("CREATE INDEX idx_trade_docs_order_id ON " + TRADES_REL_TABLE + "(order_id)");
        }
    }

    // ==========================================================================
    // Data Generation
    // ==========================================================================

    private static List<Document> generateOrderDocuments(int orderCount, int payloadSizeKB) {
        List<Document> docs = new ArrayList<>();
        Random rand = new Random(42);  // Reproducible
        String payload = generatePayload(payloadSizeKB);
        String[] symbols = {"AAPL", "GOOGL", "MSFT", "AMZN", "META", "NVDA", "TSLA", "JPM", "V", "JNJ"};
        String[] sides = {"BUY", "SELL"};
        String[] venues = {"NYSE", "NASDAQ", "ARCA", "BATS", "IEX"};
        String[] annotationSources = {"RISK_ENGINE", "COMPLIANCE", "MARKET_DATA", "REFERENCE_DATA", "ALLOCATION"};
        String[] annotationTypes = {"RISK_SCORE", "COMPLIANCE_CHECK", "PRICE_VALIDATION", "CLIENT_ENRICHMENT", "ALLOCATION_DETAIL"};

        currentComplexOrderIds = new ArrayList<>();

        for (int i = 0; i < orderCount; i++) {
            String orderId = String.format("ORD_%05d", i);
            boolean isComplex = rand.nextDouble() < COMPLEX_ORDER_RATIO;

            if (isComplex) {
                currentComplexOrderIds.add(orderId);
            }

            // Order document
            String orderDocId = orderId + "#ORDER#" + orderId;
            docs.add(new Document()
                    .append("_id", orderDocId)
                    .append("orderId", orderId)
                    .append("docType", "ORDER")
                    .append("docId", orderId)
                    .append("timestamp", Instant.now().toString())
                    .append("symbol", symbols[rand.nextInt(symbols.length)])
                    .append("side", sides[rand.nextInt(sides.length)])
                    .append("quantity", 100 + rand.nextInt(10000))
                    .append("price", 50.0 + rand.nextDouble() * 450.0)
                    .append("clientId", "CLIENT_" + String.format("%03d", rand.nextInt(100)))
                    .append("payload", payload));

            // Execution documents
            int execCount = isComplex ? rand.nextInt(5) + 1 : 1;
            for (int e = 0; e < execCount; e++) {
                String execId = String.format("EXE_%05d", e);
                String execDocId = orderId + "#EXEC#" + execId;
                docs.add(new Document()
                        .append("_id", execDocId)
                        .append("orderId", orderId)
                        .append("docType", "EXECUTION")
                        .append("docId", execId)
                        .append("timestamp", Instant.now().toString())
                        .append("fillQty", 50 + rand.nextInt(500))
                        .append("fillPrice", 50.0 + rand.nextDouble() * 450.0)
                        .append("venue", venues[rand.nextInt(venues.length)])
                        .append("payload", payload));
            }

            // Annotation documents
            int annotCount = isComplex ? rand.nextInt(11) + 5 : 5;  // 5-15 or exactly 5
            for (int a = 0; a < annotCount; a++) {
                String annotId = String.format("ANN_%05d", a);
                String annotDocId = orderId + "#ANNOT#" + annotId;
                docs.add(new Document()
                        .append("_id", annotDocId)
                        .append("orderId", orderId)
                        .append("docType", "ANNOTATION")
                        .append("docId", annotId)
                        .append("timestamp", Instant.now().toString())
                        .append("source", annotationSources[rand.nextInt(annotationSources.length)])
                        .append("annotationType", annotationTypes[rand.nextInt(annotationTypes.length)])
                        .append("value", new Document("score", rand.nextDouble()).append("details", "Annotation details " + a))
                        .append("payload", payload));
            }
        }

        return docs;
    }

    private static String generatePayload(int sizeKB) {
        int size = sizeKB * 1024;
        StringBuilder sb = new StringBuilder(size);
        String pattern = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random(42);
        for (int i = 0; i < size; i++) {
            sb.append(pattern.charAt(rand.nextInt(pattern.length())));
        }
        return sb.toString();
    }

    private static List<String> selectReadTestOrders(List<String> complexOrderIds) {
        // Select 50% of complex orders for read testing
        int count = (int) (complexOrderIds.size() * READ_SAMPLE_RATIO);
        List<String> selected = new ArrayList<>(complexOrderIds.subList(0, count));

        // Each order may be read up to MAX_READS_PER_ORDER times
        List<String> readList = new ArrayList<>();
        Random rand = new Random(42);
        for (String orderId : selected) {
            int readCount = 1 + rand.nextInt(MAX_READS_PER_ORDER);
            for (int r = 0; r < readCount; r++) {
                readList.add(orderId);
            }
        }
        return readList;
    }

    // ==========================================================================
    // Insert Methods
    // ==========================================================================

    private long insertMongoNative(List<Document> docs) {
        tradesCollection.drop();

        long start = System.nanoTime();
        tradesCollection.insertMany(docs);
        long duration = System.nanoTime() - start;

        // Create index on _id is automatic
        return duration;
    }

    private long insertOracleMongoApi(List<Document> docs) {
        if (!useOracleMongoApi || oracleTradesCollection == null) return -1;

        try {
            oracleTradesCollection.drop();
        } catch (Exception ignored) {}

        oracleTradesCollection = oracleMongoDatabase.getCollection(SODA_TRADES_COLLECTION);

        long start = System.nanoTime();
        oracleTradesCollection.insertMany(docs);
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long insertOracleJdbcJson(List<Document> docs) throws SQLException {
        // Truncate table
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + TRADES_COLLECTION);
        }

        String sql = "INSERT INTO " + TRADES_COLLECTION + " (id, data) VALUES (?, ?)";

        long start = System.nanoTime();
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            int batch = 0;
            for (Document doc : docs) {
                String id = doc.getString("_id");
                String json = doc.toJson();
                ps.setString(1, id);
                ps.setString(2, json);
                ps.addBatch();

                if (++batch >= 1000) {
                    ps.executeBatch();
                    batch = 0;
                }
            }
            if (batch > 0) {
                ps.executeBatch();
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long insertOracleJdbcRelational(List<Document> docs) throws SQLException {
        // Truncate table
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("TRUNCATE TABLE " + TRADES_REL_TABLE);
        }

        String sql = "INSERT INTO " + TRADES_REL_TABLE + " " +
                "(id, order_id, doc_type, doc_id, ts, symbol, side, quantity, price, client_id, " +
                "fill_qty, fill_price, venue, source, annotation_type, annotation_value, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        long start = System.nanoTime();
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            int batch = 0;
            for (Document doc : docs) {
                ps.setString(1, doc.getString("_id"));
                ps.setString(2, doc.getString("orderId"));
                ps.setString(3, doc.getString("docType"));
                ps.setString(4, doc.getString("docId"));
                ps.setString(5, doc.getString("timestamp"));
                ps.setString(6, doc.getString("symbol"));
                ps.setString(7, doc.getString("side"));
                ps.setObject(8, doc.getInteger("quantity"));
                ps.setObject(9, doc.getDouble("price"));
                ps.setString(10, doc.getString("clientId"));
                ps.setObject(11, doc.getInteger("fillQty"));
                ps.setObject(12, doc.getDouble("fillPrice"));
                ps.setString(13, doc.getString("venue"));
                ps.setString(14, doc.getString("source"));
                ps.setString(15, doc.getString("annotationType"));

                Document value = doc.get("value", Document.class);
                ps.setString(16, value != null ? value.toJson() : null);
                ps.setString(17, doc.getString("payload"));
                ps.addBatch();

                if (++batch >= 1000) {
                    ps.executeBatch();
                    batch = 0;
                }
            }
            if (batch > 0) {
                ps.executeBatch();
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    // ==========================================================================
    // Read Methods - Range Query on Composite _id
    // ==========================================================================

    private long measureMongoNativeRead(List<String> orderIds) {
        int totalDocs = 0;

        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";  // $ is next char after #

                for (Document doc : tradesCollection.find(
                        new Document("_id", new Document("$gte", startKey).append("$lt", endKey)))) {
                    totalDocs++;
                }
            }
        }

        // Measurement
        totalDocs = 0;
        long start = System.nanoTime();
        for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";

                for (Document doc : tradesCollection.find(
                        new Document("_id", new Document("$gte", startKey).append("$lt", endKey)))) {
                    totalDocs++;
                }
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long measureOracleNativeApiRead(List<String> orderIds) {
        if (!useOracleMongoApi || oracleTradesCollection == null) return -1;

        int totalDocs = 0;

        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";

                for (Document doc : oracleTradesCollection.find(
                        new Document("_id", new Document("$gte", startKey).append("$lt", endKey)))) {
                    totalDocs++;
                }
            }
        }

        // Measurement
        totalDocs = 0;
        long start = System.nanoTime();
        for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";

                for (Document doc : oracleTradesCollection.find(
                        new Document("_id", new Document("$gte", startKey).append("$lt", endKey)))) {
                    totalDocs++;
                }
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long measureOracleApiSqlRead(List<String> orderIds) {
        if (!useOracleMongoApi || oracleMongoDatabase == null) return -1;

        int totalDocs = 0;

        // Warmup - query the JDBC-created table (has id/data columns) via $sql
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";

                String sqlStatement = "SELECT data FROM " + TRADES_COLLECTION +
                        " WHERE id >= '" + startKey + "' AND id < '" + endKey + "' ORDER BY id";

                for (Document doc : oracleMongoDatabase.aggregate(
                        Collections.singletonList(new Document("$sql", sqlStatement)))) {
                    totalDocs++;
                }
            }
        }

        // Measurement
        totalDocs = 0;
        long start = System.nanoTime();
        for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
            for (String orderId : orderIds) {
                String startKey = orderId + "#";
                String endKey = orderId + "$";

                String sqlStatement = "SELECT data FROM " + TRADES_COLLECTION +
                        " WHERE id >= '" + startKey + "' AND id < '" + endKey + "' ORDER BY id";

                for (Document doc : oracleMongoDatabase.aggregate(
                        Collections.singletonList(new Document("$sql", sqlStatement)))) {
                    totalDocs++;
                }
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long measureOracleJdbcJsonRead(List<String> orderIds) throws SQLException {
        String sql = "SELECT data FROM " + TRADES_COLLECTION + " WHERE id >= ? AND id < ? ORDER BY id";
        int totalDocs = 0;

        // Warmup
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setFetchSize(JDBC_FETCH_SIZE);
            for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                for (String orderId : orderIds) {
                    ps.setString(1, orderId + "#");
                    ps.setString(2, orderId + "$");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            totalDocs++;
                        }
                    }
                }
            }
        }

        // Measurement
        totalDocs = 0;
        long start = System.nanoTime();
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setFetchSize(JDBC_FETCH_SIZE);
            for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
                for (String orderId : orderIds) {
                    ps.setString(1, orderId + "#");
                    ps.setString(2, orderId + "$");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            totalDocs++;
                        }
                    }
                }
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    private long measureOracleJdbcRelationalRead(List<String> orderIds) throws SQLException {
        String sql = "SELECT id, order_id, doc_type, doc_id, ts, symbol, side, quantity, price, client_id, " +
                "fill_qty, fill_price, venue, source, annotation_type, annotation_value, payload " +
                "FROM " + TRADES_REL_TABLE + " WHERE order_id = ? ORDER BY id";
        int totalDocs = 0;

        // Warmup
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setFetchSize(JDBC_FETCH_SIZE);
            for (int w = 0; w < WARMUP_ITERATIONS; w++) {
                for (String orderId : orderIds) {
                    ps.setString(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            totalDocs++;
                        }
                    }
                }
            }
        }

        // Measurement
        totalDocs = 0;
        long start = System.nanoTime();
        try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(sql)) {
            ps.setFetchSize(JDBC_FETCH_SIZE);
            for (int m = 0; m < MEASUREMENT_ITERATIONS; m++) {
                for (String orderId : orderIds) {
                    ps.setString(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rs.getString(1);
                            totalDocs++;
                        }
                    }
                }
            }
        }
        long duration = System.nanoTime() - start;

        return duration;
    }

    // ==========================================================================
    // Test Execution
    // ==========================================================================

    private void runTradeAnnotationTest(String testId, int payloadSizeKB, int orderCount) throws SQLException {
        System.out.println("\n  Running " + testId + ": " + payloadSizeKB + "KB payload, " + orderCount + " orders");

        awrSnapshotBefore(testId);

        // Generate test data
        currentPayloadSizeKB = payloadSizeKB;
        currentOrderCount = orderCount;
        currentTestDocuments = generateOrderDocuments(orderCount, payloadSizeKB);
        currentReadTestOrderIds = selectReadTestOrders(currentComplexOrderIds);

        int totalDocs = currentTestDocuments.size();
        double avgDocsPerOrder = (double) totalDocs / orderCount;
        int readQueryCount = currentReadTestOrderIds.size();

        System.out.println("    Generated: " + totalDocs + " documents (" + avgDocsPerOrder + " avg/order)");
        System.out.println("    Read queries: " + readQueryCount + " (" + currentComplexOrderIds.size() + " complex orders)");

        // Insert phase
        long mongoInsertNanos = insertMongoNative(currentTestDocuments);
        long oracleApiInsertNanos = insertOracleMongoApi(currentTestDocuments);
        long oracleJdbcJsonInsertNanos = insertOracleJdbcJson(currentTestDocuments);
        long oracleJdbcRelInsertNanos = insertOracleJdbcRelational(currentTestDocuments);

        // Gather Oracle stats for optimal query plans
        gatherOracleStats();

        // Read phase
        long mongoReadNanos = measureMongoNativeRead(currentReadTestOrderIds);
        long oracleNativeApiReadNanos = measureOracleNativeApiRead(currentReadTestOrderIds);
        long oracleApiSqlReadNanos = measureOracleApiSqlRead(currentReadTestOrderIds);
        long oracleJdbcJsonReadNanos = measureOracleJdbcJsonRead(currentReadTestOrderIds);
        long oracleJdbcRelReadNanos = measureOracleJdbcRelationalRead(currentReadTestOrderIds);

        // Calculate avg docs per read
        double avgDocsPerRead = avgDocsPerOrder;  // Approximation

        // Store results
        results.put(testId, new TradeTestResult(
                testId,
                payloadSizeKB + "KB payload, " + orderCount + " orders",
                payloadSizeKB,
                orderCount,
                totalDocs,
                mongoInsertNanos,
                oracleApiInsertNanos,
                oracleJdbcJsonInsertNanos,
                oracleJdbcRelInsertNanos,
                mongoReadNanos,
                oracleNativeApiReadNanos,
                oracleApiSqlReadNanos,
                oracleJdbcJsonReadNanos,
                oracleJdbcRelReadNanos,
                readQueryCount,
                avgDocsPerRead
        ));

        // Capture SQL Monitor for key queries
        captureSqlMonitor(testId);

        // Print summary line
        System.out.printf("    INSERT: Mongo=%,d | API=%,d | JSON=%,d | REL=%,d ns%n",
                mongoInsertNanos, oracleApiInsertNanos, oracleJdbcJsonInsertNanos, oracleJdbcRelInsertNanos);
        System.out.printf("    READ:   Mongo=%,d | API=%,d | $sql=%,d | JSON=%,d | REL=%,d ns%n",
                mongoReadNanos, oracleNativeApiReadNanos, oracleApiSqlReadNanos, oracleJdbcJsonReadNanos, oracleJdbcRelReadNanos);

        awrSnapshotAfter(testId);
    }

    private void gatherOracleStats() throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + TRADES_COLLECTION + "', cascade => TRUE); END;");
            stmt.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + TRADES_REL_TABLE + "', cascade => TRUE); END;");
        }
    }

    private void captureSqlMonitor(String testId) {
        // Capture SQL Monitor for JSON query
        String jsonSql = "SELECT data FROM " + TRADES_COLLECTION + " WHERE id >= ? AND id < ? ORDER BY id";
        String relSql = "SELECT id, order_id, doc_type, doc_id, ts, symbol, side, quantity, price, client_id, " +
                "fill_qty, fill_price, venue, source, annotation_type, annotation_value, payload " +
                "FROM " + TRADES_REL_TABLE + " WHERE order_id = ? ORDER BY id";

        String jsonMonitor = captureSqlMonitorHtmlWithSqlId(jsonSql);
        String relMonitor = captureSqlMonitorHtmlWithSqlId(relSql);

        sqlMonitors.put(testId, new String[]{jsonMonitor, relMonitor});
        sqlDetails.put(testId, new String[]{
                "db.trade_documents.find({ _id: { $gte: 'ORD_00000#', $lt: 'ORD_00000$' } })",
                jsonSql,
                relSql
        });

        // Write SQL Monitor files
        if (jsonMonitor != null && !jsonMonitor.isEmpty()) {
            writeSqlMonitorFile(testId + "_json", jsonMonitor);
        }
        if (relMonitor != null && !relMonitor.isEmpty()) {
            writeSqlMonitorFile(testId + "_rel", relMonitor);
        }
    }

    private String captureSqlMonitorHtmlWithSqlId(String sql) {
        try {
            // Find SQL_ID for the query pattern
            String findSqlId = "SELECT sql_id FROM v$sql WHERE sql_text LIKE ? AND rownum = 1";
            String sqlId = null;

            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(findSqlId)) {
                ps.setString(1, sql.replace("?", "%").substring(0, Math.min(sql.length(), 100)) + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sqlId = rs.getString(1);
                    }
                }
            }

            if (sqlId == null) return null;

            // Generate SQL Monitor report
            String monitor = "SELECT DBMS_SQLTUNE.REPORT_SQL_MONITOR(sql_id => ?, type => 'HTML') FROM dual";
            try (PreparedStatement ps = oracleJdbcConnection.prepareStatement(monitor)) {
                ps.setString(1, sqlId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            // SQL Monitor not available - ignore
        }
        return null;
    }

    private void writeSqlMonitorFile(String name, String content) {
        try {
            Path dir = Path.of(SQL_MONITOR_DIR);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("monitor_" + name + ".html"), content);
        } catch (IOException e) {
            System.out.println("  Warning: Could not write SQL Monitor file: " + e.getMessage());
        }
    }

    // ==========================================================================
    // Tests
    // ==========================================================================

    @Test
    @Order(1)
    @DisplayName("TA1_4KB_1K: 4KB payload, 1K orders")
    void testTA1_4KB_1K() throws SQLException {
        runTradeAnnotationTest("TA1_4KB_1K", 4, SMALL_ORDER_COUNT);
    }

    @Test
    @Order(2)
    @DisplayName("TA2_4KB_10K: 4KB payload, 10K orders")
    void testTA2_4KB_10K() throws SQLException {
        runTradeAnnotationTest("TA2_4KB_10K", 4, MEDIUM_ORDER_COUNT);
    }

    @Test
    @Order(3)
    @DisplayName("TA3_6KB_1K: 6KB payload, 1K orders")
    void testTA3_6KB_1K() throws SQLException {
        runTradeAnnotationTest("TA3_6KB_1K", 6, SMALL_ORDER_COUNT);
    }

    @Test
    @Order(4)
    @DisplayName("TA4_6KB_10K: 6KB payload, 10K orders")
    void testTA4_6KB_10K() throws SQLException {
        runTradeAnnotationTest("TA4_6KB_10K", 6, MEDIUM_ORDER_COUNT);
    }

    @Test
    @Order(5)
    @DisplayName("TA5_8KB_1K: 8KB payload, 1K orders")
    void testTA5_8KB_1K() throws SQLException {
        runTradeAnnotationTest("TA5_8KB_1K", 8, SMALL_ORDER_COUNT);
    }

    @Test
    @Order(6)
    @DisplayName("TA6_8KB_10K: 8KB payload, 10K orders")
    void testTA6_8KB_10K() throws SQLException {
        runTradeAnnotationTest("TA6_8KB_10K", 8, MEDIUM_ORDER_COUNT);
    }

    @Test
    @Order(7)
    @DisplayName("TA7_10KB_1K: 10KB payload, 1K orders")
    void testTA7_10KB_1K() throws SQLException {
        runTradeAnnotationTest("TA7_10KB_1K", 10, SMALL_ORDER_COUNT);
    }

    @Test
    @Order(8)
    @DisplayName("TA8_10KB_10K: 10KB payload, 10K orders")
    void testTA8_10KB_10K() throws SQLException {
        runTradeAnnotationTest("TA8_10KB_10K", 10, MEDIUM_ORDER_COUNT);
    }

    @Test
    @Order(9)
    @DisplayName("TA9_12KB_1K: 12KB payload, 1K orders")
    void testTA9_12KB_1K() throws SQLException {
        runTradeAnnotationTest("TA9_12KB_1K", 12, SMALL_ORDER_COUNT);
    }

    @Test
    @Order(10)
    @DisplayName("TA10_12KB_10K: 12KB payload, 10K orders")
    void testTA10_12KB_10K() throws SQLException {
        runTradeAnnotationTest("TA10_12KB_10K", 12, MEDIUM_ORDER_COUNT);
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
            System.out.println("    AWR Snapshot " + snapId + " created: " + description);
            return snapId;
        } catch (SQLException e) {
            System.out.println("    AWR snapshot failed: " + e.getMessage());
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
                String awrHtml = generateAwrHtmlReport(snaps[0], snaps[1], filename);
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
        System.out.println("  TRADE ANNOTATION SERVICE BENCHMARK RESULTS");
        System.out.println("=".repeat(90));

        System.out.println("\n  INSERT PERFORMANCE (nanoseconds):");
        System.out.println("  " + "-".repeat(86));
        System.out.printf("  %-15s %12s %12s %12s %12s %10s%n",
                "Test", "MongoDB", "API", "JDBC JSON", "JDBC REL", "Docs");
        System.out.println("  " + "-".repeat(86));

        for (TradeTestResult r : results.values()) {
            System.out.printf("  %-15s %,12d %,12d %,12d %,12d %,10d%n",
                    r.testId, r.mongoInsertNanos,
                    r.oracleApiInsertNanos > 0 ? r.oracleApiInsertNanos : 0,
                    r.oracleJdbcJsonInsertNanos, r.oracleJdbcRelInsertNanos,
                    r.totalDocuments);
        }

        System.out.println("\n  READ PERFORMANCE (nanoseconds for " + MEASUREMENT_ITERATIONS + " iterations):");
        System.out.println("  " + "-".repeat(100));
        System.out.printf("  %-15s %12s %12s %12s %12s %12s %10s%n",
                "Test", "MongoDB", "API Native", "API $sql", "JDBC JSON", "JDBC REL", "Queries");
        System.out.println("  " + "-".repeat(100));

        for (TradeTestResult r : results.values()) {
            System.out.printf("  %-15s %,12d %,12d %,12d %,12d %,12d %,10d%n",
                    r.testId, r.mongoReadNanos,
                    r.oracleNativeApiReadNanos > 0 ? r.oracleNativeApiReadNanos : 0,
                    r.oracleApiSqlReadNanos > 0 ? r.oracleApiSqlReadNanos : 0,
                    r.oracleJdbcJsonReadNanos, r.oracleJdbcRelReadNanos,
                    r.readQueryCount);
        }

        System.out.println("=".repeat(90));
    }

    private static void generateHtmlReport() {
        try {
            StringBuilder html = new StringBuilder();
            html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Trade Annotation Service Benchmark Report</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; background: #f5f5f5; }
                        .container { max-width: 1400px; margin: 0 auto; }
                        h1 { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 10px; }
                        h2 { color: #34495e; margin-top: 30px; }
                        .summary-box { background: white; border-radius: 8px; padding: 20px; margin: 20px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                        table { border-collapse: collapse; width: 100%; margin: 20px 0; background: white; }
                        th, td { border: 1px solid #ddd; padding: 12px; text-align: right; }
                        th { background: #3498db; color: white; }
                        tr:nth-child(even) { background: #f8f9fa; }
                        tr:hover { background: #e8f4f8; }
                        td:first-child { text-align: left; font-weight: bold; }
                        .chart-container { background: white; border-radius: 8px; padding: 20px; margin: 20px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                        .tabs { display: flex; gap: 5px; margin-bottom: 0; }
                        .tab { padding: 10px 20px; background: #ddd; border-radius: 8px 8px 0 0; cursor: pointer; }
                        .tab.active { background: white; border-bottom: 2px solid white; }
                        .tab-content { display: none; background: white; padding: 20px; border-radius: 0 8px 8px 8px; }
                        .tab-content.active { display: block; }
                        pre { background: #2d3436; color: #dfe6e9; padding: 15px; border-radius: 5px; overflow-x: auto; }
                        .metric { display: inline-block; padding: 5px 15px; margin: 5px; background: #3498db; color: white; border-radius: 20px; }
                        .winner-mongo { color: #27ae60; font-weight: bold; }
                        .winner-oracle { color: #e74c3c; font-weight: bold; }
                    </style>
                </head>
                <body>
                <div class="container">
                    <h1>Trade Annotation Service Benchmark Report</h1>

                    <div class="summary-box">
                        <h3>Test Configuration</h3>
                        <span class="metric">Workflow: Append-Only Documents</span>
                        <span class="metric">Query: Range Scan on Composite _id</span>
                        <span class="metric">Payload Sizes: 4-12 KB</span>
                        <span class="metric">Order Counts: 1K, 10K</span>
                        <span class="metric">Complex Order Ratio: 20%</span>
                    </div>
                """);

            // Results Table - INSERT
            html.append("<h2>Insert Performance</h2>");
            html.append("<table>");
            html.append("<tr><th>Test</th><th>Payload</th><th>Orders</th><th>Documents</th>");
            html.append("<th>MongoDB (ns)</th><th>Oracle API (ns)</th><th>JDBC JSON (ns)</th><th>JDBC REL (ns)</th></tr>");

            for (TradeTestResult r : results.values()) {
                html.append("<tr>");
                html.append("<td>").append(r.testId).append("</td>");
                html.append("<td>").append(r.payloadSizeKB).append(" KB</td>");
                html.append("<td>").append(String.format("%,d", r.orderCount)).append("</td>");
                html.append("<td>").append(String.format("%,d", r.totalDocuments)).append("</td>");
                html.append("<td>").append(String.format("%,d", r.mongoInsertNanos)).append("</td>");
                html.append("<td>").append(r.oracleApiInsertNanos > 0 ? String.format("%,d", r.oracleApiInsertNanos) : "N/A").append("</td>");
                html.append("<td>").append(String.format("%,d", r.oracleJdbcJsonInsertNanos)).append("</td>");
                html.append("<td>").append(String.format("%,d", r.oracleJdbcRelInsertNanos)).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");

            // Results Table - READ
            html.append("<h2>Read Performance (").append(MEASUREMENT_ITERATIONS).append(" iterations)</h2>");
            html.append("<table>");
            html.append("<tr><th>Test</th><th>Queries</th><th>MongoDB (ns)</th><th>API Native (ns)</th>");
            html.append("<th>API $sql (ns)</th><th>JDBC JSON (ns)</th><th>JDBC REL (ns)</th><th>Winner</th></tr>");

            for (TradeTestResult r : results.values()) {
                // Determine winner (lowest read time)
                long[] times = {r.mongoReadNanos, r.oracleJdbcJsonReadNanos, r.oracleJdbcRelReadNanos};
                long minTime = Arrays.stream(times).filter(t -> t > 0).min().orElse(0);
                String winner = "";
                if (minTime == r.mongoReadNanos) winner = "MongoDB";
                else if (minTime == r.oracleJdbcRelReadNanos) winner = "JDBC REL";
                else winner = "JDBC JSON";

                html.append("<tr>");
                html.append("<td>").append(r.testId).append("</td>");
                html.append("<td>").append(String.format("%,d", r.readQueryCount)).append("</td>");
                html.append("<td>").append(String.format("%,d", r.mongoReadNanos)).append("</td>");
                html.append("<td>").append(r.oracleNativeApiReadNanos > 0 ? String.format("%,d", r.oracleNativeApiReadNanos) : "N/A").append("</td>");
                html.append("<td>").append(r.oracleApiSqlReadNanos > 0 ? String.format("%,d", r.oracleApiSqlReadNanos) : "N/A").append("</td>");
                html.append("<td>").append(String.format("%,d", r.oracleJdbcJsonReadNanos)).append("</td>");
                html.append("<td>").append(String.format("%,d", r.oracleJdbcRelReadNanos)).append("</td>");
                html.append("<td class=\"").append(winner.equals("MongoDB") ? "winner-mongo" : "winner-oracle").append("\">")
                        .append(winner).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");

            // Charts
            html.append("""
                <h2>Performance Charts</h2>
                <div class="chart-container">
                    <canvas id="insertChart" height="100"></canvas>
                </div>
                <div class="chart-container">
                    <canvas id="readChart" height="100"></canvas>
                </div>
                """);

            // Chart.js data
            List<String> labels = results.values().stream().map(r -> r.testId).collect(Collectors.toList());
            List<Long> mongoInsert = results.values().stream().map(r -> r.mongoInsertNanos).collect(Collectors.toList());
            List<Long> jdbcJsonInsert = results.values().stream().map(r -> r.oracleJdbcJsonInsertNanos).collect(Collectors.toList());
            List<Long> jdbcRelInsert = results.values().stream().map(r -> r.oracleJdbcRelInsertNanos).collect(Collectors.toList());

            List<Long> mongoRead = results.values().stream().map(r -> r.mongoReadNanos).collect(Collectors.toList());
            List<Long> jdbcJsonRead = results.values().stream().map(r -> r.oracleJdbcJsonReadNanos).collect(Collectors.toList());
            List<Long> jdbcRelRead = results.values().stream().map(r -> r.oracleJdbcRelReadNanos).collect(Collectors.toList());

            html.append("<script>");
            html.append("const labels = ").append(toJsonArray(labels)).append(";");

            // Insert chart
            html.append("""
                new Chart(document.getElementById('insertChart'), {
                    type: 'bar',
                    data: {
                        labels: labels,
                        datasets: [
                            { label: 'MongoDB', data: %s, backgroundColor: 'rgba(39, 174, 96, 0.7)' },
                            { label: 'JDBC JSON', data: %s, backgroundColor: 'rgba(52, 152, 219, 0.7)' },
                            { label: 'JDBC REL', data: %s, backgroundColor: 'rgba(231, 76, 60, 0.7)' }
                        ]
                    },
                    options: {
                        responsive: true,
                        plugins: { title: { display: true, text: 'Insert Performance (nanoseconds)' } },
                        scales: { y: { beginAtZero: true } }
                    }
                });
                """.formatted(mongoInsert, jdbcJsonInsert, jdbcRelInsert));

            // Read chart
            html.append("""
                new Chart(document.getElementById('readChart'), {
                    type: 'bar',
                    data: {
                        labels: labels,
                        datasets: [
                            { label: 'MongoDB', data: %s, backgroundColor: 'rgba(39, 174, 96, 0.7)' },
                            { label: 'JDBC JSON', data: %s, backgroundColor: 'rgba(52, 152, 219, 0.7)' },
                            { label: 'JDBC REL', data: %s, backgroundColor: 'rgba(231, 76, 60, 0.7)' }
                        ]
                    },
                    options: {
                        responsive: true,
                        plugins: { title: { display: true, text: 'Read Performance (nanoseconds, %d iterations)' } },
                        scales: { y: { beginAtZero: true } }
                    }
                });
                """.formatted(mongoRead, jdbcJsonRead, jdbcRelRead, MEASUREMENT_ITERATIONS));

            html.append("</script>");

            // SQL Details tabs
            html.append("<h2>Query Details</h2>");
            html.append("<div class=\"tabs\">");
            html.append("<div class=\"tab active\" onclick=\"showTab('mongoTab')\">MongoDB</div>");
            html.append("<div class=\"tab\" onclick=\"showTab('jsonTab')\">Oracle JSON</div>");
            html.append("<div class=\"tab\" onclick=\"showTab('relTab')\">Oracle Relational</div>");
            html.append("</div>");

            html.append("<div id=\"mongoTab\" class=\"tab-content active\">");
            html.append("<h3>MongoDB Range Query</h3>");
            html.append("<pre>db.trade_documents.find({\n");
            html.append("  _id: { $gte: \"ORD_00000#\", $lt: \"ORD_00000$\" }\n");
            html.append("}).sort({ _id: 1 })</pre>");
            html.append("</div>");

            html.append("<div id=\"jsonTab\" class=\"tab-content\">");
            html.append("<h3>Oracle JDBC JSON Query</h3>");
            html.append("<pre>SELECT data FROM trade_documents\n");
            html.append("WHERE id >= ? AND id < ?\n");
            html.append("ORDER BY id\n");
            html.append("-- Parameters: 'ORD_00000#', 'ORD_00000$'</pre>");
            html.append("</div>");

            html.append("<div id=\"relTab\" class=\"tab-content\">");
            html.append("<h3>Oracle JDBC Relational Query</h3>");
            html.append("<pre>SELECT id, order_id, doc_type, doc_id, ts,\n");
            html.append("       symbol, side, quantity, price, client_id,\n");
            html.append("       fill_qty, fill_price, venue,\n");
            html.append("       source, annotation_type, annotation_value, payload\n");
            html.append("FROM trade_documents_rel\n");
            html.append("WHERE order_id = ?\n");
            html.append("ORDER BY id\n");
            html.append("-- Parameter: 'ORD_00000'</pre>");
            html.append("</div>");

            // Tab switching script
            html.append("""
                <script>
                function showTab(tabId) {
                    document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
                    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                    document.getElementById(tabId).classList.add('active');
                    event.target.classList.add('active');
                }
                </script>
                """);

            // SQL Monitor section
            if (!sqlMonitors.isEmpty()) {
                html.append("<h2>SQL Monitor Reports</h2>");
                html.append("<p>SQL Monitor reports are available in: <code>reports/sql_monitor/</code></p>");
                html.append("<ul>");
                for (String testId : sqlMonitors.keySet()) {
                    html.append("<li><a href=\"sql_monitor/monitor_").append(testId).append("_json.html\">")
                            .append(testId).append(" - JSON</a> | ");
                    html.append("<a href=\"sql_monitor/monitor_").append(testId).append("_rel.html\">")
                            .append(testId).append(" - Relational</a></li>");
                }
                html.append("</ul>");
            }

            // AWR section
            if (!awrReportContent.isEmpty()) {
                html.append("<h2>AWR Reports</h2>");
                html.append("<p>AWR reports are available in: <code>build/reports/awr/</code></p>");
                html.append("<ul>");
                for (String category : awrReportContent.keySet()) {
                    html.append("<li><a href=\"../build/reports/awr/awr_")
                            .append(category.replaceAll("[^a-zA-Z0-9]", "_"))
                            .append(".html\">").append(category).append("</a></li>");
                }
                html.append("</ul>");
            }

            html.append("</div></body></html>");

            Path reportPath = Path.of("reports/trade_annotation_report.html");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, html.toString());
            System.out.println("\nTrade Annotation report generated: " + reportPath.toAbsolutePath());

        } catch (IOException e) {
            System.out.println("Failed to generate HTML report: " + e.getMessage());
        }
    }

    private static String toJsonArray(List<?> list) {
        return "[" + list.stream()
                .map(o -> o instanceof String ? "\"" + o + "\"" : String.valueOf(o))
                .collect(Collectors.joining(",")) + "]";
    }
}
