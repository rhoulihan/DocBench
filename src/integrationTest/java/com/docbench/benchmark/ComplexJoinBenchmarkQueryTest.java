package com.docbench.benchmark;

import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD unit tests for ComplexJoinBenchmarkTest query builders.
 * Validates SQL generation, MongoDB pipeline construction, and data model correctness
 * without requiring database connections.
 *
 * Tagged as "unit" so these can be run separately from integration tests:
 *   ./gradlew integrationTest --tests "*QueryTest"
 */
@DisplayName("Complex Join Benchmark - Query Builder Tests (TDD)")
@Tag("unit")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplexJoinBenchmarkQueryTest {

    // =========================================================================
    // MongoDB $graphLookup Pipeline Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("$graphLookup pipeline has correct stages for descendant traversal")
    void graphLookupPipeline_hasCorrectStages() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildGraphLookupPipeline(3);

        assertEquals(2, pipeline.size(), "Pipeline should have 2 stages: $match + $graphLookup");

        // Verify $match stage filters root categories
        String matchJson = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(matchJson.contains("$match"), "First stage should be $match");
        assertTrue(matchJson.contains("parent_id"), "Match should filter on parent_id");

        // Verify $graphLookup stage
        String graphJson = pipeline.get(1).toBsonDocument().toJson();
        assertTrue(graphJson.contains("$graphLookup"), "Second stage should be $graphLookup");
        assertTrue(graphJson.contains("benchmark_categories"), "Should reference categories collection");
        assertTrue(graphJson.contains("descendants"), "Should output as 'descendants'");
        assertTrue(graphJson.contains("maxDepth"), "Should have maxDepth");
    }

    @Test
    @Order(2)
    @DisplayName("$graphLookup respects maxDepth parameter")
    void graphLookupPipeline_respectsMaxDepth() {
        for (int depth : new int[]{2, 3, 5, 10}) {
            List<Bson> pipeline = ComplexJoinBenchmarkTest.buildGraphLookupPipeline(depth);
            String json = pipeline.get(1).toBsonDocument().toJson();
            assertTrue(json.contains(String.valueOf(depth)),
                    "Pipeline should contain maxDepth=" + depth);
        }
    }

    @Test
    @Order(3)
    @DisplayName("$graphLookup connects _id -> parent_id for descendant traversal")
    void graphLookupPipeline_correctFieldConnections() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildGraphLookupPipeline(5);
        String json = pipeline.get(1).toBsonDocument().toJson();

        // startWith: "$_id" - start from root's _id
        assertTrue(json.contains("_id"), "startWith should reference _id");
        // connectToField: "parent_id" - connect to children's parent_id
        assertTrue(json.contains("parent_id"), "connectToField should be parent_id");
    }

    @Test
    @Order(4)
    @DisplayName("$graphLookup path pipeline traverses ancestors (reverse direction)")
    void graphLookupPathPipeline_traversesAncestors() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildGraphLookupPathPipeline();

        assertEquals(1, pipeline.size(), "Path pipeline should have 1 stage: $graphLookup");

        String json = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json.contains("$graphLookup"), "Should be $graphLookup");
        assertTrue(json.contains("ancestors"), "Should output as 'ancestors'");
        // startWith: "$parent_id" - start from current node's parent
        assertTrue(json.contains("parent_id"), "Should start from parent_id");
    }

    // =========================================================================
    // Oracle Recursive CTE Tests
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("Recursive CTE has WITH clause and UNION ALL")
    void recursiveCte_hasCorrectStructure() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);

        assertTrue(sql.contains("WITH category_tree"), "Should start with CTE definition");
        assertTrue(sql.contains("UNION ALL"), "Should use UNION ALL for recursion");
        assertTrue(sql.contains("SELECT * FROM category_tree"), "Should select from CTE");
    }

    @Test
    @Order(11)
    @DisplayName("Recursive CTE anchor selects root categories")
    void recursiveCte_anchorSelectsRoots() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);

        assertTrue(sql.contains("parent_id IS NULL"), "Anchor should match root categories");
        assertTrue(sql.contains("0 AS depth"), "Anchor should set depth = 0");
    }

    @Test
    @Order(12)
    @DisplayName("Recursive CTE joins on parent_id = category_id")
    void recursiveCte_joinsCorrectly() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);

        assertTrue(sql.contains("c.parent_id = ct.category_id"),
                "Recursive step should join child's parent_id to parent's category_id");
    }

    @Test
    @Order(13)
    @DisplayName("Recursive CTE respects maxDepth limit")
    void recursiveCte_respectsMaxDepth() {
        String sql3 = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);
        assertTrue(sql3.contains("ct.depth < 3"), "Should limit depth to 3");

        String sql5 = ComplexJoinBenchmarkTest.buildRecursiveCteSql(5);
        assertTrue(sql5.contains("ct.depth < 5"), "Should limit depth to 5");
    }

    @Test
    @Order(14)
    @DisplayName("Recursive CTE references correct table name")
    void recursiveCte_usesCorrectTable() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);
        assertTrue(sql.contains("benchmark_categories_rel"),
                "Should reference the relational categories table");
    }

    // =========================================================================
    // Oracle Recursive CTE Path Reconstruction Tests
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("Path CTE walks up via parent_id")
    void pathCte_walksUpViaParentId() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCtePathSql();

        assertTrue(sql.contains("WITH path_cte"), "Should start with CTE");
        assertTrue(sql.contains("UNION ALL"), "Should use recursion");
        assertTrue(sql.contains("c.category_id = p.current_parent_id"),
                "Should join on parent's category_id = current node's parent_id");
    }

    @Test
    @Order(21)
    @DisplayName("Path CTE uses LISTAGG for path string")
    void pathCte_usesListagg() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCtePathSql();

        assertTrue(sql.contains("LISTAGG"), "Should use LISTAGG for path concatenation");
        assertTrue(sql.contains("' > '"), "Should use ' > ' as path separator");
        assertTrue(sql.contains("ORDER BY depth DESC"),
                "Should order path segments from root (deepest depth) to leaf");
    }

    @Test
    @Order(22)
    @DisplayName("Path CTE has recursion guard")
    void pathCte_hasRecursionGuard() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCtePathSql();

        assertTrue(sql.contains("p.current_parent_id IS NOT NULL"),
                "Should stop at root (null parent_id)");
        assertTrue(sql.contains("p.depth < 10"),
                "Should have max depth guard for safety");
    }

    @Test
    @Order(23)
    @DisplayName("Path CTE carries parent_id forward for efficient single-join recursion")
    void pathCte_carriesParentIdForward() {
        String sql = ComplexJoinBenchmarkTest.buildRecursiveCtePathSql();

        assertTrue(sql.contains("current_parent_id"),
                "Should carry parent_id forward in CTE columns");
        assertTrue(sql.contains("c.parent_id"),
                "Recursive step should select new parent_id for next iteration");
    }

    // =========================================================================
    // Oracle SQL Property Graph Tests
    // =========================================================================

    @Test
    @Order(30)
    @DisplayName("Property Graph descendant SQL uses GRAPH_TABLE")
    void propertyGraph_usesGraphTable() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(3);

        assertTrue(sql.contains("GRAPH_TABLE"), "Should use GRAPH_TABLE clause");
        assertTrue(sql.contains("category_graph"), "Should reference category_graph");
    }

    @Test
    @Order(31)
    @DisplayName("Property Graph uses MATCH with variable-length path")
    void propertyGraph_usesMatchWithPath() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(3);

        assertTrue(sql.contains("MATCH"), "Should use MATCH pattern");
        assertTrue(sql.contains("child_of"), "Should use child_of edge");
        assertTrue(sql.contains("{1,3}"),
                "Should use variable-length path {1,maxDepth}");
    }

    @Test
    @Order(32)
    @DisplayName("Property Graph filters root categories")
    void propertyGraph_filtersRoots() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(5);

        assertTrue(sql.contains("parent_id IS NULL"),
                "Should filter for root categories in WHERE clause");
    }

    @Test
    @Order(33)
    @DisplayName("Property Graph COLUMNS clause extracts expected fields")
    void propertyGraph_extractsColumns() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(3);

        assertTrue(sql.contains("COLUMNS"), "Should have COLUMNS clause");
        assertTrue(sql.contains("root_id"), "Should extract root_id");
        assertTrue(sql.contains("root_name"), "Should extract root_name");
        assertTrue(sql.contains("descendant_id"), "Should extract descendant_id");
        assertTrue(sql.contains("descendant_name"), "Should extract descendant_name");
    }

    @Test
    @Order(34)
    @DisplayName("Property Graph maxDepth varies path length quantifier")
    void propertyGraph_maxDepthVaries() {
        String sql2 = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(2);
        assertTrue(sql2.contains("{1,2}"), "maxDepth=2 should produce {1,2}");

        String sql5 = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(5);
        assertTrue(sql5.contains("{1,5}"), "maxDepth=5 should produce {1,5}");
    }

    @Test
    @Order(35)
    @DisplayName("Property Graph path SQL traverses to ancestors")
    void propertyGraphPath_traversesAncestors() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphPathSql();

        assertTrue(sql.contains("GRAPH_TABLE"), "Should use GRAPH_TABLE");
        assertTrue(sql.contains("MATCH"), "Should use MATCH");
        assertTrue(sql.contains("child_of"), "Should traverse child_of edges");
        assertTrue(sql.contains("{0,10}"),
                "Should allow variable-length path including self (0 hops)");
    }

    @Test
    @Order(36)
    @DisplayName("Property Graph path SQL extracts leaf and ancestor info")
    void propertyGraphPath_extractsLeafAndAncestor() {
        String sql = ComplexJoinBenchmarkTest.buildPropertyGraphPathSql();

        assertTrue(sql.contains("leaf_id"), "Should extract leaf_id");
        assertTrue(sql.contains("ancestor_id"), "Should extract ancestor_id");
        assertTrue(sql.contains("ancestor_name"), "Should extract ancestor_name");
    }

    // =========================================================================
    // Query Equivalence Tests - All three approaches should answer the same question
    // =========================================================================

    @Test
    @Order(40)
    @DisplayName("All three descendant queries start from root categories")
    void allApproaches_startFromRoots() {
        // MongoDB: $match parent_id = null
        List<Bson> mongoPipeline = ComplexJoinBenchmarkTest.buildGraphLookupPipeline(3);
        String mongoJson = mongoPipeline.get(0).toBsonDocument().toJson();
        assertTrue(mongoJson.contains("parent_id"), "MongoDB should filter on parent_id");

        // CTE: WHERE parent_id IS NULL
        String cteSql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(3);
        assertTrue(cteSql.contains("parent_id IS NULL"), "CTE should filter root categories");

        // Property Graph: WHERE r.parent_id IS NULL
        String pgSql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(3);
        assertTrue(pgSql.contains("parent_id IS NULL"), "Property Graph should filter roots");
    }

    @Test
    @Order(41)
    @DisplayName("All descendant queries use same maxDepth semantics")
    void allApproaches_sameMaxDepthSemantics() {
        int maxDepth = 5;

        // All three should constrain traversal to maxDepth
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildGraphLookupPipeline(maxDepth);
        String mongoJson = pipeline.get(1).toBsonDocument().toJson();
        assertTrue(mongoJson.contains("5"), "MongoDB maxDepth=5");

        String cteSql = ComplexJoinBenchmarkTest.buildRecursiveCteSql(maxDepth);
        assertTrue(cteSql.contains("ct.depth < 5"), "CTE depth < 5");

        String pgSql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(maxDepth);
        assertTrue(pgSql.contains("{1,5}"), "PG path length {1,5}");
    }

    // =========================================================================
    // Edge Direction Correctness Tests
    // =========================================================================

    @Test
    @Order(50)
    @DisplayName("Property Graph edge direction: child_of goes child -> parent")
    void propertyGraph_edgeDirection_childToParent() {
        String descendantSql = ComplexJoinBenchmarkTest.buildPropertyGraphDescendantSql(3);

        // For descendants: start from descendant, follow child_of edges TO root
        // Pattern: (descendant) -[child_of]-> (root)
        // The arrow -> means "follows edge direction" i.e. child -> parent
        assertTrue(descendantSql.contains("-[e IS child_of]->"),
                "Descendant query should follow child_of edges from child toward parent");
    }

    @Test
    @Order(51)
    @DisplayName("Property Graph path reconstruction follows child_of direction")
    void propertyGraph_pathDirection() {
        String pathSql = ComplexJoinBenchmarkTest.buildPropertyGraphPathSql();

        // For path reconstruction: leaf -[child_of]-> ancestor chain
        assertTrue(pathSql.contains("-[e IS child_of]->"),
                "Path query should follow child_of edges from leaf toward ancestors");
    }

    // =========================================================================
    // X0: M:N Query Builder Tests
    // =========================================================================

    @Test
    @Order(60)
    @DisplayName("X0: M:N $lookup pipeline has 4 stages")
    void m2m_lookupPipeline_hasCorrectStages() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildM2MLookupPipeline();
        assertEquals(4, pipeline.size(), "M:N pipeline should have 4 stages: lookup, unwind, lookup, unwind");
        String json = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json.contains("$lookup"), "First stage should be $lookup");
        assertTrue(json.contains("product_categories"), "Should lookup product_categories");
    }

    @Test
    @Order(61)
    @DisplayName("X0: M:N JSON SQL joins 3 tables")
    void m2m_jsonSql_joins3Tables() {
        String sql = ComplexJoinBenchmarkTest.buildM2MJsonSql();
        assertTrue(sql.contains("benchmark_products"), "Should reference products");
        assertTrue(sql.contains("benchmark_product_categories"), "Should reference junction table");
        assertTrue(sql.contains("benchmark_categories"), "Should reference categories");
        assertTrue(sql.contains("JSON_VALUE"), "Should use JSON_VALUE");
        assertEquals(2, sql.split("JOIN").length - 1, "Should have 2 JOINs");
    }

    @Test
    @Order(62)
    @DisplayName("X0: M:N relational SQL joins 3 tables")
    void m2m_relSql_joins3Tables() {
        String sql = ComplexJoinBenchmarkTest.buildM2MRelSql();
        assertTrue(sql.contains("benchmark_products_rel"), "Should reference relational products");
        assertTrue(sql.contains("benchmark_product_categories_rel"), "Should reference relational junction");
        assertTrue(sql.contains("benchmark_categories_rel"), "Should reference relational categories");
        assertFalse(sql.contains("JSON_VALUE"), "Should NOT use JSON_VALUE");
    }

    // =========================================================================
    // X1: Hierarchical Query Builder Tests
    // =========================================================================

    @Test
    @Order(70)
    @DisplayName("X1: 2-level pipeline has customer→orders lookup")
    void hier_2level_pipeline() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildHierLookupPipeline(2);
        assertEquals(2, pipeline.size(), "2-level should have 2 stages: lookup + unwind");
        String json = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json.contains("benchmark_orders"), "Should lookup orders");
        assertTrue(json.contains("customer_id"), "Should join on customer_id");
    }

    @Test
    @Order(71)
    @DisplayName("X1: 3-level pipeline adds items lookup")
    void hier_3level_pipeline() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildHierLookupPipeline(3);
        assertEquals(4, pipeline.size(), "3-level should have 4 stages");
        String json = pipeline.get(2).toBsonDocument().toJson();
        assertTrue(json.contains("benchmark_order_items"), "Should lookup order_items at level 3");
    }

    @Test
    @Order(72)
    @DisplayName("X1: 4-level pipeline adds shipments lookup")
    void hier_4level_pipeline() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildHierLookupPipeline(4);
        assertEquals(5, pipeline.size(), "4-level should have 5 stages");
        String lastJson = pipeline.get(4).toBsonDocument().toJson();
        assertTrue(lastJson.contains("benchmark_shipments"), "Should lookup shipments at level 4");
    }

    @Test
    @Order(73)
    @DisplayName("X1: Hierarchical JSON SQL varies by level count")
    void hier_jsonSql_variesByLevel() {
        String sql2 = ComplexJoinBenchmarkTest.buildHierJsonSql(2);
        assertFalse(sql2.contains("benchmark_order_items"), "2-level should not include items");

        String sql3 = ComplexJoinBenchmarkTest.buildHierJsonSql(3);
        assertTrue(sql3.contains("benchmark_order_items"), "3-level should include items");
        assertFalse(sql3.contains("benchmark_shipments"), "3-level should not include shipments");

        String sql4 = ComplexJoinBenchmarkTest.buildHierJsonSql(4);
        assertTrue(sql4.contains("benchmark_shipments"), "4-level should include shipments");
    }

    @Test
    @Order(74)
    @DisplayName("X1: Hierarchical relational SQL uses proper join columns")
    void hier_relSql_usesProperColumns() {
        String sql = ComplexJoinBenchmarkTest.buildHierRelSql(4);
        assertTrue(sql.contains("c.customer_id = o.customer_id"), "Should join customer to orders");
        assertTrue(sql.contains("o.order_id = i.order_id"), "Should join orders to items");
        assertTrue(sql.contains("i.item_id = s.item_id"), "Should join items to shipments");
    }

    // =========================================================================
    // X2: M:N + Child 1:N Query Builder Tests
    // =========================================================================

    @Test
    @Order(80)
    @DisplayName("X2: M:N+Child pipeline has products→items→shipments")
    void m2nChild_pipeline_structure() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildM2NChildLookupPipeline();
        assertEquals(3, pipeline.size(), "Should have 3 stages: lookup items, unwind, lookup shipments");
        String json0 = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json0.contains("benchmark_order_items"), "First lookup should be order_items");
        String json2 = pipeline.get(2).toBsonDocument().toJson();
        assertTrue(json2.contains("benchmark_shipments"), "Third stage should lookup shipments");
    }

    @Test
    @Order(81)
    @DisplayName("X2: M:N+Child JSON SQL uses LEFT JOIN for shipments")
    void m2nChild_jsonSql_leftJoinShipments() {
        String sql = ComplexJoinBenchmarkTest.buildM2NChildJsonSql();
        assertTrue(sql.contains("LEFT JOIN " + "benchmark_shipments"), "Shipments should use LEFT JOIN");
        assertTrue(sql.contains("JSON_VALUE(i.data, '$._id')"), "Should join items to shipments via item _id");
    }

    @Test
    @Order(82)
    @DisplayName("X2: M:N+Child aggregate SQL has GROUP BY")
    void m2nChild_aggregateSql_hasGroupBy() {
        String jsonSql = ComplexJoinBenchmarkTest.buildM2NChildAggregateJsonSql();
        assertTrue(jsonSql.contains("GROUP BY"), "JSON aggregate should have GROUP BY");
        assertTrue(jsonSql.contains("COUNT"), "Should have COUNT");
        assertTrue(jsonSql.contains("SUM"), "Should have SUM");

        String relSql = ComplexJoinBenchmarkTest.buildM2NChildAggregateRelSql();
        assertTrue(relSql.contains("GROUP BY p.product_id"), "Rel aggregate should GROUP BY product_id");
    }

    // =========================================================================
    // X3: M:N + Both 1:N Query Builder Tests
    // =========================================================================

    @Test
    @Order(85)
    @DisplayName("X3: M:N+Both pipeline has 6 stages (reviews + junction + categories + rules)")
    void m2nBoth_pipeline_structure() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildM2NBothLookupPipeline();
        assertEquals(6, pipeline.size(), "Should have 6 stages");
        String json0 = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json0.contains("benchmark_reviews"), "First lookup should be reviews");
        String json5 = pipeline.get(5).toBsonDocument().toJson();
        assertTrue(json5.contains("benchmark_category_rules"), "Last lookup should be category_rules");
    }

    @Test
    @Order(86)
    @DisplayName("X3: M:N+Both JSON SQL joins 5 tables")
    void m2nBoth_jsonSql_joins5Tables() {
        String sql = ComplexJoinBenchmarkTest.buildM2NBothJsonSql();
        assertTrue(sql.contains("benchmark_products"), "Should reference products");
        assertTrue(sql.contains("benchmark_reviews"), "Should reference reviews");
        assertTrue(sql.contains("benchmark_product_categories"), "Should reference junction");
        assertTrue(sql.contains("benchmark_categories"), "Should reference categories");
        assertTrue(sql.contains("benchmark_category_rules"), "Should reference rules");
    }

    @Test
    @Order(87)
    @DisplayName("X3: M:N+Both filtered pipeline starts with $match")
    void m2nBoth_filteredPipeline_startsWithMatch() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildM2NBothFilteredPipeline();
        String json0 = pipeline.get(0).toBsonDocument().toJson();
        assertTrue(json0.contains("$match"), "Filtered pipeline should start with $match");
        assertTrue(json0.contains("price"), "Match should filter on price");
    }

    @Test
    @Order(88)
    @DisplayName("X3: M:N+Both filtered SQL has WHERE clause")
    void m2nBoth_filteredSql_hasWhereClause() {
        String jsonSql = ComplexJoinBenchmarkTest.buildM2NBothFilteredJsonSql();
        assertTrue(jsonSql.contains("WHERE"), "JSON filtered SQL should have WHERE");
        assertTrue(jsonSql.contains("price"), "Should filter on price");
        assertTrue(jsonSql.contains("> 50"), "Should filter price > 50");

        String relSql = ComplexJoinBenchmarkTest.buildM2NBothFilteredRelSql();
        assertTrue(relSql.contains("WHERE p.price > 50"), "Rel filtered SQL should have WHERE p.price > 50");
    }

    // =========================================================================
    // X5: Diamond Pattern Query Builder Tests
    // =========================================================================

    @Test
    @Order(90)
    @DisplayName("X5: Diamond pipeline has 10 stages (7 lookups + 3 unwinds)")
    void diamond_pipeline_structure() {
        List<Bson> pipeline = ComplexJoinBenchmarkTest.buildDiamondLookupPipeline();
        assertEquals(10, pipeline.size(), "Diamond should have 10 stages");
        // Verify key lookups exist
        String allJson = pipeline.stream().map(s -> s.toBsonDocument().toJson()).reduce("", String::concat);
        assertTrue(allJson.contains("benchmark_customers"), "Should lookup customers");
        assertTrue(allJson.contains("benchmark_regions"), "Should lookup regions");
        assertTrue(allJson.contains("benchmark_order_items"), "Should lookup order_items");
        assertTrue(allJson.contains("benchmark_products"), "Should lookup products");
        assertTrue(allJson.contains("benchmark_suppliers"), "Should lookup suppliers");
    }

    @Test
    @Order(91)
    @DisplayName("X5: Diamond JSON SQL joins 7 tables")
    void diamond_jsonSql_joins7Tables() {
        String sql = ComplexJoinBenchmarkTest.buildDiamondJsonSql();
        assertEquals(6, sql.split("JOIN").length - 1, "Should have 6 JOINs (7 tables)");
        assertTrue(sql.contains("benchmark_orders"), "Should reference orders");
        assertTrue(sql.contains("benchmark_customers"), "Should reference customers");
        assertTrue(sql.contains("benchmark_suppliers"), "Should reference suppliers");
    }

    @Test
    @Order(92)
    @DisplayName("X5: Diamond relational SQL has proper join chain")
    void diamond_relSql_properJoinChain() {
        String sql = ComplexJoinBenchmarkTest.buildDiamondRelSql();
        assertTrue(sql.contains("o.customer_id = c.customer_id"), "Orders→Customers join");
        assertTrue(sql.contains("c.region_id = cr.region_id"), "Customers→Regions join");
        assertTrue(sql.contains("o.order_id = i.order_id"), "Orders→Items join");
        assertTrue(sql.contains("i.product_id = p.product_id"), "Items→Products join");
        assertTrue(sql.contains("p.supplier_id = s.supplier_id"), "Products→Suppliers join");
        assertTrue(sql.contains("s.region_id = sr.region_id"), "Suppliers→Regions join");
    }

    @Test
    @Order(93)
    @DisplayName("X5: Diamond aggregate SQL has GROUP BY region")
    void diamond_aggregateSql_groupByRegion() {
        String jsonSql = ComplexJoinBenchmarkTest.buildDiamondAggregateJsonSql();
        assertTrue(jsonSql.contains("GROUP BY"), "Should have GROUP BY");
        assertTrue(jsonSql.contains("COUNT"), "Should have COUNT");
        assertTrue(jsonSql.contains("SUM"), "Should have SUM");

        String relSql = ComplexJoinBenchmarkTest.buildDiamondAggregateRelSql();
        assertTrue(relSql.contains("GROUP BY cr.name"), "Should GROUP BY region name");
    }

    @Test
    @Order(94)
    @DisplayName("X5: Diamond filtered SQL has WHERE price > 50")
    void diamond_filteredSql_hasFilter() {
        String jsonSql = ComplexJoinBenchmarkTest.buildDiamondFilteredJsonSql();
        assertTrue(jsonSql.contains("WHERE"), "Should have WHERE clause");
        assertTrue(jsonSql.contains("price"), "Should filter on price");

        String relSql = ComplexJoinBenchmarkTest.buildDiamondFilteredRelSql();
        assertTrue(relSql.contains("WHERE p.price > 50"), "Should have WHERE p.price > 50");
    }

    // =========================================================================
    // Cross-Category Query Equivalence Tests
    // =========================================================================

    @Test
    @Order(100)
    @DisplayName("All JSON queries use JSON_VALUE for join conditions")
    void allJsonQueries_useJsonValue() {
        String[] jsonSqls = {
                ComplexJoinBenchmarkTest.buildM2MJsonSql(),
                ComplexJoinBenchmarkTest.buildHierJsonSql(4),
                ComplexJoinBenchmarkTest.buildM2NChildJsonSql(),
                ComplexJoinBenchmarkTest.buildM2NBothJsonSql(),
                ComplexJoinBenchmarkTest.buildDiamondJsonSql()
        };
        for (String sql : jsonSqls) {
            assertTrue(sql.contains("JSON_VALUE"), "All JSON queries should use JSON_VALUE: " + sql.substring(0, 40));
        }
    }

    @Test
    @Order(101)
    @DisplayName("No relational queries use JSON_VALUE")
    void allRelQueries_noJsonValue() {
        String[] relSqls = {
                ComplexJoinBenchmarkTest.buildM2MRelSql(),
                ComplexJoinBenchmarkTest.buildHierRelSql(4),
                ComplexJoinBenchmarkTest.buildM2NChildRelSql(),
                ComplexJoinBenchmarkTest.buildM2NBothRelSql(),
                ComplexJoinBenchmarkTest.buildDiamondRelSql()
        };
        for (String sql : relSqls) {
            assertFalse(sql.contains("JSON_VALUE"), "Rel queries should NOT use JSON_VALUE: " + sql.substring(0, 40));
        }
    }
}
