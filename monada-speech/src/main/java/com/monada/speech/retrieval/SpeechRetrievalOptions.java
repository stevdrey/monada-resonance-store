package com.monada.speech.retrieval;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechTaskType;

/**
 * Configuration options for speech sample retrieval queries.
 *
 * @param topK maximum number of results to return (must be positive)
 * @param datasetSource optional filter for dataset source
 * @param condition optional filter for speech condition
 * @param taskType optional filter for speech task type
 * @param speakerId optional filter for speaker identifier
 * @param language optional filter for language code
 */
public record SpeechRetrievalOptions(
        int topK,
        SpeechDatasetSource datasetSource,
        SpeechCondition condition,
        SpeechTaskType taskType,
        String speakerId,
        String language
) {
    public SpeechRetrievalOptions {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive: " + topK);
        }
    }
}
