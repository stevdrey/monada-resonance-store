package com.monada.evaluation.speech;

import java.util.Objects;
import java.util.Set;

/** One generated, metadata-labelled stress case for the fixed hybrid candidate. */
record SpeechHybridRobustnessCase(
        PairedSpeechQuery query,
        String querySpeakerId,
        SpeechHybridConflictCategory expectedConflictCategory,
        Set<String> transcriptUnavailableSampleIds,
        Set<String> acousticUnavailableSampleIds
) {
    SpeechHybridRobustnessCase {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(querySpeakerId, "querySpeakerId");
        if (querySpeakerId.isBlank()) {
            throw new IllegalArgumentException("querySpeakerId must not be blank");
        }
        Objects.requireNonNull(expectedConflictCategory, "expectedConflictCategory");
        transcriptUnavailableSampleIds = immutableIds(transcriptUnavailableSampleIds, "transcriptUnavailableSampleIds");
        acousticUnavailableSampleIds = immutableIds(acousticUnavailableSampleIds, "acousticUnavailableSampleIds");
        validateMasks(query, expectedConflictCategory, transcriptUnavailableSampleIds, acousticUnavailableSampleIds);
    }

    private static void validateMasks(
            PairedSpeechQuery query,
            SpeechHybridConflictCategory category,
            Set<String> transcriptMasked,
            Set<String> acousticMasked
    ) {
        if (category == SpeechHybridConflictCategory.BOTH_MISSING) {
            if (transcriptMasked.isEmpty() || acousticMasked.isEmpty()) {
                throw new IllegalArgumentException("BOTH_MISSING requires unavailable transcript and acoustic candidates");
            }
            return;
        }
        if (!transcriptMasked.isEmpty() && !acousticMasked.isEmpty()) {
            throw new IllegalArgumentException("a robustness case may model only one unavailable modality");
        }
        if (category == SpeechHybridConflictCategory.TRANSCRIPT_MISSING
                && transcriptMasked.isEmpty()) {
            throw new IllegalArgumentException("TRANSCRIPT_MISSING requires unavailable transcript candidates");
        }
        if (category == SpeechHybridConflictCategory.ACOUSTIC_MISSING
                && acousticMasked.isEmpty()) {
            throw new IllegalArgumentException("ACOUSTIC_MISSING requires unavailable acoustic candidates");
        }
        if (category == SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR
                && transcriptMasked.isEmpty()) {
            throw new IllegalArgumentException(
                    "TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR requires a distractor without a transcript");
        }
        if (category == SpeechHybridConflictCategory.ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR
                && acousticMasked.isEmpty()) {
            throw new IllegalArgumentException(
                    "ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR requires a distractor without an acoustic feature");
        }
        if (category.isPartialDistractor()
                && (transcriptMasked.stream().anyMatch(query.relevantSampleIds()::contains)
                || acousticMasked.stream().anyMatch(query.relevantSampleIds()::contains))) {
            throw new IllegalArgumentException("partial-distractor cases may mask only non-relevant candidates");
        }
        if (!category.masksCandidates() && (!transcriptMasked.isEmpty() || !acousticMasked.isEmpty())) {
            throw new IllegalArgumentException("only missing-modality categories may mask candidates");
        }
    }

    private Set<String> immutableIds(Set<String> ids, String name) {
        Objects.requireNonNull(ids, name);
        Set<String> copy = Set.copyOf(ids);
        for (String id : copy) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(name + " must contain only non-blank IDs");
            }
        }
        return copy;
    }
}

enum SpeechHybridConflictCategory {
    AGREEMENT_RELEVANT,
    TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT,
    ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT,
    BOTH_AMBIGUOUS,
    TRANSCRIPT_MISSING,
    ACOUSTIC_MISSING,
    BOTH_MISSING,
    TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR,
    ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR;

    boolean isPartialDistractor() {
        return this == TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR || this == ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR;
    }

    boolean masksCandidates() {
        return this == TRANSCRIPT_MISSING || this == ACOUSTIC_MISSING || this == BOTH_MISSING
                || isPartialDistractor();
    }
}

enum SpeechSpeakerRelation {
    SAME_SPEAKER,
    CROSS_SPEAKER,
    MIXED_SPEAKERS,
    UNKNOWN
}

enum SpeechHybridBetterControl {
    TRANSCRIPT,
    ACOUSTIC,
    TIED
}

enum SpeechHybridRobustnessDecision {
    HYBRID_CANDIDATE_ROBUST,
    HYBRID_CANDIDATE_RISKY,
    INCONCLUSIVE
}
