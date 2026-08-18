package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpeechHybridSweepRunnerTest {

    private final SpeechHybridSweepRunner runner = new SpeechHybridSweepRunner();
    private final SpeechScoreNormalization normalization = new SpeechScoreNormalization();
    private final SpeechRankingEvaluator evaluator = new SpeechRankingEvaluator();

    @Test
    void hybridProfilesExcludeMissingModalityCandidatesWithoutDroppingTheirRelevantJudgments() {
        SpeechHybridQueryContext context = context(
                List.of("a", "b", "c"),
                List.of(ranked("a", 0.8, 1), ranked("c", 0.3, 2)),
                List.of(ranked("b", 0.9, 1), ranked("c", 0.2, 2)),
                Set.of("b"),
                1);

        SpeechHybridProfileQueryResult result = runner.evaluateProfileQuery(
                new SpeechFusionWeights(0.5, 0.5), context);

        assertEquals(List.of("c"), result.result().topResults().stream()
                .map(SpeechModalityRankedResult::sampleId).toList());
        assertFalse(result.result().metrics().hitAtK());
        assertEquals(SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING,
                context.candidates().get(0).availability());
        assertEquals(SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING,
                context.candidates().get(1).availability());
        assertEquals(SpeechHybridDisagreementOutcome.LOST_ACOUSTIC_WIN, result.disagreementOutcome());
    }

    @Test
    void representsBothMissingCandidatesExplicitly() {
        SpeechHybridQueryContext context = context(
                List.of("a", "b", "c"),
                List.of(ranked("c", 0.3, 1)),
                List.of(ranked("c", 0.2, 1)),
                Set.of("a"),
                1);

        SpeechHybridProfileQueryResult result = runner.evaluateProfileQuery(
                new SpeechFusionWeights(0.5, 0.5), context);

        assertEquals(SpeechHybridCandidateAvailability.BOTH_MISSING,
                context.candidates().get(0).availability());
        assertFalse(result.result().metrics().hitAtK());
        assertEquals(SpeechHybridDisagreementOutcome.BOTH_MISS_RETAINED, result.disagreementOutcome());
    }

    @Test
    void hybridTieBreakingUsesSampleIdAscending() {
        SpeechHybridQueryContext context = context(
                List.of("b", "a", "c"),
                List.of(ranked("b", 1.0, 1), ranked("a", 1.0, 2), ranked("c", 0.0, 3)),
                List.of(ranked("b", 1.0, 1), ranked("a", 1.0, 2), ranked("c", 0.0, 3)),
                Set.of("a"),
                2);

        SpeechHybridProfileQueryResult result = runner.evaluateProfileQuery(
                new SpeechFusionWeights(0.5, 0.5), context);

        assertEquals(List.of("a", "b"), result.result().topResults().stream()
                .map(SpeechModalityRankedResult::sampleId).toList());
        assertEquals(1, result.tieCount());
    }

    @Test
    void controlsPreserveTheOriginalRankingOrderAndScores() {
        List<SpeechModalityRankedResult> transcript = List.of(
                ranked("b", 1.0, 1), ranked("a", 1.0, 2), ranked("c", 0.0, 3));
        SpeechHybridQueryContext context = context(
                List.of("a", "b", "c"),
                transcript,
                List.of(ranked("a", 1.0, 1), ranked("b", 0.5, 2), ranked("c", 0.0, 3)),
                Set.of("a", "b"),
                3);

        SpeechHybridProfileQueryResult result = runner.evaluateProfileQuery(
                new SpeechFusionWeights(1.0, 0.0), context);

        assertEquals(transcript, result.result().topResults());
    }

    @Test
    void decisionRequiresTwoAdjacentPromisingHybridProfiles() {
        assertEquals(SpeechHybridDecision.HYBRID_ROBUSTNESS_STUDY_JUSTIFIED, runner.decide(List.of(
                profile(new SpeechFusionWeights(1.0, 0.0), false),
                profile(new SpeechFusionWeights(0.75, 0.25), true),
                profile(new SpeechFusionWeights(0.5, 0.5), true),
                profile(new SpeechFusionWeights(0.25, 0.75), false),
                profile(new SpeechFusionWeights(0.0, 1.0), false))));
        assertEquals(SpeechHybridDecision.KEEP_SINGLE_MODALITY, runner.decide(List.of(
                profile(new SpeechFusionWeights(1.0, 0.0), false),
                profile(new SpeechFusionWeights(0.75, 0.25), false),
                profile(new SpeechFusionWeights(0.5, 0.5), false),
                profile(new SpeechFusionWeights(0.25, 0.75), false),
                profile(new SpeechFusionWeights(0.0, 1.0), false))));
        assertEquals(SpeechHybridDecision.INCONCLUSIVE, runner.decide(List.of(
                profile(new SpeechFusionWeights(1.0, 0.0), false),
                profile(new SpeechFusionWeights(0.75, 0.25), true),
                profile(new SpeechFusionWeights(0.5, 0.5), false),
                profile(new SpeechFusionWeights(0.25, 0.75), false),
                profile(new SpeechFusionWeights(0.0, 1.0), false))));
    }

    private SpeechHybridQueryContext context(
            List<String> corpusIds,
            List<SpeechModalityRankedResult> transcriptRanking,
            List<SpeechModalityRankedResult> acousticRanking,
            Set<String> relevantIds,
            int k
    ) {
        PairedSpeechQuery query = new PairedSpeechQuery(
                "synthetic", Path.of("synthetic.wav"), "synthetic transcript", relevantIds, null, null);
        SpeechModalityQueryResult transcriptControl = evaluator.evaluate(transcriptRanking, relevantIds, k);
        SpeechModalityQueryResult acousticControl = evaluator.evaluate(acousticRanking, relevantIds, k);
        Map<String, Double> transcriptScores = scoresById(transcriptRanking);
        Map<String, Double> acousticScores = scoresById(acousticRanking);
        List<SpeechHybridCandidate> candidates = new ArrayList<>();
        for (String sampleId : corpusIds.stream().sorted().toList()) {
            boolean transcriptPresent = transcriptScores.containsKey(sampleId);
            boolean acousticPresent = acousticScores.containsKey(sampleId);
            candidates.add(new SpeechHybridCandidate(
                    sampleId,
                    transcriptScores.get(sampleId),
                    acousticScores.get(sampleId),
                    availabilityFor(transcriptPresent, acousticPresent)));
        }
        return new SpeechHybridQueryContext(
                query,
                transcriptRanking,
                acousticRanking,
                transcriptControl,
                acousticControl,
                outcome(transcriptControl.metrics().hitAtK(), acousticControl.metrics().hitAtK()),
                normalization.normalize(transcriptScores),
                normalization.normalize(acousticScores),
                k,
                candidates);
    }

    private Map<String, Double> scoresById(List<SpeechModalityRankedResult> ranking) {
        return ranking.stream().collect(Collectors.toMap(
                SpeechModalityRankedResult::sampleId,
                SpeechModalityRankedResult::score));
    }

    private SpeechHybridCandidateAvailability availabilityFor(boolean transcriptPresent, boolean acousticPresent) {
        if (transcriptPresent && acousticPresent) {
            return SpeechHybridCandidateAvailability.BOTH_AVAILABLE;
        }
        if (transcriptPresent) {
            return SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING;
        }
        if (acousticPresent) {
            return SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING;
        }
        return SpeechHybridCandidateAvailability.BOTH_MISSING;
    }

    private SpeechModalityOutcome outcome(boolean transcriptHit, boolean acousticHit) {
        if (transcriptHit && acousticHit) {
            return SpeechModalityOutcome.BOTH_SUCCEED;
        }
        if (transcriptHit) {
            return SpeechModalityOutcome.TRANSCRIPT_ONLY;
        }
        if (acousticHit) {
            return SpeechModalityOutcome.ACOUSTIC_ONLY;
        }
        return SpeechModalityOutcome.BOTH_FAIL;
    }

    private SpeechModalityRankedResult ranked(String sampleId, double score, int rank) {
        return new SpeechModalityRankedResult(sampleId, score, rank);
    }

    private SpeechHybridProfileResult profile(SpeechFusionWeights weights, boolean promising) {
        SpeechEvaluationMetrics metrics = new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0);
        SpeechHybridProfileSummary summary = new SpeechHybridProfileSummary(
                metrics,
                new SpeechMetricDelta(0.0, 0.0, 0.0, 0.0),
                new SpeechMetricDelta(0.0, 0.0, 0.0, 0.0),
                comparisonCounts(),
                comparisonCounts(),
                disagreementCounts(),
                0,
                promising);
        return new SpeechHybridProfileResult(weights, List.of(), summary);
    }

    private Map<SpeechHybridComparison, Integer> comparisonCounts() {
        return new EnumMap<>(SpeechHybridComparison.class);
    }

    private Map<SpeechHybridDisagreementOutcome, Integer> disagreementCounts() {
        return new EnumMap<>(SpeechHybridDisagreementOutcome.class);
    }
}
