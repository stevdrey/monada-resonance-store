package com.monada.storage.execution;

import java.io.IOException;
import java.util.List;

/**
 * A ledger cannot be opened or used: unsupported manifest, escaping path, corruption on a writable open
 * or an unusable instance. Carries the diagnostics that explain why, when there are any.
 */
public class LedgerException extends IOException {
    private final List<LedgerDiagnostic> diagnostics;

    public LedgerException(String message) {
        this(message, List.of());
    }

    public LedgerException(String message, List<LedgerDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<LedgerDiagnostic> diagnostics() {
        return diagnostics;
    }
}
