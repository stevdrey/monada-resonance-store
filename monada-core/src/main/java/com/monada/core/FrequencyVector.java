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
}