package com.monada.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record QueryEvaluation(
        String queryText,
        Set<String> expectedLabels,
        List<String> returnedLabels,
        Map<Integer, Double> precisionByK
) {
    public QueryEvaluation {
        Objects.requireNonNull(queryText, "queryText");
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabels = List.copyOf(Objects.requireNonNull(returnedLabels, "returnedLabels"));
        precisionByK = Map.copyOf(new TreeMap<>(Objects.requireNonNull(precisionByK, "precisionByK")));
    }
}
