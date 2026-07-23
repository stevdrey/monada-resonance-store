package com.monada.speech.retrieval;

import java.util.List;
import java.util.Objects;

/**
 * Ranked results and diagnostics produced by one opt-in acoustic retrieval.
 */
public record SpeechRetrievalOutcome(
        List<SpeechRetrievalResult> results,
        SpeechRetrievalDiagnostic diagnostic
) {
    public SpeechRetrievalOutcome {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(diagnostic, "diagnostic");
    }
}
