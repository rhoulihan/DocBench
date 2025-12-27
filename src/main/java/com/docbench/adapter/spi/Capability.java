package com.docbench.adapter.spi;

/**
 * Capabilities that determine workload compatibility.
 * Adapters declare supported capabilities; workloads require specific capabilities.
 *
 * <p>These capabilities enable the framework to validate that a workload
 * can run on a given adapter before execution.
 */
public enum Capability {

    // Document access patterns
    /**
     * Supports deep path queries (a.b.c.d).
     */
    NESTED_DOCUMENT_ACCESS,

    /**
     * Supports array element projection (items[5]).
     */
    ARRAY_INDEX_ACCESS,

    /**
     * Supports projection/field selection for partial retrieval.
     */
    PARTIAL_DOCUMENT_RETRIEVAL,

    /**
     * Supports wildcard path patterns (items[*].sku).
     */
    WILDCARD_PATH_ACCESS,

    // Operations
    /**
     * Supports batch document insertion.
     */
    BULK_INSERT,

    /**
     * Supports batch document updates.
     */
    BULK_UPDATE,

    /**
     * Supports batch document retrieval.
     */
    BULK_READ,

    // Topology
    /**
     * Supports distributed data partitioning.
     */
    SHARDING,

    /**
     * Supports read replicas.
     */
    REPLICATION,

    // Indexing
    /**
     * Supports non-primary key indexes.
     */
    SECONDARY_INDEXES,

    /**
     * Supports multi-field composite indexes.
     */
    COMPOUND_INDEXES,

    /**
     * Supports indexes on nested JSON paths.
     */
    JSON_PATH_INDEXES,

    // Transactions
    /**
     * Provides atomic single-document operations.
     */
    SINGLE_DOCUMENT_ATOMICITY,

    /**
     * Provides ACID transactions across multiple documents.
     */
    MULTI_DOCUMENT_TRANSACTIONS,

    // Instrumentation (CRITICAL for DocBench)
    /**
     * Provides database-reported execution timing.
     */
    SERVER_EXECUTION_TIME,

    /**
     * Provides format-specific navigation timing.
     * Essential for BSON vs OSON traversal comparison.
     */
    SERVER_TRAVERSAL_TIME,

    /**
     * Provides query plan access via EXPLAIN.
     */
    EXPLAIN_PLAN,

    /**
     * Provides operation-level profiling.
     */
    PROFILING,

    /**
     * Provides driver-level instrumentation hooks.
     */
    CLIENT_TIMING_HOOKS,

    /**
     * Provides client-side decode timing metrics.
     */
    DESERIALIZATION_METRICS
}
