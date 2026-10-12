package com.monada.evaluation.execution;

import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.UsageCounter;
import com.monada.core.execution.UsageDeclaration;
import com.monada.core.execution.UsageKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Sums counters per kind and provenance without ever turning unknown or unmeasured counters into zero. */
final class UsageTotalsCalculator {

    private UsageTotalsCalculator() {
    }

    static UsageTotals totals(List<StageRecorded> stages) {
        long[] reported = new long[UsageKind.values().length];
        long[] estimated = new long[UsageKind.values().length];
        int[] unknown = new int[UsageKind.values().length];
        int[] notMeasured = new int[UsageKind.values().length];
        for (StageRecorded stage : stages) {
            Map<UsageKind, UsageCounter> counters = new EnumMap<>(UsageKind.class);
            stage.usage().forEach(c -> counters.put(c.kind(), c));
            for (UsageKind kind : UsageKind.values()) {
                int i = kind.ordinal();
                UsageCounter counter = counters.get(kind);
                if (counter == null) {
                    if (stage.usageDeclaration() == UsageDeclaration.MEASURED) {
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

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("usage total overflows long", e);
        }
    }
}
