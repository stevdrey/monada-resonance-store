package com.monada.evaluation.execution;

import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.UsageProvenance;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Currency;
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

/**
 * Hand-calculated fixture: failed implementation -> repair -> review -> QA -> accepted, then one stage after
 * acceptance. Prices (USD per 1M tokens): input 2, cached input 0.5 (included in input), output 10.
 *
 * <pre>
 * implement  in 10,000 (4,000 cached) out 2,000   6,000*2/1M + 4,000*.5/1M + 2,000*10/1M = 0.012+0.002+0.020 = 0.0340
 * repair     in 20,000 (0 cached)     out 5,000e  20,000*2/1M + 0 + 5,000*10/1M          = 0.040      +0.050 = 0.0900
 * review     in  3,000 (1,000 cached) out   500   2,000*2/1M + 1,000*.5/1M + 500*10/1M  = 0.004+0.0005+0.005 = 0.0095
 * qa         in  1,000 (0 cached)     out   100   1,000*2/1M + 0 + 100*10/1M            = 0.002      +0.001 = 0.0030
 * chain total = 0.1365 (reported 0.0865 + estimated 0.0500), reported as 0.136500
 * followup (after acceptance) in 1,000 out 1,000  = 0.002 + 0.010 = 0.0120, excluded from the chain
 * </pre>
 */
class AttemptChainSummaryTest {
    @TempDir
    Path root;

    private Run chain() {
        Run run = new Run(root, Set.of(QualityDimension.TESTS));
        run.initial(A1);
        run.stage(A1, "implement", 0, 60, in(10_000), cached(4_000), out(2_000));
        run.evidence(A1, QualityDimension.TESTS, ObservationState.FAIL);
        run.finish(A1, AttemptResult.FAILED);
        run.follow(A2, 2, AttemptReason.REPAIR, A1);
        run.stage(A2, "repair", 60, 120, in(20_000), cached(0), outEstimated(5_000));
        run.stage(A2, "review", 120, 150, in(3_000), cached(1_000), out(500));
        run.stage(A2, "qa", 140, 170, in(1_000), cached(0), out(100));
        run.evidence(A2,
                observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()),
                observation(QualityDimension.MAINTAINABILITY, ObservationState.PASS, OptionalDouble.of(82.5),
                        Optional.of("index")),
                observation(QualityDimension.COMPLEXITY, ObservationState.PASS, OptionalDouble.of(7),
                        Optional.of("cyclomatic")));
        run.finish(A2, AttemptResult.COMPLETED);
        return run;
    }

    @Test
    void totalsEveryStageOfTheChainExactlyOnce() {
        try (Run run = chain()) {
            long outcomeSeq = run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            run.follow(A3, 3, AttemptReason.REVIEW_FOLLOW_UP, A2);
            run.stage(A3, "followup", 200, 210, in(1_000), out(1_000));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(Optional.of(outcomeSeq), summary.acceptanceCutoff());
            assertEquals(List.of("implement", "repair", "review", "qa"),
                    summary.chainStages().stream().map(StageUsageCost::stage).toList());
            assertTrue(summary.chainStages().stream().allMatch(StageUsageCost::covered));
            assertDecimal("0.034", summary.chainStages().get(0).lines().stream().map(CostLine::cost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

            CostToAcceptedOutcome cost = summary.cost();
            assertEquals(CostToAcceptedOutcome.Status.AVAILABLE, cost.status());
            assertEquals(CostBasis.HYPOTHETICAL_API_EQUIVALENT, cost.basis());
            assertEquals(1, cost.amounts().size());
            CurrencyCost usd = cost.amounts().get(0);
            assertEquals(Currency.getInstance("USD"), usd.currency());
            assertDecimal("0.1365", usd.unrounded());
            assertDecimal("0.0865", usd.reportedBasis());
            assertDecimal("0.05", usd.estimatedBasis());
            assertEquals(new BigDecimal("0.136500"), usd.amount());
            assertTrue(cost.reasons().isEmpty());
        }
    }

    @Test
    void keepsReportedEstimatedAndUnknownCategoriesApart() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            UsageTotals.KindUsage input = summary.chainUsage().byKind().get(com.monada.core.execution.UsageKind.INPUT_TOKENS);
            UsageTotals.KindUsage cachedUsage = summary.chainUsage().byKind().get(com.monada.core.execution.UsageKind.CACHED_INPUT_TOKENS);
            UsageTotals.KindUsage output = summary.chainUsage().byKind().get(com.monada.core.execution.UsageKind.OUTPUT_TOKENS);
            assertEquals(4, summary.chainUsage().stageCount());
            assertEquals(34_000, input.reported());
            assertEquals(0, input.estimated());
            assertEquals(5_000, cachedUsage.reported());
            assertEquals(2_600, output.reported());
            assertEquals(5_000, output.estimated());
            assertEquals(0, output.unknownStages());
            assertEquals(0, output.notMeasuredStages());

            CostLine repairOutput = summary.chainStages().get(1).lines().stream()
                    .filter(l -> l.kind() == com.monada.core.execution.UsageKind.OUTPUT_TOKENS).findFirst().orElseThrow();
            assertEquals(UsageProvenance.ESTIMATED, repairOutput.provenance());
        }
    }

    @Test
    void separatesPostAcceptanceActivityFromTheChain() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            run.follow(A3, 3, AttemptReason.REVIEW_FOLLOW_UP, A2);
            run.stage(A3, "followup", 200, 210, in(1_000), out(1_000));

            ExecutionSummary summary = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of());

            assertEquals(List.of("followup"), summary.postAcceptanceStages().stream().map(StageUsageCost::stage).toList());
            assertTrue(summary.postAcceptanceStages().get(0).postAcceptance());
            assertEquals(1, summary.postAcceptanceUsage().stageCount());
            assertEquals(1_000, summary.postAcceptanceUsage().byKind()
                    .get(com.monada.core.execution.UsageKind.INPUT_TOKENS).reported());
            assertDecimal("0.1365", summary.cost().amounts().get(0).unrounded());
        }
    }

    @Test
    void distinguishesEffortFromElapsedAndActiveTime() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            TimeSummary time = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).chainTime();

            // 60 + 60 + 30 + 30 summed; review (120-150) and qa (140-170) overlap by 10 seconds
            assertEquals(Optional.of(Duration.ofSeconds(180)), time.effort());
            assertEquals(Optional.of(Duration.ofSeconds(170)), time.wallClock());
            assertEquals(Optional.of(Duration.ofSeconds(170)), time.activeTime());
            assertEquals(Optional.of(Duration.ofSeconds(10)), time.parallelOverlap());
            assertEquals(Optional.of(at(0)), time.firstStart());
            assertEquals(Optional.of(at(170)), time.lastEnd());
        }
    }

    @Test
    void rendersEachGateIndividuallyWithProvenanceAndNoScore() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            List<GateEvidence> gates = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of()).gates();

            assertEquals(List.of(QualityDimension.values()), gates.stream().map(GateEvidence::dimension).toList());
            GateEvidence tests = gates.get(QualityDimension.TESTS.ordinal());
            assertTrue(tests.mandatory());
            assertEquals(ObservationState.PASS, tests.effective().orElseThrow().observation().state());
            GateEvidence maintainability = gates.get(QualityDimension.MAINTAINABILITY.ordinal());
            assertFalse(maintainability.mandatory());
            assertEquals(OptionalDouble.of(82.5), maintainability.effective().orElseThrow().observation().measuredValue());
            assertEquals("index", maintainability.effective().orElseThrow().observation().unit().orElseThrow());
            assertEquals("junit", maintainability.effective().orElseThrow().observation().evaluatorId());
            GateEvidence complexity = gates.get(QualityDimension.COMPLEXITY.ordinal());
            assertEquals(OptionalDouble.of(7), complexity.effective().orElseThrow().observation().measuredValue());
            GateEvidence security = gates.get(QualityDimension.SECURITY.ordinal());
            assertTrue(security.effective().isEmpty());
            assertTrue(security.absentReason().orElseThrow().contains("no applicable evidence"));
        }
    }

    @Test
    void reportsActualBilledAmountsSeparatelyAndIsDeterministic() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            ActualBilledAmount billed = new ActualBilledAmount(Currency.getInstance("USD"), new BigDecimal("0.20"),
                    "invoice-17");

            ExecutionSummary first = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of(billed));
            ExecutionSummary second = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of(billed));

            assertEquals(first, second);
            assertEquals(List.of(billed), first.actualBilled());
            assertDecimal("0.1365", first.cost().amounts().get(0).unrounded());
        }
    }

    @Test
    void priceUsedByEachStageIsVisibleWithItsVersionAndAssumptions() {
        try (Run run = chain()) {
            run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A2));
            StageUsageCost implement = ExecutionSummaryCalculator.summarize(run.view(), book(usd()), List.of())
                    .chainStages().get(0);
            assertEquals("1", implement.priceSnapshot().orElseThrow().version());
            assertTrue(implement.priceSnapshot().orElseThrow().assumptions().contains("no live lookup"));
            assertEquals("implement", implement.stage());
        }
    }
}
