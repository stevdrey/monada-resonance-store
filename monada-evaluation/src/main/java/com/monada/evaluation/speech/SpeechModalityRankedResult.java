package com.monada.evaluation.speech;

import java.util.Objects;

/** A stable speech-sample result from one retrieval modality. */
public record SpeechModalityRankedResult(String sampleId, double score, int rank) {
    public SpeechModalityRankedResult {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
