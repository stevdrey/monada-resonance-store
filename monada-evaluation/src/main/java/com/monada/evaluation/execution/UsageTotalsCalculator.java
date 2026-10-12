package com.monada.evaluation.execution;

import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sums counters per kind and provenance without ever turning unknown or unmeasured counters into zero. A
 * missing counter is counted as not measured only for kinds the stage's price snapshot prices; a stage without
 * a snapshot already carries a {@code MISSING_PRICE} gap and an unpriced kind is not a coverage gap.
 */
final class UsageTotalsCalculator {

    private UsageTotalsCalculator() {
    }

    /** {@code costs} holds the priced result of each stage, in the same order as {@code stages}. */
    static UsageTotals totals(List<StageRecorded> stages, List<StageUsageCost> costs) {
        if (stages.size() != costs.size()) {
            throw new IllegalArgumentException("stages and costs must be parallel lists");
        }
        long[] reported = new long[UsageKind.values().length];
        long[] estimated = new long[UsageKind.values().length];
        int[] unknown = new int[UsageKind.values().length];
        int[] notMeasured = new int[UsageKind.values().length];
        for (int s = 0; s < stages.size(); s++) {
            StageRecorded stage = stages.get(s);
            Set<UsageKind> priced = pricedKinds(costs.get(s));
            Map<UsageKind, UsageCounter> counters = new EnumMap<>(UsageKind.class);
            stage.usage().forEach(c -> counters.put(c.kind(), c));
            for (UsageKind kind : UsageKind.values()) {
                int i = kind.ordinal();
                UsageCounter counter = counters.get(kind);
                if (counter == null) {
                    if (stage.usageDeclaration() == UsageDeclaration.MEASURED && priced.contains(kind)) {
                        notMeasured[i]++;
                    }
                    continue;
                }
                switch (counter.provenance()) {
                    case REPORTED -> reported[i] = add(reported[i], counter.value().orElseThrow());
                    case ESTIMATED -> estimated[i] = add(estimated[i], counter.value().orElseThrow());
                    case UNKNOWN -> unknown[i]++;
                }
            }
        }
        Map<UsageKind, UsageTotals.KindUsage> byKind = new EnumMap<>(UsageKind.class);
        for (UsageKind kind : UsageKind.values()) {
            int i = kind.ordinal();
            byKind.put(kind, new UsageTotals.KindUsage(reported[i], estimated[i], unknown[i], notMeasured[i]));
        }
        return new UsageTotals(stages.size(), byKind);
    }

    private static Set<UsageKind> pricedKinds(StageUsageCost cost) {
        Set<UsageKind> kinds = EnumSet.noneOf(UsageKind.class);
        cost.priceSnapshot().ifPresent(snapshot -> snapshot.lines().forEach(l -> kinds.add(l.kind())));
        return kinds;
    }

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("usage total overflows long", e);
        }
    }
}
