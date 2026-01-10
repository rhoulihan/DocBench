# Plan: BsonDocument vs Document Deserialization Performance Tests

## Overview

This plan proposes a new benchmark test class to measure the performance trade-offs between MongoDB's document wrapper types, with Oracle OSON as a baseline:

| Type | Deserialization | Field Access | Notes |
|------|-----------------|--------------|-------|
| **RawBsonDocument** | None (lazy) | O(n) sequential scan | No upfront cost |
| **BsonDocument** | Upfront cost | O(1) hash lookup | Decode via codec |
| **Document** | Upfront cost | O(1) hash lookup | Simpler API |
| **OracleJsonObject** | None needed | O(1) hash lookup | Baseline comparison |

**Goal**: Determine the **break-even point** - how many field accesses justify the upfront deserialization cost.

---

## Key Metrics to Measure

### 1. Deserialization Latency
- Time to convert `RawBsonDocument` → `BsonDocument`
- Time to convert `RawBsonDocument` → `Document`
- Across document sizes: 100, 500, 1000 fields
- Flat and nested structures

### 2. Field Access Cost (All Four Types)
- RawBsonDocument.get() - O(n)
- BsonDocument.get() - O(1)
- Document.get() - O(1)
- OracleJsonObject.get() - O(1) baseline

### 3. Break-Even Analysis
- **Same field repeated**: Access the same field N times
- **Different fields**: Access N different fields sequentially
- Calculate crossover point where deserialization pays off

---

## Proposed Test Class

**File**: `src/integrationTest/java/com/docbench/benchmark/DeserializationOverheadTest.java`

```java
@DisplayName("Deserialization Overhead: RawBsonDocument vs BsonDocument vs Document")
@Tag("benchmark")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeserializationOverheadTest {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 100_000;

    // Document sizes
    private static final int[] FIELD_COUNTS = {100, 500, 1000};

    // Access counts for break-even analysis
    private static final int[] ACCESS_COUNTS = {1, 2, 5, 10, 25, 50, 100};

    // Results - integrated into existing report
    private static final Map<String, TestResult> results = new LinkedHashMap<>();
}
```

---

## Test Categories

### Category 1: Deserialization Cost

Measure pure deserialization time from RawBsonDocument:

| Test ID | Description |
|---------|-------------|
| `deser-bson-100` | RawBsonDocument → BsonDocument (100 fields) |
| `deser-bson-500` | RawBsonDocument → BsonDocument (500 fields) |
| `deser-bson-1000` | RawBsonDocument → BsonDocument (1000 fields) |
| `deser-doc-100` | RawBsonDocument → Document (100 fields) |
| `deser-doc-500` | RawBsonDocument → Document (500 fields) |
| `deser-doc-1000` | RawBsonDocument → Document (1000 fields) |

**Implementation**:
```java
// BsonDocument deserialization
BsonDocumentCodec codec = new BsonDocumentCodec();
long start = System.nanoTime();
BsonDocument bson = codec.decode(raw.asBsonReader(), DecoderContext.builder().build());
long time = System.nanoTime() - start;

// Document deserialization
DocumentCodec docCodec = new DocumentCodec();
Document doc = docCodec.decode(raw.asBsonReader(), DecoderContext.builder().build());
```

### Category 2: Single Access Comparison (All 4 Types)

Compare single field access across all document types, with Oracle as baseline:

| Test ID | Description |
|---------|-------------|
| `access-raw-1-1000` | RawBsonDocument - first field (1000-field doc) |
| `access-raw-500-1000` | RawBsonDocument - middle field |
| `access-raw-1000-1000` | RawBsonDocument - last field |
| `access-bson-1-1000` | BsonDocument - first field |
| `access-bson-500-1000` | BsonDocument - middle field |
| `access-bson-1000-1000` | BsonDocument - last field |
| `access-doc-1-1000` | Document - first field |
| `access-doc-500-1000` | Document - middle field |
| `access-doc-1000-1000` | Document - last field |
| `access-oson-1-1000` | OracleJsonObject - first field (baseline) |
| `access-oson-500-1000` | OracleJsonObject - middle field (baseline) |
| `access-oson-1000-1000` | OracleJsonObject - last field (baseline) |

### Category 3: Repeated Same-Field Access

Access the **same field** N times - tests caching behavior:

| Test ID | Description |
|---------|-------------|
| `repeat-same-raw-5` | RawBsonDocument - same field 5x |
| `repeat-same-raw-25` | RawBsonDocument - same field 25x |
| `repeat-same-raw-100` | RawBsonDocument - same field 100x |
| `repeat-same-bson-5` | BsonDocument - same field 5x |
| `repeat-same-bson-25` | BsonDocument - same field 25x |
| `repeat-same-bson-100` | BsonDocument - same field 100x |
| `repeat-same-doc-5` | Document - same field 5x |
| `repeat-same-doc-25` | Document - same field 25x |
| `repeat-same-doc-100` | Document - same field 100x |
| `repeat-same-oson-5` | OracleJsonObject - same field 5x (baseline) |
| `repeat-same-oson-25` | OracleJsonObject - same field 25x (baseline) |
| `repeat-same-oson-100` | OracleJsonObject - same field 100x (baseline) |

### Category 4: Different Fields Access

Access **N different fields** sequentially - simulates real-world document processing:

| Test ID | Description |
|---------|-------------|
| `repeat-diff-raw-5` | RawBsonDocument - 5 different fields |
| `repeat-diff-raw-25` | RawBsonDocument - 25 different fields |
| `repeat-diff-raw-100` | RawBsonDocument - 100 different fields |
| `repeat-diff-bson-5` | BsonDocument - 5 different fields |
| `repeat-diff-bson-25` | BsonDocument - 25 different fields |
| `repeat-diff-bson-100` | BsonDocument - 100 different fields |
| `repeat-diff-doc-5` | Document - 5 different fields |
| `repeat-diff-doc-25` | Document - 25 different fields |
| `repeat-diff-doc-100` | Document - 100 different fields |
| `repeat-diff-oson-5` | OracleJsonObject - 5 different fields (baseline) |
| `repeat-diff-oson-25` | OracleJsonObject - 25 different fields (baseline) |
| `repeat-diff-oson-100` | OracleJsonObject - 100 different fields (baseline) |

### Category 5: Break-Even Analysis

Compare **total time** for fetch + N accesses across strategies:

| Strategy | Total Time Formula |
|----------|-------------------|
| RawBsonDocument | `N × rawAccessTime` |
| BsonDocument | `deserializeToBsonTime + (N × bsonAccessTime)` |
| Document | `deserializeToDocTime + (N × docAccessTime)` |
| OracleJsonObject | `N × osonAccessTime` (baseline) |

**Tests** (for each document size 100, 500, 1000):

| Test ID | Description |
|---------|-------------|
| `breakeven-100-same` | 100-field doc, same field repeated |
| `breakeven-100-diff` | 100-field doc, different fields |
| `breakeven-500-same` | 500-field doc, same field repeated |
| `breakeven-500-diff` | 500-field doc, different fields |
| `breakeven-1000-same` | 1000-field doc, same field repeated |
| `breakeven-1000-diff` | 1000-field doc, different fields |

### Category 6: Nested Document Tests

| Test ID | Description |
|---------|-------------|
| `nested-deser-depth3` | Deserialization cost - 3 levels deep |
| `nested-deser-depth5` | Deserialization cost - 5 levels deep |
| `nested-access-depth3` | Access nested field - 3 levels |
| `nested-access-depth5` | Access nested field - 5 levels |

---

## Expected Output Format

### Console Output

```
================================================================================
  DESERIALIZATION OVERHEAD BENCHMARK
  RawBsonDocument vs BsonDocument vs Document (Oracle OSON baseline)
================================================================================

--- Deserialization Cost (one-time) ---
  100 fields → BsonDocument:    12,345 ns
  100 fields → Document:        11,234 ns
  500 fields → BsonDocument:    45,678 ns
  500 fields → Document:        42,345 ns
  1000 fields → BsonDocument:   89,012 ns
  1000 fields → Document:       85,678 ns

--- Single Field Access (1000-field document, middle position) ---
  Type              Access Time    vs Oracle
  RawBsonDocument   5,678 ns       126.2x slower
  BsonDocument      48 ns          1.07x slower
  Document          44 ns          0.98x (same)
  OracleJsonObject  45 ns          baseline

--- Break-Even Analysis: Same Field Repeated (1000 fields, middle position) ---
  Accesses  RawBson     BsonDoc      Document     Oracle      Winner
  1         5,678 ns    89,060 ns    85,722 ns    45 ns       Oracle
  2         11,356 ns   89,108 ns    85,766 ns    90 ns       Oracle
  5         28,390 ns   89,252 ns    85,898 ns    225 ns      Oracle
  10        56,780 ns   89,492 ns    86,118 ns    450 ns      Oracle
  25        141,950 ns  90,207 ns    86,778 ns    1,125 ns    Oracle
  50        283,900 ns  91,407 ns    87,878 ns    2,250 ns    Document ← MongoDB break-even
  100       567,800 ns  93,807 ns    90,078 ns    4,500 ns    Document

  BREAK-EVEN POINT (vs RawBsonDocument):
    BsonDocument wins after ~16 same-field accesses
    Document wins after ~15 same-field accesses

--- Break-Even Analysis: Different Fields (1000 fields) ---
  Accesses  RawBson     BsonDoc      Document     Oracle      Winner
  5         28,390 ns   89,252 ns    85,898 ns    225 ns      Oracle
  25        141,950 ns  90,207 ns    86,778 ns    1,125 ns    Document
  100       567,800 ns  93,807 ns    90,078 ns    4,500 ns    Document

  BREAK-EVEN POINT (vs RawBsonDocument):
    BsonDocument wins after ~18 different-field accesses
    Document wins after ~17 different-field accesses

================================================================================
```

### HTML Report Section (appended to existing report)

The test will append a new section to `reports/performance_report.html`:

```html
<div class="section">
    <h2>Deserialization Overhead Analysis</h2>
    <p class="note">Compares MongoDB document wrapper strategies with Oracle OSON as baseline</p>

    <h3>Deserialization Cost</h3>
    <table>
        <tr><th>Document Size</th><th>→ BsonDocument</th><th>→ Document</th></tr>
        <tr><td>100 fields</td><td>12,345 ns</td><td>11,234 ns</td></tr>
        ...
    </table>

    <h3>Break-Even Summary</h3>
    <div class="summary-box">
        <p><strong>Same Field Repeated:</strong> Deserialize after ~16 accesses</p>
        <p><strong>Different Fields:</strong> Deserialize after ~18 accesses</p>
        <p><strong>Recommendation:</strong> For documents accessed 20+ times,
           pre-deserialize to Document for optimal performance.</p>
    </div>

    <h3>Detailed Results</h3>
    <table>
        <tr><th>Access Count</th><th>RawBson</th><th>BsonDoc</th><th>Document</th><th>Oracle</th><th>Winner</th></tr>
        ...
    </table>
</div>
```

---

## Implementation Steps

### Step 1: Create Test Class Skeleton
- Setup/teardown following existing patterns
- MongoDB and Oracle connection initialization
- Collection/table creation

### Step 2: Implement Document Creation Utilities
```java
private Document createFlatDocument(String id, int fieldCount) {
    Document doc = new Document("_id", id);
    for (int i = 1; i <= fieldCount; i++) {
        doc.append("field_" + String.format("%03d", i),
                   "value_" + i + "_padding_for_size");
    }
    return doc;
}

private Document createNestedDocument(String id, int depth) {
    // Creates root.nested.nested...target structure
}
```

### Step 3: Implement Measurement Methods
```java
// Deserialization measurement
private long measureDeserializeToBsonDocument(RawBsonDocument raw) {
    BsonDocumentCodec codec = new BsonDocumentCodec();
    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        codec.decode(raw.asBsonReader(), DecoderContext.builder().build());
    }
    // Measure
    long total = 0;
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        long start = System.nanoTime();
        codec.decode(raw.asBsonReader(), DecoderContext.builder().build());
        total += System.nanoTime() - start;
    }
    return total / MEASUREMENT_ITERATIONS;
}

// Access measurement (all types)
private long measureRawBsonAccess(RawBsonDocument raw, String field, int accessCount);
private long measureBsonDocAccess(BsonDocument bson, String field, int accessCount);
private long measureDocumentAccess(Document doc, String field, int accessCount);
private long measureOracleAccess(OracleJsonObject oson, String field, int accessCount);

// Multi-field access
private long measureRawBsonMultiAccess(RawBsonDocument raw, List<String> fields);
private long measureBsonDocMultiAccess(BsonDocument bson, List<String> fields);
private long measureDocumentMultiAccess(Document doc, List<String> fields);
private long measureOracleMultiAccess(OracleJsonObject oson, List<String> fields);
```

### Step 4: Implement Break-Even Analysis
```java
private void runBreakEvenAnalysis(int fieldCount, boolean sameField) {
    // Fetch documents once
    RawBsonDocument raw = fetchRaw(fieldCount);
    BsonDocument bson = deserializeToBson(raw);
    Document doc = deserializeToDoc(raw);
    OracleJsonObject oson = fetchOracleDoc(fieldCount);

    // Get deserialization costs
    long bsonDeserCost = measureDeserializeToBsonDocument(raw);
    long docDeserCost = measureDeserializeToDocument(raw);

    for (int accessCount : ACCESS_COUNTS) {
        List<String> fields = sameField
            ? Collections.nCopies(accessCount, "field_500")  // Same field
            : generateFieldList(accessCount);                 // Different fields

        long rawTime = measureRawBsonMultiAccess(raw, fields);
        long bsonAccessTime = measureBsonDocMultiAccess(bson, fields);
        long docAccessTime = measureDocumentMultiAccess(doc, fields);
        long osonTime = measureOracleMultiAccess(oson, fields);

        // Total time including deserialization
        long bsonTotal = bsonDeserCost + bsonAccessTime;
        long docTotal = docDeserCost + docAccessTime;

        recordBreakEvenResult(fieldCount, accessCount, sameField,
                              rawTime, bsonTotal, docTotal, osonTime);
    }
}
```

### Step 5: Generate Report Section
- Read existing `performance_report.html`
- Insert new section before closing `</div></body>`
- Write updated HTML

---

## Data Structures

```java
// Reuse existing pattern
private record TestResult(
    String testId,
    String description,
    long mongoNanos,      // Will store RawBsonDocument time
    long oracleNanos,     // Oracle baseline
    String testType
) {}

// New records for extended data
private record DeserializationResult(
    int fieldCount,
    long toBsonDocNanos,
    long toDocumentNanos
) {}

private record BreakEvenResult(
    int fieldCount,
    int accessCount,
    boolean sameField,
    long rawBsonNanos,
    long bsonDocTotalNanos,
    long documentTotalNanos,
    long oracleNanos,
    String winner,
    int breakEvenPoint  // Calculated crossover
) {}
```

---

## Test Execution Order

```
@Order(1-6)   Deserialization cost tests
@Order(10-21) Single access comparison tests
@Order(30-41) Same-field repeated access tests
@Order(50-61) Different-field access tests
@Order(70-75) Break-even analysis tests
@Order(80-83) Nested document tests
@Order(99)    Generate report section
```

---

## Files to Create/Modify

| File | Action |
|------|--------|
| `src/integrationTest/java/com/docbench/benchmark/DeserializationOverheadTest.java` | **CREATE** |
| `reports/performance_report.html` | **MODIFY** (append section) |
| `README.md` | **UPDATE** (document new test) |

---

## Test Count Summary

| Category | Tests |
|----------|-------|
| Deserialization cost | 6 |
| Single access (4 types × 3 positions) | 12 |
| Same-field repeated (4 types × 3 counts) | 12 |
| Different-field access (4 types × 3 counts) | 12 |
| Break-even analysis (3 sizes × 2 patterns) | 6 |
| Nested documents | 4 |
| **Total** | **~52 test methods** |

---

## Key Insights Expected

1. **Oracle OSON is the fastest** - No deserialization needed, O(1) access
2. **RawBsonDocument has hidden cost** - O(n) access accumulates quickly
3. **Document/BsonDocument break-even** - Expected around 15-20 accesses
4. **Same vs different fields** - RawBsonDocument may cache, affecting break-even
5. **Document size matters** - Larger docs have higher deserialization cost but also higher RawBson access cost

---

## Approval Checklist

- [x] Document sizes: 100, 500, 1000 fields
- [x] Access patterns: Same field repeated AND different fields
- [x] Oracle baseline: Include OracleJsonObject in all tests
- [x] Report integration: Append to existing HTML report
- [x] Follow existing test patterns and measurement methodology
