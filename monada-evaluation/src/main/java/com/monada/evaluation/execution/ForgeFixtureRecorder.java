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
import java.util.Optional;
import java.util.OptionalDouble;

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
        return new RouteDescriptor("forge-fixture-worker", Optional.of(fixture.price().provider()),
                Optional.of(fixture.price().model()), Optional.empty(), BillingMode.API_METERED);
    }

    static PriceSnapshot priceSnapshot(ForgeFixture fixture) {
        ForgeFixture.Price p = fixture.price();
        return new PriceSnapshot(p.id(), p.version(), p.provider(), p.model(), PriceSnapshot.currency(p.currency()),
                p.effective(), "forge experience fixture v" + fixture.version(),
                "fixed fixture prices, no live lookup",
                List.of(PriceLine.of(UsageKind.INPUT_TOKENS, p.input(), p.unitSize()),
                        PriceLine.of(UsageKind.OUTPUT_TOKENS, p.output(), p.unitSize())));
    }

    /** Records every event of one project, in fixture order, and returns each recording outcome. */
    static List<RecordResult> recordScope(ExecutionMemory memory, ForgeFixture fixture, String scopeKey) {
        List<RecordResult> results = new ArrayList<>();
        for (ExecutionEvent event : events(fixture, scopeKey)) {
            results.add(memory.record(event));
        }
        return results;
    }

    /** All events of one project in recording order. */
    static List<ExecutionEvent> events(ForgeFixture fixture, String scopeKey) {
        List<ExecutionEvent> events = new ArrayList<>();
        ScopeId scope = scope(fixture, scopeKey);
        for (ForgeFixture.Execution e : fixture.executionsOf(scopeKey)) {
            TaskId task = TaskId.of(e.task());
            ExecutionId exec = ExecutionId.of(e.id());
            events.add(ExecutionEvent.executionStarted(EventId.of(e.id() + ".start"), scope, task, exec,
                    new SourceProvenance(e.sourceRevision(), e.contextFingerprint(), e.constraintsFingerprint()),
                    new EvaluationPolicy(POLICY_ID, POLICY_VERSION, e.mandatory()), e.summary(), T0));
            long lastEnd = 0;
            for (ForgeFixture.Attempt a : fixture.attemptsOf(e.id())) {
                AttemptId attempt = attemptId(a.ordinal());
                String prefix = e.id() + "." + attempt.value();
                Optional<AttemptId> previous = a.ordinal() == 1 ? Optional.empty()
                        : Optional.of(attemptId(a.ordinal() - 1));
                Instant begin = T0.plusSeconds(a.startSeconds()); // the declared chronology, not midnight
                events.add(new AttemptStarted(EventEnvelope.original(EventId.of(prefix + ".start"), scope, task, exec,
                        attempt, begin, begin), a.ordinal(), previous, AttemptReason.valueOf(a.reason())));
                Instant end = T0.plusSeconds(a.endSeconds());
                events.add(StageRecorded.measured(EventEnvelope.original(EventId.of(prefix + ".stage"), scope, task,
                        exec, attempt, end, end), stageName(a.reason()), route(fixture),
                        T0.plusSeconds(a.startSeconds()), end,
                        List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, a.inputTokens(), "fixture"),
                                UsageCounter.reported(UsageKind.OUTPUT_TOKENS, a.outputTokens(), "fixture")),
                        List.of()));
                if (!a.evidence().isEmpty()) {
                    List<QualityObservation> observations = new ArrayList<>();
                    a.evidence().forEach((dimension, state) -> observations.add(observation(e, a, dimension, state)));
                    events.add(new EvidenceRecorded(EventEnvelope.original(EventId.of(prefix + ".evidence"), scope,
                            task, exec, attempt, end, end), observations, List.of()));
                }
                AttemptResult result = AttemptResult.valueOf(a.result());
                EventId finish = EventId.of(prefix + ".fin");
                events.add(new AttemptFinished(EventEnvelope.original(finish, scope, task, exec, attempt, end, end),
                        result, Optional.of(a.solution()), Optional.of(a.lesson())));
                if (a.revised()) { // a revision-2 correction: the original text must no longer be recallable
                    EventId correction = EventId.of(prefix + ".fin.r2");
                    EventEnvelope outer = new EventEnvelope(correction, scope, task, exec, Optional.of(attempt), 2,
                            Optional.of(finish), end, end);
                    events.add(new Correction(outer, new AttemptFinished(EventEnvelope.original(correction, scope,
                            task, exec, attempt, end, end), result, Optional.of(a.revisedSolution()),
                            Optional.of(a.revisedLesson())), "reviewed rewrite of the finish summaries"));
                }
                lastEnd = Math.max(lastEnd, a.endSeconds());
            }
            Instant at = T0.plusSeconds(lastEnd);
            switch (e.outcome()) {
                case ACCEPTED_VALIDATED -> events.add(ExecutionEvent.outcomeRecorded(EventId.of(e.id() + ".outcome"),
                        scope, task, exec, Outcome.accepted(OutcomeOrigin.VALIDATED, attemptId(e.outcomeAttempt())),
                        at));
                case ACCEPTED_IMPORTED -> events.add(ExecutionEvent.outcomeRecorded(EventId.of(e.id() + ".outcome"),
                        scope, task, exec,
                        Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, attemptId(e.outcomeAttempt())), at));
                case REJECTED_VALIDATED -> events.add(ExecutionEvent.outcomeRecorded(EventId.of(e.id() + ".outcome"),
                        scope, task, exec, Outcome.rejected(OutcomeOrigin.VALIDATED), at));
                case NONE -> { }
            }
        }
        return events;
    }

    private static String stageName(String reason) {
        return switch (AttemptReason.valueOf(reason)) {
            case INITIAL -> "implement";
            case REPAIR -> "repair";
            case RETRY -> "retry";
            case REVIEW_FOLLOW_UP -> "review-and-qa";
        };
    }

    private static QualityObservation observation(ForgeFixture.Execution e, ForgeFixture.Attempt a,
                                                  QualityDimension dimension, String state) {
        return new QualityObservation(dimension, ObservationState.valueOf(state), OptionalDouble.empty(),
                Optional.empty(), "forge-fixture-evaluator", "1", POLICY_ID, POLICY_VERSION, ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "synthetic/" + a.experienceId() + "/" + dimension)),
                Optional.empty());
    }
}
