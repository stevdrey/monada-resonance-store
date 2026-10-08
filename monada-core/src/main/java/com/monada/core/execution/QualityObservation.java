package com.monada.core.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One typed quality observation for a single dimension. Preserves evaluator, policy, source and
 * evidence references; it makes no acceptance or routing decision.
 *
 * <p>{@code measuredValue} is optional in any state, must be finite and non-negative, and requires a
 * unit. {@code NOT_APPLICABLE} requires a justification.
 */
public record QualityObservation(
        QualityDimension dimension,
        ObservationState state,
        OptionalDouble measuredValue,
        Optional<String> unit,
        String evaluatorId,
        String evaluatorVersion,
        String policyId,
        String policyVersion,
        ObservationSource source,
        List<ArtifactRef> evidence,
        Optional<String> justification
) {
    public QualityObservation {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(measuredValue, "measuredValue");
        Objects.requireNonNull(unit, "unit");
        evaluatorId = Validation.opaque(evaluatorId, "evaluatorId");
        evaluatorVersion = Validation.opaque(evaluatorVersion, "evaluatorVersion");
        policyId = Validation.opaque(policyId, "policyId");
        policyVersion = Validation.opaque(policyVersion, "policyVersion");
        Objects.requireNonNull(source, "source");
        evidence = Validation.boundedList(evidence, "evidence", ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT);
        Objects.requireNonNull(justification, "justification");
        justification = justification.map(j -> Validation.summary(j, "justification"));
        unit = unit.map(u -> Validation.opaque(u, "unit"));
        if (measuredValue.isPresent()) {
            Validation.finiteNonNegative(measuredValue.getAsDouble(), "measuredValue");
            if (unit.isEmpty()) {
                throw new IllegalArgumentException("measuredValue requires a unit");
            }
        } else if (unit.isPresent()) {
            throw new IllegalArgumentException("unit requires a measuredValue");
        }
        if (state == ObservationState.NOT_APPLICABLE && justification.isEmpty()) {
            throw new IllegalArgumentException("NOT_APPLICABLE requires a justification");
        }
    }

    /** Observation without measured value or justification. */
    public static QualityObservation of(QualityDimension dimension, ObservationState state,
                                        String evaluatorId, String evaluatorVersion,
                                        String policyId, String policyVersion,
                                        ObservationSource source) {
        return new QualityObservation(dimension, state, OptionalDouble.empty(), Optional.empty(),
                evaluatorId, evaluatorVersion, policyId, policyVersion, source, List.of(), Optional.empty());
    }
}
