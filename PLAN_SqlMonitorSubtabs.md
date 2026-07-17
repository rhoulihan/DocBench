# Plan: SQL Monitor Reports for All Oracle Protocols

## Current State
- SQL Monitor reports are captured ONLY for JDBC JSON queries
- Single `sqlMonitorHtml` field in `SqlDetails` record
- Subtabs in SQL Monitor tab are organized by test name, not protocol

## Goal
- Capture SQL Monitor reports for ALL three Oracle protocols:
  1. **JDBC JSON** - JSON queries via JDBC
  2. **Oracle API ($sql)** - MongoDB API with $sql aggregation
  3. **JDBC Relational** - Pure relational SQL via JDBC
- Organize SQL Monitor tab with protocol-based subtabs
- Each protocol subtab contains test-level sub-subtabs

## Implementation Plan

### Phase 1: Update Data Structures (TDD)

#### 1.1 Create new SqlDetails record with per-protocol SQL Monitor fields
**Test**: Verify SqlDetails can store three separate SQL Monitor HTML strings
**Implementation**: Replace single `sqlMonitorHtml` with three fields:
- `sqlMonitorJdbcJson` - JDBC JSON SQL Monitor
- `sqlMonitorApiSql` - Oracle API $sql SQL Monitor
- `sqlMonitorRelational` - JDBC Relational SQL Monitor

### Phase 2: Update SQL Capture Logic (TDD)

#### 2.1 Update storeSqlDetails() to capture all three protocols
**Test**: Verify each protocol's SQL Monitor is captured independently
**Implementation**:
```java
String sqlMonitorJdbcJson = !jdbcSql.isEmpty() ? captureSqlMonitorHtmlWithSqlId(jdbcSql) : "";
String sqlMonitorApiSql = !apiSql.isEmpty() ? captureSqlMonitorForApiSql(apiSql) : "";
String sqlMonitorRelational = !relationalSql.isEmpty() ? captureSqlMonitorHtmlWithSqlId(relationalSql) : "";
```

#### 2.2 Handle Oracle API ($sql) SQL Monitor capture
**Challenge**: $sql queries run through MongoDB wire protocol, not JDBC
**Options**:
  a) Extract SQL from $sql pipeline and run via JDBC with MONITOR hint
  b) Skip SQL Monitor for API (note in report)
  c) Query V$SQL_MONITOR by SQL_TEXT pattern matching
**Decision**: Option (a) - Extract and re-execute for monitoring

### Phase 3: Update HTML Report Generation (TDD)

#### 3.1 Restructure SQL Monitor tab with protocol subtabs
**Test**: Verify HTML output contains three protocol subtabs
**Implementation**:
```
SQL Monitor Tab
├── Subtab: JDBC JSON
│   ├── Sub-subtab: Test A1
│   ├── Sub-subtab: Test A2
│   └── ...
├── Subtab: Oracle API ($sql)
│   ├── Sub-subtab: Test A1
│   └── ...
└── Subtab: JDBC Relational
    ├── Sub-subtab: Test A1
    └── ...
```

#### 3.2 Update CSS for nested subtabs
**Test**: Verify styling distinguishes protocol tabs from test tabs
**Implementation**: Add `.protocol-tabs` and `.test-subtabs` CSS classes

#### 3.3 Update JavaScript for nested tab navigation
**Test**: Verify clicking protocol tab shows correct content
**Implementation**: Add `openProtocolSubTab()` function

### Phase 4: Integration Testing

#### 4.1 Run full benchmark and verify reports
**Test**: Execute benchmark, open report, verify all protocols have SQL Monitor data
**Verification**:
- [ ] JDBC JSON tab has SQL Monitor for each test
- [ ] Oracle API tab has SQL Monitor (or "N/A" note) for each test
- [ ] JDBC Relational tab has SQL Monitor for each test
- [ ] Tab navigation works correctly
- [ ] iframes load SQL Monitor content

## File Changes

### LookupVsSqlJoinTest.java
1. Line ~165: Update `SqlDetails` record
2. Line ~640: Update `storeSqlDetails()` method
3. Line ~4121: Update SQL Monitor HTML generation in `generateHtmlReport()`
4. Line ~3781: Add CSS for nested subtabs
5. Line ~4225: Add JavaScript for nested tab navigation

## Risk Assessment
- **Oracle API capture**: May require different approach since queries don't go through JDBC
- **Report size**: Three SQL Monitors per test will increase report size significantly
- **Performance**: Additional DBMS_SQLTUNE calls will slow test execution

## Rollback Plan
If issues arise, can revert SqlDetails to single field and restore original generation logic.
