package com.monada.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two {@link EvaluationReport} runs and classifies the outcome.
 *
 * <p>Aggregate classification is driven by four protected metrics:
 * {@code Hit@1}, {@code Recall@3}, {@code Recall@5}, and Mean Reciprocal Rank.
 * The comparator returns {@link RankingChange#DEGRADED} if any of those metrics
 * dropped beyond {@code epsilon}, {@link RankingChange#IMPROVED} if any rose
 * beyond {@code epsilon} and none dropped, and {@link RankingChange#MAINTAINED}
 * otherwise.
 *
 * <p>Per-query classification compares the rank of the first expected label
 * present in {@code returnedLabels}: a smaller rank in {@code after} is
 * {@link RankingChange#IMPROVED}, a larger rank is
 * {@link RankingChange#DEGRADED}. Identical returned labels are
 * {@link RankingChange#MAINTAINED}.
 *
 * <p>The comparator requires both reports to be produced over the same dataset
 * (same query order and same expected labels per query); otherwise it throws
 * {@link IllegalArgumentException}.
 */
public final class EvaluationComparator {

    public static final double DEFAULT_EPSILON = 1e-12;

    private static final List<Integer> REQUIRED_KS = List.of(1, 3, 5);

    private final double epsilon;

    public EvaluationComparator() {
        this(DEFAULT_EPSILON);
    }

    public EvaluationComparator(double epsilon) {
        if (!Double.isFinite(epsilon) || epsilon < 0.0) {
            throw new IllegalArgumentException(
                    "epsilon must be finite and non-negative, got: " + epsilon);
        }
        this.epsilon = epsilon;
    }

    public EvaluationComparison compare(EvaluationReport before, EvaluationReport after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.queryResults().size() != after.queryResults().size()) {
            throw new IllegalArgumentException(
                    "reports must contain the same number of queries; before="
                            + before.queryResults().size() + ", after=" + after.queryResults().size());
        }

        var perQuery = new ArrayList<QueryRankingComparison>(before.queryResults().size());
        for (int i = 0; i < before.queryResults().size(); i++) {
            QueryEvaluation b = before.queryResults().get(i);
            QueryEvaluation a = after.queryResults().get(i);
            if (!Objects.equals(b.queryText(), a.queryText())) {
                throw new IllegalArgumentException(
                        "query order differs at index " + i + ": '" + b.queryText()
                                + "' vs '" + a.queryText() + "'");
            }
            if (!b.expectedLabels().equals(a.expectedLabels())) {
                throw new IllegalArgumentException(
                        "expectedLabels differ for query: '" + b.queryText() + "'");
            }
            perQuery.add(new QueryRankingComparison(
                    b.queryText(),
                    b.returnedLabels(),
                    a.returnedLabels(),
                    classifyQuery(b, a)));
        }

        return new EvaluationComparison(before, after, perQuery, classifyAggregate(before, after));
    }

    private RankingChange classifyQuery(QueryEvaluation before, QueryEvaluation after) {
        if (before.returnedLabels().equals(after.returnedLabels())) {
            return RankingChange.MAINTAINED;
        }
        int beforeRank = firstExpectedRank(before.expectedLabels(), before.returnedLabels());
        int afterRank = firstExpectedRank(after.expectedLabels(), after.returnedLabels());
        if (afterRank < beforeRank) {
            return RankingChange.IMPROVED;
        }
        if (afterRank > beforeRank) {
            return RankingChange.DEGRADED;
        }
        return RankingChange.MAINTAINED;
    }

    private static int firstExpectedRank(Set<String> expected, List<String> returned) {
        for (int i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private RankingChange classifyAggregate(EvaluationReport before, EvaluationReport after) {
        boolean anyImproved = false;
        boolean anyDegraded = false;

        int mrrCmp = compareDouble(before.meanReciprocalRank(), after.meanReciprocalRank());
        if (mrrCmp > 0) {
            anyImproved = true;
        } else if (mrrCmp < 0) {
            anyDegraded = true;
        }

        int hit1Cmp = compareMetric(before.averageHitByK(), after.averageHitByK(), 1, "Hit@1");
        if (hit1Cmp > 0) {
            anyImproved = true;
        } else if (hit1Cmp < 0) {
            anyDegraded = true;
        }

        int recall3Cmp = compareMetric(before.averageRecallByK(), after.averageRecallByK(), 3, "Recall@3");
        if (recall3Cmp > 0) {
            anyImproved = true;
        } else if (recall3Cmp < 0) {
            anyDegraded = true;
        }

        int recall5Cmp = compareMetric(before.averageRecallByK(), after.averageRecallByK(), 5, "Recall@5");
        if (recall5Cmp > 0) {
            anyImproved = true;
        } else if (recall5Cmp < 0) {
            anyDegraded = true;
        }

        // Sanity-check that all required Ks exist in both reports for both metrics.
        for (int k : REQUIRED_KS) {
            requireMetric(before.averageHitByK(), k, "before.averageHitByK");
            requireMetric(after.averageHitByK(), k, "after.averageHitByK");
            requireMetric(before.averageRecallByK(), k, "before.averageRecallByK");
            requireMetric(after.averageRecallByK(), k, "after.averageRecallByK");
        }

        if (anyDegraded) {
            return RankingChange.DEGRADED;
        }
        if (anyImproved) {
            return RankingChange.IMPROVED;
        }
        return RankingChange.MAINTAINED;
    }

    private int compareMetric(Map<Integer, Double> beforeMetric,
                              Map<Integer, Double> afterMetric,
                              int k,
                              String label) {
        Double b = beforeMetric.get(k);
        Double a = afterMetric.get(k);
        if (b == null || a == null) {
            throw new IllegalArgumentException("missing required metric " + label);
        }
        return compareDouble(b, a);
    }

    private static void requireMetric(Map<Integer, Double> metric, int k, String label) {
        if (!metric.containsKey(k)) {
            throw new IllegalArgumentException("missing required metric " + label + " at k=" + k);
        }
    }

    private int compareDouble(double before, double after) {
        double diff = after - before;
        if (diff > epsilon) {
            return 1;
        }
        if (diff < -epsilon) {
            return -1;
        }
        return 0;
    }
}
