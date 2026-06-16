package com.monada.core;

import java.util.Arrays;
import java.util.Objects;

public record FrequencyVector(float[] values) {
    public FrequencyVector {
        Objects.requireNonNull(values);
        if (values.length == 0) {
            throw new IllegalArgumentException("FrequencyVector must have at least one dimension");
        }
        values = Arrays.copyOf(values, values.length);
    }

    @Override
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