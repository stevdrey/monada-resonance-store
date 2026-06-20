package com.monada.speech.evaluation;

import com.monada.speech.retrieval.SpeechRetrievalOptions;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * A single evaluation query for speech retrieval.
 *
 * @param queryId           opaque identifier for the query (must be non-blank)
 * @param queryAudio        path to the query audio file (must be non-null)
 * @param relevantSampleIds sample IDs considered relevant for this query (must be non-empty)
 * @param retrievalOptions  options passed to the retriever
 */
public record SpeechEvaluationQuery(
        String queryId,
        Path queryAudio,
        Set<String> relevantSampleIds,
        SpeechRetrievalOptions retrievalOptions
) {
    public SpeechEvaluationQuery {
        Objects.requireNonNull(queryId, "queryId");
        if (queryId.isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        Objects.requireNonNull(queryAudio, "queryAudio");
        Objects.requireNonNull(relevantSampleIds, "relevantSampleIds");
        if (relevantSampleIds.isEmpty()) {
            throw new IllegalArgumentException("relevantSampleIds must not be empty for query: " + queryId);
        }
        relevantSampleIds = Set.copyOf(relevantSampleIds);
        for (String id : relevantSampleIds) {
            Objects.requireNonNull(id, "relevantSampleIds must not contain null");
            if (id.isBlank()) {
                throw new IllegalArgumentException("relevantSampleIds must not contain blank values for query: " + queryId);
            }
        }
        Objects.requireNonNull(retrievalOptions, "retrievalOptions");
    }
}
