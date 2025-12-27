package com.docbench.document;

import com.docbench.adapter.spi.JsonDocument;
import com.docbench.util.RandomSource;

import java.time.Instant;
import java.util.*;

/**
 * Generates test documents with configurable structure for benchmarking.
 * Supports deterministic generation via seeded random source.
 */
public final class DocumentGenerator {

    /**
     * Types of elements that can be generated in arrays.
     */
    public enum ArrayElementType {
        STRING, NUMBER, OBJECT, MIXED
    }

    private final RandomSource randomSource;
    private final int fieldCount;
    private final int minStringLength;
    private final int maxStringLength;
    private final double numericFieldProbability;
    private final double booleanFieldProbability;
    private final int nestingDepth;
    private final int fieldsPerLevel;
    private final String targetPath;
    private final Object targetValue;
    private final int arrayFieldCount;
    private final int minArraySize;
    private final int maxArraySize;
    private final ArrayElementType arrayElementType;
    private final int targetSizeBytes;
    private final int sizeTolerancePercent;
    private final int targetFieldPosition;
    private final String targetFieldName;
    private final DocumentTemplate template;

    private DocumentGenerator(Builder builder) {
        this.randomSource = builder.randomSource != null ? builder.randomSource : RandomSource.random();
        this.fieldCount = builder.fieldCount;
        this.minStringLength = builder.minStringLength;
        this.maxStringLength = builder.maxStringLength;
        this.numericFieldProbability = builder.numericFieldProbability;
        this.booleanFieldProbability = builder.booleanFieldProbability;
        this.nestingDepth = builder.nestingDepth;
        this.fieldsPerLevel = builder.fieldsPerLevel;
        this.targetPath = builder.targetPath;
        this.targetValue = builder.targetValue;
        this.arrayFieldCount = builder.arrayFieldCount;
        this.minArraySize = builder.minArraySize;
        this.maxArraySize = builder.maxArraySize;
        this.arrayElementType = builder.arrayElementType;
        this.targetSizeBytes = builder.targetSizeBytes;
        this.sizeTolerancePercent = builder.sizeTolerancePercent;
        this.targetFieldPosition = builder.targetFieldPosition;
        this.targetFieldName = builder.targetFieldName;
        this.template = builder.template;
    }

    /**
     * Creates a new builder for DocumentGenerator.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder preset for e-commerce order documents.
     */
    public static Builder ecommerceOrder() {
        return new Builder().template(DocumentTemplate.ECOMMERCE_ORDER);
    }

    /**
     * Creates a builder preset for user profile documents.
     */
    public static Builder userProfile() {
        return new Builder().template(DocumentTemplate.USER_PROFILE);
    }

    /**
     * Creates a builder preset for IoT sensor reading documents.
     */
    public static Builder iotSensorReading() {
        return new Builder().template(DocumentTemplate.IOT_SENSOR);
    }

    /**
     * Generates a single document with the specified ID.
     */
    public JsonDocument generate(String id) {
        if (template != null) {
            return generateFromTemplate(id);
        }

        if (targetSizeBytes > 0) {
            return generateToSize(id);
        }

        return generateStandard(id);
    }

    /**
     * Generates a batch of documents with sequential IDs.
     */
    public List<JsonDocument> generateBatch(String idPrefix, int count) {
        List<JsonDocument> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(generate(idPrefix + "-" + i));
        }
        return batch;
    }

    private JsonDocument generateStandard(String id) {
        Map<String, Object> content = new LinkedHashMap<>();

        // Generate regular fields
        int regularFields = fieldCount - arrayFieldCount;
        int targetPos = targetFieldPosition > 0 ? targetFieldPosition : -1;
        int fieldIndex = 0;

        for (int i = 0; i < regularFields; i++) {
            // Check if this is the target field position
            if (targetPos == i + 1 && targetFieldName != null) {
                content.put(targetFieldName, targetValue != null ? targetValue : "TARGET_VALUE");
            } else {
                String fieldName = "field_" + String.format("%03d", fieldIndex++);
                content.put(fieldName, generateFieldValue());
            }
        }

        // Add nested structure if configured
        if (nestingDepth > 0) {
            content.put("nested", generateNestedObject(1));
        }

        // Add array fields
        for (int i = 0; i < arrayFieldCount; i++) {
            String arrayName = i == 0 ? "items" : "array_" + i;
            content.put(arrayName, generateArray());
        }

        // Handle target path for nested structures
        if (targetPath != null && !targetPath.isEmpty()) {
            setValueAtPath(content, targetPath, targetValue);
        }

        // Use builder to ensure _id is added
        JsonDocument.Builder builder = JsonDocument.builder(id);
        builder.fields(content);
        return builder.build();
    }

    private JsonDocument generateToSize(String id) {
        Map<String, Object> content = new LinkedHashMap<>();
        int currentSize = 0;
        int fieldIndex = 0;
        int targetSize = targetSizeBytes;
        int tolerance = targetSizeBytes * sizeTolerancePercent / 100;
        int minSize = targetSize - tolerance;

        while (currentSize < minSize) {
            String fieldName = "field_" + String.format("%03d", fieldIndex++);
            Object value = generateFieldValue();
            content.put(fieldName, value);
            currentSize = estimateSize(content);
        }

        return JsonDocument.of(id, content);
    }

    private JsonDocument generateFromTemplate(String id) {
        return switch (template) {
            case ECOMMERCE_ORDER -> generateEcommerceOrder(id);
            case USER_PROFILE -> generateUserProfile(id);
            case IOT_SENSOR -> generateIotSensor(id);
        };
    }

    private JsonDocument generateEcommerceOrder(String id) {
        Map<String, Object> content = new LinkedHashMap<>();

        content.put("orderNumber", "ORD-" + id);
        content.put("orderDate", Instant.now().toString());
        content.put("status", randomChoice("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED"));

        content.put("customer", Map.of(
                "customerId", "CUST-" + randomSource.nextString(8),
                "name", randomName(),
                "email", randomEmail(),
                "tier", randomChoice("BRONZE", "SILVER", "GOLD", "PLATINUM")
        ));

        content.put("shippingAddress", generateAddress());
        content.put("billingAddress", generateAddress());

        List<Map<String, Object>> lineItems = new ArrayList<>();
        int itemCount = randomSource.nextInt(3, 15);
        double subtotal = 0;
        for (int i = 0; i < itemCount; i++) {
            double price = 5.0 + randomSource.nextDouble() * 200;
            int qty = randomSource.nextInt(1, 5);
            subtotal += price * qty;
            lineItems.add(Map.of(
                    "lineNumber", i + 1,
                    "sku", "SKU-" + randomSource.nextString(6),
                    "productName", "Product " + randomSource.nextString(10),
                    "unitPrice", Math.round(price * 100) / 100.0,
                    "quantity", qty,
                    "subtotal", Math.round(price * qty * 100) / 100.0
            ));
        }
        content.put("lineItems", lineItems);

        content.put("subtotal", Math.round(subtotal * 100) / 100.0);
        content.put("taxTotal", Math.round(subtotal * 0.08 * 100) / 100.0);
        content.put("shippingCost", 9.99);
        content.put("grandTotal", Math.round((subtotal * 1.08 + 9.99) * 100) / 100.0);

        return JsonDocument.of(id, content);
    }

    private JsonDocument generateUserProfile(String id) {
        Map<String, Object> content = new LinkedHashMap<>();

        content.put("username", "user_" + randomSource.nextString(8));
        content.put("email", randomEmail());
        content.put("passwordHash", randomSource.nextString(64));
        content.put("createdAt", Instant.now().minusSeconds(randomSource.nextLong(86400 * 365)).toString());

        content.put("profile", Map.of(
                "firstName", randomName().split(" ")[0],
                "lastName", randomName().split(" ")[0],
                "bio", randomSource.nextString(100),
                "avatarUrl", "https://example.com/avatars/" + randomSource.nextString(10) + ".jpg",
                "location", randomChoice("New York", "London", "Tokyo", "Sydney", "Berlin")
        ));

        content.put("preferences", Map.of(
                "theme", randomChoice("light", "dark", "auto"),
                "language", randomChoice("en", "es", "fr", "de", "ja"),
                "notifications", randomSource.nextBoolean()
        ));

        content.put("stats", Map.of(
                "loginCount", randomSource.nextInt(1, 1000),
                "postsCount", randomSource.nextInt(0, 500),
                "followersCount", randomSource.nextInt(0, 10000)
        ));

        return JsonDocument.of(id, content);
    }

    private JsonDocument generateIotSensor(String id) {
        Map<String, Object> content = new LinkedHashMap<>();

        content.put("sensorId", "SENSOR-" + randomSource.nextString(8));
        content.put("deviceType", randomChoice("temperature", "humidity", "pressure", "motion"));
        content.put("timestamp", Instant.now().toString());
        content.put("location", Map.of(
                "building", "Building-" + randomSource.nextInt(1, 10),
                "floor", randomSource.nextInt(1, 20),
                "room", randomSource.nextInt(100, 999)
        ));

        List<Map<String, Object>> readings = new ArrayList<>();
        int readingCount = randomSource.nextInt(10, 50);
        for (int i = 0; i < readingCount; i++) {
            readings.add(Map.of(
                    "timestamp", Instant.now().minusSeconds(i * 60).toString(),
                    "value", Math.round(randomSource.nextDouble() * 100 * 100) / 100.0,
                    "unit", randomChoice("C", "F", "%", "hPa"),
                    "quality", randomChoice("good", "fair", "poor")
            ));
        }
        content.put("readings", readings);

        content.put("battery", randomSource.nextInt(0, 100));
        content.put("signalStrength", randomSource.nextInt(-100, -30));

        return JsonDocument.of(id, content);
    }

    private Object generateFieldValue() {
        double rand = randomSource.nextDouble();

        if (rand < numericFieldProbability) {
            return randomSource.nextBoolean() ?
                    randomSource.nextInt(0, 1000000) :
                    Math.round(randomSource.nextDouble() * 10000 * 100) / 100.0;
        }

        if (rand < numericFieldProbability + booleanFieldProbability) {
            return randomSource.nextBoolean();
        }

        // String field
        int length = randomSource.nextInt(minStringLength, maxStringLength + 1);
        return randomSource.nextString(length);
    }

    private Map<String, Object> generateNestedObject(int currentDepth) {
        Map<String, Object> nested = new LinkedHashMap<>();

        for (int i = 0; i < fieldsPerLevel; i++) {
            nested.put("field_" + i, generateFieldValue());
        }

        if (currentDepth < nestingDepth) {
            nested.put("nested", generateNestedObject(currentDepth + 1));
        }

        return nested;
    }

    private List<Object> generateArray() {
        int size = randomSource.nextInt(minArraySize, maxArraySize + 1);
        List<Object> array = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            array.add(generateArrayElement(i));
        }

        return array;
    }

    private Object generateArrayElement(int index) {
        return switch (arrayElementType) {
            case STRING -> randomSource.nextString(20);
            case NUMBER -> randomSource.nextDouble() * 1000;
            case OBJECT -> {
                // Use mutable map to allow setValueAtPath to modify elements
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("index", index);
                obj.put("sku", "SKU-" + randomSource.nextString(6));
                obj.put("name", "Item " + randomSource.nextString(10));
                obj.put("value", Math.round(randomSource.nextDouble() * 100 * 100) / 100.0);
                yield obj;
            }
            case MIXED -> randomSource.nextBoolean() ?
                    randomSource.nextString(15) :
                    randomSource.nextDouble() * 100;
        };
    }

    @SuppressWarnings("unchecked")
    private void setValueAtPath(Map<String, Object> content, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = content;

        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];

            // Handle array index notation
            int bracketIndex = segment.indexOf('[');
            if (bracketIndex != -1) {
                String fieldName = segment.substring(0, bracketIndex);
                int arrayIndex = Integer.parseInt(
                        segment.substring(bracketIndex + 1, segment.indexOf(']'))
                );

                List<Object> array = (List<Object>) current.get(fieldName);
                if (array == null) {
                    array = new ArrayList<>();
                    current.put(fieldName, array);
                }

                // Ensure array has enough elements
                while (array.size() <= arrayIndex) {
                    array.add(new LinkedHashMap<String, Object>());
                }

                Object element = array.get(arrayIndex);
                if (element instanceof Map) {
                    current = (Map<String, Object>) element;
                } else {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    array.set(arrayIndex, newMap);
                    current = newMap;
                }
            } else {
                Object next = current.get(segment);
                if (next == null || !(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    current.put(segment, next);
                }
                current = (Map<String, Object>) next;
            }
        }

        // Set the final value
        String lastSegment = segments[segments.length - 1];
        int bracketIndex = lastSegment.indexOf('[');
        if (bracketIndex != -1) {
            String fieldName = lastSegment.substring(0, bracketIndex);
            int arrayIndex = Integer.parseInt(
                    lastSegment.substring(bracketIndex + 1, lastSegment.indexOf(']'))
            );
            List<Object> array = (List<Object>) current.computeIfAbsent(fieldName, k -> new ArrayList<>());
            while (array.size() <= arrayIndex) {
                array.add(null);
            }
            array.set(arrayIndex, value);
        } else {
            current.put(lastSegment, value);
        }
    }

    private Map<String, Object> generateAddress() {
        return Map.of(
                "street", randomSource.nextInt(1, 9999) + " " + randomChoice("Main", "Oak", "Maple", "First") + " " + randomChoice("St", "Ave", "Blvd", "Dr"),
                "city", randomChoice("New York", "Los Angeles", "Chicago", "Houston", "Phoenix"),
                "state", randomChoice("NY", "CA", "IL", "TX", "AZ"),
                "zip", String.format("%05d", randomSource.nextInt(10000, 99999)),
                "country", "USA"
        );
    }

    private String randomName() {
        String[] firstNames = {"John", "Jane", "Bob", "Alice", "Charlie", "Diana", "Edward", "Fiona"};
        String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis"};
        return firstNames[randomSource.nextInt(firstNames.length)] + " " +
                lastNames[randomSource.nextInt(lastNames.length)];
    }

    private String randomEmail() {
        return randomSource.nextString(8).toLowerCase() + "@" +
                randomChoice("gmail.com", "yahoo.com", "outlook.com", "example.com");
    }

    private String randomChoice(String... options) {
        return options[randomSource.nextInt(options.length)];
    }

    private int estimateSize(Map<String, Object> content) {
        int size = 4;
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            size += entry.getKey().length() * 2 + 4;
            size += estimateValueSize(entry.getValue());
        }
        return size;
    }

    private int estimateValueSize(Object value) {
        if (value == null) return 4;
        if (value instanceof String s) return s.length() * 2 + 4;
        if (value instanceof Number) return 8;
        if (value instanceof Boolean) return 1;
        if (value instanceof Map<?, ?> m) {
            int size = 4;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                size += estimateValueSize(e.getKey());
                size += estimateValueSize(e.getValue());
            }
            return size;
        }
        if (value instanceof List<?> l) {
            int size = 4;
            for (Object item : l) {
                size += estimateValueSize(item);
            }
            return size;
        }
        return 8;
    }

    /**
     * Document templates for common use cases.
     */
    enum DocumentTemplate {
        ECOMMERCE_ORDER,
        USER_PROFILE,
        IOT_SENSOR
    }

    /**
     * Builder for DocumentGenerator.
     */
    public static final class Builder {
        private RandomSource randomSource;
        private int fieldCount = 10;
        private int minStringLength = 10;
        private int maxStringLength = 50;
        private double numericFieldProbability = 0.2;
        private double booleanFieldProbability = 0.1;
        private int nestingDepth = 0;
        private int fieldsPerLevel = 5;
        private String targetPath;
        private Object targetValue;
        private int arrayFieldCount = 0;
        private int minArraySize = 5;
        private int maxArraySize = 10;
        private ArrayElementType arrayElementType = ArrayElementType.OBJECT;
        private int targetSizeBytes = 0;
        private int sizeTolerancePercent = 20;
        private int targetFieldPosition = 0;
        private String targetFieldName;
        private DocumentTemplate template;

        private Builder() {}

        public Builder randomSource(RandomSource randomSource) {
            this.randomSource = randomSource;
            return this;
        }

        public Builder fieldCount(int fieldCount) {
            this.fieldCount = fieldCount;
            return this;
        }

        public Builder stringFieldLength(int min, int max) {
            this.minStringLength = min;
            this.maxStringLength = max;
            return this;
        }

        public Builder numericFieldProbability(double probability) {
            this.numericFieldProbability = probability;
            return this;
        }

        public Builder booleanFieldProbability(double probability) {
            this.booleanFieldProbability = probability;
            return this;
        }

        public Builder nestingDepth(int depth) {
            this.nestingDepth = depth;
            return this;
        }

        public Builder fieldsPerLevel(int count) {
            this.fieldsPerLevel = count;
            return this;
        }

        public Builder targetPath(String path) {
            this.targetPath = path;
            return this;
        }

        public Builder targetValue(Object value) {
            this.targetValue = value;
            return this;
        }

        public Builder arrayFieldCount(int count) {
            this.arrayFieldCount = count;
            return this;
        }

        public Builder arraySize(int min, int max) {
            this.minArraySize = min;
            this.maxArraySize = max;
            return this;
        }

        public Builder arrayElementType(ArrayElementType type) {
            this.arrayElementType = type;
            return this;
        }

        public Builder targetSizeBytes(int size) {
            this.targetSizeBytes = size;
            return this;
        }

        public Builder sizeTolerancePercent(int percent) {
            this.sizeTolerancePercent = percent;
            return this;
        }

        public Builder targetFieldPosition(int position) {
            this.targetFieldPosition = position;
            return this;
        }

        public Builder targetFieldName(String name) {
            this.targetFieldName = name;
            return this;
        }

        Builder template(DocumentTemplate template) {
            this.template = template;
            return this;
        }

        public DocumentGenerator build() {
            return new DocumentGenerator(this);
        }
    }
}
