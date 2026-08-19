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
        if (!transcriptUnavailableSampleIds.isEmpty() && !acousticUnavailableSampleIds.isEmpty()) {
            throw new IllegalArgumentException("a robustness case may model only one unavailable modality");
        }
        if (expectedConflictCategory == SpeechHybridConflictCategory.TRANSCRIPT_MISSING
                && transcriptUnavailableSampleIds.isEmpty()) {
            throw new IllegalArgumentException("TRANSCRIPT_MISSING requires unavailable transcript candidates");
        }
        if (expectedConflictCategory == SpeechHybridConflictCategory.ACOUSTIC_MISSING
                && acousticUnavailableSampleIds.isEmpty()) {
            throw new IllegalArgumentException("ACOUSTIC_MISSING requires unavailable acoustic candidates");
        }
        if (expectedConflictCategory != SpeechHybridConflictCategory.TRANSCRIPT_MISSING
                && expectedConflictCategory != SpeechHybridConflictCategory.ACOUSTIC_MISSING
                && (!transcriptUnavailableSampleIds.isEmpty() || !acousticUnavailableSampleIds.isEmpty())) {
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
    ACOUSTIC_MISSING
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
