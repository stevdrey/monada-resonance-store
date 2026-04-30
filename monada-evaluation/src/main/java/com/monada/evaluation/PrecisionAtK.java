package com.monada.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure computation of Precision@K.
 *
 * <pre>
 *     Precision@K = |{relevant} ∩ topK| / K
 * </pre>
 *
 * <p>If {@code ranked} contains fewer than {@code k} elements, only the available
 * elements are considered as retrieved, but the denominator remains {@code k} (standard
 * IR convention).
 */
public final class PrecisionAtK {

    private PrecisionAtK() {
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

        int limit = Math.min(k, ranked.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            String candidate = ranked.get(i);
            if (candidate != null && expected.contains(candidate)) {
                hits++;
            }
        }
        return (double) hits / (double) k;
    }
}
