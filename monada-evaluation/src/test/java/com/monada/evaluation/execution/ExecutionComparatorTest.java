package com.monada.evaluation.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EvaluationPolicy;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.SourceProvenance;
import com.monada.core.execution.TaskId;
import com.monada.evaluation.execution.Comparability.Field;
import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import com.monada.evaluation.execution.ComparisonReport.ParetoGroup;
import com.monada.evaluation.execution.Eligibility.Code;
import com.monada.evaluation.execution.ObjectiveResult.Verdict;
import com.monada.evaluation.execution.PairwiseComparison.Basis;
import com.monada.evaluation.execution.PairwiseComparison.Conclusion;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static com.monada.evaluation.execution.ComparisonFixtures.*;
import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gated winner selection, comparability, INCONCLUSIVE dimensions, ties and Pareto, with hand-computed numbers. */
class ExecutionComparatorTest {
    @TempDir
    Path root;

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    private static Objective cost(Currency currency, String tolerance) {
        return new Objective("cost-" + currency, new Metric.Cost(currency), Direction.MINIMIZE,
                new BigDecimal(tolerance));
    }

    private static Objective effort() {
        return new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO);
    }

    private static ComparisonPolicy policy(Objective... objectives) {
        return new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(objectives));
    }

    private static ExecutionRef ref(String exec) {
        return new ExecutionRef(SCOPE, ExecutionId.of(exec));
    }

    private static ObjectiveResult objective(PairwiseComparison p, String id) {
        return p.objectives().stream().filter(o -> o.objective().id().equals(id)).findFirst().orElseThrow();
    }

    private static PairwiseComparison pair(ComparisonReport report, String left, String right) {
        return report.pairs().stream()
                .filter(p -> p.left().ref().equals(ref(left)) && p.right().ref().equals(ref(right)))
                .findFirst().orElseThrow();
    }

    // --- cheap invalid solution, accepted repaired solution -------------------------------------------

    @Test
    void cheaperAttemptFailingARequiredGateIsNeitherWinnerNorOnTheFront() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var cheap = candidate(f.cheapWithTests("exec-a-cheap", ObservationState.FAIL));
        var direct = candidate(f.valid("exec-b-direct", 10_000, 1_000, 10));
        var repaired = candidate(f.repaired("exec-c-repaired"));
        ComparisonPolicy policy = policy(cost(USD, "0"), effort());

        ComparisonReport report = ExecutionComparator.report(List.of(repaired, cheap, direct), policy);

        // The cheap candidate recorded an ACCEPTED/VALIDATED outcome, yet a failed mandatory gate excludes it.
        Participant cheapParticipant = report.participants().get(0);
        assertFalse(cheapParticipant.eligible());
        assertEquals(com.monada.api.execution.DerivedAcceptance.Status.ACCEPTED_UNVALIDATED,
                cheapParticipant.acceptance().status());
        assertEquals(List.of(Code.NOT_VALIDATED_ACCEPTED, Code.GATE_FAIL),
                cheapParticipant.eligibility().exclusions().stream().map(Eligibility.Exclusion::code).toList());
        // Without a validated acceptance there is no cost-to-accepted-outcome at all, not even a cheap one.
        assertEquals(CostToAcceptedOutcome.Status.UNAVAILABLE, cheapParticipant.resources().costStatus());

        PairwiseComparison cheapVsDirect = pair(report, "exec-a-cheap", "exec-b-direct");
        assertEquals(Conclusion.ONLY_RIGHT_ELIGIBLE, cheapVsDirect.conclusion());
        assertEquals(new PairwiseComparison.Winner(ref("exec-b-direct"), Basis.ONLY_ELIGIBLE),
                cheapVsDirect.winner().orElseThrow());
        assertTrue(cheapVsDirect.informationalOnly());
        // Its cost is not a cost to an accepted outcome, so it is not even shown as the cheaper one.
        ObjectiveResult cheapCost = objective(cheapVsDirect, "cost-USD");
        assertEquals(Verdict.INCONCLUSIVE, cheapCost.verdict());
        assertTrue(cheapCost.reasons().get(0).detail().contains("NO_VALIDATED_ACCEPTANCE"));

        PairwiseComparison directVsRepaired = pair(report, "exec-b-direct", "exec-c-repaired");
        assertEquals(Conclusion.LEFT_DOMINATES, directVsRepaired.conclusion());
        assertEquals(0, new BigDecimal("0.03").compareTo(objective(directVsRepaired, "cost-USD").leftValue().orElseThrow()));
        assertEquals(0, new BigDecimal("0.09").compareTo(objective(directVsRepaired, "cost-USD").rightValue().orElseThrow()));
        assertEquals(0, new BigDecimal("10").compareTo(objective(directVsRepaired, "effort").leftValue().orElseThrow()));
        assertEquals(0, new BigDecimal("40").compareTo(objective(directVsRepaired, "effort").rightValue().orElseThrow()));

        ParetoGroup group = report.groups().get(0);
        assertEquals(List.of(ref("exec-b-direct")), group.front());
        assertEquals(List.of(ref("exec-c-repaired")), group.dominated().stream().map(ComparisonReport.Dominated::execution).toList());
        assertEquals(List.of(ref("exec-b-direct")), group.dominated().get(0).dominatedBy());
        assertEquals(List.of(ref("exec-a-cheap")), group.excluded().stream().map(ComparisonReport.Excluded::execution).toList());
    }

    @Test
    void unknownAndMissingMandatoryEvidenceBlockEligibilityLikeFail() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var unknown = candidate(f.cheapWithTests("exec-a", ObservationState.UNKNOWN));
        Run noEvidence = f.run("exec-b");
        noEvidence.initial(A1);
        noEvidence.stage(A1, "work", 0, 5, usage(1_000, 100));
        noEvidence.finish(A1, AttemptResult.COMPLETED);
        noEvidence.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var missing = candidate(noEvidence);

        ComparisonReport report = ExecutionComparator.report(List.of(unknown, missing), policy(cost(USD, "0")));

        assertEquals(Conclusion.NO_ELIGIBLE_EXECUTION, report.pairs().get(0).conclusion());
        assertTrue(report.pairs().get(0).winner().isEmpty());
        assertTrue(report.participants().get(0).eligibility().exclusions().stream()
                .anyMatch(e -> e.code() == Code.GATE_UNKNOWN));
        assertTrue(report.participants().get(1).eligibility().exclusions().stream()
                .anyMatch(e -> e.code() == Code.GATE_MISSING));
        assertTrue(report.groups().get(0).front().isEmpty());
    }

    @Test
    void acceptedFlagAloneNeverOverridesEvidence() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run imported = f.run("exec-a");
        imported.initial(A1);
        imported.stage(A1, "work", 0, 5, usage(1_000, 100));
        imported.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        imported.finish(A1, AttemptResult.COMPLETED);
        imported.outcome(Outcome.accepted(OutcomeOrigin.IMPORTED_CLAIM, A1));
        var claim = candidate(imported);
        var stricter = candidate(f.valid("exec-b", 10_000, 1_000, 10));

        // Execution policy only requires TESTS; the caller also requires SECURITY, which nobody recorded.
        ComparisonPolicy strict = new ComparisonPolicy(Set.of(QualityDimension.TESTS, QualityDimension.SECURITY), List.of());
        ComparisonReport report = ExecutionComparator.report(List.of(claim, stricter), strict);

        assertEquals(List.of(Code.NOT_VALIDATED_ACCEPTED, Code.GATE_MISSING),
                report.participants().get(0).eligibility().exclusions().stream().map(Eligibility.Exclusion::code).toList());
        assertEquals(List.of(Code.GATE_MISSING),
                report.participants().get(1).eligibility().exclusions().stream().map(Eligibility.Exclusion::code).toList());
        assertTrue(report.participants().stream().noneMatch(Participant::eligible));
    }

    @Test
    void justifiedNotApplicableCountsAsAGatePass() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run r = f.run("exec-a");
        r.initial(A1);
        r.stage(A1, "work", 0, 5, usage(1_000, 100));
        r.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        r.evidence(A1, QualityDimension.SECURITY, ObservationState.NOT_APPLICABLE); // helper adds a justification
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));

        ComparisonPolicy gates = new ComparisonPolicy(Set.of(QualityDimension.TESTS, QualityDimension.SECURITY), List.of());
        assertTrue(ExecutionComparator.report(List.of(candidate(r)), gates).participants().get(0).eligible());
    }

    // --- comparability ------------------------------------------------------------------------------

    record Variant(String name, Field field, ScopeId scope, TaskId task, SourceProvenance provenance,
                   EvaluationPolicy policy) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Variant> mismatches() {
        return Stream.of(
                new Variant("scope", Field.SCOPE, ScopeId.of("scope-2"), TASK, PROV, POLICY),
                new Variant("task", Field.TASK, SCOPE, TaskId.of("task-2"), PROV, POLICY),
                new Variant("source revision", Field.SOURCE_REVISION, SCOPE, TASK,
                        new SourceProvenance("rev-2", "ctx-1", "constraints-1"), POLICY),
                new Variant("context", Field.CONTEXT_FINGERPRINT, SCOPE, TASK,
                        new SourceProvenance("rev-1", "ctx-2", "constraints-1"), POLICY),
                new Variant("constraints", Field.CONSTRAINTS_FINGERPRINT, SCOPE, TASK,
                        new SourceProvenance("rev-1", "ctx-1", "constraints-2"), POLICY),
                new Variant("policy id", Field.POLICY_ID, SCOPE, TASK, PROV,
                        new EvaluationPolicy("other", "1", Set.of(QualityDimension.TESTS))),
                new Variant("policy version", Field.POLICY_VERSION, SCOPE, TASK, PROV,
                        new EvaluationPolicy("policy", "2", Set.of(QualityDimension.TESTS))));
    }

    @ParameterizedTest
    @MethodSource("mismatches")
    void eachStartingConditionMismatchIsNotComparableWithExactlyThatReason(Variant variant) {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var base = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        Run other = f.run(variant.scope(), variant.task(), "exec-b", variant.provenance(), variant.policy());
        other.initial(A1);
        other.stage(A1, "work", 0, 10, usage(10_000, 1_000));
        other.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        other.finish(A1, AttemptResult.COMPLETED);
        other.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var changed = candidate(other, variant.scope(), book(usd()));

        PairwiseComparison p = ExecutionComparator.compare(base, changed, policy(cost(USD, "0")));

        assertEquals(Conclusion.NOT_COMPARABLE, p.conclusion());
        assertEquals(List.of(variant.field()), p.comparability().mismatches().stream().map(Comparability.Mismatch::field).toList());
        assertTrue(p.winner().isEmpty());
        assertTrue(p.objectives().isEmpty(), "nothing is compared across different starting conditions");
        assertTrue(p.informationalOnly());
    }

    @Test
    void severalMismatchesAreEachReportedWithBothValuesAndNeverShareAFront() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        Run other = f.run(SCOPE, TaskId.of("task-2"), "exec-b", new SourceProvenance("rev-9", "ctx-1", "constraints-1"), POLICY);
        other.initial(A1);
        other.stage(A1, "work", 0, 10, usage(1_000, 100));
        other.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        other.finish(A1, AttemptResult.COMPLETED);
        other.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var b = candidate(other);

        ComparisonReport report = ExecutionComparator.report(List.of(a, b), policy(cost(USD, "0")));

        PairwiseComparison p = report.pairs().get(0);
        assertEquals(List.of(new Comparability.Mismatch(Field.TASK, "task-1", "task-2"),
                new Comparability.Mismatch(Field.SOURCE_REVISION, "rev-1", "rev-9")), p.comparability().mismatches());
        assertEquals(2, report.groups().size());
        report.groups().forEach(g -> {
            assertEquals(1, g.members().size());
            assertEquals(g.members(), g.front()); // each is alone on its own front, never ranked against the other
        });
    }

    // --- inconclusive dimensions ------------------------------------------------------------------

    @Test
    void incompleteUsageMakesTheCostDimensionInconclusiveButOthersStayConclusive() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var complete = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        Run partial = f.run("exec-b");
        partial.initial(A1);
        partial.stage(A1, "work", 0, 40, in(1_000), cached(0)); // output priced but never measured
        partial.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        partial.finish(A1, AttemptResult.COMPLETED);
        partial.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var incomplete = candidate(partial);
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, incomplete.summary().cost().status());

        ComparisonReport report = ExecutionComparator.report(List.of(complete, incomplete), policy(cost(USD, "0"), effort()));

        PairwiseComparison p = report.pairs().get(0);
        ObjectiveResult cost = objective(p, "cost-USD");
        assertEquals(Verdict.INCONCLUSIVE, cost.verdict());
        assertEquals(ObjectiveResult.Code.INCOMPLETE_COST, cost.reasons().get(0).code());
        assertEquals(ObjectiveResult.Side.RIGHT, cost.reasons().get(0).side());
        assertTrue(cost.reasons().get(0).detail().contains("MISSING_USAGE(work, OUTPUT_TOKENS)"));
        assertEquals(Verdict.LEFT_BETTER, objective(p, "effort").verdict());
        assertEquals(Conclusion.INCONCLUSIVE, p.conclusion());
        assertTrue(p.winner().isEmpty());
        // The resource chain coverage of the incomplete side stays visible.
        assertEquals(CostToAcceptedOutcome.Status.PARTIAL, report.participants().get(1).resources().costStatus());
        assertEquals(1, report.participants().get(1).resources().chainStages());
        assertEquals(0, report.participants().get(1).resources().coveredStages());

        ParetoGroup group = report.groups().get(0);
        assertEquals(List.of(ref("exec-a")), group.front());
        assertEquals(List.of(ref("exec-b")), group.unassessed().stream().map(ComparisonReport.Unassessed::execution).toList());
    }

    @Test
    void inconclusiveObjectiveDoesNotEraseATradeoffAlreadyShown() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var fast = candidate(f.valid("exec-a", 20_000, 2_000, 10));
        Run slow = f.run("exec-b");
        slow.initial(A1);
        slow.stage(A1, "work", 0, 60, in(1_000), cached(0));
        slow.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        slow.finish(A1, AttemptResult.COMPLETED);
        slow.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var partial = candidate(slow);
        Objective wall = new Objective("wall", new Metric.WallClock(), Direction.MINIMIZE, BigDecimal.ZERO);
        Objective maxEffort = new Objective("effort-max", new Metric.Effort(), Direction.MAXIMIZE, BigDecimal.ZERO);

        // fast is better on wall-clock, slow on (maximized) effort, cost is inconclusive: a tradeoff stands.
        PairwiseComparison p = ExecutionComparator.compare(fast, partial, policy(cost(USD, "0"), wall, maxEffort));

        assertEquals(Conclusion.TRADEOFF, p.conclusion());
        assertTrue(p.winner().isEmpty());
    }

    @Test
    void mixedCurrenciesYieldInconclusiveAndNoTotalOrdering() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var inUsd = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        Run eur = f.run("exec-b");
        eur.initial(A1);
        eur.stage(A1, "work", route("prov-eur", "model-eur"), 0, 10, usage(10_000, 1_000));
        eur.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        eur.finish(A1, AttemptResult.COMPLETED);
        eur.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var inEur = candidate(eur, book(usd(), priced("prov-eur", "model-eur", "EUR", "fixed test prices, no live lookup")));
        assertEquals(EUR, inEur.summary().cost().amounts().get(0).currency());

        // USD objective: the EUR execution has no USD cost. Declaring both: neither is assessable on both.
        ComparisonReport usdOnly = ExecutionComparator.report(List.of(inUsd, inEur), policy(cost(USD, "0")));
        ObjectiveResult usdResult = objective(usdOnly.pairs().get(0), "cost-USD");
        assertEquals(Verdict.INCONCLUSIVE, usdResult.verdict());
        assertEquals(ObjectiveResult.Code.MIXED_CURRENCY, usdResult.reasons().get(0).code());

        ComparisonReport both = ExecutionComparator.report(List.of(inUsd, inEur), policy(cost(USD, "0"), cost(EUR, "0")));
        assertEquals(Conclusion.INCONCLUSIVE, both.pairs().get(0).conclusion());
        assertTrue(both.groups().get(0).front().isEmpty());
        assertEquals(2, both.groups().get(0).unassessed().size());
    }

    @Test
    void mixedPricingAssumptionsAreNotComparedAndNeitherSideIsOnTheFront() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var base = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        Run other = f.run("exec-b");
        other.initial(A1);
        other.stage(A1, "work", route("prov-b", "model-b"), 0, 10, usage(1_000, 100));
        other.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        other.finish(A1, AttemptResult.COMPLETED);
        other.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var cheapOther = candidate(other, book(usd(), priced("prov-b", "model-b", "USD", "negotiated discount prices")));

        ComparisonReport report = ExecutionComparator.report(List.of(base, cheapOther), policy(cost(USD, "0")));

        ObjectiveResult cost = objective(report.pairs().get(0), "cost-USD");
        assertEquals(Verdict.INCONCLUSIVE, cost.verdict());
        assertEquals(ObjectiveResult.Code.MIXED_PRICING_ASSUMPTIONS, cost.reasons().get(0).code());
        assertEquals(ObjectiveResult.Side.BOTH, cost.reasons().get(0).side());
        assertEquals(Conclusion.INCONCLUSIVE, report.pairs().get(0).conclusion());
        assertTrue(report.groups().get(0).front().isEmpty());
        assertEquals(2, report.groups().get(0).unassessed().size());
        assertEquals("negotiated discount prices", report.participants().get(1).resources().pricing().get(0).assumptions());
    }

    // --- hand-computable dominance, tradeoff and tie -------------------------------------------------

    @Test
    void tradeoffHasNoWinner() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var fast = candidate(f.valid("exec-a-fast", 20_000, 2_000, 10));   // 0.04 USD, 10 s
        var thrifty = candidate(f.valid("exec-b-thrifty", 5_000, 1_000, 60)); // 0.02 USD, 60 s

        PairwiseComparison p = ExecutionComparator.compare(fast, thrifty, policy(cost(USD, "0"), effort()));

        assertEquals(Verdict.RIGHT_BETTER, objective(p, "cost-USD").verdict());
        assertEquals(Verdict.LEFT_BETTER, objective(p, "effort").verdict());
        assertEquals(Conclusion.TRADEOFF, p.conclusion());
        assertTrue(p.winner().isEmpty());
        assertFalse(p.informationalOnly());
        assertTrue(p.interpretation().contains("no causal claim"));
    }

    @Test
    void paretoFrontOfThreeKeepsTheTradeoffAndDropsTheDominated() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(f.valid("exec-a", 10_000, 0, 30)); // 0.02 USD, 30 s
        var b = candidate(f.valid("exec-b", 20_000, 0, 10)); // 0.04 USD, 10 s
        var c = candidate(f.valid("exec-c", 25_000, 0, 40)); // 0.05 USD, 40 s

        ComparisonReport report = ExecutionComparator.report(List.of(c, a, b), policy(cost(USD, "0"), effort()));

        ParetoGroup group = report.groups().get(0);
        assertEquals(List.of(ref("exec-a"), ref("exec-b")), group.front());
        assertEquals(1, group.dominated().size());
        assertEquals(ref("exec-c"), group.dominated().get(0).execution());
        assertEquals(List.of(ref("exec-a"), ref("exec-b")), group.dominated().get(0).dominatedBy());
        assertEquals(Conclusion.TRADEOFF, pair(report, "exec-a", "exec-b").conclusion());
        assertEquals(Conclusion.LEFT_DOMINATES, pair(report, "exec-a", "exec-c").conclusion());
        assertEquals(Conclusion.LEFT_DOMINATES, pair(report, "exec-b", "exec-c").conclusion());
    }

    @Test
    void differencesWithinToleranceAreTiesAndEquivalentPairsHaveNoWinner() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(f.valid("exec-a", 10_000, 1_000, 10)); // 0.0300
        var b = candidate(f.valid("exec-b", 10_200, 1_000, 10)); // 0.030400

        PairwiseComparison tolerant = ExecutionComparator.compare(a, b, policy(cost(USD, "0.0004"), effort()));
        assertEquals(Verdict.TIE, objective(tolerant, "cost-USD").verdict()); // |diff| == tolerance is a tie
        assertEquals(Conclusion.EQUIVALENT, tolerant.conclusion());
        assertTrue(tolerant.winner().isEmpty());

        PairwiseComparison strict = ExecutionComparator.compare(a, b, policy(cost(USD, "0.0003"), effort()));
        assertEquals(Verdict.LEFT_BETTER, objective(strict, "cost-USD").verdict());
        assertEquals(Conclusion.LEFT_DOMINATES, strict.conclusion());
        assertEquals(new PairwiseComparison.Winner(ref("exec-a"), Basis.DOMINANCE), strict.winner().orElseThrow());
    }

    @Test
    void declaredZeroCostIsExactlyZeroAndBeatsAPricedChain() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run local = f.run("exec-a");
        local.initial(A1);
        local.noBillable(A1, "local check", 0, 10);
        local.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        local.finish(A1, AttemptResult.COMPLETED);
        local.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var declaredZero = candidate(local);
        assertTrue(declaredZero.summary().cost().isDeclaredZero());
        var priced = candidate(f.valid("exec-b", 10_000, 1_000, 10));

        PairwiseComparison p = ExecutionComparator.compare(declaredZero, priced, policy(cost(USD, "0")));

        assertEquals(0, BigDecimal.ZERO.compareTo(objective(p, "cost-USD").leftValue().orElseThrow()));
        assertEquals(Verdict.LEFT_BETTER, objective(p, "cost-USD").verdict());
    }

    // --- measured observations and evaluator judgments --------------------------------------------

    private Run withMeasures(ComparisonFixtures f, String exec, double complexity, String unit,
                             ObservationState maintainability) {
        Run r = f.run(exec);
        r.initial(A1);
        r.stage(A1, "work", 0, 10, usage(10_000, 1_000));
        r.evidence(A1,
                observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()),
                observation(QualityDimension.COMPLEXITY, ObservationState.PASS, OptionalDouble.of(complexity),
                        Optional.of(unit)),
                observation(QualityDimension.MAINTAINABILITY, maintainability, OptionalDouble.of(80), Optional.of("index")));
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return r;
    }

    @Test
    void measuredObservationsAreObjectivesNotGatesAndJudgmentsNeverChangeTheOutcome() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var simple = candidate(withMeasures(f, "exec-a", 7, "cyclomatic", ObservationState.PASS));
        var complex = candidate(withMeasures(f, "exec-b", 12, "cyclomatic", ObservationState.PASS));
        Objective complexity = new Objective("complexity", new Metric.Measured(QualityDimension.COMPLEXITY, "cyclomatic"),
                Direction.MINIMIZE, BigDecimal.ONE);
        PairwiseJudgment judgment = new PairwiseJudgment(ref("exec-a"), ref("exec-b"), QualityDimension.ARCHITECTURE,
                Optional.of(ref("exec-b")), "reviewer", "2", ObservationSource.MODEL_JUDGE,
                "rubric: layering, coupling", List.of(ArtifactRef.of("review", "r-1")));

        ComparisonReport report = ExecutionComparator.report(List.of(simple, complex), policy(cost(USD, "0"), complexity),
                List.of(judgment));

        PairwiseComparison p = report.pairs().get(0);
        assertEquals(Verdict.TIE, objective(p, "cost-USD").verdict());
        assertEquals(Verdict.LEFT_BETTER, objective(p, "complexity").verdict()); // 12 - 7 = 5 > tolerance 1
        assertEquals(Conclusion.LEFT_DOMINATES, p.conclusion());
        // The judge preferred the other execution; it is kept as given and does not touch the verdict.
        assertEquals(List.of(judgment), p.judgments());
        assertEquals("reviewer", p.judgments().get(0).judgeId());
        assertTrue(report.participants().stream().allMatch(Participant::eligible));
        assertEquals(3, report.participants().get(0).gates().stream().filter(g -> g.effective().isPresent()).count(),
                "evidence of every recorded dimension is kept, with its evaluator and source");
    }

    @Test
    void differentUnitsAreIncommensurableAndMeasuredValueDoesNotOverrideAFailedGate() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(withMeasures(f, "exec-a", 7, "cyclomatic", ObservationState.PASS));
        var b = candidate(withMeasures(f, "exec-b", 12, "cognitive", ObservationState.UNKNOWN));
        Objective complexity = new Objective("complexity", new Metric.Measured(QualityDimension.COMPLEXITY, "cyclomatic"),
                Direction.MINIMIZE, BigDecimal.ZERO);

        PairwiseComparison p = ExecutionComparator.compare(a, b, policy(complexity));
        assertEquals(Verdict.INCONCLUSIVE, objective(p, "complexity").verdict());
        assertEquals(ObjectiveResult.Code.INCOMMENSURABLE_AXIS, objective(p, "complexity").reasons().get(0).code());

        // MAINTAINABILITY is a caller-mandatory gate here: its measured 80 does not rescue an UNKNOWN state.
        ComparisonPolicy gated = new ComparisonPolicy(Set.of(QualityDimension.TESTS, QualityDimension.MAINTAINABILITY), List.of());
        PairwiseComparison g = ExecutionComparator.compare(a, b, gated);
        assertTrue(g.left().eligible());
        assertFalse(g.right().eligible());
        assertEquals(Conclusion.ONLY_LEFT_ELIGIBLE, g.conclusion());
    }

    @Test
    void policyWithoutObjectivesReportsEligibilityButClaimsNoOrdering() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(f.valid("exec-a", 10_000, 1_000, 10));
        var b = candidate(f.valid("exec-b", 5_000, 500, 10));

        ComparisonReport report = ExecutionComparator.report(List.of(a, b), policy());

        assertEquals(Conclusion.NO_OBJECTIVES, report.pairs().get(0).conclusion());
        assertTrue(report.pairs().get(0).winner().isEmpty());
        assertTrue(report.groups().get(0).front().isEmpty());
        assertEquals(ObjectiveResult.Code.NO_OBJECTIVES_DECLARED,
                report.groups().get(0).unassessed().get(0).reasons().get(0).code());
    }
}
