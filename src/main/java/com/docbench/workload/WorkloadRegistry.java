package com.docbench.workload;

import java.util.*;
import java.util.function.Supplier;

/**
 * Registry of available workloads.
 * Provides factory methods for creating workload instances.
 */
public final class WorkloadRegistry {

    private static final Map<String, Supplier<Workload>> WORKLOADS = new LinkedHashMap<>();

    static {
        // Register built-in workloads
        register("traverse", TraverseWorkload::new);
        register("deserialize", DeserializeWorkload::new);
    }

    private WorkloadRegistry() {}

    /**
     * Registers a workload factory.
     */
    public static void register(String id, Supplier<Workload> factory) {
        WORKLOADS.put(Objects.requireNonNull(id), Objects.requireNonNull(factory));
    }

    /**
     * Creates a new instance of the specified workload.
     */
    public static Workload create(String id) {
        Supplier<Workload> factory = WORKLOADS.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown workload: " + id);
        }
        return factory.get();
    }

    /**
     * Returns all registered workload IDs.
     */
    public static Set<String> availableWorkloads() {
        return Collections.unmodifiableSet(WORKLOADS.keySet());
    }

    /**
     * Returns true if the specified workload exists.
     */
    public static boolean exists(String id) {
        return WORKLOADS.containsKey(id);
    }

    /**
     * Returns descriptions of all available workloads.
     */
    public static Map<String, String> describeAll() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String id : WORKLOADS.keySet()) {
            Workload w = create(id);
            descriptions.put(id, w.description());
        }
        return descriptions;
    }
}
