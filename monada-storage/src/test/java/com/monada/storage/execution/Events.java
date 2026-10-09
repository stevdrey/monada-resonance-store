package com.monada.storage.execution;

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

/** Fixture builders for execution ledger tests. All identifiers and instants are fixed. */
final class Events {
    static final ScopeId SCOPE = ScopeId.of("scope-1");
    static final TaskId TASK = TaskId.of("task-1");
    static final ExecutionId EXEC = ExecutionId.of("exec-1");
    static final AttemptId ATTEMPT = AttemptId.of("attempt-1");
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-01-01T00:00:01.5Z");
    static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");

    private Events() {
    }

    static ExecutionEvent started(String id) {
        return started(id, SCOPE, EXEC, "Fix | the\tbug\n\\ ünï 😀 \u0001 ok");
    }

    static ExecutionEvent started(String id, ScopeId scope, ExecutionId exec, String summary) {
        return ExecutionEvent.executionStarted(EventId.of(id), scope, TASK, exec,
                new SourceProvenance("rev-1", "ctx-1", "constraints-1"),
                new EvaluationPolicy("policy", "2", Set.of(QualityDimension.TESTS, QualityDimension.CORRECTNESS)),
                summary, T0);
    }

    static ExecutionEvent attemptStarted(String id, AttemptId attempt, int ordinal, Optional<AttemptId> previous) {
        AttemptReason reason = ordinal == 1 ? AttemptReason.INITIAL : AttemptReason.RETRY;
        return new AttemptStarted(EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T0, T0),
                ordinal, previous, reason);
    }

    static ExecutionEvent attemptStarted(String id) {
        return attemptStarted(id, ATTEMPT, 1, Optional.empty());
    }

    static ExecutionEvent stage(String id, AttemptId attempt) {
        EventEnvelope env = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        return StageRecorded.measured(env, "plan|edit", new RouteDescriptor("worker-a", Optional.of("prov"),
                        Optional.of("model-x"), Optional.empty(), BillingMode.API_METERED), T0, T1,
                List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, 1200, "provider"),
                        UsageCounter.estimated(UsageKind.OUTPUT_TOKENS, 0, "estimator"),
                        UsageCounter.unknown(UsageKind.REQUESTS, "none")),
                List.of(ArtifactRef.of("log", "file:///x|y"), ArtifactRef.of("diff", "d-1", "sha256:abc")));
    }

    static ExecutionEvent noBillableStage(String id, AttemptId attempt) {
        EventEnvelope env = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        return StageRecorded.noBillableUsage(env, "local", RouteDescriptor.workerOnly("local-worker"), T0, T1,
                "ran locally", List.of());
    }

    static ExecutionEvent evidence(String id, AttemptId attempt) {
        EventEnvelope env = EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2);
        QualityObservation tests = new QualityObservation(QualityDimension.TESTS, ObservationState.PASS,
                OptionalDouble.of(0.8125), Optional.of("ratio"), "junit", "5", "policy", "2",
                ObservationSource.TOOL, List.of(ArtifactRef.of("report", "r-1")), Optional.of("all green"));
        QualityObservation na = new QualityObservation(QualityDimension.SECURITY, ObservationState.NOT_APPLICABLE,
                OptionalDouble.empty(), Optional.empty(), "human", "1", "policy", "2", ObservationSource.HUMAN,
                List.of(), Optional.of("no attack surface"));
        return new EvidenceRecorded(env, List.of(tests, na), List.of(ArtifactRef.of("bundle", "b-1")));
    }

    static ExecutionEvent finished(String id, AttemptId attempt) {
        return new AttemptFinished(EventEnvelope.original(EventId.of(id), SCOPE, TASK, EXEC, attempt, T1, T2),
                AttemptResult.COMPLETED, Optional.of("solved"), Optional.of("lesson | learned"));
    }

    static ExecutionEvent accepted(String id, AttemptId attempt) {
        return ExecutionEvent.outcomeRecorded(EventId.of(id), SCOPE, TASK, EXEC,
                Outcome.accepted(OutcomeOrigin.VALIDATED, attempt), T2);
    }

    /** Correction of an earlier AttemptFinished (target at revision 1). */
    static ExecutionEvent correctFinish(String id, String target, AttemptId attempt) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), SCOPE, TASK, EXEC, Optional.of(attempt), 2,
                Optional.of(EventId.of(target)), T1, T2);
        EventEnvelope inner = new EventEnvelope(EventId.of(id), SCOPE, TASK, EXEC, Optional.of(attempt), 1,
                Optional.empty(), T1, T2);
        return new Correction(outer, new AttemptFinished(inner, AttemptResult.FAILED, Optional.empty(),
                Optional.empty()), "tests were flaky");
    }

    /** Complete run: started, attempt, stage, evidence, finished, accepted, in this order. */
    static List<ExecutionEvent> fullRun() {
        return List.of(started("e1"), attemptStarted("e2"), stage("e3", ATTEMPT), evidence("e4", ATTEMPT),
                noBillableStage("e5", ATTEMPT), finished("e6", ATTEMPT), accepted("e7", ATTEMPT),
                correctFinish("e8", "e6", ATTEMPT));
    }
}
