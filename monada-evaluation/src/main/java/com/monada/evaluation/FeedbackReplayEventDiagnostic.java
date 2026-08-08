package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/** Query-key matching result for one replayed fixture event. */
public record FeedbackReplayEventDiagnostic(
        int ordinal,
        FeedbackReplayEvent event,
        List<String> matchedEvaluationQueries
) {
    public FeedbackReplayEventDiagnostic {
        if (ordinal <= 0) {
            throw new IllegalArgumentException("ordinal must be positive: " + ordinal);
        }
        Objects.requireNonNull(event, "event");
        matchedEvaluationQueries = List.copyOf(
                Objects.requireNonNull(matchedEvaluationQueries, "matchedEvaluationQueries"));
        for (String query : matchedEvaluationQueries) {
            Objects.requireNonNull(query, "matched evaluation query");
            if (query.isBlank()) {
                throw new IllegalArgumentException("matched evaluation queries must not contain blanks");
            }
        }
    }

    public boolean matchesEvaluationQuery() {
        return !matchedEvaluationQueries.isEmpty();
    }
}
