package com.docbench.adapter.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for test environment setup.
 * Defines collection names, indexes, and initial data.
 */
public final class TestEnvironmentConfig {

    private final String collectionName;
    private final List<IndexDefinition> indexes;
    private final boolean dropExisting;
    private final int initialDocumentCount;
    private final Map<String, Object> platformOptions;

    private TestEnvironmentConfig(Builder builder) {
        this.collectionName = builder.collectionName;
        this.indexes = List.copyOf(builder.indexes);
        this.dropExisting = builder.dropExisting;
        this.initialDocumentCount = builder.initialDocumentCount;
        this.platformOptions = Map.copyOf(builder.platformOptions);
    }

    /**
     * Creates a default test environment configuration.
     */
    public static TestEnvironmentConfig defaults() {
        return builder().build();
    }

    /**
     * Returns a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    public String collectionName() {
        return collectionName;
    }

    public List<IndexDefinition> indexes() {
        return indexes;
    }

    public boolean dropExisting() {
        return dropExisting;
    }

    public int initialDocumentCount() {
        return initialDocumentCount;
    }

    public Map<String, Object> platformOptions() {
        return platformOptions;
    }

    @SuppressWarnings("unchecked")
    public <T> T getPlatformOption(String key, T defaultValue) {
        Object value = platformOptions.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Index definition for test environment.
     */
    public record IndexDefinition(
            String name,
            List<String> fields,
            boolean unique,
            boolean sparse
    ) {
        public IndexDefinition {
            Objects.requireNonNull(name);
            Objects.requireNonNull(fields);
            fields = List.copyOf(fields);
        }

        public static IndexDefinition on(String... fields) {
            return new IndexDefinition(
                    "idx_" + String.join("_", fields),
                    List.of(fields),
                    false,
                    false
            );
        }

        public static IndexDefinition unique(String... fields) {
            return new IndexDefinition(
                    "uidx_" + String.join("_", fields),
                    List.of(fields),
                    true,
                    false
            );
        }
    }

    /**
     * Builder for TestEnvironmentConfig.
     */
    public static final class Builder {
        private String collectionName = "benchmark_docs";
        private final List<IndexDefinition> indexes = new java.util.ArrayList<>();
        private boolean dropExisting = true;
        private int initialDocumentCount = 0;
        private final Map<String, Object> platformOptions = new java.util.HashMap<>();

        private Builder() {
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = Objects.requireNonNull(collectionName);
            return this;
        }

        public Builder addIndex(IndexDefinition index) {
            this.indexes.add(Objects.requireNonNull(index));
            return this;
        }

        public Builder indexes(List<IndexDefinition> indexes) {
            this.indexes.clear();
            this.indexes.addAll(Objects.requireNonNull(indexes));
            return this;
        }

        public Builder dropExisting(boolean dropExisting) {
            this.dropExisting = dropExisting;
            return this;
        }

        public Builder initialDocumentCount(int count) {
            this.initialDocumentCount = count;
            return this;
        }

        public Builder platformOption(String key, Object value) {
            this.platformOptions.put(key, value);
            return this;
        }

        public TestEnvironmentConfig build() {
            return new TestEnvironmentConfig(this);
        }
    }
}
