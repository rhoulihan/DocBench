# DocBench

**BSON vs OSON Client-Side Field Access Benchmark**

DocBench demonstrates the O(n) vs O(1) algorithmic complexity difference between MongoDB's BSON and Oracle's OSON binary JSON formats for client-side field access.

## The O(n) vs O(1) Problem

| Format | Traversal Strategy | Complexity |
|--------|-------------------|------------|
| BSON (MongoDB) | Sequential field-name scanning | O(n) per level |
| OSON (Oracle) | Hash-indexed jump navigation | O(1) per level |

At scale (large documents, deeply nested paths), this difference compounds significantly.

## Benchmark Results

Client-side field access comparison (100K iterations, network overhead eliminated):

```
================================================================================
  BSON O(n) vs OSON O(1) - Client-Side Field Access
================================================================================
Test Case                       BSON (ns)    OSON (ns)      Ratio
--------------------------------------------------------------------------------
Position 1/100                        380          101      3.76x OSON
Position 50/100                      1964           87     22.57x OSON
Position 100/100                     3238           56     57.82x OSON
Position 500/500                    15903          129    123.28x OSON
Position 1000/1000                  32169           75    428.92x OSON
Nested depth 1                        613          114      5.38x OSON
Nested depth 3                       1361          115     11.83x OSON
Nested depth 5                       2135          162     13.18x OSON
--------------------------------------------------------------------------------
TOTAL                               57763          839     68.85x OSON

O(n) Scaling: Position 1 -> 1000 = BSON time increased 84.66x
================================================================================
```

### Test Descriptions

| Test | Description | What It Proves |
|------|-------------|----------------|
| **Position 1/100** | Access first field in 100-field document | Best case for BSON (minimal scanning) |
| **Position 50/100** | Access middle field in 100-field document | BSON must skip 49 fields |
| **Position 100/100** | Access last field in 100-field document | Worst case: BSON scans all 99 prior fields |
| **Position 500/500** | Access last field in 500-field document | Scaling test with larger document |
| **Position 1000/1000** | Access last field in 1000-field document | Maximum O(n) penalty for BSON |
| **Nested depth 1** | Access `level1.value` | Single level of nesting |
| **Nested depth 3** | Access `level1.level2.level3.value` | Multi-level path traversal |
| **Nested depth 5** | Access 5-level nested path | Deep nesting compounds O(n) cost |

### Key Findings

- **BSON** (`RawBsonDocument.get`): O(n) sequential scanning—time increases with field position
- **OSON** (`OracleJsonObject.get`): O(1) hash lookup—constant ~60-130ns regardless of position
- At position 1000, OSON is **429x faster** than BSON
- Overall, OSON is **68.85x faster** for client-side field access

### BSON O(n) Scaling Proof

The `ClientSideAccessScalingTest` isolates BSON behavior:

```
RawBsonDocument.get() - Sequential BSON parsing:
Position        Time (ns)  Ratio to P1
----------------------------------------
1                     167        1.00x
100                  3185       19.07x
500                 16176       96.86x
999                 32673      195.65x
```

This confirms O(n) complexity: accessing position 999 takes **196x longer** than position 1.

## Quick Start

### Prerequisites

- Java 21+
- MongoDB 7.0+
- Oracle Database 23ai Free

### Configuration

Create `config/local.properties`:

```properties
# MongoDB
mongodb.uri=mongodb://user:pass@localhost:27017/testdb
mongodb.database=testdb

# Oracle 23ai
oracle.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1
oracle.username=docbench
oracle.password=your_password
```

### Run Benchmarks

```bash
# Run all benchmarks
./gradlew integrationTest

# Run BSON vs OSON comparison only
./gradlew integrationTest --tests "*.BsonVsOsonClientSideTest"

# Run BSON O(n) scaling test only
./gradlew integrationTest --tests "*.ClientSideAccessScalingTest"
```

## How It Works

The benchmark uses:
- **MongoDB**: `RawBsonDocument.get()` - parses raw BSON bytes on each access (O(n))
- **Oracle**: `OracleJsonObject.get()` - native OSON with hash-indexed lookup (O(1))

Both tests:
1. Fetch the full document once (network cost excluded from measurement)
2. Access a specific field 100,000 times
3. Measure only the client-side field access time

This isolates the **format-level** parsing cost, demonstrating why OSON's hash-indexed structure outperforms BSON's sequential layout.

### Why RawBsonDocument?

We use `RawBsonDocument` (not `Document`) because:
- `Document` uses `LinkedHashMap` internally → O(1) after initial parse
- `RawBsonDocument` parses BSON bytes on each `.get()` → O(n) every time

This reflects real-world scenarios where BSON parsing cost is paid:
- Server-side query processing
- Initial document parsing from network
- Re-parsing cached BSON bytes
- Change stream processing

## License

MIT License - see [LICENSE](LICENSE) file.
