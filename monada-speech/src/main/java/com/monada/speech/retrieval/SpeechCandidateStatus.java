package com.monada.speech.retrieval;

/**
 * Diagnostic disposition assigned to a stored acoustic feature vector.
 */
public enum SpeechCandidateStatus {
    ORPHAN_VECTOR,
    INCOMPATIBLE_DIMENSIONS,
    METADATA_FILTERED,
    SCORED
}
