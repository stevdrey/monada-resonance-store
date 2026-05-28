package com.monada.encoder;

import java.util.Objects;

public record WeightedToken(String token, double weight) {
    public WeightedToken {
        Objects.requireNonNull(token, "token");
        if (!Double.isFinite(weight) || weight <= 0.0) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
    }
}
