package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.BillingMode;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.PriceLine;
import com.monada.core.execution.PriceSnapshot;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageKind;
import com.monada.evaluation.execution.CostReason.Code;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decimal pricing, overlap removal, price selection by version/date, currencies and rounding. */
class UsageCostPricingTest {
    private static final LocalDate DAY = LocalDate.parse("2026-01-01");

    @TempDir
    Path root;

    private Run accepted(Run run) {
        run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        run.finish(A1, AttemptResult.COMPLETED);
        run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return run;
    }

    private ExecutionSummary singleStage(PriceBook book, UsageCounter... usage) {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, usage);
            return ExecutionSummaryCalculator.summarize(accepted(run).view(), book, List.of());
        }
    }

    private static PriceSnapshot inputOnly(String version, LocalDate effective, String price) {
        return snapshot("prices", version, "prov", "model-x", "USD", effective,
                PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal(price), 1_000_000));
    }

    @Test
    void thousandInputTokensAtTwoPerMillionCostTwoThousandthsNotTwoThousand() {
        ExecutionSummary summary = singleStage(book(inputOnly("1", DAY.minusDays(1), "2")), in(1_000));
        assertEquals(new BigDecimal("0.002000"), summary.cost().amounts().get(0).amount());
    }

    @Test
    void cachedTokensAreSubtractedFromInputBeforePricing() {
        ExecutionSummary summary = singleStage(book(usd()), in(10_000), cached(4_000), out(0));
        CostToAcceptedOutcome cost = summary.cost();
        assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, cost.status());
        assertDecimal("0.014", cost.amounts().get(0).unrounded());
        StageUsageCost stage = summary.chainStages().get(0);
        assertEquals(6_000, stage.lines().stream().filter(l -> l.kind() == UsageKind.INPUT_TOKENS)
                .findFirst().orElseThrow().billableQuantity());
        assertEquals(4_000, stage.lines().stream().filter(l -> l.kind() == UsageKind.CACHED_INPUT_TOKENS)
                .findFirst().orElseThrow().billableQuantity());
    }

    @Test
    void cachedAboveInputIsInconsistentNeverClampedToZero() {
        ExecutionSummary summary = singleStage(book(usd()), in(1_000), cached(4_000), out(0));
        CostToAcceptedOutcome cost = summary.cost();
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, cost.status());
        assertEquals(List.of(Code.INCONSISTENT_USAGE), cost.reasons().stream().map(CostReason::code).toList());
        assertEquals(UsageKind.INPUT_TOKENS, cost.reasons().get(0).kind().orElseThrow());
        assertDecimal("0.002", cost.amounts().get(0).unrounded()); // only the consistent cached line
    }

    @Test
    void missingCachedCounterBlocksTheInputLineInsteadOfAssumingZeroOverlap() {
        ExecutionSummary summary = singleStage(book(usd()), in(10_000), out(0));
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, summary.cost().status());
        assertEquals(List.of(Code.MISSING_USAGE), summary.cost().reasons().stream().map(CostReason::code).toList());
        assertEquals(UsageKind.CACHED_INPUT_TOKENS, summary.cost().reasons().get(0).kind().orElseThrow());
        assertEquals(new BigDecimal("0.000000"), summary.cost().amounts().get(0).amount());
    }

    @Test
    void estimatedUsageIsSeparatedFromReportedUsageInTheCostBasis() {
        ExecutionSummary summary = singleStage(book(usd()), in(1_000), cached(0), outEstimated(100));
        CurrencyCost usd = summary.cost().amounts().get(0);
        assertDecimal("0.002", usd.reportedBasis());
        assertDecimal("0.001", usd.estimatedBasis());
        assertDecimal("0.003", usd.unrounded());
    }

    @Test
    void selectsTheSnapshotVersionEffectiveOnTheStageDate() {
        PriceBook book = book(inputOnly("1", DAY.minusDays(10), "2"), inputOnly("2", DAY, "4"),
                inputOnly("3", DAY.plusDays(30), "8"));
        ExecutionSummary summary = singleStage(book, in(1_000));
        assertEquals("2", summary.chainStages().get(0).priceSnapshot().orElseThrow().version());
        assertEquals(new BigDecimal("0.004000"), summary.cost().amounts().get(0).amount());
    }

    @Test
    void snapshotNotYetEffectiveMeansMissingPrice() {
        ExecutionSummary summary = singleStage(book(inputOnly("1", DAY.plusDays(1), "2")), in(1_000));
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, summary.cost().status());
        assertEquals(Code.MISSING_PRICE, summary.cost().reasons().get(0).code());
        assertTrue(summary.cost().amounts().isEmpty());
    }

    @Test
    void twoDifferentSnapshotsOnTheSameDateAreAmbiguousNotPickedSilently() {
        PriceBook book = book(inputOnly("1", DAY, "2"), inputOnly("2", DAY, "4"));
        ExecutionSummary summary = singleStage(book, in(1_000));
        assertEquals(Code.AMBIGUOUS_PRICE, summary.cost().reasons().get(0).code());
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, summary.cost().status());
    }

    @Test
    void stageWithoutProviderOrModelHasNoPrice() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", RouteDescriptor.workerOnly("worker"), 0, 10, in(1_000));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(accepted(run).view(),
                    book(inputOnly("1", DAY.minusDays(1), "2")), List.of());
            assertEquals(Code.MISSING_PRICE, summary.cost().reasons().get(0).code());
        }
    }

    @Test
    void currenciesAreReportedSeparatelyAndNeverSummed() {
        RouteDescriptor other = new RouteDescriptor("worker", Optional.of("prov"), Optional.of("model-y"),
                Optional.empty(), BillingMode.SUBSCRIPTION);
        PriceBook book = book(inputOnly("1", DAY.minusDays(1), "2"),
                snapshot("eur", "1", "prov", "model-y", "EUR", DAY.minusDays(1),
                        PriceLine.of(UsageKind.INPUT_TOKENS, new BigDecimal("3"), 1_000_000)));
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "implement", 0, 10, in(1_000));
            run.stage(A1, "review", other, 10, 20, in(1_000));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(accepted(run).view(), book, List.of());

            List<CurrencyCost> amounts = summary.cost().amounts();
            assertEquals(List.of("EUR", "USD"), amounts.stream().map(a -> a.currency().getCurrencyCode()).toList());
            assertEquals(new BigDecimal("0.003000"), amounts.get(0).amount());
            assertEquals(new BigDecimal("0.002000"), amounts.get(1).amount());
            assertEquals(BillingMode.SUBSCRIPTION, summary.chainStages().get(1).billingMode());
            assertEquals(CostBasis.HYPOTHETICAL_API_EQUIVALENT, summary.cost().basis());
        }
    }

    @Test
    void roundsOnceAtTheEndWithHalfEven() {
        PriceBook book = book(inputOnly("1", DAY.minusDays(1), "0.5")); // 0.5 per 1M => 3 tokens = 0.0000015
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "one", 0, 10, in(3));
            run.stage(A1, "two", 10, 20, in(3));
            CurrencyCost usd = ExecutionSummaryCalculator.summarize(accepted(run).view(), book, List.of())
                    .cost().amounts().get(0);
            assertDecimal("0.000003", usd.unrounded());
            assertEquals(new BigDecimal("0.000003"), usd.amount()); // rounding each line first would give 0.000004
        }
    }

    @Test
    void halfEvenResolvesExactTiesToTheEvenDigit() {
        PriceBook book = book(inputOnly("1", DAY.minusDays(1), "0.5"));
        assertEquals(new BigDecimal("0.000000"), singleStage(book, in(1)).cost().amounts().get(0).amount()); // 0.0000005
    }

    @Test
    void divisionUsesScale18ForUnitSizesThatAreNotPowersOfTen() {
        PriceSnapshot third = snapshot("odd", "1", "prov", "model-x", "USD", DAY.minusDays(1),
                PriceLine.of(UsageKind.INPUT_TOKENS, BigDecimal.ONE, 3));
        CurrencyCost usd = singleStage(book(third), in(1)).cost().amounts().get(0);
        assertEquals(new BigDecimal("0.333333333333333333"), usd.unrounded());
        assertEquals(new BigDecimal("0.333333"), usd.amount());
    }

    @Test
    void usageOverflowIsRejectedAsInvalidInput() {
        try (Run run = new Run(root, Set.of(QualityDimension.TESTS))) {
            run.initial(A1);
            run.stage(A1, "one", 0, 10, in(Long.MAX_VALUE));
            run.stage(A1, "two", 10, 20, in(1));
            Run accepted = accepted(run);
            assertThrows(IllegalArgumentException.class, () -> ExecutionSummaryCalculator.summarize(
                    accepted.view(), book(inputOnly("1", DAY.minusDays(1), "2")), List.of()));
        }
    }

    @Test
    void priceBookRejectsDuplicateIdAndVersionAndNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceBook.of(List.of(inputOnly("1", DAY, "2"), inputOnly("1", DAY.plusDays(1), "3"))));
        assertThrows(NullPointerException.class, () -> PriceBook.of(null));
        assertEquals(PriceBook.MatchStatus.NONE, PriceBook.empty()
                .lookup(Optional.of("prov"), Optional.of("model-x"), at(0)).status());
    }

    @Test
    void actualBilledAmountRejectsNegativeAndBlankSource() {
        var usd = java.util.Currency.getInstance("USD");
        assertThrows(IllegalArgumentException.class, () -> new ActualBilledAmount(usd, new BigDecimal("-1"), "x"));
        assertThrows(IllegalArgumentException.class, () -> new ActualBilledAmount(usd, BigDecimal.ONE, " "));
    }
}
