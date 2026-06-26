package com.monada.speech.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechBenchmarkBaselineTest {

    private static SpeechEvaluationReport report(
            int queryCount, double precision, double recall, double hit, double mrr) {
        return new SpeechEvaluationReport(
                queryCount, precision, recall, hit, mrr, List.of(), Map.of(), Map.of());
    }

    private static SpeechBenchmarkBaseline baseline() {
        return new SpeechBenchmarkBaseline(3, 2, 2, 0.5, 1.0, 1.0, 1.0, 1e-9);
    }

    @Test
    void passesWhenAllMetricsMatchWithinTolerance() {
        var comparison = baseline().compare(report(2, 0.5, 1.0, 1.0, 1.0), 3, 2);
        assertTrue(comparison.passed());
        assertTrue(comparison.mismatches().isEmpty());
    }

    @Test
    void passesWhenMetricsDifferBelowTolerance() {
        var loose = new SpeechBenchmarkBaseline(3, 2, 2, 0.5, 1.0, 1.0, 1.0, 1e-3);
        var comparison = loose.compare(report(2, 0.5004, 0.9997, 1.0, 1.0), 3, 2);
        assertTrue(comparison.passed(), comparison.mismatches().toString());
    }

    @Test
    void failsOnMetricMismatchAndListsIt() {
        var comparison = baseline().compare(report(2, 0.25, 1.0, 1.0, 1.0), 3, 2);
        assertFalse(comparison.passed());
        assertEquals(1, comparison.mismatches().size());
        assertTrue(comparison.mismatches().get(0).contains("precisionAtK"),
                comparison.mismatches().toString());
    }

    @Test
    void failsOnCorpusSizeMismatch() {
        var comparison = baseline().compare(report(2, 0.5, 1.0, 1.0, 1.0), 99, 2);
        assertFalse(comparison.passed());
        assertTrue(comparison.mismatches().get(0).contains("corpusSize"),
                comparison.mismatches().toString());
    }

    @Test
    void failsOnQueryCountMismatch() {
        var comparison = baseline().compare(report(5, 0.5, 1.0, 1.0, 1.0), 3, 2);
        assertFalse(comparison.passed());
        assertTrue(comparison.mismatches().stream().anyMatch(m -> m.contains("queryCount")),
                comparison.mismatches().toString());
    }

    @Test
    void collectsMultipleMismatches() {
        var comparison = baseline().compare(report(2, 0.1, 0.2, 0.3, 0.4), 3, 2);
        assertFalse(comparison.passed());
        assertEquals(4, comparison.mismatches().size(), comparison.mismatches().toString());
    }

    @Test
    void failsOnKMismatch() {
        var comparison = baseline().compare(report(2, 0.5, 1.0, 1.0, 1.0), 3, 5);
        assertFalse(comparison.passed());
        assertTrue(comparison.mismatches().stream().anyMatch(m -> m.contains("k")),
                comparison.mismatches().toString());
    }

    @Test
    void rejectsNegativeTolerance() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechBenchmarkBaseline(3, 2, 2, 0.5, 1.0, 1.0, 1.0, -1e-9));
    }

    @Test
    void rejectsNonPositiveK() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechBenchmarkBaseline(3, 2, 0, 0.5, 1.0, 1.0, 1.0, 1e-9));
    }
}
