package com.monada.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-query comparison of top-K results across multiple {@link EvaluationProfile}s.
 *
 * <p>Holds the expected labels, the ranked labels returned per profile, the
 * {@link RankingChange} classification of each profile versus the first (base)
 * profile, and an optional {@link RetrievalFailureType} for any profile whose
 * query was not perfect (i.e. the best-performing profile still missed a result).
 */
public record QueryProfileResult(
        String queryText,
        Set<String> expectedLabels,
        Map<EvaluationProfile, List<String>> returnedLabelsByProfile,
        Map<EvaluationProfile, RankingChange> changeByProfile,
        Optional<RetrievalFailureType> failureType
) {
    public QueryProfileResult {
        Objects.requireNonNull(queryText, "queryText");
        if (queryText.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabelsByProfile = Map.copyOf(Objects.requireNonNull(returnedLabelsByProfile, "returnedLabelsByProfile"));
        changeByProfile = Map.copyOf(Objects.requireNonNull(changeByProfile, "changeByProfile"));
        Objects.requireNonNull(failureType, "failureType");
    }
}
