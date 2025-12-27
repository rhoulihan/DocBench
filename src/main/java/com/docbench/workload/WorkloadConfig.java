package com.docbench.workload;

import java.util.*;

/**
 * Configuration for benchmark workloads.
 * Immutable and validated configuration with type-safe parameter access.
 */
public final class WorkloadConfig {

    private final String name;
    private final int iterations;
    private final int warmupIterations;
    private final Long seed;
    private final int concurrency;
    private final Map<String, Object> parameters;
    private final Set<String> requiredParameters;
    private final Map<String, int[]> parameterRanges;

    private WorkloadConfig(Builder builder) {
        this.name = builder.name;
        this.iterations = builder.iterations;
        this.warmupIterations = builder.warmupIterations;
        this.seed = builder.seed;
        this.concurrency = builder.concurrency;
        this.parameters = Map.copyOf(builder.parameters);
        this.requiredParameters = Set.copyOf(builder.requiredParameters);
        this.parameterRanges = Map.copyOf(builder.parameterRanges);
    }

    /**
     * Creates a new builder with the specified workload name.
     */
    public static Builder builder(String name) {
        return new Builder(Objects.requireNonNull(name, "name must not be null"));
    }

    /**
     * Returns the workload name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the number of benchmark iterations.
     */
    public int iterations() {
        return iterations;
    }

    /**
     * Returns the number of warmup iterations.
     */
    public int warmupIterations() {
        return warmupIterations;
    }

    /**
     * Returns the random seed, or null for random seeding.
     */
    public Long seed() {
        return seed;
    }

    /**
     * Returns the concurrency level.
     */
    public int concurrency() {
        return concurrency;
    }

    /**
     * Returns all parameters as an unmodifiable map.
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * Returns an integer parameter value, or throws if not found.
     */
    public int getIntParameter(String name) {
        Object value = parameters.get(name);
        if (value == null) {
            throw new NoSuchElementException("Parameter not found: " + name);
        }
        return ((Number) value).intValue();
    }

    /**
     * Returns an integer parameter value, or the default if not found.
     */
    public int getIntParameter(String name, int defaultValue) {
        Object value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        return ((Number) value).intValue();
    }

    /**
     * Returns a string parameter value, or throws if not found.
     */
    public String getStringParameter(String name) {
        Object value = parameters.get(name);
        if (value == null) {
            throw new NoSuchElementException("Parameter not found: " + name);
        }
        return value.toString();
    }

    /**
     * Returns a string parameter value, or the default if not found.
     */
    public String getStringParameter(String name, String defaultValue) {
        Object value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * Returns a double parameter value, or the default if not found.
     */
    public double getDoubleParameter(String name, double defaultValue) {
        Object value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        return ((Number) value).doubleValue();
    }

    /**
     * Returns a boolean parameter value, or the default if not found.
     */
    public boolean getBooleanParameter(String name, boolean defaultValue) {
        Object value = parameters.get(name);
        if (value == null) {
            return defaultValue;
        }
        return (Boolean) value;
    }

    /**
     * Returns a list parameter value, or an empty list if not found.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getListParameter(String name) {
        Object value = parameters.get(name);
        if (value == null) {
            return List.of();
        }
        return (List<T>) value;
    }

    /**
     * Validates this configuration and returns a list of validation errors.
     * Returns an empty list if validation passes.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // Check required parameters
        for (String required : requiredParameters) {
            if (!parameters.containsKey(required)) {
                errors.add("Missing required parameter: " + required);
            }
        }

        // Check parameter ranges
        for (Map.Entry<String, int[]> entry : parameterRanges.entrySet()) {
            String paramName = entry.getKey();
            int[] range = entry.getValue();
            Object value = parameters.get(paramName);
            if (value instanceof Number num) {
                int intValue = num.intValue();
                if (intValue < range[0] || intValue > range[1]) {
                    errors.add("Parameter " + paramName + " value " + intValue +
                            " is outside valid range [" + range[0] + ", " + range[1] + "]");
                }
            }
        }

        return errors;
    }

    /**
     * Creates a new builder initialized with this config's values.
     */
    public Builder toBuilder() {
        Builder builder = new Builder(name);
        builder.iterations = this.iterations;
        builder.warmupIterations = this.warmupIterations;
        builder.seed = this.seed;
        builder.concurrency = this.concurrency;
        builder.parameters.putAll(this.parameters);
        builder.requiredParameters.addAll(this.requiredParameters);
        builder.parameterRanges.putAll(this.parameterRanges);
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkloadConfig that = (WorkloadConfig) o;
        return iterations == that.iterations &&
                warmupIterations == that.warmupIterations &&
                concurrency == that.concurrency &&
                Objects.equals(name, that.name) &&
                Objects.equals(seed, that.seed) &&
                Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, iterations, warmupIterations, seed, concurrency, parameters);
    }

    @Override
    public String toString() {
        return "WorkloadConfig{" +
                "name='" + name + '\'' +
                ", iterations=" + iterations +
                ", warmupIterations=" + warmupIterations +
                ", seed=" + seed +
                ", concurrency=" + concurrency +
                ", parameters=" + parameters +
                '}';
    }

    /**
     * Builder for WorkloadConfig.
     */
    public static final class Builder {
        private final String name;
        private int iterations = 1000;
        private int warmupIterations = 100;
        private Long seed = System.nanoTime();
        private int concurrency = 1;
        private final Map<String, Object> parameters = new HashMap<>();
        private final Set<String> requiredParameters = new HashSet<>();
        private final Map<String, int[]> parameterRanges = new HashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        /**
         * Sets the number of benchmark iterations.
         */
        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        /**
         * Sets the number of warmup iterations.
         */
        public Builder warmupIterations(int warmupIterations) {
            this.warmupIterations = warmupIterations;
            return this;
        }

        /**
         * Sets the random seed for reproducibility.
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Sets the concurrency level.
         */
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        /**
         * Adds a parameter.
         */
        public Builder parameter(String name, Object value) {
            parameters.put(Objects.requireNonNull(name), value);
            return this;
        }

        /**
         * Marks a parameter as required for validation.
         */
        public Builder requiredParameter(String name) {
            requiredParameters.add(Objects.requireNonNull(name));
            return this;
        }

        /**
         * Sets a valid range for an integer parameter.
         */
        public Builder parameterRange(String name, int min, int max) {
            parameterRanges.put(Objects.requireNonNull(name), new int[]{min, max});
            return this;
        }

        /**
         * Builds the WorkloadConfig.
         *
         * @throws IllegalArgumentException if validation fails
         */
        public WorkloadConfig build() {
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (iterations <= 0) {
                throw new IllegalArgumentException("iterations must be positive");
            }
            if (warmupIterations < 0) {
                throw new IllegalArgumentException("warmup iterations must not be negative");
            }
            return new WorkloadConfig(this);
        }
    }
}
