package com.monada.evaluation;

/**
 * Classification of a ranking change between two evaluation runs.
 *
 * <p>Used by {@link EvaluationComparator} to summarize whether feedback (or any
 * other ranking-affecting change) helped, was neutral, or hurt retrieval quality
 * compared to a protected baseline.
 */
public enum RankingChange {
    IMPROVED,
    MAINTAINED,
    DEGRADED
}
