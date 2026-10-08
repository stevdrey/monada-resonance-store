package com.monada.core.execution;

/** Opaque, caller-issued task identifier. Never generated or derived from content by the library. */
public record TaskId(String value) {
    public TaskId {
        value = Validation.identifier(value, "taskId");
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
