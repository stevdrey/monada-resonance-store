package com.monada.api.execution;

import com.monada.api.execution.EffectiveFacts.Fact;
import com.monada.api.execution.ExperienceSample.Diagnostics;
import com.monada.api.execution.ExperienceSample.Eligibility;
import com.monada.api.execution.ExperienceSample.EligibilityCode;
import com.monada.api.execution.ExperienceSample.Gate;
import com.monada.api.execution.ExperienceSample.GateState;
import com.monada.api.execution.ExperienceSample.Group;
import com.monada.api.execution.ExperienceSample.IgnoredObservation;
import com.monada.api.execution.ExperienceSample.Label;
import com.monada.api.execution.ExperienceSample.OutcomeFact;
import com.monada.api.execution.ExperienceSample.Reason;
import com.monada.api.execution.ExperienceSample.SampleAttempt;
import com.monada.api.execution.ExperienceSample.SampleEvidence;
import com.monada.api.execution.ExperienceSample.SampleObservation;
import com.monada.api.execution.ExperienceSample.SampleStage;
import com.monada.api.execution.ExperienceSample.SampleUsage;
import com.monada.api.execution.ExperienceSample.UsageState;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.ExecutionStarted;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.OutcomeStatus;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Derives one {@link ExperienceSample} from the ledger entries of one execution up to the export cutoff. Facts come
 * from {@link EffectiveFacts}, the same resolution {@link ExecutionViews} uses, and the derived acceptance is taken
 * from {@link ExecutionViews} itself. If the sample's own label derivation ever disagrees with it, the sample is
 * exported {@code UNLABELED} with an {@code INCONSISTENT_DERIVATION} reason instead of failing the whole page.
 */
final class SampleBuilder {
    private SampleBuilder() {
    }

    /** {@code history}: every entry of the execution with sequence at most the cutoff, in sequence order. */
    static ExperienceSample build(ScopeId scope, ExecutionId id, List<HistoryEntry> history, ExportView view,
                                  Set<QualityDimension> additionalGates) {
        for (HistoryEntry h : history) {
            if (!h.event().scopeId().equals(scope)) {
                throw new IllegalStateException("event " + h.event().eventId() + " belongs to scope "
                        + h.event().scopeId() + ", not " + scope);
            }
        }
        ExecutionView ev = ExecutionViews.execution(history, id).orElseThrow();
        EffectiveFacts facts = new EffectiveFacts(history);
        Fact started = facts.started();
        ExecutionStarted start = (ExecutionStarted) started.event();

        List<Fact> outcomes = facts.outcomes();
        Optional<Fact> current = outcomes.isEmpty() ? Optional.empty() : Optional.of(outcomes.get(outcomes.size() - 1));
        Optional<Outcome> outcome = current.map(f -> ((OutcomeRecorded) f.event()).outcome());
        Optional<AttemptId> acceptedAttempt = outcome.flatMap(Outcome::acceptedAttempt);

        EnumSet<QualityDimension> required = EnumSet.copyOf(start.policy().mandatoryDimensions());
        required.addAll(additionalGates);

        // Applicable evidence of the accepted attempt: later applicable observations override earlier ones.
        Map<QualityDimension, QualityObservation> effectiveObs = new EnumMap<>(QualityDimension.class);
        Map<QualityDimension, Fact> effectiveFact = new EnumMap<>(QualityDimension.class);
        for (Map.Entry<AttemptId, List<Fact>> e : facts.evidence().entrySet()) {
            if (acceptedAttempt.filter(e.getKey()::equals).isEmpty()) {
                continue;
            }
            for (Fact f : e.getValue()) {
                for (QualityObservation obs : ((EvidenceRecorded) f.event()).observations()) {
                    if (matches(start, obs)) {
                        effectiveObs.put(obs.dimension(), obs);
                        effectiveFact.put(obs.dimension(), f);
                    }
                }
            }
        }
        List<IgnoredObservation> ignored = new ArrayList<>();
        for (Map.Entry<AttemptId, List<Fact>> e : facts.evidence().entrySet()) {
            for (Fact f : e.getValue()) {
                for (QualityObservation obs : ((EvidenceRecorded) f.event()).observations()) {
                    String reason = null;
                    if (!matches(start, obs)) {
                        reason = "recorded under policy " + obs.policyId() + " v" + obs.policyVersion() + ", not "
                                + start.policy().id() + " v" + start.policy().version();
                    } else if (acceptedAttempt.isEmpty()) {
                        reason = "no accepted attempt";
                    } else if (!acceptedAttempt.get().equals(e.getKey())) {
                        reason = "evidence of attempt " + e.getKey() + ", not of the accepted attempt "
                                + acceptedAttempt.get();
                    } else if (effectiveFact.get(obs.dimension()) != f) {
                        reason = "overridden by a later applicable " + obs.dimension() + " observation";
                    }
                    if (reason != null) {
                        ignored.add(new IgnoredObservation(f.ref(), obs, reason));
                    }
                }
            }
        }

        List<Gate> gates = new ArrayList<>();
        if (acceptedAttempt.isPresent()) {
            for (QualityDimension d : QualityDimension.values()) {
                QualityObservation obs = effectiveObs.get(d);
                boolean req = required.contains(d);
                if (obs == null && !req) {
                    continue;
                }
                GateState state = obs == null ? GateState.MISSING : switch (obs.state()) {
                    case PASS -> GateState.PASS;
                    case NOT_APPLICABLE -> GateState.NOT_APPLICABLE;
                    case FAIL -> GateState.FAIL;
                    case UNKNOWN -> GateState.UNKNOWN;
                };
                Fact f = effectiveFact.get(d);
                gates.add(new Gate(d, req, state, Optional.ofNullable(obs), Optional.ofNullable(f).map(Fact::ref),
                        f == null ? OptionalLong.empty() : OptionalLong.of(f.sequence())));
            }
        }

        DerivedAcceptance.Status status = ev.acceptance().status();
        List<Reason> reasons = new ArrayList<>();
        Label label;
        switch (status) {
            case PENDING -> {
                label = Label.UNLABELED;
                reasons.add(new Reason(EligibilityCode.OUTCOME_PENDING, Optional.empty(),
                        outcome.isEmpty() ? "no outcome recorded" : "current outcome is PENDING"));
            }
            case NOT_ACCEPTED -> {
                // A negative example must be a validated, non-cancelled verdict: a cancellation says nothing about
                // the route and an imported claim was never validated under the execution's policy.
                Outcome o = outcome.get();
                if (o.status() == OutcomeStatus.CANCELLED) {
                    reasons.add(new Reason(EligibilityCode.OUTCOME_CANCELLED, Optional.empty(),
                            "outcome is CANCELLED: a cancellation is not a verdict on the route"));
                }
                if (o.origin() == OutcomeOrigin.IMPORTED_CLAIM) {
                    reasons.add(new Reason(EligibilityCode.OUTCOME_IMPORTED_CLAIM, Optional.empty(),
                            "outcome is an imported claim, not a validated outcome"));
                }
                label = reasons.isEmpty() ? Label.FAILURE : Label.UNLABELED;
            }
            case ACCEPTED_UNVALIDATED, VALIDATED_ACCEPTED -> {
                if (outcome.get().origin() == OutcomeOrigin.IMPORTED_CLAIM) {
                    reasons.add(new Reason(EligibilityCode.OUTCOME_IMPORTED_CLAIM, Optional.empty(),
                            "outcome is an imported claim, not a validated outcome"));
                }
                Fact finish = facts.finishes().get(acceptedAttempt.get());
                if (finish == null) {
                    reasons.add(new Reason(EligibilityCode.ACCEPTED_ATTEMPT_UNFINISHED, Optional.empty(),
                            "accepted attempt " + acceptedAttempt.get() + " is not finished"));
                } else if (((AttemptFinished) finish.event()).result() != AttemptResult.COMPLETED) {
                    reasons.add(new Reason(EligibilityCode.ACCEPTED_ATTEMPT_NOT_COMPLETED, Optional.empty(),
                            "accepted attempt " + acceptedAttempt.get() + " is "
                                    + ((AttemptFinished) finish.event()).result()));
                }
                for (Gate g : gates) {
                    if (!g.required()) {
                        continue;
                    }
                    switch (g.state()) {
                        case FAIL -> reasons.add(new Reason(EligibilityCode.GATE_FAIL, Optional.of(g.dimension()),
                                g.dimension() + " effective observation is FAIL"));
                        case UNKNOWN -> reasons.add(new Reason(EligibilityCode.GATE_UNKNOWN,
                                Optional.of(g.dimension()), g.dimension() + " effective observation is UNKNOWN"));
                        case MISSING -> reasons.add(new Reason(EligibilityCode.GATE_MISSING,
                                Optional.of(g.dimension()), g.dimension() + " has no applicable evidence on the "
                                        + "accepted attempt under policy " + start.policy().id() + " v"
                                        + start.policy().version()));
                        default -> { }
                    }
                }
                boolean structural = reasons.stream().anyMatch(r -> r.code() == EligibilityCode.OUTCOME_IMPORTED_CLAIM
                        || r.code() == EligibilityCode.ACCEPTED_ATTEMPT_UNFINISHED
                        || r.code() == EligibilityCode.ACCEPTED_ATTEMPT_NOT_COMPLETED);
                if (status == DerivedAcceptance.Status.VALIDATED_ACCEPTED && structural
                        || status == DerivedAcceptance.Status.ACCEPTED_UNVALIDATED && reasons.isEmpty()) {
                    reasons.add(new Reason(EligibilityCode.INCONSISTENT_DERIVATION, Optional.empty(),
                            "sample derivation " + List.copyOf(reasons) + " disagrees with derived acceptance "
                                    + status + " " + ev.acceptance().reasons()));
                }
                label = reasons.isEmpty() ? Label.SUCCESS : Label.UNLABELED;
            }
            default -> throw new IllegalStateException("unexpected acceptance " + status);
        }

        boolean diagnostic = view == ExportView.DIAGNOSTIC;
        List<SampleAttempt> attempts = new ArrayList<>();
        facts.starts().values().stream()
                .sorted(Comparator.comparingInt(f -> ((AttemptStarted) f.event()).ordinal()))
                .forEach(sf -> {
                    AttemptStarted s = (AttemptStarted) sf.event();
                    AttemptId aid = s.envelope().attemptId().get();
                    Optional<Fact> fin = Optional.ofNullable(facts.finishes().get(aid));
                    List<SampleStage> sampleStages = facts.stages().getOrDefault(aid, List.of()).stream()
                            .map(SampleBuilder::stage).toList();
                    List<SampleEvidence> sampleEvidence = new ArrayList<>();
                    for (Fact f : facts.evidence().getOrDefault(aid, List.of())) {
                        EvidenceRecorded er = (EvidenceRecorded) f.event();
                        List<SampleObservation> obs = er.observations().stream()
                                .map(o -> new SampleObservation(o, matches(start, o)))
                                .filter(o -> diagnostic || o.policyMatches()).toList();
                        if (!obs.isEmpty()) {
                            sampleEvidence.add(new SampleEvidence(f.ref(), f.sequence(), obs, er.artifacts()));
                        }
                    }
                    attempts.add(new SampleAttempt(aid, sf.ref(), s.ordinal(), s.reason(), s.previousAttempt(),
                            acceptedAttempt.filter(aid::equals).isPresent(),
                            fin.map(f -> ((AttemptFinished) f.event()).result()), fin.map(Fact::ref),
                            fin.flatMap(f -> ((AttemptFinished) f.event()).solutionSummary()),
                            fin.flatMap(f -> ((AttemptFinished) f.event()).lessonSummary()),
                            sampleStages, sampleEvidence));
                });

        Optional<Diagnostics> diagnostics = diagnostic
                ? Optional.of(new Diagnostics(ev.acceptance().reasons(),
                        outcomes.stream().map(f -> new OutcomeFact(f.ref(), f.sequence(),
                                ((OutcomeRecorded) f.event()).outcome())).toList(), ignored))
                : Optional.empty();
        return new ExperienceSample(started.ref(), start.taskSummary(), start.provenance(), start.policy(),
                List.copyOf(required), current.map(f -> new OutcomeFact(f.ref(), f.sequence(),
                        ((OutcomeRecorded) f.event()).outcome())), status, label, OptionalInt.empty(), groupOf(start),
                new Eligibility(reasons.isEmpty(), reasons), gates, attempts, facts.revisions(),
                history.get(0).sequence(), history.get(history.size() - 1).sequence(), diagnostics);
    }

    /** The repeat-detection key of an execution (see {@link Group}), also usable without building a sample. */
    static Group groupOf(ExecutionStarted start) {
        return new Group(start.envelope().taskId(), start.provenance().sourceRevision(),
                start.provenance().contextFingerprint(), start.provenance().constraintsFingerprint(),
                start.policy().id(), start.policy().version());
    }

    private static boolean matches(ExecutionStarted start, QualityObservation obs) {
        return obs.policyId().equals(start.policy().id()) && obs.policyVersion().equals(start.policy().version());
    }

    private static SampleStage stage(Fact f) {
        StageRecorded s = (StageRecorded) f.event();
        Map<UsageKind, UsageCounter> byKind = new EnumMap<>(UsageKind.class);
        s.usage().forEach(u -> byKind.put(u.kind(), u));
        List<SampleUsage> usage = new ArrayList<>();
        for (UsageKind kind : UsageKind.values()) {
            UsageCounter c = byKind.get(kind);
            if (c == null) {
                usage.add(new SampleUsage(kind, OptionalLong.empty(),
                        s.usageDeclaration() == UsageDeclaration.NO_BILLABLE_USAGE
                                ? UsageState.DECLARED_NO_BILLABLE_USAGE : UsageState.NOT_MEASURED, Optional.empty()));
            } else {
                usage.add(new SampleUsage(kind, c.value(), switch (c.provenance()) {
                    case REPORTED -> UsageState.REPORTED;
                    case ESTIMATED -> UsageState.ESTIMATED;
                    case UNKNOWN -> UsageState.UNKNOWN;
                }, Optional.of(c.source())));
            }
        }
        return new SampleStage(f.ref(), f.sequence(), s.stage(), s.route(), s.startedAt(), s.endedAt(),
                s.usageDeclaration(), s.justification(), usage, s.artifacts());
    }
}
