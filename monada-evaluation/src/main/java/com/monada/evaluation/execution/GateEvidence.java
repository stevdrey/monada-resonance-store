package com.monada.evaluation.execution;

import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import java.util.Objects;
import java.util.Optional;

/**
 * Effective evidence of one quality dimension for the accepted attempt under the execution's policy, rendered
 * with its provenance (evaluator, policy, source, measured value, artifacts). There is no aggregate score.
 */
public record GateEvidence(QualityDimension dimension, boolean mandatory, Optional<Observed> effective,
                           Optional<String> absentReason) {

    /** The effective observation and the ledger sequence of the event that carries its latest revision. */
    public record Observed(QualityObservation observation, long sequence) {
        public Observed {
            Objects.requireNonNull(observation, "observation");
        }
    }

    public GateEvidence {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(absentReason, "absentReason");
        if (effective.isPresent() == absentReason.isPresent()) {
            throw new IllegalArgumentException("exactly one of effective and absentReason must be present");
        }
    }
}
