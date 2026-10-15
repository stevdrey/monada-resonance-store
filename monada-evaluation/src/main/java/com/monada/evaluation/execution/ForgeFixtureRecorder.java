package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.RecordResult;
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
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Turns a {@link ForgeFixture} into real ledger events and records them through the public
 * {@link ExecutionMemory} API. Every id, time, context SHA and price is taken from the fixture, so the same
 * fixture always produces the same events in the same order.
 */
final class ForgeFixtureRecorder {
    static final String POLICY_ID = "forge-gates";
    static final String POLICY_VERSION = "1";
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private ForgeFixtureRecorder() {
    }

    static ScopeId scope(ForgeFixture fixture, String scopeKey) {
        return ScopeId.of(fixture.scopeId(scopeKey));
    }

    static AttemptId attemptId(int ordinal) {
        return AttemptId.of("a" + ordinal);
    }

    /** The experience id (fixture id) of an attempt ref, e.g. {@code a-inv1/a2}. */
    static String experienceId(String execution, AttemptId attempt) {
        return execution + "/" + attempt.value();
    }

    static RouteDescriptor route(ForgeFixture fixture) {
        return route(fixture.price());
    }

    /** The fixed worker route of the fixture's priced provider and model. */
    static RouteDescriptor route(ForgeFixture.Price price) {
        return new RouteDescriptor("forge-fixture-worker", Optional.of(price.provider()), Optional.of(price.model()),
                Optional.empty(), BillingMode.API_METERED);
    }

    static PriceSnapshot priceSnapshot(ForgeFixture fixture) {
        return priceSnapshot(fixture.price(), "forge experience fixture v" + fixture.version());
    }

    /** The fixed price snapshot of a fixture; {@code source} names the fixture and its version. */
    static PriceSnapshot priceSnapshot(ForgeFixture.Price p, String source) {
        return new PriceSnapshot(p.id(), p.version(), p.provider(), p.model(), PriceSnapshot.currency(p.currency()),
                p.effective(), source, "fixed fixture prices, no live lookup",
                List.of(PriceLine.of(UsageKind.INPUT_TOKENS, p.input(), p.unitSize()),
                        PriceLine.of(UsageKind.OUTPUT_TOKENS, p.output(), p.unitSize())));
    }

    /** Records every event of one project, in fixture order, and returns each recording outcome. */
    static List<RecordResult> recordScope(ExecutionMemory memory, ForgeFixture fixture, String scopeKey) {
        return record(memory, events(fixture, scopeKey));
    }

    /** Records {@code events} in order and returns each recording outcome. */
    static List<RecordResult> record(ExecutionMemory memory, List<ExecutionEvent> events) {
        List<RecordResult> results = new ArrayList<>();
        for (ExecutionEvent event : events) {
            results.add(memory.record(event));
        }
        return results;
    }

    /** All events of one project in recording order. */
    static List<ExecutionEvent> events(ForgeFixture fixture, String scopeKey) {
        List<ExecutionEvent> events = new ArrayList<>();
        ScopeId scope = scope(fixture, scopeKey);
        RouteDescriptor route = route(fixture);
        for (ForgeFixture.Execution e : fixture.executionsOf(scopeKey)) {
            TaskId task = TaskId.of(e.task());
            ExecutionId exec = ExecutionId.of(e.id());
            events.add(executionStarted(scope, task, exec, e.sourceRevision(), e.contextFingerprint(),
                    e.constraintsFingerprint(), e.mandatory(), e.summary(), T0));
            long lastEnd = 0;
            for (ForgeFixture.Attempt a : fixture.attemptsOf(e.id())) {
                Instant end = T0.plusSeconds(a.endSeconds());
                events.addAll(attemptOpened(scope, task, exec, a.experienceId(), a.ordinal(), a.reason(),
                        T0.plusSeconds(a.startSeconds()), end, route, a.inputTokens(), a.outputTokens(), a.evidence()));
                AttemptResult result = AttemptResult.valueOf(a.result());
                events.addAll(attemptFinished(scope, task, exec, a.ordinal(), end, result, a.solution(), a.lesson(),
                        a.revised() ? Optional.of(new Revision(result, a.revisedSolution(), a.revisedLesson(),
                                "reviewed rewrite of the finish summaries")) : Optional.empty()));
                lastEnd = Math.max(lastEnd, a.endSeconds());
            }
            Instant at = T0.plusSeconds(lastEnd);
            switch (e.outcome()) {
                case ACCEPTED_VALIDATED -> events.add(outcome(scope, task, exec,
                        Outcome.accepted(OutcomeOrigin.VALIDATED, attemptId(e.outcomeAttempt())), at));
                case ACCEPTED_IMPORTED -> events.add(outcome(scope, task, exec,
                        Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, attemptId(e.outcomeAttempt())), at));
                case REJECTED_VALIDATED -> events.add(outcome(scope, task, exec,
                        Outcome.rejected(OutcomeOrigin.VALIDATED), at));
                case NONE -> { }
            }
        }
        return events;
    }

    // --- event builders shared by the versioned fixtures of this package ---------------------------------------

    /** A revision-2 correction of an attempt finish: the replacement result and summaries and why. */
    record Revision(AttemptResult result, String solution, String lesson, String justification) {
    }

    /** {@code <execution>.start} under the fixture's evaluation policy. */
    static ExecutionEvent executionStarted(ScopeId scope, TaskId task, ExecutionId exec, String sourceRevision,
                                           String contextFingerprint, String constraintsFingerprint,
                                           Set<QualityDimension> mandatory, String summary, Instant at) {
        return ExecutionEvent.executionStarted(EventId.of(exec.value() + ".start"), scope, task, exec,
                new SourceProvenance(sourceRevision, contextFingerprint, constraintsFingerprint),
                new EvaluationPolicy(POLICY_ID, POLICY_VERSION, mandatory), summary, at);
    }

    /**
     * The events that open one attempt, in order: {@code .start} at {@code begin} (with the previous attempt linked),
     * {@code .stage} measured from {@code begin} to {@code end} with the reported input and output tokens, and
     * {@code .evidence} at {@code end} when {@code evidence} is not empty.
     */
    static List<ExecutionEvent> attemptOpened(ScopeId scope, TaskId task, ExecutionId exec, String experienceId,
                                              int ordinal, String reason, Instant begin, Instant end,
                                              RouteDescriptor route, long inputTokens, long outputTokens,
                                              Map<QualityDimension, String> evidence) {
        List<ExecutionEvent> events = new ArrayList<>();
        AttemptId attempt = attemptId(ordinal);
        String prefix = exec.value() + "." + attempt.value();
        Optional<AttemptId> previous = ordinal == 1 ? Optional.empty() : Optional.of(attemptId(ordinal - 1));
        events.add(new AttemptStarted(EventEnvelope.original(EventId.of(prefix + ".start"), scope, task, exec, attempt,
                begin, begin), ordinal, previous, AttemptReason.valueOf(reason)));
        events.add(StageRecorded.measured(EventEnvelope.original(EventId.of(prefix + ".stage"), scope, task, exec,
                attempt, end, end), stageName(reason), route, begin, end,
                List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, inputTokens, "fixture"),
                        UsageCounter.reported(UsageKind.OUTPUT_TOKENS, outputTokens, "fixture")),
                List.of()));
        if (!evidence.isEmpty()) {
            List<QualityObservation> observations = new ArrayList<>();
            evidence.forEach((dimension, state) -> observations.add(observation(experienceId, dimension, state)));
            events.add(new EvidenceRecorded(EventEnvelope.original(EventId.of(prefix + ".evidence"), scope, task,
                    exec, attempt, end, end), observations, List.of()));
        }
        return events;
    }

    /**
     * The {@code .fin} of one attempt at {@code end} and, with a {@code revision}, its revision-2 correction
     * {@code .fin.r2} (the original text is then no longer recallable).
     */
    static List<ExecutionEvent> attemptFinished(ScopeId scope, TaskId task, ExecutionId exec, int ordinal,
                                                Instant end, AttemptResult result, String solution, String lesson,
                                                Optional<Revision> revision) {
        List<ExecutionEvent> events = new ArrayList<>();
        AttemptId attempt = attemptId(ordinal);
        String prefix = exec.value() + "." + attempt.value();
        EventId finish = EventId.of(prefix + ".fin");
        events.add(new AttemptFinished(EventEnvelope.original(finish, scope, task, exec, attempt, end, end), result,
                Optional.of(solution), Optional.of(lesson)));
        revision.ifPresent(r -> {
            EventId correction = EventId.of(prefix + ".fin.r2");
            EventEnvelope outer = new EventEnvelope(correction, scope, task, exec, Optional.of(attempt), 2,
                    Optional.of(finish), end, end);
            events.add(new Correction(outer, new AttemptFinished(EventEnvelope.original(correction, scope, task, exec,
                    attempt, end, end), r.result(), Optional.of(r.solution()), Optional.of(r.lesson())),
                    r.justification()));
        });
        return events;
    }

    /** {@code <execution>.outcome} at {@code at}. */
    static ExecutionEvent outcome(ScopeId scope, TaskId task, ExecutionId exec, Outcome outcome, Instant at) {
        return ExecutionEvent.outcomeRecorded(EventId.of(exec.value() + ".outcome"), scope, task, exec, outcome, at);
    }

    private static String stageName(String reason) {
        return switch (AttemptReason.valueOf(reason)) {
            case INITIAL -> "implement";
            case REPAIR -> "repair";
            case RETRY -> "retry";
            case REVIEW_FOLLOW_UP -> "review-and-qa";
        };
    }

    private static QualityObservation observation(String experienceId, QualityDimension dimension, String state) {
        return new QualityObservation(dimension, ObservationState.valueOf(state), OptionalDouble.empty(),
                Optional.empty(), "forge-fixture-evaluator", "1", POLICY_ID, POLICY_VERSION, ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "synthetic/" + experienceId + "/" + dimension)),
                Optional.empty());
    }
}
