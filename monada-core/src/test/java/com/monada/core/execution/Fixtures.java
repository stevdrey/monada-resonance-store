package com.monada.core.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Deterministic test fixtures; every time and ID is explicit. */
final class Fixtures {
    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    static final ScopeId SCOPE = ScopeId.of("scope-1");
    static final TaskId TASK = TaskId.of("issue-95");
    static final String TASK_TEXT = "Implement immutable execution records";

    private Fixtures() {
    }

    static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    static EvaluationPolicy policy() {
        return new EvaluationPolicy("forge-gates", "1", Set.of(QualityDimension.TESTS));
    }

    static SourceProvenance provenance() {
        return new SourceProvenance("e823256", "ctx-1", "constraints-1");
    }

    static ExecutionEvent.ExecutionStarted started(String eventId, String execution) {
        return ExecutionEvent.executionStarted(EventId.of(eventId), SCOPE, TASK, ExecutionId.of(execution),
                provenance(), policy(), TASK_TEXT, at(0));
    }

    static EventEnvelope env(String eventId, String execution, String attempt, long occurred, long recorded) {
        return EventEnvelope.original(EventId.of(eventId), SCOPE, TASK, ExecutionId.of(execution),
                AttemptId.of(attempt), at(occurred), at(recorded));
    }

    static ExecutionEvent.AttemptStarted attemptStarted(String eventId, String execution, String attempt,
                                                        int ordinal, String previous) {
        return new ExecutionEvent.AttemptStarted(env(eventId, execution, attempt, 1, 1), ordinal,
                Optional.ofNullable(previous).map(AttemptId::of),
                previous == null ? AttemptReason.INITIAL : AttemptReason.REPAIR);
    }

    static RouteDescriptor route() {
        return new RouteDescriptor("coder", Optional.of("provider-a"), Optional.of("model-x"),
                Optional.of("high"), BillingMode.API_METERED);
    }

    static ExecutionEvent.StageRecorded stage(String eventId, String execution, String attempt,
                                              List<UsageCounter> usage) {
        return ExecutionEvent.StageRecorded.measured(env(eventId, execution, attempt, 60, 61), "implement",
                route(), at(2), at(60), usage, List.of());
    }

    static QualityObservation tests(ObservationState state) {
        return QualityObservation.of(QualityDimension.TESTS, state, "gradle", "9.8.0", "forge-gates", "1",
                ObservationSource.TOOL);
    }

    static ExecutionEvent.EvidenceRecorded evidence(String eventId, String execution, String attempt,
                                                    QualityObservation... observations) {
        return new ExecutionEvent.EvidenceRecorded(env(eventId, execution, attempt, 70, 71),
                List.of(observations), List.of());
    }

    static QualityObservation withValue(double value) {
        return new QualityObservation(QualityDimension.COMPLEXITY, ObservationState.PASS,
                OptionalDouble.of(value), Optional.of("cc"), "tool", "1", "forge-gates", "1",
                ObservationSource.TOOL, List.of(), Optional.empty());
    }
}
