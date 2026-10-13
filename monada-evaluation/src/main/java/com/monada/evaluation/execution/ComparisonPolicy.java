package com.monada.evaluation.execution;

import com.monada.core.execution.QualityDimension;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything a comparison needs the caller to declare: which quality dimensions are mandatory gates and which
 * objectives (metric, direction, tolerance) are compared. Nothing is defaulted and objectives are never
 * weighted into one number.
 *
 * <p>An execution takes part in winner selection and Pareto analysis only if it is a validated acceptance and
 * every mandatory gate has an explicit {@code PASS} or a justified {@code NOT_APPLICABLE}. A policy without
 * objectives still yields comparability and eligibility.
 */
public record ComparisonPolicy(Set<QualityDimension> mandatoryGates, List<Objective> objectives) {

    public enum Direction { MINIMIZE, MAXIMIZE }

    /** What an objective measures. Every metric is read from the execution's chain up to acceptance. */
    public sealed interface Metric {
        /** Hypothetical API-equivalent cost of the chain, in exactly this currency. */
        record Cost(Currency currency) implements Metric {
            public Cost {
                Objects.requireNonNull(currency, "currency");
            }
        }

        /** Sum of stage durations, in seconds. */
        record Effort() implements Metric {
        }

        /** First stage start to last stage end, in seconds. */
        record WallClock() implements Metric {
        }

        /** A measured value (for example lines of code or a complexity figure) with an explicit unit. */
        record Measured(QualityDimension dimension, String unit) implements Metric {
            public Measured {
                Objects.requireNonNull(dimension, "dimension");
                if (Objects.requireNonNull(unit, "unit").isBlank()) {
                    throw new IllegalArgumentException("unit must not be blank");
                }
            }
        }
    }

    /**
     * Two values closer than {@code tolerance} (inclusive) are a tie. Dominance is therefore defined pairwise
     * and is not claimed to be transitive.
     */
    public record Objective(String id, Metric metric, Direction direction, BigDecimal tolerance) {
        public Objective {
            if (Objects.requireNonNull(id, "id").isBlank()) {
                throw new IllegalArgumentException("objective id must not be blank");
            }
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(direction, "direction");
            if (Objects.requireNonNull(tolerance, "tolerance").signum() < 0) {
                throw new IllegalArgumentException("tolerance must not be negative");
            }
        }
    }

    public ComparisonPolicy {
        Objects.requireNonNull(mandatoryGates, "mandatoryGates");
        Objects.requireNonNull(objectives, "objectives");
        if (mandatoryGates.isEmpty()) {
            throw new IllegalArgumentException("a comparison policy must declare at least one mandatory gate");
        }
        // Enum order, so the report never depends on the iteration order of the caller's set.
        mandatoryGates = Collections.unmodifiableSet(EnumSet.copyOf(mandatoryGates));
        objectives = List.copyOf(objectives);
        Set<String> ids = new HashSet<>();
        for (Objective objective : objectives) {
            if (!ids.add(objective.id())) {
                throw new IllegalArgumentException("duplicate objective id: " + objective.id());
            }
        }
    }

    public static ComparisonPolicy of(Collection<QualityDimension> gates, List<Objective> objectives) {
        return new ComparisonPolicy(Set.copyOf(gates), objectives);
    }
}
