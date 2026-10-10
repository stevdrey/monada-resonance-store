package com.monada.api.execution;

import com.monada.core.execution.ExecutionEvent;
import java.util.Objects;

/** One ledger entry as seen through the facade: the per-scope sequence and the recorded event. */
public record HistoryEntry(long sequence, ExecutionEvent event) {
    public HistoryEntry {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1");
        }
        Objects.requireNonNull(event, "event");
    }
}
