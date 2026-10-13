package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionView;
import com.monada.core.execution.ScopeId;
import java.util.List;
import java.util.Objects;

/**
 * One execution offered for comparison: the caller-owned scope (an {@link ExecutionView} does not carry it),
 * the view and the {@link ExecutionSummary} computed from that very view. There is no public constructor, so a
 * summary can never be paired with a view it was not computed from, and {@link #of} rejects a view whose scope,
 * task, provenance, policy, outcome or derived acceptance disagree with its own history.
 *
 * <p>It cannot authenticate the view: a hand-built view whose history was altered consistently (for example lower
 * stage usage) passes. Views must come from {@code ExecutionMemory.loadExecution} (contract section 20, trust boundary).
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
        ViewVerifier.verify(scope, view);
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
