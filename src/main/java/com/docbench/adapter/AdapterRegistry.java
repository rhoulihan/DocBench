package com.docbench.adapter;

import com.docbench.adapter.mongodb.MongoDBAdapter;
import com.docbench.adapter.oracle.OracleOsonAdapter;
import com.docbench.adapter.spi.DatabaseAdapter;

import java.util.*;
import java.util.function.Supplier;

/**
 * Registry of available database adapters.
 * Provides factory methods for creating adapter instances.
 */
public final class AdapterRegistry {

    private static final Map<String, Supplier<DatabaseAdapter>> ADAPTERS = new LinkedHashMap<>();

    static {
        // Register built-in adapters
        register("mongodb", MongoDBAdapter::new);
        register("oracle-oson", OracleOsonAdapter::new);
    }

    private AdapterRegistry() {}

    /**
     * Registers an adapter factory.
     */
    public static void register(String id, Supplier<DatabaseAdapter> factory) {
        ADAPTERS.put(Objects.requireNonNull(id), Objects.requireNonNull(factory));
    }

    /**
     * Creates a new instance of the specified adapter.
     */
    public static DatabaseAdapter create(String id) {
        Supplier<DatabaseAdapter> factory = ADAPTERS.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown adapter: " + id);
        }
        return factory.get();
    }

    /**
     * Returns all registered adapter IDs.
     */
    public static Set<String> availableAdapters() {
        return Collections.unmodifiableSet(ADAPTERS.keySet());
    }

    /**
     * Returns true if the specified adapter exists.
     */
    public static boolean exists(String id) {
        return ADAPTERS.containsKey(id);
    }

    /**
     * Returns descriptions of all available adapters.
     */
    public static Map<String, String> describeAll() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String id : ADAPTERS.keySet()) {
            DatabaseAdapter adapter = create(id);
            descriptions.put(id, adapter.getDisplayName());
        }
        return descriptions;
    }
}
