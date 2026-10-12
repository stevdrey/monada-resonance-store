package com.monada.evaluation.execution;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** A billed amount supplied by the caller. It is reported next to, and never merged with, hypothetical cost. */
public record ActualBilledAmount(Currency currency, BigDecimal amount, String source) {
    public ActualBilledAmount {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(source, "source");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }
}
