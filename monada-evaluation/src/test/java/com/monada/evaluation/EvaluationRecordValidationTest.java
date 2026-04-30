package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationRecordValidationTest {

    private static final Map<Integer, Double> VALID_MAP = Map.of(1, 0.5);

    @Test
    void queryEvaluationRejectsNaNReciprocalRank() {
        assertThrows(IllegalArgumentException.class, () -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                Double.NaN));
    }

    @Test
    void queryEvaluationRejectsInfinityReciprocalRank() {
        assertThrows(IllegalArgumentException.class, () -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                Double.NEGATIVE_INFINITY));
    }

    @Test
    void queryEvaluationRejectsOutOfRangeReciprocalRank() {
        assertThrows(IllegalArgumentException.class, () -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                -0.1));
        assertThrows(IllegalArgumentException.class, () -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                1.1));
    }

    @Test
    void queryEvaluationAcceptsBoundaryValues() {
        assertDoesNotThrow(() -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                0.0));
        assertDoesNotThrow(() -> new QueryEvaluation(
                "q", Set.of("a"), List.of("a"),
                VALID_MAP, VALID_MAP, VALID_MAP,
                1.0));
    }

    @Test
    void evaluationReportRejectsNaNMrr() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, Double.NaN));
    }

    @Test
    void evaluationReportRejectsInfinityMrr() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, Double.NEGATIVE_INFINITY));
    }

    @Test
    void evaluationReportRejectsOutOfRangeMrr() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, 1.1));
    }

    @Test
    void evaluationReportAcceptsBoundaryValues() {
        assertDoesNotThrow(() -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, 0.0));
        assertDoesNotThrow(() -> new EvaluationReport(
                List.of(), VALID_MAP, VALID_MAP, VALID_MAP, 1.0));
    }
}
