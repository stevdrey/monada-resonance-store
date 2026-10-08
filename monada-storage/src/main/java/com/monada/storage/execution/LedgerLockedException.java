package com.monada.storage.execution;

/** Another writable owner already holds this ledger. The ledger never waits and has no multi-writer mode. */
public final class LedgerLockedException extends LedgerException {
    public LedgerLockedException(String message) {
        super(message);
    }
}
