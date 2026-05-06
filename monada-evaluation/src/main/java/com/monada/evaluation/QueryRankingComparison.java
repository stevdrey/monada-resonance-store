package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Per-query before/after view of returned labels and the resulting
 * {@link RankingChange} classification.
 *
 * <p>Does not duplicate metric maps; consumers that need numeric metrics can
 * read them from the underlying {@link EvaluationReport} held by the parent
 * {@link EvaluationComparison}.
 */
public record QueryRankingComparison(
        String queryText,
        List<String> beforeLabels,
        List<String> afterLabels,
        RankingChange change
) {
    public QueryRankingComparison {
        Objects.requireNonNull(queryText, "queryText");
        if (queryText.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        beforeLabels = List.copyOf(Objects.requireNonNull(beforeLabels, "beforeLabels"));
        afterLabels = List.copyOf(Objects.requireNonNull(afterLabels, "afterLabels"));
        Objects.requireNonNull(change, "change");
    }
}
