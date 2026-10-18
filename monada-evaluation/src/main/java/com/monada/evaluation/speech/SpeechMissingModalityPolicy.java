package com.monada.evaluation.speech;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Predeclared #128 missing-modality handling policies for the fixed 0.50/0.50 hybrid candidate.
 * The set is fixed before any fixture is evaluated; none of them is tuned on the study cases.
 */
enum SpeechMissingModalityPolicy {
    /** Reference arm: identical to the #79 fixed hybrid, partial candidates are excluded. */
    BOTH_REQUIRED,
    /** Renormalizes the fixed weights over the modalities a candidate actually has. */
    AVAILABLE_MODALITY_ONLY,
    /** Fuses only when every scoreable candidate is complete, otherwise defers or abstains. */
    ABSTAIN_ON_PARTIAL;

    String description() {
        return switch (this) {
            case BOTH_REQUIRED -> "fuse BOTH_AVAILABLE candidates only; partial candidates are excluded (#79 reference)";
            case AVAILABLE_MODALITY_ONLY -> "renormalize the fixed weights over present components; one present component"
                    + " is used alone; BOTH_MISSING is excluded";
            case ABSTAIN_ON_PARTIAL -> "fuse when every scoreable candidate is complete; otherwise defer to the complete"
                    + " modality's control ranking, or abstain with an empty ranking when neither is complete";
        };
    }
}

/** How a policy produced its ranking for one query. */
enum SpeechMissingModalityRoute {
    FUSED,
    DEFERRED_TO_TRANSCRIPT,
    DEFERRED_TO_ACOUSTIC,
    ABSTAINED
}

/** Deterministic per-query rankings for each {@link SpeechMissingModalityPolicy}; never imputes a score. */
final class SpeechMissingModalityPolicyEvaluator {

    SpeechMissingModalityPolicyRanking rank(
            SpeechMissingModalityPolicy policy,
            SpeechFusionWeights weights,
            SpeechHybridQueryContext context
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(context, "context");
        if (!weights.isHybrid()) {
            throw new IllegalArgumentException("missing-modality policies apply only to hybrid weights");
        }
        return switch (policy) {
            case BOTH_REQUIRED -> fuse(weights, context, false);
            case AVAILABLE_MODALITY_ONLY -> fuse(weights, context, true);
            case ABSTAIN_ON_PARTIAL -> abstainOnPartial(weights, context);
        };
    }

    private SpeechMissingModalityPolicyRanking abstainOnPartial(SpeechFusionWeights weights, SpeechHybridQueryContext context) {
        List<SpeechHybridCandidate> scoreable = context.candidates().stream()
                .filter(candidate -> candidate.availability() != SpeechHybridCandidateAvailability.BOTH_MISSING)
                .toList();
        if (scoreable.isEmpty()) {
            return new SpeechMissingModalityPolicyRanking(SpeechMissingModalityRoute.ABSTAINED, List.of(),
                    unrankedComponents(context));
        }
        if (scoreable.stream().allMatch(candidate -> candidate.availability() == SpeechHybridCandidateAvailability.BOTH_AVAILABLE)) {
            return fuse(weights, context, false);
        }
        boolean transcriptComplete = scoreable.stream()
                .noneMatch(candidate -> candidate.availability() == SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING);
        boolean acousticComplete = scoreable.stream()
                .noneMatch(candidate -> candidate.availability() == SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING);
        if (transcriptComplete) {
            return deferred(SpeechMissingModalityRoute.DEFERRED_TO_TRANSCRIPT, context.transcriptRanking(), context);
        }
        if (acousticComplete) {
            return deferred(SpeechMissingModalityRoute.DEFERRED_TO_ACOUSTIC, context.acousticRanking(), context);
        }
        return new SpeechMissingModalityPolicyRanking(SpeechMissingModalityRoute.ABSTAINED, List.of(),
                unrankedComponents(context));
    }

    private SpeechMissingModalityPolicyRanking deferred(
            SpeechMissingModalityRoute route,
            List<SpeechModalityRankedResult> ranking,
            SpeechHybridQueryContext context
    ) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (SpeechModalityRankedResult result : ranking) {
            scores.put(result.sampleId(), result.score());
            ranks.put(result.sampleId(), result.rank());
        }
        SpeechMissingModalityComponents used = route == SpeechMissingModalityRoute.DEFERRED_TO_TRANSCRIPT
                ? SpeechMissingModalityComponents.TRANSCRIPT_ONLY : SpeechMissingModalityComponents.ACOUSTIC_ONLY;
        Map<String, SpeechMissingModalityComponentScore> components = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            boolean ranked = scores.containsKey(candidate.sampleId());
            components.put(candidate.sampleId(), componentScore(context, candidate,
                    ranked ? used : SpeechMissingModalityComponents.NONE,
                    scores.get(candidate.sampleId()), ranks.get(candidate.sampleId())));
        }
        return new SpeechMissingModalityPolicyRanking(route, ranking, components);
    }

    private SpeechMissingModalityPolicyRanking fuse(
            SpeechFusionWeights weights,
            SpeechHybridQueryContext context,
            boolean allowPartial
    ) {
        Map<String, Double> transcriptScores = context.transcriptNormalization().normalizedScoresBySampleId();
        Map<String, Double> acousticScores = context.acousticNormalization().normalizedScoresBySampleId();
        List<SpeechModalityRankedResult> unranked = new ArrayList<>();
        Map<String, SpeechMissingModalityComponents> usedBySampleId = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            Double transcript = transcriptScores.get(candidate.sampleId());
            Double acoustic = acousticScores.get(candidate.sampleId());
            Double fused = null;
            SpeechMissingModalityComponents used = SpeechMissingModalityComponents.NONE;
            switch (candidate.availability()) {
                case BOTH_AVAILABLE -> {
                    fused = weights.transcriptWeight() * transcript + weights.acousticWeight() * acoustic;
                    used = SpeechMissingModalityComponents.TRANSCRIPT_AND_ACOUSTIC;
                }
                case ACOUSTIC_FEATURE_MISSING -> {
                    if (allowPartial) {
                        fused = transcript;
                        used = SpeechMissingModalityComponents.TRANSCRIPT_ONLY;
                    }
                }
                case TRANSCRIPT_MISSING -> {
                    if (allowPartial) {
                        fused = acoustic;
                        used = SpeechMissingModalityComponents.ACOUSTIC_ONLY;
                    }
                }
                case BOTH_MISSING -> {
                    // Never scored: a candidate without evidence receives no fabricated value.
                }
            }
            usedBySampleId.put(candidate.sampleId(), used);
            if (fused != null) unranked.add(new SpeechModalityRankedResult(candidate.sampleId(), fused, 1));
        }
        unranked.sort(Comparator.comparingDouble(SpeechModalityRankedResult::score).reversed()
                .thenComparing(SpeechModalityRankedResult::sampleId));
        List<SpeechModalityRankedResult> ranking = new ArrayList<>(unranked.size());
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < unranked.size(); index++) {
            SpeechModalityRankedResult result = unranked.get(index);
            ranking.add(new SpeechModalityRankedResult(result.sampleId(), result.score(), index + 1));
            fusedScores.put(result.sampleId(), result.score());
            ranks.put(result.sampleId(), index + 1);
        }
        Map<String, SpeechMissingModalityComponentScore> components = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            components.put(candidate.sampleId(), componentScore(context, candidate,
                    usedBySampleId.get(candidate.sampleId()), fusedScores.get(candidate.sampleId()),
                    ranks.get(candidate.sampleId())));
        }
        return new SpeechMissingModalityPolicyRanking(SpeechMissingModalityRoute.FUSED, ranking, components);
    }

    private Map<String, SpeechMissingModalityComponentScore> unrankedComponents(SpeechHybridQueryContext context) {
        Map<String, SpeechMissingModalityComponentScore> components = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            components.put(candidate.sampleId(), componentScore(context, candidate,
                    SpeechMissingModalityComponents.NONE, null, null));
        }
        return components;
    }

    private SpeechMissingModalityComponentScore componentScore(
            SpeechHybridQueryContext context,
            SpeechHybridCandidate candidate,
            SpeechMissingModalityComponents used,
            Double policyScore,
            Integer rank
    ) {
        return new SpeechMissingModalityComponentScore(candidate.sampleId(), candidate.availability(),
                context.transcriptNormalization().normalizedScoresBySampleId().get(candidate.sampleId()),
                context.acousticNormalization().normalizedScoresBySampleId().get(candidate.sampleId()),
                used, policyScore, rank);
    }
}

/** Which normalized components contributed to a candidate's policy score. */
enum SpeechMissingModalityComponents {
    TRANSCRIPT_AND_ACOUSTIC("T+A"),
    TRANSCRIPT_ONLY("T"),
    ACOUSTIC_ONLY("A"),
    NONE("-");

    private final String label;

    SpeechMissingModalityComponents(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** Per-candidate modality presence, normalized components, policy score and final rank (null when unranked). */
record SpeechMissingModalityComponentScore(
        String sampleId,
        SpeechHybridCandidateAvailability availability,
        Double normalizedTranscriptScore,
        Double normalizedAcousticScore,
        SpeechMissingModalityComponents componentsUsed,
        Double policyScore,
        Integer rank
) {
    SpeechMissingModalityComponentScore {
        Objects.requireNonNull(sampleId, "sampleId");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(componentsUsed, "componentsUsed");
        if ((policyScore == null) != (rank == null)) {
            throw new IllegalArgumentException("policyScore and rank must both be present or absent for " + sampleId);
        }
        if (policyScore == null && componentsUsed != SpeechMissingModalityComponents.NONE) {
            throw new IllegalArgumentException("unranked candidates must not report used components: " + sampleId);
        }
    }
}

/** One policy's ranking for one query plus a component breakdown for every corpus candidate. */
record SpeechMissingModalityPolicyRanking(
        SpeechMissingModalityRoute route,
        List<SpeechModalityRankedResult> ranking,
        Map<String, SpeechMissingModalityComponentScore> componentsBySampleId
) {
    SpeechMissingModalityPolicyRanking {
        Objects.requireNonNull(route, "route");
        ranking = List.copyOf(Objects.requireNonNull(ranking, "ranking"));
        componentsBySampleId = Map.copyOf(Objects.requireNonNull(componentsBySampleId, "componentsBySampleId"));
        if (route == SpeechMissingModalityRoute.ABSTAINED && !ranking.isEmpty()) {
            throw new IllegalArgumentException("an abstained policy must not return a ranking");
        }
    }
}
