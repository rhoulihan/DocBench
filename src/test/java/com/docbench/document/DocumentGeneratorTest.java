package com.docbench.document;

import com.docbench.adapter.spi.JsonDocument;
import com.docbench.util.RandomSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for DocumentGenerator - generates test documents with configurable structure.
 */
@DisplayName("DocumentGenerator")
class DocumentGeneratorTest {

    private RandomSource randomSource;

    @BeforeEach
    void setUp() {
        randomSource = RandomSource.seeded(12345L);
    }

    @Nested
    @DisplayName("Basic Generation")
    class BasicGenerationTests {

        @Test
        @DisplayName("should generate document with specified field count")
        void shouldGenerateDocumentWithFieldCount() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(10)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.getId()).isEqualTo("doc-1");
            // +1 for _id field
            assertThat(doc.getContent()).hasSize(11);
        }

        @Test
        @DisplayName("should generate deterministic documents with same seed")
        void shouldGenerateDeterministicDocuments() {
            DocumentGenerator gen1 = DocumentGenerator.builder()
                    .randomSource(RandomSource.seeded(42L))
                    .fieldCount(5)
                    .build();

            DocumentGenerator gen2 = DocumentGenerator.builder()
                    .randomSource(RandomSource.seeded(42L))
                    .fieldCount(5)
                    .build();

            JsonDocument doc1 = gen1.generate("doc-1");
            JsonDocument doc2 = gen2.generate("doc-1");

            assertThat(doc1.getContent()).isEqualTo(doc2.getContent());
        }

        @Test
        @DisplayName("should generate different documents with different seeds")
        void shouldGenerateDifferentDocumentsWithDifferentSeeds() {
            DocumentGenerator gen1 = DocumentGenerator.builder()
                    .randomSource(RandomSource.seeded(42L))
                    .fieldCount(5)
                    .build();

            DocumentGenerator gen2 = DocumentGenerator.builder()
                    .randomSource(RandomSource.seeded(43L))
                    .fieldCount(5)
                    .build();

            JsonDocument doc1 = gen1.generate("doc-1");
            JsonDocument doc2 = gen2.generate("doc-1");

            assertThat(doc1.getContent()).isNotEqualTo(doc2.getContent());
        }
    }

    @Nested
    @DisplayName("Field Configuration")
    class FieldConfigurationTests {

        @Test
        @DisplayName("should generate string fields")
        void shouldGenerateStringFields() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .stringFieldLength(20, 50)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            long stringFields = doc.getContent().values().stream()
                    .filter(v -> v instanceof String && !((String) v).equals("doc-1"))
                    .count();

            assertThat(stringFields).isGreaterThan(0);
        }

        @Test
        @DisplayName("should generate numeric fields")
        void shouldGenerateNumericFields() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(10)
                    .numericFieldProbability(0.5)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            long numericFields = doc.getContent().values().stream()
                    .filter(v -> v instanceof Number)
                    .count();

            assertThat(numericFields).isGreaterThan(0);
        }

        @Test
        @DisplayName("should generate boolean fields")
        void shouldGenerateBooleanFields() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(20)
                    .booleanFieldProbability(0.3)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            long booleanFields = doc.getContent().values().stream()
                    .filter(v -> v instanceof Boolean)
                    .count();

            assertThat(booleanFields).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Nested Documents")
    class NestedDocumentTests {

        @Test
        @DisplayName("should generate nested documents to specified depth")
        void shouldGenerateNestedDocumentsToDepth() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .nestingDepth(3)
                    .fieldsPerLevel(4)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            // Check nesting exists
            Object nested = doc.getPath("nested");
            assertThat(nested).isNotNull().isInstanceOf(Map.class);

            Object deepNested = doc.getPath("nested.nested");
            assertThat(deepNested).isNotNull().isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("should place target field at specified path")
        void shouldPlaceTargetFieldAtPath() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .nestingDepth(3)
                    .targetPath("nested.nested.target")
                    .targetValue("EXPECTED_VALUE")
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.getPath("nested.nested.target")).isEqualTo("EXPECTED_VALUE");
        }

        @Test
        @DisplayName("should create deeply nested structure")
        void shouldCreateDeeplyNestedStructure() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .nestingDepth(8)
                    .fieldsPerLevel(3)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            // Navigate 8 levels deep
            String deepPath = "nested.nested.nested.nested.nested.nested.nested";
            assertThat(doc.getPath(deepPath)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Array Generation")
    class ArrayGenerationTests {

        @Test
        @DisplayName("should generate array fields")
        void shouldGenerateArrayFields() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .arrayFieldCount(2)
                    .arraySize(10, 20)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            long arrayFields = doc.getContent().values().stream()
                    .filter(v -> v instanceof List)
                    .count();

            assertThat(arrayFields).isEqualTo(2);
        }

        @Test
        @DisplayName("should generate arrays with objects")
        void shouldGenerateArraysWithObjects() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .arrayFieldCount(1)
                    .arraySize(5, 5)
                    .arrayElementType(DocumentGenerator.ArrayElementType.OBJECT)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) doc.getContent().get("items");
            assertThat(items).hasSize(5);
            assertThat(items.get(0)).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("should support array index path access")
        void shouldSupportArrayIndexPathAccess() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .arrayFieldCount(1)
                    .arraySize(10, 10)
                    .arrayElementType(DocumentGenerator.ArrayElementType.OBJECT)
                    .targetPath("items[5].sku")
                    .targetValue("TARGET-SKU")
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.getPath("items[5].sku")).isEqualTo("TARGET-SKU");
        }
    }

    @Nested
    @DisplayName("Batch Generation")
    class BatchGenerationTests {

        @Test
        @DisplayName("should generate batch of documents")
        void shouldGenerateBatchOfDocuments() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .build();

            List<JsonDocument> batch = generator.generateBatch("batch", 100);

            assertThat(batch).hasSize(100);
            assertThat(batch.get(0).getId()).isEqualTo("batch-0");
            assertThat(batch.get(99).getId()).isEqualTo("batch-99");
        }

        @Test
        @DisplayName("should generate unique documents in batch")
        void shouldGenerateUniqueDocumentsInBatch() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(5)
                    .build();

            List<JsonDocument> batch = generator.generateBatch("batch", 10);

            long uniqueIds = batch.stream()
                    .map(JsonDocument::getId)
                    .distinct()
                    .count();

            assertThat(uniqueIds).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Document Size Control")
    class DocumentSizeTests {

        @Test
        @DisplayName("should generate documents of approximate size")
        void shouldGenerateDocumentsOfApproximateSize() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .targetSizeBytes(5000)
                    .sizeTolerancePercent(20)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            int size = doc.estimatedSizeBytes();
            assertThat(size).isBetween(4000, 6000);
        }

        @Test
        @DisplayName("should generate small documents")
        void shouldGenerateSmallDocuments() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .targetSizeBytes(500)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.estimatedSizeBytes()).isLessThan(1000);
        }

        @Test
        @DisplayName("should generate large documents")
        void shouldGenerateLargeDocuments() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .targetSizeBytes(50000)
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.estimatedSizeBytes()).isGreaterThan(40000);
        }
    }

    @Nested
    @DisplayName("Field Position Control")
    class FieldPositionTests {

        @Test
        @DisplayName("should place target field at specified position")
        void shouldPlaceTargetFieldAtPosition() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(100)
                    .targetFieldPosition(50)
                    .targetFieldName("target")
                    .targetValue("FOUND")
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            assertThat(doc.getContent().get("target")).isEqualTo("FOUND");

            // Verify position - target should be around the 50th key
            List<String> keys = doc.getContent().keySet().stream().toList();
            int targetIndex = keys.indexOf("target");
            assertThat(targetIndex).isBetween(45, 55); // Allow some tolerance
        }

        @Test
        @DisplayName("should place target field at end")
        void shouldPlaceTargetFieldAtEnd() {
            DocumentGenerator generator = DocumentGenerator.builder()
                    .randomSource(randomSource)
                    .fieldCount(50)
                    .targetFieldPosition(50) // Last position
                    .targetFieldName("lastField")
                    .targetValue("LAST")
                    .build();

            JsonDocument doc = generator.generate("doc-1");

            List<String> keys = doc.getContent().keySet().stream().toList();
            // Should be near the end (after _id)
            assertThat(keys.indexOf("lastField")).isGreaterThan(40);
        }
    }

    @Nested
    @DisplayName("Presets")
    class PresetTests {

        @Test
        @DisplayName("should create e-commerce order document")
        void shouldCreateEcommerceOrderDocument() {
            DocumentGenerator generator = DocumentGenerator.ecommerceOrder()
                    .randomSource(randomSource)
                    .build();

            JsonDocument doc = generator.generate("order-1");

            assertThat(doc.hasPath("orderNumber")).isTrue();
            assertThat(doc.hasPath("customer")).isTrue();
            assertThat(doc.hasPath("lineItems")).isTrue();
            assertThat(doc.hasPath("shippingAddress")).isTrue();
        }

        @Test
        @DisplayName("should create user profile document")
        void shouldCreateUserProfileDocument() {
            DocumentGenerator generator = DocumentGenerator.userProfile()
                    .randomSource(randomSource)
                    .build();

            JsonDocument doc = generator.generate("user-1");

            assertThat(doc.hasPath("username")).isTrue();
            assertThat(doc.hasPath("email")).isTrue();
            assertThat(doc.hasPath("profile")).isTrue();
        }

        @Test
        @DisplayName("should create IoT sensor reading document")
        void shouldCreateIotSensorDocument() {
            DocumentGenerator generator = DocumentGenerator.iotSensorReading()
                    .randomSource(randomSource)
                    .build();

            JsonDocument doc = generator.generate("reading-1");

            assertThat(doc.hasPath("sensorId")).isTrue();
            assertThat(doc.hasPath("timestamp")).isTrue();
            assertThat(doc.hasPath("readings")).isTrue();
        }
    }
}
