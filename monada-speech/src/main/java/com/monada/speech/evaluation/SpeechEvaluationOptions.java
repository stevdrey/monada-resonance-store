package com.monada.speech.evaluation;

/**
 * Configuration for a speech retrieval evaluation run.
 *
 * @param k                        number of retrieved results to evaluate (must be positive)
 * @param includePerQueryDiagnostics if true, the report includes the full
 *                                 {@link SpeechQueryEvaluationResult} list; if false,
 *                                 the aggregate metrics are still computed but the
 *                                 per-query list is empty
 * @param includeAcousticRetrievalDiagnostics if true, each per-query result includes
 *                                 acoustic scan, filter, score, and relevant-candidate diagnostics
 */
public record SpeechEvaluationOptions(
        int k,
        boolean includePerQueryDiagnostics,
        boolean includeAcousticRetrievalDiagnostics
) {
    /**
     * Backwards-compatible options without deep acoustic retrieval diagnostics.
     */
    public SpeechEvaluationOptions(int k, boolean includePerQueryDiagnostics) {
        this(k, includePerQueryDiagnostics, false);
    }

    public SpeechEvaluationOptions {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (includeAcousticRetrievalDiagnostics && !includePerQueryDiagnostics) {
            throw new IllegalArgumentException(
                    "acoustic retrieval diagnostics require per-query diagnostics");
        }
    }
}
