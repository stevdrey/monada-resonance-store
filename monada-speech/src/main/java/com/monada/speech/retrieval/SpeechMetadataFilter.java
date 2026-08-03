package com.monada.speech.retrieval;

/**
 * Metadata filters that can exclude a speech sample before acoustic scoring.
 *
 * <p>The declaration order is the deterministic reporting order.
 */
public enum SpeechMetadataFilter {
    DATASET_SOURCE,
    CONDITION,
    TASK_TYPE,
    SPEAKER_ID,
    LANGUAGE
}
