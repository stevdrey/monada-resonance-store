package com.monada.api.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptFinished;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Fixed-id, fixed-time fixtures for execution facade tests (policy: mandatory TESTS, id policy/2). */
final class Fixtures {
    static final ScopeId SCOPE = ScopeId.of("scope-1");
    static final TaskId TASK = TaskId.of("task-1");
    static final ExecutionId EXEC = ExecutionId.of("exec-1");
    static final AttemptId A1 = AttemptId.of("a1");
    static final AttemptId A2 = AttemptId.of("a2");
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
    static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");

    private Fixtures() {
    }

    static ExecutionEvent started(String id) {
        return ExecutionEvent.executionStarted(EventId.of(id), SCOPE, TASK, EXEC,
                new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                new EvaluationPolicy("policy", "2", Set.of(QualityDimension.TESTS)), "Fix the bug", T0);
    }

    static ExecutionEvent attempt(String id, AttemptId attempt, int ordinal) {
        return new AttemptStarted(EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T0, T0),
                ordinal, ordinal == 1 ? Optional.empty() : Optional.of(A1),
                ordinal == 1 ? AttemptReason.INITIAL : AttemptReason.RETRY);
    }

    static StageRecorded stage(String id, AttemptId attempt) {
        EventEnvelope env = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        return StageRecorded.measured(env, "edit", new RouteDescriptor("worker-a", Optional.of("prov"),
                        Optional.of("model-x"), Optional.of("high"), BillingMode.API_METERED), T0, T1,
                List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, 1200, "provider")),
                List.of(ArtifactRef.of("log", "file:///x")));
    }

    static ExecutionEvent evidence(String id, AttemptId attempt, ObservationState state, String policyVersion) {
        EventEnvelope env = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        QualityObservation obs = new QualityObservation(QualityDimension.TESTS, state, OptionalDouble.empty(),
                Optional.empty(), "junit", "5", "policy", policyVersion, ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "r-1")),
                state == ObservationState.NOT_APPLICABLE ? Optional.of("no tests apply") : Optional.empty());
        return new EvidenceRecorded(env, List.of(obs), List.of());
    }

    static ExecutionEvent finished(String id, AttemptId attempt) {
        return new AttemptFinished(EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2),
                AttemptResult.COMPLETED, Optional.of("solved"), Optional.empty());
    }

    static ExecutionEvent outcome(String id, Outcome outcome) {
        return ExecutionEvent.outcomeRecorded(EventId.of(id), SCOPE, TASK, EXEC, outcome, T2);
    }

    /** Correction (revision {@code revision}) of {@code target}, replacing a finish with a FAILED one. */
    static ExecutionEvent correctFinish(String id, String target, int revision, AttemptId attempt) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), SCOPE, TASK, EXEC, Optional.of(attempt), revision,
                Optional.of(EventId.of(target)), T1, T2);
        EventEnvelope inner = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        return new Correction(outer, new AttemptFinished(inner, AttemptResult.FAILED, Optional.empty(),
                Optional.empty()), "tests were flaky");
    }

    /** Correction of an evidence event to another observation state. */
    static ExecutionEvent correctEvidence(String id, String target, int revision, AttemptId attempt,
                                          ObservationState state) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), SCOPE, TASK, EXEC, Optional.of(attempt), revision,
                Optional.of(EventId.of(target)), T1, T2);
        EvidenceRecorded replacement = (EvidenceRecorded) evidence(id, attempt, state, "2");
        return new Correction(outer, replacement, "re-run");
    }

    /** started, attempt a1, evidence PASS, finished. Outcome is added by each test. */
    static void baseRun(ExecutionMemory m, ObservationState tests) {
        m.record(started("e1"));
        m.record(attempt("e2", A1, 1));
        m.record(stage("e3", A1));
        m.record(evidence("e4", A1, tests, "2"));
        m.record(finished("e5", A1));
    }
}
