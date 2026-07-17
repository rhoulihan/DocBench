# RAG Benchmark Implementation Plan

## Overview

This plan implements 5 RAG (Retrieval Augmented Generation) benchmark tests showcasing advanced query patterns that join additional data to vector search results. Tests compare MongoDB aggregation pipelines vs Oracle SQL/JDBC approaches.

## Test Summary

| Test ID | Name | Pattern | Key Feature | Status |
|---------|------|---------|-------------|--------|
| RAG1 | Multi-Hop Account Relationships | Graph Traversal | Find related accounts via shared tenants/merchants | ✅ COMPLETE |
| RAG2 | Temporal Transaction Aggregation | Time Series | Weekly aggregation of transaction history | ✅ COMPLETE |
| RAG3 | Customer 360 Profile | Relational Join | Complete customer context assembly | ✅ COMPLETE |
| RAG4 | Activity Pattern Detection | Time Window | Rolling window anomaly detection | ✅ COMPLETE |
| RAG5 | Hybrid Context Ranking | Score Fusion | Re-rank by combined vector + relational score | ✅ COMPLETE |

## Implementation Progress

### Phase 1: RAG2 (Temporal Transaction Aggregation) - COMPLETE
- MongoDB: `$vectorSearch` → `$lookup` → `$group` by `$isoWeek`
- Oracle: CTE + `GROUP BY TRUNC(date, 'IW')`
- Benchmark: MongoDB 549.78ms, Oracle JDBC 14.64ms

### Phase 2: RAG3 (Customer 360 Profile) - COMPLETE
- MongoDB: `$vectorSearch` → `$lookup` → `$reduce` for category spending
- Oracle: Two-query approach (stats + categories)
- Benchmark: MongoDB 373.65ms, Oracle JDBC 27.64ms

### Phase 3: RAG1 (Graph Traversal) - COMPLETE
- MongoDB: `$vectorSearch` → `$graphLookup` (same tenant) → `$lookup` (shared merchants)
- Oracle: Self-join for same-tenant + shared merchant queries
- Benchmark: MongoDB 2235.48ms, Oracle JDBC 513.46ms

### Phase 4: RAG4 (Activity Pattern Detection) - COMPLETE
- MongoDB: `$vectorSearch` → `$lookup` (90 days) → `$setWindowFields` for 7-day rolling window → classify burst/dormant
- Oracle: CTE + `SUM() OVER (RANGE BETWEEN 6 PRECEDING AND CURRENT ROW)` analytic function
- Benchmark: MongoDB 590.47ms, Oracle JDBC 18.57ms (32x faster!)

### Phase 5: RAG5 (Hybrid Context Ranking) - COMPLETE
- MongoDB: `$vectorSearch` (50 candidates) → `$lookup` txn stats → `$setWindowFields` for normalization → hybrid score calculation → re-rank → top-10
- Oracle: CTE with over-fetch + window functions for min/max normalization + hybrid score formula
- Hybrid Score Formula: `0.5 * vectorScore + 0.3 * normalizedTxnActivity + 0.2 * recencyScore`
- Benchmark: MongoDB 2702.38ms, Oracle JDBC 17.78ms (152x faster!)

---

## RAG2: Temporal Transaction Aggregation (Enhance VS2)

### Current State (VS2)
- MongoDB: `$vectorSearch` → `$lookup` transactions (last 30 days, raw list)
- Oracle JDBC: CTE with vector search → scalar subquery counting transactions

### Enhancement Required
Add temporal grouping to aggregate transactions by week with statistics.

### Target Query Pattern
```
Vector Search → Find top-K similar accounts
    → Join transactions for last 30 days
    → Group by ISO week
    → Calculate: count, total_amount, avg_amount per week
```

### MongoDB Pipeline
```javascript
[
  { $vectorSearch: { index: "accounts_vector_idx", path: "embedding", queryVector: [...], limit: 10 } },
  { $lookup: {
      from: "transactions",
      let: { accId: "$accountId" },
      pipeline: [
        { $match: { $expr: { $and: [
          { $eq: ["$accountId", "$$accId"] },
          { $gte: ["$transactionDate", thirtyDaysAgo] }
        ]}}},
        { $group: {
            _id: { $isoWeek: "$transactionDate" },
            weekStart: { $min: "$transactionDate" },
            txnCount: { $sum: 1 },
            totalAmount: { $sum: "$amount" },
            avgAmount: { $avg: "$amount" }
        }},
        { $sort: { "_id": 1 } }
      ],
      as: "weeklyStats"
  }}
]
```

### Oracle JDBC SQL
```sql
WITH top_accounts AS (
    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id
    FROM benchmark_accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE)
    FETCH FIRST ? ROWS ONLY
)
SELECT a.id, a.account_id,
       TRUNC(t.transaction_date, 'IW') AS week_start,
       COUNT(*) AS txn_count,
       SUM(t.amount) AS total_amount,
       AVG(t.amount) AS avg_amount
FROM top_accounts a
LEFT JOIN benchmark_transactions_vec t
    ON t.account_id = a.account_id
    AND t.transaction_date >= SYSDATE - 30
GROUP BY a.id, a.account_id, TRUNC(t.transaction_date, 'IW')
ORDER BY a.id, week_start
```

### TDD Test Cases
1. `testRAG2_weeklyAggregation_returnsCorrectWeekCount` - Verify 4-5 weeks returned for 30-day range
2. `testRAG2_weeklyAggregation_sumsMatchTransactionTotals` - Verify aggregation math
3. `testRAG2_weeklyAggregation_performance` - Benchmark comparison

### Implementation Steps
1. Rename VS2 section to RAG2 in test file
2. Update `executeMongoVectorSearchWithLookup` to add `$group` by week
3. Update `executeOracleJdbcVectorSearchWithJoin` to use GROUP BY with TRUNC
4. Add new result structure to capture weekly stats
5. Update report generation for RAG category

---

## RAG1: Multi-Hop Account Relationships (Native Graph APIs)

### Query Pattern
```
Vector Search → Find top-K similar accounts
    → For each, traverse account relationship graph (1-2 hops)
    → Relationships: SAME_TENANT (shared tenantId), SHARED_MERCHANT (transact at same merchant)
```

### MongoDB: $graphLookup

MongoDB's `$graphLookup` performs recursive graph traversal in a single stage, replacing multiple `$lookup` operations.

```javascript
[
  // Stage 1: Vector search for seed accounts
  { $vectorSearch: {
      index: "accounts_vector_idx",
      path: "embedding",
      queryVector: [...],
      numCandidates: 100,
      limit: 10
  }},
  { $addFields: { vectorScore: { $meta: "vectorSearchScore" } } },

  // Stage 2: Graph traversal - same tenant accounts (1-2 hops)
  { $graphLookup: {
      from: "benchmark_accounts_vec",
      startWith: "$tenantId",
      connectFromField: "tenantId",
      connectToField: "tenantId",
      as: "sameTenantAccounts",
      maxDepth: 1,
      depthField: "hops",
      restrictSearchWithMatch: {
        $expr: { $ne: ["$_id", "$$ROOT._id"] }  // Exclude self
      }
  }},

  // Stage 3: Get merchants for this account
  { $lookup: {
      from: "benchmark_transactions_vec",
      let: { accId: "$accountId" },
      pipeline: [
        { $match: { $expr: { $eq: ["$accountId", "$$accId"] } } },
        { $group: { _id: "$merchant" } }
      ],
      as: "myMerchants"
  }},

  // Stage 4: Graph traversal - accounts sharing merchants (via transactions)
  { $graphLookup: {
      from: "benchmark_transactions_vec",
      startWith: "$myMerchants._id",
      connectFromField: "merchant",
      connectToField: "merchant",
      as: "sharedMerchantTxns",
      maxDepth: 1,
      depthField: "hops",
      restrictSearchWithMatch: {
        $expr: { $ne: ["$accountId", "$$ROOT.accountId"] }  // Exclude self
      }
  }},

  // Stage 5: Extract unique accounts from shared merchant transactions
  { $addFields: {
      sharedMerchantAccounts: {
        $setUnion: [{ $map: { input: "$sharedMerchantTxns", as: "t", in: "$$t.accountId" } }]
      }
  }},

  // Stage 6: Clean up intermediate fields
  { $project: {
      myMerchants: 0,
      sharedMerchantTxns: 0
  }}
]
```

**Key $graphLookup Parameters:**
- `startWith`: Initial value(s) to begin traversal
- `connectFromField`: Field to match in found documents for next hop
- `connectToField`: Field to match against startWith/connectFromField
- `maxDepth`: Maximum hops (0 = direct match only, 1 = one hop, etc.)
- `depthField`: Output field containing hop count
- `restrictSearchWithMatch`: Filter documents during traversal

### Oracle SQL/PGQ: Property Graph + GRAPH_TABLE

Oracle SQL/PGQ uses declarative graph pattern matching with the `GRAPH_TABLE` operator.

#### Step 1: Create Property Graph (DDL - run once at setup)
```sql
-- Create property graph defining vertices and edges
CREATE OR REPLACE PROPERTY GRAPH account_network_graph
  VERTEX TABLES (
    -- Account vertices
    benchmark_accounts_vec AS account
      KEY (id)
      LABEL account
      PROPERTIES (
        id,
        JSON_VALUE(data, '$.accountId') AS account_id,
        JSON_VALUE(data, '$.tenantId') AS tenant_id,
        embedding
      )
  )
  EDGE TABLES (
    -- Same-tenant edges (derived from shared tenantId)
    (SELECT DISTINCT
        a1.id AS src_id,
        a2.id AS dst_id,
        a1.id || '-' || a2.id AS edge_id
     FROM benchmark_accounts_vec a1
     JOIN benchmark_accounts_vec a2
       ON JSON_VALUE(a1.data, '$.tenantId') = JSON_VALUE(a2.data, '$.tenantId')
       AND a1.id < a2.id  -- Avoid duplicates
    ) AS same_tenant
      KEY (edge_id)
      SOURCE KEY (src_id) REFERENCES account (id)
      DESTINATION KEY (dst_id) REFERENCES account (id)
      LABEL same_tenant,

    -- Shared-merchant edges (derived from transactions)
    (SELECT DISTINCT
        t1.account_id || '-' || t2.account_id AS edge_id,
        a1.id AS src_id,
        a2.id AS dst_id,
        t1.merchant AS shared_merchant
     FROM benchmark_transactions_vec t1
     JOIN benchmark_transactions_vec t2
       ON t1.merchant = t2.merchant
       AND t1.account_id < t2.account_id
     JOIN benchmark_accounts_vec a1
       ON JSON_VALUE(a1.data, '$.accountId') = t1.account_id
     JOIN benchmark_accounts_vec a2
       ON JSON_VALUE(a2.data, '$.accountId') = t2.account_id
    ) AS shared_merchant
      KEY (edge_id)
      SOURCE KEY (src_id) REFERENCES account (id)
      DESTINATION KEY (dst_id) REFERENCES account (id)
      LABEL shared_merchant
      PROPERTIES (shared_merchant)
  );
```

#### Step 2: Query with GRAPH_TABLE and Quantified Path Patterns
```sql
-- Vector search + graph traversal using GRAPH_TABLE
WITH vector_matches AS (
    -- Stage 1: Vector search for seed accounts
    SELECT id,
           JSON_VALUE(data, '$.accountId') AS account_id,
           VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE) AS distance
    FROM benchmark_accounts_vec
    ORDER BY distance
    FETCH FIRST ? ROWS ONLY
)
SELECT
    vm.id AS source_id,
    vm.account_id AS source_account,
    vm.distance AS vector_distance,
    gt.target_id,
    gt.target_account_id,
    gt.relationship_type,
    gt.hop_count
FROM vector_matches vm,
     GRAPH_TABLE(account_network_graph
       MATCH (src IS account WHERE src.id = vm.id)
             -[e IS same_tenant | shared_merchant]-{1,2}  -- 1-2 hops, any edge type
             (dst IS account)
       COLUMNS (
         src.id AS source_id,
         dst.id AS target_id,
         dst.account_id AS target_account_id,
         LISTAGG(DISTINCT CASE
           WHEN e IS same_tenant THEN 'SAME_TENANT'
           WHEN e IS shared_merchant THEN 'SHARED_MERCHANT'
         END, ',') AS relationship_type,
         COUNT(e.*) AS hop_count
       )
     ) gt
WHERE gt.target_id != vm.id  -- Exclude self
ORDER BY vm.distance, gt.hop_count;
```

**Key SQL/PGQ Features:**
- `MATCH (src)-[e]-{1,2}(dst)`: Pattern matching with quantified paths
- `{1,2}`: Quantified path pattern - match 1 to 2 edge hops
- `e IS same_tenant | shared_merchant`: Match either edge type
- `COLUMNS (...)`: Project graph pattern results as relational columns
- Graph definition is separate from query (like a view)

### Alternative Oracle Approach: Recursive CTE (if PGQ unavailable)
```sql
-- Fallback for older Oracle versions without SQL/PGQ
WITH RECURSIVE vector_matches AS (
    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
           JSON_VALUE(data, '$.tenantId') AS tenant_id
    FROM benchmark_accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE)
    FETCH FIRST ? ROWS ONLY
),
graph_traversal (source_id, current_id, current_account, relationship_path, hop_count) AS (
    -- Base case: start from vector matches
    SELECT id, id, account_id, CAST('' AS VARCHAR2(4000)), 0
    FROM vector_matches

    UNION ALL

    -- Recursive: traverse same-tenant relationships
    SELECT gt.source_id, a.id, JSON_VALUE(a.data, '$.accountId'),
           gt.relationship_path || '->SAME_TENANT', gt.hop_count + 1
    FROM graph_traversal gt
    JOIN benchmark_accounts_vec a
      ON JSON_VALUE(a.data, '$.tenantId') = (
           SELECT JSON_VALUE(data, '$.tenantId')
           FROM benchmark_accounts_vec WHERE id = gt.current_id
         )
    WHERE gt.hop_count < 2
      AND a.id != gt.current_id
      AND a.id != gt.source_id
)
SELECT DISTINCT source_id, current_id AS related_id, current_account,
       relationship_path, hop_count
FROM graph_traversal
WHERE hop_count > 0
ORDER BY source_id, hop_count;
```

### TDD Test Cases
1. `testRAG1_graphLookup_findsSameTenantAccounts` - Verify $graphLookup tenant traversal
2. `testRAG1_graphLookup_findsSharedMerchantAccounts` - Verify 2-hop merchant traversal
3. `testRAG1_graphLookup_respectsMaxDepth` - Verify hop limits work correctly
4. `testRAG1_graphTable_matchesMongoResults` - Verify Oracle GRAPH_TABLE returns equivalent results
5. `testRAG1_graphTraversal_excludesSelfReferences` - Verify no self-loops in either DB
6. `testRAG1_graphTraversal_performance` - Benchmark comparison

### Implementation Steps
1. **Setup Phase:**
   - Create Oracle property graph DDL script
   - Add graph creation to test setup (run once)
   - Verify $graphLookup is supported in MongoDB version

2. **MongoDB Implementation:**
   - Create `executeMongoGraphTraversal` method using `$graphLookup`
   - Handle `depthField` to track hop count
   - Use `restrictSearchWithMatch` for filtering during traversal

3. **Oracle Implementation:**
   - Create `executeOracleGraphTraversal` method using `GRAPH_TABLE`
   - Implement quantified path patterns `{1,2}` for variable hops
   - Add fallback to recursive CTE if PGQ not available

4. **Benchmark Runner:**
   - Add `runGraphTraversalBenchmark` method
   - Capture metrics: traversal time, nodes visited, relationships found

5. **Test Method:**
   - Add `testRAG1_multiHopAccountRelationships` with both implementations

### Performance Considerations

| Aspect | MongoDB $graphLookup | Oracle GRAPH_TABLE |
|--------|---------------------|-------------------|
| Index usage | Uses indexes on connectToField | Uses graph internal indexes |
| Memory | Loads traversed docs into memory | Query-time materialization |
| Max depth | Soft limit via maxDepth | Pattern quantifier {n,m} |
| Filtering | restrictSearchWithMatch (during) | WHERE clause (after) |
| Best for | Document traversal | Complex path patterns |

---

## RAG3: Customer 360 Profile Assembly

### Query Pattern
```
Vector Search → Find top-K similar accounts
    → Join all transactions
    → Calculate: total_spent, txn_count, avg_amount, days_since_last_activity
    → Group spending by category
    → Return complete customer profile
```

### MongoDB Pipeline
```javascript
[
  { $vectorSearch: { index: "accounts_vector_idx", path: "embedding", queryVector: [...], limit: 10 } },
  { $lookup: {
      from: "transactions",
      localField: "accountId",
      foreignField: "accountId",
      as: "allTransactions"
  }},
  { $addFields: {
      profile: {
        totalSpent: { $sum: "$allTransactions.amount" },
        transactionCount: { $size: "$allTransactions" },
        avgTransactionAmount: { $avg: "$allTransactions.amount" },
        lastActivityDate: { $max: "$allTransactions.transactionDate" },
        daysSinceLastActivity: {
          $dateDiff: {
            startDate: { $max: "$allTransactions.transactionDate" },
            endDate: "$$NOW",
            unit: "day"
          }
        },
        spendingByCategory: {
          $arrayToObject: {
            $map: {
              input: { $setUnion: "$allTransactions.category" },
              as: "cat",
              in: {
                k: "$$cat",
                v: { $sum: {
                  $filter: {
                    input: "$allTransactions",
                    cond: { $eq: ["$$this.category", "$$cat"] }
                  }
                }}
              }
            }
          }
        }
      }
  }},
  { $project: { allTransactions: 0 } }
]
```

### Oracle JDBC SQL
```sql
WITH top_accounts AS (
    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id
    FROM benchmark_accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE)
    FETCH FIRST ? ROWS ONLY
),
txn_stats AS (
    SELECT ta.id,
           COUNT(*) AS txn_count,
           SUM(t.amount) AS total_spent,
           AVG(t.amount) AS avg_amount,
           MAX(t.transaction_date) AS last_activity,
           TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)) AS days_since_activity
    FROM top_accounts ta
    LEFT JOIN benchmark_transactions_vec t ON t.account_id = ta.account_id
    GROUP BY ta.id
),
category_spending AS (
    SELECT ta.id, t.category, SUM(t.amount) AS category_total
    FROM top_accounts ta
    JOIN benchmark_transactions_vec t ON t.account_id = ta.account_id
    GROUP BY ta.id, t.category
)
SELECT ts.*, cs.category, cs.category_total
FROM txn_stats ts
LEFT JOIN category_spending cs ON cs.id = ts.id
ORDER BY ts.id, cs.category
```

### TDD Test Cases
1. `testRAG3_customer360_calculatesCorrectTotals` - Verify aggregation accuracy
2. `testRAG3_customer360_includesAllCategories` - Verify category breakdown
3. `testRAG3_customer360_calculatesDaysSinceActivity` - Verify date math
4. `testRAG3_customer360_performance` - Benchmark comparison

### Implementation Steps
1. Create `executeMongoCustomer360` method with `$facet` for multiple aggregations
2. Create `executeOracleJdbcCustomer360` method with multiple CTEs
3. Add `runCustomer360Benchmark` runner
4. Add test method `testRAG3_customer360Profile`

---

## RAG4: Activity Pattern Detection

### Query Pattern
```
Vector Search → Find top-K similar accounts
    → Join transactions from last 90 days
    → Calculate 7-day rolling transaction count
    → Flag periods: burst (>2x avg) or dormant (0 txns)
```

### MongoDB Pipeline
```javascript
[
  { $vectorSearch: { index: "accounts_vector_idx", path: "embedding", queryVector: [...], limit: 10 } },
  { $lookup: {
      from: "transactions",
      let: { accId: "$accountId" },
      pipeline: [
        { $match: { $expr: { $and: [
          { $eq: ["$accountId", "$$accId"] },
          { $gte: ["$transactionDate", ninetyDaysAgo] }
        ]}}},
        { $sort: { transactionDate: 1 } },
        { $setWindowFields: {
            sortBy: { transactionDate: 1 },
            output: {
              rollingCount: {
                $count: {},
                window: { range: [-6, 0], unit: "day" }
              }
            }
        }},
        { $group: {
            _id: { $dateToString: { format: "%Y-%m-%d", date: "$transactionDate" } },
            dailyCount: { $sum: 1 },
            rollingWeekCount: { $max: "$rollingCount" }
        }}
      ],
      as: "activityPattern"
  }},
  { $addFields: {
      avgDailyTxns: { $avg: "$activityPattern.dailyCount" },
      burstDays: {
        $filter: {
          input: "$activityPattern",
          cond: { $gt: ["$$this.rollingWeekCount", { $multiply: [2, { $avg: "$activityPattern.dailyCount" }] }] }
        }
      },
      dormantPeriods: {
        $size: {
          $filter: {
            input: "$activityPattern",
            cond: { $eq: ["$$this.dailyCount", 0] }
          }
        }
      }
  }}
]
```

### Oracle JDBC SQL
```sql
WITH top_accounts AS (
    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id
    FROM benchmark_accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE)
    FETCH FIRST ? ROWS ONLY
),
daily_activity AS (
    SELECT ta.id, TRUNC(t.transaction_date) AS activity_date,
           COUNT(*) AS daily_count,
           COUNT(*) OVER (
             PARTITION BY ta.id
             ORDER BY TRUNC(t.transaction_date)
             RANGE BETWEEN 6 PRECEDING AND CURRENT ROW
           ) AS rolling_week_count
    FROM top_accounts ta
    JOIN benchmark_transactions_vec t ON t.account_id = ta.account_id
    WHERE t.transaction_date >= SYSDATE - 90
    GROUP BY ta.id, TRUNC(t.transaction_date)
),
account_stats AS (
    SELECT id, AVG(daily_count) AS avg_daily_txns
    FROM daily_activity
    GROUP BY id
)
SELECT da.*, ast.avg_daily_txns,
       CASE WHEN da.rolling_week_count > 2 * ast.avg_daily_txns THEN 'BURST'
            WHEN da.daily_count = 0 THEN 'DORMANT'
            ELSE 'NORMAL' END AS activity_status
FROM daily_activity da
JOIN account_stats ast ON ast.id = da.id
ORDER BY da.id, da.activity_date
```

### TDD Test Cases
1. `testRAG4_activityPattern_calculatesRollingWindow` - Verify 7-day window
2. `testRAG4_activityPattern_identifiesBurstPeriods` - Verify >2x detection
3. `testRAG4_activityPattern_identifiesDormantPeriods` - Verify 0-count detection
4. `testRAG4_activityPattern_performance` - Benchmark comparison

### Implementation Steps
1. Create `executeMongoActivityPattern` method with `$setWindowFields`
2. Create `executeOracleJdbcActivityPattern` method with analytic functions
3. Add `runActivityPatternBenchmark` runner
4. Add test method `testRAG4_activityPatternDetection`

---

## RAG5: Hybrid Context Ranking

### Query Pattern
```
Vector Search → Find top-50 candidates (over-fetch)
    → Join transaction statistics
    → Compute hybrid score: 0.5*vector_sim + 0.3*normalized_txn_activity + 0.2*recency_score
    → Re-rank and return top-10
```

### MongoDB Pipeline
```javascript
[
  { $vectorSearch: { index: "accounts_vector_idx", path: "embedding", queryVector: [...], numCandidates: 500, limit: 50 } },
  { $addFields: { vectorScore: { $meta: "vectorSearchScore" } } },
  { $lookup: {
      from: "transactions",
      let: { accId: "$accountId" },
      pipeline: [
        { $match: { $expr: { $eq: ["$accountId", "$$accId"] } } },
        { $group: {
            _id: null,
            txnCount: { $sum: 1 },
            lastTxnDate: { $max: "$transactionDate" }
        }}
      ],
      as: "txnStats"
  }},
  { $addFields: {
      txnCount: { $ifNull: [{ $arrayElemAt: ["$txnStats.txnCount", 0] }, 0] },
      daysSinceLastTxn: {
        $dateDiff: {
          startDate: { $ifNull: [{ $arrayElemAt: ["$txnStats.lastTxnDate", 0] }, new Date(0)] },
          endDate: "$$NOW",
          unit: "day"
        }
      }
  }},
  { $setWindowFields: {
      output: {
        maxTxnCount: { $max: "$txnCount" },
        minDaysSince: { $min: "$daysSinceLastTxn" },
        maxDaysSince: { $max: "$daysSinceLastTxn" }
      }
  }},
  { $addFields: {
      normalizedTxnActivity: { $divide: ["$txnCount", { $max: ["$maxTxnCount", 1] }] },
      recencyScore: {
        $subtract: [1, {
          $divide: [
            { $subtract: ["$daysSinceLastTxn", "$minDaysSince"] },
            { $max: [{ $subtract: ["$maxDaysSince", "$minDaysSince"] }, 1] }
          ]
        }]
      }
  }},
  { $addFields: {
      hybridScore: {
        $add: [
          { $multiply: [0.5, "$vectorScore"] },
          { $multiply: [0.3, "$normalizedTxnActivity"] },
          { $multiply: [0.2, "$recencyScore"] }
        ]
      }
  }},
  { $sort: { hybridScore: -1 } },
  { $limit: 10 },
  { $project: { txnStats: 0, maxTxnCount: 0, minDaysSince: 0, maxDaysSince: 0 } }
]
```

### Oracle JDBC SQL
```sql
WITH candidates AS (
    SELECT id, JSON_VALUE(data, '$.accountId') AS account_id,
           1 - VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE) AS vector_score
    FROM benchmark_accounts_vec
    ORDER BY VECTOR_DISTANCE(embedding, TO_VECTOR(?, 384, FLOAT64), COSINE)
    FETCH FIRST 50 ROWS ONLY
),
txn_stats AS (
    SELECT c.id,
           COUNT(t.transaction_id) AS txn_count,
           TRUNC(SYSDATE) - TRUNC(MAX(t.transaction_date)) AS days_since_last
    FROM candidates c
    LEFT JOIN benchmark_transactions_vec t ON t.account_id = c.account_id
    GROUP BY c.id
),
normalized AS (
    SELECT c.id, c.account_id, c.vector_score,
           ts.txn_count,
           ts.days_since_last,
           ts.txn_count / NULLIF(MAX(ts.txn_count) OVER (), 1) AS normalized_txn,
           1 - ((ts.days_since_last - MIN(ts.days_since_last) OVER ()) /
                NULLIF(MAX(ts.days_since_last) OVER () - MIN(ts.days_since_last) OVER (), 1)) AS recency_score
    FROM candidates c
    JOIN txn_stats ts ON ts.id = c.id
)
SELECT id, account_id, vector_score, txn_count, days_since_last,
       (0.5 * vector_score + 0.3 * normalized_txn + 0.2 * recency_score) AS hybrid_score
FROM normalized
ORDER BY hybrid_score DESC
FETCH FIRST 10 ROWS ONLY
```

### TDD Test Cases
1. `testRAG5_hybridRanking_reordersResults` - Verify re-ranking changes order
2. `testRAG5_hybridRanking_weightsAppliedCorrectly` - Verify 0.5/0.3/0.2 weights
3. `testRAG5_hybridRanking_normalizesScores` - Verify 0-1 normalization
4. `testRAG5_hybridRanking_performance` - Benchmark comparison

### Implementation Steps
1. Create `executeMongoHybridRanking` method with `$setWindowFields` for normalization
2. Create `executeOracleJdbcHybridRanking` method with window functions
3. Add `runHybridRankingBenchmark` runner
4. Add test method `testRAG5_hybridContextRanking`

---

## Implementation Order (TDD)

### Phase 1: RAG2 (Enhance VS2)
1. Write failing unit tests for weekly aggregation
2. Update MongoDB lookup to add `$group` by `$isoWeek`
3. Update Oracle JDBC to use `GROUP BY TRUNC(date, 'IW')`
4. Verify tests pass
5. Rename VS2 → RAG2 in display names and report

### Phase 2: RAG3 (Customer 360)
1. Write failing tests for profile assembly
2. Implement MongoDB pipeline with `$facet`
3. Implement Oracle JDBC with multiple CTEs
4. Verify tests pass

### Phase 3: RAG1 (Graph Traversal with Native APIs)
1. **Setup:** Create Oracle property graph DDL script (`scripts/create_account_graph.sql`)
2. **Setup:** Add graph creation to test setup (@BeforeClass or conditional)
3. Write failing tests for graph traversal (both $graphLookup and GRAPH_TABLE)
4. Implement MongoDB `$graphLookup` pipeline with maxDepth and depthField
5. Implement Oracle `GRAPH_TABLE` with quantified path patterns `{1,2}`
6. Add recursive CTE fallback for Oracle versions without PGQ
7. Verify tests pass and results match between databases

### Phase 4: RAG4 (Activity Pattern)
1. Write failing tests for rolling window detection
2. Implement MongoDB `$setWindowFields` pipeline
3. Implement Oracle analytic functions
4. Verify tests pass

### Phase 5: RAG5 (Hybrid Ranking)
1. Write failing tests for score fusion
2. Implement MongoDB normalization + hybrid score
3. Implement Oracle window functions for normalization
4. Verify tests pass

### Phase 6: Integration & Reporting
1. Update HTML report template for RAG category
2. Add RAG-specific metrics (relationship count, aggregation accuracy)
3. Run full benchmark suite
4. Generate comparative analysis

---

## Data Model Requirements

### Existing (No Changes Needed)
- `benchmark_accounts_vec`: id, accountId, tenantId, region, balance, embedding, etc.
- `benchmark_transactions_vec`: transactionId, accountId, transactionDate, amount, category, merchant

### Indexes Required
- MongoDB: `accounts_vector_idx` (existing)
- MongoDB: `{ accountId: 1 }` on transactions (may need to add)
- MongoDB: `{ tenantId: 1 }` on accounts (for $graphLookup)
- MongoDB: `{ merchant: 1 }` on transactions (for $graphLookup)
- Oracle: Vector index on embedding (existing)
- Oracle: Index on `transactions.account_id` (may need to add)

### Graph Setup (RAG1)

#### Oracle Property Graph
The property graph must be created before RAG1 tests run. Create script: `scripts/create_account_graph.sql`

**Graph Components:**
1. **Vertex Table:** `benchmark_accounts_vec` as `account`
   - Properties: id, account_id, tenant_id, embedding
2. **Edge Tables:**
   - `same_tenant`: Derived from accounts with matching tenantId
   - `shared_merchant`: Derived from transactions at same merchant

**Setup Timing:**
- Run once during test class setup
- Idempotent: Use `CREATE OR REPLACE PROPERTY GRAPH`
- Verify graph exists before RAG1 tests

#### MongoDB Graph Indexes
No explicit graph structure needed - `$graphLookup` works on existing collections.
Ensure these indexes exist for performance:
```javascript
db.benchmark_accounts_vec.createIndex({ tenantId: 1 })
db.benchmark_transactions_vec.createIndex({ merchant: 1 })
db.benchmark_transactions_vec.createIndex({ accountId: 1 })
```

---

## Success Criteria

1. All TDD tests pass (green)
2. Each RAG test produces meaningful timing comparisons
3. Report clearly shows RAG query patterns and their performance characteristics
4. No regression in existing VS tests (VS1, VS3-VS6)
