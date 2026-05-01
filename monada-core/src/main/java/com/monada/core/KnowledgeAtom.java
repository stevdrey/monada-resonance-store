package com.monada.core;

import java.nio.charset.StandardCharsets;
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
        // Deterministic id derived from content: the same input produces the same id
        // across JVM runs and fresh memory directories. This keeps recall ordering
        // reproducible when resonance scores tie and atoms are compared by id.
        Objects.requireNonNull(content, "content");
        String id = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
        return new KnowledgeAtom(
                id,
                AtomType.TEXT,
                content,
                Map.of(),
                1.0,
                Instant.now()
        );
    }
}