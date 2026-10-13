package com.monada.evaluation.execution;

import com.monada.api.execution.DerivedAcceptance;
import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.OutcomeStatus;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.ScopeId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Checks that an {@link ExecutionView} says what its own history says, because the view's constructor is public.
 * The history must obey the ledger's ordering and reference rules ({@link HistoryStructure}). Scope, execution id
 * and task are taken from every event; task, provenance, policy, current outcome and derived
 * acceptance are re-derived from the effective events with the rules of contract section 8 and must be equal.
 * A comparison trusts these fields for comparability and eligibility, so a substituted one is rejected.
 */
final class ViewVerifier {

    private ViewVerifier() {
    }

    static void verify(ScopeId scope, ExecutionView view) {
        if (view.history().isEmpty()) {
            throw new IllegalArgumentException("the view has no history, so it cannot be verified");
        }
        for (HistoryEntry entry : view.history()) {
            if (!entry.event().scopeId().equals(scope) || !entry.event().executionId().equals(view.id())) {
                throw new IllegalArgumentException("the view's history (event " + entry.event().eventId() + ", scope "
                        + entry.event().scopeId() + ", execution " + entry.event().executionId()
                        + ") does not belong to " + scope + "/" + view.id());
            }
            if (!entry.event().envelope().taskId().equals(view.task())) {
                throw new IllegalArgumentException("event " + entry.event().eventId() + " belongs to task "
                        + entry.event().envelope().taskId() + ", not to " + view.task());
            }
        }
        HistoryStructure.verify(view.history());
        ExecutionStarted started = null;
        Optional<Outcome> outcome = Optional.empty();
        Map<AttemptId, AttemptFinished> finishes = new HashMap<>();
        Map<AttemptId, List<EvidenceRecorded>> evidence = new HashMap<>();
        for (ChainResolver.Effective e : ChainResolver.resolve(view.history()).effective()) {
            switch (e.event()) {
                case ExecutionStarted s -> started = s;
                case OutcomeRecorded o -> outcome = Optional.of(o.outcome());
                case AttemptFinished f -> finishes.put(f.envelope().attemptId().orElseThrow(), f);
                case EvidenceRecorded ev ->
                        evidence.computeIfAbsent(ev.envelope().attemptId().orElseThrow(), k -> new ArrayList<>()).add(ev);
                default -> {
                }
            }
        }
        if (started == null) {
            throw new IllegalArgumentException("the view's history has no EXECUTION_STARTED event");
        }
        require(started.envelope().taskId().equals(view.task()), "task", view);
        require(started.provenance().equals(view.provenance()), "provenance", view);
        require(started.policy().equals(view.policy()), "evaluation policy", view);
        require(outcome.equals(view.currentOutcome()), "current outcome", view);
        require(derive(started.policy(), outcome, finishes, evidence).equals(view.acceptance()),
                "derived acceptance", view);
    }

    private static void require(boolean consistent, String field, ExecutionView view) {
        if (!consistent) {
            throw new IllegalArgumentException("the view's " + field + " does not match its own history for "
                    + view.id());
        }
    }

    /** Same rules as the facade's derivation (contract section 8); {@code ChainScopeGatesAndParityTest} guards the history reading. */
    private static DerivedAcceptance derive(EvaluationPolicy policy, Optional<Outcome> outcome,
                                            Map<AttemptId, AttemptFinished> finishes,
                                            Map<AttemptId, List<EvidenceRecorded>> evidence) {
        if (outcome.isEmpty() || outcome.get().status() == OutcomeStatus.PENDING) {
            return new DerivedAcceptance(DerivedAcceptance.Status.PENDING, List.of());
        }
        Outcome o = outcome.get();
        if (o.status() != OutcomeStatus.ACCEPTED) {
            return new DerivedAcceptance(DerivedAcceptance.Status.NOT_ACCEPTED, List.of("outcome is " + o.status()));
        }
        AttemptId accepted = o.acceptedAttempt().orElseThrow();
        Map<QualityDimension, QualityObservation> effective = new EnumMap<>(QualityDimension.class);
        for (EvidenceRecorded e : evidence.getOrDefault(accepted, List.of())) {
            for (QualityObservation obs : e.observations()) {
                if (obs.policyId().equals(policy.id()) && obs.policyVersion().equals(policy.version())) {
                    effective.put(obs.dimension(), obs);
                }
            }
        }
        List<String> reasons = new ArrayList<>();
        AttemptFinished finish = finishes.get(accepted);
        if (finish == null) {
            reasons.add("accepted attempt is not finished");
        } else if (finish.result() != AttemptResult.COMPLETED) {
            reasons.add("accepted attempt is " + finish.result());
        }
        if (o.origin() == OutcomeOrigin.IMPORTED_CLAIM) {
            reasons.add("outcome is an imported claim, not a validated outcome");
        }
        for (QualityDimension d : new TreeSet<>(policy.mandatoryDimensions())) {
            QualityObservation obs = effective.get(d);
            if (obs == null) {
                reasons.add(d + ": no applicable evidence");
            } else if (obs.state() == ObservationState.FAIL || obs.state() == ObservationState.UNKNOWN) {
                reasons.add(d + ": " + obs.state());
            }
        }
        return new DerivedAcceptance(reasons.isEmpty() ? DerivedAcceptance.Status.VALIDATED_ACCEPTED
                : DerivedAcceptance.Status.ACCEPTED_UNVALIDATED, reasons);
    }
}
