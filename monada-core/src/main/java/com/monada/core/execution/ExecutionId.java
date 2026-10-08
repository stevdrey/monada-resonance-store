package com.monada.core.execution;

/** Opaque, caller-issued execution identifier. Never generated or derived from content by the library. */
public record ExecutionId(String value) {
    public ExecutionId {
        value = Validation.identifier(value, "executionId");
    }

    public static ExecutionId of(String value) {
        return new ExecutionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
