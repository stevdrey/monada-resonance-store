package com.monada.speech.retrieval;

import java.util.List;
import java.util.Objects;

/**
 * Diagnostic disposition for one stored acoustic feature vector.
 *
 * @param sampleId sample identifier referenced by the vector
 * @param status candidate disposition
 * @param failedFilters metadata filters that rejected the sample, in deterministic order
 * @param score cosine score for a scored candidate, otherwise {@code 0.0}
 * @param rank full scored-candidate rank, otherwise {@code 0}
 */
public record SpeechCandidateDiagnostic(
        String sampleId,
        SpeechCandidateStatus status,
        List<SpeechMetadataFilter> failedFilters,
        double score,
        int rank
) {
    public SpeechCandidateDiagnostic {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        failedFilters = List.copyOf(Objects.requireNonNull(failedFilters, "failedFilters"));
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }

        if (status == SpeechCandidateStatus.SCORED) {
            if (!failedFilters.isEmpty()) {
                throw new IllegalArgumentException("scored candidate must not have failed filters");
            }
            if (rank <= 0) {
                throw new IllegalArgumentException("scored candidate rank must be positive: " + rank);
            }
        } else {
            if (score != 0.0) {
                throw new IllegalArgumentException("excluded candidate score must be 0.0: " + score);
            }
            if (rank != 0) {
                throw new IllegalArgumentException("excluded candidate rank must be 0: " + rank);
            }
        }

        if (status == SpeechCandidateStatus.METADATA_FILTERED && failedFilters.isEmpty()) {
            throw new IllegalArgumentException("metadata-filtered candidate must have failed filters");
        }
        if (status != SpeechCandidateStatus.METADATA_FILTERED && !failedFilters.isEmpty()) {
            throw new IllegalArgumentException("only metadata-filtered candidates may have failed filters");
        }
    }
}
