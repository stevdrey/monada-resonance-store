package com.monada.evaluation.execution;

import com.monada.api.execution.HistoryEntry;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays a view's history in sequence order against the ordering and reference rules the ledger enforces when
 * it appends (contract section 4): the ledger's own checker is private to {@code monada-storage}, and a view can
 * be built by hand, so fabricated usage or evidence (a stage for an attempt that was never started, evidence
 * after the attempt finished, a dangling correction) must not reach the comparison. Mirrors {@code LedgerState}.
 */
final class HistoryStructure {

    private static final class Attempt {
        int ordinal;
        AttemptId previous;
        boolean finished;
    }

    private record Seen(EventEnvelope envelope, EventKind effectiveKind) {
    }

    private final Map<EventId, Seen> events = new HashMap<>();
    private final Set<EventId> superseded = new HashSet<>();
    private final Map<AttemptId, Attempt> attempts = new HashMap<>();
    private final Set<Integer> ordinals = new HashSet<>();
    private boolean started;

    private HistoryStructure() {
    }

    static void verify(List<HistoryEntry> history) {
        HistoryStructure state = new HistoryStructure();
        long previous = 0;
        for (HistoryEntry entry : history) {
            if (entry.sequence() <= previous) {
                throw invalid(entry, "sequences must be strictly ascending");
            }
            previous = entry.sequence();
            state.apply(entry);
        }
        if (!state.started) {
            throw new IllegalArgumentException("the view's history has no EXECUTION_STARTED event");
        }
    }

    private static IllegalArgumentException invalid(HistoryEntry entry, String why) {
        return new IllegalArgumentException("invalid history at sequence " + entry.sequence() + " (event "
                + entry.event().eventId() + "): " + why);
    }

    private void apply(HistoryEntry entry) {
        ExecutionEvent event = entry.event();
        EventEnvelope env = event.envelope();
        if (events.containsKey(env.eventId())) {
            throw invalid(entry, "duplicate event id");
        }
        if (event instanceof Correction c) {
            applyCorrection(entry, c);
            return;
        }
        if (event instanceof ExecutionStarted) {
            if (started) {
                throw invalid(entry, "the execution was already started");
            }
            started = true;
        } else if (!started) {
            throw invalid(entry, "event before EXECUTION_STARTED");
        }
        switch (event) {
            case AttemptStarted e -> {
                AttemptId id = env.attemptId().orElseThrow();
                if (attempts.containsKey(id)) {
                    throw invalid(entry, "attempt " + id + " was already started");
                }
                if (ordinals.contains(e.ordinal())) {
                    throw invalid(entry, "attempt ordinal " + e.ordinal() + " is already used");
                }
                requireKnown(entry, e.previousAttempt().orElse(null));
                Attempt attempt = new Attempt();
                attempt.ordinal = e.ordinal();
                attempt.previous = e.previousAttempt().orElse(null);
                attempts.put(id, attempt);
                ordinals.add(e.ordinal());
            }
            case StageRecorded e -> requireOpen(entry, env);
            case EvidenceRecorded e -> requireOpen(entry, env);
            case AttemptFinished e -> {
                requireOpen(entry, env);
                attempts.get(env.attemptId().orElseThrow()).finished = true;
            }
            case OutcomeRecorded e -> requireKnown(entry, e.outcome().acceptedAttempt().orElse(null));
            default -> {
            }
        }
        events.put(env.eventId(), new Seen(env, event.kind()));
    }

    private void applyCorrection(HistoryEntry entry, Correction c) {
        EventEnvelope env = c.envelope();
        EventId targetId = env.supersedes().orElseThrow();
        Seen target = events.get(targetId);
        if (target == null) {
            throw invalid(entry, "correction target " + targetId + " does not exist");
        }
        EventEnvelope t = target.envelope();
        if (!t.executionId().equals(env.executionId()) || !t.taskId().equals(env.taskId())
                || !t.attemptId().equals(env.attemptId())) {
            throw invalid(entry, "correction must address the same task, execution and attempt as " + targetId);
        }
        if (superseded.contains(targetId)) {
            throw invalid(entry, "correction target " + targetId + " is not the latest revision");
        }
        if (env.revision() != t.revision() + 1) {
            throw invalid(entry, "correction revision must be " + (t.revision() + 1));
        }
        if (target.effectiveKind() != c.replacement().kind()) {
            throw invalid(entry, "correction must replace a " + target.effectiveKind() + " with the same kind");
        }
        switch (c.replacement()) {
            case AttemptStarted e -> {
                requireKnown(entry, e.previousAttempt().orElse(null));
                AttemptId self = env.attemptId().orElseThrow();
                AttemptId cursor = e.previousAttempt().orElse(null);
                for (int steps = 0; cursor != null && steps <= attempts.size(); steps++) {
                    if (cursor.equals(self)) {
                        throw invalid(entry, "previous-attempt link of " + self + " would create a cycle");
                    }
                    cursor = attempts.get(cursor).previous;
                }
                Attempt own = attempts.get(self);
                if (own.ordinal != e.ordinal() && ordinals.contains(e.ordinal())) {
                    throw invalid(entry, "attempt ordinal " + e.ordinal() + " is already used");
                }
                ordinals.remove(own.ordinal);
                own.ordinal = e.ordinal();
                own.previous = e.previousAttempt().orElse(null);
                ordinals.add(own.ordinal);
            }
            case OutcomeRecorded e -> requireKnown(entry, e.outcome().acceptedAttempt().orElse(null));
            default -> {
            }
        }
        superseded.add(targetId);
        events.put(env.eventId(), new Seen(env, c.replacement().kind()));
    }

    private void requireOpen(HistoryEntry entry, EventEnvelope env) {
        Attempt attempt = attempts.get(env.attemptId().orElseThrow());
        if (attempt == null) {
            throw invalid(entry, "attempt " + env.attemptId().orElseThrow() + " has not been started");
        }
        if (attempt.finished) {
            throw invalid(entry, entry.event().kind() + " after ATTEMPT_FINISHED is only allowed as a CORRECTION");
        }
    }

    private void requireKnown(HistoryEntry entry, AttemptId attempt) {
        if (attempt != null && !attempts.containsKey(attempt)) {
            throw invalid(entry, "attempt " + attempt + " does not exist");
        }
    }
}
