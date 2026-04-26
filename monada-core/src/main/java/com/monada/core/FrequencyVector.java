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