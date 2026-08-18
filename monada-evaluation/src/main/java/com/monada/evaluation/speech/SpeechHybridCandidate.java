package com.monada.evaluation.speech;

import java.util.Objects;

/** One corpus candidate aligned by stable sample ID before evaluation-only score fusion. */
record SpeechHybridCandidate(
        String sampleId,
        Double transcriptRawScore,
        Double acousticRawScore,
        SpeechHybridCandidateAvailability availability
) {
    SpeechHybridCandidate {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        validateScore(transcriptRawScore, "transcriptRawScore");
        validateScore(acousticRawScore, "acousticRawScore");
        Objects.requireNonNull(availability, "availability");
        if (availability != availabilityFor(transcriptRawScore, acousticRawScore)) {
            throw new IllegalArgumentException("availability must match modality score presence for sampleId=" + sampleId);
        }
    }

    private static void validateScore(Double score, String name) {
        if (score != null && !Double.isFinite(score)) {
            throw new IllegalArgumentException(name + " must be finite when present");
        }
    }

    private static SpeechHybridCandidateAvailability availabilityFor(Double transcriptScore, Double acousticScore) {
        if (transcriptScore != null && acousticScore != null) {
            return SpeechHybridCandidateAvailability.BOTH_AVAILABLE;
        }
        if (transcriptScore != null) {
            return SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING;
        }
        if (acousticScore != null) {
            return SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING;
        }
        return SpeechHybridCandidateAvailability.BOTH_MISSING;
    }
}

enum SpeechHybridCandidateAvailability {
    BOTH_AVAILABLE,
    TRANSCRIPT_MISSING,
    ACOUSTIC_FEATURE_MISSING,
    BOTH_MISSING
}
