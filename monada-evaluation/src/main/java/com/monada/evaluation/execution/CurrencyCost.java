package com.monada.evaluation.execution;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Hypothetical amount in one currency (currencies are never summed). {@code unrounded} is the exact sum of
 * line costs; {@code amount} is rounded once to scale 6 with {@code HALF_EVEN}. {@code reportedBasis} and
 * {@code estimatedBasis} split {@code unrounded} by usage provenance and always add up to it.
 */
public record CurrencyCost(Currency currency, BigDecimal unrounded, BigDecimal reportedBasis,
                           BigDecimal estimatedBasis, BigDecimal amount) {
    public CurrencyCost {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(unrounded, "unrounded");
        Objects.requireNonNull(reportedBasis, "reportedBasis");
        Objects.requireNonNull(estimatedBasis, "estimatedBasis");
        Objects.requireNonNull(amount, "amount");
        if (reportedBasis.add(estimatedBasis).compareTo(unrounded) != 0) {
            throw new IllegalArgumentException("reported and estimated basis must add up to the unrounded amount");
        }
    }
}
