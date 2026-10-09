package com.monada.api.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One bounded page of ledger records in ascending sequence order (all revisions included).
 * {@code next} is present exactly when {@code hasMore}; re-using a cursor always yields the same page.
 */
public record HistoryPage(List<HistoryEntry> entries, long highWatermark, boolean hasMore,
                          Optional<HistoryCursor> next) {
    public HistoryPage {
        entries = List.copyOf(entries);
        Objects.requireNonNull(next, "next");
        if (hasMore != next.isPresent()) {
            throw new IllegalArgumentException("next cursor is present exactly when hasMore");
        }
    }
}
