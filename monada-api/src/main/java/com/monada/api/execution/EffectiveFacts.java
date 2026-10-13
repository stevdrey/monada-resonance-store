package com.monada.api.execution;

import com.monada.api.execution.ExperienceSample.Revision;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExperienceRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place where the ledger entries of an execution are resolved into <b>effective facts</b> (contract
 * sections 6 and 8): a corrected event is replaced by its latest revision, every fact keeps its ledger sequence and
 * exact {@link ExperienceRef} (a correction is addressed by its own event ID and revision), and facts are kept in
 * ledger order. {@link ExecutionViews} derives views and acceptance from it and {@link SampleBuilder} derives
 * export samples from it, so the two can never resolve corrections differently.
 */
final class EffectiveFacts {
    /** An effective (latest-revision) fact: its entry, the unwrapped event it states and its exact reference. */
    record Fact(HistoryEntry entry, ExecutionEvent event, ExperienceRef ref) {
        long sequence() {
            return entry.sequence();
        }
    }

    private final Fact started;
    private final List<Fact> outcomes = new ArrayList<>();
    private final Map<AttemptId, Fact> starts = new LinkedHashMap<>();
    private final Map<AttemptId, Fact> finishes = new LinkedHashMap<>();
    private final Map<AttemptId, List<Fact>> stages = new LinkedHashMap<>();
    private final Map<AttemptId, List<Fact>> evidence = new LinkedHashMap<>();
    private final List<Revision> revisions = new ArrayList<>();

    /** {@code history}: the non-empty entries of one execution in sequence order. */
    EffectiveFacts(List<HistoryEntry> history) {
        Map<EventId, HistoryEntry> byId = new HashMap<>();
        Set<EventId> superseded = new HashSet<>();
        for (HistoryEntry h : history) {
            byId.put(h.event().eventId(), h);
            h.event().envelope().supersedes().ifPresent(superseded::add);
        }
        Fact start = null;
        for (HistoryEntry h : history) {
            if (superseded.contains(h.event().eventId())) {
                continue; // an older revision: kept in history, replaced here by its latest revision
            }
            ExecutionEvent outer = h.event();
            ExecutionEvent effective = outer instanceof Correction c ? c.replacement() : outer;
            Fact fact = new Fact(h, effective, ref(outer));
            if (outer instanceof Correction c) {
                revisions.add(new Revision(fact.ref(), h.sequence(), c.justification(), chain(outer, byId)));
            }
            switch (effective) {
                case ExecutionStarted e -> start = fact;
                case OutcomeRecorded e -> outcomes.add(fact);
                case AttemptStarted e -> starts.put(e.envelope().attemptId().get(), fact);
                case AttemptFinished e -> finishes.put(e.envelope().attemptId().get(), fact);
                case StageRecorded e -> stages.computeIfAbsent(e.envelope().attemptId().get(),
                        k -> new ArrayList<>()).add(fact);
                case EvidenceRecorded e -> evidence.computeIfAbsent(e.envelope().attemptId().get(),
                        k -> new ArrayList<>()).add(fact);
                case Correction e -> throw new IllegalStateException("nested correction");
            }
        }
        if (start == null) {
            throw new IllegalStateException("execution " + history.get(0).event().executionId()
                    + " has no EXECUTION_STARTED event");
        }
        this.started = start;
    }

    private static ExperienceRef ref(ExecutionEvent outer) {
        return new ExperienceRef(outer.scopeId(), outer.envelope().taskId(), outer.executionId(),
                outer.envelope().attemptId(), outer.eventId(), outer.envelope().revision());
    }

    /** References of the revisions a correction replaced, oldest first (the correction itself excluded). */
    private static List<ExperienceRef> chain(ExecutionEvent correction, Map<EventId, HistoryEntry> byId) {
        List<ExperienceRef> older = new ArrayList<>();
        var target = correction.envelope().supersedes();
        while (target.isPresent()) {
            ExecutionEvent e = byId.get(target.get()).event();
            older.add(0, ref(e));
            target = e.envelope().supersedes();
        }
        return older;
    }

    Fact started() {
        return started;
    }

    /** Effective outcome events in ledger order; the last one is current. */
    List<Fact> outcomes() {
        return outcomes;
    }

    Map<AttemptId, Fact> starts() {
        return starts;
    }

    Map<AttemptId, Fact> finishes() {
        return finishes;
    }

    Map<AttemptId, List<Fact>> stages() {
        return stages;
    }

    Map<AttemptId, List<Fact>> evidence() {
        return evidence;
    }

    /** One entry per corrected fact that is still effective. */
    List<Revision> revisions() {
        return revisions;
    }
}
