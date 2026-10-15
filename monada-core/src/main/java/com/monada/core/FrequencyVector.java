package com.monada.core;

import com.monada.core.io.ChunkedFloatReader;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable dense vector of floats.
 *
 * <p>This was a {@code record} until Issue #135. It is now a final class with the same public API (constructor,
 * {@link #values()}, {@link #dimensions()}, {@link #dotProduct}, {@code equals}, {@code hashCode} and
 * {@code toString}) so that the vector stores can build vectors from freshly decoded floats without a second copy;
 * see {@link #read} and ADR 0005. The backing array never escapes: the constructor and {@link #values()} still
 * copy defensively.
 */
public final class FrequencyVector {

    private final float[] values;

    public FrequencyVector(float[] values) {
        this(requireDimensions(values).clone(), true);
    }

    /** Adopts {@code values} without copying; callers must own the only reference to the array. */
    private FrequencyVector(float[] values, boolean adopted) {
        this.values = values;
    }

    /**
     * Reads the next {@code dimensions} big-endian floats from {@code input} into a new vector, with no copy
     * after decoding.
     *
     * <p>Store codec entry point for the vector stores of {@code monada-storage} and {@code monada-speech}; not
     * intended for other callers. The array is allocated here and only handed to the final
     * {@link ChunkedFloatReader}, which fills it and keeps no reference, so no caller can ever reach it. Values are
     * bit-identical to {@code new FrequencyVector(values)} after {@link ChunkedFloatReader#readFloats}.
     *
     * @param reader     chunked reader whose scratch is reused across calls
     * @param input      source positioned at the first float
     * @param dimensions number of floats to read, at least 1
     * @return the decoded vector
     * @throws IllegalArgumentException if {@code dimensions} is not positive
     * @throws EOFException             if {@code input} ends before all values are read
     */
    public static FrequencyVector read(ChunkedFloatReader reader, DataInput input, int dimensions)
            throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(input, "input");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("FrequencyVector must have at least one dimension");
        }
        var values = new float[dimensions];
        reader.readFloats(input, values);
        return new FrequencyVector(values, true);
    }

    private static float[] requireDimensions(float[] values) {
        Objects.requireNonNull(values);
        if (values.length == 0) {
            throw new IllegalArgumentException("FrequencyVector must have at least one dimension");
        }
        return values;
    }

    /** Returns a copy of the values; mutating it does not change this vector. */
    public float[] values() {
        return Arrays.copyOf(values, values.length);
    }

    public int dimensions() {
        return values.length;
    }

    /**
     * Computes the dot product between this vector and another.
     *
     * <p>Operates directly on internal arrays without defensive copies,
     * making it suitable for hot-path similarity computations.
     *
     * @param other the other vector
     * @return the dot product
     * @throws IllegalArgumentException if dimensions do not match
     * @throws NullPointerException if other is null
     */
    public double dotProduct(FrequencyVector other) {
        Objects.requireNonNull(other, "other");
        if (this.values.length != other.values.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + this.values.length + " vs " + other.values.length);
        }
        double sum = 0.0;
        for (int i = 0; i < this.values.length; i++) {
            sum += (double) this.values[i] * other.values[i];
        }
        return sum;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrequencyVector that && Arrays.equals(this.values, that.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "FrequencyVector" + Arrays.toString(values);
    }
}
