package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

public record DatasetAtom(String label, String content, List<String> aliases) {
    public DatasetAtom {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(content, "content");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        for (String alias : aliases) {
            Objects.requireNonNull(alias, "alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("aliases must not contain blank values");
            }
        }
    }

    public DatasetAtom(String label, String content) {
        this(label, content, List.of());
    }
}
