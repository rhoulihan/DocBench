# Vector Search Benchmark - Implementation Plan

## Overview

This test suite benchmarks AI-driven vector similarity search workloads:
1. **VS1**: Vector search to retrieve account document (simple retrieval)
2. **VS2**: Vector search + join with last month's transactions (complex retrieval)

## Critical Considerations

### MongoDB Vector Search Availability

MongoDB vector search (`$vectorSearch`) requires:
- **MongoDB Atlas** with Vector Search enabled, OR
- **MongoDB 7.0+ Enterprise** with Atlas Search on-prem

**For local MongoDB Community Edition**: Vector search is NOT natively available.

**Workaround Options:**
1. **Brute-force approach**: Use `$addFields` with vector math to compute distances (slow but works)
2. **Skip MongoDB vector tests**: If Atlas not available, compare Oracle methods only
3. **Pre-filter + brute-force**: Filter by region/category first, then compute distances on subset

**Recommendation**: Implement brute-force vector similarity for MongoDB to enable fair comparison. This measures the "what if MongoDB didn't have vector indexes" baseline.

### Oracle 23ai Vector Support

Oracle 23ai Free includes:
- `VECTOR` data type
- `VECTOR_DISTANCE()` function (cosine, euclidean, dot product)
- `CREATE VECTOR INDEX` for approximate nearest neighbor (ANN)
- Exact K-nearest neighbor via ORDER BY VECTOR_DISTANCE

## Data Model

### Accounts Collection/Table

```javascript
// MongoDB Document
{
  "_id": "ACC_00001",
  "accountId": "ACC_00001",
  "holderName": "John Smith",
  "email": "john.smith@example.com",
  "accountType": "CHECKING",
  "balance": 15420.50,
  "region": "NORTHEAST",
  "openedDate": "2020-03-15",
  "embedding": [0.123, -0.456, 0.789, ...],  // 384-dim vector
  "embeddingText": "Premium checking account holder in Northeast region with high balance and frequent transactions"
}
```

```sql
-- Oracle JSON Table
CREATE TABLE accounts_vec (
    id VARCHAR2(100) PRIMARY KEY,
    data JSON,
    -- Virtual column for vector extraction (if needed)
    embedding VECTOR(384) GENERATED ALWAYS AS (
        JSON_VALUE(data, '$.embedding' RETURNING VECTOR(384))
    ) VIRTUAL
);

-- Or dedicated vector column
CREATE TABLE accounts_vec (
    id VARCHAR2(100) PRIMARY KEY,
    data JSON,
    embedding VECTOR(384)  -- Stored separately for index efficiency
);

CREATE VECTOR INDEX idx_accounts_embedding ON accounts_vec(embedding)
    ORGANIZATION NEIGHBOR PARTITIONS
    DISTANCE COSINE
    WITH TARGET ACCURACY 95;
```

### Transactions Collection/Table

```javascript
// MongoDB Document
{
  "_id": "TXN_00001_00001",
  "transactionId": "TXN_00001_00001",
  "accountId": "ACC_00001",
  "transactionDate": "2024-01-15T10:30:00Z",
  "amount": -125.50,
  "type": "DEBIT",
  "category": "SHOPPING",
  "merchant": "Amazon",
  "description": "Online purchase"
}
```

```sql
-- Oracle Relational Table (for efficient date range queries and joins)
CREATE TABLE transactions_vec (
    transaction_id VARCHAR2(100) PRIMARY KEY,
    account_id VARCHAR2(100) NOT NULL,
    transaction_date DATE NOT NULL,
    amount NUMBER(18,2),
    transaction_type VARCHAR2(20),
    category VARCHAR2(50),
    merchant VARCHAR2(100),
    description VARCHAR2(500)
);

CREATE INDEX idx_txn_account_date ON transactions_vec(account_id, transaction_date);
```

## Query Implementations

### Workload 1: Vector Search + Account Retrieval

**MongoDB Brute-Force (no vector index):**
```javascript
db.accounts.aggregate([
  {
    $addFields: {
      similarity: {
        $reduce: {
          input: { $zip: { inputs: ["$embedding", queryVector] } },
          initialValue: 0,
          in: { $add: ["$$value", { $multiply: [{ $arrayElemAt: ["$$this", 0] }, { $arrayElemAt: ["$$this", 1] }] }] }
        }
      }
    }
  },
  { $sort: { similarity: -1 } },
  { $limit: 10 },
  { $project: { embedding: 0, similarity: 0 } }
])
```

**MongoDB with Atlas Vector Search (if available):**
```javascript
db.accounts.aggregate([
  {
    $vectorSearch: {
      index: "vector_index",
      path: "embedding",
      queryVector: [...],
      numCandidates: 100,
      limit: 10
    }
  }
])
```

**Oracle $sql via MongoDB API:**
```javascript
db.aggregate([{
  $sql: `
    SELECT a.data
    FROM accounts_vec a
    ORDER BY VECTOR_DISTANCE(a.embedding, :queryVector, COSINE)
    FETCH FIRST 10 ROWS ONLY
  `
}])
```

**Oracle JDBC with Vector Index:**
```sql
SELECT data
FROM accounts_vec
ORDER BY VECTOR_DISTANCE(embedding, ?, COSINE)
FETCH FIRST 10 ROWS ONLY
```

### Workload 2: Vector Search + Transaction Join

**MongoDB Brute-Force + $lookup:**
```javascript
db.accounts.aggregate([
  // Vector similarity (brute force)
  {
    $addFields: {
      similarity: { /* dot product calculation */ }
    }
  },
  { $sort: { similarity: -1 } },
  { $limit: 10 },
  // Join transactions from last month
  {
    $lookup: {
      from: "transactions",
      let: { accId: "$accountId" },
      pipeline: [
        {
          $match: {
            $expr: { $eq: ["$accountId", "$$accId"] },
            transactionDate: {
              $gte: ISODate("2024-01-01"),
              $lt: ISODate("2024-02-01")
            }
          }
        },
        { $sort: { transactionDate: -1 } }
      ],
      as: "recentTransactions"
    }
  },
  { $project: { embedding: 0, similarity: 0 } }
])
```

**Oracle $sql (Single Query with JOIN):**
```javascript
db.aggregate([{
  $sql: `
    SELECT a.data AS account_data,
           (SELECT JSON_ARRAYAGG(
              JSON_OBJECT(
                'transactionId' VALUE t.transaction_id,
                'amount' VALUE t.amount,
                'transactionDate' VALUE t.transaction_date,
                'category' VALUE t.category
              )
            )
            FROM transactions_vec t
            WHERE t.account_id = JSON_VALUE(a.data, '$.accountId')
              AND t.transaction_date >= ADD_MONTHS(SYSDATE, -1)
           ) AS transactions
    FROM accounts_vec a
    ORDER BY VECTOR_DISTANCE(a.embedding, :queryVector, COSINE)
    FETCH FIRST 10 ROWS ONLY
  `
}])
```

**Oracle JDBC (Single Query with JOIN):**
```sql
SELECT a.data AS account_data,
       t.transaction_id, t.amount, t.transaction_date, t.category
FROM (
    SELECT id, data, embedding
    FROM accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, ?, COSINE)
    FETCH FIRST 10 ROWS ONLY
) a
LEFT JOIN transactions_vec t ON t.account_id = JSON_VALUE(a.data, '$.accountId')
    AND t.transaction_date >= ADD_MONTHS(SYSDATE, -1)
ORDER BY VECTOR_DISTANCE(a.embedding, ?, COSINE), t.transaction_date DESC
```

## Test Configuration

### Vector Dimensions
| Test | Dimensions | Use Case |
|------|------------|----------|
| Small | 384 | Sentence transformers (all-MiniLM-L6-v2) |
| Medium | 768 | BERT-base embeddings |
| Large | 1536 | OpenAI text-embedding-ada-002 |

### Data Volumes
| Scale | Accounts | Transactions/Account/Month | Total Transactions |
|-------|----------|---------------------------|-------------------|
| Small | 10,000 | 20 | 200,000 |
| Medium | 50,000 | 30 | 1,500,000 |
| Large | 100,000 | 50 | 5,000,000 |

### Test Parameters
- **K (top results)**: 1, 5, 10, 20
- **Query vectors**: 100 random queries per test
- **Warmup**: 10 iterations
- **Measurement**: 50 iterations

## Test Structure

### Test Class: `VectorSearchBenchmarkTest`

```
src/integrationTest/java/com/docbench/benchmark/VectorSearchBenchmarkTest.java
```

### Test Methods

| Order | Test ID | Description |
|-------|---------|-------------|
| 1 | VS1_384_10K_K10 | 384-dim, 10K accounts, top 10 |
| 2 | VS1_768_10K_K10 | 768-dim, 10K accounts, top 10 |
| 3 | VS1_1536_10K_K10 | 1536-dim, 10K accounts, top 10 |
| 4 | VS2_384_10K_K10 | 384-dim + transactions, top 10 |
| 5 | VS2_768_10K_K10 | 768-dim + transactions, top 10 |
| 6 | VS2_1536_10K_K10 | 1536-dim + transactions, top 10 |
| 7 | VS1_384_50K_K10 | Scale test: 50K accounts |
| 8 | VS2_384_50K_K10 | Scale test: 50K + transactions |

## Implementation Steps

### Step 1: Check Vector Support
```java
private boolean checkMongoVectorSupport() {
    // Try to create vector index or use $vectorSearch
    // Return false if not available, fall back to brute-force
}

private boolean checkOracleVectorSupport() {
    // Check Oracle version >= 23ai
    // Verify VECTOR data type is available
}
```

### Step 2: Data Generation
```java
private float[] generateRandomEmbedding(int dimensions, Random rand) {
    float[] embedding = new float[dimensions];
    float norm = 0;
    for (int i = 0; i < dimensions; i++) {
        embedding[i] = (float) rand.nextGaussian();
        norm += embedding[i] * embedding[i];
    }
    // Normalize to unit vector for cosine similarity
    norm = (float) Math.sqrt(norm);
    for (int i = 0; i < dimensions; i++) {
        embedding[i] /= norm;
    }
    return embedding;
}
```

### Step 3: MongoDB Brute-Force Vector Search
```java
private long measureMongoBruteForceVectorSearch(float[] queryVector, int k) {
    // Build aggregation pipeline with $addFields for dot product
    // This is O(n) - scans all documents
}
```

### Step 4: Oracle Vector Search with Index
```java
private long measureOracleVectorSearch(float[] queryVector, int k) {
    String sql = """
        SELECT data FROM accounts_vec
        ORDER BY VECTOR_DISTANCE(embedding, ?, COSINE)
        FETCH FIRST ? ROWS ONLY
        """;
    // Uses vector index for O(log n) approximate search
}
```

### Step 5: Oracle Vector + Join
```java
private long measureOracleVectorSearchWithTransactions(float[] queryVector, int k) {
    String sql = """
        WITH top_accounts AS (
            SELECT id, data, embedding
            FROM accounts_vec
            ORDER BY VECTOR_DISTANCE(embedding, ?, COSINE)
            FETCH FIRST ? ROWS ONLY
        )
        SELECT a.data, t.*
        FROM top_accounts a
        LEFT JOIN transactions_vec t
            ON t.account_id = JSON_VALUE(a.data, '$.accountId')
            AND t.transaction_date >= ADD_MONTHS(SYSDATE, -1)
        """;
}
```

## Expected Results

### Performance Hypothesis

| Workload | MongoDB (brute-force) | Oracle (no index) | Oracle (with index) |
|----------|----------------------|-------------------|---------------------|
| VS1 (10K) | ~500ms | ~200ms | ~5ms |
| VS1 (50K) | ~2500ms | ~1000ms | ~10ms |
| VS2 (10K) | ~800ms | ~300ms | ~50ms |
| VS2 (50K) | ~4000ms | ~1500ms | ~100ms |

**Key Insight**: Oracle's vector index should provide orders-of-magnitude improvement over brute-force approaches. MongoDB without Atlas Vector Search cannot compete on large-scale vector workloads.

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `VectorSearchBenchmarkTest.java` | Create | Main test class |
| `reports/vector_search_report.html` | Generate | HTML report with charts |

## Open Questions for Discussion

1. **MongoDB Atlas**: Is Atlas available for vector search, or should we only test brute-force?

2. **Vector Dimensions**: Should we focus on 384 (faster) or 1536 (realistic for production)?

3. **Exact vs Approximate**: Should Oracle tests compare exact (no index) vs approximate (with index)?

4. **Transaction Volume**: How many transactions per account is realistic? 20-50/month?

5. **Date Range**: Fixed date range or rolling "last 30 days"?

6. **Result Verification**: Should we verify that vector search returns the same top-K across all methods?

## Verification Checklist

- [ ] Vector indexes created successfully in Oracle
- [ ] Brute-force produces correct similarity scores
- [ ] Transaction join returns correct date-filtered results
- [ ] Results are consistent across protocols (same top-K accounts)
- [ ] Charts display performance differences clearly
- [ ] AWR reports capture vector index usage

