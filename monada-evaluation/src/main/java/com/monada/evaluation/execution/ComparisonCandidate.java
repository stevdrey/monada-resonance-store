package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.ScopeId;
import java.util.List;
import java.util.Objects;

/**
 * One execution offered for comparison: the caller-owned scope (an {@link ExecutionView} does not carry it),
 * the view and the {@link ExecutionSummary} computed from that very view. There is no public constructor, so a
 * summary can never be paired with a view it was not computed from.
 */
public final class ComparisonCandidate {
    private final ScopeId scope;
    private final ExecutionView view;
    private final ExecutionSummary summary;

    private ComparisonCandidate(ScopeId scope, ExecutionView view, ExecutionSummary summary) {
        this.scope = scope;
        this.view = view;
        this.summary = summary;
    }

    /** Summarizes {@code view} with the given prices and binds the result to it. */
    public static ComparisonCandidate of(ScopeId scope, ExecutionView view, PriceBook prices,
                                         List<ActualBilledAmount> actualBilled) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(view, "view");
        if (view.history().isEmpty()) {
            throw new IllegalArgumentException("the view has no history, so its scope cannot be verified");
        }
        for (HistoryEntry entry : view.history()) {
            if (!entry.event().scopeId().equals(scope) || !entry.event().executionId().equals(view.id())) {
                throw new IllegalArgumentException("the view's history (event " + entry.event().eventId() + ", scope "
                        + entry.event().scopeId() + ", execution " + entry.event().executionId()
                        + ") does not belong to " + scope + "/" + view.id());
            }
        }
        return new ComparisonCandidate(scope, view, ExecutionSummaryCalculator.summarize(view, prices, actualBilled));
    }

    public ScopeId scope() {
        return scope;
    }

    public ExecutionView view() {
        return view;
    }

    public ExecutionSummary summary() {
        return summary;
    }

    public ExecutionRef ref() {
        return new ExecutionRef(scope, view.id());
    }
}
