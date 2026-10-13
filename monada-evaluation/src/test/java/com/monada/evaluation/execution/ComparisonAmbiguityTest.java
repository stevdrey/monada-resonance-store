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
}
