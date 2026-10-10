package com.monada.api.execution;

import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.storage.execution.LedgerRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory index of one scope's ledger, built once at open and extended per appended event, so reads never
 * copy or rescan the whole ledger. Sequences are contiguous from 1.
 */
final class HistoryIndex {
    private final List<HistoryEntry> all = new ArrayList<>();
    private final Map<ExecutionId, List<HistoryEntry>> byExecution = new HashMap<>();
    private final Map<EventId, HistoryEntry> byId = new HashMap<>();
    private final Map<EventId, HistoryEntry> successor = new HashMap<>();

    static HistoryIndex of(List<LedgerRecord> records) {
        HistoryIndex index = new HistoryIndex();
        records.forEach(r -> index.add(r.sequence(), r.event()));
        return index;
    }

    void add(long sequence, ExecutionEvent event) {
        HistoryEntry entry = new HistoryEntry(sequence, event);
        all.add(entry);
        byExecution.computeIfAbsent(event.executionId(), k -> new ArrayList<>()).add(entry);
        byId.put(event.eventId(), entry);
        event.envelope().supersedes().ifPresent(target -> successor.put(target, entry));
    }

    /** All entries in sequence order; callers must not modify it. */
    List<HistoryEntry> all() {
        return all;
    }

    List<HistoryEntry> execution(ExecutionId id) {
        return byExecution.getOrDefault(id, List.of());
    }

    Optional<HistoryEntry> find(EventId id) {
        return Optional.ofNullable(byId.get(id));
    }

    Optional<HistoryEntry> successorOf(EventId id) {
        return Optional.ofNullable(successor.get(id));
    }
}
