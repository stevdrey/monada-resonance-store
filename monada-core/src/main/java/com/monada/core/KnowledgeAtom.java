package com.monada.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record KnowledgeAtom(
        String id,
        AtomType type,
        String content,
        List<String> aliases,
        Map<String, Object> metadata,
        double weight,
        Instant createdAt
) {
    public KnowledgeAtom {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(content);
        aliases = List.copyOf(Objects.requireNonNull(aliases));
        for (String alias : aliases) {
            Objects.requireNonNull(alias, "alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("aliases must not contain blank values");
            }
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata));
        Objects.requireNonNull(createdAt);
    }

    public KnowledgeAtom(String id, AtomType type, String content, Map<String, Object> metadata,
                         double weight, Instant createdAt) {
        this(id, type, content, List.of(), metadata, weight, createdAt);
    }

    public static KnowledgeAtom text(String content) {
        return text(content, List.of());
    }

    public static KnowledgeAtom text(String content, List<String> aliases) {
        Objects.requireNonNull(content, "content");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        String id = deterministicId(content);
        return new KnowledgeAtom(
                id,
                AtomType.TEXT,
                content,
                aliases,
                Map.of(),
                1.0,
                Instant.now()
        );
    }

    public String searchableContent() {
        if (aliases.isEmpty()) {
            return content;
        }
        return content + " " + String.join(" ", aliases);
    }

    private static String deterministicId(String content) {
        // Deterministic id derived from content: the same input produces the same id
        // across JVM runs and fresh memory directories. This keeps recall ordering
        // reproducible when resonance scores tie and atoms are compared by id.
        return UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
    }
}