package com.monada.core.execution;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Caller-owned evaluation policy identity plus the dimensions it declares mandatory. */
public record EvaluationPolicy(String id, String version, Set<QualityDimension> mandatoryDimensions) {
    public EvaluationPolicy {
        id = Validation.opaque(id, "policy id");
        version = Validation.opaque(version, "policy version");
        Objects.requireNonNull(mandatoryDimensions, "mandatoryDimensions");
        mandatoryDimensions = mandatoryDimensions.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(mandatoryDimensions));
    }
}
