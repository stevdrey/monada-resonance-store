package com.monada.evaluation.execution;

import com.monada.api.execution.AttemptView;
import com.monada.api.execution.ExecutionView;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.EvidenceRecorded;
import com.monada.core.execution.ExecutionEvent.OutcomeRecorded;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.UsageKind;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chain scope labelling, not-measured semantics, gate edge cases and parity with the facade's own view. */
class ChainScopeGatesAndParityTest {
    @TempDir
    Path root;

    @Test
    void stagesAfterARejectionAreLabelledAsNoAcceptanceScopeNotAsAnAcceptedChain() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.finish(A1, AttemptResult.FAILED);
            run.outcome(Outcome.rejected(OutcomeOrigin.VALIDATED));
            run.follow(A2, 2, com.monada.core.execution.AttemptReason.RETRY, A1);
            run.stage(A2, "retry", 20, 30, in(1_000), cached(0), out(0));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(ChainScope.ALL_RECORDED_NO_ACCEPTANCE, summary.chainScope());
            assertEquals(2, summary.chainStages().size());
            assertTrue(summary.postAcceptanceStages().isEmpty());
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
        }
    }

    @Test
    void validatedChainIsScopedUpToAcceptance() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            assertEquals(ChainScope.UP_TO_ACCEPTANCE,
                    ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).chainScope());
        }
    }

    @Test
    void unpricedKindsAreNotReportedAsNotMeasuredButPricedGapsAre() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(100));
            run.stage(A1, "review", 10, 20, in(1_000), cached(0)); // output priced but not measured
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            UsageTotals totals = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).chainUsage();

            assertEquals(1, totals.byKind().get(UsageKind.OUTPUT_TOKENS).notMeasuredStages());
            assertEquals(0, totals.byKind().get(UsageKind.INPUT_TOKENS).notMeasuredStages());
            assertEquals(0, totals.byKind().get(UsageKind.REQUESTS).notMeasuredStages());
            assertEquals(0, totals.byKind().get(UsageKind.TOOL_CALLS).notMeasuredStages());
            assertEquals(0, totals.byKind().get(UsageKind.REASONING_TOKENS).notMeasuredStages());
        }
    }

    @Test
    void stageWithoutSnapshotIsNotCountedAsNotMeasuredBecauseItsGapIsTheMissingPrice() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10);
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), PriceBook.empty(), List.of());

            assertEquals(0, summary.chainUsage().byKind().get(UsageKind.INPUT_TOKENS).notMeasuredStages());
            assertEquals(CostReason.Code.MISSING_PRICE, summary.cost().reasons().get(0).code());
        }
    }

    @Test
    void justifiedNotApplicableSatisfiesTheMandatoryGateAndSetsTheCutoff() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.NOT_APPLICABLE);
            run.finish(A1, AttemptResult.COMPLETED);
            long outcome = run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(outcome), summary.acceptanceCutoff());
            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, summary.cost().status());
            GateEvidence tests = summary.gates().get(QualityDimension.TESTS.ordinal());
            assertEquals(ObservationState.NOT_APPLICABLE, tests.effective().orElseThrow().observation().state());
            assertTrue(tests.effective().orElseThrow().observation().justification().isPresent());
        }
    }

    @Test
    void laterUnknownObservationOverridesEarlierPassAndMakesTheCostUnavailable() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            EventId later = run.evidence(A1, QualityDimension.TESTS, ObservationState.UNKNOWN);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            GateEvidence.Observed tests = summary.gates().get(QualityDimension.TESTS.ordinal()).effective().orElseThrow();
            assertEquals(ObservationState.UNKNOWN, tests.observation().state());
            assertEquals(run.sequenceOf(later), tests.sequence());
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
        }
    }

    @Test
    void evidenceUnderAnotherPolicyVersionIsNotApplicable() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, new com.monada.core.execution.QualityObservation(QualityDimension.TESTS,
                    ObservationState.PASS, OptionalDouble.empty(), Optional.empty(), "junit", "5", "policy", "2",
                    com.monada.core.execution.ObservationSource.TOOL, List.of(), Optional.empty()));
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertTrue(summary.gates().get(QualityDimension.TESTS.ordinal()).effective().isEmpty());
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
        }
    }

    @Test
    void executionWithoutAnAcceptedAttemptReportsEveryGateAsAbsent() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);

            List<GateEvidence> gates = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).gates();

            assertEquals(QualityDimension.values().length, gates.size());
            for (GateEvidence gate : gates) {
                assertTrue(gate.effective().isEmpty());
                assertEquals("no accepted attempt", gate.absentReason().orElseThrow());
            }
            assertTrue(gates.get(QualityDimension.TESTS.ordinal()).mandatory());
            assertFalse(gates.get(QualityDimension.SECURITY.ordinal()).mandatory());
        }
    }

    /**
     * ChainResolver re-implements the facade's correction resolution because the facade's helper is not public.
     * This parity check fails if the two ever disagree about which revision of a fact is effective.
     */
    @Test
    void effectiveEventsMatchTheFacadeViewAfterCorrections() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            EventId stage = run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            EventId second = run.correctStage(stage, 2, A1, "implement", 0, 10, in(2_000), cached(0), out(0));
            run.correctStage(second, 3, A1, "implement", 0, 10, in(3_000), cached(0), out(0));
            run.stage(A1, "review", 10, 20, in(5), cached(0), out(0));
            EventId evidence = run.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            run.correctEvidence(evidence, 2, A1,
                    observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()));
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.pending(OutcomeOrigin.VALIDATED));
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionView view = run.view();

            ChainResolver.Resolved resolved = ChainResolver.resolve(view.history());

            Set<ExecutionEvent> resolverStages = new HashSet<>();
            Set<ExecutionEvent> resolverEvidence = new HashSet<>();
            Optional<Outcome> resolverOutcome = Optional.empty();
            for (ChainResolver.Effective e : resolved.effective()) {
                if (e.event() instanceof StageRecorded) {
                    resolverStages.add(e.event());
                } else if (e.event() instanceof EvidenceRecorded) {
                    resolverEvidence.add(e.event());
                } else if (e.event() instanceof OutcomeRecorded o) {
                    resolverOutcome = Optional.of(o.outcome());
                }
            }
            Set<ExecutionEvent> viewStages = new HashSet<>();
            Set<ExecutionEvent> viewEvidence = new HashSet<>();
            for (AttemptView attempt : view.attempts()) {
                viewStages.addAll(attempt.stages());
                viewEvidence.addAll(attempt.evidence());
            }

            assertEquals(viewStages, resolverStages);
            assertEquals(viewEvidence, resolverEvidence);
            assertEquals(view.currentOutcome(), resolverOutcome);
            assertEquals(2, resolverStages.size());
        }
    }
}
