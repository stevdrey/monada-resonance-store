package com.monada.evaluation.execution;

import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.Correction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a recorded history into effective events: deduplicated by event identity (never by content),
 * with every correction replacing the revision it supersedes so a fact is seen exactly once.
 */
final class ChainResolver {

    /**
     * {@code sequence} is the ledger sequence of the latest revision (the correction's own); {@code
     * originalSequence} is the sequence of the first revision of the same fact. {@code event} is the
     * unwrapped effective payload.
     */
    record Effective(long sequence, long originalSequence, EventId id, ExecutionEvent event) {
    }

    record Resolved(List<Effective> effective, List<EventId> ignoredDuplicates) {
    }

    private ChainResolver() {
    }

    static Resolved resolve(List<HistoryEntry> history) {
        List<HistoryEntry> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparingLong(HistoryEntry::sequence));
        Set<EventId> seen = new HashSet<>();
        List<HistoryEntry> kept = new ArrayList<>();
        List<EventId> ignored = new ArrayList<>();
        for (HistoryEntry entry : sorted) {
            if (seen.add(entry.event().eventId())) {
                kept.add(entry);
            } else {
                ignored.add(entry.event().eventId());
            }
        }
        Map<EventId, Long> originalSequence = new HashMap<>();
        Set<EventId> superseded = new HashSet<>();
        for (HistoryEntry entry : kept) {
            if (entry.event() instanceof Correction c) {
                EventId target = c.envelope().supersedes().orElseThrow();
                superseded.add(target);
                originalSequence.put(c.eventId(), originalSequence.getOrDefault(target, entry.sequence()));
            } else {
                originalSequence.put(entry.event().eventId(), entry.sequence());
            }
        }
        List<Effective> effective = new ArrayList<>();
        for (HistoryEntry entry : kept) {
            EventId id = entry.event().eventId();
            if (superseded.contains(id)) {
                continue;
            }
            ExecutionEvent event = entry.event() instanceof Correction c ? c.replacement() : entry.event();
            effective.add(new Effective(entry.sequence(), originalSequence.get(id), id, event));
        }
        return new Resolved(List.copyOf(effective), List.copyOf(ignored));
    }
}
