package com.docbench.adapter.spi;

import java.util.List;
import java.util.Objects;

/**
 * Result of configuration or input validation.
 */
public record ValidationResult(
        boolean valid,
        List<ValidationError> errors
) {

    public ValidationResult {
        Objects.requireNonNull(errors);
        errors = List.copyOf(errors);
    }

    /**
     * Creates a successful validation result.
     */
    public static ValidationResult success() {
        return new ValidationResult(true, List.of());
    }

    /**
     * Creates a failed validation result with a single error.
     */
    public static ValidationResult failure(String field, String message) {
        return new ValidationResult(false, List.of(new ValidationError(field, message)));
    }

    /**
     * Creates a failed validation result with multiple errors.
     */
    public static ValidationResult failure(List<ValidationError> errors) {
        return new ValidationResult(false, errors);
    }

    /**
     * Returns true if validation passed.
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Returns true if validation failed.
     */
    public boolean isInvalid() {
        return !valid;
    }

    /**
     * Returns the first error message, or empty string if valid.
     */
    public String firstErrorMessage() {
        return errors.isEmpty() ? "" : errors.get(0).message();
    }

    /**
     * Returns all error messages joined by newlines.
     */
    public String allErrorMessages() {
        return errors.stream()
                .map(e -> e.field() + ": " + e.message())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * Throws an exception if validation failed.
     */
    public void throwIfInvalid() {
        if (!valid) {
            throw new IllegalArgumentException("Validation failed: " + allErrorMessages());
        }
    }

    /**
     * A single validation error.
     */
    public record ValidationError(String field, String message) {
        public ValidationError {
            Objects.requireNonNull(field);
            Objects.requireNonNull(message);
        }
    }
}
