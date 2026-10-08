package com.monada.core.execution;

import java.util.Objects;
import java.util.Optional;

/** Exact address of ledger facts: scope, task, execution, optional attempt, event and revision. */
public record ExperienceRef(
        ScopeId scope,
        TaskId task,
        ExecutionId execution,
        Optional<AttemptId> attempt,
        EventId eventId,
        int revision
) {
    public ExperienceRef {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(eventId, "eventId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
    }
}
