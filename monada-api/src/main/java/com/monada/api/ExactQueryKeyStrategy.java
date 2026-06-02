package com.monada.api;

import java.util.Objects;

/**
 * Feedback key strategy that preserves exact-query matching.
 */
public final class ExactQueryKeyStrategy implements FeedbackQueryKeyStrategy {

    @Override
    public String keyFor(String query) {
        return Objects.requireNonNull(query, "query");
    }
}
