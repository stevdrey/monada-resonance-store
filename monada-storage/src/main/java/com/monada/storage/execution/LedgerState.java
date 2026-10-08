package com.monada.storage.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.TaskId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory index of one scope's valid ledger prefix, and the ordering/reference rules of contract
 * section 4. Shared by the writer (check before append), the reader and the audit so all three agree.
 */
final class LedgerState {

    /** A rule violation: a clash with existing facts (CONFLICT) or an invalid event (REJECT). */
    record Violation(boolean conflict, LedgerDiagnosticCategory category, String message) {
        static Violation conflict(LedgerDiagnosticCategory category, String message) {
            return new Violation(true, category, message);
        }

        static Violation reject(LedgerDiagnosticCategory category, String message) {
            return new Violation(false, category, message);
        }
    }

    record Entry(long sequence, ExecutionEvent event, byte[] payload) {
        EventKind effectiveKind() {
            return event instanceof Correction c ? c.replacement().kind() : event.kind();
        }
    }

    private static final class Attempt {
        int ordinal;
        boolean finished;

        Attempt(int ordinal) {
            this.ordinal = ordinal;
        }
    }

    private static final class Execution {
        final TaskId task;
        final Map<AttemptId, Attempt> attempts = new HashMap<>();
        final Set<Integer> ordinals = new HashSet<>();

        Execution(TaskId task) {
            this.task = task;
        }
    }

    private final Map<EventId, Entry> events = new LinkedHashMap<>();
    private final Set<EventId> superseded = new HashSet<>();
    private final Map<ExecutionId, Execution> executions = new HashMap<>();
    private long lastSequence;

    long lastSequence() {
        return lastSequence;
    }

    Optional<Entry> find(EventId id) {
        return Optional.ofNullable(events.get(id));
    }

    static boolean samePayload(Entry entry, byte[] payload) {
        return Arrays.equals(entry.payload(), payload);
    }

    /** Checks an event against the current state; empty means it may be appended. */
    Optional<Violation> check(ExecutionEvent event) {
        EventEnvelope env = event.envelope();
        if (events.containsKey(env.eventId())) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "event id " + env.eventId() + " already exists with a different payload"));
        }
        if (event instanceof Correction correction) {
            return checkCorrection(correction);
        }
        if (event instanceof ExecutionStarted) {
            if (executions.containsKey(env.executionId())) {
                return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                        "execution " + env.executionId() + " was already started"));
            }
            return Optional.empty();
        }
        Execution execution = executions.get(env.executionId());
        if (execution == null) {
            return Optional.of(Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                    "execution " + env.executionId() + " has no EXECUTION_STARTED in this ledger"));
        }
        if (!execution.task.equals(env.taskId())) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "execution " + env.executionId() + " belongs to task " + execution.task));
        }
        return switch (event) {
            case AttemptStarted e -> checkAttemptStarted(execution, e);
            case StageRecorded e -> checkOpenAttempt(execution, env, "STAGE_RECORDED");
            case EvidenceRecorded e -> checkOpenAttempt(execution, env, "EVIDENCE_RECORDED");
            case AttemptFinished e -> {
                Attempt attempt = execution.attempts.get(env.attemptId().get());
                if (attempt == null) {
                    yield Optional.of(missingAttempt(env));
                }
                yield attempt.finished
                        ? Optional.of(Violation.reject(LedgerDiagnosticCategory.INVALID_ORDER,
                        "attempt " + env.attemptId().get() + " is already finished"))
                        : Optional.empty();
            }
            case OutcomeRecorded e -> e.outcome().acceptedAttempt()
                    .filter(a -> !execution.attempts.containsKey(a))
                    .map(a -> Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                            "accepted attempt " + a + " does not exist in execution " + env.executionId()));
            default -> Optional.empty();
        };
    }

    private Optional<Violation> checkAttemptStarted(Execution execution, AttemptStarted e) {
        EventEnvelope env = e.envelope();
        AttemptId id = env.attemptId().get();
        if (execution.attempts.containsKey(id)) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "attempt " + id + " was already started"));
        }
        if (execution.ordinals.contains(e.ordinal())) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "attempt ordinal " + e.ordinal() + " is already used in execution " + env.executionId()));
        }
        return e.previousAttempt().filter(p -> !execution.attempts.containsKey(p))
                .map(p -> Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                        "previous attempt " + p + " does not exist in execution " + env.executionId()));
    }

    private Optional<Violation> checkOpenAttempt(Execution execution, EventEnvelope env, String kind) {
        Attempt attempt = execution.attempts.get(env.attemptId().get());
        if (attempt == null) {
            return Optional.of(missingAttempt(env));
        }
        if (attempt.finished) {
            return Optional.of(Violation.reject(LedgerDiagnosticCategory.INVALID_ORDER,
                    kind + " after ATTEMPT_FINISHED of attempt " + env.attemptId().get()
                            + " is only allowed as a CORRECTION"));
        }
        return Optional.empty();
    }

    private static Violation missingAttempt(EventEnvelope env) {
        return Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                "attempt " + env.attemptId().get() + " has not been started in execution " + env.executionId());
    }

    private Optional<Violation> checkCorrection(Correction correction) {
        EventEnvelope env = correction.envelope();
        EventId targetId = env.supersedes().get();
        Entry target = events.get(targetId);
        if (target == null) {
            return Optional.of(Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                    "correction target " + targetId + " does not exist"));
        }
        EventEnvelope targetEnv = target.event().envelope();
        if (!targetEnv.executionId().equals(env.executionId()) || !targetEnv.taskId().equals(env.taskId())
                || !targetEnv.attemptId().equals(env.attemptId())) {
            return Optional.of(Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                    "correction must address the same task, execution and attempt as " + targetId));
        }
        if (superseded.contains(targetId)) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "correction target " + targetId + " is not the latest revision"));
        }
        if (env.revision() != targetEnv.revision() + 1) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "correction revision must be " + (targetEnv.revision() + 1)));
        }
        if (target.effectiveKind() != correction.replacement().kind()) {
            return Optional.of(Violation.reject(LedgerDiagnosticCategory.INVALID_ORDER,
                    "correction of a " + target.effectiveKind() + " must replace it with the same kind"));
        }
        Execution execution = executions.get(env.executionId());
        return switch (correction.replacement()) {
            case AttemptStarted e -> checkCorrectedAttempt(execution, env, e);
            case OutcomeRecorded e -> e.outcome().acceptedAttempt()
                    .filter(a -> !execution.attempts.containsKey(a))
                    .map(a -> Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                            "accepted attempt " + a + " does not exist in execution " + env.executionId()));
            default -> Optional.empty();
        };
    }

    /** A corrected ATTEMPT_STARTED keeps the effective ordinals of the execution unique. */
    private Optional<Violation> checkCorrectedAttempt(Execution execution, EventEnvelope env, AttemptStarted e) {
        Optional<Violation> missing = e.previousAttempt().filter(p -> !execution.attempts.containsKey(p))
                .map(p -> Violation.reject(LedgerDiagnosticCategory.DANGLING_REFERENCE,
                        "previous attempt " + p + " does not exist in execution " + env.executionId()));
        if (missing.isPresent()) {
            return missing;
        }
        Attempt own = execution.attempts.get(env.attemptId().get());
        if (own.ordinal != e.ordinal() && execution.ordinals.contains(e.ordinal())) {
            return Optional.of(Violation.conflict(LedgerDiagnosticCategory.CONFLICTING_DUPLICATE_EVENT,
                    "attempt ordinal " + e.ordinal() + " is already used in execution " + env.executionId()));
        }
        return Optional.empty();
    }

    /** Records an event that passed {@link #check}. */
    void apply(long sequence, ExecutionEvent event, byte[] payload) {
        EventEnvelope env = event.envelope();
        events.put(env.eventId(), new Entry(sequence, event, payload));
        lastSequence = sequence;
        switch (event) {
            case Correction c -> {
                superseded.add(c.envelope().supersedes().get());
                if (c.replacement() instanceof AttemptStarted replaced) {
                    Execution execution = executions.get(env.executionId());
                    Attempt attempt = execution.attempts.get(env.attemptId().get());
                    execution.ordinals.remove(attempt.ordinal);
                    attempt.ordinal = replaced.ordinal();
                    execution.ordinals.add(attempt.ordinal);
                }
            }
            case ExecutionStarted e -> executions.put(env.executionId(), new Execution(env.taskId()));
            case AttemptStarted e -> {
                Execution execution = executions.get(env.executionId());
                execution.attempts.put(env.attemptId().get(), new Attempt(e.ordinal()));
                execution.ordinals.add(e.ordinal());
            }
            case AttemptFinished e -> executions.get(env.executionId()).attempts.get(env.attemptId().get()).finished = true;
            default -> {
            }
        }
    }
}
