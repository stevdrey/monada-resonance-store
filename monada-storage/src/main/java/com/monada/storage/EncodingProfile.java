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
        validatePositiveFiniteWeight("originalWeight", originalWeight);
        validatePositiveFiniteWeight("expansionWeight", expansionWeight);
        validatePositiveFiniteWeight("aliasOriginalWeight", aliasOriginalWeight);
        validatePositiveFiniteWeight("aliasExpansionWeight", aliasExpansionWeight);
    }

    private static void validatePositiveFiniteWeight(String field, double weight) {
        if (!Double.isFinite(weight) || weight <= 0.0) {
            throw new IllegalArgumentException(field + " must be finite and positive");
        }
    }
}
