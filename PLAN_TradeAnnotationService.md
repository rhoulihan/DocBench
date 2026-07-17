# Trade Annotation Service Benchmark - Implementation Plan

## Overview

This test suite simulates a real-world trade annotation service workflow where:
- Orders are received and executed
- Trades are annotated with data from various sources post-execution
- Status queries retrieve all documents for an order during high-volume processing

## Data Model

### Document Structure (Single Collection - Append Only)

```json
// Order Document
{
  "_id": "ORD_00001#ORDER#ORD_00001",
  "orderId": "ORD_00001",
  "docType": "ORDER",
  "docId": "ORD_00001",
  "timestamp": "2024-01-15T10:30:00.000Z",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 1000,
  "price": 185.50,
  "clientId": "CLIENT_123",
  "payload": "<variable size padding 4-12KB>"
}

// Execution Document
{
  "_id": "ORD_00001#EXEC#EXE_00001",
  "orderId": "ORD_00001",
  "docType": "EXECUTION",
  "docId": "EXE_00001",
  "timestamp": "2024-01-15T10:30:05.123Z",
  "fillQty": 500,
  "fillPrice": 185.48,
  "venue": "NYSE",
  "payload": "<variable size padding>"
}

// Annotation Document
{
  "_id": "ORD_00001#ANNOT#ANN_00001",
  "orderId": "ORD_00001",
  "docType": "ANNOTATION",
  "docId": "ANN_00001",
  "timestamp": "2024-01-15T10:30:10.456Z",
  "source": "RISK_ENGINE",
  "annotationType": "RISK_SCORE",
  "value": { "score": 0.85, "factors": [...] },
  "payload": "<variable size padding>"
}
```

### Composite _id Design

Format: `{orderId}#{docType}#{docId}`

Benefits:
- Documents for same order are physically adjacent (B-tree locality)
- Enables efficient range query: `_id >= "ORD_00001#" AND _id < "ORD_00001$"`
- No secondary index needed for order lookups

### Relational Equivalent Schema

```sql
CREATE TABLE trade_documents_rel (
  id VARCHAR2(200) PRIMARY KEY,           -- Composite key
  order_id VARCHAR2(50) NOT NULL,         -- For efficient lookups
  doc_type VARCHAR2(20) NOT NULL,         -- ORDER, EXECUTION, ANNOTATION
  doc_id VARCHAR2(100) NOT NULL,
  timestamp TIMESTAMP,
  symbol VARCHAR2(20),
  side VARCHAR2(10),
  quantity NUMBER,
  price NUMBER(18,6),
  client_id VARCHAR2(50),
  fill_qty NUMBER,
  fill_price NUMBER(18,6),
  venue VARCHAR2(20),
  source VARCHAR2(50),
  annotation_type VARCHAR2(50),
  annotation_value CLOB,
  payload CLOB                            -- Variable size padding
);

CREATE INDEX idx_trade_docs_order_id ON trade_documents_rel(order_id);
```

## Test Configuration

### Order Distribution

| Order Type | Percentage | Documents per Order |
|------------|------------|---------------------|
| Normal     | 80%        | 1 order + 1 exec + 5 annotations = 7 docs |
| Complex    | 20%        | 1 order + 1-5 execs + 5-15 annotations = 7-21 docs |

### Payload Size Test Runs

| Test | Payload Size | Approximate Doc Size |
|------|--------------|---------------------|
| TA1  | 4 KB         | ~4.5 KB total       |
| TA2  | 6 KB         | ~6.5 KB total       |
| TA3  | 8 KB         | ~8.5 KB total       |
| TA4  | 10 KB        | ~10.5 KB total      |
| TA5  | 12 KB        | ~12.5 KB total      |

### Order Counts

- Small scale: 1,000 orders (~8,600 documents)
- Medium scale: 10,000 orders (~86,000 documents)
- Large scale: 100,000 orders (~860,000 documents)

### Read Pattern

- 50% of complex orders (10% of total) are read 1-5 times
- Simulates status checks during trade processing
- Query retrieves ALL documents for an order (7-21 docs)

## Query Implementations

### 1. MongoDB Native

```javascript
// Using range query (more efficient than regex)
db.trades.find({
  _id: { $gte: "ORD_00001#", $lt: "ORD_00001$" }
}).sort({ _id: 1 })

// Alternative: regex (less efficient)
db.trades.find({ _id: { $regex: "^ORD_00001#" } })
```

### 2. Oracle MongoDB API (Native Pipeline)

```javascript
// Same as MongoDB - native pipeline
db.trades.find({
  _id: { $gte: "ORD_00001#", $lt: "ORD_00001$" }
}).sort({ _id: 1 })
```

### 3. Oracle MongoDB API ($sql)

```javascript
db.aggregate([{
  $sql: "SELECT data FROM trade_documents WHERE id >= 'ORD_00001#' AND id < 'ORD_00001$' ORDER BY id"
}])
```

### 4. Oracle JDBC JSON

```sql
SELECT data FROM trade_documents
WHERE id >= ? AND id < ?
ORDER BY id
-- Parameters: 'ORD_00001#', 'ORD_00001$'
```

### 5. Oracle JDBC Relational

```sql
SELECT id, order_id, doc_type, doc_id, timestamp,
       symbol, side, quantity, price, client_id,
       fill_qty, fill_price, venue,
       source, annotation_type, annotation_value, payload
FROM trade_documents_rel
WHERE order_id = ?
ORDER BY id
-- Parameter: 'ORD_00001'
```

## Test Structure

### Test Class: `TradeAnnotationServiceTest`

```
src/integrationTest/java/com/docbench/benchmark/TradeAnnotationServiceTest.java
```

### Test Methods

| Order | Test ID | Description |
|-------|---------|-------------|
| 1     | TA1_4KB_1K   | 4KB payload, 1K orders |
| 2     | TA1_4KB_10K  | 4KB payload, 10K orders |
| 3     | TA2_6KB_1K   | 6KB payload, 1K orders |
| 4     | TA2_6KB_10K  | 6KB payload, 10K orders |
| ...   | ...     | ... |
| 10    | TA5_12KB_10K | 12KB payload, 10K orders |

### Measurement Points

1. **Insert Phase**
   - Total insert time
   - Insert throughput (docs/sec)
   - Insert throughput (MB/sec)

2. **Read Phase**
   - Query latency per order lookup
   - Documents retrieved per query
   - Read throughput (queries/sec)

3. **Combined Metrics**
   - Total test duration
   - p50, p95, p99 read latencies

## Implementation Steps

### Step 1: Create Test Class Structure

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TradeAnnotationServiceTest {

    // Collections/Tables
    private static final String TRADES_COLLECTION = "trade_documents";
    private static final String TRADES_REL_TABLE = "trade_documents_rel";

    // Test configuration
    private static final int[] PAYLOAD_SIZES_KB = {4, 6, 8, 10, 12};
    private static final int SMALL_ORDER_COUNT = 1000;
    private static final int MEDIUM_ORDER_COUNT = 10000;

    // Results storage
    private record TradeTestResult(
        String testId,
        int payloadSizeKB,
        int orderCount,
        int totalDocuments,
        long insertTimeNanos,
        long readTimeNanos,
        int readQueryCount,
        double avgDocsPerQuery,
        String protocol
    ) {}
}
```

### Step 2: Data Generation Methods

```java
private List<Document> generateOrderDocuments(int orderCount, int payloadSizeKB) {
    List<Document> docs = new ArrayList<>();
    Random rand = new Random(42); // Reproducible

    for (int i = 0; i < orderCount; i++) {
        String orderId = String.format("ORD_%05d", i);
        boolean isComplex = rand.nextDouble() < 0.20; // 20% complex

        // Generate order document
        docs.add(createOrderDoc(orderId, payloadSizeKB));

        // Generate execution documents
        int execCount = isComplex ? rand.nextInt(5) + 1 : 1;
        for (int e = 0; e < execCount; e++) {
            docs.add(createExecutionDoc(orderId, e, payloadSizeKB));
        }

        // Generate annotation documents
        int annotCount = isComplex ? rand.nextInt(11) + 5 : 5; // 5-15 or 5
        for (int a = 0; a < annotCount; a++) {
            docs.add(createAnnotationDoc(orderId, a, payloadSizeKB));
        }
    }
    return docs;
}
```

### Step 3: Insert Methods (per protocol)

```java
private long insertMongoNative(List<Document> docs);
private long insertOracleMongoApi(List<Document> docs);
private long insertOracleJdbcJson(List<Document> docs);
private long insertOracleJdbcRelational(List<Document> docs);
```

### Step 4: Query Methods (per protocol)

```java
private long measureMongoNativeRead(List<String> orderIds);
private long measureOracleNativeApiRead(List<String> orderIds);
private long measureOracleApiSqlRead(List<String> orderIds);
private long measureOracleJdbcJsonRead(List<String> orderIds);
private long measureOracleJdbcRelationalRead(List<String> orderIds);
```

### Step 5: Test Execution Method

```java
private void runTradeAnnotationTest(int payloadSizeKB, int orderCount) {
    String testId = String.format("TA_%dKB_%dK", payloadSizeKB, orderCount/1000);
    awrSnapshotBefore(testId);

    // 1. Generate test data
    List<Document> docs = generateOrderDocuments(orderCount, payloadSizeKB);
    List<String> complexOrderIds = identifyComplexOrders(docs);

    // 2. Select orders for read testing (50% of complex)
    List<String> readTestOrders = selectReadTestOrders(complexOrderIds);

    // 3. Insert into all protocols
    long mongoInsertNanos = insertMongoNative(docs);
    long oracleNativeInsertNanos = insertOracleMongoApi(docs);
    long oracleJdbcJsonInsertNanos = insertOracleJdbcJson(docs);
    long oracleRelInsertNanos = insertOracleJdbcRelational(docs);

    // 4. Measure read performance (multiple iterations)
    long mongoReadNanos = measureMongoNativeRead(readTestOrders);
    long oracleNativeReadNanos = measureOracleNativeApiRead(readTestOrders);
    long oracleApiSqlReadNanos = measureOracleApiSqlRead(readTestOrders);
    long oracleJdbcJsonReadNanos = measureOracleJdbcJsonRead(readTestOrders);
    long oracleRelReadNanos = measureOracleJdbcRelationalRead(readTestOrders);

    // 5. Store results and capture SQL Monitor
    storeResults(...);
    captureSqlMonitor(...);

    awrSnapshotAfter(testId);
}
```

### Step 6: Report Generation

**Chart Categories:**
1. Insert throughput by payload size (bar chart)
2. Read latency by payload size (line chart)
3. Protocol comparison at each payload size (grouped bar)

**HTML Report Sections:**
- Summary statistics
- Insert performance comparison
- Read performance comparison
- SQL/Pipeline for each protocol
- Execution plans
- SQL Monitor tabs
- AWR reports

## Table Creation DDL

### Oracle JSON Table

```sql
CREATE TABLE trade_documents (
    id VARCHAR2(200) PRIMARY KEY,
    data JSON
);

-- Index on id is automatic (PRIMARY KEY)
```

### Oracle Relational Table

```sql
CREATE TABLE trade_documents_rel (
    id VARCHAR2(200) PRIMARY KEY,
    order_id VARCHAR2(50) NOT NULL,
    doc_type VARCHAR2(20) NOT NULL,
    doc_id VARCHAR2(100) NOT NULL,
    timestamp TIMESTAMP,
    symbol VARCHAR2(20),
    side VARCHAR2(10),
    quantity NUMBER,
    price NUMBER(18,6),
    client_id VARCHAR2(50),
    fill_qty NUMBER,
    fill_price NUMBER(18,6),
    venue VARCHAR2(20),
    source VARCHAR2(50),
    annotation_type VARCHAR2(50),
    annotation_value CLOB,
    payload CLOB
);

CREATE INDEX idx_trade_docs_order_id ON trade_documents_rel(order_id);
```

## Expected Results Format

### Results Record

```java
private record TradeAnnotationResult(
    String testId,
    String description,
    int payloadSizeKB,
    int orderCount,
    int documentCount,
    long mongoInsertNanos,
    long oracleNativeInsertNanos,
    long oracleJdbcJsonInsertNanos,
    long oracleRelInsertNanos,
    long mongoReadNanos,
    long oracleNativeReadNanos,
    long oracleApiSqlReadNanos,
    long oracleJdbcJsonReadNanos,
    long oracleRelReadNanos,
    int readQueryCount,
    double avgDocsPerRead
) {}
```

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `TradeAnnotationServiceTest.java` | Create | Main test class |
| `build.gradle.kts` | Modify | Add test task if needed |
| `reports/trade_annotation_report.html` | Generate | HTML report |

## Verification Checklist

- [ ] All 5 protocols produce identical document counts
- [ ] Composite _id ordering is consistent across protocols
- [ ] Read queries return correct document count per order
- [ ] SQL Monitor captures all query types
- [ ] AWR reports generated for each test
- [ ] Charts display all protocols with correct data
- [ ] Payload sizes match expected values (verify with document size checks)
