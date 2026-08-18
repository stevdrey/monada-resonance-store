package com.monada.evaluation.speech;

import java.util.Objects;

/** Complete paired result for one semantic speech query. */
public record SpeechModalityComparisonQueryResult(
        PairedSpeechQuery query,
        SpeechModalityQueryResult transcript,
        SpeechModalityQueryResult acoustic,
        double topKJaccard,
        SpeechModalityOutcome outcome
) {
    public SpeechModalityComparisonQueryResult {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(acoustic, "acoustic");
        if (!Double.isFinite(topKJaccard) || topKJaccard < 0.0 || topKJaccard > 1.0) {
            throw new IllegalArgumentException("topKJaccard must be finite and between 0.0 and 1.0");
        }
        Objects.requireNonNull(outcome, "outcome");
    }
}
