package com.monada.speech.evaluation;

/**
 * Configuration for a speech retrieval evaluation run.
 *
 * @param k                        number of retrieved results to evaluate (must be positive)
 * @param includePerQueryDiagnostics if true, the report includes the full
 *                                 {@link SpeechQueryEvaluationResult} list; if false,
 *                                 the aggregate metrics are still computed but the
 *                                 per-query list is empty
 */
public record SpeechEvaluationOptions(
        int k,
        boolean includePerQueryDiagnostics
) {
    public SpeechEvaluationOptions {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
    }
}
