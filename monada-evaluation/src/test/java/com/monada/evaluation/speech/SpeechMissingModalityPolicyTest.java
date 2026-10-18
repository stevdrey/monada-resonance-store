package com.monada.evaluation.speech;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechMissingModalityPolicyTest {

    private static final SpeechFusionWeights FIXED = SpeechHybridRobustnessRunner.FIXED_CANDIDATE;

    private final SpeechMissingModalityPolicyEvaluator evaluator = new SpeechMissingModalityPolicyEvaluator();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();

    @Test
    void bothRequiredMatchesTheFixedHybridAndExcludesPartialCandidates() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                Map.of("b", 1.0, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));

        SpeechMissingModalityPolicyRanking ranking = evaluator.rank(SpeechMissingModalityPolicy.BOTH_REQUIRED, FIXED, context);

        assertEquals(SpeechMissingModalityRoute.FUSED, ranking.route());
        assertEquals(profileEvaluator.evaluateProfileQuery(FIXED, context).result().topResults(), ranking.ranking());
        assertEquals(List.of("b", "c"), ids(ranking));
        SpeechMissingModalityComponentScore partial = ranking.componentsBySampleId().get("a");
        assertEquals(SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING, partial.availability());
        assertEquals(SpeechMissingModalityComponents.NONE, partial.componentsUsed());
        assertNull(partial.policyScore());
        assertNull(partial.rank());
    }

    @Test
    void availableModalityOnlyUsesTheSinglePresentComponentWithoutImputation() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                Map.of("b", 1.0, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));

        SpeechMissingModalityPolicyRanking ranking = evaluator.rank(
                SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY, FIXED, context);

        assertEquals(List.of("a", "b", "c"), ids(ranking));
        SpeechMissingModalityComponentScore partial = ranking.componentsBySampleId().get("a");
        assertEquals(SpeechMissingModalityComponents.TRANSCRIPT_ONLY, partial.componentsUsed());
        assertNull(partial.normalizedAcousticScore());
        assertEquals(1.0, partial.policyScore());
        assertEquals(1, partial.rank());
        SpeechMissingModalityComponentScore complete = ranking.componentsBySampleId().get("b");
        assertEquals(SpeechMissingModalityComponents.TRANSCRIPT_AND_ACOUSTIC, complete.componentsUsed());
        assertEquals(0.75, complete.policyScore());
    }

    @Test
    void candidatesMissingBothModalitiesAreNeverScoredByAnyPolicy() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0, "b", 0.0),
                Map.of("a", 0.0, "b", 1.0), Set.of("d"), List.of("a", "b", "d"));

        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            SpeechMissingModalityPolicyRanking ranking = evaluator.rank(policy, FIXED, context);
            SpeechMissingModalityComponentScore missing = ranking.componentsBySampleId().get("d");
            assertEquals(SpeechHybridCandidateAvailability.BOTH_MISSING, missing.availability(), policy.name());
            assertEquals(SpeechMissingModalityComponents.NONE, missing.componentsUsed(), policy.name());
            assertNull(missing.policyScore(), policy.name());
            assertFalse(ids(ranking).contains("d"), policy.name());
        }
        assertEquals(SpeechMissingModalityRoute.FUSED,
                evaluator.rank(SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL, FIXED, context).route());
    }

    @Test
    void abstainOnPartialDefersToTheModalityThatCoversEveryScoreableCandidate() {
        SpeechHybridQueryContext transcriptComplete = context(Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                Map.of("b", 1.0, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));
        SpeechHybridQueryContext acousticComplete = context(Map.of("b", 1.0, "c", 0.0),
                Map.of("a", 1.0, "b", 0.5, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));

        SpeechMissingModalityPolicyRanking toTranscript = evaluator.rank(
                SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL, FIXED, transcriptComplete);
        SpeechMissingModalityPolicyRanking toAcoustic = evaluator.rank(
                SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL, FIXED, acousticComplete);

        assertEquals(SpeechMissingModalityRoute.DEFERRED_TO_TRANSCRIPT, toTranscript.route());
        assertEquals(transcriptComplete.transcriptRanking(), toTranscript.ranking());
        assertEquals(SpeechMissingModalityRoute.DEFERRED_TO_ACOUSTIC, toAcoustic.route());
        assertEquals(acousticComplete.acousticRanking(), toAcoustic.ranking());
        assertEquals(SpeechMissingModalityComponents.ACOUSTIC_ONLY,
                toAcoustic.componentsBySampleId().get("a").componentsUsed());
        for (SpeechMissingModalityComponentScore score : toTranscript.componentsBySampleId().values()) {
            assertEquals(score.normalizedTranscriptScore(), score.policyScore(), score.sampleId());
        }
    }

    @Test
    void abstainOnPartialAbstainsExplicitlyWhenNoModalityIsComplete() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0, "b", 0.0),
                Map.of("b", 1.0, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));

        SpeechMissingModalityPolicyRanking ranking = evaluator.rank(
                SpeechMissingModalityPolicy.ABSTAIN_ON_PARTIAL, FIXED, context);

        assertEquals(SpeechMissingModalityRoute.ABSTAINED, ranking.route());
        assertTrue(ranking.ranking().isEmpty());
        assertTrue(ranking.componentsBySampleId().values().stream().allMatch(score -> score.policyScore() == null));
        assertFalse(new SpeechRankingEvaluator().evaluate(ranking.ranking(), Set.of("a"), 1).metrics().hitAtK());
    }

    @Test
    void allEqualScoresCarryNoDiscriminationAndTiesBreakBySampleId() {
        SpeechHybridQueryContext constant = context(Map.of("b", 0.4, "a", 0.4),
                Map.of("b", 0.9, "a", 0.9), Set.of("b"), List.of("a", "b"));
        SpeechHybridQueryContext crossed = context(Map.of("a", 1.0, "b", 0.0),
                Map.of("a", 0.0, "b", 1.0), Set.of("b"), List.of("a", "b"));

        assertEquals(SpeechScoreNormalizationStatus.CONSTANT_NO_DISCRIMINATION, constant.transcriptNormalization().status());
        assertEquals(SpeechScoreNormalizationStatus.CONSTANT_NO_DISCRIMINATION, constant.acousticNormalization().status());
        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            SpeechMissingModalityPolicyRanking flat = evaluator.rank(policy, FIXED, constant);
            SpeechMissingModalityPolicyRanking tied = evaluator.rank(policy, FIXED, crossed);
            assertEquals(List.of("a", "b"), ids(flat), policy.name());
            assertEquals(0.0, flat.ranking().getFirst().score(), policy.name());
            assertEquals(List.of("a", "b"), ids(tied), policy.name());
            assertEquals(tied.ranking().get(0).score(), tied.ranking().get(1).score(), policy.name());
        }
    }

    @Test
    void policiesApplyOnlyToHybridWeights() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0), Map.of("a", 1.0), Set.of("a"), List.of("a"));

        assertThrows(IllegalArgumentException.class, () -> evaluator.rank(
                SpeechMissingModalityPolicy.AVAILABLE_MODALITY_ONLY, new SpeechFusionWeights(1.0, 0.0), context));
    }

    @Test
    void rankingIsDeterministicAcrossRepeatedEvaluation() {
        SpeechHybridQueryContext context = context(Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                Map.of("b", 1.0, "c", 0.0), Set.of("a"), List.of("a", "b", "c"));

        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            assertEquals(evaluator.rank(policy, FIXED, context), evaluator.rank(policy, FIXED, context), policy.name());
        }
    }

    private SpeechHybridQueryContext context(
            Map<String, Double> transcriptScores,
            Map<String, Double> acousticScores,
            Set<String> relevantIds,
            List<String> corpusIds
    ) {
        PairedSpeechQuery query = new PairedSpeechQuery("synthetic", Path.of("synthetic.wav"), "synthetic",
                relevantIds, null, null);
        return profileEvaluator.createContext(new SpeechPairedRanking(query, ranking(transcriptScores),
                ranking(acousticScores)), corpusIds, corpusIds.size());
    }

    private List<SpeechModalityRankedResult> ranking(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> ordered = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<SpeechModalityRankedResult> ranking = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ranking.add(new SpeechModalityRankedResult(ordered.get(index).getKey(), ordered.get(index).getValue(), index + 1));
        }
        return ranking;
    }

    private List<String> ids(SpeechMissingModalityPolicyRanking ranking) {
        return ranking.ranking().stream().map(SpeechModalityRankedResult::sampleId).toList();
    }
}
