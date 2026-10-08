package com.monada.core.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity and time fields shared by every ledger event (contract section 3). All identifiers and
 * timestamps are caller-supplied; revision {@code 1} is an original fact, {@code n+1} a correction
 * that supersedes the latest revision of an earlier event.
 */
public record EventEnvelope(
        EventId eventId,
        ScopeId scopeId,
        TaskId taskId,
        ExecutionId executionId,
        Optional<AttemptId> attemptId,
        int revision,
        Optional<EventId> supersedes,
        Instant occurredAt,
        Instant recordedAt
) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(supersedes, "supersedes");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        if ((revision == 1) != supersedes.isEmpty()) {
            throw new IllegalArgumentException("supersedes is required exactly when revision > 1");
        }
        if (supersedes.isPresent() && supersedes.get().equals(eventId)) {
            throw new IllegalArgumentException("an event cannot supersede itself");
        }
        if (recordedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("recordedAt must not be before occurredAt");
        }
    }

    /** Original fact (revision 1) not tied to an attempt. */
    public static EventEnvelope original(EventId eventId, ScopeId scope, TaskId task, ExecutionId execution,
                                         Instant occurredAt, Instant recordedAt) {
        return new EventEnvelope(eventId, scope, task, execution, Optional.empty(), 1, Optional.empty(),
                occurredAt, recordedAt);
    }

    /** Original fact (revision 1) of an attempt. */
    public static EventEnvelope original(EventId eventId, ScopeId scope, TaskId task, ExecutionId execution,
                                         AttemptId attempt, Instant occurredAt, Instant recordedAt) {
        return new EventEnvelope(eventId, scope, task, execution, Optional.of(attempt), 1, Optional.empty(),
                occurredAt, recordedAt);
    }

    /** True when both envelopes describe the same fact location, ignoring revision and supersedes. */
    boolean sameFactAs(EventEnvelope other) {
        return eventId.equals(other.eventId) && scopeId.equals(other.scopeId) && taskId.equals(other.taskId)
                && executionId.equals(other.executionId) && attemptId.equals(other.attemptId)
                && occurredAt.equals(other.occurredAt) && recordedAt.equals(other.recordedAt);
    }
}
