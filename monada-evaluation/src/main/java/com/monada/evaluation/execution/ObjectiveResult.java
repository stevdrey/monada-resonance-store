package com.monada.evaluation.execution;

import com.monada.evaluation.execution.ComparisonPolicy.Direction;
import com.monada.evaluation.execution.ComparisonPolicy.Objective;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of one declared objective for a pair. {@code INCONCLUSIVE} carries every reason and means no
 * ordering is claimed for this objective: incomplete cost, mixed currencies or pricing assumptions and
 * incommensurable quality axes are never turned into a number.
 */
public record ObjectiveResult(Objective objective, Verdict verdict, Optional<BigDecimal> leftValue,
                              Optional<BigDecimal> rightValue, List<Reason> reasons) {

    public enum Verdict { LEFT_BETTER, RIGHT_BETTER, TIE, INCONCLUSIVE }

    public enum Side { LEFT, RIGHT, BOTH }

    public enum Code {
        INCOMPLETE_COST, MIXED_CURRENCY, MIXED_PRICING_ASSUMPTIONS, MISSING_TIME, MISSING_MEASUREMENT,
        INCOMMENSURABLE_AXIS, UNRELIABLE_MEASUREMENT, NO_OBJECTIVES_DECLARED, DOMINANCE_CYCLE
    }

    public record Reason(Side side, Code code, String detail) {
        public Reason {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public ObjectiveResult {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(leftValue, "leftValue");
        Objects.requireNonNull(rightValue, "rightValue");
        reasons = List.copyOf(reasons);
        if ((verdict == Verdict.INCONCLUSIVE) == reasons.isEmpty()) {
            throw new IllegalArgumentException("exactly the inconclusive results carry reasons");
        }
    }

    static ObjectiveResult of(Objective objective, Measurements.Reading left, Measurements.Reading right) {
        List<Reason> reasons = new ArrayList<>();
        left.gaps().forEach(g -> reasons.add(new Reason(Side.LEFT, g.code(), g.detail())));
        right.gaps().forEach(g -> reasons.add(new Reason(Side.RIGHT, g.code(), g.detail())));
        if (reasons.isEmpty() && left.basis().isPresent() && right.basis().isPresent()
                && !left.basis().get().equals(right.basis().get())) {
            Code code = objective.metric() instanceof ComparisonPolicy.Metric.Cost
                    ? Code.MIXED_PRICING_ASSUMPTIONS : Code.INCOMMENSURABLE_AXIS;
            reasons.add(new Reason(Side.BOTH, code,
                    "left basis " + left.basis().get() + " differs from right basis " + right.basis().get()));
        }
        if (!reasons.isEmpty()) {
            return new ObjectiveResult(objective, Verdict.INCONCLUSIVE, left.value(), right.value(), reasons);
        }
        BigDecimal l = left.value().orElseThrow();
        BigDecimal r = right.value().orElseThrow();
        Verdict verdict;
        if (l.subtract(r).abs().compareTo(objective.tolerance()) <= 0) {
            verdict = Verdict.TIE;
        } else {
            boolean leftLower = l.compareTo(r) < 0;
            boolean leftBetter = objective.direction() == Direction.MINIMIZE ? leftLower : !leftLower;
            verdict = leftBetter ? Verdict.LEFT_BETTER : Verdict.RIGHT_BETTER;
        }
        return new ObjectiveResult(objective, verdict, left.value(), right.value(), List.of());
    }
}
