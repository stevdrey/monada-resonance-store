package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate latency and scan statistics across all evaluation queries.
 *
 * <p>Timing values are best-effort and are meant to be reported, not used as
 * strict CI thresholds. Structural values such as {@code queryCount},
 * {@code corpusSize}, {@code totalScanned}, and {@code totalReturned} are
 * deterministic for a given dataset and runner configuration.
 */
public record LatencySummary(
        int queryCount,
        int corpusSize,
        int maxK,
        long totalElapsedNanos,
        long minElapsedNanos,
        long maxElapsedNanos,
        long avgElapsedNanos,
        long totalScanned,
        long totalReturned
) {
    public LatencySummary {
        if (queryCount < 0) {
            throw new IllegalArgumentException("queryCount must be >= 0, got: " + queryCount);
        }
        if (corpusSize < 0) {
            throw new IllegalArgumentException("corpusSize must be >= 0, got: " + corpusSize);
        }
        if (maxK <= 0) {
            throw new IllegalArgumentException("maxK must be > 0, got: " + maxK);
        }
        if (totalElapsedNanos < 0) {
            throw new IllegalArgumentException("totalElapsedNanos must be >= 0, got: " + totalElapsedNanos);
        }
        if (minElapsedNanos < 0) {
            throw new IllegalArgumentException("minElapsedNanos must be >= 0, got: " + minElapsedNanos);
        }
        if (maxElapsedNanos < 0) {
            throw new IllegalArgumentException("maxElapsedNanos must be >= 0, got: " + maxElapsedNanos);
        }
        if (avgElapsedNanos < 0) {
            throw new IllegalArgumentException("avgElapsedNanos must be >= 0, got: " + avgElapsedNanos);
        }
        if (totalScanned < 0) {
            throw new IllegalArgumentException("totalScanned must be >= 0, got: " + totalScanned);
        }
        if (totalReturned < 0) {
            throw new IllegalArgumentException("totalReturned must be >= 0, got: " + totalReturned);
        }
    }

    /**
     * Computes a summary from per-query metrics.
     *
     * @param queryMetrics per-query latency records for the run
     * @param maxK         the maximum K used across all queries (same value as in each metric)
     * @param corpusSize   the number of atoms in the corpus (passed explicitly to avoid silent
     *                     first-entry extraction when future callers mix metrics from different runs)
     */
    public static LatencySummary from(List<QueryLatencyMetrics> queryMetrics, int maxK, int corpusSize) {
        Objects.requireNonNull(queryMetrics, "queryMetrics");
        int queryCount = queryMetrics.size();
        long totalElapsed = 0;
        long minElapsed = Long.MAX_VALUE;
        long maxElapsed = 0;
        long totalScanned = 0;
        long totalReturned = 0;
        for (var m : queryMetrics) {
            totalElapsed += m.elapsedNanos();
            minElapsed = Math.min(minElapsed, m.elapsedNanos());
            maxElapsed = Math.max(maxElapsed, m.elapsedNanos());
            totalScanned += m.scannedCandidates();
            totalReturned += m.returnedCandidates();
        }
        long avgElapsed = queryCount == 0 ? 0 : totalElapsed / queryCount;
        if (queryCount == 0) {
            minElapsed = 0;
        }
        return new LatencySummary(
                queryCount,
                corpusSize,
                maxK,
                totalElapsed,
                minElapsed,
                maxElapsed,
                avgElapsed,
                totalScanned,
                totalReturned
        );
    }
}
