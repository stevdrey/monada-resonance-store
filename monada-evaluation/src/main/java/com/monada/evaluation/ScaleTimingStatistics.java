package com.monada.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Summary of best-effort wall-clock timing and throughput statistics across
 * measured evaluation query repetitions.
 *
 * <p>All timing values are reported for exploratory diagnostic purposes and
 * should not be asserted against strict thresholds in CI.
 */
public record ScaleTimingStatistics(
        int queryCount,
        int repetitionCount,
        int sampleCount,
        long minNanos,
        long medianNanos,
        long p95Nanos,
        long maxNanos,
        long avgNanos,
        long avgEncodeNanos,
        long avgScanNanos,
        double queriesPerSecond
) {
    public ScaleTimingStatistics {
        if (queryCount < 0) {
            throw new IllegalArgumentException("queryCount must be >= 0, got: " + queryCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0, got: " + sampleCount);
        }
        if (minNanos < 0) {
            throw new IllegalArgumentException("minNanos must be >= 0, got: " + minNanos);
        }
        if (medianNanos < 0) {
            throw new IllegalArgumentException("medianNanos must be >= 0, got: " + medianNanos);
        }
        if (p95Nanos < 0) {
            throw new IllegalArgumentException("p95Nanos must be >= 0, got: " + p95Nanos);
        }
        if (maxNanos < 0) {
            throw new IllegalArgumentException("maxNanos must be >= 0, got: " + maxNanos);
        }
        if (avgNanos < 0) {
            throw new IllegalArgumentException("avgNanos must be >= 0, got: " + avgNanos);
        }
        if (avgEncodeNanos < 0) {
            throw new IllegalArgumentException("avgEncodeNanos must be >= 0, got: " + avgEncodeNanos);
        }
        if (avgScanNanos < 0) {
            throw new IllegalArgumentException("avgScanNanos must be >= 0, got: " + avgScanNanos);
        }
        if (!Double.isFinite(queriesPerSecond) || queriesPerSecond < 0.0) {
            throw new IllegalArgumentException("queriesPerSecond must be finite and >= 0, got: " + queriesPerSecond);
        }
    }

    public static ScaleTimingStatistics from(
            List<Long> latencies,
            List<Long> encodeLatencies,
            List<Long> scanLatencies,
            int queryCount,
            int repetitionCount) {
        Objects.requireNonNull(latencies, "latencies");
        Objects.requireNonNull(encodeLatencies, "encodeLatencies");
        Objects.requireNonNull(scanLatencies, "scanLatencies");
        if (queryCount < 0) {
            throw new IllegalArgumentException("queryCount must be >= 0, got: " + queryCount);
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0, got: " + repetitionCount);
        }

        int sampleCount = latencies.size();
        if (sampleCount == 0) {
            return new ScaleTimingStatistics(queryCount, repetitionCount, 0, 0, 0, 0, 0, 0, 0, 0, 0.0);
        }

        var sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        long min = sorted.getFirst();
        long max = sorted.getLast();
        long median = sorted.get(sampleCount / 2);
        int p95Index = Math.min(sampleCount - 1, (int) Math.floor(sampleCount * 0.95));
        long p95 = sorted.get(p95Index);

        long totalElapsed = 0;
        for (long lat : sorted) {
            totalElapsed += lat;
        }
        long avg = totalElapsed / sampleCount;

        long totalEncode = 0;
        for (long enc : encodeLatencies) {
            totalEncode += enc;
        }
        long avgEncode = encodeLatencies.isEmpty() ? 0 : totalEncode / encodeLatencies.size();

        long totalScan = 0;
        for (long scn : scanLatencies) {
            totalScan += scn;
        }
        long avgScan = scanLatencies.isEmpty() ? 0 : totalScan / scanLatencies.size();

        double qps = avg == 0 ? 0.0 : (1_000_000_000.0 / avg);

        return new ScaleTimingStatistics(
                queryCount,
                repetitionCount,
                sampleCount,
                min,
                median,
                p95,
                max,
                avg,
                avgEncode,
                avgScan,
                qps
        );
    }
}
