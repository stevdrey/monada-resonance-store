package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRetrievalDiagnosticTest {

    @Test
    void perfectRetrievalProducesCorrectDiagnostic() {
        var eval = queryEval("cosine similarity", Set.of("ka_cosine"), List.of("ka_cosine", "ka_cache"));
        var diag = QueryRetrievalDiagnostic.compute(eval);

        assertEquals(Set.of("ka_cosine"), diag.expectedLabels());
        assertEquals(List.of("ka_cosine", "ka_cache"), diag.returnedLabels());
        assertEquals(List.of("ka_cosine"), diag.presentExpectedLabels());
        assertEquals(Set.of(), diag.missingExpectedLabels());
        assertEquals(1, diag.firstExpectedRank());
        assertTrue(diag.failureType().isEmpty());
        assertEquals("Perfect retrieval.", diag.note());
    }

    @Test
    void multiRelevantRecallGapProducesCorrectDiagnostic() {
        var eval = queryEval("index search storage", Set.of("ka_index", "ka_storage"), List.of("ka_index", "ka_cache"));
        var diag = QueryRetrievalDiagnostic.compute(eval);

        assertEquals(Set.of("ka_index", "ka_storage"), diag.expectedLabels());
        assertEquals(List.of("ka_index", "ka_cache"), diag.returnedLabels());
        assertEquals(List.of("ka_index"), diag.presentExpectedLabels());
        assertEquals(Set.of("ka_storage"), diag.missingExpectedLabels());
        assertEquals(1, diag.firstExpectedRank());
        assertEquals(RetrievalFailureType.MULTI_RELEVANT_RECALL_GAP, diag.failureType().orElseThrow());
        assertTrue(diag.note().contains("Recall gap: retrieved [ka_index], but missed [ka_storage]."));
    }

    @Test
    void missingExpectedAtomProducesCorrectDiagnostic() {
        var eval = queryEval("cosine similarity", Set.of("ka_cosine"), List.of("ka_cache", "ka_index"));
        var diag = QueryRetrievalDiagnostic.compute(eval);

        assertEquals(0, diag.firstExpectedRank());
        assertEquals(RetrievalFailureType.MISSING_EXPECTED_ATOM, diag.failureType().orElseThrow());
    }

    private static QueryEvaluation queryEval(String text, Set<String> expected, List<String> returned) {
        return new QueryEvaluation(
                text,
                expected,
                returned,
                metricByK(expected, returned, PrecisionAtK::compute),
                metricByK(expected, returned, RecallAtK::compute),
                metricByK(expected, returned, HitAtK::compute),
                ReciprocalRank.compute(expected, returned));
    }

    private static Map<Integer, Double> metricByK(Set<String> expected, List<String> returned, KMetric metric) {
        var map = new TreeMap<Integer, Double>();
        for (int k : EvaluationRunner.DEFAULT_KS) {
            map.put(k, metric.apply(expected, returned, k));
        }
        return map;
    }

    @FunctionalInterface
    private interface KMetric {
        double apply(Set<String> expected, List<String> ranked, int k);
    }
}
