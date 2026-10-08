package com.monada.core.execution;

/** Descriptive (not authoritative) provenance recorded when an execution starts. Opaque to the library. */
public record SourceProvenance(String sourceRevision, String contextFingerprint, String constraintsFingerprint) {
    public SourceProvenance {
        sourceRevision = Validation.opaque(sourceRevision, "sourceRevision");
        contextFingerprint = Validation.opaque(contextFingerprint, "contextFingerprint");
        constraintsFingerprint = Validation.opaque(constraintsFingerprint, "constraintsFingerprint");
    }
}
