package com.monada.speech.evaluation;

/**
 * Aggregate or grouped speech-retrieval metrics.
 *
 * @param precisionAtK mean precision at k across the included queries
 * @param recallAtK    mean recall at k across the included queries
 * @param hitRateAtK   mean hit rate at k across the included queries
 * @param mrr          mean reciprocal rank across the included queries
 * @param queryCount   number of queries used to compute the metrics
 */
public record SpeechEvaluationMetrics(
        double precisionAtK,
        double recallAtK,
        double hitRateAtK,
        double mrr,
        int queryCount
) {
    public SpeechEvaluationMetrics {
        if (!Double.isFinite(precisionAtK)) {
            throw new IllegalArgumentException("precisionAtK must be finite");
        }
        if (!Double.isFinite(recallAtK)) {
            throw new IllegalArgumentException("recallAtK must be finite");
        }
        if (!Double.isFinite(hitRateAtK)) {
            throw new IllegalArgumentException("hitRateAtK must be finite");
        }
        if (!Double.isFinite(mrr)) {
            throw new IllegalArgumentException("mrr must be finite");
        }
        if (queryCount < 0) {
            throw new IllegalArgumentException("queryCount must be non-negative");
        }
    }
}
