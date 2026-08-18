package com.monada.evaluation.speech;

/** Per-query quality movement for a hybrid profile relative to a single-modality control. */
enum SpeechHybridComparison {
    WIN,
    MAINTAIN,
    REGRESS
}

/** Recovery or loss classification using the two controls' paired Hit@K outcome. */
enum SpeechHybridDisagreementOutcome {
    BOTH_SUCCEED_RETAINED,
    LOST_BOTH_SUCCEED,
    RECOVERED_ACOUSTIC_MISS,
    LOST_TRANSCRIPT_WIN,
    RECOVERED_TRANSCRIPT_MISS,
    LOST_ACOUSTIC_WIN,
    RECOVERED_BOTH_MISS,
    BOTH_MISS_RETAINED
}
