package com.monada.core;

import java.util.List;
import java.util.Objects;

public record MonadaRecall(List<ResonanceResult> results) {
    public MonadaRecall {
        Objects.requireNonNull(results);
        results = List.copyOf(results);
    }
}
