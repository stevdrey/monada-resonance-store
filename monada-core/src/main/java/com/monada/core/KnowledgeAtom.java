package com.monada.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record KnowledgeAtom(
        String id,
        AtomType type,
        String content,
        Map<String, Object> metadata,
        double weight,
        Instant createdAt
) {
    public KnowledgeAtom {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(content);
        metadata = Map.copyOf(Objects.requireNonNull(metadata));
        Objects.requireNonNull(createdAt);
    }

    public static KnowledgeAtom text(String content) {
        return new KnowledgeAtom(
                UUID.randomUUID().toString(),
                AtomType.TEXT,
                content,
                Map.of(),
                1.0,
                Instant.now()
        );
    }
}