package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationComparatorTest {

    @Test
    void identicalReportsAreClassifiedAsMaintained() {
        var report = report(
                queryEval("q", Set.of("a"), List.of("a", "b")),
                queryEval("r", Set.of("b"), List.of("b", "a")));
        var comparison = new EvaluationComparator().compare(report, report);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
        for (var per : comparison.perQuery()) {
            assertEquals(RankingChange.MAINTAINED, per.change());
        }
    }

    @Test
    void higherMrrInAfterIsClassifiedAsImproved() {
        var before = report(queryEval("q", Set.of("a"), List.of("b", "a")));
        var after = report(queryEval("q", Set.of("a"), List.of("a", "b")));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.IMPROVED, comparison.aggregate());
        assertEquals(RankingChange.IMPROVED, comparison.perQuery().get(0).change());
    }

    @Test
    void lowerHit1InAfterIsClassifiedAsDegraded() {
        var before = report(queryEval("q", Set.of("a"), List.of("a", "b")));
        var after = report(queryEval("q", Set.of("a"), List.of("b", "a")));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.DEGRADED, comparison.aggregate());
        assertEquals(RankingChange.DEGRADED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryReorderingThatPreservesFirstExpectedRankIsMaintained() {
        // Query has expected={a}. First expected hit is at rank 0 in both runs.
        // The non-relevant labels reorder, but the metric-relevant rank does not change.
        var before = report(queryEval("q", Set.of("a"), List.of("a", "b", "c")));
        var after = report(queryEval("q", Set.of("a"), List.of("a", "c", "b")));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
        assertEquals(RankingChange.MAINTAINED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryFirstExpectedHitMovingUpIsImproved() {
        var before = report(queryEval("q", Set.of("a"), List.of("b", "a")));
        var after = report(queryEval("q", Set.of("a"), List.of("a", "b")));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.IMPROVED, comparison.perQuery().get(0).change());
    }

    @Test
    void perQueryFirstExpectedHitMovingDownIsDegraded() {
        var before = report(queryEval("q", Set.of("a"), List.of("a", "b")));
        var after = report(queryEval("q", Set.of("a"), List.of("b", "a")));

        var comparison = new EvaluationComparator().compare(before, after);

        assertEquals(RankingChange.DEGRADED, comparison.perQuery().get(0).change());
    }

    @Test
    void differencesWithinEpsilonAreClassifiedAsMaintained() {
        // Use a deliberately large epsilon so the natural metric jumps from these
        // label-driven inputs (MRR rises by 0.5, Hit@1 by 1.0) sit within the
        // tolerance and the comparator treats the change as MAINTAINED.
        var before = report(queryEval("q", Set.of("a"), List.of("b", "a")));
        var after = report(queryEval("q", Set.of("a"), List.of("a", "b")));

        var comparison = new EvaluationComparator(2.0).compare(before, after);

        assertEquals(RankingChange.MAINTAINED, comparison.aggregate());
    }

    @Test
    void rejectsReportsWithDifferentNumberOfQueries() {
        var a = report(queryEval("q", Set.of("a"), List.of("a")));
        var b = report(
                queryEval("q", Set.of("a"), List.of("a")),
                queryEval("r", Set.of("b"), List.of("b")));

        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator().compare(a, b));
    }

    @Test
    void rejectsReportsWithDifferentQueryOrder() {
        var a = report(
                queryEval("first", Set.of("a"), List.of("a")),
                queryEval("second", Set.of("b"), List.of("b")));
        var b = report(
                queryEval("second", Set.of("b"), List.of("b")),
                queryEval("first", Set.of("a"), List.of("a")));

        assertThrows(IllegalArgumentException.class, () -> new EvaluationComparator().compare(a, b));
    }

    @Test
    void rejectsReportsWithDifferentExpectedLabelsForSameQuery() {
        var a = report(queryEval("q", Set.of("a"), List.of("a")));
        var b = report(queryEval("q", Set.of("b"), List.of("b")));

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

    /**
     * Builds a {@link QueryEvaluation} whose per-query metrics are computed from
     * {@code expected} and {@code returned} via the same helpers used by
     * {@link EvaluationRunner}, so the resulting record is internally consistent
     * with what a real run would produce (Hit@K, Recall@K, Precision@K and
     * reciprocal rank all derived from the same label inputs).
     */
    private static QueryEvaluation queryEval(String text,
                                             Set<String> expected,
                                             List<String> returned) {
        return new QueryEvaluation(
                text,
                expected,
                returned,
                metricByK(expected, returned, PrecisionAtK::compute),
                metricByK(expected, returned, RecallAtK::compute),
                metricByK(expected, returned, HitAtK::compute),
                ReciprocalRank.compute(expected, returned));
    }

    private static Map<Integer, Double> metricByK(Set<String> expected,
                                                  List<String> returned,
                                                  KMetric metric) {
        var map = new TreeMap<Integer, Double>();
        for (int k : EvaluationRunner.DEFAULT_KS) {
            map.put(k, metric.apply(expected, returned, k));
        }
        return map;
    }

    /**
     * Mirrors {@link EvaluationRunner}'s aggregation: averages each per-query
     * metric across all queries and uses the average reciprocal rank as the
     * report's {@code meanReciprocalRank}, so the resulting report is
     * internally consistent with what a full evaluation run would produce.
     */
    private static EvaluationReport report(QueryEvaluation... queries) {
        if (queries.length == 0) {
            throw new IllegalArgumentException("queries must not be empty");
        }
        int n = queries.length;
        var avgPrecision = averageByK(queries, QueryEvaluation::precisionByK);
        var avgRecall = averageByK(queries, QueryEvaluation::recallByK);
        var avgHit = averageByK(queries, QueryEvaluation::hitByK);
        double rrSum = 0.0;
        for (var q : queries) {
            rrSum += q.reciprocalRank();
        }
        return new EvaluationReport(
                List.of(queries),
                avgPrecision,
                avgRecall,
                avgHit,
                rrSum / n);
    }

    private static Map<Integer, Double> averageByK(
            QueryEvaluation[] queries,
            java.util.function.Function<QueryEvaluation, Map<Integer, Double>> extractor) {
        var sums = new TreeMap<Integer, Double>();
        for (var q : queries) {
            for (var entry : extractor.apply(q).entrySet()) {
                sums.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        var avg = new TreeMap<Integer, Double>();
        int n = queries.length;
        for (var entry : sums.entrySet()) {
            avg.put(entry.getKey(), entry.getValue() / n);
        }
        return avg;
    }

    @FunctionalInterface
    private interface KMetric {
        double apply(Set<String> expected, List<String> ranked, int k);
    }
}
