package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One entry per quality dimension, built from the accepted attempt's applicable evidence (contract section 8):
 * same policy id and version, latest revision only, the highest ledger sequence wins.
 */
final class GateEvidenceCollector {

    private GateEvidenceCollector() {
    }

    static Map<QualityDimension, GateEvidence.Observed> effective(List<ChainResolver.Effective> events,
                                                                  Optional<AttemptId> acceptedAttempt,
                                                                  EvaluationPolicy policy) {
        Map<QualityDimension, GateEvidence.Observed> result = new EnumMap<>(QualityDimension.class);
        if (acceptedAttempt.isEmpty()) {
            return result;
        }
        for (ChainResolver.Effective e : events) {
            if (e.event() instanceof EvidenceRecorded evidence
                    && evidence.envelope().attemptId().equals(acceptedAttempt)) {
                for (QualityObservation obs : evidence.observations()) {
                    if (obs.policyId().equals(policy.id()) && obs.policyVersion().equals(policy.version())) {
                        result.put(obs.dimension(), new GateEvidence.Observed(obs, e.sequence()));
                    }
                }
            }
        }
        return result;
    }

    static List<GateEvidence> collect(Map<QualityDimension, GateEvidence.Observed> effective,
                                      Optional<AttemptId> acceptedAttempt, EvaluationPolicy policy) {
        List<GateEvidence> gates = new ArrayList<>();
        for (QualityDimension d : QualityDimension.values()) {
            boolean mandatory = policy.mandatoryDimensions().contains(d);
            GateEvidence.Observed observed = effective.get(d);
            if (observed != null) {
                gates.add(new GateEvidence(d, mandatory, Optional.of(observed), Optional.empty()));
            } else {
                String reason = acceptedAttempt.isEmpty() ? "no accepted attempt"
                        : "no applicable evidence on the accepted attempt under policy " + policy.id() + " v"
                                + policy.version();
                gates.add(new GateEvidence(d, mandatory, Optional.empty(), Optional.of(reason)));
            }
        }
        return List.copyOf(gates);
    }
}
