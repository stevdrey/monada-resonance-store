package com.monada.evaluation;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class EvaluationMaps {

    private EvaluationMaps() {
    }

    static Map<Integer, Double> sortedCopy(Map<Integer, Double> source, String name) {
        var sorted = new TreeMap<>(Objects.requireNonNull(source, name));
        sorted.forEach((k, v) -> validateUnitInterval(
                Objects.requireNonNull(v, name + " value"), name + "[" + k + "]"));
        return Collections.unmodifiableMap(sorted);
    }

    static void validateLabels(Set<String> labels, String name) {
        Objects.requireNonNull(labels, name);
        for (String label : labels) {
            if (label == null) {
                throw new IllegalArgumentException(name + " must not contain null elements");
            }
            if (label.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank elements");
            }
        }
    }

    static double validateUnitInterval(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0, got: " + value);
        }
        return value;
    }
}
