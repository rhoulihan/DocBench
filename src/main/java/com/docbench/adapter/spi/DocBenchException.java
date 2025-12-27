package com.docbench.adapter.spi;

/**
 * Base exception for DocBench errors.
 */
public class DocBenchException extends RuntimeException {

    public DocBenchException(String message) {
        super(message);
    }

    public DocBenchException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Exception thrown when a database connection fails.
 */
class ConnectionException extends DocBenchException {

    private final String adapterId;

    public ConnectionException(String adapterId, String message) {
        super(message);
        this.adapterId = adapterId;
    }

    public ConnectionException(String adapterId, String message, Throwable cause) {
        super(message, cause);
        this.adapterId = adapterId;
    }

    public String getAdapterId() {
        return adapterId;
    }
}

/**
 * Exception thrown when an operation fails.
 */
class OperationException extends DocBenchException {

    private final String operationId;
    private final OperationType operationType;

    public OperationException(String operationId, OperationType type, String message) {
        super(message);
        this.operationId = operationId;
        this.operationType = type;
    }

    public OperationException(String operationId, OperationType type, String message, Throwable cause) {
        super(message, cause);
        this.operationId = operationId;
        this.operationType = type;
    }

    public String getOperationId() {
        return operationId;
    }

    public OperationType getOperationType() {
        return operationType;
    }
}

/**
 * Exception thrown when test environment setup fails.
 */
class SetupException extends DocBenchException {

    public SetupException(String message) {
        super(message);
    }

    public SetupException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Exception thrown when configuration is invalid.
 */
class ConfigurationException extends DocBenchException {

    private final ValidationResult validationResult;

    public ConfigurationException(String message) {
        super(message);
        this.validationResult = ValidationResult.failure("config", message);
    }

    public ConfigurationException(ValidationResult validationResult) {
        super("Configuration validation failed: " + validationResult.allErrorMessages());
        this.validationResult = validationResult;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }
}

/**
 * Exception thrown when a capability is not supported.
 */
class CapabilityNotSupportedException extends DocBenchException {

    private final Capability capability;
    private final String adapterId;

    public CapabilityNotSupportedException(Capability capability, String adapterId) {
        super("Capability " + capability + " not supported by adapter: " + adapterId);
        this.capability = capability;
        this.adapterId = adapterId;
    }

    public Capability getCapability() {
        return capability;
    }

    public String getAdapterId() {
        return adapterId;
    }
}
