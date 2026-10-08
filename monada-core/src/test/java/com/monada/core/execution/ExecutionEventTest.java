package com.monada.core.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.monada.core.execution.Fixtures.at;
import static com.monada.core.execution.Fixtures.env;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEventTest {

    @Test
    void envelopeChronologyAndRevisionRules() {
        var id = EventId.of("e1");
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id, Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("x"), Optional.empty(), 1, Optional.empty(), at(5), at(4)));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id, Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("x"), Optional.empty(), 0, Optional.empty(), at(1), at(1)));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id, Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("x"), Optional.empty(), 1, Optional.of(EventId.of("e0")), at(1), at(1)));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id, Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("x"), Optional.empty(), 2, Optional.empty(), at(1), at(1)));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(id, Fixtures.SCOPE, Fixtures.TASK,
                ExecutionId.of("x"), Optional.empty(), 2, Optional.of(id), at(1), at(1)));
        assertEquals(at(1), EventEnvelope.original(id, Fixtures.SCOPE, Fixtures.TASK, ExecutionId.of("x"),
                at(1), at(1)).recordedAt(), "equal times are valid");
    }

    @Test
    void validExecutionStartedCarriesCallerSuppliedValues() {
        var event = Fixtures.started("evt-1", "exec-1");
        assertEquals(EventKind.EXECUTION_STARTED, event.kind());
        assertEquals(Fixtures.at(0), event.envelope().occurredAt());
        assertTrue(event.envelope().attemptId().isEmpty());
        assertEquals(Fixtures.TASK_TEXT, event.taskSummary());
    }

    @Test
    void attemptPresenceFollowsEventKind() {
        var withAttempt = env("e", "x", "a1", 1, 1);
        var without = EventEnvelope.original(EventId.of("e"), Fixtures.SCOPE, Fixtures.TASK, ExecutionId.of("x"),
                at(1), at(1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.ExecutionStarted(withAttempt,
                "s", Fixtures.provenance(), Fixtures.policy()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.OutcomeRecorded(withAttempt,
                Outcome.pending(OutcomeOrigin.VALIDATED)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptFinished(without,
                AttemptResult.COMPLETED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.EvidenceRecorded(without,
                List.of(Fixtures.tests(ObservationState.PASS)), List.of()));
    }

    @Test
    void attemptStartedRules() {
        assertEquals(AttemptReason.INITIAL, Fixtures.attemptStarted("e", "x", "a1", 1, null).reason());
        assertEquals(Optional.of(AttemptId.of("a1")),
                Fixtures.attemptStarted("e", "x", "a2", 2, "a1").previousAttempt());
        assertThrows(IllegalArgumentException.class, () -> Fixtures.attemptStarted("e", "x", "a1", 0, null));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.attemptStarted("e", "x", "a1", 2, "a1"));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.attemptStarted("e", "x", "a1", 5, null));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.attemptStarted("e", "x", "a2", 1, "a1"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptStarted(
                env("e", "x", "a2", 1, 1), 2, Optional.empty(), AttemptReason.RETRY));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptStarted(
                env("e", "x", "a2", 1, 1), 1, Optional.of(AttemptId.of("a1")), AttemptReason.INITIAL));
    }

    @Test
    void stageRulesTimeOrderingAndUsageDeclaration() {
        var counters = List.of(UsageCounter.reported(UsageKind.INPUT_TOKENS, 100, "provider"));
        var stage = Fixtures.stage("s1", "x", "a1", counters);
        assertEquals(UsageDeclaration.MEASURED, stage.usageDeclaration());

        assertThrows(IllegalArgumentException.class, () -> ExecutionEvent.StageRecorded.measured(
                env("late", "x", "a1", 2, 3), "plan", Fixtures.route(), at(2), at(4), List.of(), List.of()),
                "a stage cannot end after it was recorded");

        var noCounters = Fixtures.stage("s2", "x", "a1", List.of());
        assertTrue(noCounters.usage().isEmpty(), "missing counters stay empty, not zero");

        assertThrows(IllegalArgumentException.class, () -> ExecutionEvent.StageRecorded.measured(
                env("s3", "x", "a1", 2, 3), "plan", Fixtures.route(), at(10), at(9), List.of(), List.of()));

        var none = ExecutionEvent.StageRecorded.noBillableUsage(env("s4", "x", "a1", 2, 3), "lint",
                RouteDescriptor.workerOnly("local-check"), at(2), at(3), "deterministic local check", List.of());
        assertEquals(UsageDeclaration.NO_BILLABLE_USAGE, none.usageDeclaration());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.StageRecorded(env("s5", "x", "a1", 2, 3),
                "lint", Fixtures.route(), at(2), at(3), UsageDeclaration.NO_BILLABLE_USAGE, Optional.empty(),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.StageRecorded(env("s6", "x", "a1", 2, 3),
                "lint", Fixtures.route(), at(2), at(3), UsageDeclaration.NO_BILLABLE_USAGE, Optional.of("why"),
                counters, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.StageRecorded(env("s7", "x", "a1", 2, 3),
                "lint", Fixtures.route(), at(2), at(3), UsageDeclaration.MEASURED, Optional.of("why"),
                List.of(), List.of()));
    }

    @Test
    void stageRejectsDuplicateCounterKindsAndTooManyEntries() {
        var dup = List.of(UsageCounter.reported(UsageKind.REQUESTS, 1, "s"),
                UsageCounter.estimated(UsageKind.REQUESTS, 2, "s"));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.stage("s", "x", "a1", dup));

        List<ArtifactRef> tooMany = IntStream.rangeClosed(0, ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT)
                .mapToObj(i -> ArtifactRef.of("log", "ref-" + i)).toList();
        assertThrows(IllegalArgumentException.class, () -> ExecutionEvent.StageRecorded.measured(
                env("s", "x", "a1", 2, 3), "plan", Fixtures.route(), at(2), at(3), List.of(), tooMany));
        var exactly = tooMany.subList(0, ExecutionLimits.MAX_ARTIFACT_REFS_PER_EVENT);
        assertEquals(64, ExecutionEvent.StageRecorded.measured(env("s", "x", "a1", 2, 3), "plan",
                Fixtures.route(), at(2), at(3), List.of(), exactly).artifacts().size());
    }

    @Test
    void evidenceRules() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.evidence("v", "x", "a1"));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.evidence("v", "x", "a1",
                Fixtures.tests(ObservationState.PASS), Fixtures.tests(ObservationState.FAIL)));
        var ok = Fixtures.evidence("v", "x", "a1", Fixtures.tests(ObservationState.UNKNOWN));
        assertEquals(ObservationState.UNKNOWN, ok.observations().get(0).state());
    }

    @Test
    void summariesAndJustificationsMadeOnlyOfUnicodeSpacesAreBlank() {
        for (String blank : new String[]{"\u00A0", "\u202F\u202F", "\u3000 \t\n", "   ", "\u0085", "\u0085\u00A0"}) {
            assertThrows(IllegalArgumentException.class, () -> ExecutionEvent.executionStarted(EventId.of("e"),
                    Fixtures.SCOPE, Fixtures.TASK, ExecutionId.of("x"), Fixtures.provenance(), Fixtures.policy(),
                    blank, at(0)));
            assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptFinished(
                    env("f", "x", "a1", 1, 1), AttemptResult.FAILED, Optional.of(blank), Optional.empty()));
            var ex = assertThrows(IllegalArgumentException.class, () -> ExecutionEvent.StageRecorded.noBillableUsage(
                    env("s", "x", "a1", 1, 2), "lint", Fixtures.route(), at(1), at(2), blank, List.of()));
            assertTrue(ex.getMessage().contains("justification"), ex.getMessage());
        }
        assertEquals(UsageDeclaration.NO_BILLABLE_USAGE, ExecutionEvent.StageRecorded.noBillableUsage(
                env("s", "x", "a1", 1, 2), "lint", Fixtures.route(), at(1), at(2), "deterministic check",
                List.of()).usageDeclaration(), "same timestamps are valid with a real justification");
        assertEquals("a\u00A0b", new ExecutionEvent.AttemptFinished(env("f", "x", "a1", 1, 1),
                AttemptResult.FAILED, Optional.of("a\u00A0b"), Optional.empty()).solutionSummary().orElseThrow());
    }

    @Test
    void evidenceCountsEventAndObservationReferencesTogether() {
        List<ArtifactRef> refs33 = IntStream.range(0, 33).mapToObj(i -> ArtifactRef.of("log", "r" + i)).toList();
        List<ArtifactRef> refs64 = IntStream.range(0, 64).mapToObj(i -> ArtifactRef.of("log", "r" + i)).toList();
        QualityObservation tests33 = withEvidence(QualityDimension.TESTS, refs33);
        QualityObservation security33 = withEvidence(QualityDimension.SECURITY, refs33);
        var envelope = env("v", "x", "a1", 1, 1);

        assertThrows(IllegalArgumentException.class, () ->
                new ExecutionEvent.EvidenceRecorded(envelope, List.of(tests33, security33), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.EvidenceRecorded(envelope,
                List.of(withEvidence(QualityDimension.TESTS, refs64)), List.of(ArtifactRef.of("log", "extra"))));
        assertEquals(64, new ExecutionEvent.EvidenceRecorded(envelope,
                List.of(withEvidence(QualityDimension.TESTS, refs64)), List.of()).observations().get(0).evidence().size());
        assertEquals(33, new ExecutionEvent.EvidenceRecorded(envelope, List.of(tests33), refs33.subList(0, 31))
                .observations().get(0).evidence().size());
    }

    private static QualityObservation withEvidence(QualityDimension dimension, List<ArtifactRef> evidence) {
        return new QualityObservation(dimension, ObservationState.PASS, java.util.OptionalDouble.empty(),
                Optional.empty(), "t", "1", "forge-gates", "1", ObservationSource.TOOL, evidence, Optional.empty());
    }

    @Test
    void attemptFinishedAllowsUnicodeSummariesAndRejectsBlankOrOversized() {
        var finished = new ExecutionEvent.AttemptFinished(env("f", "x", "a1", 80, 81), AttemptResult.COMPLETED,
                Optional.of("Solución: 実装 🚀"), Optional.of("Lección\nlínea 2"));
        assertEquals("Solución: 実装 🚀", finished.solutionSummary().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptFinished(
                env("f", "x", "a1", 80, 81), AttemptResult.FAILED, Optional.of("  "), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.AttemptFinished(
                env("f", "x", "a1", 80, 81), AttemptResult.FAILED,
                Optional.of("x".repeat(ExecutionLimits.MAX_SUMMARY_CODE_POINTS + 1)), Optional.empty()));
        var max = "x".repeat(ExecutionLimits.MAX_SUMMARY_CODE_POINTS);
        assertEquals(max, new ExecutionEvent.AttemptFinished(env("f", "x", "a1", 80, 81), AttemptResult.FAILED,
                Optional.of(max), Optional.empty()).solutionSummary().orElseThrow());
    }

    @Test
    void correctionRules() {
        var original = Fixtures.evidence("v1", "x", "a1", Fixtures.tests(ObservationState.PASS));
        var replacementEnv = env("v2", "x", "a1", 90, 91);
        var correctionEnv = new EventEnvelope(EventId.of("v2"), Fixtures.SCOPE, Fixtures.TASK, ExecutionId.of("x"),
                Optional.of(AttemptId.of("a1")), 2, Optional.of(original.eventId()), at(90), at(91));
        var replacement = new ExecutionEvent.EvidenceRecorded(replacementEnv,
                List.of(Fixtures.tests(ObservationState.FAIL)), List.of());

        var correction = new ExecutionEvent.Correction(correctionEnv, replacement, "tests were re-run");
        assertEquals(EventKind.CORRECTION, correction.kind());
        assertEquals(2, correction.envelope().revision());

        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent.Correction(replacementEnv, replacement, "revision 1 is not a correction"));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent.Correction(correctionEnv, replacement, " "));
        var otherExecution = new ExecutionEvent.EvidenceRecorded(env("v2", "other", "a1", 90, 91),
                List.of(Fixtures.tests(ObservationState.FAIL)), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent.Correction(correctionEnv, otherExecution, "wrong execution"));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent.Correction(correctionEnv, correction, "nested"));
    }

    @Test
    void onlyCorrectionsMayCarryRevisionAbove1() {
        var revised = new EventEnvelope(EventId.of("e2"), Fixtures.SCOPE, Fixtures.TASK, ExecutionId.of("x"),
                Optional.empty(), 2, Optional.of(EventId.of("e1")), at(1), at(1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionEvent.OutcomeRecorded(revised, Outcome.pending(OutcomeOrigin.VALIDATED)));
    }

    @Test
    void mutableCallerListsAreCopied() {
        List<UsageCounter> usage = new ArrayList<>(List.of(UsageCounter.reported(UsageKind.REQUESTS, 1, "s")));
        var stage = Fixtures.stage("s", "x", "a1", usage);
        usage.add(UsageCounter.reported(UsageKind.TOOL_CALLS, 2, "s"));
        assertEquals(1, stage.usage().size());
        assertThrows(UnsupportedOperationException.class, () -> stage.usage().add(usage.get(1)));

        List<QualityObservation> obs = new ArrayList<>(List.of(Fixtures.tests(ObservationState.PASS)));
        var evidence = new ExecutionEvent.EvidenceRecorded(env("v", "x", "a1", 1, 1), obs, List.of());
        obs.clear();
        assertEquals(1, evidence.observations().size());
        assertThrows(UnsupportedOperationException.class, () -> evidence.observations().clear());
    }
}
