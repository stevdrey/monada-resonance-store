package com.monada.api.execution;

import com.monada.core.execution.QualityDimension;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Evidence rules the host applies to a sample export. The execution's own evaluation policy always applies;
 * {@code additionalGates} are further dimensions that must hold {@code PASS} (or a justified
 * {@code NOT_APPLICABLE}) before a sample can be labelled {@code SUCCESS}. Gates can only tighten a label, never
 * waive a mandatory dimension of the execution's policy, and measured values never satisfy a gate.
 */
public record ExportEvidencePolicy(Set<QualityDimension> additionalGates) {
    public ExportEvidencePolicy {
        Objects.requireNonNull(additionalGates, "additionalGates");
        additionalGates = additionalGates.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(additionalGates));
    }

    /** Only the mandatory dimensions of each execution's own policy. */
    public static ExportEvidencePolicy executionPolicyOnly() {
        return new ExportEvidencePolicy(Set.of());
    }

    public static ExportEvidencePolicy requiring(QualityDimension first, QualityDimension... more) {
        EnumSet<QualityDimension> gates = EnumSet.of(first, more);
        return new ExportEvidencePolicy(gates);
    }
}
