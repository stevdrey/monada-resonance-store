package com.monada.evaluation;

import java.util.Objects;
import java.util.Set;

public record EvaluationQuery(String text, Set<String> expectedLabels) {
    public EvaluationQuery {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        if (expectedLabels.isEmpty()) {
            throw new IllegalArgumentException("expectedLabels must not be empty");
        }
    }
}
