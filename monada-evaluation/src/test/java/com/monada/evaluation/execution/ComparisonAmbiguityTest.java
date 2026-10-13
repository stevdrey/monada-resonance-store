package com.monada.evaluation.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.ComparisonFixtures.*;
import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identifiers that contain separator characters must never make two different things look the same. */
class ComparisonAmbiguityTest {
    @TempDir
    Path root;

    private static final Currency USD = Currency.getInstance("USD");

    private Run measured(ComparisonFixtures f, String exec, String evaluator, String version, double value,
                         long input, long seconds) {
        Run r = f.run(exec);
        r.initial(A1);
        r.stage(A1, "work", 0, seconds, usage(input, 0));
        r.evidence(A1,
                observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()),
                new QualityObservation(QualityDimension.COMPLEXITY, ObservationState.PASS, OptionalDouble.of(value),
                        Optional.of("cyclomatic"), evaluator, version, "policy", "1", ObservationSource.TOOL,
                        List.of(ArtifactRef.of("report", "r")), Optional.empty()));
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return r;
    }

    private static final Objective COMPLEXITY = new Objective("complexity",
            new Metric.Measured(QualityDimension.COMPLEXITY, "cyclomatic"), Direction.MINIMIZE, BigDecimal.ZERO);

    @Test
    void judgmentsPreferringReferencesThatRenderAlikeKeepAStableOrder() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        // scope "a/b" + execution "c" and scope "a" + execution "b/c" both print as "a/b/c".
        Run one = f.run(ScopeId.of("a/b"), TASK, "c", PROV, POLICY);
        Run two = f.run(ScopeId.of("a"), TASK, "b/c", PROV, POLICY);
        ExecutionRef refOne = new ExecutionRef(ScopeId.of("a/b"), one.view().id());
        ExecutionRef refTwo = new ExecutionRef(ScopeId.of("a"), two.view().id());
        assertEquals(refOne.toString(), refTwo.toString());
        var c1 = candidate(one);
        var c2 = candidate(two);
        ArtifactRef evidence = ArtifactRef.of("review", "1");
        List<PairwiseJudgment> judgments = List.of(
                new PairwiseJudgment(refOne, refTwo, QualityDimension.ARCHITECTURE, Optional.of(refOne), "j", "1",
                        ObservationSource.HUMAN, "r", List.of(evidence)),
                new PairwiseJudgment(refOne, refTwo, QualityDimension.ARCHITECTURE, Optional.of(refTwo), "j", "1",
                        ObservationSource.HUMAN, "r", List.of(evidence)));
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of());

        ComparisonReport expected = ExecutionComparator.report(List.of(c1, c2), policy, judgments);

        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            List<PairwiseJudgment> shuffled = new ArrayList<>(judgments);
            Collections.shuffle(shuffled, random);
            assertEquals(expected, ExecutionComparator.report(List.of(c1, c2), policy, shuffled));
        }
        assertEquals(2, expected.pairs().get(0).judgments().size());
    }

    @Test
    void evaluatorIdentityContainingSeparatorsIsStillIncommensurable() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        // "judge" + "v@2" and "judge@v" + "2" used to collapse to the same basis text.
        var a = candidate(measured(f, "exec-a", "judge", "v@2", 5, 10_000, 10));
        var b = candidate(measured(f, "exec-b", "judge@v", "2", 9, 10_000, 10));

        PairwiseComparison p = ExecutionComparator.compare(a, b,
                new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(COMPLEXITY)));

        ObjectiveResult result = p.objectives().get(0);
        assertEquals(ObjectiveResult.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(ObjectiveResult.Code.INCOMMENSURABLE_AXIS, result.reasons().get(0).code());
        assertTrue(p.winner().isEmpty());
    }

    @Test
    void tradeoffWithAnInconclusiveObjectiveKeepsBothOffTheFront() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var a = candidate(measured(f, "exec-a", "tool-a", "1", 5, 20_000, 10));  // dearer, faster
        var b = candidate(measured(f, "exec-b", "tool-b", "1", 9, 10_000, 60));  // cheaper, slower
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                new Objective("cost", new Metric.Cost(USD), Direction.MINIMIZE, BigDecimal.ZERO),
                new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO),
                COMPLEXITY));

        ComparisonReport report = ExecutionComparator.report(List.of(a, b), policy);

        assertEquals(PairwiseComparison.Conclusion.TRADEOFF, report.pairs().get(0).conclusion());
        ComparisonReport.ParetoGroup group = report.groups().get(0);
        assertTrue(group.front().isEmpty(), "neither is conclusive on complexity against the other");
        assertEquals(2, group.unassessed().size());
        assertEquals(ObjectiveResult.Code.INCOMMENSURABLE_AXIS, group.unassessed().get(0).reasons().get(0).code());
    }

    @Test
    void dominatedExecutionKeepsTheReasonsItIsIncomparableWithAnotherPeer() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var best = candidate(f.valid("exec-a", 10_000, 0, 10));   // 0.02 USD, 10 s
        var worse = candidate(f.valid("exec-c", 25_000, 0, 40));  // 0.05 USD, 40 s, same pricing assumptions
        Run other = f.run("exec-b");
        other.initial(A1);
        other.stage(A1, "work", route("prov-b", "model-b"), 0, 20, usage(1_000, 0));
        other.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        other.finish(A1, AttemptResult.COMPLETED);
        other.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var discount = candidate(other, book(usd(), priced("prov-b", "model-b", "USD", "negotiated discount prices")));
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                new Objective("cost", new Metric.Cost(USD), Direction.MINIMIZE, BigDecimal.ZERO),
                new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO)));

        ComparisonReport report = ExecutionComparator.report(List.of(best, worse, discount), policy);

        ComparisonReport.ParetoGroup group = report.groups().get(0);
        assertEquals(1, group.dominated().size());
        ComparisonReport.Dominated dominated = group.dominated().get(0);
        assertEquals("exec-c", dominated.execution().execution().value());
        assertEquals(List.of("exec-a"), dominated.dominatedBy().stream().map(r -> r.execution().value()).toList());
        assertTrue(dominated.unresolved().stream()
                .anyMatch(r -> r.code() == ObjectiveResult.Code.MIXED_PRICING_ASSUMPTIONS));
        assertTrue(group.front().isEmpty());
    }

    @Test
    void aScopeThatDoesNotMatchTheViewHistoryIsRejected() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run run = f.valid("exec-a", 1, 1, 1);
        var view = run.view();
        run.close();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ComparisonCandidate.of(ScopeId.of("other-scope"), view, book(usd()), List.of()));
    }

    @Test
    void viewsWhoseFieldsDisagreeWithTheirHistoryAreRejected() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run run = f.cheapWithTests("exec-a", ObservationState.FAIL); // recorded ACCEPTED but not validated
        var v = run.view();
        run.close();
        var forgedAcceptance = new com.monada.api.execution.DerivedAcceptance(
                com.monada.api.execution.DerivedAcceptance.Status.VALIDATED_ACCEPTED, List.of());
        var views = List.of(
                new com.monada.api.execution.ExecutionView(v.id(), com.monada.core.execution.TaskId.of("task-9"),
                        v.taskSummary(), v.provenance(), v.policy(), v.currentOutcome(), v.acceptance(), v.attempts(), v.history()),
                new com.monada.api.execution.ExecutionView(v.id(), v.task(), v.taskSummary(),
                        new com.monada.core.execution.SourceProvenance("rev-9", "ctx-1", "constraints-1"), v.policy(),
                        v.currentOutcome(), v.acceptance(), v.attempts(), v.history()),
                new com.monada.api.execution.ExecutionView(v.id(), v.task(), v.taskSummary(), v.provenance(),
                        new com.monada.core.execution.EvaluationPolicy("policy", "2", Set.of(QualityDimension.TESTS)),
                        v.currentOutcome(), v.acceptance(), v.attempts(), v.history()),
                new com.monada.api.execution.ExecutionView(v.id(), v.task(), v.taskSummary(), v.provenance(), v.policy(),
                        v.currentOutcome(), forgedAcceptance, v.attempts(), v.history()),
                new com.monada.api.execution.ExecutionView(v.id(), v.task(), v.taskSummary(), v.provenance(), v.policy(),
                        Optional.empty(), v.acceptance(), v.attempts(), v.history()));
        for (var forged : views) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> ComparisonCandidate.of(v.history().get(0).event().scopeId(), forged, book(usd()), List.of()));
        }
        // The untouched view is accepted and keeps its honest, non-eligible acceptance.
        assertTrue(!ComparisonCandidate.of(v.history().get(0).event().scopeId(), v, book(usd()), List.of())
                .summary().acceptance().isValidatedAcceptance());
    }

    @Test
    void honestViewsWithCorrectionsAndLaterEvidencePassVerification() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        Run run = f.run("exec-a");
        run.initial(A1);
        run.stage(A1, "work", 0, 10, usage(1_000, 100));
        var ev = run.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        var fin = run.finishEvent(A1, AttemptResult.FAILED);
        run.correctFinish(fin, 2, A1, AttemptResult.COMPLETED);
        run.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        var failed = run.correctEvidence(ev, 2, A1,
                observation(QualityDimension.TESTS, ObservationState.FAIL, OptionalDouble.empty(), Optional.empty()));
        run.correctEvidence(failed, 3, A1,
                observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()));

        var candidate = candidate(run);

        assertTrue(candidate.summary().acceptance().isValidatedAcceptance());
    }
}
