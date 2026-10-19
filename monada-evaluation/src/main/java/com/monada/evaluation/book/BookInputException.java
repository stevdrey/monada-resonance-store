package com.monada.evaluation.book;

import java.util.Objects;

/**
 * A book chunking input was rejected: either the manifest is unusable ({@link Reason#BAD_MANIFEST}) or the body
 * bytes are not the verified normalized text the manifest pins ({@link Reason#INVALID_INPUT}). Never thrown for a
 * chunker bug; those are {@link IllegalStateException}s.
 */
public final class BookInputException extends Exception {
    /** Why the input was rejected; the command line maps it to an exit code. */
    public enum Reason {
        BAD_MANIFEST,
        INVALID_INPUT
    }

    private final Reason reason;

    public BookInputException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
