package com.monada.api.execution;

import com.monada.core.KnowledgeAtom;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.ProjectionCheckpoint.Entry;
import com.monada.storage.execution.ProjectionCheckpoint.EntryKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, deterministic projection of ledger events to checkpoint entries. Incremental projection, explicit
 * rebuild and verification of an existing checkpoint all run this one function over the ledger in sequence
 * order, so they agree byte for byte.
 *
 * <p>One experience is one {@code ATTEMPT_FINISHED} fact at its latest revision. Its ref names the exact
 * ledger event (the original or the correction that replaced it) and its text is
 * {@link ProjectionText}. A correction of the finish, or of the execution's task summary, retires the old
 * atom/ref pair and projects the new one. Identical texts share one atom and keep distinct refs.
 */
final class Projector {
    private record Experience(ExperienceRef ref, Optional<String> solution, Optional<String> lesson, String atomId) {
    }

    /** Checkpoint entries for one ledger event and the texts of its PROJECT entries, by atom ID. */
    record Step(List<Entry> entries, Map<String, String> texts) {
    }

    private final Map<ExecutionId, String> taskSummaries = new HashMap<>();
    private final Map<ExecutionId, LinkedHashMap<AttemptId, Experience>> experiences = new HashMap<>();
    private final TreeMap<String, TreeMap<String, ExperienceRef>> live = new TreeMap<>();
    private final Set<String> projected = new HashSet<>();

    Step apply(ExecutionEvent event) {
        ExecutionEvent effective = event instanceof Correction c ? c.replacement() : event;
        List<Entry> entries = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        switch (effective) {
            case ExecutionStarted s -> {
                taskSummaries.put(s.executionId(), s.taskSummary());
                for (Map.Entry<AttemptId, Experience> e : experiences
                        .getOrDefault(s.executionId(), new LinkedHashMap<>()).entrySet()) {
                    Experience old = e.getValue();
                    String text = ProjectionText.of(s.taskSummary(), old.solution(), old.lesson());
                    String atomId = atomId(text);
                    if (!atomId.equals(old.atomId())) {
                        move(entries, texts, old, new Experience(old.ref(), old.solution(), old.lesson(), atomId),
                                text);
                        e.setValue(new Experience(old.ref(), old.solution(), old.lesson(), atomId));
                    }
                }
            }
            case AttemptFinished f -> {
                AttemptId attempt = f.envelope().attemptId().orElseThrow();
                ExperienceRef ref = new ExperienceRef(event.scopeId(), event.envelope().taskId(),
                        event.executionId(), Optional.of(attempt), event.eventId(), event.envelope().revision());
                String text = ProjectionText.of(taskSummaries.get(f.executionId()), f.solutionSummary(),
                        f.lessonSummary());
                Experience next = new Experience(ref, f.solutionSummary(), f.lessonSummary(), atomId(text));
                Experience old = experiences.computeIfAbsent(f.executionId(), k -> new LinkedHashMap<>())
                        .put(attempt, next);
                move(entries, texts, old, next, text);
            }
            default -> {
                // Other facts never change projected text.
            }
        }
        return new Step(List.copyOf(entries), Collections.unmodifiableMap(texts));
    }

    private void move(List<Entry> entries, Map<String, String> texts, Experience old, Experience next, String text) {
        if (old != null) {
            entries.add(new Entry(EntryKind.RETIRE, old.atomId(), old.ref()));
            TreeMap<String, ExperienceRef> refs = live.get(old.atomId());
            refs.remove(ExperienceRefCodec.canonical(old.ref()));
            if (refs.isEmpty()) {
                live.remove(old.atomId());
            }
        }
        entries.add(new Entry(EntryKind.PROJECT, next.atomId(), next.ref()));
        texts.put(next.atomId(), text);
        live.computeIfAbsent(next.atomId(), k -> new TreeMap<>())
                .put(ExperienceRefCodec.canonical(next.ref()), next.ref());
        projected.add(next.atomId());
    }

    static String atomId(String text) {
        return KnowledgeAtom.text(text).id();
    }

    /** Refs currently represented by {@code atomId}, in canonical order; empty when none. */
    List<ExperienceRef> refs(String atomId) {
        TreeMap<String, ExperienceRef> refs = live.get(atomId);
        return refs == null ? List.of() : List.copyOf(refs.values());
    }

    SortedMap<String, List<ExperienceRef>> refsByAtom() {
        TreeMap<String, List<ExperienceRef>> out = new TreeMap<>();
        live.forEach((atom, refs) -> out.put(atom, List.copyOf(refs.values())));
        return out;
    }

    /** Atoms that were projected and no longer represent any ref. */
    SortedSet<String> retired() {
        TreeSet<String> out = new TreeSet<>(projected);
        out.removeAll(live.keySet());
        return out;
    }
}
