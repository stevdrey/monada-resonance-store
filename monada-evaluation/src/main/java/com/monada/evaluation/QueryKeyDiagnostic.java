package com.monada.evaluation;

import java.util.Objects;

/**
 * Diagnostic information about the feedback query key used during evaluation.
 *
 * <p>Captures the effective query key produced by the configured
 * {@code FeedbackQueryKeyStrategy}, the strategy name, whether feedback-aware
 * ranking was enabled, and optionally the seed query key used when feedback
 * was seeded before evaluation.
 *
 * <p>This is diagnostic-only and does not affect ranking behavior.
 *
 * @param queryKey           the effective feedback query key (e.g., "exact:query text")
 * @param strategyName       the simple name of the FeedbackQueryKeyStrategy class
 * @param feedbackAware      whether feedback-aware ranking was enabled
 * @param seedQueryKey       the query key used to seed feedback (null if not feedback-aware)
 */
public record QueryKeyDiagnostic(
        String queryKey,
        String strategyName,
        boolean feedbackAware,
        String seedQueryKey
) {
    public QueryKeyDiagnostic {
        Objects.requireNonNull(queryKey, "queryKey");
        Objects.requireNonNull(strategyName, "strategyName");
        // seedQueryKey can be null for non-feedback-aware profiles
    }

    /**
     * Creates a diagnostic for non-feedback-aware evaluation.
     */
    public static QueryKeyDiagnostic withoutFeedback(String queryKey, String strategyName) {
        return new QueryKeyDiagnostic(queryKey, strategyName, false, null);
    }

    /**
     * Creates a diagnostic for feedback-aware evaluation with seed key.
     */
    public static QueryKeyDiagnostic withFeedback(
            String queryKey,
            String strategyName,
            String seedQueryKey) {
        Objects.requireNonNull(seedQueryKey, "seedQueryKey must not be null for feedback-aware diagnostics");
        return new QueryKeyDiagnostic(queryKey, strategyName, true, seedQueryKey);
    }

    /**
     * Returns true if the seed query key matches the evaluation query key.
     * Always returns false for non-feedback-aware profiles (when seedQueryKey is null).
     */
    public boolean feedbackKeyMatch() {
        return seedQueryKey != null && seedQueryKey.equals(queryKey);
    }

    /**
     * Returns true if this diagnostic has a seed query key (i.e., it's feedback-aware).
     */
    public boolean hasSeedQueryKey() {
        return seedQueryKey != null;
    }
}
