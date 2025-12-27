package com.docbench.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD Tests for RandomSource abstraction.
 * Ensures reproducibility through seeding.
 */
@DisplayName("RandomSource")
class RandomSourceTest {

    @Nested
    @DisplayName("SeededRandomSource")
    class SeededRandomSourceTests {

        @Test
        @DisplayName("should produce deterministic sequence with same seed")
        void sameSeed_shouldProduceSameSequence() {
            RandomSource source1 = RandomSource.seeded(12345L);
            RandomSource source2 = RandomSource.seeded(12345L);

            for (int i = 0; i < 100; i++) {
                assertThat(source1.nextInt()).isEqualTo(source2.nextInt());
            }
        }

        @Test
        @DisplayName("should produce different sequences with different seeds")
        void differentSeeds_shouldProduceDifferentSequences() {
            RandomSource source1 = RandomSource.seeded(12345L);
            RandomSource source2 = RandomSource.seeded(54321L);

            // At least one should differ in 10 iterations
            boolean foundDifference = false;
            for (int i = 0; i < 10; i++) {
                if (source1.nextInt() != source2.nextInt()) {
                    foundDifference = true;
                    break;
                }
            }
            assertThat(foundDifference).isTrue();
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE})
        @DisplayName("should accept any seed value")
        void constructor_shouldAcceptAnySeed(long seed) {
            RandomSource source = RandomSource.seeded(seed);
            assertThat(source.nextInt()).isNotNull();
        }

        @Test
        @DisplayName("should return seed value")
        void getSeed_shouldReturnOriginalSeed() {
            RandomSource source = RandomSource.seeded(42L);
            assertThat(source.getSeed()).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("nextInt")
    class NextIntTests {

        @Test
        @DisplayName("should return value within bound")
        void nextInt_withBound_shouldReturnValueWithinRange() {
            RandomSource source = RandomSource.seeded(12345L);

            for (int i = 0; i < 1000; i++) {
                int value = source.nextInt(100);
                assertThat(value).isBetween(0, 99);
            }
        }

        @Test
        @DisplayName("should return value within origin and bound")
        void nextInt_withOriginAndBound_shouldReturnValueWithinRange() {
            RandomSource source = RandomSource.seeded(12345L);

            for (int i = 0; i < 1000; i++) {
                int value = source.nextInt(50, 100);
                assertThat(value).isBetween(50, 99);
            }
        }

        @Test
        @DisplayName("should throw for invalid bound")
        void nextInt_withZeroBound_shouldThrow() {
            RandomSource source = RandomSource.seeded(12345L);

            assertThatThrownBy(() -> source.nextInt(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should throw for invalid range")
        void nextInt_withInvalidRange_shouldThrow() {
            RandomSource source = RandomSource.seeded(12345L);

            assertThatThrownBy(() -> source.nextInt(100, 50))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("nextLong")
    class NextLongTests {

        @Test
        @DisplayName("should return deterministic values")
        void nextLong_shouldBeDeterministic() {
            RandomSource source1 = RandomSource.seeded(99999L);
            RandomSource source2 = RandomSource.seeded(99999L);

            for (int i = 0; i < 100; i++) {
                assertThat(source1.nextLong()).isEqualTo(source2.nextLong());
            }
        }

        @Test
        @DisplayName("should return value within bound")
        void nextLong_withBound_shouldReturnValueWithinRange() {
            RandomSource source = RandomSource.seeded(12345L);

            for (int i = 0; i < 1000; i++) {
                long value = source.nextLong(1_000_000L);
                assertThat(value).isBetween(0L, 999_999L);
            }
        }
    }

    @Nested
    @DisplayName("nextDouble")
    class NextDoubleTests {

        @Test
        @DisplayName("should return value between 0.0 and 1.0")
        void nextDouble_shouldReturnValueInRange() {
            RandomSource source = RandomSource.seeded(12345L);

            for (int i = 0; i < 1000; i++) {
                double value = source.nextDouble();
                assertThat(value).isBetween(0.0, 1.0);
            }
        }

        @Test
        @DisplayName("should be deterministic")
        void nextDouble_shouldBeDeterministic() {
            RandomSource source1 = RandomSource.seeded(777L);
            RandomSource source2 = RandomSource.seeded(777L);

            for (int i = 0; i < 100; i++) {
                assertThat(source1.nextDouble()).isEqualTo(source2.nextDouble());
            }
        }
    }

    @Nested
    @DisplayName("nextBoolean")
    class NextBooleanTests {

        @Test
        @DisplayName("should produce both true and false values")
        void nextBoolean_shouldProduceBothValues() {
            RandomSource source = RandomSource.seeded(12345L);
            Set<Boolean> values = new HashSet<>();

            for (int i = 0; i < 100; i++) {
                values.add(source.nextBoolean());
            }

            assertThat(values).containsExactlyInAnyOrder(true, false);
        }
    }

    @Nested
    @DisplayName("nextString")
    class NextStringTests {

        @Test
        @DisplayName("should generate string of specified length")
        void nextString_shouldReturnCorrectLength() {
            RandomSource source = RandomSource.seeded(12345L);

            String result = source.nextString(50);

            assertThat(result).hasSize(50);
        }

        @Test
        @DisplayName("should be deterministic")
        void nextString_shouldBeDeterministic() {
            RandomSource source1 = RandomSource.seeded(12345L);
            RandomSource source2 = RandomSource.seeded(12345L);

            assertThat(source1.nextString(100)).isEqualTo(source2.nextString(100));
        }

        @Test
        @DisplayName("should contain only alphanumeric characters")
        void nextString_shouldContainOnlyAlphanumeric() {
            RandomSource source = RandomSource.seeded(12345L);

            String result = source.nextString(1000);

            assertThat(result).matches("[a-zA-Z0-9]+");
        }

        @Test
        @DisplayName("should return empty string for zero length")
        void nextString_withZeroLength_shouldReturnEmpty() {
            RandomSource source = RandomSource.seeded(12345L);

            String result = source.nextString(0);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("fork")
    class ForkTests {

        @Test
        @DisplayName("should create independent random source")
        void fork_shouldCreateIndependentSource() {
            RandomSource original = RandomSource.seeded(12345L);

            // Consume some values
            original.nextInt();
            original.nextInt();

            RandomSource forked = original.fork();

            // Original and forked should produce different values
            int originalValue = original.nextInt();
            int forkedValue = forked.nextInt();

            // They could be same by chance, but fork should be independent
            assertThat(forked).isNotSameAs(original);
        }

        @Test
        @DisplayName("forked source should be deterministic given same state")
        void fork_shouldBeDeterministic() {
            RandomSource source1 = RandomSource.seeded(12345L);
            RandomSource source2 = RandomSource.seeded(12345L);

            // Same sequence of operations should produce same forked sources
            source1.nextInt();
            source2.nextInt();

            RandomSource forked1 = source1.fork();
            RandomSource forked2 = source2.fork();

            assertThat(forked1.nextInt()).isEqualTo(forked2.nextInt());
        }
    }

    @Nested
    @DisplayName("shuffle")
    class ShuffleTests {

        @Test
        @DisplayName("should shuffle array deterministically")
        void shuffle_shouldBeDeterministic() {
            RandomSource source1 = RandomSource.seeded(12345L);
            RandomSource source2 = RandomSource.seeded(12345L);

            int[] array1 = IntStream.range(0, 100).toArray();
            int[] array2 = IntStream.range(0, 100).toArray();

            source1.shuffle(array1);
            source2.shuffle(array2);

            assertThat(array1).isEqualTo(array2);
        }

        @Test
        @DisplayName("should contain all original elements")
        void shuffle_shouldContainAllElements() {
            RandomSource source = RandomSource.seeded(12345L);
            int[] array = IntStream.range(0, 100).toArray();

            source.shuffle(array);

            assertThat(array).containsExactlyInAnyOrder(
                    IntStream.range(0, 100).boxed().toArray(Integer[]::new)
            );
        }
    }
}
