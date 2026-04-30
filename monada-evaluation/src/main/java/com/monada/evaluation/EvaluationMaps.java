package com.monada.evaluation;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class EvaluationMaps {

    private EvaluationMaps() {
    }

    static Map<Integer, Double> sortedCopy(Map<Integer, Double> source, String name) {
        var sorted = new TreeMap<>(Objects.requireNonNull(source, name));
        sorted.forEach((k, v) -> Objects.requireNonNull(v, name + " value"));
        return Collections.unmodifiableMap(sorted);
    }
}
