package com.monada.evaluation.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evidence-gated comparison of two executions. There is no total order and no weighted score: the conclusion is
 * dominance only when every declared objective is conclusive, a tradeoff when each side is better somewhere,
 * and never an automatic winner then. Objective results are informational when a side is not eligible: a
 * cheaper execution that failed a gate is shown as such, never as a winner.
 *
 * @param objectives empty when the pair is {@code NOT_COMPARABLE}: nothing is compared across different
 *                   starting conditions
 * @param judgments  evaluator-supplied preferences for this pair, reported as given and never folded into the
 *                   conclusion
 */
public record PairwiseComparison(Participant left, Participant right, Comparability comparability,
                                 Conclusion conclusion, Optional<Winner> winner, List<ObjectiveResult> objectives,
                                 List<PairwiseJudgment> judgments, String interpretation) {

    public enum Conclusion {
        NOT_COMPARABLE, NO_ELIGIBLE_EXECUTION, ONLY_LEFT_ELIGIBLE, ONLY_RIGHT_ELIGIBLE, NO_OBJECTIVES,
        LEFT_DOMINATES, RIGHT_DOMINATES, TRADEOFF, EQUIVALENT, INCONCLUSIVE
    }

    public enum Basis {
        /** The other execution is excluded by validation or gates; nothing is claimed about cost or quality. */
        ONLY_ELIGIBLE,
        /** The other execution is dominated on every declared objective within the declared tolerances. */
        DOMINANCE
    }

    public record Winner(ExecutionRef execution, Basis basis) {
        public Winner {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(basis, "basis");
        }
    }

    public PairwiseComparison {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(comparability, "comparability");
        Objects.requireNonNull(conclusion, "conclusion");
        Objects.requireNonNull(winner, "winner");
        objectives = List.copyOf(objectives);
        judgments = List.copyOf(judgments);
        Objects.requireNonNull(interpretation, "interpretation");
    }

    /** True when the objective results must not be used to pick a winner. */
    public boolean informationalOnly() {
        return !comparability.isComparable() || !left.eligible() || !right.eligible();
    }

    boolean dominates(ExecutionRef ref) {
        return (conclusion == Conclusion.LEFT_DOMINATES && left.ref().equals(ref))
                || (conclusion == Conclusion.RIGHT_DOMINATES && right.ref().equals(ref));
    }
}
