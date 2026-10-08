package com.monada.storage.execution;

import com.monada.core.execution.ExecutionEvent;
import java.util.Objects;

/** One valid ledger entry: the ledger-assigned per-scope sequence and the decoded event. */
public record LedgerRecord(long sequence, ExecutionEvent event) {
    public LedgerRecord {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1");
        }
        Objects.requireNonNull(event, "event");
    }
}
