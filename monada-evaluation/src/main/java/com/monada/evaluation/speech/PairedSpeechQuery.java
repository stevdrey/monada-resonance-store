package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * A single semantic query evaluated through both transcript and acoustic retrieval.
 *
 * @param queryId opaque, stable identifier for the query
 * @param queryAudio query WAV used by the acoustic arm
 * @param transcript query text used by the transcript arm
 * @param relevantSampleIds stable speech sample IDs considered relevant
 * @param condition optional grouping condition
 * @param taskType optional grouping task type
 */
public record PairedSpeechQuery(
        String queryId,
        Path queryAudio,
        String transcript,
        Set<String> relevantSampleIds,
        SpeechCondition condition,
        SpeechTaskType taskType
) {
    public PairedSpeechQuery {
        Objects.requireNonNull(queryId, "queryId");
        if (queryId.isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        Objects.requireNonNull(queryAudio, "queryAudio");
        Objects.requireNonNull(transcript, "transcript");
        if (transcript.isBlank()) {
            throw new IllegalArgumentException("transcript must not be blank for query: " + queryId);
        }
        Objects.requireNonNull(relevantSampleIds, "relevantSampleIds");
        if (relevantSampleIds.isEmpty()) {
            throw new IllegalArgumentException("relevantSampleIds must not be empty for query: " + queryId);
        }
        for (String sampleId : relevantSampleIds) {
            Objects.requireNonNull(sampleId, "relevantSampleIds must not contain null");
            if (sampleId.isBlank()) {
                throw new IllegalArgumentException(
                        "relevantSampleIds must not contain blank values for query: " + queryId);
            }
        }
        relevantSampleIds = Set.copyOf(relevantSampleIds);
    }
}
