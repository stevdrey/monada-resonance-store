package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.CostReason.Code;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corrections, duplicate events and the acceptance cutoff: every fact is counted exactly once. */
class RevisionsAndCutoffTest {
    @TempDir
    Path root;

    @Test
    void correctedStageIsCountedOnceWithItsLatestRevision() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            EventId stage = run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(1_000));
            run.correctStage(stage, 2, A1, "implement", 0, 10, in(2_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(1, summary.chainStages().size());
            assertEquals(2_000, summary.chainUsage().byKind().get(UsageKind.INPUT_TOKENS).reported());
            assertEquals(0, summary.chainUsage().byKind().get(UsageKind.OUTPUT_TOKENS).reported());
            assertEquals(new BigDecimal("0.004000"), summary.cost().amounts().get(0).amount());
            assertEquals(Optional.of(java.time.Duration.ofSeconds(10)), summary.chainTime().effort());
        }
    }

    @Test
    void thirdRevisionReplacesTheSecondNotAddsToIt() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            EventId stage = run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            EventId second = run.correctStage(stage, 2, A1, "implement", 0, 10, in(2_000), cached(0), out(0));
            run.correctStage(second, 3, A1, "implement", 0, 10, in(3_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(1, summary.chainStages().size());
            assertEquals(3_000, summary.chainUsage().byKind().get(UsageKind.INPUT_TOKENS).reported());
        }
    }

    @Test
    void stageCorrectedAfterAcceptanceStaysInTheChainByItsOriginalSequence() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            EventId stage = run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            run.correctStage(stage, 2, A1, "implement", 0, 10, in(5_000), cached(0), out(0));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            long cutoff = summary.acceptanceCutoff().orElseThrow();
            StageUsageCost implement = summary.chainStages().get(0);
            assertTrue(implement.sequence() > cutoff, "the correction was appended after acceptance");
            assertTrue(implement.originalSequence() <= cutoff);
            assertTrue(summary.postAcceptanceStages().isEmpty());
            assertEquals(new BigDecimal("0.010000"), summary.cost().amounts().get(0).amount());
        }
    }

    @Test
    void stageExactlyBeforeAndAfterTheCutoffLandsOnTheRightSide() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "before", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            long outcome = run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            run.follow(A2, 2, AttemptReason.REVIEW_FOLLOW_UP, A1);
            run.stage(A2, "after", 20, 30, in(1_000), cached(0), out(0));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(outcome), summary.acceptanceCutoff());
            assertEquals(List.of("before"), summary.chainStages().stream().map(StageUsageCost::stage).toList());
            assertEquals(List.of("after"), summary.postAcceptanceStages().stream().map(StageUsageCost::stage).toList());
            assertEquals(new BigDecimal("0.002000"), summary.cost().amounts().get(0).amount());
        }
    }

    @Test
    void lateValidatingEvidenceMovesTheCutoffAndPullsEarlierWorkIntoTheChain() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            EventId failing = run.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            run.follow(A2, 2, AttemptReason.REVIEW_FOLLOW_UP, A1);
            run.stage(A2, "qa-run", 20, 30, in(1_000), cached(0), out(0));

            ExecutionSummary before = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());
            assertEquals(Code.NO_VALIDATED_ACCEPTANCE, before.cost().reasons().get(0).code());

            long correction = run.sequenceOf(run.correctEvidence(failing, 2, A1,
                    observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty())));
            ExecutionSummary after = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(correction), after.acceptanceCutoff());
            assertEquals(List.of("implement", "qa-run"), after.chainStages().stream().map(StageUsageCost::stage).toList());
            assertEquals(new BigDecimal("0.004000"), after.cost().amounts().get(0).amount());
        }
    }

    @Test
    void evidenceCorrectedToFailRemovesTheValidatedCost() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            EventId passing = run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE,
                    ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).cost().status());

            run.correctEvidence(passing, 2, A1,
                    observation(QualityDimension.TESTS, ObservationState.FAIL, OptionalDouble.empty(), Optional.empty()));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
            assertEquals(ObservationState.FAIL, summary.gates().get(QualityDimension.TESTS.ordinal())
                    .effective().orElseThrow().observation().state());
        }
    }

    @Test
    void duplicateEventIdentityIsIgnoredButEqualContentUnderAnotherIdCounts() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0)); // same content, new event id
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionView view = run.view();

            ExecutionSummary twice = ExecutionSummaryCalculator.summarize(view, book(usd()), List.of());
            assertEquals(2, twice.chainStages().size());
            assertEquals(new BigDecimal("0.004000"), twice.cost().amounts().get(0).amount());
            assertTrue(twice.ignoredDuplicateEvents().isEmpty());

            HistoryEntry stageEntry = view.history().stream()
                    .filter(h -> h.event().eventId().value().startsWith("stage-")).findFirst().orElseThrow();
            List<HistoryEntry> replayed = new ArrayList<>(view.history());
            replayed.add(new HistoryEntry(10_000, stageEntry.event()));
            ExecutionView duplicated = new ExecutionView(view.id(), view.task(), view.taskSummary(), view.provenance(),
                    view.policy(), view.currentOutcome(), view.acceptance(), view.attempts(), replayed);

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(duplicated, book(usd()), List.of());
            assertEquals(twice.chainStages(), summary.chainStages());
            assertEquals(twice.cost(), summary.cost());
            assertEquals(List.of(stageEntry.event().eventId()), summary.ignoredDuplicateEvents());
        }
    }

    @Test
    void unorderedHistoryGivesTheSameSummaryAsOrderedHistory() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionView view = run.view();
            List<HistoryEntry> reversed = new ArrayList<>(view.history());
            java.util.Collections.reverse(reversed);
            ExecutionView shuffled = new ExecutionView(view.id(), view.task(), view.taskSummary(), view.provenance(),
                    view.policy(), view.currentOutcome(), view.acceptance(), view.attempts(), reversed);

            assertEquals(ExecutionSummaryCalculator.summarize(view, book(usd()), List.of()),
                    ExecutionSummaryCalculator.summarize(shuffled, book(usd()), List.of()));
        }
    }

    @Test
    void validatedViewWithoutItsOutcomeInTheHistoryIsUnderivableNotGuessed() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionView view = run.view();
            List<HistoryEntry> withoutOutcome = view.history().stream()
                    .filter(h -> !h.event().eventId().value().startsWith("outcome-")).toList();
            ExecutionView truncated = new ExecutionView(view.id(), view.task(), view.taskSummary(), view.provenance(),
                    view.policy(), view.currentOutcome(), view.acceptance(), view.attempts(), withoutOutcome);

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(truncated, book(usd()), List.of());

            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
            assertEquals(Code.CUTOFF_UNDERIVABLE, summary.cost().reasons().get(0).code());
            assertTrue(summary.acceptanceCutoff().isEmpty());
        }
    }

    @Test
    void parallelAndDisjointIntervalsAreMeasuredSeparately() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "a", 0, 10, in(1), cached(0), out(0));
            run.stage(A1, "b", 0, 10, in(1), cached(0), out(0));  // fully parallel with a
            run.stage(A1, "c", 100, 110, in(1), cached(0), out(0)); // idle gap before c
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            TimeSummary time = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).chainTime();

            assertEquals(Optional.of(java.time.Duration.ofSeconds(30)), time.effort());
            assertEquals(Optional.of(java.time.Duration.ofSeconds(110)), time.wallClock());
            assertEquals(Optional.of(java.time.Duration.ofSeconds(20)), time.activeTime());
            assertEquals(Optional.of(java.time.Duration.ofSeconds(10)), time.parallelOverlap());
        }
    }

    @Test
    void cutoffIncludesTheCompletionOfTheAcceptedAttemptRecordedAfterTheOutcome() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "first", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1)); // attempt still open
            run.stage(A1, "second", 10, 20, in(4_000), cached(0), out(0));
            EventId finish = run.finishEvent(A1, AttemptResult.COMPLETED);

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(run.sequenceOf(finish)), summary.acceptanceCutoff());
            assertEquals(List.of("first", "second"),
                    summary.chainStages().stream().map(StageUsageCost::stage).toList());
            assertTrue(summary.postAcceptanceStages().isEmpty());
            assertEquals(new BigDecimal("0.010000"), summary.cost().amounts().get(0).amount());
        }
    }

    @Test
    void finishCorrectedToCompletedEstablishesTheCutoffAndCountsEarlierWorkOnce() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "first", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            EventId failed = run.finishEvent(A1, AttemptResult.FAILED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            run.follow(A2, 2, AttemptReason.REVIEW_FOLLOW_UP, A1);
            run.stage(A2, "extra", 20, 30, in(4_000), cached(0), out(0));

            ExecutionSummary before = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());
            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, before.cost().status());

            EventId correction = run.correctFinish(failed, 2, A1, AttemptResult.COMPLETED);
            ExecutionSummary after = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(run.sequenceOf(correction)), after.acceptanceCutoff());
            assertEquals(List.of("first", "extra"),
                    after.chainStages().stream().map(StageUsageCost::stage).toList());
            assertTrue(after.postAcceptanceStages().isEmpty());
            assertEquals(new BigDecimal("0.010000"), after.cost().amounts().get(0).amount());
        }
    }

    @Test
    void validatedViewWhoseHistoryLacksTheCompletedFinishIsUnderivable() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "first", 0, 10, in(1_000), cached(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionView view = run.view();
            List<HistoryEntry> withoutFinish = view.history().stream()
                    .filter(h -> !h.event().eventId().value().startsWith("fin-")).toList();
            ExecutionView truncated = new ExecutionView(view.id(), view.task(), view.taskSummary(), view.provenance(),
                    view.policy(), view.currentOutcome(), view.acceptance(), view.attempts(), withoutFinish);

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(truncated, book(usd()), List.of());

            assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, summary.cost().status());
            assertEquals(Code.CUTOFF_UNDERIVABLE, summary.cost().reasons().get(0).code());
            assertTrue(summary.acceptanceCutoff().isEmpty());
        }
    }
}
