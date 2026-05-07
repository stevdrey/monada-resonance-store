package com.monada.encoder;

import java.util.List;
import java.util.Objects;

public record NormalizedQuery(String original, String normalized, List<String> expansions) {
    public NormalizedQuery {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(normalized, "normalized");
        expansions = List.copyOf(Objects.requireNonNull(expansions, "expansions"));
        for (String expansion : expansions) {
            Objects.requireNonNull(expansion, "expansion");
            if (expansion.isBlank()) {
                throw new IllegalArgumentException("expansions must not contain blank values");
            }
        }
    }

    public String enrichedText() {
        if (expansions.isEmpty()) {
            return normalized;
        }
        return normalized + " " + String.join(" ", expansions);
    }
}
