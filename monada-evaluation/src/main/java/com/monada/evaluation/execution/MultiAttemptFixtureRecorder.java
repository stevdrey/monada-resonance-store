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
 * Turns a {@link MultiAttemptFixture} into real ledger events and records them through the public
 * {@link ExecutionMemory} API, with the event id scheme of {@link ForgeFixtureRecorder}
 * ({@code <execution>.start}, {@code <execution>.a<n>.start|stage|evidence|fin|fin.r2}, {@code <execution>.outcome}).
 * Unlike the version-1 recorder it records attempts without a finish, cancelled outcomes and corrections that change
 * the attempt result. Every id, time and price comes from the fixture, so the same fixture always produces the same
 * events in the same order.
 */
final class MultiAttemptFixtureRecorder {
    static final String POLICY_ID = "forge-gates";
    static final String POLICY_VERSION = "1";
    static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    private MultiAttemptFixtureRecorder() {
    }

    static ScopeId scope(MultiAttemptFixture fixture, String scopeKey) {
        return ScopeId.of(fixture.scopeId(scopeKey));
    }

    static PriceSnapshot priceSnapshot(MultiAttemptFixture fixture) {
        ForgeFixture.Price p = fixture.price();
        return new PriceSnapshot(p.id(), p.version(), p.provider(), p.model(), PriceSnapshot.currency(p.currency()),
                p.effective(), "forge multi-attempt fixture v" + fixture.version(),
                "fixed fixture prices, no live lookup",
                List.of(PriceLine.of(UsageKind.INPUT_TOKENS, p.input(), p.unitSize()),
                        PriceLine.of(UsageKind.OUTPUT_TOKENS, p.output(), p.unitSize())));
    }

    /** Records every event of one project, in fixture order, and returns each recording outcome. */
    static List<RecordResult> recordScope(ExecutionMemory memory, MultiAttemptFixture fixture, String scopeKey) {
        List<RecordResult> results = new ArrayList<>();
        for (ExecutionEvent event : events(fixture, scopeKey)) {
            results.add(memory.record(event));
        }
        return results;
    }

    /** All events of one project in recording order. */
    static List<ExecutionEvent> events(MultiAttemptFixture fixture, String scopeKey) {
        List<ExecutionEvent> events = new ArrayList<>();
        ScopeId scope = scope(fixture, scopeKey);
        RouteDescriptor route = new RouteDescriptor("forge-fixture-worker", Optional.of(fixture.price().provider()),
                Optional.of(fixture.price().model()), Optional.empty(), BillingMode.API_METERED);
        for (MultiAttemptFixture.Execution e : fixture.executionsOf(scopeKey)) {
            TaskId task = TaskId.of(e.task());
            ExecutionId exec = ExecutionId.of(e.id());
            events.add(ExecutionEvent.executionStarted(EventId.of(e.id() + ".start"), scope, task, exec,
                    new SourceProvenance(e.sourceRevision(), e.contextFingerprint(), e.constraintsFingerprint()),
                    new EvaluationPolicy(POLICY_ID, POLICY_VERSION, e.mandatory()), e.summary(), T0));
            long lastEnd = 0;
            for (MultiAttemptFixture.Attempt a : fixture.attemptsOf(e.id())) {
                AttemptId attempt = ForgeFixtureRecorder.attemptId(a.ordinal());
                String prefix = e.id() + "." + attempt.value();
                Optional<AttemptId> previous = a.ordinal() == 1 ? Optional.empty()
                        : Optional.of(ForgeFixtureRecorder.attemptId(a.ordinal() - 1));
                Instant begin = T0.plusSeconds(a.startSeconds());
                Instant end = T0.plusSeconds(a.endSeconds());
                events.add(new AttemptStarted(EventEnvelope.original(EventId.of(prefix + ".start"), scope, task, exec,
                        attempt, begin, begin), a.ordinal(), previous, AttemptReason.valueOf(a.reason())));
                events.add(StageRecorded.measured(EventEnvelope.original(EventId.of(prefix + ".stage"), scope, task,
                        exec, attempt, end, end), a.reason().toLowerCase(java.util.Locale.ROOT), route, begin, end,
                        List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, a.inputTokens(), "fixture"),
                                UsageCounter.reported(UsageKind.OUTPUT_TOKENS, a.outputTokens(), "fixture")),
                        List.of()));
                if (!a.evidence().isEmpty()) {
                    List<QualityObservation> observations = new ArrayList<>();
                    a.evidence().forEach((dimension, state) -> observations.add(observation(a, dimension, state)));
                    events.add(new EvidenceRecorded(EventEnvelope.original(EventId.of(prefix + ".evidence"), scope,
                            task, exec, attempt, end, end), observations, List.of()));
                }
                lastEnd = Math.max(lastEnd, a.endSeconds());
                if (!a.finished()) { // started, never finished: no ATTEMPT_FINISHED fact exists to recall
                    continue;
                }
                EventId finish = EventId.of(prefix + ".fin");
                events.add(new AttemptFinished(EventEnvelope.original(finish, scope, task, exec, attempt, end, end),
                        AttemptResult.valueOf(a.result()), Optional.of(a.solution()), Optional.of(a.lesson())));
                if (a.revised()) { // a revision-2 correction that may also change the attempt result
                    EventId correction = EventId.of(prefix + ".fin.r2");
                    EventEnvelope outer = new EventEnvelope(correction, scope, task, exec, Optional.of(attempt), 2,
                            Optional.of(finish), end, end);
                    events.add(new Correction(outer, new AttemptFinished(EventEnvelope.original(correction, scope,
                            task, exec, attempt, end, end), AttemptResult.valueOf(a.revisedResult()),
                            Optional.of(a.revisedSolution()), Optional.of(a.revisedLesson())),
                            "reviewed correction of the attempt result and summaries"));
                }
            }
            Instant at = T0.plusSeconds(lastEnd);
            EventId outcome = EventId.of(e.id() + ".outcome");
            switch (e.outcome()) {
                case ACCEPTED_VALIDATED -> events.add(ExecutionEvent.outcomeRecorded(outcome, scope, task, exec,
                        Outcome.accepted(OutcomeOrigin.VALIDATED, ForgeFixtureRecorder.attemptId(e.outcomeAttempt())),
                        at));
                case ACCEPTED_IMPORTED -> events.add(ExecutionEvent.outcomeRecorded(outcome, scope, task, exec,
                        Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM,
                                ForgeFixtureRecorder.attemptId(e.outcomeAttempt())), at));
                case REJECTED_VALIDATED -> events.add(ExecutionEvent.outcomeRecorded(outcome, scope, task, exec,
                        Outcome.rejected(OutcomeOrigin.VALIDATED), at));
                case CANCELLED -> events.add(ExecutionEvent.outcomeRecorded(outcome, scope, task, exec,
                        Outcome.cancelled(OutcomeOrigin.VALIDATED), at));
                case NONE -> { }
            }
        }
        return events;
    }

    private static QualityObservation observation(MultiAttemptFixture.Attempt a, QualityDimension dimension,
                                                  String state) {
        return new QualityObservation(dimension, ObservationState.valueOf(state), OptionalDouble.empty(),
                Optional.empty(), "forge-fixture-evaluator", "1", POLICY_ID, POLICY_VERSION, ObservationSource.TOOL,
                List.of(ArtifactRef.of("report", "synthetic/" + a.experienceId() + "/" + dimension)),
                Optional.empty());
    }
}
