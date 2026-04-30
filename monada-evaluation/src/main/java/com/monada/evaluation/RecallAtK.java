package com.monada.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure computation of Recall@K.
 *
 * <pre>
 *     Recall@K = |{relevant} ∩ topK| / |{relevant}|
 * </pre>
 *
 * <p>Each expected label is counted at most once even if it appears multiple
 * times in {@code ranked} (set-based recall).
 */
public final class RecallAtK {

    private RecallAtK() {
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
        var matched = new HashSet<String>();
        for (var i = 0; i < limit; i++) {
            var candidate = ranked.get(i);
            if (candidate != null && expected.contains(candidate)) {
                matched.add(candidate);
            }
        }
        return (double) matched.size() / (double) expected.size();
    }
}
