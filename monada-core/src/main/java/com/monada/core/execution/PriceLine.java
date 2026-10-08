package com.monada.core.execution;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Price of one counter kind per {@code unitSize} units. {@code includedIn} declares overlap, e.g.
 * {@code CACHED_INPUT_TOKENS} included in {@code INPUT_TOKENS}.
 */
public record PriceLine(UsageKind kind, BigDecimal price, long unitSize, Optional<UsageKind> includedIn) {
    public PriceLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(includedIn, "includedIn");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must be non-negative");
        }
        if (unitSize <= 0) {
            throw new IllegalArgumentException("unitSize must be positive");
        }
        if (includedIn.isPresent() && includedIn.get() == kind) {
            throw new IllegalArgumentException("a price line cannot be included in itself");
        }
    }

    public static PriceLine of(UsageKind kind, BigDecimal price, long unitSize) {
        return new PriceLine(kind, price, unitSize, Optional.empty());
    }
}
