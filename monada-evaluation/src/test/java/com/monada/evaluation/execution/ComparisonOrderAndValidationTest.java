package com.monada.evaluation.execution;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ObservationSource;
import com.monada.core.execution.ObservationState;
import com.monada.core.execution.Outcome;
import com.monada.core.execution.OutcomeOrigin;
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
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
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.monada.evaluation.execution.ComparisonFixtures.*;
import static com.monada.evaluation.execution.Run.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The report does not depend on input order, and invalid input fails before any comparison. */
class ComparisonOrderAndValidationTest {
    @TempDir
    Path root;

    private static final ComparisonPolicy POLICY_2D = new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(
            new Objective("cost", new Metric.Cost(Currency.getInstance("USD")), Direction.MINIMIZE, BigDecimal.ZERO),
            new Objective("effort", new Metric.Effort(), Direction.MINIMIZE, BigDecimal.ZERO)));

    private List<ComparisonCandidate> candidates() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        List<ComparisonCandidate> list = new ArrayList<>();
        list.add(candidate(f.valid("exec-a", 10_000, 0, 30)));
        list.add(candidate(f.valid("exec-b", 20_000, 0, 10)));
        list.add(candidate(f.valid("exec-c", 25_000, 0, 40)));
        list.add(candidate(f.cheapWithTests("exec-d", ObservationState.FAIL)));
        // Same execution id in another scope and another task: ties on id are broken by scope.
        Run otherScope = f.run(ScopeId.of("scope-2"), TaskId.of("task-2"), "exec-a", PROV, POLICY);
        otherScope.initial(A1);
        otherScope.stage(A1, "work", 0, 10, usage(10_000, 0));
        otherScope.evidence(A1, QualityDimension.TESTS, ObservationState.PASS);
        otherScope.finish(A1, AttemptResult.COMPLETED);
        otherScope.outcome(Outcome.accepted(OutcomeOrigin.VALIDATED, A1));
        list.add(candidate(otherScope));
        return list;
    }

    @Test
    void reportIsInvariantToCandidateAndJudgmentOrder() {
        List<ComparisonCandidate> list = candidates();
        ExecutionRef a = new ExecutionRef(ScopeId.of("scope-1"), ExecutionId.of("exec-a"));
        ExecutionRef b = new ExecutionRef(ScopeId.of("scope-1"), ExecutionId.of("exec-b"));
        List<PairwiseJudgment> judgments = List.of(
                new PairwiseJudgment(a, b, QualityDimension.ARCHITECTURE, Optional.of(a), "j1", "1",
                        ObservationSource.HUMAN, "rubric A", List.of(ArtifactRef.of("review", "1"))),
                new PairwiseJudgment(b, a, QualityDimension.MAINTAINABILITY, Optional.empty(), "j2", "1",
                        ObservationSource.MODEL_JUDGE, "rubric B", List.of(ArtifactRef.of("review", "2"))),
                // identical to the first except for its evidence: only the evidence tiebreaker orders them
                new PairwiseJudgment(a, b, QualityDimension.ARCHITECTURE, Optional.of(a), "j1", "1",
                        ObservationSource.HUMAN, "rubric A", List.of(ArtifactRef.of("review", "0"))),
                new PairwiseJudgment(a, b, QualityDimension.ARCHITECTURE, Optional.of(a), "j1", "1",
                        ObservationSource.HUMAN, "rubric A", List.of(ArtifactRef.of("review", "1"), ArtifactRef.of("log", "z"))));

        ComparisonReport expected = ExecutionComparator.report(list, POLICY_2D, judgments);

        Random random = new Random(42);
        for (int i = 0; i < 25; i++) {
            List<ComparisonCandidate> shuffled = new ArrayList<>(list);
            Collections.shuffle(shuffled, random);
            List<PairwiseJudgment> reversed = new ArrayList<>(judgments);
            Collections.shuffle(reversed, random);
            assertEquals(expected, ExecutionComparator.report(shuffled, POLICY_2D, reversed));
        }
        assertEquals(List.of("exec-a", "exec-a", "exec-b", "exec-c", "exec-d"),
                expected.participants().stream().map(p -> p.ref().execution().value()).toList());
        assertEquals(List.of("scope-1", "scope-2"),
                expected.participants().stream().limit(2).map(p -> p.ref().scope().value()).toList());
        assertEquals(2, expected.groups().size(), "the other task forms its own group");
        assertEquals(10, expected.pairs().size());
        PairwiseComparison ab = expected.pairs().stream()
                .filter(p -> p.left().ref().equals(a) && p.right().ref().equals(b)).findFirst().orElseThrow();
        assertEquals(4, ab.judgments().size());
        assertEquals(List.of("0", "1", "1"), ab.judgments().stream().filter(j -> j.judgeId().equals("j1"))
                .map(j -> j.evidence().get(0).reference()).toList()); // evidence alone orders the three j1 entries
    }

    @Test
    void mandatoryGateSetOrderCannotChangeThePolicy() {
        assertEquals(new ComparisonPolicy(Set.of(QualityDimension.SECURITY, QualityDimension.TESTS), List.of()),
                new ComparisonPolicy(Set.of(QualityDimension.TESTS, QualityDimension.SECURITY), List.of()));
    }

    @Test
    void invalidInputFailsFast() {
        ComparisonFixtures f = new ComparisonFixtures(root);
        ComparisonCandidate a = candidate(f.valid("exec-a", 1, 1, 1));
        ComparisonCandidate b = candidate(f.valid("exec-b", 1, 1, 1));
        Objective cost = new Objective("cost", new Metric.Cost(Currency.getInstance("USD")), Direction.MINIMIZE, BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> new ComparisonPolicy(Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Objective("x", new Metric.Effort(), Direction.MINIMIZE,
                new BigDecimal("-0.1")));
        assertThrows(IllegalArgumentException.class, () -> new ComparisonPolicy(Set.of(QualityDimension.TESTS), List.of(cost, cost)));
        assertThrows(IllegalArgumentException.class, () -> new Metric.Measured(QualityDimension.COMPLEXITY, " "));
        assertThrows(IllegalArgumentException.class, () -> ExecutionComparator.report(List.of(a, a), POLICY_2D));
        assertThrows(IllegalArgumentException.class, () -> ExecutionComparator.compare(a, a, POLICY_2D));
        assertThrows(NullPointerException.class, () -> ComparisonCandidate.of(null, a.view(), book(usd()), List.of()));
        assertThrows(NullPointerException.class, () -> ComparisonCandidate.of(ScopeId.of("scope-1"), null, book(usd()), List.of()));

        ExecutionRef known = a.ref();
        ExecutionRef unknown = new ExecutionRef(ScopeId.of("scope-1"), ExecutionId.of("nobody"));
        ArtifactRef evidence = ArtifactRef.of("review", "1");
        assertThrows(IllegalArgumentException.class, () -> ExecutionComparator.report(List.of(a, b), POLICY_2D,
                List.of(new PairwiseJudgment(known, unknown, QualityDimension.ARCHITECTURE, Optional.empty(), "j", "1",
                        ObservationSource.HUMAN, "r", List.of(evidence)))));
        assertThrows(IllegalArgumentException.class, () -> new PairwiseJudgment(known, b.ref(), QualityDimension.ARCHITECTURE,
                Optional.of(unknown), "j", "1", ObservationSource.HUMAN, "r", List.of(evidence)));
        assertThrows(IllegalArgumentException.class, () -> new PairwiseJudgment(known, b.ref(), QualityDimension.ARCHITECTURE,
                Optional.empty(), "j", "1", ObservationSource.HUMAN, "r", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PairwiseJudgment(known, b.ref(), QualityDimension.ARCHITECTURE,
                Optional.empty(), "j", "1", ObservationSource.HUMAN, " ", List.of(evidence)));
    }
}
