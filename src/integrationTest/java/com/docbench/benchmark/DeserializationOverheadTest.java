package com.docbench.benchmark;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.RawBsonDocument;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.junit.jupiter.api.*;

import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deserialization Overhead Test: Measures the cost of deserializing RawBsonDocument
 * to BsonDocument or Document, and determines the break-even point where
 * deserialization overhead is offset by faster O(1) field access.
 *
 * Compares four document types:
 * - RawBsonDocument: No deserialization, O(n) field access
 * - BsonDocument: One-time deserialization, O(1) field access
 * - Document: One-time deserialization, O(1) field access
 * - OracleJsonObject: O(1) field access baseline (no deserialization needed)
 *
 * Test methodology:
 * 1. Measure pure deserialization cost
 * 2. Measure single field access across all types
 * 3. Measure repeated access (same field vs different fields)
 * 4. Calculate break-even points
 */
@DisplayName("Deserialization Overhead: RawBsonDocument vs BsonDocument vs Document")
@Tag("benchmark")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeserializationOverheadTest {

    // Configuration
    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 100_000;

    // Document sizes to test
    private static final int[] FIELD_COUNTS = {100, 500, 1000};

    // Access counts for break-even analysis
    private static final int[] ACCESS_COUNTS = {1, 2, 5, 10, 25, 50, 100};

    // MongoDB
    private static MongoClient mongoClient;
    private static MongoDatabase mongoDatabase;
    private static MongoCollection<Document> documentCollection;
    private static MongoCollection<RawBsonDocument> rawCollection;
    private static String mongoDbName;

    // Oracle
    private static Connection oracleConnection;
    private static final String ORACLE_TABLE = "DESER_OVERHEAD_TEST";

    // Codecs for deserialization
    private static final BsonDocumentCodec BSON_CODEC = new BsonDocumentCodec();
    private static final DocumentCodec DOC_CODEC = new DocumentCodec();
    private static final DecoderContext DECODER_CONTEXT = DecoderContext.builder().build();

    // Results storage
    private static final Map<String, DeserializationResult> deserializationResults = new LinkedHashMap<>();
    private static final Map<String, AccessResult> accessResults = new LinkedHashMap<>();
    private static final List<BreakEvenResult> breakEvenResults = new ArrayList<>();

    // Result records
    record DeserializationResult(
            String testId,
            int fieldCount,
            long toBsonDocNanos,
            long toDocumentNanos,
            String description
    ) {}

    record AccessResult(
            String testId,
            int fieldCount,
            int position,
            long rawBsonNanos,
            long bsonDocNanos,
            long documentNanos,
            long oracleNanos,
            String description
    ) {}

    record BreakEvenResult(
            int fieldCount,
            int accessCount,
            boolean sameField,
            long rawBsonTotalNanos,
            long bsonDocTotalNanos,
            long documentTotalNanos,
            long oracleTotalNanos,
            String winner
    ) {}

    @BeforeAll
    static void setup() throws SQLException {
        Properties props = loadConfigProperties();

        // Setup MongoDB
        String mongoUri = props.getProperty("mongodb.uri");
        mongoDbName = props.getProperty("mongodb.database", "testdb");
        mongoClient = MongoClients.create(mongoUri);
        mongoDatabase = mongoClient.getDatabase(mongoDbName);

        // Drop and recreate collection
        mongoDatabase.getCollection("deser_overhead_test").drop();
        documentCollection = mongoDatabase.getCollection("deser_overhead_test");
        rawCollection = mongoDatabase.getCollection("deser_overhead_test", RawBsonDocument.class);

        // Setup Oracle
        String oracleUrl = props.getProperty("oracle.url");
        String oracleUser = props.getProperty("oracle.username");
        String oraclePass = props.getProperty("oracle.password");
        oracleConnection = DriverManager.getConnection(oracleUrl, oracleUser, oraclePass);

        // Create Oracle table
        try (Statement stmt = oracleConnection.createStatement()) {
            try {
                stmt.execute("DROP TABLE " + ORACLE_TABLE + " PURGE");
            } catch (SQLException e) {
                // Table doesn't exist - ignore
            }
            stmt.execute("CREATE TABLE " + ORACLE_TABLE + " (id VARCHAR2(100) PRIMARY KEY, doc JSON)");
        }

        // Create test documents for all sizes
        createTestDocuments();

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  DESERIALIZATION OVERHEAD BENCHMARK");
        System.out.println("  RawBsonDocument vs BsonDocument vs Document (Oracle OSON baseline)");
        System.out.println("=".repeat(80));

        // Global warmup
        runGlobalWarmup();
    }

    @AfterAll
    static void teardown() {
        printFinalReport();
        appendToHtmlReport();

        if (mongoClient != null) {
            mongoDatabase.getCollection("deser_overhead_test").drop();
            mongoClient.close();
        }
        if (oracleConnection != null) {
            try {
                try (Statement stmt = oracleConnection.createStatement()) {
                    stmt.execute("DROP TABLE " + ORACLE_TABLE + " PURGE");
                }
                oracleConnection.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

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

    private static void createTestDocuments() throws SQLException {
        for (int fieldCount : FIELD_COUNTS) {
            String docId = "test-" + fieldCount;
            Document doc = createFlatDocument(docId, fieldCount);

            // Insert into MongoDB
            documentCollection.insertOne(doc);

            // Insert into Oracle
            String json = doc.toJson();
            try (PreparedStatement ps = oracleConnection.prepareStatement(
                    "INSERT INTO " + ORACLE_TABLE + " (id, doc) VALUES (?, ?)")) {
                ps.setString(1, docId);
                ps.setString(2, json);
                ps.executeUpdate();
            }
        }
        System.out.println("Created test documents with field counts: " + Arrays.toString(FIELD_COUNTS));
    }

    private static Document createFlatDocument(String id, int fieldCount) {
        Document doc = new Document("_id", id);
        for (int i = 1; i <= fieldCount; i++) {
            String fieldName = "field_" + String.format("%04d", i);
            String value = "value_" + i + "_padding_to_ensure_reasonable_field_size_for_testing";
            doc.append(fieldName, value);
        }
        return doc;
    }

    private static void runGlobalWarmup() {
        System.out.println("\nRunning global warmup...");

        // Warmup MongoDB
        RawBsonDocument raw = rawCollection.find(new Document("_id", "test-100")).first();
        if (raw != null) {
            for (int i = 0; i < 1000; i++) {
                raw.get("field_0050");
                BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
                DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
            }
        }

        // Warmup Oracle
        try {
            OracleJsonObject oson = fetchOracleDocument("test-100");
            if (oson != null) {
                for (int i = 0; i < 1000; i++) {
                    oson.get("field_0050");
                }
            }
        } catch (SQLException e) {
            System.out.println("Oracle warmup failed: " + e.getMessage());
        }

        System.out.println("Global warmup complete.\n");
    }

    private static OracleJsonObject fetchOracleDocument(String docId) throws SQLException {
        String sql = "SELECT doc FROM " + ORACLE_TABLE + " WHERE id = ?";
        try (PreparedStatement ps = oracleConnection.prepareStatement(sql)) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    OracleJsonValue jsonValue = rs.getObject(1, OracleJsonValue.class);
                    if (jsonValue != null && jsonValue.getOracleJsonType() == OracleJsonValue.OracleJsonType.OBJECT) {
                        return jsonValue.asJsonObject();
                    }
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Category 1: Deserialization Cost Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Deserialization cost - 100 fields")
    void deserializationCost_100fields() {
        measureDeserializationCost(100);
    }

    @Test
    @Order(2)
    @DisplayName("Deserialization cost - 500 fields")
    void deserializationCost_500fields() {
        measureDeserializationCost(500);
    }

    @Test
    @Order(3)
    @DisplayName("Deserialization cost - 1000 fields")
    void deserializationCost_1000fields() {
        measureDeserializationCost(1000);
    }

    private void measureDeserializationCost(int fieldCount) {
        String docId = "test-" + fieldCount;
        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "Test document not found: " + docId);

        // Measure deserialization to BsonDocument
        long toBsonNanos = measureDeserializeToBsonDocument(raw);

        // Measure deserialization to Document
        long toDocNanos = measureDeserializeToDocument(raw);

        String testId = "deser-" + fieldCount;
        String description = fieldCount + " fields";
        deserializationResults.put(testId, new DeserializationResult(
                testId, fieldCount, toBsonNanos, toDocNanos, description));

        System.out.printf("  %-20s: → BsonDocument=%,8d ns, → Document=%,8d ns%n",
                description, toBsonNanos, toDocNanos);
    }

    private long measureDeserializeToBsonDocument(RawBsonDocument raw) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureDeserializeToDocument(RawBsonDocument raw) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // =========================================================================
    // Category 2: Single Access Comparison Tests
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("Single access - position 1 (first field)")
    void singleAccess_position1() throws SQLException {
        measureSingleAccess(1000, 1);
    }

    @Test
    @Order(11)
    @DisplayName("Single access - position 500 (middle field)")
    void singleAccess_position500() throws SQLException {
        measureSingleAccess(1000, 500);
    }

    @Test
    @Order(12)
    @DisplayName("Single access - position 1000 (last field)")
    void singleAccess_position1000() throws SQLException {
        measureSingleAccess(1000, 1000);
    }

    private void measureSingleAccess(int fieldCount, int position) throws SQLException {
        String docId = "test-" + fieldCount;
        String fieldName = "field_" + String.format("%04d", position);

        // Fetch documents
        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "RawBsonDocument not found: " + docId);

        BsonDocument bsonDoc = BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        Document doc = DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        OracleJsonObject oson = fetchOracleDocument(docId);
        assertNotNull(oson, "Oracle document not found: " + docId);

        // Measure each type
        long rawNanos = measureRawBsonSingleAccess(raw, fieldName);
        long bsonNanos = measureBsonDocSingleAccess(bsonDoc, fieldName);
        long docNanos = measureDocumentSingleAccess(doc, fieldName);
        long osonNanos = measureOracleSingleAccess(oson, fieldName);

        String testId = "access-" + position + "-" + fieldCount;
        String description = "Position " + position + "/" + fieldCount;
        accessResults.put(testId, new AccessResult(
                testId, fieldCount, position, rawNanos, bsonNanos, docNanos, osonNanos, description));

        System.out.printf("  %-25s: Raw=%,6d ns, BsonDoc=%,4d ns, Document=%,4d ns, Oracle=%,4d ns%n",
                description, rawNanos, bsonNanos, docNanos, osonNanos);
    }

    private long measureRawBsonSingleAccess(RawBsonDocument raw, String fieldName) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            raw.get(fieldName);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            raw.get(fieldName);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureBsonDocSingleAccess(BsonDocument bsonDoc, String fieldName) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            bsonDoc.get(fieldName);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            bsonDoc.get(fieldName);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureDocumentSingleAccess(Document doc, String fieldName) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            doc.get(fieldName);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            doc.get(fieldName);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureOracleSingleAccess(OracleJsonObject oson, String fieldName) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            oson.get(fieldName);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            oson.get(fieldName);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // =========================================================================
    // Category 3: Same Field Repeated Access Tests
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Same field repeated - 5 accesses")
    void sameFieldRepeated_5() throws SQLException {
        measureRepeatedAccess(1000, 5, true);
    }

    @Test
    @Order(31)
    @DisplayName("Same field repeated - 25 accesses")
    void sameFieldRepeated_25() throws SQLException {
        measureRepeatedAccess(1000, 25, true);
    }

    @Test
    @Order(32)
    @DisplayName("Same field repeated - 100 accesses")
    void sameFieldRepeated_100() throws SQLException {
        measureRepeatedAccess(1000, 100, true);
    }

    // =========================================================================
    // Category 4: Different Fields Access Tests
    // =========================================================================

    @Test
    @Order(50)
    @DisplayName("Different fields - 5 accesses")
    void differentFields_5() throws SQLException {
        measureRepeatedAccess(1000, 5, false);
    }

    @Test
    @Order(51)
    @DisplayName("Different fields - 25 accesses")
    void differentFields_25() throws SQLException {
        measureRepeatedAccess(1000, 25, false);
    }

    @Test
    @Order(52)
    @DisplayName("Different fields - 100 accesses")
    void differentFields_100() throws SQLException {
        measureRepeatedAccess(1000, 100, false);
    }

    private void measureRepeatedAccess(int fieldCount, int accessCount, boolean sameField) throws SQLException {
        String docId = "test-" + fieldCount;

        // Generate field list
        List<String> fields;
        if (sameField) {
            fields = Collections.nCopies(accessCount, "field_0500"); // Middle field
        } else {
            fields = new ArrayList<>();
            int step = Math.max(1, fieldCount / accessCount);
            for (int i = 0; i < accessCount; i++) {
                int pos = Math.min(1 + (i * step), fieldCount);
                fields.add("field_" + String.format("%04d", pos));
            }
        }

        // Fetch documents
        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "RawBsonDocument not found: " + docId);

        BsonDocument bsonDoc = BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        Document doc = DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        OracleJsonObject oson = fetchOracleDocument(docId);
        assertNotNull(oson, "Oracle document not found: " + docId);

        // Measure access time for each type
        long rawNanos = measureRawBsonMultiAccess(raw, fields);
        long bsonNanos = measureBsonDocMultiAccess(bsonDoc, fields);
        long docNanos = measureDocumentMultiAccess(doc, fields);
        long osonNanos = measureOracleMultiAccess(oson, fields);

        String pattern = sameField ? "same" : "diff";
        String description = (sameField ? "Same field" : "Diff fields") + " x" + accessCount;

        System.out.printf("  %-25s: Raw=%,8d ns, BsonDoc=%,6d ns, Document=%,6d ns, Oracle=%,6d ns%n",
                description, rawNanos, bsonNanos, docNanos, osonNanos);
    }

    private long measureRawBsonMultiAccess(RawBsonDocument raw, List<String> fields) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String field : fields) {
                raw.get(field);
            }
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (String field : fields) {
                raw.get(field);
            }
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureBsonDocMultiAccess(BsonDocument bsonDoc, List<String> fields) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String field : fields) {
                bsonDoc.get(field);
            }
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (String field : fields) {
                bsonDoc.get(field);
            }
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureDocumentMultiAccess(Document doc, List<String> fields) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String field : fields) {
                doc.get(field);
            }
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (String field : fields) {
                doc.get(field);
            }
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private long measureOracleMultiAccess(OracleJsonObject oson, List<String> fields) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            for (String field : fields) {
                oson.get(field);
            }
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            for (String field : fields) {
                oson.get(field);
            }
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    // =========================================================================
    // Category 5: Break-Even Analysis Tests
    // =========================================================================

    @Test
    @Order(70)
    @DisplayName("Break-even analysis - 100 fields, same field")
    void breakEven_100_same() throws SQLException {
        runBreakEvenAnalysis(100, true);
    }

    @Test
    @Order(71)
    @DisplayName("Break-even analysis - 100 fields, different fields")
    void breakEven_100_diff() throws SQLException {
        runBreakEvenAnalysis(100, false);
    }

    @Test
    @Order(72)
    @DisplayName("Break-even analysis - 500 fields, same field")
    void breakEven_500_same() throws SQLException {
        runBreakEvenAnalysis(500, true);
    }

    @Test
    @Order(73)
    @DisplayName("Break-even analysis - 500 fields, different fields")
    void breakEven_500_diff() throws SQLException {
        runBreakEvenAnalysis(500, false);
    }

    @Test
    @Order(74)
    @DisplayName("Break-even analysis - 1000 fields, same field")
    void breakEven_1000_same() throws SQLException {
        runBreakEvenAnalysis(1000, true);
    }

    @Test
    @Order(75)
    @DisplayName("Break-even analysis - 1000 fields, different fields")
    void breakEven_1000_diff() throws SQLException {
        runBreakEvenAnalysis(1000, false);
    }

    private void runBreakEvenAnalysis(int fieldCount, boolean sameField) throws SQLException {
        String docId = "test-" + fieldCount;
        String pattern = sameField ? "same" : "diff";

        System.out.printf("%n  Break-even: %d fields, %s field pattern%n", fieldCount, pattern);
        System.out.printf("  %-10s %12s %12s %12s %12s %10s%n",
                "Accesses", "RawBson", "BsonDoc", "Document", "Oracle", "Winner");
        System.out.println("  " + "-".repeat(70));

        // Fetch documents once
        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "RawBsonDocument not found: " + docId);

        OracleJsonObject oson = fetchOracleDocument(docId);
        assertNotNull(oson, "Oracle document not found: " + docId);

        // Get deserialization costs (averaged)
        long bsonDeserCost = measureDeserializeToBsonDocument(raw);
        long docDeserCost = measureDeserializeToDocument(raw);

        // Pre-deserialize for access measurements
        BsonDocument bsonDoc = BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        Document doc = DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);

        for (int accessCount : ACCESS_COUNTS) {
            // Generate field list
            List<String> fields;
            if (sameField) {
                fields = Collections.nCopies(accessCount, "field_" + String.format("%04d", fieldCount / 2));
            } else {
                fields = new ArrayList<>();
                int step = Math.max(1, fieldCount / accessCount);
                for (int i = 0; i < accessCount; i++) {
                    int pos = Math.min(1 + (i * step), fieldCount);
                    fields.add("field_" + String.format("%04d", pos));
                }
            }

            // Measure access times
            long rawAccessTime = measureRawBsonMultiAccess(raw, fields);
            long bsonAccessTime = measureBsonDocMultiAccess(bsonDoc, fields);
            long docAccessTime = measureDocumentMultiAccess(doc, fields);
            long osonAccessTime = measureOracleMultiAccess(oson, fields);

            // Calculate totals (including deserialization for parsed types)
            long rawTotal = rawAccessTime;
            long bsonTotal = bsonDeserCost + bsonAccessTime;
            long docTotal = docDeserCost + docAccessTime;
            long osonTotal = osonAccessTime;

            // Determine winner (lowest total time)
            String winner;
            long minTime = Math.min(Math.min(rawTotal, bsonTotal), Math.min(docTotal, osonTotal));
            if (minTime == osonTotal) winner = "Oracle";
            else if (minTime == docTotal) winner = "Document";
            else if (minTime == bsonTotal) winner = "BsonDoc";
            else winner = "RawBson";

            breakEvenResults.add(new BreakEvenResult(
                    fieldCount, accessCount, sameField,
                    rawTotal, bsonTotal, docTotal, osonTotal, winner));

            System.out.printf("  %-10d %,12d %,12d %,12d %,12d %10s%n",
                    accessCount, rawTotal, bsonTotal, docTotal, osonTotal, winner);
        }
    }

    // =========================================================================
    // Category 6: Nested Document Tests
    // =========================================================================

    @Test
    @Order(80)
    @DisplayName("Nested deserialization - depth 3")
    void nestedDeser_depth3() throws SQLException {
        measureNestedDeserialization(3);
    }

    @Test
    @Order(81)
    @DisplayName("Nested deserialization - depth 5")
    void nestedDeser_depth5() throws SQLException {
        measureNestedDeserialization(5);
    }

    @Test
    @Order(82)
    @DisplayName("Nested access - depth 3")
    void nestedAccess_depth3() throws SQLException {
        measureNestedAccess(3);
    }

    @Test
    @Order(83)
    @DisplayName("Nested access - depth 5")
    void nestedAccess_depth5() throws SQLException {
        measureNestedAccess(5);
    }

    private void measureNestedDeserialization(int depth) throws SQLException {
        String docId = "nested-" + depth;

        // Create nested document if not exists
        if (rawCollection.find(new Document("_id", docId)).first() == null) {
            Document nestedDoc = createNestedDocument(docId, depth);
            documentCollection.insertOne(nestedDoc);

            // Insert into Oracle
            String json = nestedDoc.toJson();
            try (PreparedStatement ps = oracleConnection.prepareStatement(
                    "INSERT INTO " + ORACLE_TABLE + " (id, doc) VALUES (?, ?)")) {
                ps.setString(1, docId);
                ps.setString(2, json);
                ps.executeUpdate();
            }
        }

        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "Nested document not found: " + docId);

        long toBsonNanos = measureDeserializeToBsonDocument(raw);
        long toDocNanos = measureDeserializeToDocument(raw);

        System.out.printf("  Nested depth %d deser: → BsonDocument=%,8d ns, → Document=%,8d ns%n",
                depth, toBsonNanos, toDocNanos);
    }

    private void measureNestedAccess(int depth) throws SQLException {
        String docId = "nested-" + depth;

        // Ensure document exists
        if (rawCollection.find(new Document("_id", docId)).first() == null) {
            Document nestedDoc = createNestedDocument(docId, depth);
            documentCollection.insertOne(nestedDoc);

            String json = nestedDoc.toJson();
            try (PreparedStatement ps = oracleConnection.prepareStatement(
                    "INSERT INTO " + ORACLE_TABLE + " (id, doc) VALUES (?, ?)")) {
                ps.setString(1, docId);
                ps.setString(2, json);
                ps.executeUpdate();
            }
        }

        RawBsonDocument raw = rawCollection.find(new Document("_id", docId)).first();
        assertNotNull(raw, "Nested document not found: " + docId);

        BsonDocument bsonDoc = BSON_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        Document doc = DOC_CODEC.decode(raw.asBsonReader(), DECODER_CONTEXT);
        OracleJsonObject oson = fetchOracleDocument(docId);
        assertNotNull(oson, "Oracle nested document not found: " + docId);

        // Build path parts
        String[] pathParts = buildNestedPath(depth);

        long rawNanos = measureRawBsonNestedAccess(raw, pathParts);
        long bsonNanos = measureBsonDocNestedAccess(bsonDoc, pathParts);
        long docNanos = measureDocumentNestedAccess(doc, pathParts);
        long osonNanos = measureOracleNestedAccess(oson, pathParts);

        System.out.printf("  Nested depth %d access: Raw=%,6d ns, BsonDoc=%,4d ns, Document=%,4d ns, Oracle=%,4d ns%n",
                depth, rawNanos, bsonNanos, docNanos, osonNanos);
    }

    private Document createNestedDocument(String id, int depth) {
        Document doc = new Document("_id", id);
        Document current = new Document("target", "TARGET_VALUE");

        // Add padding fields at deepest level
        for (int i = 0; i < 10; i++) {
            current.append("padding_" + i, "padding_value_" + i);
        }

        // Build nested structure
        for (int d = depth - 1; d >= 0; d--) {
            Document parent = new Document();
            for (int i = 0; i < 10; i++) {
                parent.append("padding_" + i, "level_" + d + "_value_" + i);
            }
            parent.append("nested", current);
            current = parent;
        }

        doc.append("root", current);
        return doc;
    }

    private String[] buildNestedPath(int depth) {
        List<String> parts = new ArrayList<>();
        parts.add("root");
        for (int i = 0; i < depth; i++) {
            parts.add("nested");
        }
        parts.add("target");
        return parts.toArray(new String[0]);
    }

    private long measureRawBsonNestedAccess(RawBsonDocument raw, String[] path) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            navigateRawBson(raw, path);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            navigateRawBson(raw, path);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private Object navigateRawBson(RawBsonDocument raw, String[] path) {
        org.bson.BsonValue current = raw;
        for (String part : path) {
            if (current == null || !current.isDocument()) return null;
            current = current.asDocument().get(part);
        }
        return current;
    }

    private long measureBsonDocNestedAccess(BsonDocument bsonDoc, String[] path) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            navigateBsonDoc(bsonDoc, path);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            navigateBsonDoc(bsonDoc, path);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private Object navigateBsonDoc(BsonDocument bsonDoc, String[] path) {
        org.bson.BsonValue current = bsonDoc;
        for (String part : path) {
            if (current == null || !current.isDocument()) return null;
            current = current.asDocument().get(part);
        }
        return current;
    }

    private long measureDocumentNestedAccess(Document doc, String[] path) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            navigateDocument(doc, path);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            navigateDocument(doc, path);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private Object navigateDocument(Document doc, String[] path) {
        Object current = doc;
        for (String part : path) {
            if (current == null) return null;
            if (current instanceof Document) {
                current = ((Document) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private long measureOracleNestedAccess(OracleJsonObject oson, String[] path) {
        // Warmup
        for (int w = 0; w < WARMUP_ITERATIONS; w++) {
            navigateOson(oson, path);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            navigateOson(oson, path);
            totalNanos += System.nanoTime() - start;
        }

        return totalNanos / MEASUREMENT_ITERATIONS;
    }

    private Object navigateOson(OracleJsonObject oson, String[] path) {
        OracleJsonValue current = oson;
        for (String part : path) {
            if (current == null) return null;
            if (current.getOracleJsonType() == OracleJsonValue.OracleJsonType.OBJECT) {
                current = current.asJsonObject().get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    // =========================================================================
    // Report Generation
    // =========================================================================

    private static void printFinalReport() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  DESERIALIZATION OVERHEAD - FINAL REPORT");
        System.out.println("=".repeat(80));

        // Deserialization costs
        System.out.println("\n--- Deserialization Cost (one-time) ---");
        System.out.printf("%-15s %15s %15s%n", "Fields", "→ BsonDocument", "→ Document");
        System.out.println("-".repeat(50));
        for (DeserializationResult r : deserializationResults.values()) {
            System.out.printf("%-15d %,15d ns %,15d ns%n",
                    r.fieldCount, r.toBsonDocNanos, r.toDocumentNanos);
        }

        // Access comparison
        System.out.println("\n--- Single Field Access (per access) ---");
        System.out.printf("%-20s %12s %12s %12s %12s%n",
                "Position", "RawBson", "BsonDoc", "Document", "Oracle");
        System.out.println("-".repeat(70));
        for (AccessResult r : accessResults.values()) {
            System.out.printf("%-20s %,12d %,12d %,12d %,12d%n",
                    r.description, r.rawBsonNanos, r.bsonDocNanos, r.documentNanos, r.oracleNanos);
        }

        // Break-even summary
        System.out.println("\n--- Break-Even Analysis Summary ---");
        calculateAndPrintBreakEvenPoints();

        System.out.println("\n" + "=".repeat(80));
    }

    private static void calculateAndPrintBreakEvenPoints() {
        // Group by fieldCount and sameField
        Map<String, List<BreakEvenResult>> grouped = new LinkedHashMap<>();
        for (BreakEvenResult r : breakEvenResults) {
            String key = r.fieldCount + "-" + (r.sameField ? "same" : "diff");
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<BreakEvenResult>> entry : grouped.entrySet()) {
            List<BreakEvenResult> results = entry.getValue();
            if (results.isEmpty()) continue;

            int fieldCount = results.get(0).fieldCount;
            boolean sameField = results.get(0).sameField;
            String pattern = sameField ? "same field" : "different fields";

            // Find break-even point where BsonDoc/Document beats RawBson
            int bsonBreakEven = -1;
            int docBreakEven = -1;

            for (BreakEvenResult r : results) {
                if (bsonBreakEven == -1 && r.bsonDocTotalNanos < r.rawBsonTotalNanos) {
                    bsonBreakEven = r.accessCount;
                }
                if (docBreakEven == -1 && r.documentTotalNanos < r.rawBsonTotalNanos) {
                    docBreakEven = r.accessCount;
                }
            }

            System.out.printf("%n  %d fields, %s:%n", fieldCount, pattern);
            if (bsonBreakEven > 0) {
                System.out.printf("    BsonDocument beats RawBson at %d+ accesses%n", bsonBreakEven);
            } else {
                System.out.printf("    BsonDocument never beats RawBson (in tested range)%n");
            }
            if (docBreakEven > 0) {
                System.out.printf("    Document beats RawBson at %d+ accesses%n", docBreakEven);
            } else {
                System.out.printf("    Document never beats RawBson (in tested range)%n");
            }
        }
    }

    private static void appendToHtmlReport() {
        try {
            Path reportPath = Path.of("reports/performance_report.html");

            // Check if report exists
            if (!Files.exists(reportPath)) {
                System.out.println("Warning: performance_report.html not found, creating standalone report");
                generateStandaloneHtmlReport(reportPath);
                return;
            }

            // Read existing report
            String existingHtml = Files.readString(reportPath);

            // Find insertion point (before closing </div></body>)
            int insertionPoint = existingHtml.lastIndexOf("</div>");
            if (insertionPoint == -1) {
                System.out.println("Warning: Could not find insertion point in HTML report");
                return;
            }

            // Generate new section
            String newSection = generateHtmlSection();

            // Insert new section
            String updatedHtml = existingHtml.substring(0, insertionPoint)
                    + newSection
                    + existingHtml.substring(insertionPoint);

            Files.writeString(reportPath, updatedHtml);
            System.out.println("\nAppended deserialization results to: " + reportPath.toAbsolutePath());

        } catch (IOException e) {
            System.out.println("Failed to update HTML report: " + e.getMessage());
        }
    }

    private static void generateStandaloneHtmlReport(Path reportPath) throws IOException {
        Files.createDirectories(reportPath.getParent());

        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>DocBench - Deserialization Overhead Analysis</title>
                    <style>
                        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 40px; background: #f5f5f5; }
                        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                        h1 { color: #333; border-bottom: 3px solid #0066cc; padding-bottom: 10px; }
                        h2 { color: #0066cc; margin-top: 30px; }
                        h3 { color: #666; }
                        table { border-collapse: collapse; width: 100%; margin: 20px 0; }
                        th, td { border: 1px solid #ddd; padding: 12px; text-align: right; }
                        th { background: #0066cc; color: white; }
                        td:first-child { text-align: left; font-weight: 500; }
                        tr:nth-child(even) { background: #f9f9f9; }
                        tr:hover { background: #f0f7ff; }
                        .winner-raw { color: #cc6600; font-weight: bold; }
                        .winner-bson { color: #006699; font-weight: bold; }
                        .winner-doc { color: #009966; font-weight: bold; }
                        .winner-oracle { color: #990066; font-weight: bold; }
                        .summary-box { background: #f0f7ff; border: 1px solid #0066cc; border-radius: 8px; padding: 20px; margin: 20px 0; }
                        .section { margin: 30px 0; }
                        .note { color: #666; font-style: italic; font-size: 0.9em; }
                    </style>
                </head>
                <body>
                <div class="container">
                    <h1>DocBench - Deserialization Overhead Analysis</h1>
                    <p class="note">RawBsonDocument vs BsonDocument vs Document (Oracle OSON baseline)</p>
                """);

        html.append(generateHtmlSection());

        html.append("""
                </div>
                </body>
                </html>
                """);

        Files.writeString(reportPath, html.toString());
        System.out.println("Generated standalone report: " + reportPath.toAbsolutePath());
    }

    private static String generateHtmlSection() {
        StringBuilder html = new StringBuilder();

        html.append("""
                <div class="section">
                    <h2>Deserialization Overhead Analysis</h2>
                    <p class="note">Compares MongoDB document wrapper strategies with Oracle OSON as baseline</p>
                """);

        // Deserialization cost table
        html.append("""
                    <h3>Deserialization Cost (one-time)</h3>
                    <table>
                        <tr><th>Document Size</th><th>→ BsonDocument</th><th>→ Document</th></tr>
                """);

        for (DeserializationResult r : deserializationResults.values()) {
            html.append(String.format(
                    "<tr><td>%d fields</td><td>%,d ns</td><td>%,d ns</td></tr>%n",
                    r.fieldCount, r.toBsonDocNanos, r.toDocumentNanos));
        }
        html.append("</table>");

        // Single access table
        html.append("""
                    <h3>Single Field Access Time</h3>
                    <table>
                        <tr><th>Position</th><th>RawBson</th><th>BsonDoc</th><th>Document</th><th>Oracle</th></tr>
                """);

        for (AccessResult r : accessResults.values()) {
            html.append(String.format(
                    "<tr><td>%s</td><td>%,d ns</td><td>%,d ns</td><td>%,d ns</td><td>%,d ns</td></tr>%n",
                    r.description, r.rawBsonNanos, r.bsonDocNanos, r.documentNanos, r.oracleNanos));
        }
        html.append("</table>");

        // Break-even summary
        html.append("""
                    <h3>Break-Even Summary</h3>
                    <div class="summary-box">
                """);

        // Calculate break-even points
        Map<String, int[]> breakEvenPoints = calculateBreakEvenPoints();
        for (Map.Entry<String, int[]> entry : breakEvenPoints.entrySet()) {
            int[] points = entry.getValue();
            html.append(String.format("<p><strong>%s:</strong> ", entry.getKey()));
            if (points[0] > 0) {
                html.append(String.format("BsonDocument wins at %d+ accesses, ", points[0]));
            }
            if (points[1] > 0) {
                html.append(String.format("Document wins at %d+ accesses", points[1]));
            }
            html.append("</p>\n");
        }

        html.append("""
                        <p class="note"><strong>Recommendation:</strong> For documents accessed fewer than the break-even point,
                        use RawBsonDocument. For documents accessed more frequently, pre-deserialize to Document.</p>
                    </div>
                """);

        // Detailed break-even table
        html.append("""
                    <h3>Detailed Break-Even Analysis</h3>
                    <table>
                        <tr><th>Config</th><th>Accesses</th><th>RawBson</th><th>BsonDoc</th><th>Document</th><th>Oracle</th><th>Winner</th></tr>
                """);

        for (BreakEvenResult r : breakEvenResults) {
            String config = r.fieldCount + " fields, " + (r.sameField ? "same" : "diff");
            String winnerClass = switch (r.winner) {
                case "RawBson" -> "winner-raw";
                case "BsonDoc" -> "winner-bson";
                case "Document" -> "winner-doc";
                case "Oracle" -> "winner-oracle";
                default -> "";
            };
            html.append(String.format(
                    "<tr><td>%s</td><td>%d</td><td>%,d ns</td><td>%,d ns</td><td>%,d ns</td><td>%,d ns</td><td class='%s'>%s</td></tr>%n",
                    config, r.accessCount, r.rawBsonTotalNanos, r.bsonDocTotalNanos,
                    r.documentTotalNanos, r.oracleTotalNanos, winnerClass, r.winner));
        }
        html.append("</table>");

        html.append("</div>");
        return html.toString();
    }

    private static Map<String, int[]> calculateBreakEvenPoints() {
        Map<String, int[]> breakEvenPoints = new LinkedHashMap<>();

        Map<String, List<BreakEvenResult>> grouped = new LinkedHashMap<>();
        for (BreakEvenResult r : breakEvenResults) {
            String key = r.fieldCount + " fields, " + (r.sameField ? "same field" : "different fields");
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<BreakEvenResult>> entry : grouped.entrySet()) {
            List<BreakEvenResult> results = entry.getValue();
            int bsonBreakEven = -1;
            int docBreakEven = -1;

            for (BreakEvenResult r : results) {
                if (bsonBreakEven == -1 && r.bsonDocTotalNanos < r.rawBsonTotalNanos) {
                    bsonBreakEven = r.accessCount;
                }
                if (docBreakEven == -1 && r.documentTotalNanos < r.rawBsonTotalNanos) {
                    docBreakEven = r.accessCount;
                }
            }

            breakEvenPoints.put(entry.getKey(), new int[]{bsonBreakEven, docBreakEven});
        }

        return breakEvenPoints;
    }
}
