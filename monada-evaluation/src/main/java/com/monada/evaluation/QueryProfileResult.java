package com.monada.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-query comparison of top-K results across multiple {@link EvaluationProfile}s.
 *
 * <p>Holds the expected labels, the ranked labels returned per profile, the
 * {@link RankingChange} classification of each profile versus the first (base)
 * profile, an optional {@link RetrievalFailureType} for any profile whose
 * query was not perfect, and per-profile {@link QueryKeyDiagnostic} information.
 */
public record QueryProfileResult(
        String queryText,
        Set<String> expectedLabels,
        Map<EvaluationProfile, List<String>> returnedLabelsByProfile,
        Map<EvaluationProfile, RankingChange> changeByProfile,
        RetrievalFailureType failureType,
        Map<EvaluationProfile, QueryKeyDiagnostic> queryKeyDiagnosticByProfile
) {
    public QueryProfileResult {
        Objects.requireNonNull(queryText, "queryText");
        if (queryText.isBlank()) {
            throw new IllegalArgumentException("queryText must not be blank");
        }
        expectedLabels = Set.copyOf(Objects.requireNonNull(expectedLabels, "expectedLabels"));
        returnedLabelsByProfile = Map.copyOf(Objects.requireNonNull(returnedLabelsByProfile, "returnedLabelsByProfile"));
        changeByProfile = Map.copyOf(Objects.requireNonNull(changeByProfile, "changeByProfile"));
        // failureType can be null when there is no failure
        queryKeyDiagnosticByProfile = Map.copyOf(Objects.requireNonNull(queryKeyDiagnosticByProfile, "queryKeyDiagnosticByProfile"));
    }

    /**
     * Backwards-compatible constructor without query key diagnostics.
     */
    public QueryProfileResult(
            String queryText,
            Set<String> expectedLabels,
            Map<EvaluationProfile, List<String>> returnedLabelsByProfile,
            Map<EvaluationProfile, RankingChange> changeByProfile,
            RetrievalFailureType failureType) {
        this(queryText, expectedLabels, returnedLabelsByProfile, changeByProfile,
                failureType, new HashMap<>());
    }

    /**
     * Returns true if this query result has a failure type.
     */
    public boolean hasFailure() {
        return failureType != null;
    }
}
