package com.monada.evaluation.speech;

/** Outcome of comparing transcript and acoustic Hit@K for one paired query. */
public enum SpeechModalityOutcome {
    BOTH_SUCCEED,
    TRANSCRIPT_ONLY,
    ACOUSTIC_ONLY,
    BOTH_FAIL
}
