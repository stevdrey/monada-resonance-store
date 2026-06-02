package com.monada.storage.feedback;

import java.time.Instant;
import java.util.Objects;

public record FeedbackEvent(
        String query,
        String queryKey,
        String atomId,
        FeedbackSignal signal,
        double delta,
        Instant createdAt
) {
    public FeedbackEvent(String query, String atomId, FeedbackSignal signal, double delta, Instant createdAt) {
        this(query, query, atomId, signal, delta, createdAt);
    }

    public FeedbackEvent {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(queryKey, "queryKey");
        Objects.requireNonNull(atomId, "atomId");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("delta must be finite, got: " + delta);
        }
        if (signal == FeedbackSignal.POSITIVE && delta <= 0.0) {
            throw new IllegalArgumentException("positive feedback delta must be greater than zero");
        }
        if (signal == FeedbackSignal.NEGATIVE && delta >= 0.0) {
            throw new IllegalArgumentException("negative feedback delta must be less than zero");
        }
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        if (queryKey.isEmpty()) {
            throw new IllegalArgumentException("queryKey must not be empty");
        }
        if (atomId.isEmpty()) {
            throw new IllegalArgumentException("atomId must not be empty");
        }
    }
}
