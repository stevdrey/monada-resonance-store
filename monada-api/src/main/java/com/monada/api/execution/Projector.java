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
import java.io.IOException;
import java.io.UncheckedIOException;
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

    /** Checkpoint entries for one ledger event and the text of every atom it projects, by atom ID (tests). */
    record Step(List<Entry> entries, Map<String, String> texts) {
    }

    /**
     * Receives the entries of one ledger event as they are produced, in order; {@code text} is the projection
     * text for a {@code PROJECT} entry and null for a {@code RETIRE}. Nothing is buffered per event, so a
     * correction touching any number of attempts uses bounded memory beyond the projector's own state.
     */
    interface Sink {
        Sink NONE = (entry, text) -> { };

        void accept(Entry entry, String text) throws IOException;
    }

    /** Collecting form for tests: materializes the event's entries. */
    Step apply(ExecutionEvent event) {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        try {
            apply(event, (entry, text) -> {
                entries.add(entry);
                if (text != null) {
                    texts.put(entry.atomId(), text);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen: the collecting sink does no I/O
        }
        return new Step(List.copyOf(entries), Collections.unmodifiableMap(texts));
    }

    private final Map<ExecutionId, String> taskSummaries = new HashMap<>();
    private final Map<ExecutionId, LinkedHashMap<AttemptId, Experience>> experiences = new HashMap<>();
    private final TreeMap<String, TreeMap<String, ExperienceRef>> live = new TreeMap<>();
    private final Set<String> projected = new HashSet<>();
    private final TreeMap<String, String> liveTexts = new TreeMap<>();
    private final Map<String, String> textOf = new HashMap<>(); // every atom ever projected; never pruned

    /** Projects {@code event}, streaming its entries to {@code sink}. */
    void apply(ExecutionEvent event, Sink sink) throws IOException {
        ExecutionEvent effective = event instanceof Correction c ? c.replacement() : event;
        switch (effective) {
            case ExecutionStarted s -> {
                taskSummaries.put(s.executionId(), s.taskSummary());
                for (Map.Entry<AttemptId, Experience> e : experiences
                        .getOrDefault(s.executionId(), new LinkedHashMap<>()).entrySet()) {
                    Experience old = e.getValue();
                    String text = ProjectionText.of(s.taskSummary(), old.solution(), old.lesson());
                    String atomId = atomId(text);
                    if (!atomId.equals(old.atomId())) {
                        Experience next = new Experience(old.ref(), old.solution(), old.lesson(), atomId);
                        e.setValue(next);
                        move(sink, old, next, text);
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
                move(sink, old, next, text);
            }
            default -> {
                // Other facts never change projected text.
            }
        }
    }

    private void move(Sink sink, Experience old, Experience next, String text) throws IOException {
        if (old != null) {
            sink.accept(new Entry(EntryKind.RETIRE, old.atomId(), old.ref()), null);
            TreeMap<String, ExperienceRef> refs = live.get(old.atomId());
            refs.remove(ExperienceRefCodec.canonical(old.ref()));
            if (refs.isEmpty()) {
                live.remove(old.atomId());
                liveTexts.remove(old.atomId());
            }
        }
        sink.accept(new Entry(EntryKind.PROJECT, next.atomId(), next.ref()), text);
        projected.add(next.atomId());
        liveTexts.put(next.atomId(), text);
        textOf.put(next.atomId(), text);
        live.computeIfAbsent(next.atomId(), k -> new TreeMap<>())
                .put(ExperienceRefCodec.canonical(next.ref()), next.ref());
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

    /** Projection text of any atom this projector ever projected (live or retired), or null. */
    String textOf(String atomId) {
        return textOf.get(atomId);
    }

    /** Text of every live atom, in atom ID order: what a compacted rebuild stores. */
    SortedMap<String, String> liveTexts() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(liveTexts));
    }

    /** Atoms that were projected and no longer represent any ref. */
    SortedSet<String> retired() {
        TreeSet<String> out = new TreeSet<>(projected);
        out.removeAll(live.keySet());
        return out;
    }
}
