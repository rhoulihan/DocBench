package com.docbench.adapter.spi;

import java.util.List;
import java.util.Objects;

/**
 * Sealed interface for type-safe operation definitions.
 * Each operation type captures the data needed for execution and timing.
 */
public sealed interface Operation
        permits InsertOperation, ReadOperation, UpdateOperation, DeleteOperation, AggregateOperation {

    /**
     * Unique identifier for this operation instance.
     * Used for correlating timing events and results.
     */
    String operationId();

    /**
     * The type of this operation.
     */
    OperationType type();
}

/**
 * Enumeration of operation types.
 */
enum OperationType {
    INSERT,
    READ,
    UPDATE,
    DELETE,
    AGGREGATE
}

/**
 * Read preference for replica set deployments.
 */
enum ReadPreference {
    /**
     * Read from primary only.
     */
    PRIMARY,

    /**
     * Read from primary preferred, secondary if unavailable.
     */
    PRIMARY_PREFERRED,

    /**
     * Read from secondary only.
     */
    SECONDARY,

    /**
     * Read from secondary preferred, primary if unavailable.
     */
    SECONDARY_PREFERRED,

    /**
     * Read from nearest member.
     */
    NEAREST
}

/**
 * Insert operation for creating new documents.
 */
record InsertOperation(
        String operationId,
        JsonDocument document
) implements Operation {

    public InsertOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }

    @Override
    public OperationType type() {
        return OperationType.INSERT;
    }
}

/**
 * Read operation for retrieving documents.
 */
record ReadOperation(
        String operationId,
        String documentId,
        List<String> projectionPaths,  // Empty = full document
        ReadPreference readPreference
) implements Operation {

    public ReadOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(projectionPaths, "projectionPaths must not be null");
        Objects.requireNonNull(readPreference, "readPreference must not be null");
        projectionPaths = List.copyOf(projectionPaths);
    }

    /**
     * Creates a read operation for full document retrieval.
     */
    public static ReadOperation fullDocument(String operationId, String documentId) {
        return new ReadOperation(operationId, documentId, List.of(), ReadPreference.PRIMARY);
    }

    /**
     * Creates a read operation with projection.
     */
    public static ReadOperation withProjection(String operationId, String documentId, List<String> paths) {
        return new ReadOperation(operationId, documentId, paths, ReadPreference.PRIMARY);
    }

    @Override
    public OperationType type() {
        return OperationType.READ;
    }

    /**
     * Returns true if this is a partial document retrieval.
     */
    public boolean hasProjection() {
        return !projectionPaths.isEmpty();
    }
}

/**
 * Update operation for modifying documents.
 */
record UpdateOperation(
        String operationId,
        String documentId,
        String updatePath,
        Object newValue,
        boolean upsert
) implements Operation {

    public UpdateOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(updatePath, "updatePath must not be null");
    }

    @Override
    public OperationType type() {
        return OperationType.UPDATE;
    }
}

/**
 * Delete operation for removing documents.
 */
record DeleteOperation(
        String operationId,
        String documentId
) implements Operation {

    public DeleteOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
    }

    @Override
    public OperationType type() {
        return OperationType.DELETE;
    }
}

/**
 * Aggregate operation for complex queries.
 */
record AggregateOperation(
        String operationId,
        List<String> pipeline,
        boolean explain
) implements Operation {

    public AggregateOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        pipeline = List.copyOf(pipeline);
    }

    @Override
    public OperationType type() {
        return OperationType.AGGREGATE;
    }
}
