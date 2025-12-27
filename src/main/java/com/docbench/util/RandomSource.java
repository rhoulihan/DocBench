package com.docbench.util;

import java.util.Objects;
import java.util.Random;

/**
 * Abstraction over random number generation for reproducibility.
 * Seeded implementations enable deterministic test execution.
 */
public sealed interface RandomSource permits SeededRandomSource {

    /**
     * Returns the seed used to initialize this source.
     */
    long getSeed();

    /**
     * Returns the next pseudorandom integer.
     */
    int nextInt();

    /**
     * Returns a pseudorandom integer between 0 (inclusive) and bound (exclusive).
     *
     * @param bound the upper bound (exclusive)
     * @return a random integer in [0, bound)
     * @throws IllegalArgumentException if bound is not positive
     */
    int nextInt(int bound);

    /**
     * Returns a pseudorandom integer between origin (inclusive) and bound (exclusive).
     *
     * @param origin the lower bound (inclusive)
     * @param bound  the upper bound (exclusive)
     * @return a random integer in [origin, bound)
     * @throws IllegalArgumentException if origin >= bound
     */
    int nextInt(int origin, int bound);

    /**
     * Returns the next pseudorandom long.
     */
    long nextLong();

    /**
     * Returns a pseudorandom long between 0 (inclusive) and bound (exclusive).
     *
     * @param bound the upper bound (exclusive)
     * @return a random long in [0, bound)
     */
    long nextLong(long bound);

    /**
     * Returns the next pseudorandom double between 0.0 (inclusive) and 1.0 (exclusive).
     */
    double nextDouble();

    /**
     * Returns the next pseudorandom boolean.
     */
    boolean nextBoolean();

    /**
     * Returns a pseudorandom alphanumeric string of the specified length.
     *
     * @param length the length of the string
     * @return an alphanumeric string
     */
    String nextString(int length);

    /**
     * Creates a new independent random source derived from this one.
     * The forked source will produce different values than this source.
     */
    RandomSource fork();

    /**
     * Shuffles the array in place using Fisher-Yates algorithm.
     *
     * @param array the array to shuffle
     */
    void shuffle(int[] array);

    /**
     * Shuffles the array in place using Fisher-Yates algorithm.
     *
     * @param array the array to shuffle
     */
    <T> void shuffle(T[] array);

    /**
     * Creates a new seeded random source.
     *
     * @param seed the seed value
     * @return a new RandomSource
     */
    static RandomSource seeded(long seed) {
        return new SeededRandomSource(seed);
    }

    /**
     * Creates a random source with a random seed (non-deterministic).
     *
     * @return a new RandomSource with random seed
     */
    static RandomSource random() {
        return new SeededRandomSource(System.nanoTime());
    }
}

/**
 * Seeded implementation of RandomSource.
 * Provides deterministic random number generation.
 */
final class SeededRandomSource implements RandomSource {

    private static final String ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final long seed;
    private final Random random;

    SeededRandomSource(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public int nextInt() {
        return random.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return random.nextInt(bound);
    }

    @Override
    public int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException(
                    "origin must be less than bound: origin=" + origin + ", bound=" + bound);
        }
        return random.nextInt(origin, bound);
    }

    @Override
    public long nextLong() {
        return random.nextLong();
    }

    @Override
    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return random.nextLong(bound);
    }

    @Override
    public double nextDouble() {
        return random.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    @Override
    public String nextString(int length) {
        if (length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    @Override
    public RandomSource fork() {
        return new SeededRandomSource(nextLong());
    }

    @Override
    public void shuffle(int[] array) {
        Objects.requireNonNull(array);
        for (int i = array.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }

    @Override
    public <T> void shuffle(T[] array) {
        Objects.requireNonNull(array);
        for (int i = array.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }
}
