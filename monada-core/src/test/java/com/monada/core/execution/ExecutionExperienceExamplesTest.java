package com.monada.core.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.monada.core.execution.Fixtures.at;
import static com.monada.core.execution.Fixtures.env;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worked examples from the contract: two comparable executions of the same task text, plus an
 * incomplete and a cancelled one. No clock, no randomness: every value below is explicit.
 */
class ExecutionExperienceExamplesTest {

    private static List<ExecutionEvent> comparableExecution(String execution, boolean needsRepair) {
        String x = execution;
        var events = new java.util.ArrayList<ExecutionEvent>();
        events.add(Fixtures.started(x + "-e1", x));
        events.add(Fixtures.attemptStarted(x + "-e2", x, x + "-a1", 1, null));
        events.add(Fixtures.stage(x + "-e3", x, x + "-a1", List.of(
                UsageCounter.reported(UsageKind.INPUT_TOKENS, 12_000, "provider-a"),
                UsageCounter.reported(UsageKind.OUTPUT_TOKENS, 3_000, "provider-a"))));
        String finalAttempt = x + "-a1";
        if (needsRepair) {
            events.add(Fixtures.evidence(x + "-e4", x, x + "-a1", Fixtures.tests(ObservationState.FAIL)));
            events.add(new ExecutionEvent.AttemptFinished(env(x + "-e5", x, x + "-a1", 80, 81),
                    AttemptResult.FAILED, Optional.empty(), Optional.of("Tests failed on empty input")));
            finalAttempt = x + "-a2";
            events.add(Fixtures.attemptStarted(x + "-e6", x, finalAttempt, 2, x + "-a1"));
            events.add(Fixtures.stage(x + "-e7", x, finalAttempt, List.of(
                    UsageCounter.reported(UsageKind.INPUT_TOKENS, 8_000, "provider-a"),
                    UsageCounter.estimated(UsageKind.OUTPUT_TOKENS, 1_500, "local-estimator"))));
        }
        events.add(Fixtures.evidence(x + "-e8", x, finalAttempt, Fixtures.tests(ObservationState.PASS)));
        events.add(new ExecutionEvent.AttemptFinished(env(x + "-e9", x, finalAttempt, 120, 121),
                AttemptResult.COMPLETED, Optional.of("Records implemented"), Optional.empty()));
        events.add(ExecutionEvent.outcomeRecorded(EventId.of(x + "-e10"), Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of(x), Outcome.accepted(OutcomeOrigin.VALIDATED, AttemptId.of(finalAttempt)), at(130)));
        return events;
    }

    @Test
    void twoComparableAttemptsShareTaskAndProvenanceButHaveDistinctIdentities() {
        var direct = comparableExecution("exec-direct", false);
        var repaired = comparableExecution("exec-repaired", true);

        var a = (ExecutionEvent.ExecutionStarted) direct.get(0);
        var b = (ExecutionEvent.ExecutionStarted) repaired.get(0);
        assertEquals(a.taskSummary(), b.taskSummary(), "identical task text");
        assertEquals(a.provenance(), b.provenance(), "comparable: same revision/context/constraints");
        assertEquals(a.policy(), b.policy());
        assertNotEquals(a.executionId(), b.executionId());

        assertEquals(6, direct.size());
        assertEquals(10, repaired.size());
        assertEquals(2, repaired.stream().filter(e -> e instanceof ExecutionEvent.AttemptStarted).count());
        assertEquals(1, direct.stream().filter(e -> e instanceof ExecutionEvent.AttemptStarted).count());

        // The repaired execution keeps a mixed-provenance usage trail: reported and estimated stay labelled.
        var repairStage = (ExecutionEvent.StageRecorded) repaired.get(6);
        assertEquals(List.of(UsageProvenance.REPORTED, UsageProvenance.ESTIMATED),
                repairStage.usage().stream().map(UsageCounter::provenance).toList());
    }

    @Test
    void incompleteExecutionKeepsUnknownAndMissingValuesDistinctFromZeroAndPass() {
        String x = "exec-incomplete";
        var stage = Fixtures.stage(x + "-e3", x, x + "-a1", List.of(
                UsageCounter.unknown(UsageKind.OUTPUT_TOKENS, "provider-a")));
        var evidence = Fixtures.evidence(x + "-e4", x, x + "-a1", Fixtures.tests(ObservationState.UNKNOWN));

        assertTrue(stage.usage().get(0).value().isEmpty(), "unknown usage has no value");
        assertTrue(stage.usage().stream().noneMatch(c -> c.kind() == UsageKind.INPUT_TOKENS),
                "INPUT_TOKENS is not measured: absent, not zero");
        assertEquals(ObservationState.UNKNOWN, evidence.observations().get(0).state());
        // No AttemptFinished and no OutcomeRecorded were recorded: replay later reports IN_PROGRESS / PENDING.
        assertEquals(Fixtures.at(0), Fixtures.started(x + "-e1", x).envelope().occurredAt());
    }

    @Test
    void cancelledExecutionIsRepresentedWithoutAnAcceptedAttempt() {
        String x = "exec-cancelled";
        var finished = new ExecutionEvent.AttemptFinished(env(x + "-e5", x, x + "-a1", 40, 41),
                AttemptResult.CANCELLED, Optional.empty(), Optional.of("Cancelled by the host before tests ran"));
        var outcome = ExecutionEvent.outcomeRecorded(EventId.of(x + "-e6"), Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of(x), Outcome.cancelled(OutcomeOrigin.VALIDATED), at(42));

        assertEquals(AttemptResult.CANCELLED, finished.result());
        assertEquals(OutcomeStatus.CANCELLED, outcome.outcome().status());
        assertTrue(outcome.outcome().acceptedAttempt().isEmpty());
    }

    @Test
    void identicalInputsProduceEqualRecords() {
        assertEquals(comparableExecution("exec-1", true), comparableExecution("exec-1", true));
        assertEquals(comparableExecution("exec-1", true).hashCode(), comparableExecution("exec-1", true).hashCode());
        assertNotEquals(comparableExecution("exec-1", true), comparableExecution("exec-2", true));
    }
}
