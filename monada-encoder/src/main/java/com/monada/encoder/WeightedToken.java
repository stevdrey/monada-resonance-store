package com.monada.encoder;

import java.util.Objects;

public record WeightedToken(String token, double weight) {
    public WeightedToken {
        Objects.requireNonNull(token, "token");
        if (weight <= 0.0) {
            throw new IllegalArgumentException("weight must be positive");
        }
    }
}
