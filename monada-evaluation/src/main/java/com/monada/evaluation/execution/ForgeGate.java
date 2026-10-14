package com.monada.evaluation.execution;

import java.util.List;
import java.util.Objects;

/**
 * One hard correctness or isolation gate of the Forge experience evaluation. A gate is a yes/no property of the
 * public API's behaviour; it never depends on retrieval quality.
 */
public record ForgeGate(String id, String name, boolean passed, List<String> details) {
    public static final String SCOPE_ISOLATION = "G1-SCOPE-ISOLATION";
    public static final String NO_INVALID_SUCCESS = "G2-NO-INVALID-SUCCESS";
    public static final String EXACT_REFERENCES = "G3-EXACT-REFERENCES";
    public static final String REPLAY_IDEMPOTENCY = "G4-REPLAY-IDEMPOTENCY";
    public static final String RESOURCE_ACCOUNTING = "G5-RESOURCE-ACCOUNTING";
    public static final String COMPARISON = "G6-COMPARISON";
    public static final String DETERMINISM = "G7-DETERMINISM";

    public ForgeGate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        details = List.copyOf(details);
    }

    /** A gate passes exactly when it collected no violation; {@code evidence} lines are always kept. */
    static ForgeGate of(String id, String name, List<String> violations, List<String> evidence) {
        List<String> details = new java.util.ArrayList<>(evidence);
        violations.forEach(v -> details.add("VIOLATION: " + v));
        return new ForgeGate(id, name, violations.isEmpty(), details);
    }
}
