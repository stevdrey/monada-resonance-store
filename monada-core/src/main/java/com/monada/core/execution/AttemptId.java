package com.monada.core.execution;

/** Opaque, caller-issued attempt identifier. Never generated or derived from content by the library. */
public record AttemptId(String value) {
    public AttemptId {
        value = Validation.identifier(value, "attemptId");
    }

    public static AttemptId of(String value) {
        return new AttemptId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
