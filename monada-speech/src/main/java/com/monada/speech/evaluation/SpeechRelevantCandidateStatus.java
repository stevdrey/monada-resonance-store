package com.monada.speech.evaluation;

/**
 * Diagnostic outcome for a sample ID declared relevant to an evaluation query.
 */
public enum SpeechRelevantCandidateStatus {
    RETRIEVED_AT_K,
    SCORED_BELOW_K,
    METADATA_FILTERED,
    INCOMPATIBLE_DIMENSIONS,
    ORPHAN_VECTOR,
    MISSING_VECTOR,
    MISSING_SAMPLE
}
