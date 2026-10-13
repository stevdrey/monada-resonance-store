package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionView;
import com.monada.core.execution.ScopeId;
import java.util.List;
import java.util.Objects;

/**
 * One execution offered for comparison: the caller-owned scope (an {@link ExecutionView} does not carry it),
 * the view and the {@link ExecutionSummary} computed from that same view.
 */
public record ComparisonCandidate(ScopeId scope, ExecutionView view, ExecutionSummary summary) {
    public ComparisonCandidate {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(summary, "summary");
        if (!summary.execution().equals(view.id()) || !summary.acceptance().equals(view.acceptance())) {
            throw new IllegalArgumentException("the summary was not computed from this execution view");
        }
    }

    /** Summarizes {@code view} with the given prices; the usual way to build a candidate. */
    public static ComparisonCandidate of(ScopeId scope, ExecutionView view, PriceBook prices,
                                         List<ActualBilledAmount> actualBilled) {
        return new ComparisonCandidate(scope, view, ExecutionSummaryCalculator.summarize(view, prices, actualBilled));
    }

    public ExecutionRef ref() {
        return new ExecutionRef(scope, view.id());
    }
}
