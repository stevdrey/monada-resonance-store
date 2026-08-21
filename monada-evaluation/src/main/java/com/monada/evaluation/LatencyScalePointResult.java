package com.monada.evaluation;

import java.util.Objects;

/**
 * Encapsulates the complete evaluation, timing, and structural scan results
 * for a single {@code (corpusSize, topK)} scale point configuration.
 */
public record LatencyScalePointResult(
        int corpusSize,
        int queryCount,
        int topK,
        int warmupCount,
        int repetitionCount,
        long totalScanned,
        double scanFraction,
        long totalReturned,
        ScaleTimingStatistics timing,
        EvaluationReport evaluationReport,
        int improvedQueryCount,
        int maintainedQueryCount,
        int degradedQueryCount
) {
    public LatencyScalePointResult {
        if (corpusSize <= 0) {
            throw new IllegalArgumentException("corpusSize must be > 0, got: " + corpusSize);
        }
        if (queryCount <= 0) {
            throw new IllegalArgumentException("queryCount must be > 0, got: " + queryCount);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0, got: " + warmupCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
        if (totalScanned < 0) {
            throw new IllegalArgumentException("totalScanned must be >= 0, got: " + totalScanned);
        }
        if (!Double.isFinite(scanFraction) || scanFraction < 0.0) {
            throw new IllegalArgumentException("scanFraction must be finite and >= 0, got: " + scanFraction);
        }
        if (totalReturned < 0) {
            throw new IllegalArgumentException("totalReturned must be >= 0, got: " + totalReturned);
        }
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(evaluationReport, "evaluationReport");
        if (improvedQueryCount < 0) {
            throw new IllegalArgumentException("improvedQueryCount must be >= 0");
        }
        if (maintainedQueryCount < 0) {
            throw new IllegalArgumentException("maintainedQueryCount must be >= 0");
        }
        if (degradedQueryCount < 0) {
            throw new IllegalArgumentException("degradedQueryCount must be >= 0");
        }
    }

    public double averageScannedPerQuery() {
        return queryCount == 0 ? 0.0 : (double) totalScanned / queryCount;
    }
}
