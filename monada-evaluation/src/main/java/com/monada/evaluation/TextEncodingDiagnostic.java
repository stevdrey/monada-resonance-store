package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/** Optional query and candidate lexical contribution diagnostic. */
public record TextEncodingDiagnostic(
        TextEncodingRepresentationDiagnostic queryRepresentation,
        List<TextEncodingCandidateDiagnostic> candidates,
        int omittedTopResultCount,
        int omittedMissedExpectedCount
) {
    public TextEncodingDiagnostic {
        Objects.requireNonNull(queryRepresentation, "queryRepresentation");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (omittedTopResultCount < 0) {
            throw new IllegalArgumentException("omittedTopResultCount must be non-negative");
        }
        if (omittedMissedExpectedCount < 0) {
            throw new IllegalArgumentException("omittedMissedExpectedCount must be non-negative");
        }
    }
}
