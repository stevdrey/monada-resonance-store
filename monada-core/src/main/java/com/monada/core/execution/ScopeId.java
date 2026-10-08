package com.monada.core.execution;

/** Opaque, caller-issued scope identifier. Never generated or derived from content by the library. */
public record ScopeId(String value) {
    public ScopeId {
        value = Validation.identifier(value, "scopeId");
    }

    public static ScopeId of(String value) {
        return new ScopeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
