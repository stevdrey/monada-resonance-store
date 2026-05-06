package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationComparatorTest {

    @Test
    void identicalReportsAreClassifiedAsMaintained() {
        var report = report(
                queryEval("q", Set.of("a"), List.of("a", "b"), 1.0, 1.0, 1.0),
                queryEval("r", Set.of("b"), List.of("b", "a"), 1.0, 1.0, 1.0));
        var comparison = new EvaluationComparator().compare(report, report);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
        for (var per : comparison.perQuery()) {
            assertEquals(RankingChange.MAINTAINED, per.change());
        }
    }

    @Test
    void higherMrrInAfterIsClassifiedAsImproved() {
        var before = report(0.5,
                queryEval("q", Set.of("a"), List.of("b", "a"), 0.0, 0.5, 0.5));
        var after = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "b"), 1.0, 1.0, 1.0));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.IMPROVED, comparison.aggregate());
        assertEquals(RankingChange.IMPROVED, comparison.perQuery().get(0).change());
    }

    @Test
    void lowerHit1InAfterIsClassifiedAsDegraded() {
        var before = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "b"), 1.0, 1.0, 1.0));
        var after = report(0.5,
                queryEval("q", Set.of("a"), List.of("b", "a"), 0.0, 0.5, 0.5));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.DEGRADED, comparison.aggregate());
        assertEquals(RankingChange.DEGRADED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryReorderingThatPreservesFirstExpectedRankIsMaintained() {
        // Query has expected={a}. First expected hit is at rank 0 in both runs.
        // The non-relevant labels reorder, but the metric-relevant rank does not change.
        var before = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "b", "c"), 1.0, 1.0, 1.0));
        var after = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "c", "b"), 1.0, 1.0, 1.0));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
        assertEquals(RankingChange.MAINTAINED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryFirstExpectedHitMovingUpIsImproved() {
        var before = report(0.5,
                queryEval("q", Set.of("a"), List.of("b", "a"), 0.0, 0.5, 0.5));
        var after = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "b"), 1.0, 1.0, 1.0));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.IMPROVED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryFirstExpectedHitMovingDownIsDegraded() {
        var before = report(1.0,
                queryEval("q", Set.of("a"), List.of("a", "b"), 1.0, 1.0, 1.0));
        var after = report(0.5,
                queryEval("q", Set.of("a"), List.of("b", "a"), 0.0, 0.5, 0.5));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.DEGRADED, comparison.perQuery().get(0).change());
    }

    @Test
    void differencesWithinEpsilonAreClassifiedAsMaintained() {
        var before = report(0.5,
                queryEval("q", Set.of("a"), List.of("a", "b"), 0.5, 0.5, 0.5));
        var after = report(0.5 + 1e-13,
                queryEval("q", Set.of("a"), List.of("a", "b"), 0.5, 0.5, 0.5));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
    }

    @Test
    void rejectsReportsWithDifferentNumberOfQueries() {
        var a = report(queryEval("q", Set.of("a"), List.of("a"), 1.0, 1.0, 1.0));
        var b = report(
                queryEval("q", Set.of("a"), List.of("a"), 1.0, 1.0, 1.0),
                queryEval("r", Set.of("b"), List.of("b"), 1.0, 1.0, 1.0));

        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator().compare(a, b));
    }

    @Test
    void rejectsReportsWithDifferentQueryOrder() {
        var a = report(
                queryEval("first", Set.of("a"), List.of("a"), 1.0, 1.0, 1.0),
                queryEval("second", Set.of("b"), List.of("b"), 1.0, 1.0, 1.0));
        var b = report(
                queryEval("second", Set.of("b"), List.of("b"), 1.0, 1.0, 1.0),
                queryEval("first", Set.of("a"), List.of("a"), 1.0, 1.0, 1.0));

        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator().compare(a, b));
    }

    @Test
    void rejectsReportsWithDifferentExpectedLabelsForSameQuery() {
        var a = report(queryEval("q", Set.of("a"), List.of("a"), 1.0, 1.0, 1.0));
        var b = report(queryEval("q", Set.of("b"), List.of("b"), 1.0, 1.0, 1.0));

        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator().compare(a, b));
    }

    @Test
    void rejectsNegativeOrNonFiniteEpsilon() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator(-0.0001));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationComparator(Double.POSITIVE_INFINITY));
    }

    // ----- helpers -----

    private static QueryEvaluation queryEval(String text,
                                             Set<String> expected,
                                             List<String> returned,
                                             double hit1,
                                             double recall3,
                                             double recall5) {
        return new QueryEvaluation(
                text,
                expected,
                returned,
                Map.of(1, hit1, 3, hit1, 5, hit1),
                Map.of(1, hit1, 3, recall3, 5, recall5),
                Map.of(1, hit1, 3, hit1, 5, hit1),
                hit1);
    }

    private static EvaluationReport report(QueryEvaluation... queries) {
        double mrr = 0.0;
        for (QueryEvaluation q : queries) {
            mrr += q.reciprocalRank();
        }
        mrr = queries.length == 0 ? 0.0 : mrr / queries.length;
        return reportWithMrr(mrr, queries);
    }

    private static EvaluationReport report(double mrr, QueryEvaluation... queries) {
        return reportWithMrr(mrr, queries);
    }

    private static EvaluationReport reportWithMrr(double mrr, QueryEvaluation... queries) {
        // Average each metric using the same logic the runner uses, so the report's
        // aggregate maps stay consistent with the per-query maps regardless of how
        // the test constructs them.
        double avgHit1 = average(queries, q -> q.hitByK().get(1));
        double avgHit3 = average(queries, q -> q.hitByK().get(3));
        double avgHit5 = average(queries, q -> q.hitByK().get(5));
        double avgRecall1 = average(queries, q -> q.recallByK().get(1));
        double avgRecall3 = average(queries, q -> q.recallByK().get(3));
        double avgRecall5 = average(queries, q -> q.recallByK().get(5));
        double avgPrecision1 = average(queries, q -> q.precisionByK().get(1));
        double avgPrecision3 = average(queries, q -> q.precisionByK().get(3));
        double avgPrecision5 = average(queries, q -> q.precisionByK().get(5));

        return new EvaluationReport(
                List.of(queries),
                Map.of(1, avgPrecision1, 3, avgPrecision3, 5, avgPrecision5),
                Map.of(1, avgRecall1, 3, avgRecall3, 5, avgRecall5),
                Map.of(1, avgHit1, 3, avgHit3, 5, avgHit5),
                mrr);
    }

    private static double average(QueryEvaluation[] queries,
                                  java.util.function.ToDoubleFunction<QueryEvaluation> f) {
        if (queries.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (QueryEvaluation q : queries) {
            sum += f.applyAsDouble(q);
        }
        return sum / queries.length;
    }
}
