package com.docbench.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for WorkloadConfig - configuration for benchmark workloads.
 */
@DisplayName("WorkloadConfig")
class WorkloadConfigTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should create config with required fields")
        void shouldCreateConfigWithRequiredFields() {
            WorkloadConfig config = WorkloadConfig.builder("traverse-deep")
                    .iterations(10000)
                    .build();

            assertThat(config.name()).isEqualTo("traverse-deep");
            assertThat(config.iterations()).isEqualTo(10000);
        }

        @Test
        @DisplayName("should use default values when not specified")
        void shouldUseDefaultValues() {
            WorkloadConfig config = WorkloadConfig.builder("test-workload")
                    .build();

            assertThat(config.iterations()).isEqualTo(1000);
            assertThat(config.warmupIterations()).isEqualTo(100);
            assertThat(config.seed()).isNotNull();
            assertThat(config.concurrency()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow setting all parameters")
        void shouldAllowSettingAllParameters() {
            WorkloadConfig config = WorkloadConfig.builder("full-config")
                    .iterations(50000)
                    .warmupIterations(5000)
                    .seed(12345L)
                    .concurrency(4)
                    .parameter("nestingDepth", 5)
                    .parameter("fieldsPerLevel", 20)
                    .parameter("targetPath", "order.items[5].sku")
                    .build();

            assertThat(config.iterations()).isEqualTo(50000);
            assertThat(config.warmupIterations()).isEqualTo(5000);
            assertThat(config.seed()).isEqualTo(12345L);
            assertThat(config.concurrency()).isEqualTo(4);
            assertThat(config.getIntParameter("nestingDepth")).isEqualTo(5);
            assertThat(config.getIntParameter("fieldsPerLevel")).isEqualTo(20);
            assertThat(config.getStringParameter("targetPath")).isEqualTo("order.items[5].sku");
        }

        @Test
        @DisplayName("should reject null name")
        void shouldRejectNullName() {
            assertThatThrownBy(() -> WorkloadConfig.builder(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThatThrownBy(() -> WorkloadConfig.builder("  ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("should reject non-positive iterations")
        void shouldRejectNonPositiveIterations() {
            assertThatThrownBy(() -> WorkloadConfig.builder("test")
                    .iterations(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("iterations");
        }

        @Test
        @DisplayName("should reject negative warmup iterations")
        void shouldRejectNegativeWarmupIterations() {
            assertThatThrownBy(() -> WorkloadConfig.builder("test")
                    .warmupIterations(-1)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("warmup");
        }
    }

    @Nested
    @DisplayName("Parameters")
    class ParameterTests {

        @Test
        @DisplayName("should return default for missing int parameter")
        void shouldReturnDefaultForMissingIntParameter() {
            WorkloadConfig config = WorkloadConfig.builder("test").build();

            assertThat(config.getIntParameter("missing", 42)).isEqualTo(42);
        }

        @Test
        @DisplayName("should return default for missing string parameter")
        void shouldReturnDefaultForMissingStringParameter() {
            WorkloadConfig config = WorkloadConfig.builder("test").build();

            assertThat(config.getStringParameter("missing", "default")).isEqualTo("default");
        }

        @Test
        @DisplayName("should return default for missing double parameter")
        void shouldReturnDefaultForMissingDoubleParameter() {
            WorkloadConfig config = WorkloadConfig.builder("test").build();

            assertThat(config.getDoubleParameter("missing", 3.14)).isEqualTo(3.14);
        }

        @Test
        @DisplayName("should return default for missing boolean parameter")
        void shouldReturnDefaultForMissingBooleanParameter() {
            WorkloadConfig config = WorkloadConfig.builder("test").build();

            assertThat(config.getBooleanParameter("missing", true)).isTrue();
        }

        @Test
        @DisplayName("should return all parameters as map")
        void shouldReturnAllParametersAsMap() {
            WorkloadConfig config = WorkloadConfig.builder("test")
                    .parameter("a", 1)
                    .parameter("b", "two")
                    .parameter("c", 3.0)
                    .build();

            Map<String, Object> params = config.parameters();
            assertThat(params).hasSize(3);
            assertThat(params).containsEntry("a", 1);
            assertThat(params).containsEntry("b", "two");
            assertThat(params).containsEntry("c", 3.0);
        }

        @Test
        @DisplayName("should support list parameters")
        void shouldSupportListParameters() {
            WorkloadConfig config = WorkloadConfig.builder("test")
                    .parameter("fields", List.of("name", "email", "phone"))
                    .build();

            List<String> fields = config.getListParameter("fields");
            assertThat(fields).containsExactly("name", "email", "phone");
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should validate required parameters")
        void shouldValidateRequiredParameters() {
            WorkloadConfig config = WorkloadConfig.builder("traverse-deep")
                    .requiredParameter("nestingDepth")
                    .requiredParameter("targetPath")
                    .parameter("nestingDepth", 5)
                    .build();

            assertThat(config.validate())
                    .isNotEmpty()
                    .anyMatch(error -> error.contains("targetPath"));
        }

        @Test
        @DisplayName("should pass validation when all required parameters present")
        void shouldPassValidationWhenAllRequiredPresent() {
            WorkloadConfig config = WorkloadConfig.builder("traverse-deep")
                    .requiredParameter("nestingDepth")
                    .parameter("nestingDepth", 5)
                    .build();

            assertThat(config.validate()).isEmpty();
        }

        @Test
        @DisplayName("should validate parameter ranges")
        void shouldValidateParameterRanges() {
            WorkloadConfig config = WorkloadConfig.builder("test")
                    .parameter("nestingDepth", 100)
                    .parameterRange("nestingDepth", 1, 20)
                    .build();

            assertThat(config.validate())
                    .isNotEmpty()
                    .anyMatch(error -> error.contains("nestingDepth") && error.contains("range"));
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return immutable parameters map")
        void shouldReturnImmutableParametersMap() {
            WorkloadConfig config = WorkloadConfig.builder("test")
                    .parameter("key", "value")
                    .build();

            assertThatThrownBy(() -> config.parameters().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Copy and Modify")
    class CopyTests {

        @Test
        @DisplayName("should create modified copy with toBuilder")
        void shouldCreateModifiedCopy() {
            WorkloadConfig original = WorkloadConfig.builder("test")
                    .iterations(1000)
                    .parameter("depth", 5)
                    .build();

            WorkloadConfig modified = original.toBuilder()
                    .iterations(2000)
                    .build();

            assertThat(original.iterations()).isEqualTo(1000);
            assertThat(modified.iterations()).isEqualTo(2000);
            assertThat(modified.getIntParameter("depth")).isEqualTo(5);
        }
    }
}
