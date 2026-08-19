package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechHybridRobustnessRunnerTest {

    private final SpeechHybridRobustnessRunner runner = new SpeechHybridRobustnessRunner();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();

    @Test
    void riskEvidenceWinsEvenWhenCoverageIsIncomplete() {
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY,
                runner.decide(List.of(result(SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
                        SpeechHybridComparison.REGRESS, SpeechSpeakerRelation.SAME_SPEAKER,
                        SpeechCondition.CONTROL,
                        SpeechTaskType.COMMAND)), false));
    }

    @Test
    void missingCoverageIsInconclusiveWithoutObservedRisk() {
        assertEquals(SpeechHybridRobustnessDecision.INCONCLUSIVE,
                runner.decide(List.of(result(SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
                        SpeechHybridComparison.MAINTAIN, SpeechSpeakerRelation.SAME_SPEAKER,
                        SpeechCondition.CONTROL,
                        SpeechTaskType.COMMAND)), false));
    }

    @Test
    void completeCoverageWithNoRegressionIsRobustButAnyGroupedRegressionIsRisky() {
        List<SpeechHybridRobustnessQueryResult> coverage = completeCoverage();
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_ROBUST, runner.decide(coverage, false));
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY, runner.decide(coverage, true));
    }

    @Test
    void groupedRegressionBlocksTheCandidateWhenAggregateMetricsLookAcceptable() {
        List<SpeechHybridRobustnessQueryResult> results = List.of(
                resultFromRankings("control-regression", SpeechCondition.CONTROL,
                        SpeechHybridConflictCategory.AGREEMENT_RELEVANT, "target-a", "target-a", "wrong-a"),
                resultFromRankings("dysarthric-recovery", SpeechCondition.DYSARTHRIC,
                        SpeechHybridConflictCategory.BOTH_AMBIGUOUS, "wrong-b", "wrong-b", "target-b"));

        SpeechHybridRobustnessSummary aggregate = runner.summarize(results);
        Map<String, SpeechHybridRobustnessSummary> byCondition = runner.summarizeBy(
                results, result -> result.stressCase().query().condition().name());
        boolean groupRegression = runner.hasGroupRegression(aggregate, byCondition, Map.of(), Map.of(), Map.of());

        assertEquals(aggregate.transcriptMetrics().precisionAtK(), aggregate.hybridMetrics().precisionAtK());
        assertEquals(aggregate.transcriptMetrics().recallAtK(), aggregate.hybridMetrics().recallAtK());
        assertEquals(aggregate.transcriptMetrics().hitRateAtK(), aggregate.hybridMetrics().hitRateAtK());
        assertEquals(aggregate.transcriptMetrics().mrr(), aggregate.hybridMetrics().mrr());
        assertEquals(aggregate.acousticMetrics().precisionAtK(), aggregate.hybridMetrics().precisionAtK());
        assertEquals(aggregate.acousticMetrics().recallAtK(), aggregate.hybridMetrics().recallAtK());
        assertFalse(aggregate.groupRegression());
        assertTrue(byCondition.get("CONTROL").groupRegression());
        assertFalse(byCondition.get("DYSARTHRIC").groupRegression());
        assertTrue(groupRegression);
        assertEquals(SpeechHybridComparison.REGRESS, results.getFirst().vsStrongerControl());
        assertEquals(SpeechHybridComparison.WIN, results.getLast().vsStrongerControl());
        assertEquals(SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY, runner.decide(results, groupRegression));
    }

    private List<SpeechHybridRobustnessQueryResult> completeCoverage() {
        List<SpeechHybridRobustnessQueryResult> results = new ArrayList<>();
        SpeechHybridConflictCategory[] categories = SpeechHybridConflictCategory.values();
        for (int index = 0; index < categories.length; index++) {
            results.add(result(categories[index], SpeechHybridComparison.MAINTAIN,
                    index % 2 == 0 ? SpeechSpeakerRelation.SAME_SPEAKER : SpeechSpeakerRelation.CROSS_SPEAKER,
                    index % 2 == 0 ? SpeechCondition.CONTROL : SpeechCondition.DYSARTHRIC,
                    switch (index % 3) {
                        case 0 -> SpeechTaskType.COMMAND;
                        case 1 -> SpeechTaskType.SENTENCE;
                        default -> SpeechTaskType.WORD;
                    }));
        }
        return results;
    }

    private SpeechHybridRobustnessQueryResult result(
            SpeechHybridConflictCategory category,
            SpeechHybridComparison comparison,
            SpeechSpeakerRelation speakerRelation,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
        PairedSpeechQuery query = new PairedSpeechQuery(category.name(), Path.of(category.name() + ".wav"),
                "synthetic", Set.of("sample"), condition, taskType);
        SpeechHybridRobustnessCase stressCase = new SpeechHybridRobustnessCase(query, "speaker", category,
                category == SpeechHybridConflictCategory.TRANSCRIPT_MISSING ? Set.of("sample") : Set.of(),
                category == SpeechHybridConflictCategory.ACOUSTIC_MISSING ? Set.of("sample") : Set.of());
        return new SpeechHybridRobustnessQueryResult(stressCase, null, null, speakerRelation,
                SpeechHybridBetterControl.TIED, comparison);
    }

    private SpeechHybridRobustnessQueryResult resultFromRankings(
            String queryId,
            SpeechCondition condition,
            SpeechHybridConflictCategory category,
            String transcriptTopId,
            String acousticTopId,
            String hybridTopId
    ) {
        String targetId = condition == SpeechCondition.CONTROL ? "target-a" : "target-b";
        PairedSpeechQuery query = new PairedSpeechQuery(queryId, Path.of(queryId + ".wav"), "synthetic",
                Set.of(targetId), condition, SpeechTaskType.COMMAND);
        SpeechRankingEvaluator rankingEvaluator = new SpeechRankingEvaluator();
        List<SpeechModalityRankedResult> transcriptRanking = ranking(transcriptTopId);
        List<SpeechModalityRankedResult> acousticRanking = ranking(acousticTopId);
        SpeechModalityQueryResult transcript = rankingEvaluator.evaluate(transcriptRanking, query.relevantSampleIds(), 1);
        SpeechModalityQueryResult acoustic = rankingEvaluator.evaluate(acousticRanking, query.relevantSampleIds(), 1);
        SpeechHybridQueryContext context = new SpeechHybridQueryContext(
                query,
                transcriptRanking,
                acousticRanking,
                transcript,
                acoustic,
                category == SpeechHybridConflictCategory.AGREEMENT_RELEVANT
                        ? SpeechModalityOutcome.BOTH_SUCCEED : SpeechModalityOutcome.BOTH_FAIL,
                noScores(),
                noScores(),
                1,
                List.of());
        SpeechModalityQueryResult hybridResult = rankingEvaluator.evaluate(
                ranking(hybridTopId), query.relevantSampleIds(), 1);
        SpeechHybridProfileQueryResult hybrid = new SpeechHybridProfileQueryResult(
                context,
                SpeechHybridRobustnessRunner.FIXED_CANDIDATE,
                hybridResult,
                Map.of(),
                0,
                profileEvaluator.compare(hybridResult.metrics(), transcript.metrics()),
                profileEvaluator.compare(hybridResult.metrics(), acoustic.metrics()),
                category == SpeechHybridConflictCategory.AGREEMENT_RELEVANT
                        ? SpeechHybridDisagreementOutcome.LOST_BOTH_SUCCEED
                        : SpeechHybridDisagreementOutcome.RECOVERED_BOTH_MISS);
        SpeechHybridRobustnessCase stressCase = new SpeechHybridRobustnessCase(
                query, "speaker", category, Set.of(), Set.of());
        return new SpeechHybridRobustnessQueryResult(
                stressCase,
                context,
                hybrid,
                SpeechSpeakerRelation.SAME_SPEAKER,
                SpeechHybridBetterControl.TIED,
                profileEvaluator.compare(hybridResult.metrics(), transcript.metrics()));
    }

    private List<SpeechModalityRankedResult> ranking(String sampleId) {
        return List.of(new SpeechModalityRankedResult(sampleId, 1.0, 1));
    }

    private SpeechNormalizedScores noScores() {
        return new SpeechNormalizedScores(SpeechScoreNormalizationStatus.NO_AVAILABLE_SCORES, 0.0, 0.0, Map.of());
    }
}
