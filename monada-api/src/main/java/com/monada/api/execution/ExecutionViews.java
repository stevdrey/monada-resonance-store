package com.monada.api.execution;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.storage.execution.LedgerRecord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic replay of ledger records into views and derived acceptance (contract sections 6 and 8). */
final class ExecutionViews {
    private ExecutionViews() {
    }

    static Optional<ExecutionView> execution(List<LedgerRecord> all, ExecutionId id) {
        List<LedgerRecord> history = all.stream().filter(r -> r.event().executionId().equals(id)).toList();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        Set<EventId> superseded = new HashSet<>();
        for (LedgerRecord r : history) {
            if (r.event() instanceof Correction c) {
                superseded.add(c.envelope().supersedes().get());
            }
        }
        ExecutionStarted started = null;
        Optional<Outcome> outcome = Optional.empty();
        Map<AttemptId, AttemptStarted> starts = new LinkedHashMap<>();
        Map<AttemptId, AttemptFinished> finishes = new LinkedHashMap<>();
        Map<AttemptId, List<StageRecorded>> stages = new LinkedHashMap<>();
        Map<AttemptId, List<EvidenceRecorded>> evidence = new LinkedHashMap<>();
        for (LedgerRecord r : history) {
            if (superseded.contains(r.event().eventId())) {
                continue; // an older revision: kept in history, replaced here by its latest revision
            }
            ExecutionEvent effective = r.event() instanceof Correction c ? c.replacement() : r.event();
            switch (effective) {
                case ExecutionStarted e -> started = e;
                case OutcomeRecorded e -> outcome = Optional.of(e.outcome());
                case AttemptStarted e -> starts.put(e.envelope().attemptId().get(), e);
                case AttemptFinished e -> finishes.put(e.envelope().attemptId().get(), e);
                case StageRecorded e ->
                        stages.computeIfAbsent(e.envelope().attemptId().get(), k -> new ArrayList<>()).add(e);
                case EvidenceRecorded e ->
                        evidence.computeIfAbsent(e.envelope().attemptId().get(), k -> new ArrayList<>()).add(e);
                case Correction e -> throw new IllegalStateException("nested correction");
            }
        }
        if (started == null) {
            throw new IllegalStateException("execution " + id + " has no EXECUTION_STARTED event");
        }
        List<AttemptView> attempts = new ArrayList<>();
        starts.forEach((attempt, s) -> {
            Optional<AttemptFinished> f = Optional.ofNullable(finishes.get(attempt));
            attempts.add(new AttemptView(attempt, s.ordinal(), s.reason(), s.previousAttempt(),
                    f.map(AttemptFinished::result), f.flatMap(AttemptFinished::solutionSummary),
                    f.flatMap(AttemptFinished::lessonSummary), stages.getOrDefault(attempt, List.of()),
                    evidence.getOrDefault(attempt, List.of())));
        });
        DerivedAcceptance acceptance = derive(started.policy(), outcome, evidence);
        return Optional.of(new ExecutionView(id, started.envelope().taskId(), started.taskSummary(),
                started.provenance(), started.policy(), outcome, acceptance, attempts, history));
    }

    /** Contract section 8: only the accepted attempt's evidence under the execution's own policy counts. */
    private static DerivedAcceptance derive(EvaluationPolicy policy, Optional<Outcome> outcome,
                                            Map<AttemptId, List<EvidenceRecorded>> evidence) {
        if (outcome.isEmpty() || outcome.get().status() == com.monada.core.execution.OutcomeStatus.PENDING) {
            return new DerivedAcceptance(DerivedAcceptance.Status.PENDING, List.of());
        }
        Outcome o = outcome.get();
        switch (o.status()) {
            case REJECTED, FAILED, CANCELLED -> {
                return new DerivedAcceptance(DerivedAcceptance.Status.NOT_ACCEPTED,
                        List.of("outcome is " + o.status()));
            }
            default -> {
            }
        }
        Map<QualityDimension, QualityObservation> effective = new EnumMap<>(QualityDimension.class);
        for (EvidenceRecorded e : evidence.getOrDefault(o.acceptedAttempt().get(), List.of())) {
            for (QualityObservation obs : e.observations()) {
                if (obs.policyId().equals(policy.id()) && obs.policyVersion().equals(policy.version())) {
                    effective.put(obs.dimension(), obs); // later evidence overrides earlier
                }
            }
        }
        List<String> reasons = new ArrayList<>();
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
