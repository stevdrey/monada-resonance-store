package com.monada.evaluation.execution;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Hypothetical amount in one currency (currencies are never summed), split by usage provenance.
 * {@link #unrounded()} is the exact sum of line costs (scale at most 18); {@link #amount()} is rounded once
 * to scale 6 with {@code HALF_EVEN}.
 */
public record CurrencyCost(Currency currency, BigDecimal reportedBasis, BigDecimal estimatedBasis) {
    public CurrencyCost {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(reportedBasis, "reportedBasis");
        Objects.requireNonNull(estimatedBasis, "estimatedBasis");
        if (reportedBasis.signum() < 0 || estimatedBasis.signum() < 0) {
            throw new IllegalArgumentException("cost bases must be non-negative");
        }
    }

    public BigDecimal unrounded() {
        return reportedBasis.add(estimatedBasis);
    }

    public BigDecimal amount() {
        return CostArithmetic.report(unrounded());
    }
}
