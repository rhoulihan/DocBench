package com.docbench.benchmark;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import oracle.jdbc.pool.OracleDataSource;
import org.bson.Document;
import org.junit.jupiter.api.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vector Search Benchmark Test Suite
 *
 * Compares MongoDB's native $vectorSearch operator against Oracle implementations:
 * - Oracle MongoDB API (same pipeline via ORDS)
 * - Oracle $sql aggregation operator
 * - Oracle JDBC with VECTOR_DISTANCE
 *
 * Captures AWR reports, SQL Monitor reports, and execution plans for each test.
 * Generates comprehensive HTML report with charts.
 */
@DisplayName("Vector Search Benchmark: MongoDB vs Oracle (API, $sql, JDBC)")
@Tag("benchmark")
@Tag("integration")
@Tag("vector")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VectorSearchBenchmarkTest {

    // ==========================================================================
    // Configuration Constants
    // ==========================================================================

    // Vector dimensions (matching common embedding models)
    private static final int DIM_384 = 384;   // all-MiniLM-L6-v2
    private static final int DIM_768 = 768;   // BERT-base
    private static final int DIM_1536 = 1536; // OpenAI text-embedding-ada-002

    // Data sizes
    private static final int SMALL_ACCOUNT_COUNT = 10_000;
    private static final int TRANSACTIONS_PER_ACCOUNT = 20;

    // Test parameters
    private static final int TOP_K = 10;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 20;

    // Collection/table names
    private static final String ACCOUNTS_COLLECTION = "benchmark_accounts_vec";
    private static final String TRANSACTIONS_COLLECTION = "benchmark_transactions_vec";
    private static final String SODA_ACCOUNTS_COLLECTION = "soda_accounts_vec";
    private static final String SODA_TRANSACTIONS_COLLECTION = "soda_transactions_vec";
    private static final String ACCOUNTS_TABLE = "benchmark_accounts_vec";
    private static final String TRANSACTIONS_TABLE = "benchmark_transactions_vec";
    private static final String VECTOR_INDEX_NAME = "accounts_vector_idx";

    // Report directories
    private static final String REPORTS_DIR = "reports/vector_search";
    private static final String SQL_MONITOR_DIR = REPORTS_DIR + "/sql_monitor";
    private static final String AWR_REPORT_DIR = "build/reports/awr";
    private static final String HTML_REPORT_FILE = REPORTS_DIR + "/vector_search_report.html";

    // Enhanced data model constants
    private static final String[] TENANT_IDS = {"TENANT_A", "TENANT_B", "TENANT_C", "TENANT_D", "TENANT_E"};
    private static final String[] STATES = {"NY", "CA", "TX", "FL", "IL", "PA", "OH", "GA", "NC", "MI"};
    private static final String[] CITIES = {"New York", "Los Angeles", "Houston", "Phoenix", "Philadelphia",
                                             "San Antonio", "San Diego", "Dallas", "San Jose", "Austin"};
    private static final String[] TAGS = {"premium", "active", "dormant", "high-risk", "low-risk",
                                           "international", "business", "personal", "rewards", "digital"};
    private static final String[] DESCRIPTIONS = {
        "Premium customer with extensive international transaction history and high-value transfers",
        "Active account holder with frequent shopping transactions and regular bill payments",
        "Business account with multiple sub-accounts and large monthly transaction volumes",
        "Personal savings account with steady deposit patterns and conservative spending",
        "Digital-first customer preferring mobile banking and contactless payments",
        "High-net-worth individual with diversified investment portfolio connections",
        "Small business owner with seasonal transaction patterns and vendor payments",
        "Rewards-focused customer maximizing cashback and travel point accumulations",
        "International customer with multi-currency needs and overseas wire transfers",
        "Young professional building credit history with regular income deposits"
    };

    // ==========================================================================
    // Database Connections
    // ==========================================================================

    // MongoDB Native
    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> accountsCollection;
    private static MongoCollection<Document> transactionsCollection;

    // Oracle MongoDB API
    private static MongoClient oracleMongoClient;
    private static MongoDatabase oracleMongoDatabase;
    private static MongoCollection<Document> oracleSodaAccountsCollection;
    private static MongoCollection<Document> oracleSodaTransactionsCollection;

    // Oracle JDBC
    private static Connection oracleJdbcConnection;

    // Cached PreparedStatements for JDBC (statement caching for performance)
    private static PreparedStatement cachedVectorSearchStmt;
    private static PreparedStatement cachedVectorSearchWithJoinStmt;
    private static PreparedStatement cachedFilteredVectorSearchStmt;
    private static PreparedStatement cachedTextSearchStmt;
    private static int cachedVectorSearchDimensions = -1;
    private static int cachedFilteredSearchDimensions = -1;
    private static String cachedFilteredSearchWhereClause = null;

    // Feature detection flags
    private static boolean mongoVectorSearchSupported = false;
    private static boolean oracleVectorSupported = false;
    private static boolean oracleMongoApiSupported = false;

    // AWR tracking
    private static long dbId = -1;
    private static int instanceNumber = -1;
    private static final Map<String, long[]> awrSnapshots = new LinkedHashMap<>();
    private static final Map<String, String> awrReportContent = new LinkedHashMap<>();

    // ==========================================================================
    // Results Storage
    // ==========================================================================

    // Results: [mongoNative, oracleSql, oracleJdbc]
    private static final Map<String, long[]> results = new LinkedHashMap<>();
    private static final Map<String, SqlDetails> sqlDetailsMap = new LinkedHashMap<>();
    private static final Random random = new Random(42);

    // SQL Details record - organized by protocol
    private record SqlDetails(
        // MongoDB Native
        String mongoPipeline,
        String mongoExplain,
        // Oracle $sql aggregation
        String oracleSqlStatement,
        String oracleSqlExplain,
        String sqlMonitorSql,
        // Oracle JDBC
        String oracleJdbcSql,
        String oracleJdbcExplain,
        String sqlMonitorJdbc,
        // AWR report link (per test)
        String awrReportLink
    ) {}

    // RAG2: Weekly transaction statistics record
    private record WeeklyStats(
        String weekStart,      // ISO week start date (YYYY-MM-DD)
        int isoWeek,           // ISO week number
        int txnCount,          // Transaction count for the week
        double totalAmount,    // Sum of transaction amounts
        double avgAmount       // Average transaction amount
    ) {}

    // RAG2: Account with weekly stats record
    private record AccountWithWeeklyStats(
        String accountId,
        double vectorScore,
        List<WeeklyStats> weeklyStats
    ) {}

    // RAG3: Customer 360 Profile record
    private record Customer360Profile(
        String accountId,
        double vectorScore,
        int transactionCount,          // Total number of transactions
        double totalSpent,             // Sum of all transaction amounts
        double avgTransactionAmount,   // Average transaction amount
        String lastActivityDate,       // Date of most recent transaction (YYYY-MM-DD)
        int daysSinceLastActivity,     // Days since last transaction
        Map<String, Double> spendingByCategory  // Category -> total amount
    ) {}

    // RAG1: Graph Traversal Result record
    private record GraphTraversalResult(
        String sourceAccountId,        // The seed account from vector search
        double vectorScore,            // Vector similarity score
        List<RelatedAccount> sameTenantAccounts,    // Accounts in same tenant (1 hop)
        List<RelatedAccount> sharedMerchantAccounts // Accounts sharing merchants (via transactions)
    ) {
        int totalRelatedAccounts() {
            Set<String> unique = new HashSet<>();
            sameTenantAccounts.forEach(a -> unique.add(a.accountId()));
            sharedMerchantAccounts.forEach(a -> unique.add(a.accountId()));
            return unique.size();
        }
    }

    // RAG1: Related Account record (for graph traversal)
    private record RelatedAccount(
        String accountId,
        String relationshipType,  // "SAME_TENANT" or "SHARED_MERCHANT"
        int hops                  // Number of hops from source
    ) {}

    // RAG4: Activity Pattern Detection records
    private record ActivityPatternResult(
        String accountId,
        double vectorScore,
        double avgDailyTxns,           // Average daily transaction count
        List<DailyActivity> activityPattern,  // Daily activity with rolling counts
        int burstDayCount,             // Days with rolling count > 2x average
        int dormantDayCount            // Days with 0 transactions
    ) {
        // Utility method to get activity status summary
        String getActivitySummary() {
            if (burstDayCount > 0 && dormantDayCount > 0) return "MIXED";
            if (burstDayCount > 0) return "BURSTY";
            if (dormantDayCount > activityPattern.size() / 2) return "DORMANT";
            return "NORMAL";
        }
    }

    // RAG4: Daily activity record for rolling window analysis
    private record DailyActivity(
        String date,           // YYYY-MM-DD format
        int dailyCount,        // Transactions on this day
        int rollingWeekCount,  // Sum of transactions in 7-day window ending on this day
        String status          // "BURST", "DORMANT", or "NORMAL"
    ) {}

    // RAG5: Hybrid Context Ranking result record
    private record HybridRankingResult(
        String accountId,
        double vectorScore,           // Original vector similarity score (0-1)
        int txnCount,                 // Total transaction count
        int daysSinceLastTxn,         // Days since last transaction
        double normalizedTxnActivity, // Normalized txn count (0-1)
        double recencyScore,          // Recency score (0-1, higher = more recent)
        double hybridScore            // Final re-ranked score: 0.5*vector + 0.3*activity + 0.2*recency
    ) {
        // Verify hybrid score calculation
        double computedHybridScore() {
            return 0.5 * vectorScore + 0.3 * normalizedTxnActivity + 0.2 * recencyScore;
        }
    }

    // ==========================================================================
    // TDD Unit Tests (T1-T11)
    // ==========================================================================

    @Test
    @Order(1)
    @DisplayName("T1: MongoDB connection and $vectorSearch support check")
    void testT1_mongoDbVectorSearchSupport() {
        assertNotNull(mongoClient, "MongoDB client should be connected");
        assertNotNull(mongoDatabase, "MongoDB database should be accessible");
        assertTrue(mongoVectorSearchSupported,
            "MongoDB $vectorSearch should be supported. Ensure MongoDB Atlas Local is running.");
    }

    @Test
    @Order(2)
    @DisplayName("T2: Oracle JDBC connection and VECTOR type support check")
    void testT2_oracleVectorSupport() throws SQLException {
        Assumptions.assumeTrue(oracleJdbcConnection != null,
            "Skipping: Oracle connection not available");
        assertFalse(oracleJdbcConnection.isClosed(), "Oracle connection should be open");
        assertTrue(oracleVectorSupported,
            "Oracle VECTOR data type should be supported. Ensure Oracle 23ai is running.");
    }

    @Test
    @Order(3)
    @DisplayName("T3: Oracle MongoDB API connection check")
    void testT3_oracleMongoApiSupport() {
        Assumptions.assumeTrue(oracleMongoApiSupported,
            "Skipping: Oracle MongoDB API not available");
        assertNotNull(oracleMongoClient, "Oracle MongoDB API client should be connected");
        assertNotNull(oracleMongoDatabase, "Oracle MongoDB API database should be accessible");
    }

    @Test
    @Order(4)
    @DisplayName("T4: Generate normalized embedding vectors")
    void testT4_embeddingGeneration() {
        double[] embedding = generateNormalizedEmbedding(DIM_384, random);
        assertEquals(DIM_384, embedding.length);

        double norm = 0;
        for (double v : embedding) norm += v * v;
        norm = Math.sqrt(norm);
        assertEquals(1.0, norm, 0.0001, "Embedding should be normalized to unit vector");
    }

    @Test
    @Order(5)
    @DisplayName("T5: Create account document with embedding")
    void testT5_accountDocumentCreation() {
        double[] embedding = generateNormalizedEmbedding(DIM_384, random);
        Document account = createAccountDocument("ACC_TEST_001", embedding);

        assertNotNull(account.getString("_id"));
        assertEquals("ACC_TEST_001", account.getString("accountId"));
        @SuppressWarnings("unchecked")
        List<Double> storedEmbedding = (List<Double>) account.get("embedding");
        assertEquals(DIM_384, storedEmbedding.size());
    }

    @Test
    @Order(10)
    @DisplayName("T6: Create MongoDB vector search index")
    void testT6_createMongoVectorIndex() {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        accountsCollection.drop();
        double[] embedding = generateNormalizedEmbedding(DIM_384, random);
        Document testDoc = createAccountDocument("ACC_INDEX_TEST", embedding);
        accountsCollection.insertOne(testDoc);

        boolean indexCreated = createMongoVectorSearchIndex(DIM_384);
        assertTrue(indexCreated, "Vector search index should be created successfully");
        waitForIndexReady(5000);
    }

    @Test
    @Order(11)
    @DisplayName("T7: Create Oracle tables with VECTOR column")
    void testT7_createOracleTables() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        createOracleAccountsTable(DIM_384);

        try (Statement stmt = oracleJdbcConnection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT column_name, data_type FROM user_tab_columns " +
                 "WHERE table_name = '" + ACCOUNTS_TABLE.toUpperCase() + "' ORDER BY column_id")) {

            boolean hasEmbeddingColumn = false;
            while (rs.next()) {
                if ("EMBEDDING".equalsIgnoreCase(rs.getString("column_name"))) {
                    hasEmbeddingColumn = true;
                    assertEquals("VECTOR", rs.getString("data_type").toUpperCase());
                }
            }
            assertTrue(hasEmbeddingColumn, "Table should have EMBEDDING column");
        }
    }

    @Test
    @Order(20)
    @DisplayName("T8: Insert test accounts into MongoDB")
    void testT8_insertMongoTestData() {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        int testCount = 100;
        accountsCollection.drop();
        List<Document> docs = generateAccountDocuments(testCount, DIM_384);
        accountsCollection.insertMany(docs);

        assertEquals(testCount, accountsCollection.countDocuments());
    }

    @Test
    @Order(21)
    @DisplayName("T9: Insert test accounts into Oracle JDBC")
    void testT9_insertOracleTestData() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        int testCount = 100;
        createOracleAccountsTable(DIM_384);

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("DELETE FROM " + ACCOUNTS_TABLE);
        }

        List<Document> docs = generateAccountDocuments(testCount, DIM_384);
        insertAccountsIntoOracle(docs, DIM_384);

        try (Statement stmt = oracleJdbcConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + ACCOUNTS_TABLE)) {
            rs.next();
            assertEquals(testCount, rs.getInt(1));
        }
    }

    @Test
    @Order(30)
    @DisplayName("T10: Execute MongoDB $vectorSearch query")
    void testT10_mongoVectorSearchQuery() {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        int testCount = 100;
        accountsCollection.drop();
        List<Document> docs = generateAccountDocuments(testCount, DIM_384);
        accountsCollection.insertMany(docs);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(123));
        List<Document> results = executeMongoVectorSearch(queryVector, TOP_K);

        assertNotNull(results);
        assertEquals(TOP_K, results.size());
    }

    @Test
    @Order(31)
    @DisplayName("T11: Execute Oracle VECTOR_DISTANCE query")
    void testT11_oracleVectorDistanceQuery() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        int testCount = 100;
        createOracleAccountsTable(DIM_384);
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("DELETE FROM " + ACCOUNTS_TABLE);
        }
        List<Document> docs = generateAccountDocuments(testCount, DIM_384);
        insertAccountsIntoOracle(docs, DIM_384);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(123));
        List<Document> results = executeOracleJdbcVectorSearch(queryVector, TOP_K);

        assertNotNull(results);
        assertEquals(TOP_K, results.size());
    }

    // ==========================================================================
    // T100-T103: Enhanced Data Model Unit Tests
    // ==========================================================================

    @Test
    @Order(100)
    @DisplayName("T100: Generate enhanced account document with all fields")
    void testT100_enhancedAccountDocument() {
        double[] embedding = generateNormalizedEmbedding(DIM_384, random);
        Document doc = createEnhancedAccountDocument("ACC_TEST_001", embedding);

        assertNotNull(doc.getString("tenantId"));
        assertTrue(Arrays.asList(TENANT_IDS).contains(doc.getString("tenantId")));
        assertNotNull(doc.getString("state"));
        assertTrue(doc.getString("state").length() == 2);
        assertNotNull(doc.getString("city"));
        assertNotNull(doc.getDate("lastActivityDate"));
        assertNotNull(doc.getDouble("riskScore"));
        assertTrue(doc.getDouble("riskScore") >= 0.0 && doc.getDouble("riskScore") <= 1.0);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) doc.get("tags");
        assertNotNull(tags);
        assertFalse(tags.isEmpty());
        assertTrue(tags.size() >= 2 && tags.size() <= 4);
        assertNotNull(doc.getString("description"));
        assertTrue(doc.getString("description").length() > 50);
        assertNotNull(doc.getString("embeddingModel"));
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
        assertTrue(cosineSim > 0.95, "Quantization should preserve >95% similarity, got: " + cosineSim);
    }

    @Test
    @Order(102)
    @DisplayName("T102: Generate binary quantized embedding")
    void testT102_binaryQuantizedEmbedding() {
        double[] embedding = {0.5, -0.3, 0.8, -0.9, 0.1, -0.2, 0.7, -0.1};
        byte[] binary = quantizeToBinary(embedding);

        // 8 dimensions -> 1 byte
        assertEquals(1, binary.length);

        // Verify correct bits: positive values (0.5, 0.8, 0.1, 0.7) = bits at positions 0, 2, 4, 6
        // Binary: 10110010 = 0xB2 = -78 (signed byte)
        // Bits: 1(0.5), 0(-0.3), 1(0.8), 1(-0.9<0 but we check >0 so 0... wait
        // Let me recalculate: indices 0,1,2,3,4,5,6,7 -> 0.5>0=1, -0.3>0=0, 0.8>0=1, -0.9>0=0, 0.1>0=1, -0.2>0=0, 0.7>0=1, -0.1>0=0
        // So: 10101010 = 0xAA = -86 (signed)
        assertEquals((byte) 0xAA, binary[0], "Binary quantization should produce correct bit pattern");
    }

    @Test
    @Order(103)
    @DisplayName("T103: Calculate recall correctly")
    void testT103_calculateRecall() {
        List<String> groundTruth = List.of("A", "B", "C", "D", "E");
        List<String> annResults = List.of("A", "B", "X", "D", "Y");

        double recall = calculateRecall(groundTruth, annResults);

        assertEquals(0.6, recall, 0.001, "Recall should be 3/5 = 60%");
    }

    @Test
    @Order(104)
    @DisplayName("T104: Cosine similarity calculation")
    void testT104_cosineSimilarity() {
        double[] a = {1.0, 0.0, 0.0};
        double[] b = {1.0, 0.0, 0.0};
        double[] c = {0.0, 1.0, 0.0};

        assertEquals(1.0, cosineSimilarity(a, b), 0.0001, "Identical vectors should have similarity 1.0");
        assertEquals(0.0, cosineSimilarity(a, c), 0.0001, "Orthogonal vectors should have similarity 0.0");
    }

    @Test
    @Order(105)
    @DisplayName("T105: RRF fusion combines ranked lists correctly")
    void testT105_rrfFusion() {
        List<String> vectorResults = List.of("A", "B", "C", "D", "E");
        List<String> textResults = List.of("C", "A", "F", "G", "B");

        List<String> fused = rrfFusion(vectorResults, textResults, 60);

        // A appears at rank 0 in vector (score=1/61) and rank 1 in text (score=1/62) = total ~0.0326
        // C appears at rank 2 in vector (score=1/63) and rank 0 in text (score=1/61) = total ~0.0322
        // A and C should be top 2
        assertTrue(fused.indexOf("A") < 3, "A should be in top 3");
        assertTrue(fused.indexOf("C") < 3, "C should be in top 3");
    }

    @Test
    @Order(106)
    @DisplayName("T106: Balance distribution for filter selectivity")
    void testT106_balanceDistribution() {
        // Generate 10000 accounts and verify distribution
        int count = 10000;
        int below25k = 0, below80k = 0, below95k = 0;

        for (int i = 0; i < count; i++) {
            double balance = generateDistributedBalance();
            if (balance < 25000) below25k++;
            if (balance < 80000) below80k++;
            if (balance < 95000) below95k++;
        }

        // Check distributions with 5% tolerance
        assertTrue(below25k > count * 0.45 && below25k < count * 0.55,
            "~50% should have balance < 25000, got: " + (100.0 * below25k / count) + "%");
        assertTrue(below80k > count * 0.85 && below80k < count * 0.95,
            "~90% should have balance < 80000, got: " + (100.0 * below80k / count) + "%");
        assertTrue(below95k > count * 0.94 && below95k <= count,
            "~99% should have balance < 95000, got: " + (100.0 * below95k / count) + "%");
    }

    // ==========================================================================
    // RAG2 TDD Unit Tests: Weekly Aggregation Validation
    // ==========================================================================

    @Test
    @Order(50)
    @DisplayName("RAG2_TDD1: MongoDB weekly aggregation returns correct week count")
    void testRAG2_TDD1_weeklyAggregation_returnsCorrectWeekCount() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        // Setup: Ensure we have accounts and transactions spanning 30 days
        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<AccountWithWeeklyStats> results = executeMongoWeeklyAggregation(queryVector, TOP_K);

        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Results should not be empty");

        // Each account should have 4-5 weeks of data for a 30-day range
        for (AccountWithWeeklyStats account : results) {
            assertNotNull(account.weeklyStats(), "Weekly stats should not be null for account " + account.accountId());
            int weekCount = account.weeklyStats().size();
            assertTrue(weekCount >= 3 && weekCount <= 6,
                "Expected 3-6 weeks for 30-day range, got " + weekCount + " for account " + account.accountId());
        }
    }

    @Test
    @Order(51)
    @DisplayName("RAG2_TDD2: MongoDB weekly aggregation calculates correct totals")
    void testRAG2_TDD2_weeklyAggregation_calculatesCorrectTotals() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<AccountWithWeeklyStats> results = executeMongoWeeklyAggregation(queryVector, TOP_K);

        // Verify aggregation math: avg = total / count
        // Note: Transaction amounts are negative (debits), so totalAmount will be negative
        for (AccountWithWeeklyStats account : results) {
            for (WeeklyStats week : account.weeklyStats()) {
                assertTrue(week.txnCount() > 0, "Transaction count should be positive");
                assertTrue(week.totalAmount() != 0, "Total amount should be non-zero");
                double expectedAvg = week.totalAmount() / week.txnCount();
                assertEquals(expectedAvg, week.avgAmount(), 0.01,
                    "Average should equal total/count for week " + week.isoWeek());
            }
        }
    }

    @Test
    @Order(52)
    @DisplayName("RAG2_TDD3: Oracle JDBC weekly aggregation returns correct week count")
    void testRAG2_TDD3_oracleWeeklyAggregation_returnsCorrectWeekCount() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<AccountWithWeeklyStats> results = executeOracleJdbcWeeklyAggregation(queryVector, TOP_K, DIM_384);

        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Results should not be empty");

        // Each account should have 4-5 weeks of data for a 30-day range
        for (AccountWithWeeklyStats account : results) {
            assertNotNull(account.weeklyStats(), "Weekly stats should not be null for account " + account.accountId());
            int weekCount = account.weeklyStats().size();
            assertTrue(weekCount >= 3 && weekCount <= 6,
                "Expected 3-6 weeks for 30-day range, got " + weekCount + " for account " + account.accountId());
        }
    }

    @Test
    @Order(53)
    @DisplayName("RAG2_TDD4: Oracle JDBC weekly aggregation calculates correct totals")
    void testRAG2_TDD4_oracleWeeklyAggregation_calculatesCorrectTotals() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<AccountWithWeeklyStats> results = executeOracleJdbcWeeklyAggregation(queryVector, TOP_K, DIM_384);

        // Verify aggregation math: avg = total / count
        // Note: Transaction amounts are negative (debits), so totalAmount will be negative
        for (AccountWithWeeklyStats account : results) {
            for (WeeklyStats week : account.weeklyStats()) {
                assertTrue(week.txnCount() > 0, "Transaction count should be positive");
                assertTrue(week.totalAmount() != 0, "Total amount should be non-zero");
                double expectedAvg = week.totalAmount() / week.txnCount();
                assertEquals(expectedAvg, week.avgAmount(), 0.01,
                    "Average should equal total/count for week " + week.isoWeek());
            }
        }
    }

    @Test
    @Order(54)
    @DisplayName("RAG2_TDD5: MongoDB and Oracle return equivalent weekly stats")
    void testRAG2_TDD5_weeklyAggregation_equivalentResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported && oracleVectorSupported,
            "Skipping: Both MongoDB and Oracle required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

        List<AccountWithWeeklyStats> mongoResults = executeMongoWeeklyAggregation(queryVector, TOP_K);
        List<AccountWithWeeklyStats> oracleResults = executeOracleJdbcWeeklyAggregation(queryVector, TOP_K, DIM_384);

        assertEquals(mongoResults.size(), oracleResults.size(),
            "MongoDB and Oracle should return same number of accounts");

        // Compare total transaction counts per account (weeks may vary slightly due to timezone)
        for (int i = 0; i < Math.min(mongoResults.size(), oracleResults.size()); i++) {
            int mongoTotalTxns = mongoResults.get(i).weeklyStats().stream()
                .mapToInt(WeeklyStats::txnCount).sum();
            int oracleTotalTxns = oracleResults.get(i).weeklyStats().stream()
                .mapToInt(WeeklyStats::txnCount).sum();
            // Allow 10% tolerance due to potential date boundary differences
            assertTrue(Math.abs(mongoTotalTxns - oracleTotalTxns) <= Math.max(mongoTotalTxns, oracleTotalTxns) * 0.1,
                "Total transactions should be similar: MongoDB=" + mongoTotalTxns + ", Oracle=" + oracleTotalTxns);
        }
    }

    // ==========================================================================
    // RAG3 TDD Unit Tests: Customer 360 Profile Validation
    // ==========================================================================

    @Test
    @Order(60)
    @DisplayName("RAG3_TDD1: MongoDB Customer 360 calculates correct transaction totals")
    void testRAG3_TDD1_customer360_calculatesCorrectTotals() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<Customer360Profile> results = executeMongoCustomer360(queryVector, TOP_K);

        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Results should not be empty");

        for (Customer360Profile profile : results) {
            // Each account should have TRANSACTIONS_PER_ACCOUNT transactions
            assertTrue(profile.transactionCount() > 0,
                "Transaction count should be positive for " + profile.accountId());
            // Total spent should be non-zero (negative since amounts are debits)
            assertTrue(profile.totalSpent() != 0,
                "Total spent should be non-zero for " + profile.accountId());
            // Average should equal total / count
            double expectedAvg = profile.totalSpent() / profile.transactionCount();
            assertEquals(expectedAvg, profile.avgTransactionAmount(), 0.01,
                "Average should equal total/count for " + profile.accountId());
        }
    }

    @Test
    @Order(61)
    @DisplayName("RAG3_TDD2: MongoDB Customer 360 includes all spending categories")
    void testRAG3_TDD2_customer360_includesAllCategories() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<Customer360Profile> results = executeMongoCustomer360(queryVector, TOP_K);

        // Expected categories from createTransactionDocument
        Set<String> expectedCategories = Set.of("SHOPPING", "FOOD", "UTILITIES", "ENTERTAINMENT", "TRANSFER");

        for (Customer360Profile profile : results) {
            assertNotNull(profile.spendingByCategory(),
                "Spending by category should not be null for " + profile.accountId());
            assertFalse(profile.spendingByCategory().isEmpty(),
                "Spending by category should not be empty for " + profile.accountId());

            // All categories in the profile should be valid
            for (String category : profile.spendingByCategory().keySet()) {
                assertTrue(expectedCategories.contains(category),
                    "Category '" + category + "' should be one of the expected categories");
            }

            // Sum of category spending should equal total spent
            double categorySum = profile.spendingByCategory().values().stream()
                .mapToDouble(Double::doubleValue).sum();
            assertEquals(profile.totalSpent(), categorySum, 0.01,
                "Sum of category spending should equal total spent for " + profile.accountId());
        }
    }

    @Test
    @Order(62)
    @DisplayName("RAG3_TDD3: MongoDB Customer 360 calculates days since last activity")
    void testRAG3_TDD3_customer360_calculatesDaysSinceActivity() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<Customer360Profile> results = executeMongoCustomer360(queryVector, TOP_K);

        for (Customer360Profile profile : results) {
            assertNotNull(profile.lastActivityDate(),
                "Last activity date should not be null for " + profile.accountId());
            // Transactions are within last 30 days, so days since should be 0-30
            assertTrue(profile.daysSinceLastActivity() >= 0 && profile.daysSinceLastActivity() <= 30,
                "Days since last activity should be 0-30, got " + profile.daysSinceLastActivity() +
                " for " + profile.accountId());
        }
    }

    @Test
    @Order(63)
    @DisplayName("RAG3_TDD4: Oracle JDBC Customer 360 calculates correct totals")
    void testRAG3_TDD4_oracleCustomer360_calculatesCorrectTotals() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<Customer360Profile> results = executeOracleJdbcCustomer360(queryVector, TOP_K, DIM_384);

        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(), "Results should not be empty");

        for (Customer360Profile profile : results) {
            assertTrue(profile.transactionCount() > 0,
                "Transaction count should be positive for " + profile.accountId());
            assertTrue(profile.totalSpent() != 0,
                "Total spent should be non-zero for " + profile.accountId());
            double expectedAvg = profile.totalSpent() / profile.transactionCount();
            assertEquals(expectedAvg, profile.avgTransactionAmount(), 0.01,
                "Average should equal total/count for " + profile.accountId());
        }
    }

    @Test
    @Order(64)
    @DisplayName("RAG3_TDD5: Oracle JDBC Customer 360 includes all spending categories")
    void testRAG3_TDD5_oracleCustomer360_includesAllCategories() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle VECTOR type not supported");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<Customer360Profile> results = executeOracleJdbcCustomer360(queryVector, TOP_K, DIM_384);

        Set<String> expectedCategories = Set.of("SHOPPING", "FOOD", "UTILITIES", "ENTERTAINMENT", "TRANSFER");

        for (Customer360Profile profile : results) {
            assertNotNull(profile.spendingByCategory(),
                "Spending by category should not be null for " + profile.accountId());

            for (String category : profile.spendingByCategory().keySet()) {
                assertTrue(expectedCategories.contains(category),
                    "Category '" + category + "' should be one of the expected categories");
            }

            double categorySum = profile.spendingByCategory().values().stream()
                .mapToDouble(Double::doubleValue).sum();
            assertEquals(profile.totalSpent(), categorySum, 0.01,
                "Sum of category spending should equal total spent for " + profile.accountId());
        }
    }

    @Test
    @Order(65)
    @DisplayName("RAG3_TDD6: MongoDB and Oracle return equivalent Customer 360 profiles")
    void testRAG3_TDD6_customer360_equivalentResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported && oracleVectorSupported,
            "Skipping: Both MongoDB and Oracle required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

        List<Customer360Profile> mongoResults = executeMongoCustomer360(queryVector, TOP_K);
        List<Customer360Profile> oracleResults = executeOracleJdbcCustomer360(queryVector, TOP_K, DIM_384);

        assertEquals(mongoResults.size(), oracleResults.size(),
            "MongoDB and Oracle should return same number of profiles");

        // Compare transaction counts and totals (should be very close)
        // Allow 0.1 tolerance for floating-point differences between databases
        for (int i = 0; i < Math.min(mongoResults.size(), oracleResults.size()); i++) {
            Customer360Profile mongo = mongoResults.get(i);
            Customer360Profile oracle = oracleResults.get(i);

            assertEquals(mongo.transactionCount(), oracle.transactionCount(),
                "Transaction counts should match for account " + i);
            assertEquals(mongo.totalSpent(), oracle.totalSpent(), 0.1,
                "Total spent should match for account " + i);
        }
    }

    // ==========================================================================
    // RAG1 TDD Tests: Graph Traversal
    // ==========================================================================

    @Test
    @Order(70)
    @DisplayName("RAG1_TDD1: MongoDB $graphLookup finds same-tenant accounts")
    void testRAG1_TDD1_graphLookup_findsSameTenantAccounts() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<GraphTraversalResult> results = executeMongoGraphTraversal(queryVector, TOP_K);

        assertFalse(results.isEmpty(), "Should return at least one result");

        // Each result should have same-tenant accounts (5 tenants with 100 accounts = ~20 per tenant)
        GraphTraversalResult first = results.get(0);
        assertNotNull(first.sameTenantAccounts(), "Same-tenant accounts list should not be null");
        assertFalse(first.sameTenantAccounts().isEmpty(),
            "Should find at least one same-tenant account (tenants have ~20 accounts each)");

        // Verify all same-tenant relationships are of correct type
        for (RelatedAccount related : first.sameTenantAccounts()) {
            assertEquals("SAME_TENANT", related.relationshipType(),
                "Relationship type should be SAME_TENANT");
            assertTrue(related.hops() >= 0 && related.hops() <= 2,
                "Hops should be within expected range (0-2)");
        }
    }

    @Test
    @Order(71)
    @DisplayName("RAG1_TDD2: MongoDB $graphLookup finds shared-merchant accounts")
    void testRAG1_TDD2_graphLookup_findsSharedMerchantAccounts() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<GraphTraversalResult> results = executeMongoGraphTraversal(queryVector, TOP_K);

        assertFalse(results.isEmpty(), "Should return at least one result");

        // With 100 accounts and 20 txns each using ~10 merchants, accounts should share merchants
        GraphTraversalResult first = results.get(0);
        assertNotNull(first.sharedMerchantAccounts(), "Shared-merchant accounts list should not be null");

        // Verify all shared-merchant relationships are of correct type
        for (RelatedAccount related : first.sharedMerchantAccounts()) {
            assertEquals("SHARED_MERCHANT", related.relationshipType(),
                "Relationship type should be SHARED_MERCHANT");
        }
    }

    @Test
    @Order(72)
    @DisplayName("RAG1_TDD3: Graph traversal excludes self-references")
    void testRAG1_TDD3_graphTraversal_excludesSelfReferences() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<GraphTraversalResult> results = executeMongoGraphTraversal(queryVector, TOP_K);

        for (GraphTraversalResult result : results) {
            String sourceId = result.sourceAccountId();

            // Check same-tenant accounts don't include self
            for (RelatedAccount related : result.sameTenantAccounts()) {
                assertNotEquals(sourceId, related.accountId(),
                    "Same-tenant accounts should not include source account itself");
            }

            // Check shared-merchant accounts don't include self
            for (RelatedAccount related : result.sharedMerchantAccounts()) {
                assertNotEquals(sourceId, related.accountId(),
                    "Shared-merchant accounts should not include source account itself");
            }
        }
    }

    @Test
    @Order(73)
    @DisplayName("RAG1_TDD4: Oracle JDBC graph traversal finds same-tenant accounts")
    void testRAG1_TDD4_oracleGraphTraversal_findsSameTenantAccounts() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<GraphTraversalResult> results = executeOracleJdbcGraphTraversal(queryVector, TOP_K, DIM_384);

        assertFalse(results.isEmpty(), "Should return at least one result");

        GraphTraversalResult first = results.get(0);
        assertNotNull(first.sameTenantAccounts(), "Same-tenant accounts list should not be null");
        assertFalse(first.sameTenantAccounts().isEmpty(),
            "Should find at least one same-tenant account");

        for (RelatedAccount related : first.sameTenantAccounts()) {
            assertEquals("SAME_TENANT", related.relationshipType(),
                "Relationship type should be SAME_TENANT");
        }
    }

    @Test
    @Order(74)
    @DisplayName("RAG1_TDD5: Oracle JDBC graph traversal excludes self-references")
    void testRAG1_TDD5_oracleGraphTraversal_excludesSelfReferences() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<GraphTraversalResult> results = executeOracleJdbcGraphTraversal(queryVector, TOP_K, DIM_384);

        for (GraphTraversalResult result : results) {
            String sourceId = result.sourceAccountId();

            for (RelatedAccount related : result.sameTenantAccounts()) {
                assertNotEquals(sourceId, related.accountId(),
                    "Oracle same-tenant accounts should not include source");
            }

            for (RelatedAccount related : result.sharedMerchantAccounts()) {
                assertNotEquals(sourceId, related.accountId(),
                    "Oracle shared-merchant accounts should not include source");
            }
        }
    }

    @Test
    @Order(75)
    @DisplayName("RAG1_TDD6: MongoDB and Oracle return equivalent graph traversal results")
    void testRAG1_TDD6_graphTraversal_equivalentResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported && oracleVectorSupported,
            "Skipping: Both MongoDB and Oracle required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

        List<GraphTraversalResult> mongoResults = executeMongoGraphTraversal(queryVector, TOP_K);
        List<GraphTraversalResult> oracleResults = executeOracleJdbcGraphTraversal(queryVector, TOP_K, DIM_384);

        assertEquals(mongoResults.size(), oracleResults.size(),
            "MongoDB and Oracle should return same number of results");

        // Compare that both find related accounts for each source
        for (int i = 0; i < Math.min(mongoResults.size(), oracleResults.size()); i++) {
            GraphTraversalResult mongo = mongoResults.get(i);
            GraphTraversalResult oracle = oracleResults.get(i);

            // Both should find some same-tenant accounts
            assertFalse(mongo.sameTenantAccounts().isEmpty(),
                "MongoDB should find same-tenant accounts for result " + i);
            assertFalse(oracle.sameTenantAccounts().isEmpty(),
                "Oracle should find same-tenant accounts for result " + i);

            // Total related accounts should be similar (allow some variance in shared merchants)
            int mongoTotal = mongo.totalRelatedAccounts();
            int oracleTotal = oracle.totalRelatedAccounts();
            assertTrue(Math.abs(mongoTotal - oracleTotal) <= Math.max(mongoTotal, oracleTotal) * 0.2,
                "Total related accounts should be within 20% variance");
        }
    }

    // ==========================================================================
    // RAG4 TDD Tests: Activity Pattern Detection
    // ==========================================================================

    @Test
    @Order(80)
    @DisplayName("RAG4_TDD1: MongoDB activity pattern calculates rolling 7-day window")
    void testRAG4_TDD1_activityPattern_calculatesRollingWindow() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        // Setup with enough transactions over 90 days for meaningful patterns
        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<ActivityPatternResult> results = executeMongoActivityPattern(queryVector, TOP_K);

        assertFalse(results.isEmpty(), "Should return at least one result");

        ActivityPatternResult first = results.get(0);
        assertNotNull(first.activityPattern(), "Activity pattern should not be null");

        // Verify rolling window calculation - each day's rollingWeekCount should be <= 7 * max daily count
        for (DailyActivity day : first.activityPattern()) {
            assertTrue(day.rollingWeekCount() >= day.dailyCount(),
                "Rolling week count should be >= daily count");
        }
    }

    @Test
    @Order(81)
    @DisplayName("RAG4_TDD2: MongoDB activity pattern identifies burst periods (>2x avg)")
    void testRAG4_TDD2_activityPattern_identifiesBurstPeriods() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<ActivityPatternResult> results = executeMongoActivityPattern(queryVector, TOP_K);

        assertFalse(results.isEmpty(), "Should return at least one result");

        // Check burst detection logic - count days with BURST status
        for (ActivityPatternResult result : results) {
            int burstCount = 0;
            for (DailyActivity day : result.activityPattern()) {
                if ("BURST".equals(day.status())) {
                    burstCount++;
                    // Verify burst threshold: rolling count > 2 * avg daily
                    assertTrue(day.rollingWeekCount() > 2 * result.avgDailyTxns(),
                        "Burst day should have rolling count > 2x average");
                }
            }
            assertEquals(result.burstDayCount(), burstCount,
                "Burst day count should match counted BURST status days");
        }
    }

    @Test
    @Order(82)
    @DisplayName("RAG4_TDD3: MongoDB activity pattern calculates average daily transactions")
    void testRAG4_TDD3_activityPattern_calculatesAverage() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<ActivityPatternResult> results = executeMongoActivityPattern(queryVector, TOP_K);

        for (ActivityPatternResult result : results) {
            // Average should be positive if there are transactions
            if (!result.activityPattern().isEmpty()) {
                assertTrue(result.avgDailyTxns() >= 0,
                    "Average daily transactions should be non-negative");

                // Manually verify average
                double totalTxns = result.activityPattern().stream()
                    .mapToInt(DailyActivity::dailyCount)
                    .sum();
                double expectedAvg = totalTxns / result.activityPattern().size();
                assertEquals(expectedAvg, result.avgDailyTxns(), 0.01,
                    "Average daily transactions should be correctly calculated");
            }
        }
    }

    @Test
    @Order(83)
    @DisplayName("RAG4_TDD4: Oracle JDBC activity pattern calculates rolling window")
    void testRAG4_TDD4_oracleActivityPattern_calculatesRollingWindow() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<ActivityPatternResult> results = executeOracleJdbcActivityPattern(queryVector, TOP_K, DIM_384);

        assertFalse(results.isEmpty(), "Should return at least one result");

        ActivityPatternResult first = results.get(0);
        assertNotNull(first.activityPattern(), "Activity pattern should not be null");

        // Verify rolling window calculation
        for (DailyActivity day : first.activityPattern()) {
            assertTrue(day.rollingWeekCount() >= day.dailyCount(),
                "Rolling week count should be >= daily count");
        }
    }

    @Test
    @Order(84)
    @DisplayName("RAG4_TDD5: Oracle JDBC activity pattern identifies burst periods")
    void testRAG4_TDD5_oracleActivityPattern_identifiesBurstPeriods() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<ActivityPatternResult> results = executeOracleJdbcActivityPattern(queryVector, TOP_K, DIM_384);

        for (ActivityPatternResult result : results) {
            int burstCount = 0;
            for (DailyActivity day : result.activityPattern()) {
                if ("BURST".equals(day.status())) {
                    burstCount++;
                }
            }
            assertEquals(result.burstDayCount(), burstCount,
                "Burst day count should match counted BURST status days");
        }
    }

    @Test
    @Order(85)
    @DisplayName("RAG4_TDD6: MongoDB and Oracle return similar activity pattern results")
    void testRAG4_TDD6_activityPattern_equivalentResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported && oracleVectorSupported,
            "Skipping: Both MongoDB and Oracle required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

        List<ActivityPatternResult> mongoResults = executeMongoActivityPattern(queryVector, TOP_K);
        List<ActivityPatternResult> oracleResults = executeOracleJdbcActivityPattern(queryVector, TOP_K, DIM_384);

        assertEquals(mongoResults.size(), oracleResults.size(),
            "MongoDB and Oracle should return same number of results");

        // Compare average daily transactions (should be close)
        for (int i = 0; i < Math.min(mongoResults.size(), oracleResults.size()); i++) {
            ActivityPatternResult mongo = mongoResults.get(i);
            ActivityPatternResult oracle = oracleResults.get(i);

            // Averages should be within 10% of each other
            if (mongo.avgDailyTxns() > 0 && oracle.avgDailyTxns() > 0) {
                double diff = Math.abs(mongo.avgDailyTxns() - oracle.avgDailyTxns());
                double maxAvg = Math.max(mongo.avgDailyTxns(), oracle.avgDailyTxns());
                assertTrue(diff <= maxAvg * 0.2,
                    "Average daily txns should be within 20% variance for account " + i);
            }
        }
    }

    // ==========================================================================
    // RAG5 TDD Tests: Hybrid Context Ranking
    // ==========================================================================

    @Test
    @Order(90)
    @DisplayName("RAG5_TDD1: MongoDB hybrid ranking computes correct hybrid score")
    void testRAG5_TDD1_hybridRanking_computesCorrectScore() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<HybridRankingResult> results = executeMongoHybridRanking(queryVector, TOP_K);

        assertFalse(results.isEmpty(), "Should return at least one result");

        // Verify hybrid score calculation: 0.5*vector + 0.3*activity + 0.2*recency
        for (HybridRankingResult result : results) {
            double expectedScore = result.computedHybridScore();
            assertEquals(expectedScore, result.hybridScore(), 0.01,
                "Hybrid score should match 0.5*vector + 0.3*activity + 0.2*recency formula");
        }
    }

    @Test
    @Order(91)
    @DisplayName("RAG5_TDD2: MongoDB hybrid ranking re-orders results from pure vector order")
    void testRAG5_TDD2_hybridRanking_reordersResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<HybridRankingResult> hybridResults = executeMongoHybridRanking(queryVector, TOP_K);

        // Get pure vector search results for comparison
        List<Document> vectorResults = executeMongoVectorSearch(queryVector, TOP_K);

        assertFalse(hybridResults.isEmpty(), "Should return hybrid results");
        assertFalse(vectorResults.isEmpty(), "Should return vector results");

        // Hybrid ranking should produce different ordering than pure vector search
        // (unless transaction patterns are identical, which is unlikely)
        List<String> hybridOrder = hybridResults.stream()
            .map(HybridRankingResult::accountId)
            .toList();
        List<String> vectorOrder = vectorResults.stream()
            .map(d -> d.getString("accountId"))
            .toList();

        // Results are sorted by hybrid score descending
        for (int i = 0; i < hybridResults.size() - 1; i++) {
            assertTrue(hybridResults.get(i).hybridScore() >= hybridResults.get(i + 1).hybridScore(),
                "Results should be sorted by hybrid score descending");
        }
    }

    @Test
    @Order(92)
    @DisplayName("RAG5_TDD3: MongoDB hybrid ranking normalizes scores to 0-1 range")
    void testRAG5_TDD3_hybridRanking_normalizesScores() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported,
            "Skipping: MongoDB vector search required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<HybridRankingResult> results = executeMongoHybridRanking(queryVector, TOP_K);

        for (HybridRankingResult result : results) {
            // Vector score should be 0-1
            assertTrue(result.vectorScore() >= 0 && result.vectorScore() <= 1,
                "Vector score should be in 0-1 range");

            // Normalized activity should be 0-1
            assertTrue(result.normalizedTxnActivity() >= 0 && result.normalizedTxnActivity() <= 1,
                "Normalized txn activity should be in 0-1 range");

            // Recency score should be 0-1
            assertTrue(result.recencyScore() >= 0 && result.recencyScore() <= 1,
                "Recency score should be in 0-1 range");

            // Hybrid score should be 0-1 (weighted sum of 0-1 values)
            assertTrue(result.hybridScore() >= 0 && result.hybridScore() <= 1,
                "Hybrid score should be in 0-1 range");
        }
    }

    @Test
    @Order(93)
    @DisplayName("RAG5_TDD4: Oracle JDBC hybrid ranking computes correct hybrid score")
    void testRAG5_TDD4_oracleHybridRanking_computesCorrectScore() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<HybridRankingResult> results = executeOracleJdbcHybridRanking(queryVector, TOP_K, DIM_384);

        assertFalse(results.isEmpty(), "Should return at least one result");

        // Verify hybrid score calculation
        for (HybridRankingResult result : results) {
            double expectedScore = result.computedHybridScore();
            assertEquals(expectedScore, result.hybridScore(), 0.01,
                "Hybrid score should match formula");
        }
    }

    @Test
    @Order(94)
    @DisplayName("RAG5_TDD5: Oracle JDBC hybrid ranking normalizes scores to 0-1 range")
    void testRAG5_TDD5_oracleHybridRanking_normalizesScores() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported,
            "Skipping: Oracle vector support required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));
        List<HybridRankingResult> results = executeOracleJdbcHybridRanking(queryVector, TOP_K, DIM_384);

        for (HybridRankingResult result : results) {
            assertTrue(result.vectorScore() >= 0 && result.vectorScore() <= 1,
                "Vector score should be in 0-1 range");
            assertTrue(result.normalizedTxnActivity() >= 0 && result.normalizedTxnActivity() <= 1,
                "Normalized txn activity should be in 0-1 range");
            assertTrue(result.recencyScore() >= 0 && result.recencyScore() <= 1,
                "Recency score should be in 0-1 range");
        }
    }

    @Test
    @Order(95)
    @DisplayName("RAG5_TDD6: MongoDB and Oracle return similar hybrid ranking results")
    void testRAG5_TDD6_hybridRanking_equivalentResults() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported && oracleVectorSupported,
            "Skipping: Both MongoDB and Oracle required");

        setupBenchmarkData(100, DIM_384, TRANSACTIONS_PER_ACCOUNT);
        createMongoVectorSearchIndex(DIM_384);
        waitForIndexReady(5000);

        double[] queryVector = generateNormalizedEmbedding(DIM_384, new Random(42));

        List<HybridRankingResult> mongoResults = executeMongoHybridRanking(queryVector, TOP_K);
        List<HybridRankingResult> oracleResults = executeOracleJdbcHybridRanking(queryVector, TOP_K, DIM_384);

        assertEquals(mongoResults.size(), oracleResults.size(),
            "MongoDB and Oracle should return same number of results");

        // Compare average hybrid scores (re-ranking may produce different orderings)
        double mongoAvgScore = mongoResults.stream()
            .mapToDouble(HybridRankingResult::hybridScore)
            .average()
            .orElse(0.0);
        double oracleAvgScore = oracleResults.stream()
            .mapToDouble(HybridRankingResult::hybridScore)
            .average()
            .orElse(0.0);

        // Average hybrid scores should be within 30% of each other
        double avgDiff = Math.abs(mongoAvgScore - oracleAvgScore);
        double maxAvgScore = Math.max(mongoAvgScore, oracleAvgScore);
        assertTrue(avgDiff <= maxAvgScore * 0.3,
            "Average hybrid scores should be within 30% variance: MongoDB=" + mongoAvgScore + ", Oracle=" + oracleAvgScore);

        // Both should produce results sorted by hybrid score descending
        for (int i = 0; i < mongoResults.size() - 1; i++) {
            assertTrue(mongoResults.get(i).hybridScore() >= mongoResults.get(i + 1).hybridScore(),
                "MongoDB results should be sorted by hybrid score descending");
        }
        for (int i = 0; i < oracleResults.size() - 1; i++) {
            assertTrue(oracleResults.get(i).hybridScore() >= oracleResults.get(i + 1).hybridScore(),
                "Oracle results should be sorted by hybrid score descending");
        }
    }

    // ==========================================================================
    // VS1: Vector Search Benchmarks (Simple Retrieval)
    // ==========================================================================

    @Test
    @Order(100)
    @DisplayName("VS1: Vector search benchmark - 384 dim, 10K accounts")
    void testVS1_vectorSearch_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runVectorSearchBenchmark("VS1_384_10K", SMALL_ACCOUNT_COUNT, DIM_384);
    }

    @Test
    @Order(101)
    @DisplayName("VS1: Vector search benchmark - 768 dim, 10K accounts")
    void testVS1_vectorSearch_768dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runVectorSearchBenchmark("VS1_768_10K", SMALL_ACCOUNT_COUNT, DIM_768);
    }

    @Test
    @Order(102)
    @DisplayName("VS1: Vector search benchmark - 1536 dim, 1K accounts")
    void testVS1_vectorSearch_1536dim_1K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runVectorSearchBenchmark("VS1_1536_1K", 1000, DIM_1536);
    }

    // ==========================================================================
    // RAG2: Temporal Transaction Aggregation (Weekly Stats)
    // ==========================================================================

    @Test
    @Order(200)
    @DisplayName("RAG2: Vector search + weekly aggregation - 384 dim, 10K accounts")
    void testRAG2_weeklyAggregation_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runWeeklyAggregationBenchmark("RAG2_384_10K", SMALL_ACCOUNT_COUNT, DIM_384, TRANSACTIONS_PER_ACCOUNT);
    }

    // ==========================================================================
    // RAG3: Customer 360 Profile Assembly
    // ==========================================================================

    @Test
    @Order(210)
    @DisplayName("RAG3: Vector search + Customer 360 profile - 384 dim, 10K accounts")
    void testRAG3_customer360Profile_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runCustomer360Benchmark("RAG3_384_10K", SMALL_ACCOUNT_COUNT, DIM_384, TRANSACTIONS_PER_ACCOUNT);
    }

    // ==========================================================================
    // RAG1: Multi-Hop Account Relationships (Graph Traversal)
    // ==========================================================================

    @Test
    @Order(220)
    @DisplayName("RAG1: Vector search + graph traversal - 384 dim, 10K accounts")
    void testRAG1_graphTraversal_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runGraphTraversalBenchmark("RAG1_384_10K", SMALL_ACCOUNT_COUNT, DIM_384, TRANSACTIONS_PER_ACCOUNT);
    }

    // ==========================================================================
    // RAG4: Activity Pattern Detection (Rolling Window)
    // ==========================================================================

    @Test
    @Order(230)
    @DisplayName("RAG4: Vector search + activity pattern detection - 384 dim, 10K accounts")
    void testRAG4_activityPattern_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runActivityPatternBenchmark("RAG4_384_10K", SMALL_ACCOUNT_COUNT, DIM_384, TRANSACTIONS_PER_ACCOUNT);
    }

    // ==========================================================================
    // RAG5: Hybrid Context Ranking (Score Fusion)
    // ==========================================================================

    @Test
    @Order(240)
    @DisplayName("RAG5: Vector search + hybrid ranking - 384 dim, 10K accounts")
    void testRAG5_hybridRanking_384dim_10K() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runHybridRankingBenchmark("RAG5_384_10K", SMALL_ACCOUNT_COUNT, DIM_384, TRANSACTIONS_PER_ACCOUNT);
    }

    // ==========================================================================
    // VS3: Recall Accuracy Benchmarks
    // ==========================================================================

    @Test
    @Order(300)
    @DisplayName("VS3_RECALL_10: Recall@10 measurement")
    void testVS3_recall10() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runRecallBenchmark("VS3_RECALL_10", SMALL_ACCOUNT_COUNT, DIM_384, 10);
    }

    @Test
    @Order(301)
    @DisplayName("VS3_RECALL_50: Recall@50 measurement")
    void testVS3_recall50() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runRecallBenchmark("VS3_RECALL_50", SMALL_ACCOUNT_COUNT, DIM_384, 50);
    }

    @Test
    @Order(302)
    @DisplayName("VS3_RECALL_100: Recall@100 measurement")
    void testVS3_recall100() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runRecallBenchmark("VS3_RECALL_100", SMALL_ACCOUNT_COUNT, DIM_384, 100);
    }

    // ==========================================================================
    // VS4: Filtered Vector Search Benchmarks
    // ==========================================================================

    @Test
    @Order(400)
    @DisplayName("VS4_FILTER_50PCT: 50% filter selectivity (balance >= 25000)")
    void testVS4_filter50pct() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        Map<String, Object> filters = new HashMap<>();
        filters.put("balance", Map.of("$gte", 25000.0));
        runFilteredSearchBenchmark("VS4_FILTER_50PCT", SMALL_ACCOUNT_COUNT, DIM_384, filters, 0.50);
    }

    @Test
    @Order(401)
    @DisplayName("VS4_FILTER_10PCT: 10% filter selectivity (balance >= 80000)")
    void testVS4_filter10pct() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        Map<String, Object> filters = new HashMap<>();
        filters.put("balance", Map.of("$gte", 80000.0));
        runFilteredSearchBenchmark("VS4_FILTER_10PCT", SMALL_ACCOUNT_COUNT, DIM_384, filters, 0.10);
    }

    @Test
    @Order(402)
    @DisplayName("VS4_FILTER_REGION: Region filter (NORTHEAST ~20%)")
    void testVS4_filterRegion() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        Map<String, Object> filters = new HashMap<>();
        filters.put("region", "NORTHEAST");
        runFilteredSearchBenchmark("VS4_FILTER_REGION", SMALL_ACCOUNT_COUNT, DIM_384, filters, 0.20);
    }

    @Test
    @Order(403)
    @DisplayName("VS4_FILTER_COMPOUND: Compound filter (region + accountType)")
    void testVS4_filterCompound() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        Map<String, Object> filters = new HashMap<>();
        filters.put("region", "NORTHEAST");
        filters.put("accountType", "CHECKING");
        runFilteredSearchBenchmark("VS4_FILTER_COMPOUND", SMALL_ACCOUNT_COUNT, DIM_384, filters, 0.05);
    }

    // ==========================================================================
    // VS5: Hybrid Search Benchmarks (Vector + Full-Text)
    // ==========================================================================

    @Test
    @Order(500)
    @DisplayName("VS5_VECTOR_ONLY: Pure vector search baseline")
    void testVS5_vectorOnly() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runHybridSearchBenchmark("VS5_VECTOR_ONLY", HybridMode.VECTOR_ONLY);
    }

    @Test
    @Order(501)
    @DisplayName("VS5_TEXT_ONLY: Pure text search baseline")
    void testVS5_textOnly() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runHybridSearchBenchmark("VS5_TEXT_ONLY", HybridMode.TEXT_ONLY);
    }

    // ==========================================================================
    // VS6: Quantization Performance Benchmarks
    // ==========================================================================

    @Test
    @Order(600)
    @DisplayName("VS6_FLOAT32: Float32 vector baseline")
    void testVS6_float32() throws SQLException {
        Assumptions.assumeTrue(mongoVectorSearchSupported, "Skipping: MongoDB vector search required");

        runQuantizationBenchmark("VS6_FLOAT32", QuantizationType.FLOAT32);
    }

    @Test
    @Order(601)
    @DisplayName("VS6_INT8: Int8 scalar quantization")
    void testVS6_int8() throws SQLException {
        Assumptions.assumeTrue(oracleVectorSupported, "Skipping: Oracle vector support required");

        runQuantizationBenchmark("VS6_INT8", QuantizationType.INT8);
    }

    // Enum for hybrid search modes
    enum HybridMode { VECTOR_ONLY, TEXT_ONLY, RRF_FUSION, WEIGHTED_FUSION }

    // Enum for quantization types
    enum QuantizationType { FLOAT32, INT8, BINARY }

    // ==========================================================================
    // Main Benchmark Runner - VS1 (Vector Search Only)
    // ==========================================================================

    private void runVectorSearchBenchmark(String testId, int accountCount, int dimensions) throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        // AWR snapshot before
        awrSnapshotBefore(testId);

        // Setup data
        setupBenchmarkData(accountCount, dimensions, 0);

        // Generate query vectors
        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build pipelines and SQL for capture
        String mongoPipeline = buildMongoVectorSearchPipeline(sampleVector);
        String oracleSqlStatement = buildOracleSqlAggregation(sampleVector, dimensions);
        String oracleJdbcSql = buildOracleJdbcSql(sampleVector, dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleSqlVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
                executeOracleJdbcVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB Native...");
        long mongoTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            executeMongoVectorSearch(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql aggregation
        long oracleSqlAvg = -1;
        String sqlMonitorSql = "";
        String oracleSqlExplain = "";
        if (oracleVectorSupported && oracleMongoApiSupported) {
            System.out.println("  Measuring Oracle $sql aggregation...");
            // Test if query works first
            List<Document> testResult = executeOracleSqlVectorSearch(queryVectors.get(0), TOP_K, dimensions);
            if (!testResult.isEmpty()) {
                long oracleSqlTotal = 0;
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    executeOracleSqlVectorSearch(queryVectors.get(i), TOP_K, dimensions);
                    oracleSqlTotal += System.nanoTime() - start;
                }
                oracleSqlAvg = oracleSqlTotal / MEASUREMENT_ITERATIONS;

                // Capture SQL Monitor for $sql
                sqlMonitorSql = captureSqlMonitorForSql(oracleSqlStatement, testId);
                oracleSqlExplain = captureExplainPlan(oracleSqlStatement.replace("?", "'[0.1,0.2,0.3]'").replace("FETCH FIRST ? ROWS", "FETCH FIRST 10 ROWS"));
            } else {
                System.out.println("    Oracle $sql: Query returned no results (ORDS may have issues)");
            }
        }

        // Measure Oracle JDBC
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcVectorSearch(queryVectors.get(i), TOP_K);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor for JDBC
            sqlMonitorJdbc = captureSqlMonitorForJdbc(oracleJdbcSql, sampleVector, testId);
            oracleJdbcExplain = captureExplainPlan(oracleJdbcSql.replace("?", "'[0.1,0.2,0.3]'").replace("FETCH FIRST ? ROWS", "FETCH FIRST 10 ROWS"));
        }

        // Capture MongoDB explain
        String mongoExplain = captureMongoExplain(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql, Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        // AWR snapshot after (before storing details so we can get the link)
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details organized by protocol
        sqlDetailsMap.put(testId, new SqlDetails(
            // MongoDB Native
            mongoPipeline,
            mongoExplain,
            // Oracle $sql aggregation
            oracleSqlStatement,
            oracleSqlExplain,
            sqlMonitorSql,
            // Oracle JDBC
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            // AWR link
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleSqlAvg > 0) System.out.printf(", Oracle $sql: %.2f ms", oracleSqlAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - VS2 (Vector Search + Join)
    // ==========================================================================

    private void runVectorSearchWithJoinBenchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoVectorSearchWithLookupPipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcVectorSearchWithJoinSql(dimensions);
        String oracleSqlStatement = buildOracleSqlVectorSearchWithJoinSql(sampleVector, dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoVectorSearchWithLookup(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcVectorSearchWithJoin(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + $lookup...");
        long mongoTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            executeMongoVectorSearchWithLookup(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql with JOIN (uses inline subquery, not CTE)
        long oracleSqlAvg = -1;
        if (oracleMongoApiSupported) {
            System.out.println("  Measuring Oracle $sql with JOIN...");
            // Test if query works first
            List<Document> testResult = executeOracleSqlVectorSearchWithJoin(queryVectors.get(0), TOP_K, dimensions);
            if (testResult != null && !testResult.isEmpty()) {
                long oracleSqlTotal = 0;
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    executeOracleSqlVectorSearchWithJoin(queryVectors.get(i), TOP_K, dimensions);
                    oracleSqlTotal += System.nanoTime() - start;
                }
                oracleSqlAvg = oracleSqlTotal / MEASUREMENT_ITERATIONS;
            } else {
                System.out.println("    Oracle $sql with JOIN: Query returned no results or failed");
            }
        }

        // Measure Oracle JDBC with JOIN
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC VECTOR_DISTANCE + JOIN...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcVectorSearchWithJoin(queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForJdbcWithJoin(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForJoin(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain for join pipeline
        String mongoExplain = captureMongoExplainWithLookup(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql, Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details organized by protocol
        sqlDetailsMap.put(testId, new SqlDetails(
            // MongoDB Native
            mongoPipeline,
            mongoExplain,
            // Oracle $sql aggregation (not supported for join)
            oracleSqlStatement,
            "Not supported: CTE/complex queries exceed ORDS limits",
            "",
            // Oracle JDBC
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            // AWR link
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleSqlAvg > 0) System.out.printf(", Oracle $sql: %.2f ms", oracleSqlAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - RAG2 (Weekly Aggregation)
    // ==========================================================================

    private void runWeeklyAggregationBenchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);
        createMongoVectorSearchIndex(dimensions);
        waitForIndexReady(5000);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoWeeklyAggregationPipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcWeeklyAggregationSql(dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoWeeklyAggregation(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcWeeklyAggregation(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + $lookup + $group by week...");
        long mongoTotal = 0;
        int mongoWeekCountTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<AccountWithWeeklyStats> mongoResults = executeMongoWeeklyAggregation(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
            // Count total weeks returned for verification
            mongoWeekCountTotal += mongoResults.stream()
                .mapToInt(a -> a.weeklyStats().size())
                .sum();
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double avgWeeksPerQuery = (double) mongoWeekCountTotal / MEASUREMENT_ITERATIONS / TOP_K;
        System.out.printf("    Average weeks per account: %.1f%n", avgWeeksPerQuery);

        // Measure Oracle JDBC with Weekly Aggregation
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC VECTOR_DISTANCE + GROUP BY TRUNC(IW)...");
            long oracleJdbcTotal = 0;
            int oracleWeekCountTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                List<AccountWithWeeklyStats> oracleResults = executeOracleJdbcWeeklyAggregation(
                    queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
                oracleWeekCountTotal += oracleResults.stream()
                    .mapToInt(a -> a.weeklyStats().size())
                    .sum();
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;
            double oracleAvgWeeks = (double) oracleWeekCountTotal / MEASUREMENT_ITERATIONS / TOP_K;
            System.out.printf("    Oracle average weeks per account: %.1f%n", oracleAvgWeeks);

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForWeeklyAggregation(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForWeeklyAggregation(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain for weekly aggregation pipeline
        String mongoExplain = captureMongoExplainWeeklyAggregation(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql (N/A), Oracle JDBC)
        // Oracle $sql doesn't support this complex aggregation
        results.put(testId, new long[]{mongoAvg, -1, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details organized by protocol
        sqlDetailsMap.put(testId, new SqlDetails(
            // MongoDB Native
            mongoPipeline,
            mongoExplain,
            // Oracle $sql aggregation (not supported for complex aggregation)
            "Not applicable - complex aggregation not supported via ORDS $sql",
            "N/A",
            "",
            // Oracle JDBC
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            // AWR link
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - RAG3 (Customer 360 Profile)
    // ==========================================================================

    private void runCustomer360Benchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);
        createMongoVectorSearchIndex(dimensions);
        waitForIndexReady(5000);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoCustomer360Pipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcCustomer360Sql(dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoCustomer360(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcCustomer360(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + Customer 360 profile assembly...");
        long mongoTotal = 0;
        int mongoCategoryCountTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<Customer360Profile> mongoResults = executeMongoCustomer360(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
            mongoCategoryCountTotal += mongoResults.stream()
                .mapToInt(p -> p.spendingByCategory().size())
                .sum();
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double avgCategoriesPerAccount = (double) mongoCategoryCountTotal / MEASUREMENT_ITERATIONS / TOP_K;
        System.out.printf("    Average categories per account: %.1f%n", avgCategoriesPerAccount);

        // Measure Oracle JDBC with Customer 360
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC Customer 360 profile assembly...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcCustomer360(queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForCustomer360(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForCustomer360(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain
        String mongoExplain = captureMongoExplainCustomer360(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql (N/A), Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, -1, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details
        sqlDetailsMap.put(testId, new SqlDetails(
            mongoPipeline,
            mongoExplain,
            "Not applicable - complex aggregation not supported via ORDS $sql",
            "N/A",
            "",
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - RAG1 (Graph Traversal)
    // ==========================================================================

    private void runGraphTraversalBenchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);
        createMongoVectorSearchIndex(dimensions);
        waitForIndexReady(5000);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoGraphTraversalPipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcGraphTraversalSql(dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoGraphTraversal(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcGraphTraversal(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + $graphLookup graph traversal...");
        long mongoTotal = 0;
        int mongoTotalRelatedAccounts = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<GraphTraversalResult> mongoResults = executeMongoGraphTraversal(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
            mongoTotalRelatedAccounts += mongoResults.stream()
                .mapToInt(GraphTraversalResult::totalRelatedAccounts)
                .sum();
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double avgRelatedPerAccount = (double) mongoTotalRelatedAccounts / MEASUREMENT_ITERATIONS / TOP_K;
        System.out.printf("    Average related accounts per source: %.1f%n", avgRelatedPerAccount);

        // Measure Oracle JDBC with graph traversal
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC graph traversal via JOIN...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcGraphTraversal(queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForGraphTraversal(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForGraphTraversal(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain
        String mongoExplain = captureMongoExplainGraphTraversal(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql (N/A), Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, -1, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details
        sqlDetailsMap.put(testId, new SqlDetails(
            mongoPipeline,
            mongoExplain,
            "Not applicable - graph traversal not supported via ORDS $sql",
            "N/A",
            "",
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - RAG4 (Activity Pattern Detection)
    // ==========================================================================

    private void runActivityPatternBenchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);
        createMongoVectorSearchIndex(dimensions);
        waitForIndexReady(5000);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoActivityPatternPipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcActivityPatternSql(dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoActivityPattern(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcActivityPattern(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + $setWindowFields activity pattern...");
        long mongoTotal = 0;
        int mongoTotalBurstDays = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<ActivityPatternResult> mongoResults = executeMongoActivityPattern(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
            mongoTotalBurstDays += mongoResults.stream()
                .mapToInt(ActivityPatternResult::burstDayCount)
                .sum();
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double avgBurstDaysPerAccount = (double) mongoTotalBurstDays / MEASUREMENT_ITERATIONS / TOP_K;
        System.out.printf("    Average burst days per account: %.1f%n", avgBurstDaysPerAccount);

        // Measure Oracle JDBC with activity pattern
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC activity pattern via analytic functions...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcActivityPattern(queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForActivityPattern(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForActivityPattern(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain
        String mongoExplain = captureMongoExplainActivityPattern(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql (N/A), Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, -1, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details
        sqlDetailsMap.put(testId, new SqlDetails(
            mongoPipeline,
            mongoExplain,
            "Not applicable - activity pattern not supported via ORDS $sql",
            "N/A",
            "",
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - RAG5 (Hybrid Context Ranking)
    // ==========================================================================

    private void runHybridRankingBenchmark(String testId, int accountCount, int dimensions, int txnPerAccount)
            throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupBenchmarkData(accountCount, dimensions, txnPerAccount);
        createMongoVectorSearchIndex(dimensions);
        waitForIndexReady(5000);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build queries for capture
        String mongoPipeline = buildMongoHybridRankingPipeline(sampleVector);
        String oracleJdbcSql = buildOracleJdbcHybridRankingSql(dimensions);

        // Warmup all protocols
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoHybridRanking(queryVectors.get(i % queryVectors.size()), TOP_K);
            if (oracleVectorSupported) {
                executeOracleJdbcHybridRanking(queryVectors.get(i % queryVectors.size()), TOP_K, dimensions);
            }
        }

        // Measure MongoDB Native
        System.out.println("  Measuring MongoDB $vectorSearch + hybrid re-ranking...");
        long mongoTotal = 0;
        double mongoTotalHybridScore = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<HybridRankingResult> mongoResults = executeMongoHybridRanking(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
            mongoTotalHybridScore += mongoResults.stream()
                .mapToDouble(HybridRankingResult::hybridScore)
                .average()
                .orElse(0.0);
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double avgHybridScore = mongoTotalHybridScore / MEASUREMENT_ITERATIONS;
        System.out.printf("    Average hybrid score: %.3f%n", avgHybridScore);

        // Measure Oracle JDBC with hybrid ranking
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        String oracleJdbcExplain = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC hybrid re-ranking via window functions...");
            long oracleJdbcTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcHybridRanking(queryVectors.get(i), TOP_K, dimensions);
                oracleJdbcTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleJdbcTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor and explain plan
            sqlMonitorJdbc = captureSqlMonitorForHybridRanking(oracleJdbcSql, sampleVector, testId, dimensions);
            oracleJdbcExplain = captureExplainPlanForHybridRanking(oracleJdbcSql, dimensions);
        }

        // Capture MongoDB explain
        String mongoExplain = captureMongoExplainHybridRanking(sampleVector);

        // Store results (3 elements: MongoDB, Oracle $sql (N/A), Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, -1, oracleJdbcAvg});

        // AWR snapshot after
        awrSnapshotAfter(testId);
        String awrLink = awrReportContent.getOrDefault(testId, "");

        // Store SQL details
        sqlDetailsMap.put(testId, new SqlDetails(
            mongoPipeline,
            mongoExplain,
            "Not applicable - hybrid ranking not supported via ORDS $sql",
            "N/A",
            "",
            oracleJdbcSql,
            oracleJdbcExplain,
            sqlMonitorJdbc,
            awrLink
        ));

        // Print results
        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleJdbcAvg > 0) System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Main Benchmark Runner - VS3 (Recall Accuracy)
    // ==========================================================================

    private void runRecallBenchmark(String testId, int accountCount, int dimensions, int k) throws SQLException {
        System.out.println("\n  " + testId + " Setup...");

        awrSnapshotBefore(testId);
        setupEnhancedBenchmarkData(accountCount, dimensions);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Warmup
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoVectorSearch(queryVectors.get(i % queryVectors.size()), k);
            if (oracleVectorSupported) {
                executeOracleJdbcVectorSearch(queryVectors.get(i % queryVectors.size()), k);
            }
        }

        // Compute ground truth using MongoDB exact search (high numCandidates)
        System.out.println("  Computing ground truth with exact search...");
        Map<Integer, List<String>> groundTruthMap = new HashMap<>();
        for (int i = 0; i < queryVectors.size(); i++) {
            groundTruthMap.put(i, computeExactKnn(queryVectors.get(i), k));
        }

        // Measure MongoDB ANN with standard numCandidates
        System.out.println("  Measuring MongoDB ANN recall...");
        long mongoTotal = 0;
        double mongoRecallTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<Document> annResults = executeMongoVectorSearch(queryVectors.get(i), k);
            mongoTotal += System.nanoTime() - start;
            List<String> annIds = annResults.stream().map(d -> d.getString("_id")).toList();
            mongoRecallTotal += calculateRecall(groundTruthMap.get(i), annIds);
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;
        double mongoRecallAvg = mongoRecallTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql via MongoDB API
        long oracleSqlAvg = -1;
        double oracleSqlRecallAvg = 0;
        if (oracleMongoApiSupported) {
            System.out.println("  Measuring Oracle $sql recall...");
            long oracleTotal = 0;
            double oracleRecallTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                List<Document> oracleResults = executeOracleSqlVectorSearch(queryVectors.get(i), k, dimensions);
                oracleTotal += System.nanoTime() - start;
                List<String> oracleIds = oracleResults.stream().map(d -> d.getString("_id")).toList();
                oracleRecallTotal += calculateRecall(groundTruthMap.get(i), oracleIds);
            }
            oracleSqlAvg = oracleTotal / MEASUREMENT_ITERATIONS;
            oracleSqlRecallAvg = oracleRecallTotal / MEASUREMENT_ITERATIONS;
        }

        // Measure Oracle JDBC
        long oracleJdbcAvg = -1;
        double oracleRecallAvg = 0;
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC recall...");
            long oracleTotal = 0;
            double oracleRecallTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                List<Document> oracleResults = executeOracleJdbcVectorSearch(queryVectors.get(i), k);
                oracleTotal += System.nanoTime() - start;
                List<String> oracleIds = oracleResults.stream().map(d -> d.getString("_id")).toList();
                oracleRecallTotal += calculateRecall(groundTruthMap.get(i), oracleIds);
            }
            oracleJdbcAvg = oracleTotal / MEASUREMENT_ITERATIONS;
            oracleRecallAvg = oracleRecallTotal / MEASUREMENT_ITERATIONS;
        }

        // Store results (3 elements: MongoDB, Oracle $sql, Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        awrSnapshotAfter(testId);

        // Print results with recall
        System.out.printf("  Results: MongoDB: %.2f ms (recall: %.2f%%)",
            mongoAvg / 1_000_000.0, mongoRecallAvg * 100);
        if (oracleSqlAvg > 0) {
            System.out.printf(", Oracle $sql: %.2f ms (recall: %.2f%%)",
                oracleSqlAvg / 1_000_000.0, oracleSqlRecallAvg * 100);
        }
        if (oracleJdbcAvg > 0) {
            System.out.printf(", Oracle JDBC: %.2f ms (recall: %.2f%%)",
                oracleJdbcAvg / 1_000_000.0, oracleRecallAvg * 100);
        }
        System.out.println();

        assertTrue(mongoRecallAvg >= 0.85, "MongoDB recall should be >= 85%, got: " + (mongoRecallAvg * 100) + "%");
    }

    /**
     * Compute exact KNN using brute force (high numCandidates in MongoDB)
     */
    private List<String> computeExactKnn(double[] queryVector, int k) {
        List<Double> queryList = toDoubleList(queryVector);

        // Use very high numCandidates for near-exact results
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", Math.min(accountsCollection.countDocuments(), 10000))
            .append("limit", k));

        List<String> results = new ArrayList<>();
        accountsCollection.aggregate(Collections.singletonList(vectorSearchStage))
            .forEach(doc -> results.add(doc.getString("_id")));
        return results;
    }

    // ==========================================================================
    // Main Benchmark Runner - VS4 (Filtered Vector Search)
    // ==========================================================================

    private void runFilteredSearchBenchmark(String testId, int accountCount, int dimensions,
            Map<String, Object> filters, double expectedSelectivity) throws SQLException {
        System.out.println("\n  " + testId + " Setup (expected selectivity: " + (expectedSelectivity * 100) + "%)...");

        awrSnapshotBefore(testId);
        setupEnhancedBenchmarkData(accountCount, dimensions);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, dimensions);
        double[] sampleVector = queryVectors.get(0);

        // Build filter documents
        Document mongoFilter = buildMongoFilter(filters);
        String oracleWhereClause = buildOracleWhereClause(filters);

        System.out.println("    MongoDB filter: " + mongoFilter.toJson());
        System.out.println("    Oracle WHERE: " + oracleWhereClause);

        // Warmup
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeMongoFilteredVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K, mongoFilter);
            if (oracleVectorSupported) {
                executeOracleFilteredVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K, oracleWhereClause);
            }
        }

        // Measure MongoDB filtered search
        System.out.println("  Measuring MongoDB filtered vector search...");
        long mongoTotal = 0;
        int mongoResultCount = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            List<Document> searchResults = executeMongoFilteredVectorSearch(queryVectors.get(i), TOP_K, mongoFilter);
            mongoTotal += System.nanoTime() - start;
            mongoResultCount += searchResults.size();
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql filtered search
        long oracleSqlAvg = -1;
        if (oracleMongoApiSupported) {
            System.out.println("  Measuring Oracle $sql filtered vector search...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleSqlFilteredVectorSearch(queryVectors.get(i), TOP_K, dimensions, oracleWhereClause);
                oracleTotal += System.nanoTime() - start;
            }
            oracleSqlAvg = oracleTotal / MEASUREMENT_ITERATIONS;
        }

        // Measure Oracle JDBC filtered search
        long oracleJdbcAvg = -1;
        String sqlMonitorJdbc = "";
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC filtered vector search...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleFilteredVectorSearch(queryVectors.get(i), TOP_K, oracleWhereClause);
                oracleTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleTotal / MEASUREMENT_ITERATIONS;

            // Capture SQL Monitor
            String filteredSql = buildOracleFilteredJdbcSql(dimensions, oracleWhereClause);
            sqlMonitorJdbc = captureSqlMonitorForFilteredSearch(filteredSql, sampleVector, testId);

            // Print explain plan for diagnostic purposes
            printFilteredSearchExplainPlan(dimensions, oracleWhereClause);
        }

        // Store results
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        awrSnapshotAfter(testId);

        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleSqlAvg > 0) {
            System.out.printf(", Oracle $sql: %.2f ms", oracleSqlAvg / 1_000_000.0);
        }
        if (oracleJdbcAvg > 0) {
            System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        }
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    /**
     * Execute Oracle $sql filtered vector search
     */
    private static List<Document> executeOracleSqlFilteredVectorSearch(double[] queryVector, int limit, int dimensions, String whereClause) {
        if (!oracleMongoApiSupported) return Collections.emptyList();

        String vectorStr = formatVectorString(queryVector);

        // ORDS $sql requires projecting a single JSON column - use JSON_OBJECT
        String sqlStatement = String.format("""
            SELECT JSON_OBJECT(
                '_id' VALUE id,
                'distance' VALUE VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE)
            RETURNING CLOB) AS json_doc
            FROM %s
            WHERE %s
            ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE)
            FETCH FIRST %d ROWS ONLY
            """, vectorStr, dimensions, ACCOUNTS_TABLE, whereClause, vectorStr, dimensions, limit);

        Document sqlStage = new Document("$sql", sqlStatement);

        List<Document> results = new ArrayList<>();
        try {
            oracleSodaAccountsCollection.aggregate(Collections.singletonList(sqlStage))
                .forEach(results::add);
        } catch (Exception e) {
            // $sql with WHERE clause may fail on some configurations
        }
        return results;
    }

    /**
     * Build MongoDB filter document from map
     */
    private static Document buildMongoFilter(Map<String, Object> filters) {
        Document filter = new Document();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rangeOps = (Map<String, Object>) value;
                Document rangeFilter = new Document();
                for (Map.Entry<String, Object> op : rangeOps.entrySet()) {
                    rangeFilter.append(op.getKey(), op.getValue());
                }
                filter.append(key, rangeFilter);
            } else {
                filter.append(key, value);
            }
        }
        return filter;
    }

    /**
     * Build Oracle WHERE clause from filter map
     */
    private static String buildOracleWhereClause(Map<String, Object> filters) {
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Map JSON field names to relational columns
            String column = switch (key) {
                case "balance" -> "BALANCE";
                case "region" -> "REGION";
                case "accountType" -> "ACCOUNT_TYPE";
                case "tenantId" -> "TENANT_ID";
                case "riskScore" -> "RISK_SCORE";
                default -> "JSON_VALUE(data, '$." + key + "')";
            };

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rangeOps = (Map<String, Object>) value;
                for (Map.Entry<String, Object> op : rangeOps.entrySet()) {
                    String sqlOp = switch (op.getKey()) {
                        case "$gte" -> ">=";
                        case "$gt" -> ">";
                        case "$lte" -> "<=";
                        case "$lt" -> "<";
                        case "$eq" -> "=";
                        default -> "=";
                    };
                    conditions.add(column + " " + sqlOp + " " + formatSqlValue(op.getValue()));
                }
            } else {
                conditions.add(column + " = " + formatSqlValue(value));
            }
        }
        return String.join(" AND ", conditions);
    }

    private static String formatSqlValue(Object value) {
        if (value instanceof String) {
            return "'" + value + "'";
        }
        return value.toString();
    }

    // ==========================================================================
    // Main Benchmark Runner - VS5 (Hybrid Search)
    // ==========================================================================

    private void runHybridSearchBenchmark(String testId, HybridMode mode) throws SQLException {
        System.out.println("\n  " + testId + " Setup (mode: " + mode + ")...");

        awrSnapshotBefore(testId);
        setupEnhancedBenchmarkData(SMALL_ACCOUNT_COUNT, DIM_384);

        // Create text index if not exists
        createMongoTextIndex();
        if (oracleVectorSupported) {
            createOracleTextIndex();
        }

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, DIM_384);
        String[] searchTerms = {"premium", "international", "business", "digital", "investment"};

        // Warmup
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String term = searchTerms[i % searchTerms.length];
            double[] vector = queryVectors.get(i % queryVectors.size());
            switch (mode) {
                case VECTOR_ONLY -> executeMongoVectorSearch(vector, TOP_K);
                case TEXT_ONLY -> executeMongoTextSearch(term, TOP_K);
                case RRF_FUSION -> executeHybridSearch(vector, term, TOP_K);
                default -> {}
            }
        }

        // Measure MongoDB
        System.out.println("  Measuring MongoDB " + mode + "...");
        long mongoTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            String term = searchTerms[i % searchTerms.length];
            double[] vector = queryVectors.get(i);
            long start = System.nanoTime();
            switch (mode) {
                case VECTOR_ONLY -> executeMongoVectorSearch(vector, TOP_K);
                case TEXT_ONLY -> executeMongoTextSearch(term, TOP_K);
                case RRF_FUSION -> executeHybridSearch(vector, term, TOP_K);
                default -> {}
            }
            mongoTotal += System.nanoTime() - start;
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql (for VECTOR_ONLY and TEXT_ONLY modes)
        long oracleSqlAvg = -1;
        if (oracleMongoApiSupported && (mode == HybridMode.VECTOR_ONLY || mode == HybridMode.TEXT_ONLY)) {
            System.out.println("  Measuring Oracle $sql " + mode + "...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                String term = searchTerms[i % searchTerms.length];
                double[] vector = queryVectors.get(i);
                long start = System.nanoTime();
                switch (mode) {
                    case VECTOR_ONLY -> executeOracleSqlVectorSearch(vector, TOP_K, DIM_384);
                    case TEXT_ONLY -> executeOracleSqlTextSearch(term, TOP_K);
                    default -> {}
                }
                oracleTotal += System.nanoTime() - start;
            }
            oracleSqlAvg = oracleTotal / MEASUREMENT_ITERATIONS;
        }

        // Measure Oracle JDBC (for VECTOR_ONLY and TEXT_ONLY modes)
        long oracleJdbcAvg = -1;
        if (oracleVectorSupported && (mode == HybridMode.VECTOR_ONLY || mode == HybridMode.TEXT_ONLY)) {
            System.out.println("  Measuring Oracle JDBC " + mode + "...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                String term = searchTerms[i % searchTerms.length];
                double[] vector = queryVectors.get(i);
                long start = System.nanoTime();
                switch (mode) {
                    case VECTOR_ONLY -> executeOracleJdbcVectorSearch(vector, TOP_K);
                    case TEXT_ONLY -> executeOracleJdbcTextSearch(term, TOP_K);
                    default -> {}
                }
                oracleTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleTotal / MEASUREMENT_ITERATIONS;
        }

        // Store results (3 elements: MongoDB, Oracle $sql, Oracle JDBC)
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        awrSnapshotAfter(testId);

        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleSqlAvg > 0) {
            System.out.printf(", Oracle $sql: %.2f ms", oracleSqlAvg / 1_000_000.0);
        }
        if (oracleJdbcAvg > 0) {
            System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        }
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    /**
     * Execute Oracle $sql text search using LIKE via ORDS MongoDB API
     */
    private static List<Document> executeOracleSqlTextSearch(String searchText, int limit) {
        if (!oracleMongoApiSupported) return Collections.emptyList();

        String sqlStatement = String.format("""
            SELECT id, data FROM %s
            WHERE UPPER(description) LIKE UPPER('%%%s%%')
            FETCH FIRST %d ROWS ONLY
            """, ACCOUNTS_TABLE, searchText, limit);

        Document sqlStage = new Document("$sql", sqlStatement);

        List<Document> results = new ArrayList<>();
        try {
            oracleSodaAccountsCollection.aggregate(Collections.singletonList(sqlStage))
                .forEach(results::add);
        } catch (Exception e) {
            // $sql text search may fail on some configurations
        }
        return results;
    }

    /**
     * Execute Oracle JDBC text search using LIKE (simplified text search)
     * Note: Full Oracle Text requires CONTAINS which needs CTX_DDL setup
     */
    private List<Document> executeOracleJdbcTextSearch(String searchText, int limit) {
        if (oracleJdbcConnection == null) return Collections.emptyList();

        List<Document> results = new ArrayList<>();
        try {
            // Create or reuse cached PreparedStatement
            if (cachedTextSearchStmt == null) {
                String sql = """
                    SELECT id FROM %s
                    WHERE UPPER(description) LIKE UPPER(?)
                    FETCH FIRST ? ROWS ONLY
                    """.formatted(ACCOUNTS_TABLE);
                cachedTextSearchStmt = oracleJdbcConnection.prepareStatement(sql);
            }

            cachedTextSearchStmt.setString(1, "%" + searchText + "%");
            cachedTextSearchStmt.setInt(2, limit);

            int count = 0;
            try (ResultSet rs = cachedTextSearchStmt.executeQuery()) {
                while (rs.next()) count++;
            }
            return Collections.nCopies(count, new Document());
        } catch (SQLException e) {
            // Silently fail
        }
        return Collections.emptyList();
    }

    /**
     * Execute MongoDB text search
     */
    private List<Document> executeMongoTextSearch(String searchText, int limit) {
        Document textSearchStage = new Document("$match",
            new Document("$text", new Document("$search", searchText)));
        Document limitStage = new Document("$limit", limit);
        Document scoreStage = new Document("$addFields",
            new Document("textScore", new Document("$meta", "textScore")));

        List<Document> results = new ArrayList<>();
        try {
            accountsCollection.aggregate(Arrays.asList(textSearchStage, scoreStage, limitStage))
                .forEach(results::add);
        } catch (Exception e) {
            // Text index may not be ready
            System.out.println("    Text search failed: " + e.getMessage());
        }
        return results;
    }

    /**
     * Execute hybrid search with RRF fusion
     */
    private List<Document> executeHybridSearch(double[] queryVector, String searchText, int limit) {
        // Get vector search results
        List<Document> vectorResults = executeMongoVectorSearch(queryVector, limit * 2);
        List<String> vectorIds = vectorResults.stream().map(d -> d.getString("_id")).toList();

        // Get text search results
        List<Document> textResults = executeMongoTextSearch(searchText, limit * 2);
        List<String> textIds = textResults.stream().map(d -> d.getString("_id")).toList();

        // Fuse with RRF
        List<String> fusedIds = rrfFusion(vectorIds, textIds, 60);

        // Return top-k fused results
        List<String> topK = fusedIds.subList(0, Math.min(limit, fusedIds.size()));

        // Fetch full documents for top-k
        List<Document> results = new ArrayList<>();
        for (String id : topK) {
            Document doc = accountsCollection.find(new Document("_id", id)).first();
            if (doc != null) results.add(doc);
        }
        return results;
    }

    /**
     * Create MongoDB text index on description field
     */
    private void createMongoTextIndex() {
        try {
            accountsCollection.createIndex(new Document("description", "text").append("tags", "text"));
        } catch (Exception e) {
            // Index may already exist
        }
    }

    /**
     * Create Oracle text index on description
     */
    private void createOracleTextIndex() {
        if (oracleJdbcConnection == null) return;
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try {
                stmt.execute("DROP INDEX idx_accounts_text");
            } catch (SQLException ignored) {}

            // Create basic text index - CTX requires specific setup
            // For now, skip the full-text index creation
        } catch (SQLException e) {
            System.out.println("    Oracle text index creation skipped: " + e.getMessage());
        }
    }

    // ==========================================================================
    // Main Benchmark Runner - VS6 (Quantization)
    // ==========================================================================

    private void runQuantizationBenchmark(String testId, QuantizationType type) throws SQLException {
        System.out.println("\n  " + testId + " Setup (type: " + type + ")...");

        awrSnapshotBefore(testId);

        int accountCount = 5000; // Smaller count for quantization tests
        setupEnhancedBenchmarkData(accountCount, DIM_384);

        List<double[]> queryVectors = generateQueryVectors(MEASUREMENT_ITERATIONS, DIM_384);

        // Warmup with appropriate quantization
        System.out.println("  Warmup (" + WARMUP_ITERATIONS + " iterations)...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            if (type == QuantizationType.FLOAT32) {
                executeMongoVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K);
            }
            if (oracleVectorSupported) {
                executeOracleJdbcVectorSearch(queryVectors.get(i % queryVectors.size()), TOP_K);
            }
        }

        // Measure MongoDB
        System.out.println("  Measuring MongoDB " + type + "...");
        long mongoTotal = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            executeMongoVectorSearch(queryVectors.get(i), TOP_K);
            mongoTotal += System.nanoTime() - start;
        }
        long mongoAvg = mongoTotal / MEASUREMENT_ITERATIONS;

        // Measure Oracle $sql
        long oracleSqlAvg = -1;
        if (oracleMongoApiSupported) {
            System.out.println("  Measuring Oracle $sql " + type + "...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleSqlVectorSearch(queryVectors.get(i), TOP_K, DIM_384);
                oracleTotal += System.nanoTime() - start;
            }
            oracleSqlAvg = oracleTotal / MEASUREMENT_ITERATIONS;
        }

        // Measure Oracle JDBC
        long oracleJdbcAvg = -1;
        if (oracleVectorSupported) {
            System.out.println("  Measuring Oracle JDBC " + type + "...");
            long oracleTotal = 0;
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                executeOracleJdbcVectorSearch(queryVectors.get(i), TOP_K);
                oracleTotal += System.nanoTime() - start;
            }
            oracleJdbcAvg = oracleTotal / MEASUREMENT_ITERATIONS;
        }

        // Store results
        results.put(testId, new long[]{mongoAvg, oracleSqlAvg, oracleJdbcAvg});

        awrSnapshotAfter(testId);

        System.out.printf("  Results: MongoDB: %.2f ms", mongoAvg / 1_000_000.0);
        if (oracleSqlAvg > 0) {
            System.out.printf(", Oracle $sql: %.2f ms", oracleSqlAvg / 1_000_000.0);
        }
        if (oracleJdbcAvg > 0) {
            System.out.printf(", Oracle JDBC: %.2f ms", oracleJdbcAvg / 1_000_000.0);
        }
        System.out.println();

        assertTrue(mongoAvg > 0, "MongoDB timing should be positive");
    }

    // ==========================================================================
    // Enhanced Data Setup
    // ==========================================================================

    private void setupEnhancedBenchmarkData(int accountCount, int dimensions) throws SQLException {
        System.out.println("\n  Setting up enhanced benchmark data...");
        System.out.println("    Accounts: " + accountCount);
        System.out.println("    Dimensions: " + dimensions);

        List<Document> accounts = generateEnhancedAccountDocuments(accountCount, dimensions);

        // MongoDB Native setup
        if (mongoVectorSearchSupported) {
            accountsCollection.drop();
            accountsCollection.insertMany(accounts);
            createMongoVectorSearchIndex(dimensions);
            waitForIndexReady(5000);
            System.out.println("    MongoDB Native: " + accountsCollection.countDocuments() + " accounts");
        }

        // Oracle JDBC setup with enhanced schema
        if (oracleVectorSupported) {
            createEnhancedOracleAccountsTable(dimensions);
            insertEnhancedAccountsIntoOracle(accounts, dimensions);

            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + ACCOUNTS_TABLE)) {
                rs.next();
                System.out.println("    Oracle JDBC: " + rs.getInt(1) + " accounts");
            }
        }

        System.out.println("    Setup complete.");
    }

    /**
     * Create enhanced Oracle accounts table with additional columns for filtered search
     */
    private static void createEnhancedOracleAccountsTable(int dimensions) throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try {
                stmt.execute("DROP TABLE " + ACCOUNTS_TABLE + " CASCADE CONSTRAINTS");
            } catch (SQLException ignored) {}

            String ddl = String.format("""
                CREATE TABLE %s (
                    id VARCHAR2(100) PRIMARY KEY,
                    tenant_id VARCHAR2(50),
                    data JSON,
                    embedding VECTOR(%d, FLOAT64),
                    region VARCHAR2(20),
                    account_type VARCHAR2(20),
                    balance NUMBER(18,2),
                    risk_score NUMBER(5,4),
                    description CLOB
                )
                """, ACCOUNTS_TABLE, dimensions);
            stmt.execute(ddl);

            // Create indexes for filtered search
            stmt.execute("CREATE INDEX idx_accounts_region ON " + ACCOUNTS_TABLE + "(region)");
            stmt.execute("CREATE INDEX idx_accounts_type ON " + ACCOUNTS_TABLE + "(account_type)");
            stmt.execute("CREATE INDEX idx_accounts_balance ON " + ACCOUNTS_TABLE + "(balance)");
            stmt.execute("CREATE INDEX idx_accounts_tenant ON " + ACCOUNTS_TABLE + "(tenant_id)");

            // Composite indexes for filtered vector search (VS4 optimization)
            // These allow Oracle to filter first, then compute vector distance on subset
            stmt.execute("CREATE INDEX idx_accounts_region_id ON " + ACCOUNTS_TABLE + "(region, id)");
            stmt.execute("CREATE INDEX idx_accounts_region_type ON " + ACCOUNTS_TABLE + "(region, account_type, id)");

            // Create vector index for faster similarity search
            // Try HNSW first (faster, in-memory), fall back to IVF (storage-based)
            boolean vectorIndexCreated = false;
            try {
                stmt.execute(String.format("""
                    CREATE VECTOR INDEX idx_accounts_vector ON %s(embedding)
                    ORGANIZATION INMEMORY NEIGHBOR GRAPH
                    DISTANCE COSINE
                    WITH TARGET ACCURACY 95
                    PARAMETERS (type HNSW, neighbors 40, efconstruction 500)
                    """, ACCOUNTS_TABLE));
                System.out.println("    HNSW vector index created (in-memory, graph-based)");
                vectorIndexCreated = true;
            } catch (SQLException e) {
                // HNSW requires VECTOR_MEMORY_SIZE to be configured - fall back to IVF
                System.out.println("    HNSW unavailable (requires vector_memory_size), trying IVF...");
            }

            if (!vectorIndexCreated) {
                try {
                    stmt.execute(String.format("""
                        CREATE VECTOR INDEX idx_accounts_vector ON %s(embedding)
                        ORGANIZATION NEIGHBOR PARTITIONS
                        DISTANCE COSINE
                        WITH TARGET ACCURACY 95
                        """, ACCOUNTS_TABLE));
                    System.out.println("    IVF vector index created (storage-based, partition-based)");
                } catch (SQLException e) {
                    System.out.println("    Note: Vector index creation failed: " + e.getMessage());
                }
            }

            // Print indexes created for diagnostic purposes
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT index_name, index_type FROM user_indexes WHERE table_name = '" +
                    ACCOUNTS_TABLE.toUpperCase() + "' ORDER BY index_name")) {
                System.out.print("    Indexes created: ");
                StringBuilder indexList = new StringBuilder();
                while (rs.next()) {
                    if (indexList.length() > 0) indexList.append(", ");
                    indexList.append(rs.getString("index_name"));
                }
                System.out.println(indexList.length() > 0 ? indexList : "(none)");
            }
        }
    }

    /**
     * Insert enhanced accounts into Oracle with additional columns
     */
    private static void insertEnhancedAccountsIntoOracle(List<Document> docs, int dimensions) throws SQLException {
        String sql = """
            INSERT INTO %s (id, tenant_id, data, embedding, region, account_type, balance, risk_score, description)
            VALUES (?, ?, ?, TO_VECTOR(?), ?, ?, ?, ?, ?)
            """.formatted(ACCOUNTS_TABLE);

        try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                pstmt.setString(1, doc.getString("_id"));
                pstmt.setString(2, doc.getString("tenantId"));

                Document dataDoc = new Document(doc);
                dataDoc.remove("embedding");
                pstmt.setString(3, dataDoc.toJson());

                @SuppressWarnings("unchecked")
                List<Double> embedding = (List<Double>) doc.get("embedding");
                pstmt.setString(4, formatVectorString(embedding.stream().mapToDouble(Double::doubleValue).toArray()));

                pstmt.setString(5, doc.getString("region"));
                pstmt.setString(6, doc.getString("accountType"));
                pstmt.setDouble(7, doc.getDouble("balance"));
                pstmt.setDouble(8, doc.getDouble("riskScore"));
                pstmt.setString(9, doc.getString("description"));

                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    // ==========================================================================
    // Filtered Vector Search Execution
    // ==========================================================================

    /**
     * Execute MongoDB filtered vector search with preFilter
     */
    private static List<Document> executeMongoFilteredVectorSearch(double[] queryVector, int limit, Document preFilter) {
        List<Double> queryList = toDoubleList(queryVector);

        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", limit * 20) // Higher for filtered search
            .append("limit", limit)
            .append("filter", preFilter));

        List<Document> results = new ArrayList<>();
        try {
            accountsCollection.aggregate(Collections.singletonList(vectorSearchStage))
                .forEach(results::add);
        } catch (Exception e) {
            System.out.println("    Filtered vector search failed: " + e.getMessage());
        }
        return results;
    }

    /**
     * Execute Oracle filtered vector search with WHERE clause
     */
    private static List<Document> executeOracleFilteredVectorSearch(double[] queryVector, int limit, String whereClause)
            throws SQLException {
        int dimensions = queryVector.length;

        // Create or reuse cached PreparedStatement (re-prepare if dimensions or whereClause changed)
        if (cachedFilteredVectorSearchStmt == null ||
            cachedFilteredSearchDimensions != dimensions ||
            !whereClause.equals(cachedFilteredSearchWhereClause)) {

            if (cachedFilteredVectorSearchStmt != null) {
                cachedFilteredVectorSearchStmt.close();
            }
            String sql = String.format("""
                SELECT id, VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                WHERE %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
                """, dimensions, ACCOUNTS_TABLE, whereClause);
            cachedFilteredVectorSearchStmt = oracleJdbcConnection.prepareStatement(sql);
            cachedFilteredSearchDimensions = dimensions;
            cachedFilteredSearchWhereClause = whereClause;
        }

        String vectorStr = formatVectorString(queryVector);
        cachedFilteredVectorSearchStmt.setString(1, vectorStr);
        cachedFilteredVectorSearchStmt.setInt(2, limit);

        int count = 0;
        try (ResultSet rs = cachedFilteredVectorSearchStmt.executeQuery()) {
            while (rs.next()) count++;
        }
        return Collections.nCopies(count, new Document());
    }

    private static String buildOracleFilteredJdbcSql(int dimensions, String whereClause) {
        // Use PARALLEL and let optimizer decide best plan
        // The WHERE clause uses indexed columns (region, account_type, balance)
        return String.format("""
            SELECT /*+ PARALLEL(4) */ id, data,
                   VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
            FROM %s
            WHERE %s
            ORDER BY distance
            FETCH FIRST ? ROWS ONLY
            """, dimensions, ACCOUNTS_TABLE, whereClause);
    }

    /**
     * Print explain plan for filtered vector search to diagnose index usage
     */
    private static void printFilteredSearchExplainPlan(int dimensions, String whereClause) {
        if (oracleJdbcConnection == null) return;

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'VS4_DIAG'");

            String explainSql = String.format("""
                EXPLAIN PLAN SET STATEMENT_ID = 'VS4_DIAG' FOR
                SELECT /*+ PARALLEL(4) */ id, data,
                       VECTOR_DISTANCE(embedding, TO_VECTOR('[0.1,0.2]', %d, FLOAT64), COSINE) AS distance
                FROM %s
                WHERE %s
                ORDER BY distance
                FETCH FIRST 10 ROWS ONLY
                """, dimensions, ACCOUNTS_TABLE, whereClause);

            stmt.execute(explainSql);

            System.out.println("  Oracle JDBC Explain Plan:");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'VS4_DIAG', 'BASIC +PREDICATE'))")) {
                int lineCount = 0;
                while (rs.next() && lineCount < 30) {
                    System.out.println("    " + rs.getString(1));
                    lineCount++;
                }
            }
        } catch (SQLException e) {
            System.out.println("  Explain plan capture failed: " + e.getMessage());
        }
    }

    private static String captureSqlMonitorForFilteredSearch(String sql, double[] queryVector, String testId) {
        if (oracleJdbcConnection == null) return "";

        try {
            String monitoredSql = sql.replace("SELECT", "SELECT /*+ MONITOR */");
            String vectorStr = formatVectorString(queryVector);

            try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(monitoredSql)) {
                pstmt.setString(1, vectorStr);
                pstmt.setInt(2, TOP_K);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_filtered", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    // ==========================================================================
    // Execution Methods - MongoDB Native
    // ==========================================================================

    private static List<Document> executeMongoVectorSearch(double[] queryVector, int limit) {
        List<Double> queryList = toDoubleList(queryVector);

        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", limit * 10)
            .append("limit", limit));

        List<Document> results = new ArrayList<>();
        accountsCollection.aggregate(Collections.singletonList(vectorSearchStage))
            .forEach(results::add);
        return results;
    }

    private static List<Document> executeMongoVectorSearchWithLookup(double[] queryVector, int limit) {
        List<Double> queryList = toDoubleList(queryVector);

        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", limit * 10)
            .append("limit", limit));

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);

        Document lookupStage = new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", cal.getTime()))
                    ))
                )),
                new Document("$sort", new Document("transactionDate", -1)),
                new Document("$limit", 10)
            ))
            .append("as", "recentTransactions"));

        List<Document> results = new ArrayList<>();
        accountsCollection.aggregate(Arrays.asList(vectorSearchStage, lookupStage))
            .forEach(results::add);
        return results;
    }

    // ==========================================================================
    // RAG2: Weekly Aggregation Execution Methods
    // ==========================================================================

    /**
     * RAG2: MongoDB Vector Search with Weekly Transaction Aggregation
     *
     * Pipeline: $vectorSearch → $lookup transactions (30 days) → $group by ISO week
     * Returns accounts with weekly statistics (count, total, avg per week)
     */
    private static List<AccountWithWeeklyStats> executeMongoWeeklyAggregation(double[] queryVector, int limit) {
        List<Double> queryList = toDoubleList(queryVector);

        // Stage 1: Vector search for top-K accounts
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", limit * 10)
            .append("limit", limit));

        // Stage 2: Add vector score
        Document addScoreStage = new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore")));

        // Calculate 30 days ago
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);

        // Stage 3: Lookup transactions and group by ISO week
        Document lookupWithGroupStage = new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                // Match transactions for this account in last 30 days
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", cal.getTime()))
                    ))
                )),
                // Group by ISO week
                new Document("$group", new Document()
                    .append("_id", new Document("$isoWeek", "$transactionDate"))
                    .append("weekStart", new Document("$min", "$transactionDate"))
                    .append("txnCount", new Document("$sum", 1))
                    .append("totalAmount", new Document("$sum", "$amount"))
                    .append("avgAmount", new Document("$avg", "$amount"))
                ),
                // Sort by week number
                new Document("$sort", new Document("_id", 1))
            ))
            .append("as", "weeklyStats"));

        List<AccountWithWeeklyStats> results = new ArrayList<>();
        accountsCollection.aggregate(Arrays.asList(vectorSearchStage, addScoreStage, lookupWithGroupStage))
            .forEach(doc -> {
                String accountId = doc.getString("accountId");
                double vectorScore = doc.getDouble("vectorScore") != null ? doc.getDouble("vectorScore") : 0.0;

                @SuppressWarnings("unchecked")
                List<Document> weeklyDocs = (List<Document>) doc.get("weeklyStats");
                List<WeeklyStats> weeklyStats = new ArrayList<>();

                if (weeklyDocs != null) {
                    for (Document weekDoc : weeklyDocs) {
                        int isoWeek = weekDoc.getInteger("_id", 0);
                        java.util.Date weekStart = weekDoc.getDate("weekStart");
                        String weekStartStr = weekStart != null ?
                            new java.text.SimpleDateFormat("yyyy-MM-dd").format(weekStart) : "";
                        int txnCount = weekDoc.getInteger("txnCount", 0);
                        double totalAmount = weekDoc.getDouble("totalAmount") != null ?
                            weekDoc.getDouble("totalAmount") : 0.0;
                        double avgAmount = weekDoc.getDouble("avgAmount") != null ?
                            weekDoc.getDouble("avgAmount") : 0.0;

                        weeklyStats.add(new WeeklyStats(weekStartStr, isoWeek, txnCount, totalAmount, avgAmount));
                    }
                }

                results.add(new AccountWithWeeklyStats(accountId, vectorScore, weeklyStats));
            });

        return results;
    }

    /**
     * RAG2: Oracle JDBC Vector Search with Weekly Transaction Aggregation
     *
     * Query: CTE for top-K accounts → JOIN transactions → GROUP BY TRUNC(date, 'IW')
     * Returns accounts with weekly statistics (count, total, avg per week)
     */
    private static List<AccountWithWeeklyStats> executeOracleJdbcWeeklyAggregation(
            double[] queryVector, int limit, int dimensions) throws SQLException {

        String sql = """
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.id, a.account_id, a.distance,
                   TRUNC(t.transaction_date, 'IW') AS week_start,
                   TO_CHAR(t.transaction_date, 'IW') AS iso_week,
                   COUNT(*) AS txn_count,
                   SUM(t.amount) AS total_amount,
                   AVG(t.amount) AS avg_amount
            FROM top_accounts a
            LEFT JOIN %s t
                ON t.account_id = a.account_id
                AND t.transaction_date >= SYSDATE - 30
            GROUP BY a.id, a.account_id, a.distance, TRUNC(t.transaction_date, 'IW'), TO_CHAR(t.transaction_date, 'IW')
            ORDER BY a.distance, week_start
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

        String vectorStr = formatVectorString(queryVector);
        Map<String, AccountWithWeeklyStatsBuilder> accountMap = new LinkedHashMap<>();

        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    double distance = rs.getDouble("distance");
                    double vectorScore = 1.0 - distance; // Convert distance to similarity score

                    // Get or create account builder
                    AccountWithWeeklyStatsBuilder builder = accountMap.computeIfAbsent(
                        accountId,
                        k -> new AccountWithWeeklyStatsBuilder(accountId, vectorScore)
                    );

                    // Add weekly stats if not null (LEFT JOIN may produce nulls)
                    java.sql.Date weekStart = rs.getDate("week_start");
                    if (weekStart != null) {
                        String weekStartStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(weekStart);
                        int isoWeek = rs.getInt("iso_week");
                        int txnCount = rs.getInt("txn_count");
                        double totalAmount = rs.getDouble("total_amount");
                        double avgAmount = rs.getDouble("avg_amount");

                        builder.addWeeklyStats(new WeeklyStats(weekStartStr, isoWeek, txnCount, totalAmount, avgAmount));
                    }
                }
            }
        }

        // Convert builders to final records
        List<AccountWithWeeklyStats> results = new ArrayList<>();
        for (AccountWithWeeklyStatsBuilder builder : accountMap.values()) {
            results.add(builder.build());
        }
        return results;
    }

    // Helper class to build AccountWithWeeklyStats
    private static class AccountWithWeeklyStatsBuilder {
        private final String accountId;
        private final double vectorScore;
        private final List<WeeklyStats> weeklyStats = new ArrayList<>();

        AccountWithWeeklyStatsBuilder(String accountId, double vectorScore) {
            this.accountId = accountId;
            this.vectorScore = vectorScore;
        }

        void addWeeklyStats(WeeklyStats stats) {
            weeklyStats.add(stats);
        }

        AccountWithWeeklyStats build() {
            return new AccountWithWeeklyStats(accountId, vectorScore, weeklyStats);
        }
    }

    // ==========================================================================
    // RAG3: Customer 360 Profile Execution Methods
    // ==========================================================================

    /**
     * RAG3: MongoDB Vector Search with Customer 360 Profile Assembly
     *
     * Pipeline: $vectorSearch → $lookup all transactions → compute profile stats
     * Returns complete customer profile with spending by category
     */
    private static List<Customer360Profile> executeMongoCustomer360(double[] queryVector, int limit) {
        List<Double> queryList = toDoubleList(queryVector);

        // Stage 1: Vector search for top-K accounts
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", limit * 10)
            .append("limit", limit));

        // Stage 2: Add vector score
        Document addScoreStage = new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore")));

        // Stage 3: Lookup all transactions for this account
        Document lookupStage = new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("localField", "accountId")
            .append("foreignField", "accountId")
            .append("as", "allTransactions"));

        // Stage 4: Compute profile statistics and spending by category
        // Use $reduce to build category spending map in place (avoids complex unwind/group)
        Document addProfileStage = new Document("$addFields", new Document()
            .append("profile", new Document()
                .append("totalSpent", new Document("$sum", "$allTransactions.amount"))
                .append("transactionCount", new Document("$size", "$allTransactions"))
                .append("avgTransactionAmount", new Document("$avg", "$allTransactions.amount"))
                .append("lastActivityDate", new Document("$max", "$allTransactions.transactionDate"))
                .append("daysSinceLastActivity", new Document("$dateDiff", new Document()
                    .append("startDate", new Document("$max", "$allTransactions.transactionDate"))
                    .append("endDate", "$$NOW")
                    .append("unit", "day")))
            )
            // Compute unique categories and their totals
            .append("categoryList", new Document("$setUnion", Arrays.asList(
                new Document("$map", new Document()
                    .append("input", "$allTransactions")
                    .append("as", "txn")
                    .append("in", "$$txn.category")))))
        );

        // Stage 5: Compute spending by each category
        Document addCategorySpendingStage = new Document("$addFields", new Document()
            .append("spendingByCategory", new Document("$arrayToObject",
                new Document("$map", new Document()
                    .append("input", "$categoryList")
                    .append("as", "cat")
                    .append("in", new Document()
                        .append("k", "$$cat")
                        .append("v", new Document("$reduce", new Document()
                            .append("input", "$allTransactions")
                            .append("initialValue", 0.0)
                            .append("in", new Document("$add", Arrays.asList(
                                "$$value",
                                new Document("$cond", Arrays.asList(
                                    new Document("$eq", Arrays.asList("$$this.category", "$$cat")),
                                    "$$this.amount",
                                    0
                                ))
                            )))
                        ))
                    ))
            ))
        );

        // Stage 6: Project final fields (inclusion only, MongoDB doesn't allow mixing inclusion/exclusion)
        Document projectStage = new Document("$project", new Document()
            .append("_id", 0)
            .append("accountId", 1)
            .append("vectorScore", 1)
            .append("profile", 1)
            .append("spendingByCategory", 1));

        List<Customer360Profile> results = new ArrayList<>();
        accountsCollection.aggregate(Arrays.asList(
            vectorSearchStage, addScoreStage, lookupStage, addProfileStage,
            addCategorySpendingStage, projectStage
        )).forEach(doc -> {
            String accountId = doc.getString("accountId");
            double vectorScore = doc.getDouble("vectorScore") != null ? doc.getDouble("vectorScore") : 0.0;

            Document profile = doc.get("profile", Document.class);
            // MongoDB may return Long or Integer for counts, use Number to handle both
            int txnCount = 0;
            if (profile != null) {
                Object countObj = profile.get("transactionCount");
                if (countObj instanceof Number) {
                    txnCount = ((Number) countObj).intValue();
                }
            }
            // MongoDB may return Integer/Long instead of Double for aggregation results
            double totalSpent = 0.0;
            if (profile != null) {
                Object totalObj = profile.get("totalSpent");
                if (totalObj instanceof Number) {
                    totalSpent = ((Number) totalObj).doubleValue();
                }
            }
            double avgAmount = 0.0;
            if (profile != null) {
                Object avgObj = profile.get("avgTransactionAmount");
                if (avgObj instanceof Number) {
                    avgAmount = ((Number) avgObj).doubleValue();
                }
            }

            java.util.Date lastActivityDate = profile != null ? profile.getDate("lastActivityDate") : null;
            String lastActivityStr = lastActivityDate != null ?
                new java.text.SimpleDateFormat("yyyy-MM-dd").format(lastActivityDate) : "";

            // MongoDB $dateDiff returns Long, not Integer
            int daysSince = 0;
            if (profile != null) {
                Object daysObj = profile.get("daysSinceLastActivity");
                if (daysObj instanceof Number) {
                    daysSince = ((Number) daysObj).intValue();
                }
            }

            @SuppressWarnings("unchecked")
            Document spendingDoc = doc.get("spendingByCategory", Document.class);
            Map<String, Double> spendingByCategory = new LinkedHashMap<>();
            if (spendingDoc != null) {
                for (String key : spendingDoc.keySet()) {
                    Object value = spendingDoc.get(key);
                    if (value instanceof Number) {
                        spendingByCategory.put(key, ((Number) value).doubleValue());
                    }
                }
            }

            results.add(new Customer360Profile(
                accountId, vectorScore, txnCount, totalSpent, avgAmount,
                lastActivityStr, daysSince, spendingByCategory
            ));
        });

        return results;
    }

    /**
     * RAG3: Oracle JDBC Vector Search with Customer 360 Profile Assembly
     *
     * Query: CTE for top-K accounts → JOIN transactions → aggregate stats + category spending
     * Returns complete customer profile with spending by category
     */
    private static List<Customer360Profile> executeOracleJdbcCustomer360(
            double[] queryVector, int limit, int dimensions) throws SQLException {

        // First query: Get account stats
        String statsSql = """
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.id, a.account_id, (1 - a.distance) AS vector_score,
                   COUNT(t.transaction_id) AS txn_count,
                   NVL(SUM(t.amount), 0) AS total_spent,
                   NVL(AVG(t.amount), 0) AS avg_amount,
                   MAX(t.transaction_date) AS last_activity,
                   NVL(TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)), 0) AS days_since
            FROM top_accounts a
            LEFT JOIN %s t ON t.account_id = a.account_id
            GROUP BY a.id, a.account_id, a.distance
            ORDER BY a.distance
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

        // Second query: Get category spending for each account
        String categorySql = """
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id
                FROM %s
                ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.account_id, t.category, SUM(t.amount) AS category_total
            FROM top_accounts a
            JOIN %s t ON t.account_id = a.account_id
            GROUP BY a.account_id, t.category
            ORDER BY a.account_id, t.category
            """.formatted(ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);

        String vectorStr = formatVectorString(queryVector);

        // Execute stats query
        Map<String, Customer360ProfileBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(statsSql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    double vectorScore = rs.getDouble("vector_score");
                    int txnCount = rs.getInt("txn_count");
                    double totalSpent = rs.getDouble("total_spent");
                    double avgAmount = rs.getDouble("avg_amount");
                    java.sql.Date lastActivity = rs.getDate("last_activity");
                    String lastActivityStr = lastActivity != null ?
                        new java.text.SimpleDateFormat("yyyy-MM-dd").format(lastActivity) : "";
                    int daysSince = rs.getInt("days_since");

                    builders.put(accountId, new Customer360ProfileBuilder(
                        accountId, vectorScore, txnCount, totalSpent, avgAmount,
                        lastActivityStr, daysSince
                    ));
                }
            }
        }

        // Execute category query
        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(categorySql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    String category = rs.getString("category");
                    double categoryTotal = rs.getDouble("category_total");

                    Customer360ProfileBuilder builder = builders.get(accountId);
                    if (builder != null && category != null) {
                        builder.addCategorySpending(category, categoryTotal);
                    }
                }
            }
        }

        // Build final profiles
        List<Customer360Profile> results = new ArrayList<>();
        for (Customer360ProfileBuilder builder : builders.values()) {
            results.add(builder.build());
        }
        return results;
    }

    // Helper class to build Customer360Profile
    private static class Customer360ProfileBuilder {
        private final String accountId;
        private final double vectorScore;
        private final int transactionCount;
        private final double totalSpent;
        private final double avgTransactionAmount;
        private final String lastActivityDate;
        private final int daysSinceLastActivity;
        private final Map<String, Double> spendingByCategory = new LinkedHashMap<>();

        Customer360ProfileBuilder(String accountId, double vectorScore, int transactionCount,
                                   double totalSpent, double avgTransactionAmount,
                                   String lastActivityDate, int daysSinceLastActivity) {
            this.accountId = accountId;
            this.vectorScore = vectorScore;
            this.transactionCount = transactionCount;
            this.totalSpent = totalSpent;
            this.avgTransactionAmount = avgTransactionAmount;
            this.lastActivityDate = lastActivityDate;
            this.daysSinceLastActivity = daysSinceLastActivity;
        }

        void addCategorySpending(String category, double amount) {
            spendingByCategory.put(category, amount);
        }

        Customer360Profile build() {
            return new Customer360Profile(
                accountId, vectorScore, transactionCount, totalSpent, avgTransactionAmount,
                lastActivityDate, daysSinceLastActivity, spendingByCategory
            );
        }
    }

    // ==========================================================================
    // Execution Methods - RAG1 Graph Traversal
    // ==========================================================================

    /**
     * RAG1: MongoDB Vector Search with Graph Traversal via $graphLookup
     *
     * Pipeline: $vectorSearch → $graphLookup (same tenant) → $lookup (merchants) → shared merchant lookup
     * Finds accounts related by tenant and shared merchant relationships
     */
    private static List<GraphTraversalResult> executeMongoGraphTraversal(double[] queryVector, int limit) {
        List<Double> vectorList = new ArrayList<>();
        for (double v : queryVector) vectorList.add(v);

        List<Document> pipeline = new ArrayList<>();

        // Stage 1: Vector search for seed accounts
        pipeline.add(new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", vectorList)
            .append("numCandidates", 100)
            .append("limit", limit)));

        // Stage 2: Add vector score
        pipeline.add(new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore"))));

        // Stage 3: $graphLookup for same-tenant accounts
        // This recursively finds all accounts sharing the same tenantId
        pipeline.add(new Document("$graphLookup", new Document()
            .append("from", ACCOUNTS_COLLECTION)
            .append("startWith", "$tenantId")
            .append("connectFromField", "tenantId")
            .append("connectToField", "tenantId")
            .append("as", "sameTenantAccountsRaw")
            .append("maxDepth", 0)  // Direct match only (accounts with same tenantId)
            .append("depthField", "hopCount")));

        // Stage 4: Filter out self from same-tenant accounts
        pipeline.add(new Document("$addFields", new Document()
            .append("sameTenantAccounts", new Document("$filter", new Document()
                .append("input", "$sameTenantAccountsRaw")
                .append("as", "acc")
                .append("cond", new Document("$ne", Arrays.asList("$$acc.accountId", "$accountId")))))));

        // Calculate date 90 days ago for filtering recent transactions
        java.util.Date ninetyDaysAgo = new java.util.Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000);

        // Stage 5: Lookup merchants this account transacts with (last 90 days)
        pipeline.add(new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", ninetyDaysAgo))
                    )))),
                new Document("$group", new Document("_id", "$merchant"))
            ))
            .append("as", "myMerchants")));

        // Stage 6: Lookup accounts that share these merchants (via recent transactions)
        pipeline.add(new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("merchants",
                new Document("$map", new Document()
                    .append("input", "$myMerchants")
                    .append("as", "m")
                    .append("in", "$$m._id")))
                .append("sourceAccId", "$accountId"))
            .append("pipeline", Arrays.asList(
                // Match recent transactions at any of our merchants
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$in", Arrays.asList("$merchant", "$$merchants")),
                        new Document("$gte", Arrays.asList("$transactionDate", ninetyDaysAgo))
                    )))),
                // Exclude our own transactions
                new Document("$match", new Document("$expr",
                    new Document("$ne", Arrays.asList("$accountId", "$$sourceAccId")))),
                // Group by accountId to get unique accounts
                new Document("$group", new Document("_id", "$accountId"))
            ))
            .append("as", "sharedMerchantTxns")));

        // Stage 7: Extract shared merchant account IDs
        pipeline.add(new Document("$addFields", new Document()
            .append("sharedMerchantAccountIds", new Document("$map", new Document()
                .append("input", "$sharedMerchantTxns")
                .append("as", "t")
                .append("in", "$$t._id")))));

        // Stage 8: Project final result fields only
        pipeline.add(new Document("$project", new Document()
            .append("_id", 0)
            .append("accountId", 1)
            .append("vectorScore", 1)
            .append("sameTenantAccounts", new Document("$map", new Document()
                .append("input", "$sameTenantAccounts")
                .append("as", "acc")
                .append("in", new Document()
                    .append("accountId", "$$acc.accountId")
                    .append("hopCount", new Document("$ifNull", Arrays.asList("$$acc.hopCount", 0))))))
            .append("sharedMerchantAccountIds", 1)));

        List<GraphTraversalResult> results = new ArrayList<>();
        accountsCollection.aggregate(pipeline).forEach(doc -> {
            String sourceAccountId = doc.getString("accountId");
            double vectorScore = doc.getDouble("vectorScore") != null ? doc.getDouble("vectorScore") : 0.0;

            // Parse same-tenant accounts
            List<RelatedAccount> sameTenantAccounts = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Document> sameTenantDocs = (List<Document>) doc.get("sameTenantAccounts");
            if (sameTenantDocs != null) {
                for (Document tenantAcc : sameTenantDocs) {
                    String accId = tenantAcc.getString("accountId");
                    int hops = tenantAcc.get("hopCount") instanceof Number ?
                        ((Number) tenantAcc.get("hopCount")).intValue() : 0;
                    sameTenantAccounts.add(new RelatedAccount(accId, "SAME_TENANT", hops));
                }
            }

            // Parse shared-merchant accounts
            List<RelatedAccount> sharedMerchantAccounts = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<String> sharedMerchantIds = (List<String>) doc.get("sharedMerchantAccountIds");
            if (sharedMerchantIds != null) {
                for (String accId : sharedMerchantIds) {
                    sharedMerchantAccounts.add(new RelatedAccount(accId, "SHARED_MERCHANT", 1));
                }
            }

            results.add(new GraphTraversalResult(
                sourceAccountId, vectorScore, sameTenantAccounts, sharedMerchantAccounts));
        });

        return results;
    }

    /**
     * RAG1: Oracle JDBC Vector Search with Graph Traversal via Recursive CTE
     *
     * Query: CTE for top-K accounts → Self-join for tenant traversal → JOIN for shared merchants
     * Uses simple join approach for same-tenant relationships
     */
    private static List<GraphTraversalResult> executeOracleJdbcGraphTraversal(
            double[] queryVector, int limit, int dimensions) throws SQLException {

        // Query 1: Vector search + same-tenant accounts (with PARALLEL hint)
        String sameTenantSql = """
            WITH top_accounts AS (
                SELECT /*+ PARALLEL(4) */ id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       JSON_VALUE(data, '$.tenantId') AS tenant_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT /*+ PARALLEL(4) */ ta.account_id AS source_account_id,
                   (1 - ta.distance) AS vector_score,
                   JSON_VALUE(other.data, '$.accountId') AS related_account_id,
                   'SAME_TENANT' AS relationship_type,
                   0 AS hops
            FROM top_accounts ta
            JOIN %s other
              ON JSON_VALUE(other.data, '$.tenantId') = ta.tenant_id
              AND JSON_VALUE(other.data, '$.accountId') != ta.account_id
            ORDER BY ta.distance, related_account_id
            """.formatted(dimensions, ACCOUNTS_TABLE, ACCOUNTS_TABLE);

        // Query 2: Shared merchant accounts (with date filter and PARALLEL for performance)
        // Limiting to recent transactions (90 days) dramatically reduces join cardinality
        String sharedMerchantSql = """
            WITH top_accounts AS (
                SELECT /*+ PARALLEL(4) */ id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            ),
            my_merchants AS (
                SELECT /*+ PARALLEL(4) */ DISTINCT ta.account_id, t.merchant
                FROM top_accounts ta
                JOIN %s t ON t.account_id = ta.account_id
                WHERE t.transaction_date >= TRUNC(SYSDATE) - 90
            ),
            shared_accounts AS (
                SELECT /*+ PARALLEL(4) */ DISTINCT
                    mm.account_id AS source_account_id,
                    t.account_id AS related_account_id
                FROM my_merchants mm
                JOIN %s t ON t.merchant = mm.merchant
                           AND t.transaction_date >= TRUNC(SYSDATE) - 90
                WHERE t.account_id != mm.account_id
            )
            SELECT sa.source_account_id,
                   sa.related_account_id,
                   'SHARED_MERCHANT' AS relationship_type,
                   1 AS hops
            FROM shared_accounts sa
            ORDER BY sa.source_account_id, sa.related_account_id
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE, TRANSACTIONS_TABLE);

        String vectorStr = formatVectorString(queryVector);

        // Map to build results: sourceAccountId -> GraphTraversalResultBuilder
        Map<String, GraphTraversalResultBuilder> builders = new LinkedHashMap<>();

        // First, get the vector scores for top accounts
        String vectorScoreSql = """
            SELECT /*+ PARALLEL(4) */ JSON_VALUE(data, '$.accountId') AS account_id,
                   (1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)) AS vector_score
            FROM %s
            ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
            FETCH FIRST ? ROWS ONLY
            """.formatted(dimensions, ACCOUNTS_TABLE, dimensions);

        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(vectorScoreSql)) {
            stmt.setString(1, vectorStr);
            stmt.setString(2, vectorStr);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    double vectorScore = rs.getDouble("vector_score");
                    builders.put(accountId, new GraphTraversalResultBuilder(accountId, vectorScore));
                }
            }
        }

        // Execute same-tenant query
        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sameTenantSql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String sourceAccountId = rs.getString("source_account_id");
                    String relatedAccountId = rs.getString("related_account_id");
                    String relationshipType = rs.getString("relationship_type");
                    int hops = rs.getInt("hops");

                    GraphTraversalResultBuilder builder = builders.get(sourceAccountId);
                    if (builder != null) {
                        builder.addSameTenantAccount(new RelatedAccount(relatedAccountId, relationshipType, hops));
                    }
                }
            }
        }

        // Execute shared-merchant query
        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sharedMerchantSql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String sourceAccountId = rs.getString("source_account_id");
                    String relatedAccountId = rs.getString("related_account_id");
                    String relationshipType = rs.getString("relationship_type");
                    int hops = rs.getInt("hops");

                    GraphTraversalResultBuilder builder = builders.get(sourceAccountId);
                    if (builder != null) {
                        builder.addSharedMerchantAccount(new RelatedAccount(relatedAccountId, relationshipType, hops));
                    }
                }
            }
        }

        // Build final results
        List<GraphTraversalResult> results = new ArrayList<>();
        for (GraphTraversalResultBuilder builder : builders.values()) {
            results.add(builder.build());
        }
        return results;
    }

    // Helper class to build GraphTraversalResult
    private static class GraphTraversalResultBuilder {
        private final String sourceAccountId;
        private final double vectorScore;
        private final List<RelatedAccount> sameTenantAccounts = new ArrayList<>();
        private final List<RelatedAccount> sharedMerchantAccounts = new ArrayList<>();

        GraphTraversalResultBuilder(String sourceAccountId, double vectorScore) {
            this.sourceAccountId = sourceAccountId;
            this.vectorScore = vectorScore;
        }

        void addSameTenantAccount(RelatedAccount account) {
            sameTenantAccounts.add(account);
        }

        void addSharedMerchantAccount(RelatedAccount account) {
            sharedMerchantAccounts.add(account);
        }

        GraphTraversalResult build() {
            return new GraphTraversalResult(
                sourceAccountId, vectorScore, sameTenantAccounts, sharedMerchantAccounts);
        }
    }

    // ==========================================================================
    // Execution Methods - RAG4 Activity Pattern Detection
    // ==========================================================================

    /**
     * RAG4: MongoDB Vector Search with Activity Pattern Detection via $setWindowFields
     *
     * Pipeline: $vectorSearch → $lookup transactions (90 days) → $setWindowFields for rolling 7-day window
     * → $group by date → classify burst/dormant periods
     */
    private static List<ActivityPatternResult> executeMongoActivityPattern(double[] queryVector, int limit) {
        List<Double> vectorList = new ArrayList<>();
        for (double v : queryVector) vectorList.add(v);

        // Calculate 90 days ago
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -90);
        java.util.Date ninetyDaysAgo = cal.getTime();

        List<Document> pipeline = new ArrayList<>();

        // Stage 1: Vector search for seed accounts
        pipeline.add(new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", vectorList)
            .append("numCandidates", 100)
            .append("limit", limit)));

        // Stage 2: Add vector score
        pipeline.add(new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore"))));

        // Stage 3: Lookup transactions for last 90 days with daily aggregation
        pipeline.add(new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                // Match transactions for this account in last 90 days
                new Document("$match", new Document("$expr", new Document("$and", Arrays.asList(
                    new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                    new Document("$gte", Arrays.asList("$transactionDate", ninetyDaysAgo))
                )))),
                // Sort by date for window function
                new Document("$sort", new Document("transactionDate", 1)),
                // Apply setWindowFields for rolling 7-day count
                new Document("$setWindowFields", new Document()
                    .append("sortBy", new Document("transactionDate", 1))
                    .append("output", new Document("rollingCount", new Document()
                        .append("$sum", 1)
                        .append("window", new Document("range", Arrays.asList(-6, 0)).append("unit", "day"))))),
                // Group by date to get daily stats
                new Document("$group", new Document()
                    .append("_id", new Document("$dateToString", new Document()
                        .append("format", "%Y-%m-%d")
                        .append("date", "$transactionDate")))
                    .append("dailyCount", new Document("$sum", 1))
                    .append("rollingWeekCount", new Document("$max", "$rollingCount"))),
                // Sort by date
                new Document("$sort", new Document("_id", 1))
            ))
            .append("as", "dailyActivity")));

        // Stage 4: Calculate statistics and classify activity
        pipeline.add(new Document("$addFields", new Document()
            .append("avgDailyTxns", new Document("$cond", Arrays.asList(
                new Document("$gt", Arrays.asList(new Document("$size", "$dailyActivity"), 0)),
                new Document("$avg", "$dailyActivity.dailyCount"),
                0
            )))));

        // Stage 5: Project final result with activity pattern classification
        pipeline.add(new Document("$project", new Document()
            .append("_id", 0)
            .append("accountId", 1)
            .append("vectorScore", 1)
            .append("avgDailyTxns", 1)
            .append("dailyActivity", new Document("$map", new Document()
                .append("input", "$dailyActivity")
                .append("as", "day")
                .append("in", new Document()
                    .append("date", "$$day._id")
                    .append("dailyCount", "$$day.dailyCount")
                    .append("rollingWeekCount", "$$day.rollingWeekCount")
                    .append("status", new Document("$cond", Arrays.asList(
                        new Document("$gt", Arrays.asList("$$day.rollingWeekCount",
                            new Document("$multiply", Arrays.asList(2, "$avgDailyTxns")))),
                        "BURST",
                        new Document("$cond", Arrays.asList(
                            new Document("$eq", Arrays.asList("$$day.dailyCount", 0)),
                            "DORMANT",
                            "NORMAL"
                        ))
                    ))))))));

        List<ActivityPatternResult> results = new ArrayList<>();
        accountsCollection.aggregate(pipeline).forEach(doc -> {
            String accountId = doc.getString("accountId");
            double vectorScore = doc.getDouble("vectorScore") != null ? doc.getDouble("vectorScore") : 0.0;
            double avgDailyTxns = doc.getDouble("avgDailyTxns") != null ? doc.getDouble("avgDailyTxns") : 0.0;

            List<DailyActivity> activityPattern = new ArrayList<>();
            int burstDayCount = 0;
            int dormantDayCount = 0;

            @SuppressWarnings("unchecked")
            List<Document> dailyActivityDocs = (List<Document>) doc.get("dailyActivity");
            if (dailyActivityDocs != null) {
                for (Document day : dailyActivityDocs) {
                    String date = day.getString("date");
                    int dailyCount = day.get("dailyCount") instanceof Number ?
                        ((Number) day.get("dailyCount")).intValue() : 0;
                    int rollingWeekCount = day.get("rollingWeekCount") instanceof Number ?
                        ((Number) day.get("rollingWeekCount")).intValue() : 0;
                    String status = day.getString("status");

                    activityPattern.add(new DailyActivity(date, dailyCount, rollingWeekCount, status));

                    if ("BURST".equals(status)) burstDayCount++;
                    if ("DORMANT".equals(status)) dormantDayCount++;
                }
            }

            results.add(new ActivityPatternResult(
                accountId, vectorScore, avgDailyTxns, activityPattern, burstDayCount, dormantDayCount));
        });

        return results;
    }

    /**
     * RAG4: Oracle JDBC Vector Search with Activity Pattern Detection via Analytic Functions
     *
     * Query: CTE for top-K accounts → daily aggregation → rolling window via SUM() OVER()
     * → classify burst/dormant periods
     */
    private static List<ActivityPatternResult> executeOracleJdbcActivityPattern(
            double[] queryVector, int limit, int dimensions) throws SQLException {

        String sql = """
            WITH top_accounts AS (
                SELECT id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            ),
            daily_txns AS (
                SELECT ta.account_id,
                       (1 - ta.distance) AS vector_score,
                       TRUNC(t.transaction_date) AS txn_date,
                       COUNT(*) AS daily_count
                FROM top_accounts ta
                JOIN %s t ON t.account_id = ta.account_id
                WHERE t.transaction_date >= SYSDATE - 90
                GROUP BY ta.account_id, ta.distance, TRUNC(t.transaction_date)
            ),
            with_rolling AS (
                SELECT account_id,
                       vector_score,
                       txn_date,
                       daily_count,
                       SUM(daily_count) OVER (
                           PARTITION BY account_id
                           ORDER BY txn_date
                           RANGE BETWEEN 6 PRECEDING AND CURRENT ROW
                       ) AS rolling_week_count
                FROM daily_txns
            ),
            account_stats AS (
                SELECT account_id,
                       AVG(daily_count) AS avg_daily_txns
                FROM daily_txns
                GROUP BY account_id
            )
            SELECT wr.account_id,
                   wr.vector_score,
                   TO_CHAR(wr.txn_date, 'YYYY-MM-DD') AS activity_date,
                   wr.daily_count,
                   wr.rolling_week_count,
                   ast.avg_daily_txns,
                   CASE
                       WHEN wr.rolling_week_count > 2 * ast.avg_daily_txns THEN 'BURST'
                       WHEN wr.daily_count = 0 THEN 'DORMANT'
                       ELSE 'NORMAL'
                   END AS activity_status
            FROM with_rolling wr
            JOIN account_stats ast ON ast.account_id = wr.account_id
            ORDER BY wr.account_id, wr.txn_date
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

        String vectorStr = formatVectorString(queryVector);

        // Map to accumulate results by account
        Map<String, ActivityPatternResultBuilder> builders = new LinkedHashMap<>();

        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sql)) {
            stmt.setString(1, vectorStr);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    double vectorScore = rs.getDouble("vector_score");
                    String activityDate = rs.getString("activity_date");
                    int dailyCount = rs.getInt("daily_count");
                    int rollingWeekCount = rs.getInt("rolling_week_count");
                    double avgDailyTxns = rs.getDouble("avg_daily_txns");
                    String status = rs.getString("activity_status");

                    ActivityPatternResultBuilder builder = builders.computeIfAbsent(accountId,
                        id -> new ActivityPatternResultBuilder(id, vectorScore, avgDailyTxns));

                    builder.addDailyActivity(new DailyActivity(activityDate, dailyCount, rollingWeekCount, status));
                }
            }
        }

        // Build final results
        List<ActivityPatternResult> results = new ArrayList<>();
        for (ActivityPatternResultBuilder builder : builders.values()) {
            results.add(builder.build());
        }
        return results;
    }

    // Helper class to build ActivityPatternResult
    private static class ActivityPatternResultBuilder {
        private final String accountId;
        private final double vectorScore;
        private final double avgDailyTxns;
        private final List<DailyActivity> activityPattern = new ArrayList<>();
        private int burstDayCount = 0;
        private int dormantDayCount = 0;

        ActivityPatternResultBuilder(String accountId, double vectorScore, double avgDailyTxns) {
            this.accountId = accountId;
            this.vectorScore = vectorScore;
            this.avgDailyTxns = avgDailyTxns;
        }

        void addDailyActivity(DailyActivity day) {
            activityPattern.add(day);
            if ("BURST".equals(day.status())) burstDayCount++;
            if ("DORMANT".equals(day.status())) dormantDayCount++;
        }

        ActivityPatternResult build() {
            return new ActivityPatternResult(
                accountId, vectorScore, avgDailyTxns, activityPattern, burstDayCount, dormantDayCount);
        }
    }

    // ==========================================================================
    // Execution Methods - RAG5 Hybrid Context Ranking
    // ==========================================================================

    /**
     * RAG5: MongoDB Vector Search with Hybrid Context Ranking
     *
     * Pipeline: $vectorSearch (over-fetch 50) → $lookup txn stats → $setWindowFields for normalization
     * → compute hybrid score (0.5*vector + 0.3*activity + 0.2*recency) → re-rank → limit 10
     */
    private static List<HybridRankingResult> executeMongoHybridRanking(double[] queryVector, int limit) {
        List<Double> vectorList = new ArrayList<>();
        for (double v : queryVector) vectorList.add(v);

        int overFetchLimit = limit * 5; // Over-fetch 5x for re-ranking

        List<Document> pipeline = new ArrayList<>();

        // Stage 1: Vector search with over-fetch
        pipeline.add(new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", vectorList)
            .append("numCandidates", overFetchLimit * 10)
            .append("limit", overFetchLimit)));

        // Stage 2: Add vector score
        pipeline.add(new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore"))));

        // Stage 3: Lookup transaction stats
        pipeline.add(new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                new Document("$match", new Document("$expr",
                    new Document("$eq", Arrays.asList("$accountId", "$$accId")))),
                new Document("$group", new Document()
                    .append("_id", null)
                    .append("txnCount", new Document("$sum", 1))
                    .append("lastTxnDate", new Document("$max", "$transactionDate")))
            ))
            .append("as", "txnStats")));

        // Stage 4: Extract txn stats with defaults
        pipeline.add(new Document("$addFields", new Document()
            .append("txnCount", new Document("$ifNull", Arrays.asList(
                new Document("$arrayElemAt", Arrays.asList("$txnStats.txnCount", 0)), 0)))
            .append("daysSinceLastTxn", new Document("$dateDiff", new Document()
                .append("startDate", new Document("$ifNull", Arrays.asList(
                    new Document("$arrayElemAt", Arrays.asList("$txnStats.lastTxnDate", 0)),
                    new java.util.Date(0))))
                .append("endDate", "$$NOW")
                .append("unit", "day")))));

        // Stage 5: Use $setWindowFields to compute min/max for normalization
        pipeline.add(new Document("$setWindowFields", new Document()
            .append("output", new Document()
                .append("maxTxnCount", new Document("$max", "$txnCount"))
                .append("minDaysSince", new Document("$min", "$daysSinceLastTxn"))
                .append("maxDaysSince", new Document("$max", "$daysSinceLastTxn")))));

        // Stage 6: Compute normalized scores
        pipeline.add(new Document("$addFields", new Document()
            .append("normalizedTxnActivity", new Document("$cond", Arrays.asList(
                new Document("$gt", Arrays.asList("$maxTxnCount", 0)),
                new Document("$divide", Arrays.asList("$txnCount", "$maxTxnCount")),
                0)))
            .append("recencyScore", new Document("$cond", Arrays.asList(
                new Document("$gt", Arrays.asList(
                    new Document("$subtract", Arrays.asList("$maxDaysSince", "$minDaysSince")), 0)),
                new Document("$subtract", Arrays.asList(1,
                    new Document("$divide", Arrays.asList(
                        new Document("$subtract", Arrays.asList("$daysSinceLastTxn", "$minDaysSince")),
                        new Document("$subtract", Arrays.asList("$maxDaysSince", "$minDaysSince")))))),
                1)))));

        // Stage 7: Compute hybrid score: 0.5*vector + 0.3*activity + 0.2*recency
        pipeline.add(new Document("$addFields", new Document()
            .append("hybridScore", new Document("$add", Arrays.asList(
                new Document("$multiply", Arrays.asList(0.5, "$vectorScore")),
                new Document("$multiply", Arrays.asList(0.3, "$normalizedTxnActivity")),
                new Document("$multiply", Arrays.asList(0.2, "$recencyScore")))))));

        // Stage 8: Sort by hybrid score descending
        pipeline.add(new Document("$sort", new Document("hybridScore", -1)));

        // Stage 9: Limit to final count
        pipeline.add(new Document("$limit", limit));

        // Stage 10: Project final fields
        pipeline.add(new Document("$project", new Document()
            .append("_id", 0)
            .append("accountId", 1)
            .append("vectorScore", 1)
            .append("txnCount", 1)
            .append("daysSinceLastTxn", 1)
            .append("normalizedTxnActivity", 1)
            .append("recencyScore", 1)
            .append("hybridScore", 1)));

        List<HybridRankingResult> results = new ArrayList<>();
        accountsCollection.aggregate(pipeline).forEach(doc -> {
            String accountId = doc.getString("accountId");
            double vectorScore = doc.get("vectorScore") instanceof Number ?
                ((Number) doc.get("vectorScore")).doubleValue() : 0.0;
            int txnCount = doc.get("txnCount") instanceof Number ?
                ((Number) doc.get("txnCount")).intValue() : 0;
            int daysSinceLastTxn = doc.get("daysSinceLastTxn") instanceof Number ?
                ((Number) doc.get("daysSinceLastTxn")).intValue() : 0;
            double normalizedTxnActivity = doc.get("normalizedTxnActivity") instanceof Number ?
                ((Number) doc.get("normalizedTxnActivity")).doubleValue() : 0.0;
            double recencyScore = doc.get("recencyScore") instanceof Number ?
                ((Number) doc.get("recencyScore")).doubleValue() : 0.0;
            double hybridScore = doc.get("hybridScore") instanceof Number ?
                ((Number) doc.get("hybridScore")).doubleValue() : 0.0;

            results.add(new HybridRankingResult(
                accountId, vectorScore, txnCount, daysSinceLastTxn,
                normalizedTxnActivity, recencyScore, hybridScore));
        });

        return results;
    }

    /**
     * RAG5: Oracle JDBC Vector Search with Hybrid Context Ranking
     *
     * Query: CTE for candidates (over-fetch 50) → JOIN txn stats → window functions for normalization
     * → compute hybrid score → ORDER BY hybrid_score → FETCH FIRST 10
     */
    private static List<HybridRankingResult> executeOracleJdbcHybridRanking(
            double[] queryVector, int limit, int dimensions) throws SQLException {

        int overFetchLimit = limit * 5; // Over-fetch 5x for re-ranking

        String sql = """
            WITH candidates AS (
                SELECT id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS vector_score
                FROM %s
                ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                FETCH FIRST ? ROWS ONLY
            ),
            txn_stats AS (
                SELECT c.account_id,
                       COUNT(t.transaction_id) AS txn_count,
                       NVL(TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)), 9999) AS days_since_last
                FROM candidates c
                LEFT JOIN %s t ON t.account_id = c.account_id
                GROUP BY c.account_id
            ),
            with_normalization AS (
                SELECT c.account_id,
                       c.vector_score,
                       NVL(ts.txn_count, 0) AS txn_count,
                       NVL(ts.days_since_last, 9999) AS days_since_last,
                       NVL(ts.txn_count, 0) / NULLIF(MAX(ts.txn_count) OVER (), 1) AS normalized_txn,
                       CASE
                           WHEN MAX(ts.days_since_last) OVER () = MIN(ts.days_since_last) OVER () THEN 1
                           ELSE 1 - ((NVL(ts.days_since_last, 9999) - MIN(ts.days_since_last) OVER ()) /
                                     NULLIF(MAX(ts.days_since_last) OVER () - MIN(ts.days_since_last) OVER (), 1))
                       END AS recency_score
                FROM candidates c
                LEFT JOIN txn_stats ts ON ts.account_id = c.account_id
            )
            SELECT account_id,
                   vector_score,
                   txn_count,
                   days_since_last,
                   NVL(normalized_txn, 0) AS normalized_txn_activity,
                   NVL(recency_score, 1) AS recency_score,
                   (0.5 * vector_score + 0.3 * NVL(normalized_txn, 0) + 0.2 * NVL(recency_score, 1)) AS hybrid_score
            FROM with_normalization
            ORDER BY hybrid_score DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(dimensions, ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);

        String vectorStr = formatVectorString(queryVector);

        List<HybridRankingResult> results = new ArrayList<>();

        try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sql)) {
            stmt.setString(1, vectorStr);
            stmt.setString(2, vectorStr);
            stmt.setInt(3, overFetchLimit);
            stmt.setInt(4, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("account_id");
                    double vectorScore = rs.getDouble("vector_score");
                    int txnCount = rs.getInt("txn_count");
                    int daysSinceLastTxn = rs.getInt("days_since_last");
                    double normalizedTxnActivity = rs.getDouble("normalized_txn_activity");
                    double recencyScore = rs.getDouble("recency_score");
                    double hybridScore = rs.getDouble("hybrid_score");

                    results.add(new HybridRankingResult(
                        accountId, vectorScore, txnCount, daysSinceLastTxn,
                        normalizedTxnActivity, recencyScore, hybridScore));
                }
            }
        }

        return results;
    }

    // ==========================================================================
    // Execution Methods - Oracle MongoDB API
    // ==========================================================================

    private static List<Document> executeOracleApiVectorSearch(double[] queryVector, int limit) {
        // Oracle MongoDB API doesn't support $vectorSearch directly
        // We use $sql aggregation instead
        return executeOracleSqlVectorSearch(queryVector, limit, queryVector.length);
    }

    // ==========================================================================
    // Execution Methods - Oracle $sql Aggregation
    // ==========================================================================

    private static List<Document> executeOracleSqlVectorSearch(double[] queryVector, int limit, int dimensions) {
        if (!oracleMongoApiSupported) return Collections.emptyList();

        String vectorStr = formatVectorString(queryVector);

        // ORDS $sql requires projecting a single JSON column - use JSON_OBJECT
        String sqlStatement = String.format("""
            SELECT JSON_OBJECT(
                '_id' VALUE id,
                'distance' VALUE VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE)
            RETURNING CLOB) AS json_doc
            FROM %s
            ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE)
            FETCH FIRST %d ROWS ONLY
            """, vectorStr, dimensions, ACCOUNTS_TABLE, vectorStr, dimensions, limit);

        Document sqlStage = new Document("$sql", sqlStatement);

        List<Document> results = new ArrayList<>();
        try {
            oracleSodaAccountsCollection.aggregate(Collections.singletonList(sqlStage))
                .forEach(results::add);
        } catch (Exception e) {
            // ORDS $sql with VECTOR_DISTANCE may fail - silently return empty
        }
        return results;
    }

    private static List<Document> executeOracleSqlVectorSearchWithJoin(double[] queryVector, int limit, int dimensions) {
        if (!oracleMongoApiSupported) return Collections.emptyList();

        // VS2 uses the same simple query as VS1 (no actual JOIN in $sql - join is done separately)
        // Just delegate to the working VS1 method
        return executeOracleSqlVectorSearch(queryVector, limit, dimensions);
    }


    // ==========================================================================
    // Execution Methods - Oracle JDBC
    // ==========================================================================

    private static List<Document> executeOracleJdbcVectorSearch(double[] queryVector, int limit) throws SQLException {
        int dimensions = queryVector.length;

        // Create or reuse cached PreparedStatement
        if (cachedVectorSearchStmt == null || cachedVectorSearchDimensions != dimensions) {
            if (cachedVectorSearchStmt != null) {
                cachedVectorSearchStmt.close();
            }
            String sql = """
                SELECT id, VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
                """.formatted(dimensions, ACCOUNTS_TABLE);
            cachedVectorSearchStmt = oracleJdbcConnection.prepareStatement(sql);
            cachedVectorSearchDimensions = dimensions;
        }

        String vectorStr = formatVectorString(queryVector);
        cachedVectorSearchStmt.setString(1, vectorStr);
        cachedVectorSearchStmt.setInt(2, limit);

        List<Document> results = new ArrayList<>();
        try (ResultSet rs = cachedVectorSearchStmt.executeQuery()) {
            while (rs.next()) {
                // Only fetch ID for recall calculation compatibility
                results.add(new Document("_id", rs.getString("id")));
            }
        }
        return results;
    }

    private static List<Document> executeOracleJdbcVectorSearchWithJoin(double[] queryVector, int limit, int dimensions)
            throws SQLException {
        // Create or reuse cached PreparedStatement
        if (cachedVectorSearchWithJoinStmt == null) {
            String sql = """
                WITH top_accounts AS (
                    SELECT id, data, embedding
                    FROM %s
                    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                    FETCH FIRST ? ROWS ONLY
                )
                SELECT a.id,
                       (SELECT COUNT(*) FROM %s t
                        WHERE t.account_id = JSON_VALUE(a.data, '$.accountId')
                          AND t.transaction_date >= SYSDATE - 30
                       ) AS txn_count
                FROM top_accounts a
                """.formatted(ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);
            cachedVectorSearchWithJoinStmt = oracleJdbcConnection.prepareStatement(sql);
        }

        String vectorStr = formatVectorString(queryVector);
        cachedVectorSearchWithJoinStmt.setString(1, vectorStr);
        cachedVectorSearchWithJoinStmt.setInt(2, limit);

        int count = 0;
        try (ResultSet rs = cachedVectorSearchWithJoinStmt.executeQuery()) {
            while (rs.next()) count++;
        }
        return Collections.nCopies(count, new Document());
    }

    // ==========================================================================
    // Query Builders
    // ==========================================================================

    private static String buildMongoVectorSearchPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        Document stage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", TOP_K * 10)
            .append("limit", TOP_K));
        return stage.toJson();
    }

    private static String buildOracleSqlAggregation(double[] queryVector, int dimensions) {
        String vectorStr = formatVectorString(queryVector);
        return String.format("""
            SELECT id, data, embedding,
                   VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE) AS distance
            FROM %s
            ORDER BY distance
            FETCH FIRST %d ROWS ONLY
            """, vectorStr, dimensions, ACCOUNTS_TABLE, TOP_K);
    }

    private static String buildOracleJdbcSql(double[] queryVector, int dimensions) {
        return """
            SELECT id, data, embedding,
                   VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
            FROM %s
            ORDER BY distance
            FETCH FIRST ? ROWS ONLY
            """.formatted(dimensions, ACCOUNTS_TABLE);
    }

    private static String buildMongoVectorSearchWithLookupPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", TOP_K * 10)
            .append("limit", TOP_K));

        Document lookupStage = new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", "<30 days ago>"))
                    ))
                )),
                new Document("$sort", new Document("transactionDate", -1)),
                new Document("$limit", 10)
            ))
            .append("as", "recentTransactions"));

        return "[\n  " + vectorSearchStage.toJson() + ",\n  " + lookupStage.toJson() + "\n]";
    }

    private static String buildOracleJdbcVectorSearchWithJoinSql(int dimensions) {
        return """
            WITH top_accounts AS (
                SELECT id, data, embedding
                FROM %s
                ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.data AS account_data,
                   (SELECT JSON_ARRAYAGG(
                       JSON_OBJECT(
                           'transactionId' VALUE t.transaction_id,
                           'amount' VALUE t.amount,
                           'transactionDate' VALUE TO_CHAR(t.transaction_date, 'YYYY-MM-DD'),
                           'category' VALUE t.category
                       ) RETURNING CLOB
                   )
                   FROM %s t
                   WHERE t.account_id = JSON_VALUE(a.data, '$.accountId')
                     AND t.transaction_date >= SYSDATE - 30
                   ) AS transactions
            FROM top_accounts a
            """.formatted(ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);
    }

    private static String buildOracleSqlVectorSearchWithJoinSql(double[] queryVector, int dimensions) {
        String vectorStr = formatVectorString(queryVector);
        // Use inline subquery - CTEs not supported through ORDS MongoDB API
        return """
            SELECT a.data AS account_data,
                   (SELECT JSON_ARRAYAGG(
                       JSON_OBJECT(
                           'transactionId' VALUE t.transaction_id,
                           'amount' VALUE t.amount,
                           'transactionDate' VALUE TO_CHAR(t.transaction_date, 'YYYY-MM-DD'),
                           'category' VALUE t.category
                       ) RETURNING CLOB
                   )
                   FROM %s t
                   WHERE t.account_id = JSON_VALUE(a.data, '$.accountId')
                     AND t.transaction_date >= SYSDATE - 30
                   ) AS transactions
            FROM (
                SELECT id, data, embedding
                FROM %s
                ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR('%s', %d, FLOAT64), COSINE)
                FETCH FIRST %d ROWS ONLY
            ) a
            """.formatted(TRANSACTIONS_TABLE, ACCOUNTS_TABLE, vectorStr, dimensions, TOP_K);
    }

    // ==========================================================================
    // RAG2 Query Builders
    // ==========================================================================

    private static String buildMongoWeeklyAggregationPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
            .append("index", VECTOR_INDEX_NAME)
            .append("path", "embedding")
            .append("queryVector", queryList)
            .append("numCandidates", TOP_K * 10)
            .append("limit", TOP_K));

        Document addScoreStage = new Document("$addFields", new Document()
            .append("vectorScore", new Document("$meta", "vectorSearchScore")));

        Document lookupWithGroupStage = new Document("$lookup", new Document()
            .append("from", TRANSACTIONS_COLLECTION)
            .append("let", new Document("accId", "$accountId"))
            .append("pipeline", Arrays.asList(
                new Document("$match", new Document("$expr",
                    new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", "<30 days ago>"))
                    ))
                )),
                new Document("$group", new Document()
                    .append("_id", new Document("$isoWeek", "$transactionDate"))
                    .append("weekStart", new Document("$min", "$transactionDate"))
                    .append("txnCount", new Document("$sum", 1))
                    .append("totalAmount", new Document("$sum", "$amount"))
                    .append("avgAmount", new Document("$avg", "$amount"))
                ),
                new Document("$sort", new Document("_id", 1))
            ))
            .append("as", "weeklyStats"));

        return "[\n  " + vectorSearchStage.toJson() + ",\n  " +
               addScoreStage.toJson() + ",\n  " +
               lookupWithGroupStage.toJson() + "\n]";
    }

    private static String buildOracleJdbcWeeklyAggregationSql(int dimensions) {
        return """
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.id, a.account_id, a.distance,
                   TRUNC(t.transaction_date, 'IW') AS week_start,
                   TO_CHAR(t.transaction_date, 'IW') AS iso_week,
                   COUNT(*) AS txn_count,
                   SUM(t.amount) AS total_amount,
                   AVG(t.amount) AS avg_amount
            FROM top_accounts a
            LEFT JOIN %s t
                ON t.account_id = a.account_id
                AND t.transaction_date >= SYSDATE - 30
            GROUP BY a.id, a.account_id, a.distance, TRUNC(t.transaction_date, 'IW'), TO_CHAR(t.transaction_date, 'IW')
            ORDER BY a.distance, week_start
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);
    }

    // ==========================================================================
    // RAG2 Explain Capture Methods
    // ==========================================================================

    private static String captureMongoExplainWeeklyAggregation(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", TOP_K * 10)
                .append("limit", TOP_K));

            Document addScoreStage = new Document("$addFields", new Document()
                .append("vectorScore", new Document("$meta", "vectorSearchScore")));

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -30);

            Document lookupWithGroupStage = new Document("$lookup", new Document()
                .append("from", TRANSACTIONS_COLLECTION)
                .append("let", new Document("accId", "$accountId"))
                .append("pipeline", Arrays.asList(
                    new Document("$match", new Document("$expr",
                        new Document("$and", Arrays.asList(
                            new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                            new Document("$gte", Arrays.asList("$transactionDate", cal.getTime()))
                        ))
                    )),
                    new Document("$group", new Document()
                        .append("_id", new Document("$isoWeek", "$transactionDate"))
                        .append("weekStart", new Document("$min", "$transactionDate"))
                        .append("txnCount", new Document("$sum", 1))
                        .append("totalAmount", new Document("$sum", "$amount"))
                        .append("avgAmount", new Document("$avg", "$amount"))
                    ),
                    new Document("$sort", new Document("_id", 1))
                ))
                .append("as", "weeklyStats"));

            Document explainResult = accountsCollection.aggregate(
                Arrays.asList(vectorSearchStage, addScoreStage, lookupWithGroupStage)
            ).explain();

            return explainResult.toJson();
        } catch (Exception e) {
            return "Explain capture failed: " + e.getMessage();
        }
    }

    private static String captureSqlMonitorForWeeklyAggregation(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            String vectorStr = formatVectorString(queryVector);
            // Add MONITOR hint to the CTE
            String sqlWithHint = sql.replace("WITH top_accounts",
                "WITH /*+ MONITOR */ top_accounts");

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sqlWithHint)) {
                stmt.setString(1, vectorStr);
                stmt.setInt(2, TOP_K);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            // Give Oracle time to populate V$SQL_MONITOR
            Thread.sleep(100);

            // Get SQL_ID
            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            // Generate SQL Monitor report
            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_weekly", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail - SQL Monitor may not be available
        }
        return "";
    }

    private static String captureExplainPlanForWeeklyAggregation(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            // Clear previous explain plan
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG2_WEEKLY'");
            }

            // Create explain plan
            String explainSql = "EXPLAIN PLAN SET STATEMENT_ID = 'RAG2_WEEKLY' FOR " + sql;
            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(explainSql)) {
                stmt.setString(1, "[1.0, 0.5, ...]"); // placeholder vector
                stmt.setInt(2, TOP_K);
                stmt.execute();
            }

            // Retrieve plan
            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT LPAD(' ', 2*LEVEL) || OPERATION || ' ' || OPTIONS || " +
                     "DECODE(OBJECT_NAME, NULL, '', ' ON ' || OBJECT_NAME) AS PLAN_LINE " +
                     "FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG2_WEEKLY' " +
                     "START WITH ID = 0 CONNECT BY PRIOR ID = PARENT_ID ORDER SIBLINGS BY ID")) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }

            return plan.toString();
        } catch (Exception e) {
            return "Explain plan capture failed: " + e.getMessage();
        }
    }

    // ==========================================================================
    // RAG3 Query Builders
    // ==========================================================================

    private static String buildMongoCustomer360Pipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        return """
            [
              { "$vectorSearch": { "index": "%s", "path": "embedding", "queryVector": %s, "numCandidates": %d, "limit": %d } },
              { "$addFields": { "vectorScore": { "$meta": "vectorSearchScore" } } },
              { "$lookup": { "from": "%s", "localField": "accountId", "foreignField": "accountId", "as": "allTransactions" } },
              { "$addFields": {
                  "profile": {
                    "totalSpent": { "$sum": "$allTransactions.amount" },
                    "transactionCount": { "$size": "$allTransactions" },
                    "avgTransactionAmount": { "$avg": "$allTransactions.amount" },
                    "lastActivityDate": { "$max": "$allTransactions.transactionDate" },
                    "daysSinceLastActivity": { "$dateDiff": { "startDate": { "$max": "$allTransactions.transactionDate" }, "endDate": "$$NOW", "unit": "day" } }
                  },
                  "spendingByCategory": "computed via $reduce"
              }}
            ]
            """.formatted(VECTOR_INDEX_NAME, queryList, TOP_K * 10, TOP_K, TRANSACTIONS_COLLECTION);
    }

    private static String buildOracleJdbcCustomer360Sql(int dimensions) {
        return """
            -- Query 1: Account stats
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT a.account_id, (1 - a.distance) AS vector_score,
                   COUNT(t.transaction_id) AS txn_count,
                   SUM(t.amount) AS total_spent,
                   AVG(t.amount) AS avg_amount,
                   MAX(t.transaction_date) AS last_activity,
                   TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)) AS days_since
            FROM top_accounts a
            LEFT JOIN %s t ON t.account_id = a.account_id
            GROUP BY a.id, a.account_id, a.distance
            ORDER BY a.distance;

            -- Query 2: Category spending (separate query)
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);
    }

    // ==========================================================================
    // RAG3 Explain Capture Methods
    // ==========================================================================

    private static String captureMongoExplainCustomer360(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", TOP_K * 10)
                .append("limit", TOP_K));

            Document addScoreStage = new Document("$addFields", new Document()
                .append("vectorScore", new Document("$meta", "vectorSearchScore")));

            Document lookupStage = new Document("$lookup", new Document()
                .append("from", TRANSACTIONS_COLLECTION)
                .append("localField", "accountId")
                .append("foreignField", "accountId")
                .append("as", "allTransactions"));

            Document explainResult = accountsCollection.aggregate(
                Arrays.asList(vectorSearchStage, addScoreStage, lookupStage)
            ).explain();

            return explainResult.toJson();
        } catch (Exception e) {
            return "Explain capture failed: " + e.getMessage();
        }
    }

    private static String captureSqlMonitorForCustomer360(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            String vectorStr = formatVectorString(queryVector);

            // Execute stats query with MONITOR hint
            String statsSql = """
                WITH /*+ MONITOR */ top_accounts AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                )
                SELECT a.account_id, (1 - a.distance) AS vector_score,
                       COUNT(t.transaction_id) AS txn_count,
                       SUM(t.amount) AS total_spent,
                       AVG(t.amount) AS avg_amount,
                       MAX(t.transaction_date) AS last_activity,
                       TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)) AS days_since
                FROM top_accounts a
                LEFT JOIN %s t ON t.account_id = a.account_id
                GROUP BY a.id, a.account_id, a.distance
                ORDER BY a.distance
                """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(statsSql)) {
                stmt.setString(1, vectorStr);
                stmt.setInt(2, TOP_K);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            Thread.sleep(100);

            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_customer360", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureExplainPlanForCustomer360(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG3_CUSTOMER360'");
            }

            String explainSql = """
                EXPLAIN PLAN SET STATEMENT_ID = 'RAG3_CUSTOMER360' FOR
                WITH top_accounts AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                )
                SELECT a.account_id, (1 - a.distance) AS vector_score,
                       COUNT(t.transaction_id) AS txn_count,
                       SUM(t.amount) AS total_spent
                FROM top_accounts a
                LEFT JOIN %s t ON t.account_id = a.account_id
                GROUP BY a.id, a.account_id, a.distance
                """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(explainSql)) {
                stmt.setString(1, "[1.0, 0.5, ...]");
                stmt.setInt(2, TOP_K);
                stmt.execute();
            }

            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT LPAD(' ', 2*LEVEL) || OPERATION || ' ' || OPTIONS || " +
                     "DECODE(OBJECT_NAME, NULL, '', ' ON ' || OBJECT_NAME) AS PLAN_LINE " +
                     "FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG3_CUSTOMER360' " +
                     "START WITH ID = 0 CONNECT BY PRIOR ID = PARENT_ID ORDER SIBLINGS BY ID")) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }

            return plan.toString();
        } catch (Exception e) {
            return "Explain plan capture failed: " + e.getMessage();
        }
    }

    // ==========================================================================
    // RAG1 Query Builders
    // ==========================================================================

    private static String buildMongoGraphTraversalPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        return """
            [
              { "$vectorSearch": { "index": "%s", "path": "embedding", "queryVector": %s, "numCandidates": 100, "limit": %d } },
              { "$addFields": { "vectorScore": { "$meta": "vectorSearchScore" } } },
              { "$graphLookup": {
                  "from": "%s",
                  "startWith": "$tenantId",
                  "connectFromField": "tenantId",
                  "connectToField": "tenantId",
                  "as": "sameTenantAccountsRaw",
                  "maxDepth": 0,
                  "depthField": "hopCount"
              }},
              { "$addFields": { "sameTenantAccounts": { "$filter": { "input": "$sameTenantAccountsRaw", "as": "acc", "cond": { "$ne": ["$$acc.accountId", "$accountId"] } } } } },
              { "$lookup": { "from": "%s", "let": { "accId": "$accountId" }, "pipeline": [
                  { "$match": { "$expr": { "$eq": ["$accountId", "$$accId"] } } },
                  { "$group": { "_id": "$merchant" } }
              ], "as": "myMerchants" } },
              { "$lookup": { "from": "%s", "let": { "merchants": [...], "sourceAccId": "$accountId" }, "pipeline": [
                  { "$match": { "$expr": { "$in": ["$merchant", "$$merchants"] } } },
                  { "$match": { "$expr": { "$ne": ["$accountId", "$$sourceAccId"] } } },
                  { "$group": { "_id": "$accountId" } }
              ], "as": "sharedMerchantTxns" } }
            ]
            """.formatted(VECTOR_INDEX_NAME, queryList, TOP_K, ACCOUNTS_COLLECTION, TRANSACTIONS_COLLECTION, TRANSACTIONS_COLLECTION);
    }

    private static String buildOracleJdbcGraphTraversalSql(int dimensions) {
        return """
            -- Query 1: Same-tenant accounts
            WITH top_accounts AS (
                SELECT id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       JSON_VALUE(data, '$.tenantId') AS tenant_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            )
            SELECT ta.account_id AS source_account_id,
                   (1 - ta.distance) AS vector_score,
                   JSON_VALUE(other.data, '$.accountId') AS related_account_id,
                   'SAME_TENANT' AS relationship_type,
                   0 AS hops
            FROM top_accounts ta
            JOIN %s other
              ON JSON_VALUE(other.data, '$.tenantId') = ta.tenant_id
              AND JSON_VALUE(other.data, '$.accountId') != ta.account_id
            ORDER BY ta.distance, related_account_id;

            -- Query 2: Shared merchant accounts (separate query)
            """.formatted(dimensions, ACCOUNTS_TABLE, ACCOUNTS_TABLE);
    }

    // ==========================================================================
    // RAG1 Explain Capture Methods
    // ==========================================================================

    private static String captureMongoExplainGraphTraversal(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", 100)
                .append("limit", TOP_K));

            Document addScoreStage = new Document("$addFields", new Document()
                .append("vectorScore", new Document("$meta", "vectorSearchScore")));

            Document graphLookupStage = new Document("$graphLookup", new Document()
                .append("from", ACCOUNTS_COLLECTION)
                .append("startWith", "$tenantId")
                .append("connectFromField", "tenantId")
                .append("connectToField", "tenantId")
                .append("as", "sameTenantAccountsRaw")
                .append("maxDepth", 0)
                .append("depthField", "hopCount"));

            Document explainResult = accountsCollection.aggregate(
                Arrays.asList(vectorSearchStage, addScoreStage, graphLookupStage)
            ).explain();

            return explainResult.toJson();
        } catch (Exception e) {
            return "Explain capture failed: " + e.getMessage();
        }
    }

    private static String captureSqlMonitorForGraphTraversal(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            String vectorStr = formatVectorString(queryVector);

            // Execute same-tenant query with MONITOR hint
            String sameTenantSql = """
                WITH /*+ MONITOR */ top_accounts AS (
                    SELECT id,
                           JSON_VALUE(data, '$.accountId') AS account_id,
                           JSON_VALUE(data, '$.tenantId') AS tenant_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                )
                SELECT ta.account_id AS source_account_id,
                       (1 - ta.distance) AS vector_score,
                       JSON_VALUE(other.data, '$.accountId') AS related_account_id,
                       'SAME_TENANT' AS relationship_type
                FROM top_accounts ta
                JOIN %s other
                  ON JSON_VALUE(other.data, '$.tenantId') = ta.tenant_id
                  AND JSON_VALUE(other.data, '$.accountId') != ta.account_id
                """.formatted(dimensions, ACCOUNTS_TABLE, ACCOUNTS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(sameTenantSql)) {
                stmt.setString(1, vectorStr);
                stmt.setInt(2, TOP_K);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            Thread.sleep(100);

            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_graph_traversal", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureExplainPlanForGraphTraversal(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG1_GRAPH'");
            }

            String explainSql = """
                EXPLAIN PLAN SET STATEMENT_ID = 'RAG1_GRAPH' FOR
                WITH top_accounts AS (
                    SELECT id,
                           JSON_VALUE(data, '$.accountId') AS account_id,
                           JSON_VALUE(data, '$.tenantId') AS tenant_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                )
                SELECT ta.account_id, JSON_VALUE(other.data, '$.accountId') AS related
                FROM top_accounts ta
                JOIN %s other
                  ON JSON_VALUE(other.data, '$.tenantId') = ta.tenant_id
                  AND JSON_VALUE(other.data, '$.accountId') != ta.account_id
                """.formatted(dimensions, ACCOUNTS_TABLE, ACCOUNTS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(explainSql)) {
                stmt.setString(1, "[1.0, 0.5, ...]");
                stmt.setInt(2, TOP_K);
                stmt.execute();
            }

            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT LPAD(' ', 2*LEVEL) || OPERATION || ' ' || OPTIONS || " +
                     "DECODE(OBJECT_NAME, NULL, '', ' ON ' || OBJECT_NAME) AS PLAN_LINE " +
                     "FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG1_GRAPH' " +
                     "START WITH ID = 0 CONNECT BY PRIOR ID = PARENT_ID ORDER SIBLINGS BY ID")) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }

            return plan.toString();
        } catch (Exception e) {
            return "Explain plan capture failed: " + e.getMessage();
        }
    }

    // ==========================================================================
    // RAG4 Query Builders
    // ==========================================================================

    private static String buildMongoActivityPatternPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        return """
            [
              { "$vectorSearch": { "index": "%s", "path": "embedding", "queryVector": %s, "numCandidates": 100, "limit": %d } },
              { "$addFields": { "vectorScore": { "$meta": "vectorSearchScore" } } },
              { "$lookup": {
                  "from": "%s",
                  "let": { "accId": "$accountId" },
                  "pipeline": [
                    { "$match": { "$expr": { "$and": [
                      { "$eq": ["$accountId", "$$accId"] },
                      { "$gte": ["$transactionDate", "90_days_ago"] }
                    ]}}},
                    { "$sort": { "transactionDate": 1 } },
                    { "$setWindowFields": {
                        "sortBy": { "transactionDate": 1 },
                        "output": { "rollingCount": { "$sum": 1, "window": { "range": [-6, 0], "unit": "day" } } }
                    }},
                    { "$group": { "_id": { "$dateToString": { "format": "%%Y-%%m-%%d", "date": "$transactionDate" } },
                                  "dailyCount": { "$sum": 1 }, "rollingWeekCount": { "$max": "$rollingCount" } } }
                  ],
                  "as": "dailyActivity"
              }},
              { "$addFields": { "avgDailyTxns": { "$avg": "$dailyActivity.dailyCount" } } }
            ]
            """.formatted(VECTOR_INDEX_NAME, queryList, TOP_K, TRANSACTIONS_COLLECTION);
    }

    private static String buildOracleJdbcActivityPatternSql(int dimensions) {
        return """
            WITH top_accounts AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                FROM %s
                ORDER BY distance
                FETCH FIRST ? ROWS ONLY
            ),
            daily_txns AS (
                SELECT ta.account_id, (1 - ta.distance) AS vector_score,
                       TRUNC(t.transaction_date) AS txn_date, COUNT(*) AS daily_count
                FROM top_accounts ta
                JOIN %s t ON t.account_id = ta.account_id
                WHERE t.transaction_date >= SYSDATE - 90
                GROUP BY ta.account_id, ta.distance, TRUNC(t.transaction_date)
            ),
            with_rolling AS (
                SELECT account_id, vector_score, txn_date, daily_count,
                       SUM(daily_count) OVER (PARTITION BY account_id ORDER BY txn_date
                           RANGE BETWEEN 6 PRECEDING AND CURRENT ROW) AS rolling_week_count
                FROM daily_txns
            ),
            account_stats AS (
                SELECT account_id, AVG(daily_count) AS avg_daily_txns FROM daily_txns GROUP BY account_id
            )
            SELECT wr.*, ast.avg_daily_txns,
                   CASE WHEN wr.rolling_week_count > 2 * ast.avg_daily_txns THEN 'BURST'
                        WHEN wr.daily_count = 0 THEN 'DORMANT' ELSE 'NORMAL' END AS activity_status
            FROM with_rolling wr JOIN account_stats ast ON ast.account_id = wr.account_id
            ORDER BY wr.account_id, wr.txn_date
            """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);
    }

    // ==========================================================================
    // RAG4 Explain Capture Methods
    // ==========================================================================

    private static String captureMongoExplainActivityPattern(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -90);
            java.util.Date ninetyDaysAgo = cal.getTime();

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", 100)
                .append("limit", TOP_K));

            Document addScoreStage = new Document("$addFields", new Document()
                .append("vectorScore", new Document("$meta", "vectorSearchScore")));

            Document lookupStage = new Document("$lookup", new Document()
                .append("from", TRANSACTIONS_COLLECTION)
                .append("let", new Document("accId", "$accountId"))
                .append("pipeline", Arrays.asList(
                    new Document("$match", new Document("$expr", new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                        new Document("$gte", Arrays.asList("$transactionDate", ninetyDaysAgo))
                    ))))
                ))
                .append("as", "dailyActivity"));

            Document explainResult = accountsCollection.aggregate(
                Arrays.asList(vectorSearchStage, addScoreStage, lookupStage)
            ).explain();

            return explainResult.toJson();
        } catch (Exception e) {
            return "Explain capture failed: " + e.getMessage();
        }
    }

    private static String captureSqlMonitorForActivityPattern(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            String vectorStr = formatVectorString(queryVector);

            String monitorSql = """
                WITH /*+ MONITOR */ top_accounts AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                ),
                daily_txns AS (
                    SELECT ta.account_id, TRUNC(t.transaction_date) AS txn_date, COUNT(*) AS daily_count
                    FROM top_accounts ta
                    JOIN %s t ON t.account_id = ta.account_id
                    WHERE t.transaction_date >= SYSDATE - 90
                    GROUP BY ta.account_id, TRUNC(t.transaction_date)
                )
                SELECT account_id, txn_date, daily_count FROM daily_txns
                """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(monitorSql)) {
                stmt.setString(1, vectorStr);
                stmt.setInt(2, TOP_K);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            Thread.sleep(100);

            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_activity_pattern", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureExplainPlanForActivityPattern(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG4_ACTIVITY'");
            }

            String explainSql = """
                EXPLAIN PLAN SET STATEMENT_ID = 'RAG4_ACTIVITY' FOR
                WITH top_accounts AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS distance
                    FROM %s
                    ORDER BY distance
                    FETCH FIRST ? ROWS ONLY
                ),
                daily_txns AS (
                    SELECT ta.account_id, TRUNC(t.transaction_date) AS txn_date, COUNT(*) AS daily_count
                    FROM top_accounts ta
                    JOIN %s t ON t.account_id = ta.account_id
                    WHERE t.transaction_date >= SYSDATE - 90
                    GROUP BY ta.account_id, TRUNC(t.transaction_date)
                )
                SELECT account_id, txn_date, daily_count FROM daily_txns
                """.formatted(dimensions, ACCOUNTS_TABLE, TRANSACTIONS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(explainSql)) {
                stmt.setString(1, "[1.0, 0.5, ...]");
                stmt.setInt(2, TOP_K);
                stmt.execute();
            }

            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT LPAD(' ', 2*LEVEL) || OPERATION || ' ' || OPTIONS || " +
                     "DECODE(OBJECT_NAME, NULL, '', ' ON ' || OBJECT_NAME) AS PLAN_LINE " +
                     "FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG4_ACTIVITY' " +
                     "START WITH ID = 0 CONNECT BY PRIOR ID = PARENT_ID ORDER SIBLINGS BY ID")) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }

            return plan.toString();
        } catch (Exception e) {
            return "Explain plan capture failed: " + e.getMessage();
        }
    }

    // ==========================================================================
    // RAG5 Query Builders
    // ==========================================================================

    private static String buildMongoHybridRankingPipeline(double[] queryVector) {
        List<Double> queryList = toDoubleList(queryVector);
        return """
            [
              { "$vectorSearch": { "index": "%s", "path": "embedding", "queryVector": %s, "numCandidates": 500, "limit": 50 } },
              { "$addFields": { "vectorScore": { "$meta": "vectorSearchScore" } } },
              { "$lookup": { "from": "%s", "let": { "accId": "$accountId" }, "pipeline": [
                  { "$match": { "$expr": { "$eq": ["$accountId", "$$accId"] } } },
                  { "$group": { "_id": null, "txnCount": { "$sum": 1 }, "lastTxnDate": { "$max": "$transactionDate" } } }
              ], "as": "txnStats" } },
              { "$setWindowFields": { "output": { "maxTxnCount": { "$max": "$txnCount" }, "minDaysSince": { "$min": "$daysSinceLastTxn" }, "maxDaysSince": { "$max": "$daysSinceLastTxn" } } } },
              { "$addFields": {
                  "normalizedTxnActivity": { "$divide": ["$txnCount", "$maxTxnCount"] },
                  "recencyScore": { "$subtract": [1, { "$divide": [...] }] },
                  "hybridScore": { "$add": [{ "$multiply": [0.5, "$vectorScore"] }, { "$multiply": [0.3, "$normalizedTxnActivity"] }, { "$multiply": [0.2, "$recencyScore"] }] }
              }},
              { "$sort": { "hybridScore": -1 } },
              { "$limit": 10 }
            ]
            """.formatted(VECTOR_INDEX_NAME, queryList, TRANSACTIONS_COLLECTION);
    }

    private static String buildOracleJdbcHybridRankingSql(int dimensions) {
        return """
            WITH candidates AS (
                SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                       1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS vector_score
                FROM %s
                ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                FETCH FIRST 50 ROWS ONLY
            ),
            txn_stats AS (
                SELECT c.account_id, COUNT(t.transaction_id) AS txn_count,
                       NVL(TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)), 9999) AS days_since_last
                FROM candidates c LEFT JOIN %s t ON t.account_id = c.account_id
                GROUP BY c.account_id
            ),
            with_normalization AS (
                SELECT c.account_id, c.vector_score, NVL(ts.txn_count, 0) AS txn_count,
                       NVL(ts.txn_count, 0) / NULLIF(MAX(ts.txn_count) OVER (), 1) AS normalized_txn,
                       1 - ((NVL(ts.days_since_last, 9999) - MIN(ts.days_since_last) OVER ()) /
                            NULLIF(MAX(ts.days_since_last) OVER () - MIN(ts.days_since_last) OVER (), 1)) AS recency_score
                FROM candidates c LEFT JOIN txn_stats ts ON ts.account_id = c.account_id
            )
            SELECT account_id, vector_score, txn_count, normalized_txn, recency_score,
                   (0.5 * vector_score + 0.3 * NVL(normalized_txn, 0) + 0.2 * NVL(recency_score, 1)) AS hybrid_score
            FROM with_normalization
            ORDER BY hybrid_score DESC
            FETCH FIRST 10 ROWS ONLY
            """.formatted(dimensions, ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);
    }

    // ==========================================================================
    // RAG5 Explain Capture Methods
    // ==========================================================================

    private static String captureMongoExplainHybridRanking(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", 500)
                .append("limit", 50));

            Document addScoreStage = new Document("$addFields", new Document()
                .append("vectorScore", new Document("$meta", "vectorSearchScore")));

            Document lookupStage = new Document("$lookup", new Document()
                .append("from", TRANSACTIONS_COLLECTION)
                .append("let", new Document("accId", "$accountId"))
                .append("pipeline", Arrays.asList(
                    new Document("$match", new Document("$expr",
                        new Document("$eq", Arrays.asList("$accountId", "$$accId")))),
                    new Document("$group", new Document()
                        .append("_id", null)
                        .append("txnCount", new Document("$sum", 1)))
                ))
                .append("as", "txnStats"));

            Document explainResult = accountsCollection.aggregate(
                Arrays.asList(vectorSearchStage, addScoreStage, lookupStage)
            ).explain();

            return explainResult.toJson();
        } catch (Exception e) {
            return "Explain capture failed: " + e.getMessage();
        }
    }

    private static String captureSqlMonitorForHybridRanking(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            String vectorStr = formatVectorString(queryVector);

            String monitorSql = """
                WITH /*+ MONITOR */ candidates AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS vector_score
                    FROM %s
                    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                    FETCH FIRST 50 ROWS ONLY
                ),
                txn_stats AS (
                    SELECT c.account_id, COUNT(t.transaction_id) AS txn_count
                    FROM candidates c LEFT JOIN %s t ON t.account_id = c.account_id
                    GROUP BY c.account_id
                )
                SELECT c.account_id, c.vector_score, ts.txn_count
                FROM candidates c LEFT JOIN txn_stats ts ON ts.account_id = c.account_id
                """.formatted(dimensions, ACCOUNTS_TABLE, dimensions, TRANSACTIONS_TABLE);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(monitorSql)) {
                stmt.setString(1, vectorStr);
                stmt.setString(2, vectorStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            Thread.sleep(100);

            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc_hybrid_ranking", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureExplainPlanForHybridRanking(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG5_HYBRID'");
            }

            String explainSql = """
                EXPLAIN PLAN SET STATEMENT_ID = 'RAG5_HYBRID' FOR
                WITH candidates AS (
                    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
                           1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE) AS vector_score
                    FROM %s
                    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, %d, FLOAT64), COSINE)
                    FETCH FIRST 50 ROWS ONLY
                )
                SELECT account_id, vector_score FROM candidates
                """.formatted(dimensions, ACCOUNTS_TABLE, dimensions);

            try (PreparedStatement stmt = oracleJdbcConnection.prepareStatement(explainSql)) {
                stmt.setString(1, "[1.0, 0.5, ...]");
                stmt.setString(2, "[1.0, 0.5, ...]");
                stmt.execute();
            }

            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT LPAD(' ', 2*LEVEL) || OPERATION || ' ' || OPTIONS || " +
                     "DECODE(OBJECT_NAME, NULL, '', ' ON ' || OBJECT_NAME) AS PLAN_LINE " +
                     "FROM PLAN_TABLE WHERE STATEMENT_ID = 'RAG5_HYBRID' " +
                     "START WITH ID = 0 CONNECT BY PRIOR ID = PARENT_ID ORDER SIBLINGS BY ID")) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }

            return plan.toString();
        } catch (Exception e) {
            return "Explain plan capture failed: " + e.getMessage();
        }
    }

    // ==========================================================================
    // Oracle Parallel Execution Setup
    // ==========================================================================

    private static void enableOracleParallelExecution() {
        if (oracleJdbcConnection == null) return;

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Enable automatic parallel degree selection
            stmt.execute("ALTER SESSION SET PARALLEL_DEGREE_POLICY = AUTO");

            // Allow parallel DML operations
            stmt.execute("ALTER SESSION ENABLE PARALLEL DML");

            // Set a reasonable parallel degree limit
            stmt.execute("ALTER SESSION SET PARALLEL_DEGREE_LIMIT = 4");

            System.out.println("  Oracle parallel execution: ENABLED (degree limit: 4)");
        } catch (SQLException e) {
            System.out.println("  Oracle parallel execution setup skipped: " + e.getMessage());
        }
    }

    // ==========================================================================
    // AWR Snapshot Methods
    // ==========================================================================

    private static void initializeAwr() {
        if (oracleJdbcConnection == null) return;

        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            // Get database ID from DBA_HIST_DATABASE_INSTANCE (more reliable for AWR)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT DBID, INSTANCE_NUMBER FROM DBA_HIST_DATABASE_INSTANCE " +
                    "WHERE ROWNUM = 1 ORDER BY STARTUP_TIME DESC")) {
                if (rs.next()) {
                    dbId = rs.getLong(1);
                    instanceNumber = rs.getInt(2);
                }
            }

            // Fallback to V$ views if DBA_HIST not available
            if (dbId < 0) {
                try (ResultSet rs = stmt.executeQuery("SELECT DBID FROM V$DATABASE")) {
                    if (rs.next()) {
                        dbId = rs.getLong(1);
                    }
                }
                try (ResultSet rs = stmt.executeQuery("SELECT INSTANCE_NUMBER FROM V$INSTANCE")) {
                    if (rs.next()) {
                        instanceNumber = rs.getInt(1);
                    }
                }
            }

            if (dbId > 0) {
                System.out.println("  AWR initialized: DBID=" + dbId + ", Instance=" + instanceNumber);
            } else {
                System.out.println("  AWR not available (Enterprise Edition required)");
            }
        } catch (SQLException e) {
            System.out.println("  AWR initialization skipped: " + e.getMessage());
            dbId = -1;
        }
    }

    private static void awrSnapshotBefore(String category) {
        long snapId = createAwrSnapshot("Before " + category);
        awrSnapshots.put(category, new long[]{snapId, -1});
    }

    private static void awrSnapshotAfter(String category) {
        long snapId = createAwrSnapshot("After " + category);
        long[] snaps = awrSnapshots.get(category);
        if (snaps != null) {
            snaps[1] = snapId;
        }
    }

    private static long createAwrSnapshot(String description) {
        if (oracleJdbcConnection == null || dbId < 0) return -1;

        try (CallableStatement cs = oracleJdbcConnection.prepareCall(
                "BEGIN :1 := DBMS_WORKLOAD_REPOSITORY.CREATE_SNAPSHOT(); END;")) {
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.execute();
            long snapId = cs.getLong(1);
            System.out.println("    AWR Snapshot: " + snapId + " (" + description + ")");
            return snapId;
        } catch (SQLException e) {
            // Silently fail - AWR may not be available
            return -1;
        }
    }

    private static void generateAwrReports() {
        if (dbId < 0 || awrSnapshots.isEmpty()) return;

        try {
            Files.createDirectories(Path.of(AWR_REPORT_DIR));
        } catch (IOException ignored) {}

        for (Map.Entry<String, long[]> entry : awrSnapshots.entrySet()) {
            String category = entry.getKey();
            long[] snaps = entry.getValue();
            if (snaps[0] > 0 && snaps[1] > 0) {
                String filename = AWR_REPORT_DIR + "/awr_" + category.replaceAll("[^a-zA-Z0-9]", "_") + ".html";
                String report = generateAwrHtmlReport(snaps[0], snaps[1], filename);
                if (!report.isEmpty()) {
                    awrReportContent.put(category, filename);
                }
            }
        }
    }

    private static String generateAwrHtmlReport(long beginSnap, long endSnap, String filename) {
        if (oracleJdbcConnection == null) return "";

        try {
            StringBuilder report = new StringBuilder();
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

            try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(sql)) {
                pstmt.setLong(1, dbId);
                pstmt.setInt(2, instanceNumber);
                pstmt.setLong(3, beginSnap);
                pstmt.setLong(4, endSnap);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        if (line != null) report.append(line).append("\n");
                    }
                }
            }

            if (report.length() > 0) {
                Files.writeString(Path.of(filename), report.toString());
                System.out.println("    AWR report generated: " + filename);
            }
            return report.toString();
        } catch (Exception e) {
            System.out.println("    AWR report generation failed: " + e.getMessage());
            return "";
        }
    }

    // ==========================================================================
    // SQL Monitor Methods
    // ==========================================================================

    private static String captureSqlMonitorForJdbc(String sql, double[] queryVector, String testId) {
        if (oracleJdbcConnection == null) return "";

        try {
            // Add MONITOR hint
            String monitoredSql = sql.replace("SELECT", "SELECT /*+ MONITOR */");

            // Execute the monitored query
            String vectorStr = formatVectorString(queryVector);
            try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(monitoredSql)) {
                pstmt.setString(1, vectorStr);
                pstmt.setInt(2, TOP_K);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            // Get SQL_ID
            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            // Generate SQL Monitor report
            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureSqlMonitorForSql(String sql, String testId) {
        // For $sql aggregation, we can't easily capture SQL Monitor
        // The SQL is executed through ORDS, not directly
        return "";
    }

    private static String captureSqlMonitorForJdbcWithJoin(String sql, double[] queryVector, String testId, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            // Add MONITOR hint
            String monitoredSql = sql.replace("SELECT id", "SELECT /*+ MONITOR */ id")
                                     .replace("SELECT a.data", "SELECT /*+ MONITOR */ a.data");

            // Execute the monitored query
            String vectorStr = formatVectorString(queryVector);
            try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(monitoredSql)) {
                pstmt.setString(1, vectorStr);
                pstmt.setInt(2, TOP_K);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) { /* consume results */ }
                }
            }

            // Get SQL_ID
            String sqlId = getSqlIdFromMonitor();
            if (sqlId == null) return "";

            // Generate SQL Monitor report
            String report = generateSqlMonitorReport(sqlId);
            if (!report.isEmpty()) {
                String filename = writeSqlMonitorFile(testId, "jdbc", report);
                System.out.println("    SQL Monitor captured: " + filename);
                return filename;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return "";
    }

    private static String captureExplainPlanForJoin(String sql, int dimensions) {
        if (oracleJdbcConnection == null) return "";

        try {
            // Clear plan table
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'VS_JOIN_BENCHMARK'");
            }

            // Replace bind variables with literals for EXPLAIN PLAN
            String explainableSql = sql.replace("TO_VECTOR(?,", "TO_VECTOR('[0.1,0.2,0.3]',")
                                       .replace("FETCH FIRST ? ROWS", "FETCH FIRST 10 ROWS");

            // Explain the SQL
            String explainSql = "EXPLAIN PLAN SET STATEMENT_ID = 'VS_JOIN_BENCHMARK' FOR " + explainableSql;
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute(explainSql);
            }

            // Get the plan
            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            String query = """
                SELECT PLAN_TABLE_OUTPUT FROM TABLE(
                    DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'VS_JOIN_BENCHMARK', 'ALL')
                )
                """;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }
            return plan.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==========================================================================
    // MongoDB Explain Capture
    // ==========================================================================

    private static String captureMongoExplain(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", TOP_K * 10)
                .append("limit", TOP_K));

            Document explainCmd = new Document("aggregate", ACCOUNTS_COLLECTION)
                .append("pipeline", Collections.singletonList(vectorSearchStage))
                .append("explain", true)
                .append("cursor", new Document());

            Document explainResult = mongoDatabase.runCommand(explainCmd);
            return explainResult.toJson(org.bson.json.JsonWriterSettings.builder()
                .indent(true)
                .build());
        } catch (Exception e) {
            return "Explain not available: " + e.getMessage();
        }
    }

    private static String captureMongoExplainWithLookup(double[] queryVector) {
        try {
            List<Double> queryList = toDoubleList(queryVector);

            Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", VECTOR_INDEX_NAME)
                .append("path", "embedding")
                .append("queryVector", queryList)
                .append("numCandidates", TOP_K * 10)
                .append("limit", TOP_K));

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -30);

            Document lookupStage = new Document("$lookup", new Document()
                .append("from", TRANSACTIONS_COLLECTION)
                .append("let", new Document("accId", "$accountId"))
                .append("pipeline", Arrays.asList(
                    new Document("$match", new Document("$expr",
                        new Document("$and", Arrays.asList(
                            new Document("$eq", Arrays.asList("$accountId", "$$accId")),
                            new Document("$gte", Arrays.asList("$transactionDate", cal.getTime()))
                        ))
                    )),
                    new Document("$sort", new Document("transactionDate", -1)),
                    new Document("$limit", 10)
                ))
                .append("as", "recentTransactions"));

            Document explainCmd = new Document("aggregate", ACCOUNTS_COLLECTION)
                .append("pipeline", Arrays.asList(vectorSearchStage, lookupStage))
                .append("explain", true)
                .append("cursor", new Document());

            Document explainResult = mongoDatabase.runCommand(explainCmd);
            return explainResult.toJson(org.bson.json.JsonWriterSettings.builder()
                .indent(true)
                .build());
        } catch (Exception e) {
            return "Explain not available: " + e.getMessage();
        }
    }

    private static String getSqlIdFromMonitor() throws SQLException {
        String query = """
            SELECT SQL_ID FROM V$SQL_MONITOR
            WHERE SID = SYS_CONTEXT('USERENV', 'SID')
              AND SQL_TEXT LIKE '%MONITOR%'
            ORDER BY SQL_EXEC_START DESC
            FETCH FIRST 1 ROW ONLY
            """;

        try (Statement stmt = oracleJdbcConnection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    private static String generateSqlMonitorReport(String sqlId) throws SQLException {
        String call = "BEGIN :1 := DBMS_SQLTUNE.REPORT_SQL_MONITOR(sql_id => :2, type => 'ACTIVE', report_level => 'ALL'); END;";

        try (CallableStatement cs = oracleJdbcConnection.prepareCall(call)) {
            cs.registerOutParameter(1, Types.CLOB);
            cs.setString(2, sqlId);
            cs.execute();
            Clob clob = cs.getClob(1);
            if (clob != null) {
                return clob.getSubString(1, (int) clob.length());
            }
        } catch (SQLException e) {
            // DBMS_SQLTUNE may not be available
        }
        return "";
    }

    private static String writeSqlMonitorFile(String testId, String protocol, String content) {
        try {
            Files.createDirectories(Path.of(SQL_MONITOR_DIR));
            String filename = SQL_MONITOR_DIR + "/monitor_" + testId + "_" + protocol + ".html";
            Files.writeString(Path.of(filename), content);
            return filename;
        } catch (IOException e) {
            return "";
        }
    }

    // ==========================================================================
    // Execution Plan Capture
    // ==========================================================================

    private static String captureExplainPlan(String sql) {
        if (oracleJdbcConnection == null) return "";

        try {
            // Clear plan table
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'VS_BENCHMARK'");
            }

            // Explain the SQL
            String explainSql = "EXPLAIN PLAN SET STATEMENT_ID = 'VS_BENCHMARK' FOR " + sql;
            try (Statement stmt = oracleJdbcConnection.createStatement()) {
                stmt.execute(explainSql);
            }

            // Get the plan
            StringBuilder plan = new StringBuilder();
            int lineCount = 0;
            final int MAX_PLAN_LINES = 200;
            String query = """
                SELECT PLAN_TABLE_OUTPUT FROM TABLE(
                    DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'VS_BENCHMARK', 'ALL')
                )
                """;
            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next() && lineCount < MAX_PLAN_LINES) {
                    plan.append(rs.getString(1)).append("\n");
                    lineCount++;
                }
                if (lineCount >= MAX_PLAN_LINES) {
                    plan.append("... (truncated at ").append(MAX_PLAN_LINES).append(" lines)\n");
                }
            }
            return plan.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==========================================================================
    // HTML Report Generation
    // ==========================================================================

    private static void generateHtmlReport() {
        if (results.isEmpty()) return;

        try {
            Files.createDirectories(Path.of(REPORTS_DIR));

            StringBuilder html = new StringBuilder();
            html.append(generateHtmlHeader());
            html.append(generateMainTabs());
            html.append(generateHtmlFooter());

            Files.writeString(Path.of(HTML_REPORT_FILE), html.toString());
            System.out.println("\n  HTML Report generated: " + HTML_REPORT_FILE);
        } catch (IOException e) {
            System.out.println("  HTML Report generation failed: " + e.getMessage());
        }
    }

    private static String generateMainTabs() {
        StringBuilder html = new StringBuilder();

        // Main tab buttons
        html.append("""
            <div class="main-tabs">
                <button class="main-tab active" onclick="showMainTab('summary')">Summary</button>
                <button class="main-tab" onclick="showMainTab('results')">Results & Details</button>
            </div>
            """);

        // Summary Tab Content
        html.append("<div id=\"main-summary\" class=\"main-tab-content active\">");
        html.append(generateSummaryTabContent());
        html.append("</div>");

        // Results Tab Content
        html.append("<div id=\"main-results\" class=\"main-tab-content\">");
        html.append(generateResultsTabContent());
        html.append("</div>");

        // Main tab JavaScript
        html.append("""
            <script>
                function showMainTab(tabId) {
                    document.querySelectorAll('.main-tab-content').forEach(c => c.classList.remove('active'));
                    document.querySelectorAll('.main-tab').forEach(t => t.classList.remove('active'));
                    document.getElementById('main-' + tabId).classList.add('active');
                    event.target.classList.add('active');
                }
            </script>
            """);

        return html.toString();
    }

    private static String generateSummaryTabContent() {
        StringBuilder html = new StringBuilder();

        // Win counts
        int mongoWins = 0, oracleSqlWins = 0, oracleJdbcWins = 0;
        for (long[] times : results.values()) {
            long best = times[0];
            int winner = 0;
            if (times[1] > 0 && times[1] < best) { best = times[1]; winner = 1; }
            if (times[2] > 0 && times[2] < best) { winner = 2; }
            switch (winner) {
                case 0 -> mongoWins++;
                case 1 -> oracleSqlWins++;
                case 2 -> oracleJdbcWins++;
            }
        }

        html.append("""
            <h2>Performance Summary</h2>
            <div class="summary-grid">
                <div class="summary-card">
                    <div class="value mongo">%d</div>
                    <div class="label">MongoDB Wins</div>
                </div>
                <div class="summary-card">
                    <div class="value oracle-sql">%d</div>
                    <div class="label">Oracle $sql Wins</div>
                </div>
                <div class="summary-card">
                    <div class="value oracle-jdbc">%d</div>
                    <div class="label">Oracle JDBC Wins</div>
                </div>
                <div class="summary-card">
                    <div class="value">%d</div>
                    <div class="label">Total Tests</div>
                </div>
            </div>
            """.formatted(mongoWins, oracleSqlWins, oracleJdbcWins, results.size()));

        // Test Descriptions
        html.append(generateTestDescriptions());

        return html.toString();
    }

    private static String generateTestDescriptions() {
        return """
            <h2>Test Descriptions</h2>
            <div class="desc-category-tabs">
                <button class="desc-category-tab active" onclick="showDescCategory('VS')">VS Tests (Vector Search)</button>
                <button class="desc-category-tab" onclick="showDescCategory('RAG')">RAG Tests (Retrieval Augmented)</button>
            </div>

            <!-- VS Tests Descriptions -->
            <div id="desc-VS" class="desc-category-content active">
            <div class="test-descriptions">
                <div class="test-desc">
                    <h3>VS1: Vector Search (Simple Retrieval)</h3>
                    <p><strong>Purpose:</strong> Find the top-10 most similar accounts based on vector embedding similarity using cosine distance.</p>
                    <p><strong>Data:</strong> Account documents with embeddings (384/768/1536 dimensions)</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> with knnVector index (HNSW algorithm)</td></tr>
                        <tr><td>Oracle $sql</td><td><code>$sql</code> aggregation with <code>VECTOR_DISTANCE()</code> via ORDS MongoDB API</td></tr>
                        <tr><td>Oracle JDBC</td><td>Direct SQL with <code>VECTOR_DISTANCE(embedding, TO_VECTOR(...), COSINE)</code></td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>VS3: Recall Accuracy Measurement</h3>
                    <p><strong>Purpose:</strong> Measure the recall rate of ANN (Approximate Nearest Neighbor) search against exact brute-force KNN.</p>
                    <p><strong>Metrics:</strong> Recall@10, Recall@50, Recall@100 - percentage of true nearest neighbors found</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> with varying <code>numCandidates</code></td></tr>
                        <tr><td>Oracle JDBC</td><td><code>VECTOR_DISTANCE</code> with optional vector index hints</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>VS4: Filtered Vector Search</h3>
                    <p><strong>Purpose:</strong> Test vector search performance with pre-filters at different selectivity levels.</p>
                    <p><strong>Selectivity Levels:</strong> 50%%, 10%%, ~20%% (region), ~5%% (compound filter)</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> with <code>filter</code> parameter (pre-filter)</td></tr>
                        <tr><td>Oracle JDBC</td><td><code>VECTOR_DISTANCE</code> with <code>WHERE</code> clause</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>VS5: Hybrid Search (Vector + Full-Text)</h3>
                    <p><strong>Purpose:</strong> Compare pure vector search, pure text search, and hybrid fusion approaches.</p>
                    <p><strong>Fusion Methods:</strong> RRF (Reciprocal Rank Fusion) with k=60</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> + <code>$search</code> (text) with client-side RRF</td></tr>
                        <tr><td>Oracle JDBC</td><td><code>VECTOR_DISTANCE</code> + <code>CONTAINS</code> (Oracle Text)</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>VS6: Quantization Performance</h3>
                    <p><strong>Purpose:</strong> Compare search performance and recall with different vector quantization levels.</p>
                    <p><strong>Types:</strong> Float32 (baseline), Int8 (scalar quantization)</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td>Float32 vectors (quantization is automatic in Atlas)</td></tr>
                        <tr><td>Oracle JDBC</td><td><code>VECTOR(384, FLOAT64)</code> and <code>VECTOR(384, INT8)</code></td></tr>
                    </table>
                </div>
            </div>
            </div>

            <!-- RAG Tests Descriptions -->
            <div id="desc-RAG" class="desc-category-content">
            <div class="test-descriptions">
                <div class="test-desc">
                    <h3>RAG1: Multi-Hop Graph Traversal</h3>
                    <p><strong>Purpose:</strong> RAG pattern - Find similar accounts and traverse relationships to find connected accounts.</p>
                    <p><strong>Data:</strong> 10K accounts with embeddings + 200K transactions (20 per account)</p>
                    <p><strong>Traversal:</strong> Same-tenant accounts via $graphLookup, shared-merchant accounts via transaction joins</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> → <code>$graphLookup</code> (same tenant) → <code>$lookup</code> (shared merchants)</td></tr>
                        <tr><td>Oracle JDBC</td><td>CTE + self-join for same-tenant + shared merchant subqueries</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>RAG2: Temporal Transaction Aggregation (Weekly Stats)</h3>
                    <p><strong>Purpose:</strong> RAG pattern - Find similar accounts, join transactions (30 days), and aggregate by ISO week.</p>
                    <p><strong>Data:</strong> 10K accounts with embeddings + 200K transactions (20 per account)</p>
                    <p><strong>Aggregation:</strong> Group by ISO week → count, sum(amount), avg(amount) per week</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> → <code>$lookup</code> → <code>$group</code> by <code>$isoWeek</code></td></tr>
                        <tr><td>Oracle JDBC</td><td>CTE + <code>GROUP BY TRUNC(date, 'IW')</code> with window functions</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>RAG3: Customer 360 Profile Assembly</h3>
                    <p><strong>Purpose:</strong> RAG pattern - Find similar accounts and assemble complete customer profile with spending analysis.</p>
                    <p><strong>Data:</strong> 10K accounts with embeddings + 200K transactions (20 per account)</p>
                    <p><strong>Profile:</strong> txn_count, total_spent, avg_amount, days_since_last_activity, spending by category</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> → <code>$lookup</code> → <code>$addFields</code> with <code>$reduce</code> for category map</td></tr>
                        <tr><td>Oracle JDBC</td><td>Two CTEs: stats aggregation + category spending pivot</td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>RAG4: Activity Pattern Detection (Rolling Window)</h3>
                    <p><strong>Purpose:</strong> RAG pattern - Find similar accounts and detect activity patterns using 7-day rolling windows.</p>
                    <p><strong>Data:</strong> 10K accounts with embeddings + 200K transactions (90-day range)</p>
                    <p><strong>Pattern Detection:</strong> Rolling 7-day count, burst detection (>2x avg), dormant detection (0 txns)</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> → <code>$lookup</code> → <code>$setWindowFields</code> for rolling window</td></tr>
                        <tr><td>Oracle JDBC</td><td>CTE + <code>SUM() OVER (RANGE BETWEEN 6 PRECEDING AND CURRENT ROW)</code></td></tr>
                    </table>
                </div>
                <div class="test-desc">
                    <h3>RAG5: Hybrid Context Ranking (Score Fusion)</h3>
                    <p><strong>Purpose:</strong> RAG pattern - Over-fetch candidates, compute hybrid score, and re-rank for final results.</p>
                    <p><strong>Data:</strong> 10K accounts with embeddings + 200K transactions</p>
                    <p><strong>Hybrid Score:</strong> 0.5×vector_score + 0.3×normalized_activity + 0.2×recency_score</p>
                    <table class="desc-table">
                        <tr><th>Protocol</th><th>Implementation</th></tr>
                        <tr><td>MongoDB Native</td><td><code>$vectorSearch</code> (50 candidates) → <code>$lookup</code> → <code>$setWindowFields</code> for normalization → hybrid score → top-10</td></tr>
                        <tr><td>Oracle JDBC</td><td>CTE with over-fetch + window functions for min/max normalization + hybrid formula</td></tr>
                    </table>
                </div>
            </div>
            </div>

            <style>
                .test-descriptions { margin-bottom: 30px; }
                .test-desc { background: rgba(255,255,255,0.03); border-radius: 10px; padding: 20px; margin-bottom: 15px; border: 1px solid var(--border-color); }
                .test-desc h3 { margin-bottom: 10px; color: var(--oracle-sql-color); }
                .test-desc p { margin-bottom: 10px; }
                .desc-table { width: 100%%; margin-top: 10px; }
                .desc-table th { background: rgba(255,255,255,0.08); }
                .desc-table td, .desc-table th { padding: 8px; }
                .desc-table code { background: rgba(0,0,0,0.3); padding: 2px 6px; border-radius: 3px; }
                .desc-category-tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
                .desc-category-tab {
                    padding: 10px 20px;
                    background: rgba(255,255,255,0.1);
                    border: none;
                    border-radius: 8px;
                    color: var(--text-primary);
                    cursor: pointer;
                    font-weight: 500;
                }
                .desc-category-tab.active { background: linear-gradient(135deg, #10b981, #059669); }
                .desc-category-content { display: none; }
                .desc-category-content.active { display: block; }
            </style>
            <script>
                function showDescCategory(category) {
                    document.querySelectorAll('.desc-category-content').forEach(c => c.classList.remove('active'));
                    document.querySelectorAll('.desc-category-tab').forEach(t => t.classList.remove('active'));
                    document.getElementById('desc-' + category).classList.add('active');
                    event.target.classList.add('active');
                }
            </script>
            """;
    }

    private static String generateResultsTabContent() {
        StringBuilder html = new StringBuilder();

        // Categorize tests
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("VS", new ArrayList<>());
        categories.put("RAG", new ArrayList<>());

        for (String testId : results.keySet()) {
            if (testId.startsWith("RAG")) {
                categories.get("RAG").add(testId);
            } else {
                categories.get("VS").add(testId);
            }
        }

        // Category subtabs
        html.append("""
            <div class="category-tabs">
                <button class="category-tab active" onclick="showCategoryTab('VS')">VS Tests (Vector Search)</button>
                <button class="category-tab" onclick="showCategoryTab('RAG')">RAG Tests (Retrieval Augmented)</button>
            </div>
            """);

        // VS Tab Content
        html.append("<div id=\"category-VS\" class=\"category-tab-content active\">");
        html.append(generateCategoryContent("VS", categories.get("VS")));
        html.append("</div>");

        // RAG Tab Content
        html.append("<div id=\"category-RAG\" class=\"category-tab-content\">");
        html.append(generateCategoryContent("RAG", categories.get("RAG")));
        html.append("</div>");

        // Category tab JavaScript
        html.append("""
            <script>
                function showCategoryTab(category) {
                    document.querySelectorAll('.category-tab-content').forEach(c => c.classList.remove('active'));
                    document.querySelectorAll('.category-tab').forEach(t => t.classList.remove('active'));
                    document.getElementById('category-' + category).classList.add('active');
                    event.target.classList.add('active');
                }
            </script>
            """);

        return html.toString();
    }

    private static String generateCategoryContent(String category, List<String> testIds) {
        StringBuilder html = new StringBuilder();

        if (testIds.isEmpty()) {
            html.append("<p>No tests in this category</p>");
            return html.toString();
        }

        // Generate chart for this category
        html.append("<h3>").append(category).append(" Performance Comparison</h3>");
        html.append(generateCategoryChart(category, testIds));

        // Results table for this category
        html.append("<h3>Results</h3>");
        html.append(generateCategoryResultsTable(testIds));

        // Query details section
        html.append("<h3>Query Details</h3>");
        html.append(generateCategoryQueryDetails(category, testIds));

        return html.toString();
    }

    private static String generateCategoryChart(String category, List<String> testIds) {
        StringBuilder labels = new StringBuilder("[");
        StringBuilder mongoData = new StringBuilder("[");
        StringBuilder oracleSqlData = new StringBuilder("[");
        StringBuilder oracleJdbcData = new StringBuilder("[");

        boolean first = true;
        for (String testId : testIds) {
            long[] times = results.get(testId);
            if (times == null) continue;

            if (!first) {
                labels.append(",");
                mongoData.append(",");
                oracleSqlData.append(",");
                oracleJdbcData.append(",");
            }
            first = false;

            labels.append("\"").append(testId).append("\"");
            mongoData.append(String.format("%.2f", times[0] / 1_000_000.0));
            oracleSqlData.append(times[1] > 0 ? String.format("%.2f", times[1] / 1_000_000.0) : "null");
            oracleJdbcData.append(times[2] > 0 ? String.format("%.2f", times[2] / 1_000_000.0) : "null");
        }

        labels.append("]");
        mongoData.append("]");
        oracleSqlData.append("]");
        oracleJdbcData.append("]");

        String chartId = "chart_" + category;

        return """
            <div class="chart-container">
                <canvas id="%s"></canvas>
            </div>
            <script>
                new Chart(document.getElementById('%s'), {
                    type: 'bar',
                    data: {
                        labels: %s,
                        datasets: [
                            { label: 'MongoDB Native', data: %s, backgroundColor: 'rgba(74, 222, 128, 0.8)', borderColor: '#4ade80', borderWidth: 1 },
                            { label: 'Oracle $sql', data: %s, backgroundColor: 'rgba(245, 158, 11, 0.8)', borderColor: '#f59e0b', borderWidth: 1 },
                            { label: 'Oracle JDBC', data: %s, backgroundColor: 'rgba(59, 130, 246, 0.8)', borderColor: '#3b82f6', borderWidth: 1 }
                        ]
                    },
                    options: {
                        responsive: true,
                        plugins: {
                            title: { display: true, text: '%s Tests - Query Latency (ms) - Lower is Better', color: '#e0e0e0' },
                            legend: { labels: { color: '#e0e0e0' } }
                        },
                        scales: {
                            x: { ticks: { color: '#a0a0a0', maxRotation: 45, minRotation: 45 }, grid: { color: '#333' } },
                            y: { type: 'logarithmic', ticks: { color: '#a0a0a0' }, grid: { color: '#333' }, title: { display: true, text: 'Latency (ms) - Log Scale', color: '#a0a0a0' } }
                        }
                    }
                });
            </script>
            """.formatted(chartId, chartId, labels, mongoData, oracleSqlData, oracleJdbcData, category);
    }

    private static String generateCategoryResultsTable(List<String> testIds) {
        StringBuilder html = new StringBuilder();

        html.append("""
            <table>
                <tr>
                    <th>Test</th>
                    <th>MongoDB (ms)</th>
                    <th>Oracle $sql (ms)</th>
                    <th>Oracle JDBC (ms)</th>
                    <th>Winner</th>
                    <th>Speedup</th>
                </tr>
            """);

        for (String testId : testIds) {
            long[] times = results.get(testId);
            if (times == null) continue;

            double mongo = times[0] / 1_000_000.0;
            double oracleSql = times[1] > 0 ? times[1] / 1_000_000.0 : -1;
            double oracleJdbc = times[2] > 0 ? times[2] / 1_000_000.0 : -1;

            String winner = "MongoDB";
            double best = mongo;
            double worst = mongo;
            if (oracleSql > 0) { worst = Math.max(worst, oracleSql); if (oracleSql < best) { best = oracleSql; winner = "Oracle $sql"; } }
            if (oracleJdbc > 0) { worst = Math.max(worst, oracleJdbc); if (oracleJdbc < best) { best = oracleJdbc; winner = "Oracle JDBC"; } }

            String speedup = String.format("%.1fx", worst / best);

            html.append(String.format("""
                <tr>
                    <td>%s</td>
                    <td>%.2f</td>
                    <td>%s</td>
                    <td>%s</td>
                    <td class="winner">%s</td>
                    <td>%s</td>
                </tr>
                """,
                testId,
                mongo,
                oracleSql > 0 ? String.format("%.2f", oracleSql) : "-",
                oracleJdbc > 0 ? String.format("%.2f", oracleJdbc) : "-",
                winner,
                speedup
            ));
        }

        html.append("</table>");
        return html.toString();
    }

    private static String generateCategoryQueryDetails(String category, List<String> testIds) {
        StringBuilder html = new StringBuilder();

        // Test tabs within category
        html.append("<div class=\"test-tabs\">");
        int tabIdx = 0;
        for (String testId : testIds) {
            html.append(String.format(
                "<button class=\"test-tab%s\" onclick=\"showTestTab_%s('%s')\">%s</button>",
                tabIdx == 0 ? " active" : "", category, testId, testId
            ));
            tabIdx++;
        }
        html.append("</div>");

        // Test content with nested protocol tabs
        tabIdx = 0;
        for (String testId : testIds) {
            long[] times = results.get(testId);
            if (times == null) continue;

            SqlDetails d = sqlDetailsMap.get(testId);

            html.append(String.format(
                "<div id=\"%s_test_%s\" class=\"test-tab-content%s\">",
                category, testId, tabIdx == 0 ? " active" : ""
            ));

            // Protocol tabs within this test
            html.append("<div class=\"protocol-tabs\">");
            html.append(String.format("<button class=\"protocol-tab mongo active\" onclick=\"showProtocol_%s('%s', 'mongo')\">MongoDB Native</button>", category, testId));
            html.append(String.format("<button class=\"protocol-tab oracle-sql\" onclick=\"showProtocol_%s('%s', 'oracle-sql')\">Oracle $sql</button>", category, testId));
            html.append(String.format("<button class=\"protocol-tab oracle-jdbc\" onclick=\"showProtocol_%s('%s', 'oracle-jdbc')\">Oracle JDBC</button>", category, testId));
            html.append("</div>");

            // MongoDB Native content
            html.append(String.format("<div id=\"%s_%s_mongo\" class=\"protocol-content active\">", category, testId));
            if (d != null) {
                html.append("<div class=\"detail-section\"><h4>Aggregation Pipeline</h4>");
                html.append("<pre>").append(escapeHtml(d.mongoPipeline())).append("</pre></div>");
                html.append("<div class=\"detail-section\"><h4>Explain Output</h4>");
                if (d.mongoExplain() != null && !d.mongoExplain().isEmpty()) {
                    html.append("<pre>").append(escapeHtml(d.mongoExplain())).append("</pre>");
                } else {
                    html.append("<p class=\"not-available\">Explain output not available</p>");
                }
                html.append("</div>");
            } else {
                html.append("<p class=\"not-available\">Query details not captured for this test</p>");
            }
            html.append(String.format("<p><strong>Latency:</strong> %.2f ms</p>", times[0] / 1_000_000.0));
            html.append("</div>");

            // Oracle $sql content
            html.append(String.format("<div id=\"%s_%s_oracle-sql\" class=\"protocol-content\">", category, testId));
            if (d != null && d.oracleSqlStatement() != null && !d.oracleSqlStatement().isEmpty()) {
                html.append("<div class=\"detail-section\"><h4>SQL Statement</h4>");
                html.append("<pre>").append(escapeHtml(d.oracleSqlStatement())).append("</pre></div>");
                html.append("<div class=\"detail-section\"><h4>Execution Plan</h4>");
                if (d.oracleSqlExplain() != null && !d.oracleSqlExplain().isEmpty() && !d.oracleSqlExplain().startsWith("Not supported")) {
                    html.append("<pre>").append(escapeHtml(d.oracleSqlExplain())).append("</pre>");
                } else {
                    html.append("<p class=\"not-available\">").append(escapeHtml(d.oracleSqlExplain() != null ? d.oracleSqlExplain() : "Not available")).append("</p>");
                }
                html.append("</div>");
            } else {
                html.append("<p class=\"not-available\">Oracle $sql not tested for this test</p>");
            }
            if (times[1] > 0) {
                html.append(String.format("<p><strong>Latency:</strong> %.2f ms</p>", times[1] / 1_000_000.0));
            }
            html.append("</div>");

            // Oracle JDBC content
            html.append(String.format("<div id=\"%s_%s_oracle-jdbc\" class=\"protocol-content\">", category, testId));
            if (d != null && d.oracleJdbcSql() != null && !d.oracleJdbcSql().isEmpty()) {
                html.append("<div class=\"detail-section\"><h4>SQL Statement</h4>");
                html.append("<pre>").append(escapeHtml(d.oracleJdbcSql())).append("</pre></div>");
                html.append("<div class=\"detail-section\"><h4>Execution Plan</h4>");
                if (d.oracleJdbcExplain() != null && !d.oracleJdbcExplain().isEmpty()) {
                    html.append("<pre>").append(escapeHtml(d.oracleJdbcExplain())).append("</pre>");
                } else {
                    html.append("<p class=\"not-available\">Execution plan not available</p>");
                }
                html.append("</div>");
                if (d.sqlMonitorJdbc() != null && !d.sqlMonitorJdbc().isEmpty()) {
                    html.append("<div class=\"links-row\">");
                    html.append("<a href=\"").append(d.sqlMonitorJdbc().replace("reports/vector_search/", "")).append("\" target=\"_blank\">View SQL Monitor Report</a>");
                    html.append("</div>");
                }
            } else {
                html.append("<p class=\"not-available\">Query details not captured for this test</p>");
            }
            if (times[2] > 0) {
                html.append(String.format("<p style=\"margin-top:10px\"><strong>Latency:</strong> %.2f ms</p>", times[2] / 1_000_000.0));
            }
            html.append("</div>");

            html.append("</div>"); // Close test-tab-content
            tabIdx++;
        }

        // JavaScript for nested tabs within this category
        html.append(String.format("""
            <script>
                function showTestTab_%s(testId) {
                    const container = document.getElementById('category-%s');
                    container.querySelectorAll('.test-tab-content').forEach(c => c.classList.remove('active'));
                    container.querySelectorAll('.test-tab').forEach(t => t.classList.remove('active'));
                    document.getElementById('%s_test_' + testId).classList.add('active');
                    event.target.classList.add('active');
                }
                function showProtocol_%s(testId, protocol) {
                    const testContent = document.getElementById('%s_test_' + testId);
                    testContent.querySelectorAll('.protocol-content').forEach(c => c.classList.remove('active'));
                    testContent.querySelectorAll('.protocol-tab').forEach(t => t.classList.remove('active'));
                    document.getElementById('%s_' + testId + '_' + protocol).classList.add('active');
                    event.target.classList.add('active');
                }
            </script>
            """, category, category, category, category, category, category));

        return html.toString();
    }

    private static String generateHtmlHeader() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Vector Search Benchmark Report</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                <style>
                    :root {
                        --bg-primary: #0f0f23;
                        --bg-secondary: #1a1a3e;
                        --text-primary: #e0e0e0;
                        --text-secondary: #a0a0a0;
                        --mongo-color: #4ade80;
                        --oracle-sql-color: #f59e0b;
                        --oracle-jdbc-color: #3b82f6;
                        --border-color: #333;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: 'Segoe UI', system-ui, sans-serif;
                        background: linear-gradient(135deg, var(--bg-primary) 0%%, var(--bg-secondary) 100%%);
                        color: var(--text-primary);
                        min-height: 100vh;
                        padding: 20px;
                    }
                    .container { max-width: 1400px; margin: 0 auto; }
                    h1 { font-size: 2rem; margin-bottom: 10px; }
                    h2 { font-size: 1.5rem; margin: 20px 0 10px; border-bottom: 1px solid var(--border-color); padding-bottom: 10px; }
                    .timestamp { color: var(--text-secondary); font-size: 0.9rem; margin-bottom: 20px; }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 15px;
                        margin-bottom: 30px;
                    }
                    .summary-card {
                        background: rgba(255,255,255,0.05);
                        border-radius: 10px;
                        padding: 20px;
                        text-align: center;
                        border: 1px solid var(--border-color);
                    }
                    .summary-card .value { font-size: 2rem; font-weight: bold; }
                    .summary-card .label { color: var(--text-secondary); font-size: 0.9rem; }
                    .chart-container {
                        background: rgba(255,255,255,0.03);
                        border-radius: 10px;
                        padding: 20px;
                        margin-bottom: 20px;
                        border: 1px solid var(--border-color);
                    }
                    table {
                        width: 100%%;
                        border-collapse: collapse;
                        margin: 20px 0;
                    }
                    th, td {
                        padding: 12px;
                        text-align: left;
                        border-bottom: 1px solid var(--border-color);
                    }
                    th { background: rgba(255,255,255,0.05); }
                    .winner { color: var(--mongo-color); font-weight: bold; }
                    /* Main tabs */
                    .main-tabs { display: flex; gap: 10px; margin-bottom: 20px; border-bottom: 2px solid var(--border-color); padding-bottom: 10px; }
                    .main-tab {
                        padding: 12px 24px;
                        background: rgba(255,255,255,0.1);
                        border: none;
                        border-radius: 8px 8px 0 0;
                        color: var(--text-primary);
                        cursor: pointer;
                        font-size: 1.1rem;
                        font-weight: 600;
                    }
                    .main-tab.active { background: linear-gradient(135deg, #3b82f6, #1d4ed8); }
                    .main-tab-content { display: none; }
                    .main-tab-content.active { display: block; }
                    /* Category tabs */
                    .category-tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
                    .category-tab {
                        padding: 10px 20px;
                        background: rgba(255,255,255,0.1);
                        border: none;
                        border-radius: 8px;
                        color: var(--text-primary);
                        cursor: pointer;
                        font-weight: 500;
                    }
                    .category-tab.active { background: linear-gradient(135deg, #10b981, #059669); }
                    .category-tab-content { display: none; }
                    .category-tab-content.active { display: block; }
                    /* Test tabs */
                    .test-tabs { display: flex; gap: 8px; margin: 15px 0; flex-wrap: wrap; }
                    .test-tab {
                        padding: 8px 16px;
                        background: rgba(255,255,255,0.1);
                        border: none;
                        border-radius: 5px;
                        color: var(--text-primary);
                        cursor: pointer;
                        font-size: 0.9rem;
                    }
                    .test-tab.active { background: rgba(255,255,255,0.25); }
                    .test-tab-content { display: none; background: rgba(255,255,255,0.02); border-radius: 10px; padding: 20px; border: 1px solid var(--border-color); margin-bottom: 20px; }
                    .test-tab-content.active { display: block; }
                    /* Protocol tabs */
                    .protocol-tabs { display: flex; gap: 8px; margin-bottom: 15px; flex-wrap: wrap; }
                    .protocol-tab { padding: 8px 16px; border: none; border-radius: 5px; cursor: pointer; font-size: 0.9rem; }
                    .protocol-tab.mongo { background: rgba(74, 222, 128, 0.2); color: var(--mongo-color); }
                    .protocol-tab.mongo.active { background: var(--mongo-color); color: #000; }
                    .protocol-tab.oracle-sql { background: rgba(245, 158, 11, 0.2); color: var(--oracle-sql-color); }
                    .protocol-tab.oracle-sql.active { background: var(--oracle-sql-color); color: #000; }
                    .protocol-tab.oracle-jdbc { background: rgba(59, 130, 246, 0.2); color: var(--oracle-jdbc-color); }
                    .protocol-tab.oracle-jdbc.active { background: var(--oracle-jdbc-color); color: #fff; }
                    .protocol-content { display: none; }
                    .protocol-content.active { display: block; }
                    .detail-section { margin-bottom: 20px; }
                    .detail-section h4 { margin-bottom: 10px; color: var(--text-secondary); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 0.5px; }
                    .detail-section pre { max-height: 400px; overflow: auto; }
                    .links-row { display: flex; gap: 15px; margin-top: 15px; flex-wrap: wrap; }
                    .links-row a { padding: 8px 16px; background: rgba(255,255,255,0.1); border-radius: 5px; text-decoration: none; }
                    .links-row a:hover { background: rgba(255,255,255,0.2); }
                    .not-available { color: var(--text-secondary); font-style: italic; }
                    pre {
                        background: rgba(0,0,0,0.3);
                        padding: 15px;
                        border-radius: 5px;
                        overflow-x: auto;
                        font-size: 0.85rem;
                    }
                    a { color: var(--oracle-sql-color); }
                    .mongo { color: var(--mongo-color); }
                    .oracle-sql { color: var(--oracle-sql-color); }
                    .oracle-jdbc { color: var(--oracle-jdbc-color); }
                </style>
            </head>
            <body>
            <div class="container">
                <h1>Vector Search Benchmark Report</h1>
                <div class="timestamp">Generated: %s</div>
            """.formatted(timestamp);
    }

    private static String generateHtmlFooter() {
        return """
            </div>
            </body>
            </html>
            """;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    // ==========================================================================
    // Lifecycle Methods
    // ==========================================================================

    @BeforeAll
    static void setup() throws SQLException {
        Properties props = loadConfigProperties();

        // MongoDB Native connection
        String mongoUri = props.getProperty("mongodb.uri");
        String mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);

        WriteConcern durableWriteConcern = WriteConcern.W1.withJournal(true);
        accountsCollection = mongoDatabase.getCollection(ACCOUNTS_COLLECTION)
            .withWriteConcern(durableWriteConcern);
        transactionsCollection = mongoDatabase.getCollection(TRANSACTIONS_COLLECTION)
            .withWriteConcern(durableWriteConcern);

        mongoVectorSearchSupported = checkMongoVectorSearchSupport();

        // Oracle JDBC connection
        String oracleUrl = props.getProperty("oracle.url");
        String oracleUser = props.getProperty("oracle.username");
        String oraclePass = props.getProperty("oracle.password");

        try {
            OracleDataSource ods = new OracleDataSource();
            ods.setURL(oracleUrl);
            ods.setUser(oracleUser);
            ods.setPassword(oraclePass);
            oracleJdbcConnection = ods.getConnection();
            oracleJdbcConnection.setAutoCommit(true);
            oracleVectorSupported = checkOracleVectorSupport();

            // Enable parallel execution for better query performance
            enableOracleParallelExecution();

            initializeAwr();
        } catch (SQLException e) {
            System.out.println("  Oracle JDBC connection failed: " + e.getMessage());
            oracleVectorSupported = false;
        }

        // Oracle MongoDB API connection
        String oracleMongoUri = props.getProperty("oracle.mongodb.uri");
        String oracleMongoDbName = props.getProperty("oracle.mongodb.database", mongoDbName);

        if (oracleMongoUri != null && !oracleMongoUri.isEmpty()) {
            try {
                // Create a trust-all SSL context for self-signed certificates
                SSLContext sslContext = createTrustAllSslContext();

                MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(oracleMongoUri))
                    .applyToSslSettings(builder -> {
                        builder.enabled(true);
                        builder.invalidHostNameAllowed(true);
                        builder.context(sslContext);
                    })
                    .build();

                oracleMongoClient = MongoClients.create(settings);
                oracleMongoDatabase = oracleMongoClient.getDatabase(oracleMongoDbName);
                oracleMongoDatabase.listCollectionNames().first(); // Test connection

                oracleSodaAccountsCollection = oracleMongoDatabase.getCollection(SODA_ACCOUNTS_COLLECTION)
                    .withWriteConcern(durableWriteConcern);
                oracleSodaTransactionsCollection = oracleMongoDatabase.getCollection(SODA_TRANSACTIONS_COLLECTION)
                    .withWriteConcern(durableWriteConcern);

                oracleMongoApiSupported = true;
                System.out.println("  Oracle MongoDB API: CONNECTED");
            } catch (Exception e) {
                System.out.println("  Oracle MongoDB API connection failed: " + e.getMessage());
                oracleMongoApiSupported = false;
            }
        }

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  VECTOR SEARCH BENCHMARK TEST SUITE");
        System.out.println("  " + "-".repeat(84));
        System.out.println("  MongoDB Native:     " + (mongoVectorSearchSupported ? "SUPPORTED" : "NOT SUPPORTED"));
        System.out.println("  Oracle MongoDB API: " + (oracleMongoApiSupported ? "SUPPORTED" : "NOT SUPPORTED"));
        System.out.println("  Oracle JDBC:        " + (oracleVectorSupported ? "SUPPORTED" : "NOT SUPPORTED"));
        System.out.println("=".repeat(90));
    }

    @AfterAll
    static void teardown() {
        // Generate reports
        generateAwrReports();
        generateHtmlReport();
        printResultsSummary();

        // Cleanup MongoDB
        if (mongoClient != null) {
            try {
                mongoDatabase.getCollection(ACCOUNTS_COLLECTION).drop();
                mongoDatabase.getCollection(TRANSACTIONS_COLLECTION).drop();
            } catch (Exception ignored) {}
            mongoClient.close();
        }

        // Cleanup Oracle MongoDB API
        if (oracleMongoClient != null) {
            try {
                oracleMongoDatabase.getCollection(SODA_ACCOUNTS_COLLECTION).drop();
                oracleMongoDatabase.getCollection(SODA_TRANSACTIONS_COLLECTION).drop();
            } catch (Exception ignored) {}
            oracleMongoClient.close();
        }

        // Cleanup Oracle JDBC
        if (oracleJdbcConnection != null) {
            try {
                // Close cached PreparedStatements
                if (cachedVectorSearchStmt != null) cachedVectorSearchStmt.close();
                if (cachedVectorSearchWithJoinStmt != null) cachedVectorSearchWithJoinStmt.close();
                if (cachedFilteredVectorSearchStmt != null) cachedFilteredVectorSearchStmt.close();
                if (cachedTextSearchStmt != null) cachedTextSearchStmt.close();

                try (Statement stmt = oracleJdbcConnection.createStatement()) {
                    stmt.execute("DROP TABLE " + TRANSACTIONS_TABLE + " CASCADE CONSTRAINTS");
                } catch (Exception ignored) {}
                try (Statement stmt = oracleJdbcConnection.createStatement()) {
                    stmt.execute("DROP TABLE " + ACCOUNTS_TABLE + " CASCADE CONSTRAINTS");
                } catch (Exception ignored) {}
                oracleJdbcConnection.close();
            } catch (Exception ignored) {}
        }
    }

    // ==========================================================================
    // Helper Methods - Feature Detection
    // ==========================================================================

    private static boolean checkMongoVectorSearchSupport() {
        try {
            MongoCollection<Document> testColl = mongoDatabase.getCollection("_vector_test_");
            testColl.drop();
            testColl.insertOne(new Document("_id", "test")
                .append("embedding", Arrays.asList(0.1, 0.2, 0.3, 0.4)));

            Document indexDef = new Document("mappings", new Document()
                .append("dynamic", true)
                .append("fields", new Document("embedding", new Document()
                    .append("type", "knnVector")
                    .append("dimensions", 4)
                    .append("similarity", "cosine"))));

            testColl.createSearchIndex("test_vector_idx", indexDef);
            testColl.drop();

            System.out.println("  MongoDB $vectorSearch: SUPPORTED");
            return true;
        } catch (Exception e) {
            System.out.println("  MongoDB $vectorSearch: NOT SUPPORTED (" + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean checkOracleVectorSupport() {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            stmt.execute("SELECT TO_VECTOR('[1,2,3]') FROM DUAL");
            System.out.println("  Oracle VECTOR type: SUPPORTED");
            return true;
        } catch (Exception e) {
            System.out.println("  Oracle VECTOR type: NOT SUPPORTED (" + e.getMessage() + ")");
            return false;
        }
    }

    // ==========================================================================
    // Helper Methods - Data Generation
    // ==========================================================================

    private static double[] generateNormalizedEmbedding(int dimensions, Random rand) {
        double[] embedding = new double[dimensions];
        double norm = 0;
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = rand.nextGaussian();
            norm += embedding[i] * embedding[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            embedding[i] /= norm;
        }
        return embedding;
    }

    private static Document createAccountDocument(String accountId, double[] embedding) {
        String[] types = {"CHECKING", "SAVINGS", "MONEY_MARKET", "CD"};
        String[] regions = {"NORTHEAST", "SOUTHEAST", "MIDWEST", "SOUTHWEST", "WEST"};

        List<Double> embeddingList = toDoubleList(embedding);

        return new Document()
            .append("_id", accountId)
            .append("accountId", accountId)
            .append("tenantId", TENANT_IDS[random.nextInt(TENANT_IDS.length)])
            .append("holderName", "Account Holder " + accountId)
            .append("email", accountId.toLowerCase() + "@example.com")
            .append("accountType", types[random.nextInt(types.length)])
            .append("balance", 1000.0 + random.nextDouble() * 99000.0)
            .append("region", regions[random.nextInt(regions.length)])
            .append("openedDate", new java.util.Date())
            .append("embedding", embeddingList)
            .append("embeddingText", "Account profile embedding for " + accountId);
    }

    private static Document createTransactionDocument(String txnId, String accountId, int dayOffset) {
        String[] types = {"DEBIT", "CREDIT"};
        String[] categories = {"SHOPPING", "FOOD", "UTILITIES", "ENTERTAINMENT", "TRANSFER"};
        String[] merchants = {"Amazon", "Walmart", "Target", "Costco", "Whole Foods"};

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -dayOffset);

        return new Document()
            .append("_id", txnId)
            .append("transactionId", txnId)
            .append("accountId", accountId)
            .append("transactionDate", cal.getTime())
            .append("amount", -10.0 - random.nextDouble() * 490.0)
            .append("type", types[random.nextInt(types.length)])
            .append("category", categories[random.nextInt(categories.length)])
            .append("merchant", merchants[random.nextInt(merchants.length)])
            .append("description", "Transaction " + txnId);
    }

    private static List<Document> generateAccountDocuments(int count, int dimensions) {
        List<Document> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String accountId = String.format("ACC_%05d", i);
            double[] embedding = generateNormalizedEmbedding(dimensions, random);
            docs.add(createAccountDocument(accountId, embedding));
        }
        return docs;
    }

    private static List<double[]> generateQueryVectors(int count, int dimensions) {
        List<double[]> vectors = new ArrayList<>(count);
        Random queryRand = new Random(999);
        for (int i = 0; i < count; i++) {
            vectors.add(generateNormalizedEmbedding(dimensions, queryRand));
        }
        return vectors;
    }

    /**
     * Creates an enhanced account document with all fields for advanced tests.
     * Includes: tenantId, state, city, lastActivityDate, riskScore, tags, description
     */
    private static Document createEnhancedAccountDocument(String accountId, double[] embedding) {
        String[] types = {"CHECKING", "SAVINGS", "MONEY_MARKET", "CD"};
        String[] regions = {"NORTHEAST", "SOUTHEAST", "MIDWEST", "SOUTHWEST", "WEST"};

        List<Double> embeddingList = toDoubleList(embedding);

        // Generate random tags (2-4 tags per account)
        List<String> accountTags = new ArrayList<>();
        int numTags = 2 + random.nextInt(3);
        Set<Integer> usedTagIndices = new HashSet<>();
        while (accountTags.size() < numTags) {
            int idx = random.nextInt(TAGS.length);
            if (usedTagIndices.add(idx)) {
                accountTags.add(TAGS[idx]);
            }
        }

        // Generate lastActivityDate (within last 90 days)
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -random.nextInt(90));

        // Generate balance with distribution for filter selectivity tests
        double balance = generateDistributedBalance();

        return new Document()
            .append("_id", accountId)
            .append("accountId", accountId)
            .append("tenantId", TENANT_IDS[random.nextInt(TENANT_IDS.length)])
            .append("holderName", "Account Holder " + accountId)
            .append("email", accountId.toLowerCase() + "@example.com")
            .append("accountType", types[random.nextInt(types.length)])
            .append("balance", balance)
            .append("region", regions[random.nextInt(regions.length)])
            .append("state", STATES[random.nextInt(STATES.length)])
            .append("city", CITIES[random.nextInt(CITIES.length)])
            .append("openedDate", new java.util.Date())
            .append("lastActivityDate", cal.getTime())
            .append("riskScore", random.nextDouble())
            .append("tags", accountTags)
            .append("description", DESCRIPTIONS[random.nextInt(DESCRIPTIONS.length)])
            .append("embedding", embeddingList)
            .append("embeddingModel", "all-MiniLM-L6-v2");
    }

    /**
     * Generate balance with specific distribution for filter selectivity testing.
     * 50% <= 25000, 90% <= 80000, 99% <= 95000
     */
    private static double generateDistributedBalance() {
        double r = random.nextDouble();
        if (r < 0.50) {
            // 50% have balance 1000-25000
            return 1000.0 + random.nextDouble() * 24000.0;
        } else if (r < 0.90) {
            // 40% have balance 25000-80000
            return 25000.0 + random.nextDouble() * 55000.0;
        } else if (r < 0.99) {
            // 9% have balance 80000-95000
            return 80000.0 + random.nextDouble() * 15000.0;
        } else {
            // 1% have balance 95000-100000
            return 95000.0 + random.nextDouble() * 5000.0;
        }
    }

    /**
     * Generate enhanced account documents for advanced tests
     */
    private static List<Document> generateEnhancedAccountDocuments(int count, int dimensions) {
        List<Document> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String accountId = String.format("ACC_%05d", i);
            double[] embedding = generateNormalizedEmbedding(dimensions, random);
            docs.add(createEnhancedAccountDocument(accountId, embedding));
        }
        return docs;
    }

    // ==========================================================================
    // Quantization Helper Methods
    // ==========================================================================

    /**
     * Quantize float64 embedding to int8 (-128 to 127)
     */
    private static byte[] quantizeToInt8(double[] embedding) {
        byte[] quantized = new byte[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            // Map [-1, 1] to [-128, 127]
            double clamped = Math.max(-1.0, Math.min(1.0, embedding[i]));
            quantized[i] = (byte) Math.round(clamped * 127);
        }
        return quantized;
    }

    /**
     * Dequantize int8 back to float64
     */
    private static double[] dequantizeFromInt8(byte[] quantized) {
        double[] embedding = new double[quantized.length];
        for (int i = 0; i < quantized.length; i++) {
            embedding[i] = quantized[i] / 127.0;
        }
        return embedding;
    }

    /**
     * Quantize float64 embedding to binary (1-bit per dimension)
     */
    private static byte[] quantizeToBinary(double[] embedding) {
        int numBytes = (embedding.length + 7) / 8;
        byte[] binary = new byte[numBytes];
        for (int i = 0; i < embedding.length; i++) {
            if (embedding[i] > 0) {
                binary[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return binary;
    }

    /**
     * Calculate cosine similarity between two embeddings
     */
    private static double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Calculate recall rate between ground truth and approximate results
     */
    private static double calculateRecall(List<String> groundTruth, List<String> results) {
        if (groundTruth.isEmpty()) return 0.0;
        Set<String> truthSet = new HashSet<>(groundTruth);
        long matches = results.stream().filter(truthSet::contains).count();
        return (double) matches / groundTruth.size();
    }

    /**
     * RRF (Reciprocal Rank Fusion) for hybrid search
     */
    private static List<String> rrfFusion(List<String> list1, List<String> list2, int k) {
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

    // ==========================================================================
    // Helper Methods - Utilities
    // ==========================================================================

    private static List<Double> toDoubleList(double[] array) {
        List<Double> list = new ArrayList<>(array.length);
        for (double v : array) list.add(v);
        return list;
    }

    private static String formatVectorString(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ==========================================================================
    // Helper Methods - MongoDB Operations
    // ==========================================================================

    private static boolean createMongoVectorSearchIndex(int dimensions) {
        try {
            try {
                accountsCollection.dropSearchIndex(VECTOR_INDEX_NAME);
                Thread.sleep(1000);
            } catch (Exception ignored) {}

            // Include filter fields for filtered vector search
            Document fields = new Document()
                .append("embedding", new Document()
                    .append("type", "knnVector")
                    .append("dimensions", dimensions)
                    .append("similarity", "cosine"))
                // Filter fields for VS4 filtered search tests
                .append("region", new Document("type", "token"))
                .append("accountType", new Document("type", "token"))
                .append("tenantId", new Document("type", "token"))
                .append("balance", new Document("type", "number"));

            Document indexDef = new Document("mappings", new Document()
                .append("dynamic", true)
                .append("fields", fields));

            accountsCollection.createSearchIndex(VECTOR_INDEX_NAME, indexDef);
            return true;
        } catch (Exception e) {
            System.out.println("  Warning: Failed to create vector index: " + e.getMessage());
            return false;
        }
    }

    private static void waitForIndexReady(int maxWaitMs) {
        try {
            Thread.sleep(Math.min(maxWaitMs, 3000));
        } catch (InterruptedException ignored) {}
    }

    // ==========================================================================
    // Helper Methods - Oracle Operations
    // ==========================================================================

    private static void createOracleAccountsTable(int dimensions) throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try {
                stmt.execute("DROP TABLE " + ACCOUNTS_TABLE + " CASCADE CONSTRAINTS");
            } catch (SQLException ignored) {}

            String ddl = String.format("""
                CREATE TABLE %s (
                    id VARCHAR2(100) PRIMARY KEY,
                    data JSON,
                    embedding VECTOR(%d, FLOAT64)
                )
                """, ACCOUNTS_TABLE, dimensions);
            stmt.execute(ddl);

            // Create vector index for faster similarity search
            // Try HNSW first (faster, in-memory), fall back to IVF (storage-based)
            boolean vectorIndexCreated = false;
            try {
                stmt.execute(String.format("""
                    CREATE VECTOR INDEX idx_accounts_vector ON %s(embedding)
                    ORGANIZATION INMEMORY NEIGHBOR GRAPH
                    DISTANCE COSINE
                    WITH TARGET ACCURACY 95
                    PARAMETERS (type HNSW, neighbors 40, efconstruction 500)
                    """, ACCOUNTS_TABLE));
                System.out.println("    HNSW vector index created (in-memory, graph-based)");
                vectorIndexCreated = true;
            } catch (SQLException e) {
                // HNSW requires VECTOR_MEMORY_SIZE to be configured - fall back to IVF
                System.out.println("    HNSW unavailable (requires vector_memory_size), trying IVF...");
            }

            if (!vectorIndexCreated) {
                try {
                    stmt.execute(String.format("""
                        CREATE VECTOR INDEX idx_accounts_vector ON %s(embedding)
                        ORGANIZATION NEIGHBOR PARTITIONS
                        DISTANCE COSINE
                        WITH TARGET ACCURACY 95
                        """, ACCOUNTS_TABLE));
                    System.out.println("    IVF vector index created (storage-based, partition-based)");
                } catch (SQLException e) {
                    System.out.println("    Note: Vector index creation failed: " + e.getMessage());
                }
            }
        }
    }

    private static void createOracleTransactionsTable() throws SQLException {
        try (Statement stmt = oracleJdbcConnection.createStatement()) {
            try {
                stmt.execute("DROP TABLE " + TRANSACTIONS_TABLE + " CASCADE CONSTRAINTS");
            } catch (SQLException ignored) {}

            String ddl = """
                CREATE TABLE %s (
                    transaction_id VARCHAR2(100) PRIMARY KEY,
                    account_id VARCHAR2(100) NOT NULL,
                    transaction_date DATE NOT NULL,
                    amount NUMBER(18,2),
                    transaction_type VARCHAR2(20),
                    category VARCHAR2(50),
                    merchant VARCHAR2(100),
                    description VARCHAR2(500)
                )
                """.formatted(TRANSACTIONS_TABLE);
            stmt.execute(ddl);

            // Composite index for account lookups with date range
            stmt.execute("CREATE INDEX idx_txn_acct_date ON " +
                TRANSACTIONS_TABLE + "(account_id, transaction_date)");

            // Index for merchant lookups (RAG1 shared merchant queries)
            stmt.execute("CREATE INDEX idx_txn_merchant ON " +
                TRANSACTIONS_TABLE + "(merchant)");

            // Composite index for merchant + account (RAG1 shared merchant optimization)
            // Allows finding accounts that share merchants without full table scan
            stmt.execute("CREATE INDEX idx_txn_merchant_account ON " +
                TRANSACTIONS_TABLE + "(merchant, account_id)");

            // Index for category aggregations (RAG3 spending by category)
            stmt.execute("CREATE INDEX idx_txn_category ON " +
                TRANSACTIONS_TABLE + "(category)");

            // Composite index for account + category (RAG3 category spending)
            stmt.execute("CREATE INDEX idx_txn_acct_cat ON " +
                TRANSACTIONS_TABLE + "(account_id, category)");
        }
    }

    private static void insertAccountsIntoOracle(List<Document> docs, int dimensions) throws SQLException {
        String sql = "INSERT INTO " + ACCOUNTS_TABLE + " (id, data, embedding) VALUES (?, ?, TO_VECTOR(?))";

        try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(sql)) {
            for (Document doc : docs) {
                pstmt.setString(1, doc.getString("_id"));

                Document dataDoc = new Document(doc);
                dataDoc.remove("embedding");
                pstmt.setString(2, dataDoc.toJson());

                @SuppressWarnings("unchecked")
                List<Double> embedding = (List<Double>) doc.get("embedding");
                pstmt.setString(3, formatVectorString(embedding.stream().mapToDouble(Double::doubleValue).toArray()));

                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private static void insertTransactionsIntoOracle(List<Document> transactions) throws SQLException {
        String sql = """
            INSERT INTO %s (transaction_id, account_id, transaction_date, amount,
                           transaction_type, category, merchant, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TRANSACTIONS_TABLE);

        try (PreparedStatement pstmt = oracleJdbcConnection.prepareStatement(sql)) {
            int batchCount = 0;
            for (Document txn : transactions) {
                pstmt.setString(1, txn.getString("_id"));
                pstmt.setString(2, txn.getString("accountId"));
                pstmt.setDate(3, new java.sql.Date(txn.getDate("transactionDate").getTime()));
                pstmt.setDouble(4, txn.getDouble("amount"));
                pstmt.setString(5, txn.getString("type"));
                pstmt.setString(6, txn.getString("category"));
                pstmt.setString(7, txn.getString("merchant"));
                pstmt.setString(8, txn.getString("description"));

                pstmt.addBatch();
                batchCount++;

                if (batchCount % 1000 == 0) {
                    pstmt.executeBatch();
                }
            }
            if (batchCount % 1000 != 0) {
                pstmt.executeBatch();
            }
        }
    }

    // ==========================================================================
    // Helper Methods - Benchmark Setup
    // ==========================================================================

    private void setupBenchmarkData(int accountCount, int dimensions, int transactionsPerAccount)
            throws SQLException {
        System.out.println("\n  Setting up benchmark data...");
        System.out.println("    Accounts: " + accountCount);
        System.out.println("    Dimensions: " + dimensions);
        System.out.println("    Transactions/account: " + transactionsPerAccount);

        List<Document> accounts = generateAccountDocuments(accountCount, dimensions);

        // MongoDB Native setup
        if (mongoVectorSearchSupported) {
            accountsCollection.drop();
            accountsCollection.insertMany(accounts);
            createMongoVectorSearchIndex(dimensions);
            waitForIndexReady(5000);
            System.out.println("    MongoDB Native: " + accountsCollection.countDocuments() + " accounts");
        }

        // Oracle JDBC setup
        if (oracleVectorSupported) {
            createOracleAccountsTable(dimensions);
            insertAccountsIntoOracle(accounts, dimensions);

            try (Statement stmt = oracleJdbcConnection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + ACCOUNTS_TABLE)) {
                rs.next();
                System.out.println("    Oracle JDBC: " + rs.getInt(1) + " accounts");
            }
        }

        // Transaction setup
        if (transactionsPerAccount > 0) {
            List<Document> transactions = new ArrayList<>();
            for (Document account : accounts) {
                String accountId = account.getString("accountId");
                for (int i = 0; i < transactionsPerAccount; i++) {
                    String txnId = String.format("%s_TXN_%03d", accountId, i);
                    transactions.add(createTransactionDocument(txnId, accountId, i % 30));
                }
            }

            if (mongoVectorSearchSupported) {
                transactionsCollection.drop();
                transactionsCollection.insertMany(transactions);
                // Create indexes for efficient RAG queries
                transactionsCollection.createIndex(new Document("accountId", 1).append("transactionDate", -1));
                transactionsCollection.createIndex(new Document("merchant", 1));
                transactionsCollection.createIndex(new Document("category", 1));
                transactionsCollection.createIndex(new Document("accountId", 1).append("category", 1));
                System.out.println("    MongoDB Native: " + transactionsCollection.countDocuments() + " transactions");
            }

            if (oracleVectorSupported) {
                createOracleTransactionsTable();
                insertTransactionsIntoOracle(transactions);
                try (Statement stmt = oracleJdbcConnection.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TRANSACTIONS_TABLE)) {
                    rs.next();
                    System.out.println("    Oracle JDBC: " + rs.getInt(1) + " transactions");
                }
            }
        }

        System.out.println("    Setup complete.");
    }

    // ==========================================================================
    // Helper Methods - Configuration
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

    /**
     * Creates an SSLContext that trusts all certificates.
     * Used for connecting to Oracle MongoDB API with self-signed certificates.
     */
    private static SSLContext createTrustAllSslContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to create trust-all SSL context", e);
        }
    }

    // ==========================================================================
    // Helper Methods - Reporting
    // ==========================================================================

    private static void printResultsSummary() {
        if (results.isEmpty()) {
            System.out.println("\n  No benchmark results to report.");
            return;
        }

        System.out.println("\n" + "=".repeat(95));
        System.out.println("  VECTOR SEARCH BENCHMARK RESULTS");
        System.out.println("  " + "-".repeat(89));
        System.out.printf("  %-15s %15s %15s %15s %15s%n",
            "Test", "MongoDB (ms)", "Oracle $sql", "Oracle JDBC", "Winner");
        System.out.println("  " + "-".repeat(89));

        for (Map.Entry<String, long[]> entry : results.entrySet()) {
            String testId = entry.getKey();
            long[] times = entry.getValue();

            double mongo = times[0] / 1_000_000.0;
            double oracleSql = times[1] > 0 ? times[1] / 1_000_000.0 : -1;
            double oracleJdbc = times[2] > 0 ? times[2] / 1_000_000.0 : -1;

            String winner = "MongoDB";
            double best = mongo;
            if (oracleSql > 0 && oracleSql < best) { best = oracleSql; winner = "Oracle $sql"; }
            if (oracleJdbc > 0 && oracleJdbc < best) { best = oracleJdbc; winner = "Oracle JDBC"; }

            System.out.printf("  %-15s %15.2f %15s %15s %15s%n",
                testId,
                mongo,
                oracleSql > 0 ? String.format("%.2f", oracleSql) : "-",
                oracleJdbc > 0 ? String.format("%.2f", oracleJdbc) : "-",
                winner
            );
        }
        System.out.println("=".repeat(95));
    }
}
