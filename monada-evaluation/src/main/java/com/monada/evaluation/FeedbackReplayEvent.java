package com.monada.evaluation;

import com.monada.storage.feedback.FeedbackSignal;

import java.time.Instant;
import java.util.Objects;

/**
 * Inspectable feedback event used by the evaluation replay fixture.
 *
 * <p>The stable dataset label is resolved to a content-derived atom id only after
 * the evaluation store has been seeded. Repeated records represent repeated
 * persisted events; no evaluation-only count field is introduced.
 */
public record FeedbackReplayEvent(
        String queryText,
        String queryKey,
        String targetLabel,
        FeedbackSignal signal,
        double delta,
        Instant createdAt,
        FeedbackReplayExpectedScope expectedScope
) {
    public FeedbackReplayEvent {
        queryText = requireNonBlank(queryText, "queryText");
        queryKey = requireNonBlank(queryKey, "queryKey");
        targetLabel = requireNonBlank(targetLabel, "targetLabel");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expectedScope, "expectedScope");
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("delta must be finite: " + delta);
        }
        if (signal == FeedbackSignal.POSITIVE && delta <= 0.0) {
            throw new IllegalArgumentException("positive feedback delta must be greater than zero");
        }
        if (signal == FeedbackSignal.NEGATIVE && delta >= 0.0) {
            throw new IllegalArgumentException("negative feedback delta must be less than zero");
        }
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
