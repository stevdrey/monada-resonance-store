package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeechHybridRobustnessReportTest {

    @Test
    void reportRejectsInvalidRequiredInputs() {
        assertThrows(IllegalArgumentException.class, () -> report(" ", 1, 1, List.of(queryResult()), aggregate(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> report("generated", 0, 1, List.of(queryResult()), aggregate(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> report("generated", 1, 0, List.of(queryResult()), aggregate(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> report("generated", 1, 1, List.of(), aggregate(), Map.of()));
        assertThrows(NullPointerException.class, () -> new SpeechHybridRobustnessReport(
                null, "generated", 1, 1, new SpeechFusionWeights(0.5, 0.5), List.of(queryResult()),
                aggregate(), Map.of(), Map.of(), Map.of(), Map.of(), null,
                SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY));
        assertThrows(NullPointerException.class, () -> new SpeechHybridRobustnessReport(
                SpeechModalityEvidence.GENERATED_CI, "generated", 1, 1, new SpeechFusionWeights(0.5, 0.5),
                List.of(queryResult()), null, Map.of(), Map.of(), Map.of(), Map.of(), null,
                SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY));
        assertThrows(NullPointerException.class, () -> new SpeechHybridRobustnessReport(
                SpeechModalityEvidence.GENERATED_CI, "generated", 1, 1, new SpeechFusionWeights(0.5, 0.5),
                List.of(queryResult()), aggregate(), null, Map.of(), Map.of(), Map.of(), null,
                SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY));
    }

    @Test
    void reportAndSummaryDefensivelyCopyTheirMaps() {
        Map<String, SpeechHybridRobustnessSummary> groups = new HashMap<>();
        groups.put("CONTROL", aggregate());
        SpeechHybridRobustnessReport report = report("generated", 1, 1, List.of(queryResult()), aggregate(), groups);
        groups.put("DYSARTHRIC", aggregate());
        assertEquals(Map.of("CONTROL", aggregate()), report.byCondition());
        assertThrows(UnsupportedOperationException.class, () -> report.byCondition().put("DYSARTHRIC", aggregate()));

        Map<SpeechHybridComparison, Integer> counts = new EnumMap<>(SpeechHybridComparison.class);
        counts.put(SpeechHybridComparison.WIN, 2);
        SpeechHybridRobustnessSummary summary = new SpeechHybridRobustnessSummary(
                metrics(2), metrics(2), metrics(2), counts, false);
        counts.put(SpeechHybridComparison.REGRESS, 3);
        assertEquals(2, summary.strongerControlComparisonCounts().get(SpeechHybridComparison.WIN));
        assertEquals(0, summary.strongerControlComparisonCounts().get(SpeechHybridComparison.MAINTAIN));
        assertEquals(0, summary.strongerControlComparisonCounts().get(SpeechHybridComparison.REGRESS));
        assertThrows(UnsupportedOperationException.class,
                () -> summary.strongerControlComparisonCounts().put(SpeechHybridComparison.REGRESS, 1));
    }

    @Test
    void summaryRejectsNegativeComparisonCounts() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechHybridRobustnessSummary(
                metrics(1), metrics(1), metrics(1), Map.of(SpeechHybridComparison.WIN, -1), false));
    }

    private SpeechHybridRobustnessReport report(
            String label,
            int corpusSize,
            int k,
            List<SpeechHybridRobustnessQueryResult> queryResults,
            SpeechHybridRobustnessSummary aggregate,
            Map<String, SpeechHybridRobustnessSummary> byCondition
    ) {
        return new SpeechHybridRobustnessReport(
                SpeechModalityEvidence.GENERATED_CI, label, corpusSize, k, new SpeechFusionWeights(0.5, 0.5),
                queryResults, aggregate, byCondition, Map.of(), Map.of(), Map.of(), null,
                SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY);
    }

    private SpeechHybridRobustnessSummary aggregate() {
        return new SpeechHybridRobustnessSummary(metrics(1), metrics(1), metrics(1), Map.of(), false);
    }

    private SpeechEvaluationMetrics metrics(int queryCount) {
        return new SpeechEvaluationMetrics(1.0, 1.0, 1.0, 1.0, queryCount);
    }

    private SpeechHybridRobustnessQueryResult queryResult() {
        return new SpeechHybridRobustnessQueryResult(null, null, null, null, null, null);
    }
}
