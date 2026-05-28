package com.monada.storage;

import java.util.Objects;

public record EncodingProfile(
    String encoder,
    String encoderVersion,
    int dimensions,
    String normalizer,
    String normalizerVersion,
    String weightingStrategy,
    double originalWeight,
    double expansionWeight,
    double aliasOriginalWeight,
    double aliasExpansionWeight
) {
    public EncodingProfile {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(encoderVersion, "encoderVersion");
        Objects.requireNonNull(normalizer, "normalizer");
        Objects.requireNonNull(normalizerVersion, "normalizerVersion");
        Objects.requireNonNull(weightingStrategy, "weightingStrategy");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero");
        }
    }
}
