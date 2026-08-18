package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeechModalityComparisonRunnerTest {

    private final SpeechModalityComparisonRunner runner = new SpeechModalityComparisonRunner();

    @Test
    void classifiesEveryPairOfModalityHits() {
        assertEquals(SpeechModalityOutcome.BOTH_SUCCEED, runner.classify(true, true));
        assertEquals(SpeechModalityOutcome.TRANSCRIPT_ONLY, runner.classify(true, false));
        assertEquals(SpeechModalityOutcome.ACOUSTIC_ONLY, runner.classify(false, true));
        assertEquals(SpeechModalityOutcome.BOTH_FAIL, runner.classify(false, false));
    }

    @Test
    void computesTopKJaccardIncludingEmptyRankings() {
        assertEquals(1.0, runner.topKJaccard(List.of(), List.of()));
        assertEquals(1.0, runner.topKJaccard(List.of(result("a")), List.of(result("a"))));
        assertEquals(1.0 / 3.0, runner.topKJaccard(
                List.of(result("a"), result("b")),
                List.of(result("b"), result("c"))));
        assertEquals(0.0, runner.topKJaccard(List.of(result("a")), List.of(result("b"))));
    }

    @Test
    void decisionRequiresOneExclusiveWinForEachModality() {
        assertEquals(
                SpeechModalityDecision.HYBRID_EXPERIMENT_JUSTIFIED,
                runner.decide(summary(Map.of(
                        SpeechModalityOutcome.TRANSCRIPT_ONLY, 1,
                        SpeechModalityOutcome.ACOUSTIC_ONLY, 1))));
        assertEquals(
                SpeechModalityDecision.NO_COMPLEMENTARITY_OBSERVED,
                runner.decide(summary(Map.of(SpeechModalityOutcome.BOTH_SUCCEED, 2))));
        assertEquals(
                SpeechModalityDecision.INCONCLUSIVE,
                runner.decide(summary(Map.of(SpeechModalityOutcome.ACOUSTIC_ONLY, 1))));
    }

    @Test
    void resultModelsRejectInconsistentMetrics() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechModalityQueryMetrics(
                1, 0, true, 0.0, 0.0, 0.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SpeechModalityQueryMetrics(
                1, 1, false, 1.0, 1.0, 1.0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SpeechModalityQueryResult(
                List.of(result("a")),
                new SpeechModalityQueryMetrics(0, 0, false, 0.0, 0.0, 0.0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SpeechModalitySummary(
                metrics(1), metrics(1), Map.of(SpeechModalityOutcome.BOTH_FAIL, 2), 1.0, 0.0));
    }

    private SpeechModalityRankedResult result(String sampleId) {
        return new SpeechModalityRankedResult(sampleId, 1.0, 1);
    }

    private SpeechModalitySummary summary(Map<SpeechModalityOutcome, Integer> counts) {
        int queryCount = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new SpeechModalitySummary(metrics(queryCount), metrics(queryCount), counts, 0.0, 0.0);
    }

    private SpeechEvaluationMetrics metrics(int queryCount) {
        return new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, queryCount);
    }
}
