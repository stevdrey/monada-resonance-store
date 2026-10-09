package com.monada.api.execution;

import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.storage.execution.LedgerRecord;
import java.util.List;
import java.util.Optional;

/**
 * One execution as currently known. {@code currentOutcome} carries the recorded {@code OutcomeOrigin}
 * (an imported claim stays distinguishable) while {@code acceptance} is derived separately.
 * {@code history} keeps every ledger record of the execution, every revision included.
 */
public record ExecutionView(ExecutionId id, TaskId task, String taskSummary, SourceProvenance provenance,
                            EvaluationPolicy policy, Optional<Outcome> currentOutcome, DerivedAcceptance acceptance,
                            List<AttemptView> attempts, List<LedgerRecord> history) {
    public ExecutionView {
        attempts = List.copyOf(attempts);
        history = List.copyOf(history);
    }

    public Optional<AttemptView> attempt(com.monada.core.execution.AttemptId attemptId) {
        return attempts.stream().filter(a -> a.id().equals(attemptId)).findFirst();
    }
}
