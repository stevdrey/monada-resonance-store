package com.monada.evaluation.execution;

import com.monada.core.execution.UsageKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Usage of a set of stages with provenance kept apart: reported and estimated values are never merged, an
 * unknown counter and a counter that was not measured are counted, not added as zero. Not-measured counts
 * apply to {@code MEASURED} stages only.
 */
public record UsageTotals(int stageCount, Map<UsageKind, KindUsage> byKind) {

    /** Totals of one usage kind: sums per provenance and the number of stages without a value. */
    public record KindUsage(long reported, long estimated, int unknownStages, int notMeasuredStages) {
        public KindUsage {
            if (reported < 0 || estimated < 0 || unknownStages < 0 || notMeasuredStages < 0) {
                throw new IllegalArgumentException("usage totals must be non-negative");
            }
        }
    }

    public UsageTotals {
        Objects.requireNonNull(byKind, "byKind");
        EnumMap<UsageKind, KindUsage> copy = new EnumMap<>(UsageKind.class);
        copy.putAll(byKind);
        byKind = Collections.unmodifiableMap(copy);
    }
}
