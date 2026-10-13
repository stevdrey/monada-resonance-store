package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether an execution may take part in a validated winner or a Pareto front. It must be a validated acceptance
 * with a derivable cutoff and every caller-mandatory gate needs an explicit {@code PASS} or a justified {@code
 * NOT_APPLICABLE}. {@code FAIL}, {@code UNKNOWN} and missing evidence exclude it. An accepted outcome alone
 * never makes an execution eligible, and low cost never compensates for an exclusion.
 */
public record Eligibility(List<Exclusion> exclusions) {

    public enum Code { NOT_VALIDATED_ACCEPTED, CUTOFF_UNDERIVABLE, GATE_FAIL, GATE_UNKNOWN, GATE_MISSING }

    public record Exclusion(Code code, Optional<QualityDimension> dimension, String detail) {
        public Exclusion {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public Eligibility {
        exclusions = List.copyOf(exclusions);
    }

    public boolean eligible() {
        return exclusions.isEmpty();
    }

    static Eligibility assess(ExecutionSummary summary, Set<QualityDimension> mandatoryGates) {
        List<Exclusion> out = new ArrayList<>();
        if (!summary.acceptance().isValidatedAcceptance()) {
            DerivedAcceptance a = summary.acceptance();
            out.add(new Exclusion(Code.NOT_VALIDATED_ACCEPTED, Optional.empty(),
                    a.status() + (a.reasons().isEmpty() ? "" : " " + a.reasons())));
        } else if (summary.acceptanceCutoff().isEmpty()) {
            out.add(new Exclusion(Code.CUTOFF_UNDERIVABLE, Optional.empty(),
                    "the acceptance cutoff cannot be derived from the supplied history"));
        }
        for (GateEvidence gate : summary.gates()) {
            if (!mandatoryGates.contains(gate.dimension())) {
                continue;
            }
            if (gate.effective().isEmpty()) {
                out.add(new Exclusion(Code.GATE_MISSING, Optional.of(gate.dimension()),
                        gate.absentReason().orElse("no evidence")));
                continue;
            }
            QualityObservation o = gate.effective().get().observation();
            String who = o.evaluatorId() + "@" + o.evaluatorVersion() + " (" + o.source() + ")";
            if (o.state() == ObservationState.FAIL) {
                out.add(new Exclusion(Code.GATE_FAIL, Optional.of(gate.dimension()), "FAIL by " + who));
            } else if (o.state() == ObservationState.UNKNOWN) {
                out.add(new Exclusion(Code.GATE_UNKNOWN, Optional.of(gate.dimension()), "UNKNOWN by " + who));
            }
        }
        return new Eligibility(out);
    }
}
