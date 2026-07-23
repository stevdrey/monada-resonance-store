package com.monada.speech.evaluation;

import com.monada.speech.retrieval.SpeechRetrievalDiagnostic;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Acoustic scan diagnostics plus relevance-aware explanations for one evaluation query.
 */
public record SpeechQueryAcousticDiagnostic(
        SpeechRetrievalDiagnostic retrieval,
        List<SpeechRelevantCandidateDiagnostic> relevantCandidates
) {
    public SpeechQueryAcousticDiagnostic {
        Objects.requireNonNull(retrieval, "retrieval");
        relevantCandidates = List.copyOf(Objects.requireNonNull(relevantCandidates, "relevantCandidates"));
        for (int i = 1; i < relevantCandidates.size(); i++) {
            if (Comparator.comparing(SpeechRelevantCandidateDiagnostic::sampleId)
                    .compare(relevantCandidates.get(i - 1), relevantCandidates.get(i)) > 0) {
                throw new IllegalArgumentException("relevantCandidates must be sorted by sampleId");
            }
        }
    }
}
