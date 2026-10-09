package com.monada.storage.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of {@link ExecutionLedger#append}. {@code sequence} is present for {@code APPENDED} and
 * {@code IDEMPOTENT}; {@code reason} is present for {@code CONFLICT}.
 */
public record AppendResult(Status status, long sequence, Optional<String> reason) {

    public enum Status { APPENDED, IDEMPOTENT, CONFLICT }

    public AppendResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
    }

    static AppendResult appended(long sequence) {
        return new AppendResult(Status.APPENDED, sequence, Optional.empty());
    }

    static AppendResult idempotent(long sequence) {
        return new AppendResult(Status.IDEMPOTENT, sequence, Optional.empty());
    }

    static AppendResult conflict(String reason) {
        return new AppendResult(Status.CONFLICT, 0, Optional.of(reason));
    }
}
