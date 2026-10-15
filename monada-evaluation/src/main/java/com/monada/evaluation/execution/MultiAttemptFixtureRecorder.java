package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@link MultiAttemptFixture} into real ledger events and records them through the public
 * {@link ExecutionMemory} API, with the event builders and id scheme of {@link ForgeFixtureRecorder}
 * ({@code <execution>.start}, {@code <execution>.a<n>.start|stage|evidence|fin|fin.r2}, {@code <execution>.outcome}).
 * Unlike the version-1 fixture it records attempts without a finish, cancelled outcomes and corrections that change
 * the attempt result. Every id, time and price comes from the fixture, so the same fixture always produces the same
 * events in the same order.
 */
final class MultiAttemptFixtureRecorder {
    static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");
    private static final String JUSTIFICATION = "reviewed correction of the attempt result and summaries";

    private MultiAttemptFixtureRecorder() {
    }

    static ScopeId scope(MultiAttemptFixture fixture, String scopeKey) {
        return ScopeId.of(fixture.scopeId(scopeKey));
    }

    static PriceSnapshot priceSnapshot(MultiAttemptFixture fixture) {
        return ForgeFixtureRecorder.priceSnapshot(fixture.price(), "forge multi-attempt fixture v" + fixture.version());
    }

    /** Records every event of one project, in fixture order, and returns each recording outcome. */
    static List<RecordResult> recordScope(ExecutionMemory memory, MultiAttemptFixture fixture, String scopeKey) {
        return ForgeFixtureRecorder.record(memory, events(fixture, scopeKey));
    }

    /** All events of one project in recording order. */
    static List<ExecutionEvent> events(MultiAttemptFixture fixture, String scopeKey) {
        List<ExecutionEvent> events = new ArrayList<>();
        ScopeId scope = scope(fixture, scopeKey);
        RouteDescriptor route = ForgeFixtureRecorder.route(fixture.price());
        for (MultiAttemptFixture.Execution e : fixture.executionsOf(scopeKey)) {
            TaskId task = TaskId.of(e.task());
            ExecutionId exec = ExecutionId.of(e.id());
            events.add(ForgeFixtureRecorder.executionStarted(scope, task, exec, e.sourceRevision(),
                    e.contextFingerprint(), e.constraintsFingerprint(), e.mandatory(), e.summary(), T0));
            long lastEnd = 0;
            for (MultiAttemptFixture.Attempt a : fixture.attemptsOf(e.id())) {
                Instant end = T0.plusSeconds(a.endSeconds());
                events.addAll(ForgeFixtureRecorder.attemptOpened(scope, task, exec, a.experienceId(), a.ordinal(),
                        a.reason(), T0.plusSeconds(a.startSeconds()), end, route, a.inputTokens(), a.outputTokens(),
                        a.evidence()));
                lastEnd = Math.max(lastEnd, a.endSeconds());
                if (!a.finished()) { // started, never finished: no ATTEMPT_FINISHED fact exists to recall
                    continue;
                }
                events.addAll(ForgeFixtureRecorder.attemptFinished(scope, task, exec, a.ordinal(), end,
                        AttemptResult.valueOf(a.result()), a.solution(), a.lesson(), a.revised()
                                ? Optional.of(new ForgeFixtureRecorder.Revision(AttemptResult.valueOf(a.revisedResult()),
                                a.revisedSolution(), a.revisedLesson(), JUSTIFICATION))
                                : Optional.empty()));
            }
            Instant at = T0.plusSeconds(lastEnd);
            outcome(e).ifPresent(o -> events.add(ForgeFixtureRecorder.outcome(scope, task, exec, o, at)));
        }
        return events;
    }

    private static Optional<Outcome> outcome(MultiAttemptFixture.Execution e) {
        return switch (e.outcome()) {
            case ACCEPTED_VALIDATED -> Optional.of(Outcome.accepted(OutcomeOrigin.VALIDATED,
                    ForgeFixtureRecorder.attemptId(e.outcomeAttempt())));
            case ACCEPTED_IMPORTED -> Optional.of(Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM,
                    ForgeFixtureRecorder.attemptId(e.outcomeAttempt())));
            case REJECTED_VALIDATED -> Optional.of(Outcome.rejected(OutcomeOrigin.VALIDATED));
            case CANCELLED -> Optional.of(Outcome.cancelled(OutcomeOrigin.VALIDATED));
            case NONE -> Optional.empty();
        };
    }
}
