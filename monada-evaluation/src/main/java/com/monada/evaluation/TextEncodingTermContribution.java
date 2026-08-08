package com.monada.evaluation;

import java.util.Objects;

/** Aggregated occurrences of one weighted term from one encoding source. */
public record TextEncodingTermContribution(
        String term,
        TextEncodingContributionSource source,
        double appliedWeight,
        int occurrences,
        double totalWeight
) {
    public TextEncodingTermContribution {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(source, "source");
        if (term.isBlank()) {
            throw new IllegalArgumentException("term must not be blank");
        }
        if (!Double.isFinite(appliedWeight) || appliedWeight <= 0.0) {
            throw new IllegalArgumentException("appliedWeight must be finite and positive");
        }
        if (occurrences <= 0) {
            throw new IllegalArgumentException("occurrences must be positive: " + occurrences);
        }
        if (!Double.isFinite(totalWeight) || totalWeight <= 0.0) {
            throw new IllegalArgumentException("totalWeight must be finite and positive");
        }
        double expectedTotal = appliedWeight * occurrences;
        double tolerance = Math.max(1.0e-12, Math.ulp(expectedTotal) * 4.0);
        if (Math.abs(totalWeight - expectedTotal) > tolerance) {
            throw new IllegalArgumentException(
                    "totalWeight must equal appliedWeight * occurrences: "
                            + totalWeight + " != " + expectedTotal);
        }
    }
}
