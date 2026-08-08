package com.monada.evaluation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, deterministic weighted-term representation used in diagnostics. */
public record TextEncodingRepresentationDiagnostic(
        List<TextEncodingTermContribution> terms,
        int omittedTermCount
) {
    private static final Comparator<TextEncodingTermContribution> TERM_ORDER = Comparator
            .comparing(TextEncodingTermContribution::source)
            .thenComparing(TextEncodingTermContribution::term)
            .thenComparingDouble(TextEncodingTermContribution::appliedWeight);

    public TextEncodingRepresentationDiagnostic {
        terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
        if (omittedTermCount < 0) {
            throw new IllegalArgumentException("omittedTermCount must be non-negative");
        }
        for (int i = 1; i < terms.size(); i++) {
            if (TERM_ORDER.compare(terms.get(i - 1), terms.get(i)) > 0) {
                throw new IllegalArgumentException("terms must use deterministic source/term order");
            }
        }
    }
}
