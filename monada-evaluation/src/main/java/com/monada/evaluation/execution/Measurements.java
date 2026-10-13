package com.monada.evaluation.execution;

import com.monada.core.execution.ObservationState;
import com.monada.core.execution.QualityObservation;
import com.monada.evaluation.execution.ComparisonPolicy.Metric;
import com.monada.evaluation.execution.ObjectiveResult.Code;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reads one metric from an {@link ExecutionSummary} without guessing: a value is present only when the
 * evidence is complete, otherwise the reading carries its gaps. {@code basis} says what a value is only
 * comparable with (price assumptions, evaluator and source); readings with different bases are not compared.
 */
final class Measurements {

    record Gap(Code code, String detail) {
    }

    record Reading(Optional<BigDecimal> value, Optional<List<String>> basis, List<Gap> gaps) {
        Reading {
            basis = basis.map(List::copyOf);
            gaps = List.copyOf(gaps);
        }

        static Reading of(BigDecimal value, Optional<List<String>> basis) {
            return new Reading(Optional.of(value), basis, List.of());
        }

        static Reading gap(Code code, String detail) {
            return gaps(List.of(new Gap(code, detail)));
        }

        static Reading gaps(List<Gap> gaps) {
            return new Reading(Optional.empty(), Optional.empty(), gaps);
        }
    }

    private Measurements() {
    }

    static Reading read(ExecutionSummary summary, Metric metric) {
        return switch (metric) {
            case Metric.Cost c -> cost(summary, c);
            case Metric.Effort e -> time(summary.chainTime().effort(), summary.chainTime());
            case Metric.WallClock w -> time(summary.chainTime().wallClock(), summary.chainTime());
            case Metric.Measured m -> measured(summary, m);
        };
    }

    private static Reading cost(ExecutionSummary summary, Metric.Cost metric) {
        CostToAcceptedOutcome cost = summary.cost();
        if (cost.status() != CostToAcceptedOutcome.Status.AVAILABLE) {
            List<String> reasons = cost.reasons().stream().map(r -> r.code()
                    + r.stage().map(s -> "(" + s + r.kind().map(k -> ", " + k).orElse("") + ")").orElse("")).toList();
            return Reading.gap(Code.INCOMPLETE_COST, "cost is " + cost.status() + " " + reasons);
        }
        List<Gap> gaps = new ArrayList<>();
        String wanted = metric.currency().getCurrencyCode();
        List<String> currencies = cost.amounts().stream().map(a -> a.currency().getCurrencyCode()).toList();
        if (currencies.size() > 1 || (!currencies.isEmpty() && !currencies.get(0).equals(wanted))) {
            gaps.add(new Gap(Code.MIXED_CURRENCY, "chain is priced in " + currencies + ", objective is " + wanted));
        }
        TreeSet<String> assumptions = new TreeSet<>();
        for (StageUsageCost stage : summary.chainStages()) {
            stage.priceSnapshot().ifPresent(s -> assumptions.add(s.assumptions()));
        }
        if (assumptions.size() > 1) {
            gaps.add(new Gap(Code.MIXED_PRICING_ASSUMPTIONS, "chain mixes pricing assumptions " + assumptions));
        }
        if (!gaps.isEmpty()) {
            return Reading.gaps(gaps);
        }
        // A declared zero (every stage NO_BILLABLE_USAGE) has no currency and no assumptions: it is exactly 0.
        BigDecimal value = cost.amounts().isEmpty() ? BigDecimal.ZERO : cost.amounts().get(0).unrounded();
        return Reading.of(value, assumptions.isEmpty() ? Optional.empty() : Optional.of(List.of(assumptions.first())));
    }

    private static Reading time(Optional<Duration> duration, TimeSummary time) {
        if (duration.isEmpty()) {
            return Reading.gap(Code.MISSING_TIME, time.unavailableReason().orElse("time is not available"));
        }
        Duration d = duration.get();
        return Reading.of(BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9)), Optional.empty());
    }

    private static Reading measured(ExecutionSummary summary, Metric.Measured metric) {
        Optional<GateEvidence.Observed> observed = summary.gates().stream()
                .filter(g -> g.dimension() == metric.dimension())
                .flatMap(g -> g.effective().stream()).findFirst();
        if (observed.isEmpty() || observed.get().observation().measuredValue().isEmpty()) {
            return Reading.gap(Code.MISSING_MEASUREMENT,
                    "no measured " + metric.dimension() + " value on the accepted attempt");
        }
        QualityObservation o = observed.get().observation();
        if (o.state() != ObservationState.PASS) {
            return Reading.gap(Code.UNRELIABLE_MEASUREMENT, metric.dimension() + " has a measured value but its state is "
                    + o.state() + " (" + o.evaluatorId() + "@" + o.evaluatorVersion() + "), not PASS");
        }
        String unit = o.unit().orElseThrow();
        if (!unit.equals(metric.unit())) {
            return Reading.gap(Code.INCOMMENSURABLE_AXIS,
                    metric.dimension() + " is measured in " + unit + ", objective expects " + metric.unit());
        }
        return Reading.of(BigDecimal.valueOf(o.measuredValue().getAsDouble()),
                Optional.of(List.of(o.evaluatorId(), o.evaluatorVersion(), o.source().name())));
    }
}
