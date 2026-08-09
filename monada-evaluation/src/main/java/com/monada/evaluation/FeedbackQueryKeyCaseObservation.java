package com.monada.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete before/after evidence for one fixture case under one strategy. */
public record FeedbackQueryKeyCaseObservation(
        FeedbackQueryKeyComparisonCase comparisonCase,
        String seedQueryKey,
        String evaluationQueryKey,
        boolean keysMatched,
        boolean targetRelevantToEvaluationQuery,
        FeedbackQueryKeyTargetRank targetBeforeReplay,
        FeedbackQueryKeyTargetRank targetAfterReplay,
        RankingChange targetRankChange,
        List<String> baselineTopK,
        List<String> replayedTopK,
        Map<Integer, Double> precisionDeltaByK,
        Map<Integer, Double> recallDeltaByK,
        Map<Integer, Double> hitDeltaByK,
        double reciprocalRankDelta,
        FeedbackQueryKeyCaseClassification classification
) {
    public FeedbackQueryKeyCaseObservation {
        Objects.requireNonNull(comparisonCase, "comparisonCase");
        seedQueryKey = requireNonBlank(seedQueryKey, "seedQueryKey");
        evaluationQueryKey = requireNonBlank(evaluationQueryKey, "evaluationQueryKey");
        Objects.requireNonNull(targetBeforeReplay, "targetBeforeReplay");
        Objects.requireNonNull(targetAfterReplay, "targetAfterReplay");
        Objects.requireNonNull(targetRankChange, "targetRankChange");
        baselineTopK = List.copyOf(Objects.requireNonNull(baselineTopK, "baselineTopK"));
        replayedTopK = List.copyOf(Objects.requireNonNull(replayedTopK, "replayedTopK"));
        precisionDeltaByK = sortedCopy(precisionDeltaByK, "precisionDeltaByK");
        recallDeltaByK = sortedCopy(recallDeltaByK, "recallDeltaByK");
        hitDeltaByK = sortedCopy(hitDeltaByK, "hitDeltaByK");
        if (!Double.isFinite(reciprocalRankDelta)) {
            throw new IllegalArgumentException("reciprocalRankDelta must be finite");
        }
        Objects.requireNonNull(classification, "classification");
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private Map<Integer, Double> sortedCopy(Map<Integer, Double> values, String name) {
        Objects.requireNonNull(values, name);
        var copy = new TreeMap<Integer, Double>();
        for (Map.Entry<Integer, Double> entry : values.entrySet()) {
            Integer key = Objects.requireNonNull(entry.getKey(), name + " key");
            Double value = Objects.requireNonNull(entry.getValue(), name + " value");
            if (key <= 0 || !Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain positive K values and finite deltas");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
