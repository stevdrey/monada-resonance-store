package com.monada.speech.evaluation;

import com.monada.speech.retrieval.SpeechMetadataFilter;

import java.util.List;
import java.util.Objects;

/**
 * Explanation of what happened to one relevant sample ID.
 *
 * @param sampleId relevant sample identifier
 * @param status diagnostic outcome
 * @param failedFilters metadata filters that excluded the sample
 * @param score cosine score when the sample was scored, otherwise {@code 0.0}
 * @param rank full scored-candidate rank when scored, otherwise {@code 0}
 */
public record SpeechRelevantCandidateDiagnostic(
        String sampleId,
        SpeechRelevantCandidateStatus status,
        List<SpeechMetadataFilter> failedFilters,
        double score,
        int rank
) {
    public SpeechRelevantCandidateDiagnostic {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        failedFilters = List.copyOf(Objects.requireNonNull(failedFilters, "failedFilters"));
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }

        boolean scored = status == SpeechRelevantCandidateStatus.RETRIEVED_AT_K
                || status == SpeechRelevantCandidateStatus.SCORED_BELOW_K;
        if (scored && rank <= 0) {
            throw new IllegalArgumentException("scored relevant candidate rank must be positive: " + rank);
        }
        if (!scored && (rank != 0 || score != 0.0)) {
            throw new IllegalArgumentException("unscored relevant candidate must have rank 0 and score 0.0");
        }
        if (status == SpeechRelevantCandidateStatus.METADATA_FILTERED && failedFilters.isEmpty()) {
            throw new IllegalArgumentException("metadata-filtered relevant candidate must have failed filters");
        }
        if (status != SpeechRelevantCandidateStatus.METADATA_FILTERED && !failedFilters.isEmpty()) {
            throw new IllegalArgumentException("only metadata-filtered relevant candidates may have failed filters");
        }
    }
}
