package com.monada.evaluation.book;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Distribution of one latency series (Issue #168): wall-clock nanoseconds, informational only, never a gate.
 *
 * <p>Percentiles are nearest-rank over the sorted samples: {@code pX} is the sample at rank {@code ceil(X/100 * n)}
 * (1-based), with no interpolation, so every reported value is a latency that was actually observed. A tail
 * percentile is only reported when at least one sample lies above it ({@code n >= 100/(100-X)}): p50 needs
 * {@value #MIN_P50} sample, p95 {@value #MIN_P95} and p99 {@value #MIN_P99}; below that it would just be the maximum,
 * so it is empty and rendered as {@code n/a}. The standard deviation is the sample one ({@code n-1}) and needs two
 * samples.
 */
record BookLatencyStats(int n, long min, long max, double mean, double stddev, OptionalLong p50, OptionalLong p95,
                        OptionalLong p99, long totalNanos) {
    static final int MIN_P50 = 1;
    static final int MIN_P95 = 20;
    static final int MIN_P99 = 100;

    static BookLatencyStats of(List<Long> nanos) {
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        int n = sorted.length;
        if (n == 0) {
            return new BookLatencyStats(0, 0, 0, Double.NaN, Double.NaN, OptionalLong.empty(), OptionalLong.empty(),
                    OptionalLong.empty(), 0);
        }
        for (long v : sorted) {
            if (v < 0) {
                throw new IllegalArgumentException("negative latency " + v);
            }
        }
        long total = Arrays.stream(sorted).sum();
        double mean = (double) total / n;
        double stddev = Double.NaN;
        if (n >= 2) {
            double squares = 0;
            for (long v : sorted) {
                squares += (v - mean) * (v - mean);
            }
            stddev = Math.sqrt(squares / (n - 1));
        }
        return new BookLatencyStats(n, sorted[0], sorted[n - 1], mean, stddev, percentile(sorted, 50, MIN_P50),
                percentile(sorted, 95, MIN_P95), percentile(sorted, 99, MIN_P99), total);
    }

    /** Nearest-rank percentile of {@code sorted}, or empty when it has fewer than {@code minSamples} samples. */
    static OptionalLong percentile(long[] sorted, int percent, int minSamples) {
        int n = sorted.length;
        if (n == 0 || n < minSamples) {
            return OptionalLong.empty();
        }
        int rank = (int) Math.ceil(percent / 100.0 * n);
        return OptionalLong.of(sorted[Math.max(1, Math.min(n, rank)) - 1]);
    }

    /** Units per second over the summed elapsed time, or NaN when nothing elapsed (never an infinite rate). */
    static double perSecond(double units, long nanos) {
        return nanos <= 0 ? Double.NaN : units * 1_000_000_000.0 / nanos;
    }

    /** {@code pX} in microseconds, or {@code n/a (n=<n> < <min>)} when the series is too small for it. */
    static String micros(OptionalLong value, int n, int minSamples) {
        return value.isPresent() ? fmt(value.getAsLong() / 1_000.0) : "n/a (n=" + n + " < " + minSamples + ")";
    }

    static String fmt(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.1f", value);
    }
}
