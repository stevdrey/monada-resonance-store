package com.monada.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Encoding contribution details for one selected evaluation atom. */
public record TextEncodingCandidateDiagnostic(
        String label,
        TextEncodingCandidateSelection selection,
        OptionalInt rank,
        OptionalDouble score,
        TextEncodingRepresentationDiagnostic representation,
        List<String> overlappingTerms
) {
    public TextEncodingCandidateDiagnostic {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(representation, "representation");
        overlappingTerms = List.copyOf(Objects.requireNonNull(overlappingTerms, "overlappingTerms"));
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (rank.isPresent() && rank.getAsInt() <= 0) {
            throw new IllegalArgumentException("rank must be positive when present");
        }
        if (score.isPresent() && !Double.isFinite(score.getAsDouble())) {
            throw new IllegalArgumentException("score must be finite when present");
        }
        if (rank.isPresent() != score.isPresent()) {
            throw new IllegalArgumentException("rank and score must either both be present or both be absent");
        }
        for (int i = 1; i < overlappingTerms.size(); i++) {
            if (overlappingTerms.get(i - 1).compareTo(overlappingTerms.get(i)) >= 0) {
                throw new IllegalArgumentException("overlappingTerms must be sorted and unique");
            }
        }
    }
}
