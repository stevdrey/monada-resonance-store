package com.monada.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure computation of Hit@K.
 *
 * <pre>
 *     Hit@K = 1.0 if at least one expected label appears within the top K
 *             results, otherwise 0.0
 * </pre>
 */
public final class HitAtK {

    private HitAtK() {
    }

    public static double compute(Set<String> expected, List<String> ranked, int k) {
        EvaluationMaps.validateLabels(expected, "expected");
        Objects.requireNonNull(ranked, "ranked");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be greater than zero");
        }
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("expected must not be empty");
        }

        var limit = Math.min(k, ranked.size());
        for (var i = 0; i < limit; i++) {
            var candidate = ranked.get(i);
            if (candidate != null && expected.contains(candidate)) {
                return 1.0;
            }
        }
        return 0.0;
    }
}
