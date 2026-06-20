package com.monada.speech.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Per-query evaluation result for a speech retrieval query.
 *
 * @param queryId             opaque identifier for the query
 * @param retrievedCount      number of results returned for this query (limited by evaluation k)
 * @param relevantRetrievedCount number of returned results that are relevant
 * @param hitAtK              true if at least one relevant sample appears in the top-k
 * @param precisionAtK        precision at k
 * @param recallAtK           recall at k
 * @param reciprocalRank      reciprocal rank of the first relevant result, or 0.0 if none
 * @param retrievedSampleIds  ranked list of sample IDs returned by the retriever
 * @param missedRelevantSampleIds relevant sample IDs that did not appear in the top-k, sorted
 * @param topResultSampleId   sample ID of the highest-ranked result, or null if no results
 * @param topResultScore      score of the highest-ranked result, or 0.0 if no results
 */
public record SpeechQueryEvaluationResult(
        String queryId,
        int retrievedCount,
        int relevantRetrievedCount,
        boolean hitAtK,
        double precisionAtK,
        double recallAtK,
        double reciprocalRank,
        List<String> retrievedSampleIds,
        List<String> missedRelevantSampleIds,
        String topResultSampleId,
        double topResultScore
) {
    public SpeechQueryEvaluationResult {
        Objects.requireNonNull(queryId, "queryId");
        if (queryId.isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        if (retrievedCount < 0) {
            throw new IllegalArgumentException("retrievedCount must be non-negative");
        }
        if (relevantRetrievedCount < 0 || relevantRetrievedCount > retrievedCount) {
            throw new IllegalArgumentException("relevantRetrievedCount must be between 0 and retrievedCount");
        }
        if (!Double.isFinite(precisionAtK)) {
            throw new IllegalArgumentException("precisionAtK must be finite");
        }
        if (!Double.isFinite(recallAtK)) {
            throw new IllegalArgumentException("recallAtK must be finite");
        }
        if (!Double.isFinite(reciprocalRank)) {
            throw new IllegalArgumentException("reciprocalRank must be finite");
        }
        Objects.requireNonNull(retrievedSampleIds, "retrievedSampleIds");
        Objects.requireNonNull(missedRelevantSampleIds, "missedRelevantSampleIds");
        if (!Double.isFinite(topResultScore)) {
            throw new IllegalArgumentException("topResultScore must be finite");
        }
        retrievedSampleIds = List.copyOf(retrievedSampleIds);
        missedRelevantSampleIds = List.copyOf(missedRelevantSampleIds);
    }
}
