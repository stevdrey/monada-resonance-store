package com.monada.core.execution;

/** Opaque, caller-issued event identifier. Never generated or derived from content by the library. */
public record EventId(String value) {
    public EventId {
        value = Validation.identifier(value, "eventId");
    }

    public static EventId of(String value) {
        return new EventId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
