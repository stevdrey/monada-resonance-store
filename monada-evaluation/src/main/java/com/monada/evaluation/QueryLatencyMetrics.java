package com.monada.evaluation;

import java.util.Objects;

/**
 * Latency and scan diagnostics for a single recall query.
 *
 * <p>All metrics are structural except {@code elapsedNanos}, which is a
 * best-effort wall-clock measurement. Tests should assert the structural
 * fields and the presence of timing, never strict wall-clock thresholds.
 */
public record QueryLatencyMetrics(
        String queryText,
        int topK,
        double threshold,
        int corpusSize,
        int scannedCandidates,
        int returnedCandidates,
        long elapsedNanos,
        boolean blankQuery
) {
    public QueryLatencyMetrics {
        Objects.requireNonNull(queryText, "queryText");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        if (corpusSize < 0) {
            throw new IllegalArgumentException("corpusSize must be >= 0, got: " + corpusSize);
        }
        if (scannedCandidates < 0) {
            throw new IllegalArgumentException("scannedCandidates must be >= 0, got: " + scannedCandidates);
        }
        if (returnedCandidates < 0) {
            throw new IllegalArgumentException("returnedCandidates must be >= 0, got: " + returnedCandidates);
        }
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsedNanos must be >= 0, got: " + elapsedNanos);
        }
    }
}
