package com.monada.evaluation;

import java.util.Objects;
import java.util.OptionalDouble;

/** Structural safety counts for one normalization transformation category. */
public record NormalizedQueryKeyStressCategorySummary(
        NormalizationStressCategory category,
        int pairCount,
        int equivalentPairCount,
        int equivalentKeyMatchCount,
        int distinctPairCount,
        int distinctKeyCollisionCount,
        int contaminationCount
) {
    public NormalizedQueryKeyStressCategorySummary {
        Objects.requireNonNull(category, "category");
        int[] counts = {
                pairCount,
                equivalentPairCount,
                equivalentKeyMatchCount,
                distinctPairCount,
                distinctKeyCollisionCount,
                contaminationCount
        };
        for (int count : counts) {
            if (count < 0) {
                throw new IllegalArgumentException("summary counts must be non-negative");
            }
        }
        if (pairCount != equivalentPairCount + distinctPairCount) {
            throw new IllegalArgumentException("pairCount must partition equivalent and distinct pairs");
        }
        if (equivalentKeyMatchCount > equivalentPairCount
                || distinctKeyCollisionCount > distinctPairCount
                || contaminationCount > distinctKeyCollisionCount) {
            throw new IllegalArgumentException("summary counts exceed their category totals");
        }
    }

    public OptionalDouble equivalentKeyMatchRate() {
        return rate(equivalentKeyMatchCount, equivalentPairCount);
    }

    public OptionalDouble distinctKeyCollisionRate() {
        return rate(distinctKeyCollisionCount, distinctPairCount);
    }

    public OptionalDouble contaminationRate() {
        return rate(contaminationCount, distinctPairCount);
    }

    private OptionalDouble rate(int numerator, int denominator) {
        return denominator == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) numerator / denominator);
    }
}
