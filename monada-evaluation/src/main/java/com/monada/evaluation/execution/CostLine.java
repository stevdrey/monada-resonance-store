package com.monada.evaluation.execution;

import com.monada.core.execution.UsageKind;
import com.monada.core.execution.UsageProvenance;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One priced component of a stage. {@code billableQuantity} is the non-overlapping quantity (counter value
 * minus the values of kinds included in it); {@code provenance} is {@code ESTIMATED} when any contributing
 * counter is estimated. {@code cost} is unrounded (scale at most 18).
 */
public record CostLine(UsageKind kind, long billableQuantity, UsageProvenance provenance, BigDecimal price,
                       long unitSize, BigDecimal cost) {
    public CostLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(cost, "cost");
        if (billableQuantity < 0) {
            throw new IllegalArgumentException("billableQuantity must be >= 0");
        }
        if (provenance == UsageProvenance.UNKNOWN) {
            throw new IllegalArgumentException("a cost line cannot be built on unknown usage");
        }
    }
}
