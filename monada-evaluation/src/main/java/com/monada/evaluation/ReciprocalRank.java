package com.monada.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure computation of Reciprocal Rank.
 *
 * <pre>
 *     ReciprocalRank = 1 / rank position of first relevant result
 *                    = 0.0 if no relevant result is found
 * </pre>
 *
 * <p>Ranks are 1-indexed.
 */
public final class ReciprocalRank {

    private ReciprocalRank() {
    }

    public static double compute(Set<String> expected, List<String> ranked) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(ranked, "ranked");
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("expected must not be empty");
        }

        for (var i = 0; i < ranked.size(); i++) {
            var candidate = ranked.get(i);
            if (candidate != null && expected.contains(candidate)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
