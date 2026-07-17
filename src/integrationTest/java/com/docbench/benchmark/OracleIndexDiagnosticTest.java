package com.docbench.benchmark;

import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Diagnostic test to verify Oracle index usage for vector search queries
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Oracle Index Diagnostic")
public class OracleIndexDiagnosticTest {

    private static Connection connection;

    @BeforeAll
    static void setup() throws Exception {
        Properties props = new Properties();
        try (InputStream is = new FileInputStream("config/local.properties")) {
            props.load(is);
        }

        String oracleUrl = props.getProperty("oracle.url");
        String oracleUser = props.getProperty("oracle.username");
        String oraclePass = props.getProperty("oracle.password");

        OracleDataSource ods = new OracleDataSource();
        ods.setURL(oracleUrl);
        ods.setUser(oracleUser);
        ods.setPassword(oraclePass);
        connection = ods.getConnection();
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    @Order(1)
    @DisplayName("List ACCOUNTS indexes")
    void listAccountsIndexes() throws SQLException {
        System.out.println("\n=== ACCOUNTS TABLE INDEXES ===");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT index_name, index_type, status FROM user_indexes " +
                 "WHERE table_name = 'BENCHMARK_ACCOUNTS_VEC' ORDER BY index_name")) {
            while (rs.next()) {
                System.out.printf("  %-30s %-20s %s%n",
                    rs.getString("index_name"),
                    rs.getString("index_type"),
                    rs.getString("status"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("List TRANSACTIONS indexes")
    void listTransactionsIndexes() throws SQLException {
        System.out.println("\n=== TRANSACTIONS TABLE INDEXES ===");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT index_name, index_type, status FROM user_indexes " +
                 "WHERE table_name = 'BENCHMARK_TRANSACTIONS_VEC' ORDER BY index_name")) {
            while (rs.next()) {
                System.out.printf("  %-30s %-20s %s%n",
                    rs.getString("index_name"),
                    rs.getString("index_type"),
                    rs.getString("status"));
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("Check Vector Index Stats")
    void checkVectorIndexStats() throws SQLException {
        System.out.println("\n=== VECTOR INDEX STATISTICS ===");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT INDEX_NAME, NUM_VECTORS, DIMENSION_COUNT, TYPE " +
                 "FROM VECSYS.VECTOR_INDEX_STATS WHERE INDEX_NAME LIKE '%ACCOUNTS%'")) {
            while (rs.next()) {
                System.out.printf("  Index: %s, Vectors: %d, Dimensions: %d, Type: %s%n",
                    rs.getString("INDEX_NAME"),
                    rs.getLong("NUM_VECTORS"),
                    rs.getInt("DIMENSION_COUNT"),
                    rs.getString("TYPE"));
            }
        } catch (SQLException e) {
            System.out.println("  Vector index stats not available: " + e.getMessage());
        }
    }

    @Test
    @Order(4)
    @DisplayName("Explain Plan: Filtered Vector Search (region)")
    void explainFilteredVectorSearchRegion() throws SQLException {
        System.out.println("\n=== EXPLAIN PLAN: FILTERED VECTOR SEARCH (REGION) ===");

        // Clear plan table
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'DIAG_REGION'");
        }

        // Explain the query
        String explainSql = """
            EXPLAIN PLAN SET STATEMENT_ID = 'DIAG_REGION' FOR
            SELECT /*+ PARALLEL(4) */ id, data,
                   VECTOR_DISTANCE(embedding, TO_VECTOR('[0.1,0.2,0.3]', 384, FLOAT64), COSINE) AS distance
            FROM BENCHMARK_ACCOUNTS_VEC
            WHERE region = 'NORTHEAST'
            ORDER BY distance
            FETCH FIRST 10 ROWS ONLY
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(explainSql);
        }

        // Display the plan
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'DIAG_REGION', 'ALL'))")) {
            while (rs.next()) {
                System.out.println(rs.getString(1));
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Explain Plan: Filtered Vector Search (compound)")
    void explainFilteredVectorSearchCompound() throws SQLException {
        System.out.println("\n=== EXPLAIN PLAN: FILTERED VECTOR SEARCH (COMPOUND) ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'DIAG_COMPOUND'");
        }

        String explainSql = """
            EXPLAIN PLAN SET STATEMENT_ID = 'DIAG_COMPOUND' FOR
            SELECT /*+ PARALLEL(4) */ id, data,
                   VECTOR_DISTANCE(embedding, TO_VECTOR('[0.1,0.2,0.3]', 384, FLOAT64), COSINE) AS distance
            FROM BENCHMARK_ACCOUNTS_VEC
            WHERE region = 'NORTHEAST' AND account_type = 'PREMIUM'
            ORDER BY distance
            FETCH FIRST 10 ROWS ONLY
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(explainSql);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'DIAG_COMPOUND', 'ALL'))")) {
            while (rs.next()) {
                System.out.println(rs.getString(1));
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Explain Plan: Shared Merchant Query (RAG1)")
    void explainSharedMerchantQuery() throws SQLException {
        System.out.println("\n=== EXPLAIN PLAN: SHARED MERCHANT QUERY (RAG1) ===");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'DIAG_RAG1'");
        }

        String explainSql = """
            EXPLAIN PLAN SET STATEMENT_ID = 'DIAG_RAG1' FOR
            WITH top_accounts AS (
                SELECT /*+ PARALLEL(4) */ id,
                       JSON_VALUE(data, '$.accountId') AS account_id,
                       VECTOR_DISTANCE(embedding, TO_VECTOR('[0.1,0.2,0.3]', 384, FLOAT64), COSINE) AS distance
                FROM BENCHMARK_ACCOUNTS_VEC
                ORDER BY distance
                FETCH FIRST 10 ROWS ONLY
            ),
            my_merchants AS (
                SELECT /*+ PARALLEL(4) */ DISTINCT ta.account_id, t.merchant
                FROM top_accounts ta
                JOIN BENCHMARK_TRANSACTIONS_VEC t ON t.account_id = ta.account_id
                WHERE t.transaction_date >= TRUNC(SYSDATE) - 90
            ),
            shared_accounts AS (
                SELECT /*+ PARALLEL(4) */ DISTINCT
                    mm.account_id AS source_account_id,
                    t.account_id AS related_account_id
                FROM my_merchants mm
                JOIN BENCHMARK_TRANSACTIONS_VEC t ON t.merchant = mm.merchant
                           AND t.transaction_date >= TRUNC(SYSDATE) - 90
                WHERE t.account_id != mm.account_id
            )
            SELECT sa.source_account_id,
                   sa.related_account_id,
                   'SHARED_MERCHANT' AS relationship_type,
                   1 AS hops
            FROM shared_accounts sa
            ORDER BY sa.source_account_id, sa.related_account_id
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(explainSql);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', 'DIAG_RAG1', 'ALL'))")) {
            while (rs.next()) {
                System.out.println(rs.getString(1));
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("Check table statistics")
    void checkTableStatistics() throws SQLException {
        System.out.println("\n=== TABLE STATISTICS ===");
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT table_name, num_rows, last_analyzed " +
                 "FROM user_tables WHERE table_name IN ('BENCHMARK_ACCOUNTS_VEC', 'BENCHMARK_TRANSACTIONS_VEC')")) {
            while (rs.next()) {
                System.out.printf("  %-15s rows: %-10s last_analyzed: %s%n",
                    rs.getString("table_name"),
                    rs.getString("num_rows"),
                    rs.getString("last_analyzed"));
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("Gather fresh statistics")
    void gatherStatistics() throws SQLException {
        System.out.println("\n=== GATHERING FRESH STATISTICS ===");
        try (CallableStatement cs = connection.prepareCall(
                "{call DBMS_STATS.GATHER_TABLE_STATS(ownname => 'DOCBENCH', tabname => 'BENCHMARK_ACCOUNTS_VEC', cascade => TRUE)}")) {
            cs.execute();
            System.out.println("  ACCOUNTS statistics gathered");
        }
        try (CallableStatement cs = connection.prepareCall(
                "{call DBMS_STATS.GATHER_TABLE_STATS(ownname => 'DOCBENCH', tabname => 'BENCHMARK_TRANSACTIONS_VEC', cascade => TRUE)}")) {
            cs.execute();
            System.out.println("  TRANSACTIONS statistics gathered");
        }
    }
}
