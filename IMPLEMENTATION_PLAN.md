# DocBench Implementation Plan

## Test-Driven Development Implementation Strategy

**Architect**: Senior Java Architect (25 years experience)
**Methodology**: Strict TDD with Red-Green-Refactor cycles
**Target Runtime**: Java 21+ (Virtual Threads)

---

## Guiding Principles

### TDD Rules (Kent Beck's Three Laws)
1. **Red**: Write a failing test before writing any production code
2. **Green**: Write the minimum production code to make the test pass
3. **Refactor**: Clean up the code while keeping tests green

### Architectural Constraints
- All public APIs must have tests written FIRST
- No production code without a corresponding test
- Mutation testing (PIT) score > 60% required
- Test isolation: No shared state between tests
- Constructor injection for all dependencies

---

## Phase 1: Foundation (Core Interfaces & Metrics)

### 1.1 Project Setup
```
docbench/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/
    ├── main/java/com/docbench/
    └── test/java/com/docbench/
```

**Dependencies**:
- Java 21
- JUnit 5.10+
- Mockito 5+
- AssertJ 3.24+
- HdrHistogram 2.1+
- picocli 4.7+
- Guice 7+
- SnakeYAML 2+
- TestContainers 1.19+
- PIT Mutation Testing

### 1.2 Core Interfaces (Test-First)

#### Order of Implementation:

1. **Duration utilities** (simplest, no dependencies)
   - Test: `DurationUtilsTest` - formatting, parsing
   - Impl: `DurationUtils`

2. **TimeSource abstraction** (enables testable timing)
   - Test: `TimeSourceTest` - mock time progression
   - Impl: `TimeSource` interface + `SystemTimeSource`

3. **RandomSource abstraction** (reproducibility)
   - Test: `RandomSourceTest` - seeded determinism
   - Impl: `RandomSource` interface + `SeededRandomSource`

4. **OverheadBreakdown record** (central data structure)
   - Test: `OverheadBreakdownTest` - derived calculations
   - Impl: Immutable record with validation

5. **MetricsCollector** (core measurement)
   - Test: `MetricsCollectorTest` - accumulation, summarization
   - Impl: HdrHistogram integration

6. **Capability enum** (adapter contract)
   - Test: `CapabilityTest` - set operations
   - Impl: Enum with compatibility checks

7. **Operation sealed interface hierarchy**
   - Test: `OperationTest` - type safety, serialization
   - Impl: Sealed permits for type-safe dispatch

8. **DatabaseAdapter SPI**
   - Test: `DatabaseAdapterContractTest` - abstract test class
   - Impl: Interface with default methods

9. **Configuration loading**
   - Test: `ConfigLoaderTest` - YAML parsing, validation, env vars
   - Impl: `ConfigLoader` with validation

10. **CLI commands structure**
    - Test: `CliCommandTest` - argument parsing
    - Impl: picocli command hierarchy

### 1.3 TDD Cycle for OverheadBreakdown

```java
// RED: Write failing test first
@Test
void traversalOverhead_shouldSumServerAndClientTraversal() {
    var breakdown = new OverheadBreakdown(
        Duration.ofMicros(1000),  // total
        Duration.ofMicros(50),    // connAcq
        Duration.ofMicros(50),    // connRel
        Duration.ofMicros(100),   // serialization
        Duration.ofMicros(75),    // wireTransmit
        Duration.ofMicros(400),   // serverExec
        Duration.ofMicros(50),    // serverParse
        Duration.ofMicros(200),   // serverTraversal  <-- 200
        Duration.ofMicros(0),     // serverIndex
        Duration.ofMicros(100),   // serverFetch
        Duration.ofMicros(75),    // wireReceive
        Duration.ofMicros(80),    // deserialization
        Duration.ofMicros(20),    // clientTraversal  <-- 20
        Map.of()
    );

    assertThat(breakdown.traversalOverhead())
        .isEqualTo(Duration.ofMicros(220));  // 200 + 20
}

// GREEN: Implement minimum code
public Duration traversalOverhead() {
    return serverTraversalTime.plus(clientTraversalTime);
}

// REFACTOR: No refactoring needed for simple calculation
```

---

## Phase 2: MongoDB Adapter

### 2.1 Test Infrastructure Setup
- TestContainers MongoDB configuration
- Test data builders (fluent API)
- Integration test base class

### 2.2 TDD Sequence

1. **MongoDBConnectionConfig**
   - Test: URI parsing, validation, defaults
   - Impl: Immutable builder pattern

2. **InstrumentedDocumentCodec**
   - Test: Mock BsonReader, verify field scanning capture
   - Impl: Decorator around standard codec

3. **MongoDBTimingInterceptor**
   - Test: CommandListener event capture
   - Impl: Thread-safe timing accumulation

4. **MongoDBInstrumentedConnection**
   - Test: Connection lifecycle, metrics reset
   - Impl: Wrapper with timing hooks

5. **MongoDBAdapter.connect()**
   - Integration Test: TestContainers connection
   - Impl: MongoClient configuration

6. **MongoDBAdapter.execute() - InsertOperation**
   - Integration Test: Insert and verify
   - Impl: BSON serialization with timing

7. **MongoDBAdapter.execute() - ReadOperation**
   - Integration Test: Read with projection
   - Impl: Explain plan extraction, timing capture

8. **MongoDBAdapter.getOverheadBreakdown()**
   - Integration Test: Verify decomposition accuracy
   - Impl: OperationResult transformation

### 2.3 BSON Traversal Timing Strategy

```java
// Test that field position affects traversal time
@Test
void bsonTraversal_shouldIncreaseWithFieldPosition() {
    // Given: Documents with target field at positions 1, 50, 100
    var doc1 = createDocumentWithTargetAt(1, 100);
    var doc50 = createDocumentWithTargetAt(50, 100);
    var doc100 = createDocumentWithTargetAt(100, 100);

    // When: Access target field
    var time1 = measureFieldAccess(doc1, "target");
    var time50 = measureFieldAccess(doc50, "target");
    var time100 = measureFieldAccess(doc100, "target");

    // Then: Later positions should take longer (BSON O(n) characteristic)
    assertThat(time50).isGreaterThan(time1);
    assertThat(time100).isGreaterThan(time50);
}
```

---

## Phase 3: Oracle OSON Adapter

### 3.1 Test Infrastructure
- TestContainers Oracle Free 23ai
- JDBC connection pool setup
- SODA API test utilities

### 3.2 TDD Sequence

1. **OracleConnectionConfig**
   - Test: JDBC URL parsing, credential handling
   - Impl: Secure builder with env var support

2. **OracleInstrumentedConnection**
   - Test: OSON format enablement, statement metrics
   - Impl: OracleConnection wrapper

3. **OracleOSONAdapter.connect()**
   - Integration Test: Connection with OSON enabled
   - Impl: DataSource configuration

4. **OracleOSONAdapter.execute() - InsertOperation**
   - Integration Test: JSON document insertion
   - Impl: PreparedStatement with JSON binding

5. **OracleOSONAdapter.execute() - ReadOperation**
   - Integration Test: SQL/JSON query with projection
   - Impl: JSON_VALUE/JSON_QUERY optimization

6. **SODA API Alternative**
   - Integration Test: OracleCollection operations
   - Impl: Document-oriented API

7. **OracleOSONAdapter.getOverheadBreakdown()**
   - Integration Test: Server CPU time extraction
   - Impl: OracleStatement metrics

### 3.3 OSON Jump Navigation Verification

```java
// Test that field position does NOT affect OSON traversal time
@Test
void osonTraversal_shouldRemainConstantRegardlessOfPosition() {
    // Given: Documents with target field at positions 1, 50, 100
    var doc1 = insertDocumentWithTargetAt(1, 100);
    var doc50 = insertDocumentWithTargetAt(50, 100);
    var doc100 = insertDocumentWithTargetAt(100, 100);

    // When: Access via JSON_VALUE (triggers OSON hash lookup)
    var times1 = measureMultipleAccesses(doc1, "target", 1000);
    var times50 = measureMultipleAccesses(doc50, "target", 1000);
    var times100 = measureMultipleAccesses(doc100, "target", 1000);

    // Then: All positions should have similar timing (O(1) characteristic)
    assertThat(times1.mean())
        .isCloseTo(times100.mean(), withinPercentage(15));
}
```

---

## Phase 4: Workloads & Reporting

### 4.1 Workload Implementation (TDD)

1. **WorkloadConfig**
   - Test: Parameter validation, defaults, sweeps
   - Impl: Type-safe configuration

2. **DocumentGenerator**
   - Test: Deterministic generation, schema compliance
   - Impl: Builder with seed support

3. **TraverseShallowWorkload**
   - Test: Operation generation, field position control
   - Impl: Single-level targeting

4. **TraverseDeepWorkload**
   - Test: Nested path generation, array access
   - Impl: Multi-level navigation

5. **TraverseScaleWorkload**
   - Test: Batch generation, access patterns
   - Impl: Volume testing

6. **DeserializeFullWorkload**
   - Test: Complete document access
   - Impl: No projection

7. **DeserializePartialWorkload**
   - Test: Projection ratio validation
   - Impl: Selective field access

### 4.2 Reporting Implementation (TDD)

1. **MetricsSummary**
   - Test: Aggregation calculations
   - Impl: Statistical summary

2. **ConsoleReporter**
   - Test: Table formatting, color codes
   - Impl: ANSI output

3. **JsonReporter**
   - Test: Schema compliance
   - Impl: Jackson serialization

4. **CsvReporter**
   - Test: Column ordering, escaping
   - Impl: Standard CSV format

5. **HtmlReporter**
   - Test: Valid HTML, chart data
   - Impl: Template-based generation

6. **ComparisonReport**
   - Test: Delta calculations, highlighting
   - Impl: Multi-adapter comparison

---

## Test Categories & Coverage

### Unit Tests (80%+ line coverage)
- All pure functions
- Record validations
- Configuration parsing
- Report formatting

### Integration Tests (TestContainers)
- MongoDB operations
- Oracle OSON operations
- End-to-end workflows

### Contract Tests
- Adapter SPI compliance
- Cross-adapter consistency

### Performance Tests (JMH)
- Instrumentation overhead < 1%
- Timing accuracy validation

### Mutation Testing (PIT)
- Target: 60%+ mutation score
- Focus: Core calculation logic

---

## Continuous Integration

### GitHub Actions Workflow
```yaml
name: CI

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Build
        run: ./gradlew build
      - name: Test
        run: ./gradlew test
      - name: Integration Test
        run: ./gradlew integrationTest
      - name: Mutation Test
        run: ./gradlew pitest
      - name: Upload Coverage
        uses: codecov/codecov-action@v3
```

---

## Implementation Timeline

### Sprint 1: Core Foundation
- [ ] Project scaffolding
- [ ] TimeSource, RandomSource
- [ ] OverheadBreakdown record
- [ ] MetricsCollector with HdrHistogram

### Sprint 2: SPI & Configuration
- [ ] DatabaseAdapter interface
- [ ] Operation hierarchy
- [ ] ConfigLoader
- [ ] CLI commands

### Sprint 3: MongoDB Adapter
- [ ] TestContainers setup
- [ ] InstrumentedDocumentCodec
- [ ] MongoDBAdapter implementation
- [ ] Integration tests

### Sprint 4: Oracle Adapter
- [ ] Oracle TestContainers setup
- [ ] OSON format handling
- [ ] OracleOSONAdapter implementation
- [ ] Integration tests

### Sprint 5: Workloads
- [ ] DocumentGenerator
- [ ] All workload implementations
- [ ] Workload registry

### Sprint 6: Reporting & CLI
- [ ] All reporter implementations
- [ ] Comparison logic
- [ ] CLI polish
- [ ] Documentation

---

## Definition of Done

Each component is "done" when:
1. All tests pass (unit + integration)
2. Code coverage > 80% lines
3. Mutation score > 60%
4. Javadoc complete for public APIs
5. No FindBugs/SpotBugs warnings
6. Code reviewed

---

*This plan ensures rigorous TDD discipline while building a production-quality benchmarking framework.*
