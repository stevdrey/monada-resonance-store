package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.CostReason.Code;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Completeness rules: unavailable and partial costs carry reasons and never a fabricated zero. */
class CostToAcceptedOutcomeStatusTest {
    @TempDir
    Path root;

    private static PriceBook inputOutput() {
        return book(snapshot("prices", "1", "prov", "model-x", "USD", LocalDate.parse("2025-12-01"),
                PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("2"), 1_000_000),
                PriceLine.of(UsageKind.OUTPUT_TOKENS, new BigDecimal("10"), 1_000_000)));
    }

    /** Attempt a1 with one fully metered stage (0.002 + 0.001 = 0.003 USD) and passing TESTS, finished. */
    private Run base(ObservationState tests, AttemptResult result) {
        Run run = new Run(root, Set.of(QualityDimension.TESTS));
        run.initial(A1);
        run.stage(A1, "implement", 0, 10, in(1_000), out(100));
        run.evidence(A1, QualityDimension.TESTS, tests);
        run.finish(A1, result);
        return run;
    }

    private static CostToAcceptedOutcome cost(Run run, PriceBook book) {
        return ExecutionSummaryCalculator.summarize(run.view(), book, List.of()).cost();
    }

    private static void assertUnavailable(CostToAcceptedOutcome cost, Code code) {
        assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, cost.status());
        assertTrue(cost.amounts().isEmpty(), "unavailable must not carry an amount, not even zero");
        assertFalse(cost.isLowerBound());
        assertEquals(code, cost.reasons().get(0).code());
        assertEquals(CostBasis.HYPOTHETICAL_API_EQUIVALENT, cost.basis());
    }

    @Test
    void pendingChainHasNoValidatedCost() {
        try (Run run = base(ObservationState.PASS, AttemptResult.COMPLETED)) {
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), inputOutput(), List.of());
            assertUnavailable(summary.cost(), Code.NO_VALIDATED_ACCEPTANCE);
            assertTrue(summary.acceptanceCutoff().isEmpty());
            assertEquals(1, summary.chainStages().size(), "recorded work stays visible");
        }
    }

    @Test
    void rejectedFailedAndCancelledOutcomesHaveNoValidatedCost() {
        for (Outcome outcome : List.of(Outcome.rejected(OutcomeOrigin.VALIDATED),
                Outcome.failed(OutcomeOrigin.VALIDATED), Outcome.cancelled(OutcomeOrigin.VALIDATED),
                Outcome.pending(OutcomeOrigin.VALIDATED))) {
            try (Run run = new Run(root.resolve(outcome.status().name()), Set.of(QualityDimension.TESTS))) {
                run.initial(A1);
                run.stage(A1, "implement", 0, 10, in(1_000), out(100));
                run.finish(A1, AttemptResult.FAILED);
                run.outcome(outcome);
                assertUnavailable(cost(run, inputOutput()), Code.NO_VALIDATED_ACCEPTANCE);
            }
        }
    }

    @Test
    void importedClaimAndFailingGateAreNotValidatedAcceptance() {
        try (Run run = base(ObservationState.PASS, AttemptResult.COMPLETED)) {
            run.outcome(Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, A1));
            assertUnavailable(cost(run, inputOutput()), Code.NO_VALIDATED_ACCEPTANCE);
        }
    }

    @Test
    void acceptedWithFailingMandatoryEvidenceIsUnvalidated() {
        try (Run run = base(ObservationState.FAIL, AttemptResult.COMPLETED)) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), inputOutput(), List.of());
            assertUnavailable(summary.cost(), Code.NO_VALIDATED_ACCEPTANCE);
            assertTrue(summary.cost().reasons().get(0).detail().contains("TESTS"));
            assertEquals(ObservationState.FAIL, summary.gates().get(QualityDimension.TESTS.ordinal())
                    .effective().orElseThrow().observation().state());
        }
    }

    @Test
    void validatedChainWithoutStagesIsUnavailableNotZero() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), inputOutput(), List.of());
            assertUnavailable(summary.cost(), Code.NO_USAGE_RECORDED);
            assertTrue(summary.chainTime().effort().isEmpty());
            assertTrue(summary.chainTime().wallClock().isEmpty());
            assertEquals("no stage recorded", summary.chainTime().unavailableReason().orElseThrow());
        }
    }

    @Test
    void stageWithoutCountersMakesTheCostPartialWithALowerBound() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), out(100));
            run.stage(A1, "review", 10, 20);
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            CostToAcceptedOutcome cost = cost(run, inputOutput());

            assertEquals(CostToAcceptedOutcome.Status.PARTIAL, cost.status());
            assertTrue(cost.isLowerBound());
            assertEquals(new BigDecimal("0.003000"), cost.amounts().get(0).amount());
            assertEquals(List.of(Code.MISSING_USAGE, Code.MISSING_USAGE),
                    cost.reasons().stream().map(CostReason::code).toList());
            assertEquals(List.of(UsageKind.INPUT_TOKENS, UsageKind.OUTPUT_TOKENS),
                    cost.reasons().stream().map(r -> r.kind().orElseThrow()).toList());
            assertEquals("review", cost.reasons().get(0).stage().orElseThrow());
        }
    }

    @Test
    void unknownOutputProvenanceIsPartialWithTheInputCostAsLowerBound() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000), UsageCounter.unknown(UsageKind.OUTPUT_TOKENS, "provider"));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), inputOutput(), List.of());

            assertEquals(CostToAcceptedOutcome.Status.PARTIAL, summary.cost().status());
            assertEquals(new BigDecimal("0.002000"), summary.cost().amounts().get(0).amount());
            assertEquals(Code.UNKNOWN_USAGE, summary.cost().reasons().get(0).code());
            assertEquals(1, summary.chainUsage().byKind().get(UsageKind.OUTPUT_TOKENS).unknownStages());
            assertEquals(0, summary.chainUsage().byKind().get(UsageKind.OUTPUT_TOKENS).reported());
        }
    }

    @Test
    void knownZeroCountersAreAvailableWithAZeroAmount() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(0), out(0));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            CostToAcceptedOutcome cost = cost(run, inputOutput());

            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, cost.status());
            assertEquals(new BigDecimal("0.000000"), cost.amounts().get(0).amount());
            assertFalse(cost.isDeclaredZero());
        }
    }

    @Test
    void measuredStageWithoutCountersOrPriceIsPartialAndCarriesNoAmount() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10);
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            CostToAcceptedOutcome cost = cost(run, PriceBook.empty());

            assertEquals(CostToAcceptedOutcome.Status.PARTIAL, cost.status());
            assertTrue(cost.isLowerBound());
            assertTrue(cost.amounts().isEmpty(), "no priced line means no currency to attach a bound to");
            assertEquals(List.of(Code.MISSING_PRICE), cost.reasons().stream().map(CostReason::code).toList());
        }
    }

    @Test
    void declaredNoBillableUsageIsAvailableAndVisiblyDeclared() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.noBillable(A1, "lint", 0, 10);
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            CostToAcceptedOutcome cost = cost(run, PriceBook.empty());

            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, cost.status());
            assertTrue(cost.isDeclaredZero());
            assertEquals(Code.DECLARED_NO_BILLABLE_USAGE, cost.reasons().get(0).code());
            assertEquals("deterministic local check", cost.reasons().get(0).detail());
        }
    }

    @Test
    void declaredZeroStageNextToPricedStageStaysVisible() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.noBillable(A1, "lint", 0, 10);
            run.stage(A1, "implement", 10, 20, in(1_000), out(100));
            run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
            run.finish(A1, AttemptResult.COMPLETED);
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

            CostToAcceptedOutcome cost = cost(run, inputOutput());

            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, cost.status());
            assertEquals(new BigDecimal("0.003000"), cost.amounts().get(0).amount());
            assertEquals(List.of(Code.DECLARED_NO_BILLABLE_USAGE),
                    cost.reasons().stream().map(CostReason::code).toList());
            assertFalse(cost.isDeclaredZero());
        }
    }
}
