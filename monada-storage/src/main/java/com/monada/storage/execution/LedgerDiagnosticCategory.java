package com.monada.storage.execution;

/** Diagnostic taxonomy of the execution ledger (contract section 6). */
public enum LedgerDiagnosticCategory {
    MANIFEST_MISSING,
    MANIFEST_INVALID,
    UNSUPPORTED_VERSION,
    SCOPE_ID_MISMATCH,
    UNSUPPORTED_SCHEMA,
    MALFORMED_RECORD,
    DIGEST_MISMATCH,
    OVERSIZED_RECORD,
    SEQUENCE_GAP,
    DUPLICATE_SEQUENCE,
    CONFLICTING_DUPLICATE_EVENT,
    DANGLING_REFERENCE,
    INVALID_ORDER,
    TORN_TAIL,
    PATH_ESCAPE
}
