package com.monada.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record QueryEvaluation(
        String queryText,
        Set<String> expectedLabels,
        List<String> returnedLabels,
        Map<Integer, Double> precisionByK,
        Map<Integer, Double> recallByK,
        Map<Integer, Double> hitByK,
        double reciprocalRank
) {
    public QueryEvaluation {
        Objects.requireNonNull(queryText, "queryText");
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabels = List.copyOf(Objects.requireNonNull(returnedLabels, "returnedLabels"));
        precisionByK = sortedCopy(precisionByK, "precisionByK");
        recallByK = sortedCopy(recallByK, "recallByK");
        hitByK = sortedCopy(hitByK, "hitByK");
    }

    private static Map<Integer, Double> sortedCopy(Map<Integer, Double> source, String name) {
        var sorted = new TreeMap<>(Objects.requireNonNull(source, name));
        sorted.forEach((k, v) -> Objects.requireNonNull(v, name + " value"));
        return Collections.unmodifiableMap(sorted);
    }
}
