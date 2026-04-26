package com.monada.evaluation;

import java.util.Objects;

public record DatasetAtom(String label, String content) {
    public DatasetAtom {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(content, "content");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
