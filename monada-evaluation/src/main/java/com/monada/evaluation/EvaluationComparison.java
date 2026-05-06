package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Result of comparing two {@link EvaluationReport} runs over the same dataset.
 *
 * <p>Holds both reports, a per-query before/after view, and an aggregate
 * {@link RankingChange} summarizing whether the protected metrics
 * (Hit@1, Recall@3, Recall@5, MRR) improved, were maintained, or degraded.
 */
public record EvaluationComparison(
        EvaluationReport before,
        EvaluationReport after,
        List<QueryRankingComparison> perQuery,
        RankingChange aggregate
) {
    public EvaluationComparison {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        perQuery = List.copyOf(Objects.requireNonNull(perQuery, "perQuery"));
        Objects.requireNonNull(aggregate, "aggregate");
        if (perQuery.size() != before.queryResults().size()) {
            throw new IllegalArgumentException(
                    "perQuery size (" + perQuery.size() + ") must match before report query count ("
                            + before.queryResults().size() + ")");
        }
        if (before.queryResults().size() != after.queryResults().size()) {
            throw new IllegalArgumentException(
                    "before and after must contain the same number of queries");
        }
    }
}
