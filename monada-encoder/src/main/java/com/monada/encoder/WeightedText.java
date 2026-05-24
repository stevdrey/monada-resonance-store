package com.monada.encoder;

import java.util.List;
import java.util.Objects;

public record WeightedText(List<WeightedToken> tokens) {
    public WeightedText {
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
    }
}
