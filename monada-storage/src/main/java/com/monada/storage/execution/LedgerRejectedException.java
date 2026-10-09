package com.monada.storage.execution;

import java.util.Objects;

/**
 * An event is invalid for this ledger (wrong scope, missing reference, impossible order, oversized
 * record). Thrown before any I/O: the ledger is unchanged.
 */
public final class LedgerRejectedException extends IllegalArgumentException {
    private final LedgerDiagnosticCategory category;

    public LedgerRejectedException(LedgerDiagnosticCategory category, String message) {
        super("[" + Objects.requireNonNull(category, "category") + "] " + message);
        this.category = category;
    }

    public LedgerDiagnosticCategory category() {
        return category;
    }
}
