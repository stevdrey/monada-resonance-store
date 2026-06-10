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
 * <p>Three valid states exist:
 * <ul>
 *   <li><b>Non-feedback-aware</b>: {@code feedbackAware=false}, {@code seedQueryKey=null}.
 *       Use {@link #withoutFeedback}.</li>
 *   <li><b>Feedback-aware with explicit seeding</b>: {@code feedbackAware=true},
 *       {@code seedQueryKey} non-null and non-blank.  Use {@link #withFeedback}.</li>
 *   <li><b>Feedback-aware ranking only</b>: {@code feedbackAware=true},
 *       {@code seedQueryKey=null}. Applies when feedback-aware ranking is active but
 *       this runner did not seed feedback explicitly (e.g. a direct
 *       {@code EvaluationRunner.run(...)} call). Use {@link #feedbackAwareNoSeed}.</li>
 * </ul>
 *
 * <p>This is diagnostic-only and does not affect ranking behavior.
 *
 * @param queryKey           the effective feedback query key (e.g., "exact:query text")
 * @param strategyName       the simple name of the FeedbackQueryKeyStrategy class
 * @param feedbackAware      whether feedback-aware ranking was enabled
 * @param seedQueryKey       the query key used to seed feedback; null when no explicit
 *                           seeding was performed by this runner
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
        if (queryKey.isBlank()) {
            throw new IllegalArgumentException("queryKey must not be blank");
        }
        if (strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName must not be blank");
        }
        // seedQueryKey may be null when feedbackAware=true but no explicit seeding was performed.
        // When present it must not be blank.
        if (seedQueryKey != null && seedQueryKey.isBlank()) {
            throw new IllegalArgumentException("seedQueryKey must not be blank when present");
        }
        if (!feedbackAware && seedQueryKey != null) {
            throw new IllegalArgumentException("seedQueryKey must be null when feedbackAware is false");
        }
    }

    /**
     * Creates a diagnostic for non-feedback-aware evaluation.
     */
    public static QueryKeyDiagnostic withoutFeedback(String queryKey, String strategyName) {
        return new QueryKeyDiagnostic(queryKey, strategyName, false, null);
    }

    /**
     * Creates a diagnostic for feedback-aware evaluation where this runner also seeded
     * explicit feedback events. Both the evaluation key and the seed key are reported,
     * and {@link #feedbackKeyMatch()} reflects whether they agree.
     */
    public static QueryKeyDiagnostic withFeedback(
            String queryKey,
            String strategyName,
            String seedQueryKey) {
        Objects.requireNonNull(seedQueryKey, "seedQueryKey must not be null for feedback-with-seed diagnostics");
        if (seedQueryKey.isBlank()) {
            throw new IllegalArgumentException("seedQueryKey must not be blank");
        }
        return new QueryKeyDiagnostic(queryKey, strategyName, true, seedQueryKey);
    }

    /**
     * Creates a diagnostic for a run where feedback-aware ranking is active but this
     * runner did not seed explicit feedback events (e.g. a direct
     * {@code EvaluationRunner.run(...)} call using default or custom options).
     *
     * <p>The rendered report will show {@code Feedback Aware: true} and the evaluation
     * query key, but will omit the seed-key and key-match lines because no seeding
     * was performed by this runner.
     */
    public static QueryKeyDiagnostic feedbackAwareNoSeed(String queryKey, String strategyName) {
        return new QueryKeyDiagnostic(queryKey, strategyName, true, null);
    }

    /**
     * Returns true if and only if feedback-aware ranking was enabled AND the seed
     * query key matches the evaluation query key.  Always returns false when
     * {@code feedbackAware} is false, even if a non-null seed key was somehow
     * supplied via the public record constructor.
     */
    public boolean feedbackKeyMatch() {
        return feedbackAware && seedQueryKey != null && seedQueryKey.equals(queryKey);
    }

    /**
     * Returns true if this diagnostic has a seed query key (i.e., it's feedback-aware).
     */
    public boolean hasSeedQueryKey() {
        return seedQueryKey != null;
    }
}
