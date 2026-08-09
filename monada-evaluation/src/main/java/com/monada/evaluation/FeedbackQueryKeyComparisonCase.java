package com.monada.evaluation;

import com.monada.storage.feedback.FeedbackSignal;

import java.time.Instant;
import java.util.Objects;

/** One inspectable seed-query to evaluation-query feedback replay case. */
public record FeedbackQueryKeyComparisonCase(
        String id,
        FeedbackQueryKeyComparisonCaseCategory category,
        String seedQueryText,
        String evaluationQueryText,
        String targetLabel,
        FeedbackSignal signal,
        double delta,
        Instant createdAt,
        boolean expectedTargetRelevant
) {
    public FeedbackQueryKeyComparisonCase {
        id = requireNonBlank(id, "id");
        Objects.requireNonNull(category, "category");
        seedQueryText = requireNonBlank(seedQueryText, "seedQueryText");
        evaluationQueryText = requireNonBlank(evaluationQueryText, "evaluationQueryText");
        targetLabel = requireNonBlank(targetLabel, "targetLabel");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException("delta must be finite: " + delta);
        }
        if (signal == FeedbackSignal.POSITIVE && delta <= 0.0) {
            throw new IllegalArgumentException("positive feedback delta must be greater than zero");
        }
        if (signal == FeedbackSignal.NEGATIVE && delta >= 0.0) {
            throw new IllegalArgumentException("negative feedback delta must be less than zero");
        }
        if (category.intendedTransfer() != expectedTargetRelevant) {
            throw new IllegalArgumentException(
                    "category " + category + " must have expectedTargetRelevant="
                            + category.intendedTransfer());
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
