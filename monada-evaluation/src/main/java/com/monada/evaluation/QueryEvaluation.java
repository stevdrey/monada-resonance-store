package com.monada.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.monada.evaluation.EvaluationMaps.sortedCopy;
import static com.monada.evaluation.EvaluationMaps.validateUnitInterval;

public record QueryEvaluation(
        String queryText,
        Set<String> expectedLabels,
        List<String> returnedLabels,
        Map<Integer, Double> precisionByK,
        Map<Integer, Double> recallByK,
        Map<Integer, Double> hitByK,
        double reciprocalRank,
        QueryKeyDiagnostic queryKeyDiagnostic,
        TextEncodingDiagnostic textEncodingDiagnostic
) {
    public QueryEvaluation {
        Objects.requireNonNull(queryText, "queryText");
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabels = List.copyOf(Objects.requireNonNull(returnedLabels, "returnedLabels"));
        precisionByK = sortedCopy(precisionByK, "precisionByK");
        recallByK = sortedCopy(recallByK, "recallByK");
        hitByK = sortedCopy(hitByK, "hitByK");
        reciprocalRank = validateUnitInterval(reciprocalRank, "reciprocalRank");
        // queryKeyDiagnostic can be null when not captured
        // textEncodingDiagnostic is null unless contribution diagnostics were enabled
    }

    /**
     * Backwards-compatible constructor without encoding contribution diagnostics.
     */
    public QueryEvaluation(
            String queryText,
            Set<String> expectedLabels,
            List<String> returnedLabels,
            Map<Integer, Double> precisionByK,
            Map<Integer, Double> recallByK,
            Map<Integer, Double> hitByK,
            double reciprocalRank,
            QueryKeyDiagnostic queryKeyDiagnostic) {
        this(queryText, expectedLabels, returnedLabels, precisionByK, recallByK, hitByK,
                reciprocalRank, queryKeyDiagnostic, null);
    }

    /**
     * Backwards-compatible constructor without query key diagnostic.
     * Creates a QueryEvaluation with empty diagnostic.
     */
    public QueryEvaluation(
            String queryText,
            Set<String> expectedLabels,
            List<String> returnedLabels,
            Map<Integer, Double> precisionByK,
            Map<Integer, Double> recallByK,
            Map<Integer, Double> hitByK,
            double reciprocalRank) {
        this(queryText, expectedLabels, returnedLabels, precisionByK, recallByK, hitByK,
                reciprocalRank, null, null);
    }
}
