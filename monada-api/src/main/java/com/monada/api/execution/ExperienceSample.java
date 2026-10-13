package com.monada.api.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One neutral, read-only observation of one execution as of an export cutoff (contract v1, section 12; schema
 * {@value ExperienceSamplePage#SCHEMA}). Every fact carries the {@link ExperienceRef} of the exact ledger event
 * and revision it came from, so a consumer can always return to the source.
 *
 * <p>A sample is <b>not</b> a retrieval hit and holds no similarity: recalled similarity is resonance only (see
 * {@link ExecutionMemory#recall}) and a host joins it to samples by execution. It holds no training target and
 * no routing score; {@link #label} is only the structural verdict of contract section 8 under the export's
 * evidence policy, and a {@code SUCCESS} label is given to a validated acceptance only, exactly once per
 * execution: the retries and repairs that led to it stay visible as attempts but never as further successes,
 * and corrected revisions replace, never accompany, the facts they correct.
 *
 * <p>Only caller-approved summaries, typed observations and artifact references are exposed; the library holds
 * no transcript or secret to expose.
 */
public record ExperienceSample(
        ExperienceRef execution,
        String taskSummary,
        SourceProvenance provenance,
        EvaluationPolicy policy,
        List<QualityDimension> requiredGates,
        Optional<OutcomeFact> outcome,
        DerivedAcceptance.Status acceptance,
        Label label,
        OptionalInt successOrdinal,
        Group group,
        Eligibility eligibility,
        List<Gate> gates,
        List<SampleAttempt> attempts,
        List<Revision> revisions,
        long firstSequence,
        long lastSequence,
        Optional<Diagnostics> diagnostics) {

    public ExperienceSample {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(taskSummary, "taskSummary");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(successOrdinal, "successOrdinal");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(eligibility, "eligibility");
        if (successOrdinal.isPresent() && label != Label.SUCCESS) {
            throw new IllegalArgumentException("only a SUCCESS sample has a successOrdinal");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        requiredGates = List.copyOf(requiredGates);
        gates = List.copyOf(gates);
        attempts = List.copyOf(attempts);
        revisions = List.copyOf(revisions);
    }

    public ExecutionId executionId() {
        return execution.execution();
    }

    /** The same sample with its position among the group's successes (used by the exporter). */
    ExperienceSample withSuccessOrdinal(int ordinal) {
        return new ExperienceSample(execution, taskSummary, provenance, policy, requiredGates, outcome, acceptance,
                label, OptionalInt.of(ordinal), group, eligibility, gates, attempts, revisions, firstSequence,
                lastSequence, diagnostics);
    }

    /** Structural verdict. {@code UNLABELED} samples are exported, never dropped, with their reasons. */
    public enum Label { SUCCESS, FAILURE, UNLABELED }

    /** Why a sample cannot (or can) be used as a labelled example. */
    public enum EligibilityCode {
        OUTCOME_PENDING, OUTCOME_CANCELLED, OUTCOME_IMPORTED_CLAIM, ACCEPTED_ATTEMPT_UNFINISHED,
        ACCEPTED_ATTEMPT_NOT_COMPLETED, GATE_FAIL, GATE_UNKNOWN, GATE_MISSING, INCONSISTENT_DERIVATION
    }

    /**
     * What makes two executions repeats of one piece of work: the same task started from the same source
     * revision, context and constraints under the same evaluation policy (the comparability key of section 10
     * without the scope). Several validated executions of one group are distinct facts, so Store marks them
     * ({@link #successOrdinal}) and never hides them; a consumer that wants one example per group keeps the
     * sample whose ordinal is 1.
     */
    public record Group(TaskId task, String sourceRevision, String contextFingerprint,
                        String constraintsFingerprint, String policyId, String policyVersion) {
        public Group {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(sourceRevision, "sourceRevision");
            Objects.requireNonNull(contextFingerprint, "contextFingerprint");
            Objects.requireNonNull(constraintsFingerprint, "constraintsFingerprint");
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(policyVersion, "policyVersion");
        }
    }

    /** Effective state of one gate dimension; {@code MISSING} means no applicable observation exists. */
    public enum GateState { PASS, NOT_APPLICABLE, FAIL, UNKNOWN, MISSING }

    /** Usage state of one counter kind of a stage; unknown and not-measured are never zero. */
    public enum UsageState { REPORTED, ESTIMATED, UNKNOWN, NOT_MEASURED, DECLARED_NO_BILLABLE_USAGE }

    /** {@code eligible} is true exactly for {@code SUCCESS} and {@code FAILURE} labels. */
    public record Eligibility(boolean eligible, List<Reason> reasons) {
        public Eligibility {
            reasons = List.copyOf(reasons);
            if (eligible && !reasons.isEmpty()) {
                throw new IllegalArgumentException("an eligible sample has no reasons");
            }
            if (!eligible && reasons.isEmpty()) {
                throw new IllegalArgumentException("an ineligible sample states why");
            }
        }
    }

    public record Reason(EligibilityCode code, Optional<QualityDimension> dimension, String detail) {
        public Reason {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** The current outcome as recorded, with its origin (an imported claim stays distinguishable). */
    public record OutcomeFact(ExperienceRef ref, long sequence, Outcome outcome) {
    }

    /**
     * Effective evidence of one dimension for the accepted attempt: the applicable observation with the highest
     * ledger sequence (section 8). Only produced when the outcome is {@code ACCEPTED}.
     */
    public record Gate(QualityDimension dimension, boolean required, GateState state,
                       Optional<QualityObservation> observation, Optional<ExperienceRef> ref, OptionalLong sequence) {
        public Gate {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(sequence, "sequence");
        }
    }

    public record SampleAttempt(AttemptId id, ExperienceRef start, int ordinal, AttemptReason reason,
                                Optional<AttemptId> previousAttempt, boolean accepted,
                                Optional<AttemptResult> result, Optional<ExperienceRef> finish,
                                Optional<String> solutionSummary, Optional<String> lessonSummary,
                                List<SampleStage> stages, List<SampleEvidence> evidence) {
        public SampleAttempt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(previousAttempt, "previousAttempt");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(finish, "finish");
            Objects.requireNonNull(solutionSummary, "solutionSummary");
            Objects.requireNonNull(lessonSummary, "lessonSummary");
            stages = List.copyOf(stages);
            evidence = List.copyOf(evidence);
        }

        public boolean isInProgress() {
            return result.isEmpty();
        }
    }

    public record SampleStage(ExperienceRef ref, long sequence, String stage, RouteDescriptor route,
                              Instant startedAt, Instant endedAt, UsageDeclaration usageDeclaration,
                              Optional<String> justification, List<SampleUsage> usage,
                              List<ArtifactRef> artifacts) {
        public SampleStage {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(usageDeclaration, "usageDeclaration");
            Objects.requireNonNull(justification, "justification");
            usage = List.copyOf(usage);
            artifacts = List.copyOf(artifacts);
        }
    }

    /** One entry per {@link UsageKind}, in enum order, so a missing counter is explicit rather than absent. */
    public record SampleUsage(UsageKind kind, OptionalLong value, UsageState state, Optional<String> source) {
        public SampleUsage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(source, "source");
        }
    }

    public record SampleEvidence(ExperienceRef ref, long sequence, List<SampleObservation> observations,
                                 List<ArtifactRef> artifacts) {
        public SampleEvidence {
            Objects.requireNonNull(ref, "ref");
            observations = List.copyOf(observations);
            artifacts = List.copyOf(artifacts);
        }
    }

    /** {@code policyMatches}: recorded under the execution's own policy id and version. */
    public record SampleObservation(QualityObservation observation, boolean policyMatches) {
        public SampleObservation {
            Objects.requireNonNull(observation, "observation");
        }
    }

    /**
     * A corrected fact: {@code effective} is the revision that counts, {@code superseded} the earlier revisions
     * (oldest first) that must not be counted beside it.
     */
    public record Revision(ExperienceRef effective, long sequence, String justification,
                           List<ExperienceRef> superseded) {
        public Revision {
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(justification, "justification");
            superseded = List.copyOf(superseded);
        }
    }

    /** Present only in the {@link ExportView#DIAGNOSTIC} view. */
    public record Diagnostics(List<String> acceptanceReasons, List<OutcomeFact> outcomeHistory,
                              List<IgnoredObservation> ignoredObservations) {
        public Diagnostics {
            acceptanceReasons = List.copyOf(acceptanceReasons);
            outcomeHistory = List.copyOf(outcomeHistory);
            ignoredObservations = List.copyOf(ignoredObservations);
        }
    }

    /** An observation that never counted for acceptance, and why. */
    public record IgnoredObservation(ExperienceRef ref, QualityObservation observation, String reason) {
        public IgnoredObservation {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
