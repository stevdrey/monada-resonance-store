package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/** Complete normalized-key and persisted-replay evidence for one stress pair. */
public record NormalizedQueryKeyStressObservation(
        NormalizedQueryKeyStressCase stressCase,
        String normalizedA,
        String normalizedB,
        String queryKeyA,
        String queryKeyB,
        boolean keysMatched,
        FeedbackQueryKeyTargetRank targetBeforeReplay,
        FeedbackQueryKeyTargetRank targetAfterReplay,
        RankingChange targetRankChange,
        boolean scoreChanged,
        List<String> baselineTopK,
        List<String> replayedTopK,
        boolean baselineTopKPrefixConsistent,
        boolean replayedTopKPrefixConsistent,
        NormalizedQueryKeyStressClassification classification
) {
    public NormalizedQueryKeyStressObservation {
        Objects.requireNonNull(stressCase, "stressCase");
        normalizedA = Objects.requireNonNull(normalizedA, "normalizedA");
        normalizedB = Objects.requireNonNull(normalizedB, "normalizedB");
        queryKeyA = requireNonBlank(queryKeyA, "queryKeyA");
        queryKeyB = requireNonBlank(queryKeyB, "queryKeyB");
        Objects.requireNonNull(targetBeforeReplay, "targetBeforeReplay");
        Objects.requireNonNull(targetAfterReplay, "targetAfterReplay");
        Objects.requireNonNull(targetRankChange, "targetRankChange");
        baselineTopK = List.copyOf(Objects.requireNonNull(baselineTopK, "baselineTopK"));
        replayedTopK = List.copyOf(Objects.requireNonNull(replayedTopK, "replayedTopK"));
        Objects.requireNonNull(classification, "classification");
        if (keysMatched != queryKeyA.equals(queryKeyB)) {
            throw new IllegalArgumentException("keysMatched must reflect the observed query keys");
        }
    }

    public boolean targetMoved() {
        return targetRankChange != RankingChange.MAINTAINED || scoreChanged;
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
