package com.monada.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.monada.evaluation.EvaluationMaps.sortedCopy;

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
}
