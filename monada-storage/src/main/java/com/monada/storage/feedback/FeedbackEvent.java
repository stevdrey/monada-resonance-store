package com.monada.storage.feedback;

import java.time.Instant;
import java.util.Objects;

public record FeedbackEvent(
        String query,
        String atomId,
        FeedbackSignal signal,
        double delta,
        Instant createdAt
) {
    public FeedbackEvent {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(atomId, "atomId");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("delta must be finite, got: " + delta);
        }
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        if (atomId.isEmpty()) {
            throw new IllegalArgumentException("atomId must not be empty");
        }
    }
}
