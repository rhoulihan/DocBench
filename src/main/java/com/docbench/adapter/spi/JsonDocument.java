package com.docbench.adapter.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a JSON document for benchmarking operations.
 * Provides a platform-agnostic document representation.
 */
public final class JsonDocument {

    private final String id;
    private final Map<String, Object> content;

    private JsonDocument(String id, Map<String, Object> content) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        // Use LinkedHashMap to preserve field insertion order for position-sensitive tests
        this.content = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(content, "content must not be null")));
    }

    /**
     * Creates a new JsonDocument with the given id and content.
     */
    public static JsonDocument of(String id, Map<String, Object> content) {
        return new JsonDocument(id, content);
    }

    /**
     * Creates a new builder for constructing JsonDocument instances.
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Returns the document ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the document content as an unmodifiable map.
     */
    public Map<String, Object> getContent() {
        return content;
    }

    /**
     * Returns the value at the specified path, or null if not found.
     * Supports dot notation for nested access (e.g., "customer.address.city").
     */
    @SuppressWarnings("unchecked")
    public Object getPath(String path) {
        Objects.requireNonNull(path, "path must not be null");

        String[] segments = path.split("\\.");
        Object current = content;

        for (String segment : segments) {
            if (current == null) {
                return null;
            }

            // Handle array index notation (e.g., "items[5]")
            int bracketIndex = segment.indexOf('[');
            if (bracketIndex != -1) {
                String fieldName = segment.substring(0, bracketIndex);
                int arrayIndex = Integer.parseInt(
                        segment.substring(bracketIndex + 1, segment.indexOf(']'))
                );

                if (current instanceof Map<?, ?> map) {
                    Object arrayObj = map.get(fieldName);
                    if (arrayObj instanceof List<?> list && arrayIndex < list.size()) {
                        current = list.get(arrayIndex);
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(segment);
                } else {
                    return null;
                }
            }
        }

        return current;
    }

    /**
     * Returns true if a value exists at the specified path.
     */
    public boolean hasPath(String path) {
        return getPath(path) != null;
    }

    /**
     * Returns the estimated size of this document in bytes.
     */
    public int estimatedSizeBytes() {
        return estimateSize(content);
    }

    @SuppressWarnings("unchecked")
    private int estimateSize(Object obj) {
        if (obj == null) {
            return 4; // null representation
        }
        if (obj instanceof String s) {
            return s.length() * 2 + 4; // UTF-16 + overhead
        }
        if (obj instanceof Number) {
            return 8; // assume 64-bit
        }
        if (obj instanceof Boolean) {
            return 1;
        }
        if (obj instanceof Map<?, ?> map) {
            int size = 4; // overhead
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size += estimateSize(entry.getKey());
                size += estimateSize(entry.getValue());
            }
            return size;
        }
        if (obj instanceof List<?> list) {
            int size = 4; // overhead
            for (Object item : list) {
                size += estimateSize(item);
            }
            return size;
        }
        return 8; // unknown type, assume 8 bytes
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JsonDocument that = (JsonDocument) o;
        return Objects.equals(id, that.id) && Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, content);
    }

    @Override
    public String toString() {
        return "JsonDocument{id='" + id + "', content=" + content + "}";
    }

    /**
     * Builder for constructing JsonDocument instances.
     */
    public static final class Builder {
        private final String id;
        private final Map<String, Object> content = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id must not be null");
        }

        /**
         * Adds a field to the document.
         */
        public Builder field(String name, Object value) {
            content.put(Objects.requireNonNull(name), value);
            return this;
        }

        /**
         * Adds a nested object to the document.
         */
        public Builder nestedObject(String name, Map<String, Object> nested) {
            content.put(Objects.requireNonNull(name), Map.copyOf(nested));
            return this;
        }

        /**
         * Adds an array to the document.
         */
        public Builder array(String name, List<?> array) {
            content.put(Objects.requireNonNull(name), List.copyOf(array));
            return this;
        }

        /**
         * Adds all fields from the given map.
         */
        public Builder fields(Map<String, Object> fields) {
            content.putAll(Objects.requireNonNull(fields));
            return this;
        }

        /**
         * Builds the JsonDocument.
         */
        public JsonDocument build() {
            // Automatically add _id field if not present
            if (!content.containsKey("_id")) {
                content.put("_id", id);
            }
            return new JsonDocument(id, content);
        }
    }
}
