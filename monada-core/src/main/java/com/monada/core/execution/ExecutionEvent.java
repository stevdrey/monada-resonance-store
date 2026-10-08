package com.monada.core.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable ledger event (contract section 4). Sealed by event kind so each kind carries exactly
 * its typed payload. Persistence, idempotency and reference checks against the ledger belong to the
 * storage layer; these records only guarantee that a single event is internally valid.
 */
public sealed interface ExecutionEvent
        permits ExecutionEvent.ExecutionStarted, ExecutionEvent.AttemptStarted, ExecutionEvent.StageRecorded,
        ExecutionEvent.EvidenceRecorded, ExecutionEvent.AttemptFinished, ExecutionEvent.OutcomeRecorded,
        ExecutionEvent.Correction {

    EventEnvelope envelope();

    EventKind kind();

    default EventId eventId() {
        return envelope().eventId();
    }

    default ScopeId scopeId() {
        return envelope().scopeId();
    }

    default ExecutionId executionId() {
        return envelope().executionId();
    }

    /** Opens an execution with its provenance and evaluation policy. */
    static ExecutionStarted executionStarted(EventId eventId, ScopeId scope, TaskId task, ExecutionId execution,
                                             SourceProvenance provenance, EvaluationPolicy policy,
                                             String taskSummary, Instant at) {
        return new ExecutionStarted(EventEnvelope.original(eventId, scope, task, execution, at, at),
                taskSummary, provenance, policy);
    }

    /** Records an execution-level outcome. */
    static OutcomeRecorded outcomeRecorded(EventId eventId, ScopeId scope, TaskId task, ExecutionId execution,
                                           Outcome outcome, Instant at) {
        return new OutcomeRecorded(EventEnvelope.original(eventId, scope, task, execution, at, at), outcome);
    }

    private static void requireOriginal(EventEnvelope envelope) {
        if (envelope.revision() != 1) {
            throw new IllegalArgumentException("only a Correction may carry revision > 1");
        }
    }

    private static void requireAttempt(EventEnvelope envelope, boolean required, EventKind kind) {
        if (required && envelope.attemptId().isEmpty()) {
            throw new IllegalArgumentException(kind + " requires an attemptId");
        }
        if (!required && envelope.attemptId().isPresent()) {
            throw new IllegalArgumentException(kind + " must not carry an attemptId");
        }
    }

    record ExecutionStarted(EventEnvelope envelope, String taskSummary, SourceProvenance provenance,
                            EvaluationPolicy policy) implements ExecutionEvent {
        public ExecutionStarted {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, false, EventKind.EXECUTION_STARTED);
            taskSummary = Validation.summary(taskSummary, "taskSummary");
            Objects.requireNonNull(provenance, "provenance");
            Objects.requireNonNull(policy, "policy");
        }

        @Override
        public EventKind kind() {
            return EventKind.EXECUTION_STARTED;
        }
    }

    record AttemptStarted(EventEnvelope envelope, int ordinal, Optional<AttemptId> previousAttempt,
                          AttemptReason reason) implements ExecutionEvent {
        public AttemptStarted {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, true, EventKind.ATTEMPT_STARTED);
            Objects.requireNonNull(previousAttempt, "previousAttempt");
            Objects.requireNonNull(reason, "reason");
            if (ordinal < 1) {
                throw new IllegalArgumentException("ordinal must be >= 1");
            }
            if (previousAttempt.isPresent() && previousAttempt.equals(envelope.attemptId())) {
                throw new IllegalArgumentException("an attempt cannot follow itself");
            }
            if (reason == AttemptReason.INITIAL && previousAttempt.isPresent()) {
                throw new IllegalArgumentException("an INITIAL attempt has no previous attempt");
            }
            if (reason != AttemptReason.INITIAL && previousAttempt.isEmpty()) {
                throw new IllegalArgumentException(reason + " requires a previous attempt");
            }
        }

        @Override
        public EventKind kind() {
            return EventKind.ATTEMPT_STARTED;
        }
    }

    record StageRecorded(EventEnvelope envelope, String stage, RouteDescriptor route, Instant startedAt,
                         Instant endedAt, UsageDeclaration usageDeclaration, Optional<String> justification,
                         List<UsageCounter> usage, List<ArtifactRef> artifacts) implements ExecutionEvent {
        public StageRecorded {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, true, EventKind.STAGE_RECORDED);
            stage = Validation.opaque(stage, "stage");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            Objects.requireNonNull(usageDeclaration, "usageDeclaration");
            Objects.requireNonNull(justification, "justification");
            justification = justification.map(j -> Validation.summary(j, "justification"));
            usage = Validation.boundedList(usage, "usage", ExecutionLimits.MAX_USAGE_COUNTERS_PER_EVENT);
            artifacts = Validation.boundedList(artifacts, "artifacts", ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT);
            if (endedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("endedAt must not be before startedAt");
            }
            Validation.requireUnique(usage, UsageCounter::kind, "usage counter kind");
            if (usageDeclaration == UsageDeclaration.NO_BILLABLE_USAGE) {
                if (justification.isEmpty()) {
                    throw new IllegalArgumentException("NO_BILLABLE_USAGE requires a justification");
                }
                if (!usage.isEmpty()) {
                    throw new IllegalArgumentException("NO_BILLABLE_USAGE must not carry usage counters");
                }
            } else if (justification.isPresent()) {
                throw new IllegalArgumentException("a justification is only allowed with NO_BILLABLE_USAGE");
            }
        }

        /** Measured stage; counters may be empty, which means "not measured", never zero. */
        public static StageRecorded measured(EventEnvelope envelope, String stage, RouteDescriptor route,
                                             Instant startedAt, Instant endedAt, List<UsageCounter> usage,
                                             List<ArtifactRef> artifacts) {
            return new StageRecorded(envelope, stage, route, startedAt, endedAt, UsageDeclaration.MEASURED,
                    Optional.empty(), usage, artifacts);
        }

        /** Stage that explicitly declares it consumed nothing billable. */
        public static StageRecorded noBillableUsage(EventEnvelope envelope, String stage, RouteDescriptor route,
                                                    Instant startedAt, Instant endedAt, String justification,
                                                    List<ArtifactRef> artifacts) {
            return new StageRecorded(envelope, stage, route, startedAt, endedAt,
                    UsageDeclaration.NO_BILLABLE_USAGE, Optional.of(justification), List.of(), artifacts);
        }

        @Override
        public EventKind kind() {
            return EventKind.STAGE_RECORDED;
        }
    }

    record EvidenceRecorded(EventEnvelope envelope, List<QualityObservation> observations,
                            List<ArtifactRef> artifacts) implements ExecutionEvent {
        public EvidenceRecorded {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, true, EventKind.EVIDENCE_RECORDED);
            observations = Validation.boundedList(observations, "observations",
                    ExecutionLimits.MAX_OBSERVATIONS_PER_EVENT);
            artifacts = Validation.boundedList(artifacts, "artifacts", ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT);
            if (observations.isEmpty()) {
                throw new IllegalArgumentException("EVIDENCE_RECORDED requires at least one observation");
            }
            int references = artifacts.size()
                    + observations.stream().mapToInt(o -> o.evidence().size()).sum();
            if (references > ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT) {
                throw new IllegalArgumentException("artifact references (event-level plus observation evidence) "
                        + "exceed " + ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT + " per event");
            }
            Validation.requireUnique(observations, QualityObservation::dimension, "observation dimension");
        }

        @Override
        public EventKind kind() {
            return EventKind.EVIDENCE_RECORDED;
        }
    }

    record AttemptFinished(EventEnvelope envelope, AttemptResult result, Optional<String> solutionSummary,
                           Optional<String> lessonSummary) implements ExecutionEvent {
        public AttemptFinished {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, true, EventKind.ATTEMPT_FINISHED);
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(solutionSummary, "solutionSummary");
            Objects.requireNonNull(lessonSummary, "lessonSummary");
            solutionSummary = solutionSummary.map(s -> Validation.summary(s, "solutionSummary"));
            lessonSummary = lessonSummary.map(s -> Validation.summary(s, "lessonSummary"));
        }

        @Override
        public EventKind kind() {
            return EventKind.ATTEMPT_FINISHED;
        }
    }

    record OutcomeRecorded(EventEnvelope envelope, Outcome outcome) implements ExecutionEvent {
        public OutcomeRecorded {
            Objects.requireNonNull(envelope, "envelope");
            requireOriginal(envelope);
            requireAttempt(envelope, false, EventKind.OUTCOME_RECORDED);
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public EventKind kind() {
            return EventKind.OUTCOME_RECORDED;
        }
    }

    /**
     * Revises an earlier event with a full replacement payload. The outer envelope has revision
     * {@code n+1} and names the superseded event; the replacement is an original-shaped event that
     * addresses the same fact (same ids, attempt and timestamps as the outer envelope).
     */
    record Correction(EventEnvelope envelope, ExecutionEvent replacement, String justification)
            implements ExecutionEvent {
        public Correction {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(replacement, "replacement");
            if (envelope.revision() < 2) {
                throw new IllegalArgumentException("a Correction must have revision >= 2");
            }
            if (replacement instanceof Correction) {
                throw new IllegalArgumentException("a Correction cannot replace another Correction");
            }
            if (!envelope.sameFactAs(replacement.envelope())) {
                throw new IllegalArgumentException("replacement must address the same event, execution and attempt");
            }
            justification = Validation.summary(justification, "justification");
        }

        @Override
        public EventKind kind() {
            return EventKind.CORRECTION;
        }
    }
}
