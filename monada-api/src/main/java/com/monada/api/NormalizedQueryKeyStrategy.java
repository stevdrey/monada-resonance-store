package com.monada.api;

import com.monada.encoder.TextNormalizer;

import java.util.Objects;

/**
 * Feedback key strategy based on the configured normalized query text.
 */
public final class NormalizedQueryKeyStrategy implements FeedbackQueryKeyStrategy {

    private final TextNormalizer textNormalizer;

    public NormalizedQueryKeyStrategy(TextNormalizer textNormalizer) {
        this.textNormalizer = Objects.requireNonNull(textNormalizer, "textNormalizer");
    }

    @Override
    public String keyFor(String query) {
        Objects.requireNonNull(query, "query");
        var normalized = textNormalizer.normalize(query).normalized();
        if (normalized.isBlank()) {
            return query;
        }
        return "normalized:" + normalized;
    }
}
