package com.monada.evaluation.speech;

/**
 * Identifies whether a modality-comparison report came from generated CI fixtures
 * or an exploratory local corpus.
 */
public enum SpeechModalityEvidence {
    GENERATED_CI,
    LOCAL_EXPLORATORY
}
