package com.monada.evaluation.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.QualityObservation;
import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.ComparisonFixtures.*;
import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measured values need a PASS observation; dominance that cycles within tolerance is not reported as a front. */
class ComparisonRefinementsTest {
    @TempDir
    Path root;

    private static QualityObservation measure(QualityDimension d, ObservationState state, double value) {
        return new QualityObservation(d, state, OptionalDouble.of(value), Optional.of("points"), "tool", "1",
                "policy", "1", ObservationSource.TOOL, List.of(ArtifactRef.of("report", "r-" + d)), Optional.empty());
    }

    private ComparisonCandidate measured(ComparisonFixtures f, String exec, ObservationState state, double o1,
                                         double o2, double o3) {
        Run r = f.run(exec);
        r.initial(A1);
        r.stage(A1, "work", 0, 10, usage(1_000, 100));
        r.evidence(A1,
                observation(QualityDimension.TESTS, ObservationState.PASS, OptionalDouble.empty(), Optional.empty()),
                measure(QualityDimension.COMPLEXITY, state, o1),
                measure(QualityDimension.MAINTAINABILITY, state, o2),
                measure(QualityDimension.SECURITY, state, o3));
        r.finish(A1, AttemptResult.COMPLETED);
        r.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        return candidate(r);
    }

    private static Objective minimize(String id, QualityDimension d, String tolerance) {
        return new Objective(id, new Metric.Measured(d, "points"), Direction.MINIMIZE, new BigDecimal(tolerance));
    }

    @Test
    void aMeasuredValueWithoutAPassObservationIsInconclusiveNotCompared() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var unknown = measured(f, "exec-a", ObservationState.UNKNOWN, 5, 0, 0);
        var failed = measured(f, "exec-b", ObservationState.FAIL, 50, 0, 0);
        var passed = measured(f, "exec-c", ObservationState.PASS, 50, 0, 0);
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS),
                List.of(minimize("complexity", QualityDimension.COMPLEXITY, "0")));

        PairwiseComparison unknownVsPassed = ExecutionComparator.compare(unknown, passed, policy);
        ObjectiveResult result = unknownVsPassed.objectives().get(0);
        assertEquals(ObjectiveResult.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(ObjectiveResult.Code.UNRELIABLE_MEASUREMENT, result.reasons().get(0).code());
        assertEquals(ObjectiveResult.Side.LEFT, result.reasons().get(0).side());
        assertTrue(unknownVsPassed.winner().isEmpty());

        ComparisonReport report = ExecutionComparator.report(List.of(unknown, failed, passed), policy);
        assertTrue(report.groups().get(0).front().isEmpty() || report.groups().get(0).front().size() <= 1);
        assertEquals(2, report.groups().get(0).unassessed().size()); // unknown and failed carry their own gap
    }

    @Test
    void dominanceThatCyclesWithinToleranceIsUnassessedNotAnEmptyFront() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        // Tolerance 1: A beats B on o1, B beats C on o2, C beats A on o3, every other difference is a tie.
        var a = measured(f, "exec-a", ObservationState.PASS, 0, 1, 2);
        var b = measured(f, "exec-b", ObservationState.PASS, 2, 0, 1);
        var c = measured(f, "exec-c", ObservationState.PASS, 1, 2, 0);
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                minimize("o1", QualityDimension.COMPLEXITY, "1"),
                minimize("o2", QualityDimension.MAINTAINABILITY, "1"),
                minimize("o3", QualityDimension.SECURITY, "1")));

        ComparisonReport report = ExecutionComparator.report(List.of(c, a, b), policy);

        assertEquals(PairwiseComparison.Conclusion.LEFT_DOMINATES, report.pairs().get(0).conclusion()); // a over b
        assertEquals(PairwiseComparison.Conclusion.LEFT_DOMINATES, report.pairs().get(2).conclusion()); // b over c
        assertEquals(PairwiseComparison.Conclusion.RIGHT_DOMINATES, report.pairs().get(1).conclusion()); // c over a
        ComparisonReport.ParetoGroup group = report.groups().get(0);
        assertTrue(group.front().isEmpty());
        assertTrue(group.dominated().isEmpty());
        assertEquals(3, group.unassessed().size());
        assertTrue(group.unassessed().stream()
                .allMatch(u -> u.reasons().get(0).code() == ObjectiveResult.Code.DOMINANCE_CYCLE));
    }

    @Test
    void dominatedMembersOutsideACycleStayDominated() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        var best = measured(f, "exec-a", ObservationState.PASS, 0, 0, 0);
        var worse = measured(f, "exec-b", ObservationState.PASS, 9, 9, 9);
        ComparisonPolicy policy = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
                minimize("o1", QualityDimension.COMPLEXITY, "1"),
                minimize("o2", QualityDimension.MAINTAINABILITY, "1")));

        ComparisonReport.ParetoGroup group = ExecutionComparator.report(List.of(best, worse), policy).groups().get(0);

        assertEquals(1, group.front().size());
        assertEquals(1, group.dominated().size());
        assertTrue(group.unassessed().isEmpty());
    }
}
