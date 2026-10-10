package com.monada.api.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of {@link ExecutionMemory#record}. {@code sequence} is the ledger sequence of the stored event for
 * {@code APPENDED} and {@code IDEMPOTENT} (0 for {@code CONFLICT}); {@code reason} is present for
 * {@code CONFLICT}. A conflict writes nothing.
 */
public record RecordResult(Status status, long sequence, Optional<String> reason) {

    public enum Status { APPENDED, IDEMPOTENT, CONFLICT }

    public RecordResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
    }
}
