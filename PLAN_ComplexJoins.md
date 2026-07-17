# Complex Joins Benchmark - Test Plan

## Overview

This document proposes a new **Category X: Complex Joins** to replace the deprecated HY (Hybrid Schema) and S (Simple Scan) test categories. The new tests focus on realistic, complex relationship patterns that stress different aspects of MongoDB's `$lookup` operator versus Oracle's SQL JOIN capabilities.

## Deprecated Tests

The following tests will be marked `@Disabled` with deprecation notes:

| Order | Test | Reason for Deprecation |
|-------|------|------------------------|
| 75-78 | HY1-HY4 (Hybrid Schema) | Narrow use case, virtual column pattern not widely adopted |
| 80 | S0 (Simple Scan) | Not a join test, out of scope for this benchmark |

---

## New Category X: Complex Joins (Order 90-119)

### Data Model: E-Commerce Platform

We'll use a realistic e-commerce data model that naturally exhibits all the complex relationship patterns:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  SUPPLIERS  │────<│  PRODUCTS   │>────│ CATEGORIES  │
└─────────────┘ 1:N └──────┬──────┘ M:N └─────────────┘
                           │
                           │ M:N (via ORDER_ITEMS)
                           │
┌─────────────┐     ┌──────┴──────┐     ┌─────────────┐
│  CUSTOMERS  │────<│   ORDERS    │────<│ ORDER_ITEMS │
└──────┬──────┘ 1:N └─────────────┘ 1:N └──────┬──────┘
       │                                       │
       │ 1:N                                   │ 1:N
       │                                       │
┌──────┴──────┐                         ┌──────┴──────┐
│  ADDRESSES  │                         │  SHIPMENTS  │
└─────────────┘                         └─────────────┘
```

### Relationship Patterns Covered

| Pattern | Example | MongoDB Challenge | SQL Advantage |
|---------|---------|-------------------|---------------|
| **M:N** | Products ↔ Categories | Two `$lookup` + `$unwind` | Single 3-table JOIN |
| **Hierarchical 1:N** | Customer → Orders → Items → Shipments | Nested `$lookup` | Multi-table JOIN |
| **M:N + 1:N child** | Products ↔ Orders + Items with Shipments | Complex pipeline | Natural JOIN |
| **M:N + 1:N both** | Products (with Reviews) ↔ Categories (with Rules) | Pipeline explosion | JOIN + GROUP BY |
| **Self-referential** | Categories with parent_id | `$graphLookup` | Recursive CTE + SQL Property Graph |
| **Diamond** | Orders → (Products, Customers) → Regions | Multiple paths | Standard JOINs |

---

## Proposed Tests

### Subcategory X0: Many-to-Many (M:N) Relationships (Order 90-94)

| Order | Test ID | Description | Data Size |
|-------|---------|-------------|-----------|
| 90 | `X0_M2M_basic_1K` | Basic M:N: Products ↔ Categories (1K products, 50 categories) | Small |
| 91 | `X0_M2M_basic_10K` | Basic M:N: Products ↔ Categories (10K products, 100 categories) | Medium |
| 92 | `X0_M2M_basic_100K` | Basic M:N: Products ↔ Categories (100K products, 500 categories) | Large |
| 93 | `X0_M2M_dense` | Dense M:N: Each product in 20 categories (high fan-out) | Medium |
| 94 | `X0_M2M_sparse` | Sparse M:N: Each product in 2 categories (low fan-out) | Medium |

**MongoDB Pipeline:**
```javascript
db.products.aggregate([
  { $lookup: {
      from: "product_categories",  // junction table
      localField: "_id",
      foreignField: "product_id",
      as: "category_links"
  }},
  { $unwind: "$category_links" },
  { $lookup: {
      from: "categories",
      localField: "category_links.category_id",
      foreignField: "_id",
      as: "category"
  }},
  { $unwind: "$category" }
])
```

**Oracle SQL:**
```sql
SELECT p.data, c.data
FROM benchmark_products p
JOIN benchmark_product_categories pc
  ON p.data."_id".string() = pc.data.product_id.string()
JOIN benchmark_categories c
  ON pc.data.category_id.string() = c.data."_id".string()
```

---

### Subcategory X1: Hierarchical Multi-Level 1:N (Order 95-99)

| Order | Test ID | Description | Levels |
|-------|---------|-------------|--------|
| 95 | `X1_hier_2level` | Customer → Orders (2-level) | 2 |
| 96 | `X1_hier_3level` | Customer → Orders → Items (3-level) | 3 |
| 97 | `X1_hier_4level` | Customer → Orders → Items → Shipments (4-level) | 4 |
| 98 | `X1_hier_wide` | Customer → Orders (100 orders/customer) | 2 (wide) |
| 99 | `X1_hier_deep` | 5-level hierarchy with narrow fan-out | 5 (deep) |

**MongoDB Pipeline (4-level):**
```javascript
db.customers.aggregate([
  { $lookup: {
      from: "orders",
      localField: "_id",
      foreignField: "customer_id",
      as: "orders"
  }},
  { $unwind: "$orders" },
  { $lookup: {
      from: "order_items",
      localField: "orders._id",
      foreignField: "order_id",
      as: "orders.items"
  }},
  { $unwind: "$orders.items" },
  { $lookup: {
      from: "shipments",
      localField: "orders.items._id",
      foreignField: "item_id",
      as: "orders.items.shipments"
  }}
])
```

**Oracle SQL:**
```sql
SELECT c.data, o.data, i.data, s.data
FROM benchmark_customers c
JOIN benchmark_orders o ON c.data."_id".string() = o.data.customer_id.string()
JOIN benchmark_order_items i ON o.data."_id".string() = i.data.order_id.string()
LEFT JOIN benchmark_shipments s ON i.data."_id".string() = s.data.item_id.string()
```

---

### Subcategory X2: M:N with 1:N on Child Side (Order 100-104)

| Order | Test ID | Description | Pattern |
|-------|---------|-------------|---------|
| 100 | `X2_M2N_child1N_basic` | Products ↔ Orders, Items have Shipments | M:N + 1:N |
| 101 | `X2_M2N_child1N_1K` | 1K products, 10K orders, 2 shipments/item | Small |
| 102 | `X2_M2N_child1N_10K` | 10K products, 100K orders | Medium |
| 103 | `X2_M2N_child1N_highFanout` | High shipment fan-out (10/item) | Stress |
| 104 | `X2_M2N_child1N_aggregate` | With SUM/COUNT aggregations | Analytics |

**MongoDB Pipeline:**
```javascript
db.products.aggregate([
  { $lookup: {
      from: "order_items",
      localField: "_id",
      foreignField: "product_id",
      as: "order_items"
  }},
  { $unwind: "$order_items" },
  { $lookup: {
      from: "shipments",
      localField: "order_items._id",
      foreignField: "item_id",
      as: "order_items.shipments"
  }}
])
```

**Oracle SQL:**
```sql
SELECT p.data, i.data, s.data
FROM benchmark_products p
JOIN benchmark_order_items i ON p.data."_id".string() = i.data.product_id.string()
LEFT JOIN benchmark_shipments s ON i.data."_id".string() = s.data.item_id.string()
```

---

### Subcategory X3: M:N with 1:N on Both Sides (Order 105-109)

| Order | Test ID | Description | Pattern |
|-------|---------|-------------|---------|
| 105 | `X3_M2N_both1N_basic` | Products (Reviews) ↔ Categories (Rules) | Complex M:N |
| 106 | `X3_M2N_both1N_1K` | 1K products with 5 reviews each | Small |
| 107 | `X3_M2N_both1N_10K` | 10K products, 500 categories | Medium |
| 108 | `X3_M2N_both1N_aggregate` | Aggregate reviews per category | Analytics |
| 109 | `X3_M2N_both1N_filtered` | With WHERE clause filtering | Selective |

**MongoDB Pipeline:**
```javascript
db.products.aggregate([
  { $lookup: {
      from: "reviews",
      localField: "_id",
      foreignField: "product_id",
      as: "reviews"
  }},
  { $lookup: {
      from: "product_categories",
      localField: "_id",
      foreignField: "product_id",
      as: "category_links"
  }},
  { $unwind: "$category_links" },
  { $lookup: {
      from: "categories",
      localField: "category_links.category_id",
      foreignField: "_id",
      as: "category"
  }},
  { $unwind: "$category" },
  { $lookup: {
      from: "category_rules",
      localField: "category._id",
      foreignField: "category_id",
      as: "category.rules"
  }}
])
```

**Oracle SQL:**
```sql
SELECT p.data, r.data, c.data, cr.data
FROM benchmark_products p
LEFT JOIN benchmark_reviews r ON p.data."_id".string() = r.data.product_id.string()
JOIN benchmark_product_categories pc ON p.data."_id".string() = pc.data.product_id.string()
JOIN benchmark_categories c ON pc.data.category_id.string() = c.data."_id".string()
LEFT JOIN benchmark_category_rules cr ON c.data."_id".string() = cr.data.category_id.string()
```

---

### Subcategory X4: Self-Referential Hierarchies (Order 110-114)

| Order | Test ID | Description | Depth |
|-------|---------|-------------|-------|
| 110 | `X4_selfref_2level` | Categories with parent (2 levels) | 2 |
| 111 | `X4_selfref_3level` | Categories (3-level tree) | 3 |
| 112 | `X4_selfref_5level` | Deep category tree (5 levels) | 5 |
| 113 | `X4_selfref_wide` | Wide tree (100 children/node, 2 levels) | 2 (wide) |
| 114 | `X4_selfref_path` | Full path reconstruction | Variable |

Each test measures **three approaches**:
1. MongoDB `$graphLookup`
2. Oracle Recursive CTE
3. Oracle SQL Property Graph (SQL/PGQ)

**MongoDB Pipeline (using $graphLookup):**
```javascript
db.categories.aggregate([
  { $match: { parent_id: null } },  // Start from roots
  { $graphLookup: {
      from: "categories",
      startWith: "$_id",
      connectFromField: "_id",
      connectToField: "parent_id",
      as: "descendants",
      maxDepth: 5,
      depthField: "level"
  }}
])
```

**Oracle SQL (Recursive CTE):**
```sql
WITH category_tree AS (
  -- Anchor: root categories
  SELECT data, 0 as level
  FROM benchmark_categories
  WHERE data.parent_id IS JSON NULL

  UNION ALL

  -- Recursive: children
  SELECT c.data, ct.level + 1
  FROM benchmark_categories c
  JOIN category_tree ct ON c.data.parent_id.string() = ct.data."_id".string()
  WHERE ct.level < 5
)
SELECT * FROM category_tree
```

**Oracle SQL Property Graph (SQL/PGQ):**

Property Graph Definition (created once during setup):
```sql
-- Create Property Graph on relational category table
CREATE OR REPLACE PROPERTY GRAPH category_graph
  VERTEX TABLES (
    benchmark_categories_rel AS category
      KEY (category_id)
      PROPERTIES (category_id, name, parent_id, level)
  )
  EDGE TABLES (
    benchmark_categories_rel AS parent_of
      KEY (category_id)
      SOURCE KEY (parent_id) REFERENCES category (category_id)
      DESTINATION KEY (category_id) REFERENCES category (category_id)
      NO PROPERTIES
  );
```

Graph Pattern Matching Query (descendants):
```sql
-- Find all descendants of root categories using MATCH clause
SELECT c.category_id, c.name, d.category_id AS descendant_id, d.name AS descendant_name
FROM GRAPH_TABLE (category_graph
  MATCH (c IS category WHERE c.parent_id IS NULL)
        -[e IS parent_of]->+ (d IS category)
  COLUMNS (c.category_id, c.name, d.category_id AS descendant_id, d.name AS descendant_name)
) gt;
```

Graph Pattern Matching Query (path reconstruction):
```sql
-- Reconstruct full path from leaf to root
SELECT leaf.category_id,
       LISTAGG(ancestor.name, ' > ') WITHIN GROUP (ORDER BY path_length DESC) AS full_path
FROM GRAPH_TABLE (category_graph
  MATCH (leaf IS category) -[e IS parent_of]->{0,10} (ancestor IS category)
  COLUMNS (leaf.category_id, ancestor.name, PATH_LENGTH(e) AS path_length)
) gt
GROUP BY leaf.category_id;
```

Graph Pattern Matching Query (find siblings):
```sql
-- Find all siblings (same parent)
SELECT c1.category_id, c1.name, c2.category_id AS sibling_id, c2.name AS sibling_name
FROM GRAPH_TABLE (category_graph
  MATCH (c1 IS category) <-[e1 IS parent_of]- (parent IS category) -[e2 IS parent_of]-> (c2 IS category)
  WHERE c1.category_id <> c2.category_id
  COLUMNS (c1.category_id, c1.name, c2.category_id AS sibling_id, c2.name AS sibling_name)
) gt;
```

---

### Subcategory X5: Diamond Patterns (Order 115-119)

| Order | Test ID | Description | Pattern |
|-------|---------|-------------|---------|
| 115 | `X5_diamond_basic` | Order → Product → Supplier, Order → Customer → Region | Diamond |
| 116 | `X5_diamond_converge` | Multiple paths converging to same table | Converge |
| 117 | `X5_diamond_aggregate` | Aggregate across diamond (e.g., sales by region) | Analytics |
| 118 | `X5_diamond_filtered` | With selective filters on multiple paths | Selective |
| 119 | `X5_star_schema` | Star schema: Fact + 4 dimensions | Star |

**MongoDB Pipeline:**
```javascript
db.orders.aggregate([
  { $lookup: {
      from: "customers",
      localField: "customer_id",
      foreignField: "_id",
      as: "customer"
  }},
  { $unwind: "$customer" },
  { $lookup: {
      from: "regions",
      localField: "customer.region_id",
      foreignField: "_id",
      as: "customer_region"
  }},
  { $lookup: {
      from: "order_items",
      localField: "_id",
      foreignField: "order_id",
      as: "items"
  }},
  { $unwind: "$items" },
  { $lookup: {
      from: "products",
      localField: "items.product_id",
      foreignField: "_id",
      as: "product"
  }},
  { $unwind: "$product" },
  { $lookup: {
      from: "suppliers",
      localField: "product.supplier_id",
      foreignField: "_id",
      as: "supplier"
  }},
  { $lookup: {
      from: "regions",
      localField: "supplier.region_id",
      foreignField: "_id",
      as: "supplier_region"
  }}
])
```

**Oracle SQL:**
```sql
SELECT o.data, c.data, cr.data, i.data, p.data, s.data, sr.data
FROM benchmark_orders o
JOIN benchmark_customers c ON o.data.customer_id.string() = c.data."_id".string()
JOIN benchmark_regions cr ON c.data.region_id.string() = cr.data."_id".string()
JOIN benchmark_order_items i ON o.data."_id".string() = i.data.order_id.string()
JOIN benchmark_products p ON i.data.product_id.string() = p.data."_id".string()
JOIN benchmark_suppliers s ON p.data.supplier_id.string() = s.data."_id".string()
JOIN benchmark_regions sr ON s.data.region_id.string() = sr.data."_id".string()
```

---

## New Tables Required

### JSON Tables

```sql
-- Junction table for Products ↔ Categories (M:N)
CREATE TABLE benchmark_product_categories (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_pc_product ON benchmark_product_categories (data.product_id.string());
CREATE INDEX idx_pc_category ON benchmark_product_categories (data.category_id.string());

-- Categories (with self-reference for hierarchy)
CREATE TABLE benchmark_categories (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_cat_id ON benchmark_categories (data."_id".string());
CREATE INDEX idx_cat_parent ON benchmark_categories (data.parent_id.string());

-- Order Items (junction for Orders ↔ Products M:N)
CREATE TABLE benchmark_order_items (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_oi_order ON benchmark_order_items (data.order_id.string());
CREATE INDEX idx_oi_product ON benchmark_order_items (data.product_id.string());

-- Shipments (1:N from Order Items)
CREATE TABLE benchmark_shipments (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_ship_item ON benchmark_shipments (data.item_id.string());

-- Suppliers (1:N to Products)
CREATE TABLE benchmark_suppliers (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_supp_id ON benchmark_suppliers (data."_id".string());

-- Regions (referenced by Customers and Suppliers)
CREATE TABLE benchmark_regions (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_reg_id ON benchmark_regions (data."_id".string());

-- Reviews (1:N from Products)
CREATE TABLE benchmark_reviews (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_rev_product ON benchmark_reviews (data.product_id.string());

-- Category Rules (1:N from Categories)
CREATE TABLE benchmark_category_rules (
    id      VARCHAR2(100) PRIMARY KEY,
    data    JSON
);
CREATE INDEX idx_cr_category ON benchmark_category_rules (data.category_id.string());
```

### Relational Tables

```sql
-- Product Categories Junction (M:N)
CREATE TABLE benchmark_product_categories_rel (
    product_id    VARCHAR2(100) NOT NULL,
    category_id   VARCHAR2(100) NOT NULL,
    PRIMARY KEY (product_id, category_id)
);
CREATE INDEX idx_pcr_category ON benchmark_product_categories_rel (category_id);

-- Categories (with hierarchy)
CREATE TABLE benchmark_categories_rel (
    category_id   VARCHAR2(100) PRIMARY KEY,
    name          VARCHAR2(200),
    parent_id     VARCHAR2(100),
    level         NUMBER(2)
);
CREATE INDEX idx_catr_parent ON benchmark_categories_rel (parent_id);

-- Order Items
CREATE TABLE benchmark_order_items_rel (
    item_id       VARCHAR2(100) PRIMARY KEY,
    order_id      VARCHAR2(100) NOT NULL,
    product_id    VARCHAR2(100) NOT NULL,
    quantity      NUMBER(10),
    unit_price    NUMBER(10,2),
    line_total    NUMBER(10,2)
);
CREATE INDEX idx_oir_order ON benchmark_order_items_rel (order_id);
CREATE INDEX idx_oir_product ON benchmark_order_items_rel (product_id);

-- Shipments
CREATE TABLE benchmark_shipments_rel (
    shipment_id   VARCHAR2(100) PRIMARY KEY,
    item_id       VARCHAR2(100) NOT NULL,
    ship_date     DATE,
    carrier       VARCHAR2(100),
    tracking_num  VARCHAR2(100),
    status        VARCHAR2(20)
);
CREATE INDEX idx_shipr_item ON benchmark_shipments_rel (item_id);

-- Suppliers
CREATE TABLE benchmark_suppliers_rel (
    supplier_id   VARCHAR2(100) PRIMARY KEY,
    name          VARCHAR2(200),
    region_id     VARCHAR2(100),
    contact_email VARCHAR2(200)
);
CREATE INDEX idx_suppr_region ON benchmark_suppliers_rel (region_id);

-- Regions
CREATE TABLE benchmark_regions_rel (
    region_id     VARCHAR2(100) PRIMARY KEY,
    name          VARCHAR2(100),
    country       VARCHAR2(100)
);

-- Reviews
CREATE TABLE benchmark_reviews_rel (
    review_id     VARCHAR2(100) PRIMARY KEY,
    product_id    VARCHAR2(100) NOT NULL,
    rating        NUMBER(1),
    review_text   VARCHAR2(4000),
    review_date   DATE
);
CREATE INDEX idx_revr_product ON benchmark_reviews_rel (product_id);

-- Category Rules
CREATE TABLE benchmark_category_rules_rel (
    rule_id       VARCHAR2(100) PRIMARY KEY,
    category_id   VARCHAR2(100) NOT NULL,
    rule_name     VARCHAR2(200),
    rule_value    VARCHAR2(1000)
);
CREATE INDEX idx_crr_category ON benchmark_category_rules_rel (category_id);

-- Add supplier_id and region_id to existing tables
ALTER TABLE benchmark_products_rel ADD supplier_id VARCHAR2(100);
ALTER TABLE benchmark_customers_rel ADD region_id VARCHAR2(100);
CREATE INDEX idx_prodr_supplier ON benchmark_products_rel (supplier_id);
CREATE INDEX idx_custr_region ON benchmark_customers_rel (region_id);
```

### SQL Property Graph Definition

```sql
-- ============================================================================
-- SQL Property Graph for Category Hierarchy (Oracle 23c+)
-- ============================================================================
-- This graph enables SQL/PGQ pattern matching queries for hierarchical
-- traversal, competing with MongoDB's $graphLookup operator.
-- ============================================================================

CREATE OR REPLACE PROPERTY GRAPH category_graph
  VERTEX TABLES (
    benchmark_categories_rel AS category
      KEY (category_id)
      PROPERTIES (category_id, name, parent_id, level)
  )
  EDGE TABLES (
    -- Self-referential edge: parent_id -> category_id
    -- Note: We create a derived edge table from the categories table
    benchmark_categories_rel AS parent_of
      KEY (category_id)
      SOURCE KEY (parent_id) REFERENCES category (category_id)
      DESTINATION KEY (category_id) REFERENCES category (category_id)
      NO PROPERTIES
  );

-- Alternative: Create explicit edge table for more control
CREATE TABLE benchmark_category_edges (
    edge_id       VARCHAR2(100) PRIMARY KEY,
    parent_id     VARCHAR2(100) NOT NULL,
    child_id      VARCHAR2(100) NOT NULL,
    edge_type     VARCHAR2(20) DEFAULT 'PARENT_OF'
);
CREATE INDEX idx_ce_parent ON benchmark_category_edges (parent_id);
CREATE INDEX idx_ce_child ON benchmark_category_edges (child_id);

-- Property Graph with explicit edge table
CREATE OR REPLACE PROPERTY GRAPH category_graph_explicit
  VERTEX TABLES (
    benchmark_categories_rel AS category
      KEY (category_id)
      PROPERTIES (category_id, name, level)
  )
  EDGE TABLES (
    benchmark_category_edges AS parent_of
      KEY (edge_id)
      SOURCE KEY (parent_id) REFERENCES category (category_id)
      DESTINATION KEY (child_id) REFERENCES category (category_id)
      PROPERTIES (edge_type)
  );
```

---

## Test Data Generation

### Data Volume Matrix

| Entity | Small (1K) | Medium (10K) | Large (100K) |
|--------|------------|--------------|--------------|
| Products | 1,000 | 10,000 | 100,000 |
| Categories | 50 | 100 | 500 |
| Product-Category Links | 3,000 | 50,000 | 500,000 |
| Customers | 500 | 5,000 | 50,000 |
| Orders | 2,000 | 20,000 | 200,000 |
| Order Items | 5,000 | 50,000 | 500,000 |
| Shipments | 7,500 | 75,000 | 750,000 |
| Suppliers | 50 | 200 | 1,000 |
| Regions | 10 | 20 | 50 |
| Reviews | 5,000 | 50,000 | 500,000 |
| Category Rules | 150 | 300 | 1,500 |

### Sample Document Structures

```javascript
// Product
{
  "_id": "prod_00001",
  "name": "Widget Pro",
  "description": "High-quality widget",
  "price": 29.99,
  "supplier_id": "supp_001",
  "created_at": "2024-01-15"
}

// Category
{
  "_id": "cat_001",
  "name": "Electronics",
  "parent_id": null,  // or "cat_000" for hierarchy
  "level": 0
}

// Product-Category Junction
{
  "_id": "pc_00001",
  "product_id": "prod_00001",
  "category_id": "cat_001"
}

// Order Item
{
  "_id": "item_00001",
  "order_id": "ord_00001",
  "product_id": "prod_00001",
  "quantity": 2,
  "unit_price": 29.99,
  "line_total": 59.98
}

// Shipment
{
  "_id": "ship_00001",
  "item_id": "item_00001",
  "ship_date": "2024-01-20",
  "carrier": "FedEx",
  "tracking_num": "FX123456789",
  "status": "DELIVERED"
}

// Supplier
{
  "_id": "supp_001",
  "name": "Acme Widgets Inc",
  "region_id": "reg_001",
  "contact_email": "sales@acmewidgets.com"
}

// Region
{
  "_id": "reg_001",
  "name": "Northeast",
  "country": "USA"
}

// Review
{
  "_id": "rev_00001",
  "product_id": "prod_00001",
  "rating": 5,
  "review_text": "Excellent product!",
  "review_date": "2024-02-01"
}

// Category Rule
{
  "_id": "rule_001",
  "category_id": "cat_001",
  "rule_name": "min_price",
  "rule_value": "10.00"
}
```

---

## Java Test Data Generation Methods

```java
// In LookupVsSqlJoinTest.java

// ============================================================================
// Complex Join Test Data Generation
// ============================================================================

private void generateComplexTestData(int productCount, int categoryCount,
                                      int customersCount, int ordersPerCustomer,
                                      int itemsPerOrder, int shipmentsPerItem) {
    // Generate base entities
    generateRegions(Math.max(10, productCount / 1000));
    generateSuppliers(Math.max(50, productCount / 20));
    generateCategories(categoryCount);
    generateProducts(productCount);
    generateProductCategoryLinks(productCount, categoryCount, 3);  // avg 3 categories/product

    // Generate transaction data
    generateCustomers(customersCount);
    generateOrders(customersCount, ordersPerCustomer);
    generateOrderItems(customersCount * ordersPerCustomer, itemsPerOrder, productCount);
    generateShipments(customersCount * ordersPerCustomer * itemsPerOrder, shipmentsPerItem);

    // Generate supplementary data
    generateReviews(productCount, 5);  // avg 5 reviews/product
    generateCategoryRules(categoryCount, 3);  // avg 3 rules/category
}

private void generateCategories(int count) {
    // Generate hierarchical categories (20% are roots, rest have parents)
    List<Document> mongoDocs = new ArrayList<>();
    List<Object[]> relRows = new ArrayList<>();

    int rootCount = Math.max(1, count / 5);

    for (int i = 0; i < count; i++) {
        String id = String.format("cat_%05d", i);
        String parentId = (i < rootCount) ? null : String.format("cat_%05d", i % rootCount);
        int level = (i < rootCount) ? 0 : 1 + (i / rootCount) % 4;

        Document doc = new Document()
            .append("_id", id)
            .append("name", "Category " + i)
            .append("parent_id", parentId)
            .append("level", level);
        mongoDocs.add(doc);

        relRows.add(new Object[]{id, "Category " + i, parentId, level});
    }

    // Batch insert to MongoDB
    categoriesCollection.insertMany(mongoDocs);

    // Batch insert to Oracle
    String sql = "INSERT INTO benchmark_categories_rel VALUES (?, ?, ?, ?)";
    batchInsertOracle(sql, relRows);
}

private void generateProductCategoryLinks(int productCount, int categoryCount, int avgLinksPerProduct) {
    List<Document> mongoDocs = new ArrayList<>();
    List<Object[]> relRows = new ArrayList<>();
    Random random = new Random(42);  // Reproducible

    int linkId = 0;
    for (int p = 0; p < productCount; p++) {
        String productId = String.format("prod_%05d", p);
        int linkCount = 1 + random.nextInt(avgLinksPerProduct * 2);  // 1 to 2*avg

        Set<Integer> usedCategories = new HashSet<>();
        for (int l = 0; l < linkCount && usedCategories.size() < categoryCount; l++) {
            int catIdx = random.nextInt(categoryCount);
            if (usedCategories.add(catIdx)) {
                String categoryId = String.format("cat_%05d", catIdx);
                String id = String.format("pc_%08d", linkId++);

                Document doc = new Document()
                    .append("_id", id)
                    .append("product_id", productId)
                    .append("category_id", categoryId);
                mongoDocs.add(doc);

                relRows.add(new Object[]{productId, categoryId});
            }
        }
    }

    productCategoriesCollection.insertMany(mongoDocs);
    batchInsertOracle("INSERT INTO benchmark_product_categories_rel VALUES (?, ?)", relRows);
}

// ... similar methods for other entities
```

---

## Cleanup Methods

```java
private void cleanupComplexTestData() {
    // MongoDB cleanup
    categoriesCollection.deleteMany(new Document());
    productCategoriesCollection.deleteMany(new Document());
    orderItemsCollection.deleteMany(new Document());
    shipmentsCollection.deleteMany(new Document());
    suppliersCollection.deleteMany(new Document());
    regionsCollection.deleteMany(new Document());
    reviewsCollection.deleteMany(new Document());
    categoryRulesCollection.deleteMany(new Document());

    // Oracle JSON cleanup
    try (Statement stmt = oracleJdbcConnection.createStatement()) {
        stmt.execute("TRUNCATE TABLE benchmark_categories");
        stmt.execute("TRUNCATE TABLE benchmark_product_categories");
        stmt.execute("TRUNCATE TABLE benchmark_order_items");
        stmt.execute("TRUNCATE TABLE benchmark_shipments");
        stmt.execute("TRUNCATE TABLE benchmark_suppliers");
        stmt.execute("TRUNCATE TABLE benchmark_regions");
        stmt.execute("TRUNCATE TABLE benchmark_reviews");
        stmt.execute("TRUNCATE TABLE benchmark_category_rules");
    }

    // Oracle relational cleanup
    try (Statement stmt = oracleJdbcConnection.createStatement()) {
        stmt.execute("TRUNCATE TABLE benchmark_categories_rel");
        stmt.execute("TRUNCATE TABLE benchmark_product_categories_rel");
        stmt.execute("TRUNCATE TABLE benchmark_order_items_rel");
        stmt.execute("TRUNCATE TABLE benchmark_shipments_rel");
        stmt.execute("TRUNCATE TABLE benchmark_suppliers_rel");
        stmt.execute("TRUNCATE TABLE benchmark_regions_rel");
        stmt.execute("TRUNCATE TABLE benchmark_reviews_rel");
        stmt.execute("TRUNCATE TABLE benchmark_category_rules_rel");
    }
}
```

---

## Expected Benchmark Insights

### MongoDB Challenges

1. **M:N Joins**: Requires two `$lookup` stages with `$unwind` between them
2. **Hierarchical**: Nested `$lookup` creates deeply nested documents (16MB limit risk)
3. **Self-referential**: `$graphLookup` is powerful but single-threaded
4. **Diamond patterns**: Multiple independent `$lookup` operations, no query optimizer
5. **Memory pressure**: Complex pipelines accumulate intermediate results

### Oracle Advantages

1. **Single Query**: All patterns expressed as standard multi-table JOINs
2. **Parallel Execution**: `PARALLEL` hints scale across all tables
3. **Query Optimizer**: Cost-based optimization across the entire query
4. **No Size Limits**: Streaming results without 16MB constraint
5. **Recursive CTEs**: Native support for hierarchical queries

---

## Test Execution Order

```
Category X: Complex Joins (Order 90-119)
├── X0: Many-to-Many (90-94)
├── X1: Hierarchical 1:N (95-99)
├── X2: M:N + Child 1:N (100-104)
├── X3: M:N + Both 1:N (105-109)
├── X4: Self-Referential (110-114)
└── X5: Diamond Patterns (115-119)
```

---

## Questions for Review

1. **Data Volumes**: Are the proposed sizes (1K/10K/100K) appropriate, or should we go larger?

2. **Additional Patterns**: Any other join patterns worth testing (e.g., UNION-based joins, anti-joins)?

3. **Aggregation Focus**: Should we add more tests with GROUP BY/SUM/AVG across complex joins?

4. **Index Variations**: Should we test with/without indexes on foreign keys?

5. **Parallel Scaling**: Should we include parallel degree variations (like Category C) for complex joins?

---

## Implementation Timeline

1. **Phase 1**: Add DDL for new tables (JSON + Relational)
2. **Phase 2**: Implement data generation methods
3. **Phase 3**: Implement X0 (M:N) tests
4. **Phase 4**: Implement X1 (Hierarchical) tests
5. **Phase 5**: Implement X2-X3 (M:N + 1:N) tests
6. **Phase 6**: Implement X4 (Self-referential) tests
7. **Phase 7**: Implement X5 (Diamond) tests
8. **Phase 8**: Deprecate HY/S tests and update report generation
