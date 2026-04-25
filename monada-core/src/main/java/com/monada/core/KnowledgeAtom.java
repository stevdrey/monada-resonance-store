package com.monada.core;

import java.time.Instant;
import java.util.Map;

public record KnowledgeAtom(
        String id,
        String type,
        String content,
        Map<String, Object> metadata,
        double weight,
        Instant createdAt
) {}