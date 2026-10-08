package com.monada.storage.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeStatus;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic replay view of a scope in ledger-sequence order. Incomplete but valid runs are normal:
 * an execution with no outcome is {@linkplain ExecutionView#isPending() pending}, an attempt with no
 * finish is {@linkplain AttemptView#isInProgress() in progress}. Corrected events are replaced by their
 * latest revision in these views; {@link ExecutionView#history()} keeps every revision.
 */
public record ExecutionReplay(ScopeId scope, List<ExecutionView> executions) {

    public ExecutionReplay {
        executions = List.copyOf(executions);
    }

    public record AttemptView(AttemptId id, int ordinal, Optional<AttemptResult> result) {
        public boolean isInProgress() {
            return result.isEmpty();
        }
    }

    public record ExecutionView(ExecutionId id, TaskId task, Optional<Outcome> currentOutcome,
                                List<AttemptView> attempts, List<LedgerRecord> history) {
        public ExecutionView {
            attempts = List.copyOf(attempts);
            history = List.copyOf(history);
        }

        /** No outcome yet, or the current outcome is an explicit {@code PENDING} (contract section 8). */
        public boolean isPending() {
            return currentOutcome.isEmpty() || currentOutcome.get().status() == OutcomeStatus.PENDING;
        }
    }

    public Optional<ExecutionView> execution(ExecutionId id) {
        return executions.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Builds the view from records in ascending sequence order. */
    static ExecutionReplay of(ScopeId scope, List<LedgerRecord> records) {
        Set<EventId> superseded = new HashSet<>();
        for (LedgerRecord r : records) {
            if (r.event() instanceof Correction c) {
                superseded.add(c.envelope().supersedes().get());
            }
        }
        Map<ExecutionId, List<LedgerRecord>> byExecution = new LinkedHashMap<>();
        for (LedgerRecord r : records) {
            byExecution.computeIfAbsent(r.event().executionId(), k -> new ArrayList<>()).add(r);
        }
        List<ExecutionView> views = new ArrayList<>();
        byExecution.forEach((id, history) -> views.add(view(id, history, superseded)));
        return new ExecutionReplay(scope, views);
    }

    private static ExecutionView view(ExecutionId id, List<LedgerRecord> history, Set<EventId> superseded) {
        TaskId task = history.get(0).event().envelope().taskId();
        Optional<Outcome> outcome = Optional.empty();
        Map<AttemptId, Integer> ordinals = new LinkedHashMap<>();
        Map<AttemptId, AttemptResult> results = new LinkedHashMap<>();
        for (LedgerRecord r : history) {
            if (superseded.contains(r.event().eventId())) {
                continue;
            }
            ExecutionEvent effective = r.event() instanceof Correction c ? c.replacement() : r.event();
            switch (effective) {
                case OutcomeRecorded e -> outcome = Optional.of(e.outcome());
                case AttemptStarted e -> ordinals.put(e.envelope().attemptId().get(), e.ordinal());
                case AttemptFinished e -> results.put(e.envelope().attemptId().get(), e.result());
                default -> {
                }
            }
        }
        List<AttemptView> attempts = new ArrayList<>();
        ordinals.forEach((attempt, ordinal) ->
                attempts.add(new AttemptView(attempt, ordinal, Optional.ofNullable(results.get(attempt)))));
        return new ExecutionView(id, task, outcome, attempts, history);
    }
}
