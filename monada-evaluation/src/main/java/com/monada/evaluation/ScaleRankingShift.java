package com.monada.evaluation;

import java.util.Objects;
import java.util.Set;

/**
 * Details of a ranking change observed for a specific query between the seed baseline
 * and a scaled evaluation run with distractors.
 */
public record ScaleRankingShift(
        String queryText,
        Set<String> expectedLabels,
        int baselineRank,
        int scaledRank,
        RankingChange change
) {
    public ScaleRankingShift {
        Objects.requireNonNull(queryText, "queryText");
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        if (baselineRank < 0) {
            throw new IllegalArgumentException("baselineRank must be >= 0, got: " + baselineRank);
        }
        if (scaledRank < 0) {
            throw new IllegalArgumentException("scaledRank must be >= 0, got: " + scaledRank);
        }
        Objects.requireNonNull(change, "change");
    }
}
