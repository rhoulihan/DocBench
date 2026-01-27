package com.docbench.benchmark;

import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.util.*;

/**
 * Exploratory test for Oracle MongoDB API's $sql aggregation operator syntax.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OracleMongoApiSqlTest {

    private static MongoClient mongoClient;
    private static MongoDatabase database;
    private static MongoCollection<Document> testCollection;

    @BeforeAll
    static void setup() throws Exception {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config/local.properties")) {
            props.load(fis);
        }

        String uri = props.getProperty("oracle.mongodb.uri");
        String dbName = props.getProperty("oracle.mongodb.database", "docbench");

        if (uri == null || uri.isEmpty()) {
            throw new RuntimeException("oracle.mongodb.uri not configured");
        }

        mongoClient = MongoClients.create(uri);
        database = mongoClient.getDatabase(dbName);

        // Create test collection
        try { database.getCollection("sql_test").drop(); } catch (Exception ignored) {}
        testCollection = database.getCollection("sql_test");

        // Insert test data
        testCollection.insertOne(new Document("_id", "1").append("name", "Alice").append("value", 100));
        testCollection.insertOne(new Document("_id", "2").append("name", "Bob").append("value", 200));
        testCollection.insertOne(new Document("_id", "3").append("name", "Charlie").append("value", 300));

        System.out.println("Test collection created with 3 documents");
    }

    @AfterAll
    static void teardown() {
        if (testCollection != null) {
            try { testCollection.drop(); } catch (Exception ignored) {}
        }
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test basic find (no $sql)")
    void testBasicFind() {
        System.out.println("\n=== Test 1: Basic find ===");
        for (Document doc : testCollection.find()) {
            System.out.println("  " + doc.toJson());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Test $sql with simple SELECT")
    void testSqlSimpleSelect() {
        System.out.println("\n=== Test 2: $sql with simple SELECT ===");
        try {
            // Try simple SELECT * FROM collection
            String sql = "SELECT * FROM sql_test";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Test $sql with SELECT data column")
    void testSqlSelectData() {
        System.out.println("\n=== Test 3: $sql with SELECT data ===");
        try {
            // Oracle SODA stores documents in a 'data' column
            String sql = "SELECT data FROM sql_test";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(4)
    @DisplayName("Test $sql with JSON_VALUE")
    void testSqlJsonValue() {
        System.out.println("\n=== Test 4: $sql with JSON_VALUE ===");
        try {
            // Try accessing JSON fields via JSON_VALUE
            String sql = "SELECT JSON_VALUE(data, '$.name') as name FROM sql_test";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(5)
    @DisplayName("Test $sql with dot notation")
    void testSqlDotNotation() {
        System.out.println("\n=== Test 5: $sql with dot notation ===");
        try {
            // Try Oracle's simplified JSON dot notation
            String sql = "SELECT t.data.name FROM sql_test t";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(6)
    @DisplayName("Test $sql with quoted _id")
    void testSqlQuotedId() {
        System.out.println("\n=== Test 6: $sql with quoted \"_id\" ===");
        try {
            // Try accessing _id with quoted identifier
            String sql = "SELECT t.data.\"_id\", t.data.name FROM sql_test t";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test $sql object format")
    void testSqlObjectFormat() {
        System.out.println("\n=== Test 7: $sql with object format ===");
        try {
            // Try $sql as an object with statement property
            Document sqlStage = new Document("$sql", new Document("statement", "SELECT data FROM sql_test"));
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(8)
    @DisplayName("Test $sql with WHERE clause")
    void testSqlWithWhere() {
        System.out.println("\n=== Test 8: $sql with WHERE ===");
        try {
            String sql = "SELECT data FROM sql_test WHERE JSON_VALUE(data, '$.value') > 150";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            for (Document doc : testCollection.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
            }
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(9)
    @DisplayName("Setup JOIN test collections")
    void setupJoinCollections() {
        System.out.println("\n=== Test 9: Setup JOIN collections ===");
        try {
            // Create customers collection
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            try { customers.drop(); } catch (Exception ignored) {}
            customers = database.getCollection("sql_customers");
            customers.insertOne(new Document("_id", "C1").append("name", "Alice"));
            customers.insertOne(new Document("_id", "C2").append("name", "Bob"));

            // Create orders collection
            MongoCollection<Document> orders = database.getCollection("sql_orders");
            try { orders.drop(); } catch (Exception ignored) {}
            orders = database.getCollection("sql_orders");
            orders.insertOne(new Document("_id", "O1").append("customer_id", "C1").append("total", 100));
            orders.insertOne(new Document("_id", "O2").append("customer_id", "C1").append("total", 200));
            orders.insertOne(new Document("_id", "O3").append("customer_id", "C2").append("total", 300));

            System.out.println("  Created sql_customers (2 docs) and sql_orders (3 docs)");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(10)
    @DisplayName("Test $sql JOIN with SELECT *")
    void testSqlJoinSelectStar() {
        System.out.println("\n=== Test 10: $sql JOIN with SELECT * ===");
        try {
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            String sql = "SELECT * FROM sql_customers c JOIN sql_orders o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            int count = 0;
            for (Document doc : customers.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
                count++;
            }
            System.out.println("  Returned " + count + " documents");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(11)
    @DisplayName("Test $sql JOIN with single column")
    void testSqlJoinSingleColumn() {
        System.out.println("\n=== Test 11: $sql JOIN with single column (c.data) ===");
        try {
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            String sql = "SELECT c.data FROM sql_customers c JOIN sql_orders o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            int count = 0;
            for (Document doc : customers.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
                count++;
            }
            System.out.println("  Returned " + count + " documents");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(12)
    @DisplayName("Test $sql JOIN with JSON_OBJECT")
    void testSqlJoinJsonObject() {
        System.out.println("\n=== Test 12: $sql JOIN with JSON_OBJECT ===");
        try {
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            // Use JSON_OBJECT to combine results into a single JSON document
            String sql = "SELECT JSON_OBJECT('customer' VALUE c.data, 'order' VALUE o.data) as result " +
                         "FROM sql_customers c JOIN sql_orders o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            int count = 0;
            for (Document doc : customers.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
                count++;
            }
            System.out.println("  Returned " + count + " documents");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(13)
    @DisplayName("Test $sql JOIN with JSON_MERGEPATCH")
    void testSqlJoinJsonMergePatch() {
        System.out.println("\n=== Test 13: $sql JOIN with JSON_MERGEPATCH ===");
        try {
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            // Use JSON_MERGEPATCH to merge customer and order into single document
            String sql = "SELECT JSON_MERGEPATCH(c.data, o.data) as merged " +
                         "FROM sql_customers c JOIN sql_orders o ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id')";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            int count = 0;
            for (Document doc : customers.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
                count++;
            }
            System.out.println("  Returned " + count + " documents");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(14)
    @DisplayName("Test $sql JOIN with ROWNUM")
    void testSqlJoinWithRownum() {
        System.out.println("\n=== Test 14: $sql JOIN with ROWNUM ===");
        try {
            MongoCollection<Document> customers = database.getCollection("sql_customers");
            String sql = "SELECT c.data FROM sql_customers c JOIN sql_orders o " +
                         "ON JSON_VALUE(c.data, '$._id') = JSON_VALUE(o.data, '$.customer_id') " +
                         "WHERE ROWNUM <= 2";
            Document sqlStage = new Document("$sql", sql);
            List<Document> pipeline = Arrays.asList(sqlStage);

            int count = 0;
            for (Document doc : customers.aggregate(pipeline)) {
                System.out.println("  " + doc.toJson());
                count++;
            }
            System.out.println("  Returned " + count + " documents");
            System.out.println("  SUCCESS");
        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    @Test
    @Order(99)
    @DisplayName("Cleanup JOIN test collections")
    void cleanupJoinCollections() {
        System.out.println("\n=== Cleanup ===");
        try {
            database.getCollection("sql_customers").drop();
            database.getCollection("sql_orders").drop();
            System.out.println("  Cleaned up sql_customers and sql_orders");
        } catch (Exception ignored) {}
    }
}
