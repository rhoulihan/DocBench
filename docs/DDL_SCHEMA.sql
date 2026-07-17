-- ============================================================================
-- DocBench DDL Schema
-- MongoDB $lookup vs Oracle $sql JOIN Benchmark Test Suite
-- ============================================================================
-- This file contains all table definitions used in the benchmark tests.
-- Tables are organized by category: JSON Tables and Relational Tables.
-- ============================================================================

-- ============================================================================
-- SECTION 1: JSON TABLES (Oracle JSON-Relational Duality)
-- ============================================================================
-- These tables store data as JSON documents and are used for:
--   - MongoDB $lookup aggregation comparisons
--   - Oracle $sql operator via MongoDB API
--   - JDBC JSON queries using JSON path expressions
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1.1 BENCHMARK_CUSTOMERS (JSON)
-- ----------------------------------------------------------------------------
-- Used in: Categories A, B, C, E, F, G, H, R
-- Purpose: Customer master data stored as JSON documents
-- Sample document:
--   {
--     "_id": "CUST_00001",
--     "name": "Customer 1",
--     "email": "customer1@example.com",
--     "region": "NORTH",
--     "created_at": "2024-01-15"
--   }
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_customers (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);

-- Index on _id field for fast lookups via JSON path
CREATE INDEX idx_cust_id ON benchmark_customers (data."_id".string());

-- ----------------------------------------------------------------------------
-- 1.2 BENCHMARK_ORDERS (JSON)
-- ----------------------------------------------------------------------------
-- Used in: Categories A, B, C, E, F, G, H, R
-- Purpose: Order transactions stored as JSON documents
-- Sample document:
--   {
--     "_id": "ORD_00001",
--     "customer_id": "CUST_00001",
--     "product_id": "PROD_001",
--     "order_date": "2024-01-20",
--     "total": 150.00,
--     "status": "COMPLETED"
--   }
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_orders (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);

-- Index on customer_id for join operations
CREATE INDEX idx_orders_custid ON benchmark_orders (data.customer_id.string());

-- Index on product_id for multi-table joins (Category G)
CREATE INDEX idx_orders_prodid ON benchmark_orders (data.product_id.string());

-- ----------------------------------------------------------------------------
-- 1.3 BENCHMARK_PRODUCTS (JSON)
-- ----------------------------------------------------------------------------
-- Used in: Category G (Multi-Stage Pipeline - Chained Lookups)
-- Purpose: Product catalog stored as JSON documents
-- Sample document:
--   {
--     "_id": "PROD_001",
--     "name": "Product 1",
--     "category": "Electronics",
--     "price": 99.99
--   }
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_products (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);

-- Index on _id field for product lookups
CREATE INDEX idx_prod_id ON benchmark_products (data."_id".string());

-- ----------------------------------------------------------------------------
-- 1.4 BENCHMARK_LARGE_ORDERS (JSON)
-- ----------------------------------------------------------------------------
-- Used in: Category D (Document Size Limit Tests)
-- Purpose: Orders with large embedded data to test BSON 16MB limit
-- Sample document (with padding):
--   {
--     "_id": "LO_00001",
--     "customer_id": "CUST_00001",
--     "order_date": "2024-01-20",
--     "total": 500.00,
--     "status": "COMPLETED",
--     "padding": "XXXX...XXXX"  -- Variable size padding to test limits
--   }
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_large_orders (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);

-- Index on customer_id for join operations
CREATE INDEX idx_large_orders_custid ON benchmark_large_orders (data.customer_id.string());


-- ============================================================================
-- SECTION 2: RELATIONAL TABLES (Traditional SQL)
-- ============================================================================
-- These tables store data in traditional relational format and are used for:
--   - JDBC Relational baseline comparisons
--   - Demonstrating Oracle's native SQL join performance
--   - No JSON parsing overhead
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 2.1 BENCHMARK_CUSTOMERS_REL (Relational)
-- ----------------------------------------------------------------------------
-- Used in: Categories A, B, C, E, F, G, H, R (JDBC REL measurements)
-- Purpose: Customer master data in relational format
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_customers_rel (
    customer_id     VARCHAR2(100) PRIMARY KEY,
    name            VARCHAR2(200),
    email           VARCHAR2(200),
    region          VARCHAR2(20),
    created_at      VARCHAR2(20)
);

-- Primary key index is automatically created

-- ----------------------------------------------------------------------------
-- 2.2 BENCHMARK_ORDERS_REL (Relational)
-- ----------------------------------------------------------------------------
-- Used in: Categories A, B, C, E, F, G, H, R (JDBC REL measurements)
-- Purpose: Order transactions in relational format
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_orders_rel (
    order_id        VARCHAR2(100) PRIMARY KEY,
    customer_id     VARCHAR2(100) NOT NULL,
    product_id      VARCHAR2(100),
    order_date      VARCHAR2(20),
    total           NUMBER(10,2),
    status          VARCHAR2(20)
);

-- Index on customer_id for efficient joins
CREATE INDEX idx_orders_rel_custid ON benchmark_orders_rel (customer_id);

-- Index on product_id for multi-table joins (Category G)
CREATE INDEX idx_orders_rel_prodid ON benchmark_orders_rel (product_id);

-- ----------------------------------------------------------------------------
-- 2.3 BENCHMARK_PRODUCTS_REL (Relational)
-- ----------------------------------------------------------------------------
-- Used in: Category G (Multi-Stage Pipeline - Chained Lookups)
-- Purpose: Product catalog in relational format for 3-table joins
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_products_rel (
    product_id      VARCHAR2(100) PRIMARY KEY,
    name            VARCHAR2(200),
    category        VARCHAR2(100),
    price           NUMBER(10,2)
);

-- Primary key index is automatically created

-- ----------------------------------------------------------------------------
-- 2.4 BENCHMARK_LARGE_ORDERS_REL (Relational)
-- ----------------------------------------------------------------------------
-- Used in: Category D (Document Size Limit Tests)
-- Purpose: Orders with large padding data in relational format
-- ----------------------------------------------------------------------------
CREATE TABLE benchmark_large_orders_rel (
    order_id        VARCHAR2(100) PRIMARY KEY,
    customer_id     VARCHAR2(100) NOT NULL,
    order_date      VARCHAR2(20),
    total           NUMBER(10,2),
    status          VARCHAR2(20),
    padding         CLOB
);

-- Index on customer_id for efficient joins
CREATE INDEX idx_large_orders_rel_custid ON benchmark_large_orders_rel (customer_id);


-- ============================================================================
-- SECTION 3: SAMPLE QUERIES BY TEST CATEGORY
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Category A: Baseline Join Performance (1K, 10K, 100K customers)
-- ----------------------------------------------------------------------------

-- JSON Query (JDBC JSON):
SELECT c.data, o.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
FETCH FIRST 1000 ROWS ONLY;

-- Relational Query (JDBC REL):
SELECT c.customer_id, c.name, c.email, o.order_id, o.order_date, o.total
FROM benchmark_customers_rel c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
FETCH FIRST 1000 ROWS ONLY;

-- ----------------------------------------------------------------------------
-- Category B: One-to-Many Cardinality (1:1, 1:10, 1:100, 1:1000)
-- ----------------------------------------------------------------------------
-- Same queries as Category A with different data cardinalities

-- ----------------------------------------------------------------------------
-- Category C: Oracle Parallel Execution
-- ----------------------------------------------------------------------------

-- JSON Query with PARALLEL hint:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.data, o.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
FETCH FIRST 100000 ROWS ONLY;

-- Relational Query with PARALLEL hint:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.customer_id, c.name, o.order_id, o.total
FROM benchmark_customers_rel c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
FETCH FIRST 100000 ROWS ONLY;

-- ----------------------------------------------------------------------------
-- Category D: Document Size Limit Tests (100KB to 50MB)
-- ----------------------------------------------------------------------------

-- JSON Query (tests MongoDB 16MB BSON limit):
SELECT c.data, o.data
FROM (SELECT * FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_large_orders o ON c.data."_id".string() = o.data.customer_id.string();

-- Relational Query (no size limit - CLOB supports large data):
SELECT c.customer_id, c.name, c.email, c.region,
       o.order_id, o.order_date, o.total, o.status, o.padding
FROM (SELECT * FROM benchmark_customers_rel WHERE ROWNUM <= ?) c
JOIN benchmark_large_orders_rel o ON c.customer_id = o.customer_id;

-- ----------------------------------------------------------------------------
-- Category E: Aggregation Memory Limit (50MB to 500MB working sets)
-- ----------------------------------------------------------------------------

-- JSON Query:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.data, o.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
FETCH FIRST ? ROWS ONLY;

-- Relational Query:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.customer_id, c.name, o.order_id, o.total
FROM benchmark_customers_rel c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
FETCH FIRST ? ROWS ONLY;

-- ----------------------------------------------------------------------------
-- Category F: Sort Spillover Tests (10K to 1M documents)
-- ----------------------------------------------------------------------------

-- JSON Query with ORDER BY:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.data, o.data
FROM (SELECT * FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
ORDER BY o.data.total.number() DESC;

-- Relational Query with ORDER BY:
SELECT /*+ PARALLEL(c,2) PARALLEL(o,2) */ c.customer_id, c.name, o.order_id, o.total
FROM (SELECT * FROM benchmark_customers_rel WHERE ROWNUM <= ?) c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
ORDER BY o.total DESC;

-- ----------------------------------------------------------------------------
-- Category G: Multi-Stage Pipeline
-- ----------------------------------------------------------------------------

-- G0: 2-stage ($lookup -> $sort) - JSON:
SELECT c.data, o.data
FROM (SELECT data FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
ORDER BY o.data.total.number() DESC;

-- G1: 3-stage ($lookup -> $unwind -> $group) - JSON:
SELECT c.data."_id".string() as customer_id, COUNT(*) as order_count, SUM(o.data.total.number()) as total_amount
FROM (SELECT * FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
GROUP BY c.data."_id".string();

-- G1: 3-stage - Relational:
SELECT c.customer_id, COUNT(*) as order_count, SUM(o.total) as total_amount
FROM (SELECT * FROM benchmark_customers_rel WHERE ROWNUM <= ?) c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
GROUP BY c.customer_id;

-- G2: 4-stage ($lookup -> $unwind -> $group -> $sort) - JSON:
SELECT c.data."_id".string() as customer_id, COUNT(*) as order_count, SUM(o.data.total.number()) as total_amount
FROM (SELECT * FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
GROUP BY c.data."_id".string()
ORDER BY total_amount DESC;

-- G3: Chained lookups ($lookup -> $lookup) - JSON:
SELECT c.data, o.data, p.data
FROM (SELECT * FROM benchmark_customers WHERE ROWNUM <= ?) c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
LEFT JOIN benchmark_products p ON o.data.product_id.string() = p.data."_id".string();

-- G3: Chained lookups - Relational:
SELECT c.customer_id, c.name, o.order_id, o.total, p.product_id, p.name, p.category, p.price
FROM (SELECT * FROM benchmark_customers_rel WHERE ROWNUM <= ?) c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
LEFT JOIN benchmark_products_rel p ON o.product_id = p.product_id;

-- ----------------------------------------------------------------------------
-- Category H: Selective/Indexed Joins
-- ----------------------------------------------------------------------------

-- H0: Single customer lookup - JSON:
SELECT c.data, o.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
WHERE c.data."_id".string() = ?;

-- H0: Single customer lookup - Relational:
SELECT c.customer_id, c.name, c.email, o.order_id, o.order_date, o.total
FROM benchmark_customers_rel c
JOIN benchmark_orders_rel o ON c.customer_id = o.customer_id
WHERE c.customer_id = ?;

-- H1-H3: Batch lookups (10, 100, 1000 customers) - JSON:
SELECT c.data, o.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
WHERE c.data."_id".string() IN (?, ?, ...);

-- ----------------------------------------------------------------------------
-- Category R: Relational vs JSON Comparison
-- ----------------------------------------------------------------------------
-- Uses same queries as Category A but explicitly compares all four modes:
--   MongoDB, Oracle $sql API, JDBC JSON, JDBC Relational


-- ============================================================================
-- SECTION 4: STATISTICS GATHERING
-- ============================================================================
-- Run after data insertion for optimal query plans

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_CUSTOMERS', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_ORDERS', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_PRODUCTS', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_LARGE_ORDERS', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_CUSTOMERS_REL', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_ORDERS_REL', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_PRODUCTS_REL', cascade => TRUE);
END;
/

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'BENCHMARK_LARGE_ORDERS_REL', cascade => TRUE);
END;
/


-- ============================================================================
-- SECTION 5: CLEANUP (Optional)
-- ============================================================================

-- Drop all tables (use with caution)
/*
DROP TABLE benchmark_orders CASCADE CONSTRAINTS;
DROP TABLE benchmark_large_orders CASCADE CONSTRAINTS;
DROP TABLE benchmark_customers CASCADE CONSTRAINTS;
DROP TABLE benchmark_products CASCADE CONSTRAINTS;
DROP TABLE benchmark_orders_rel CASCADE CONSTRAINTS;
DROP TABLE benchmark_customers_rel CASCADE CONSTRAINTS;
DROP TABLE benchmark_products_rel CASCADE CONSTRAINTS;
DROP TABLE benchmark_large_orders_rel CASCADE CONSTRAINTS;
*/
