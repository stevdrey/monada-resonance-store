package com.monada.speech.retrieval;

import com.monada.speech.domain.SpeechSample;

import java.util.Objects;

/**
 * Result of a speech sample retrieval query, containing the matched sample,
 * similarity score, and rank position.
 *
 * @param sample the matched speech sample
 * @param score acoustic similarity score (higher is more similar)
 * @param rank 1-based rank position in the result list
 */
public record SpeechRetrievalResult(
        SpeechSample sample,
        double score,
        int rank
) {
    public SpeechRetrievalResult {
        Objects.requireNonNull(sample, "sample");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
    }
}
