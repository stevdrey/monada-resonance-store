package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.VectorCopyProbe.StoreKind;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report of the evaluation-only vector copy-share measurement (Issue #135): how much of one {@code findAll()} call
 * of {@code FileFrequencyStore} and {@code FileSpeechFeatureStore} is spent on {@code FrequencyVector}'s defensive
 * copy of the freshly decoded {@code float[]}, in wall-clock time and in allocated bytes, plus a decision on
 * removing that copy.
 */
public record VectorCopyShareReport(
        String seedDatasetName,
        long seed,
        int frequencyDimensions,
        int speechDimensions,
        List<Integer> scalePoints,
        int warmupCount,
        int repetitionCount,
        boolean allocationMeasured,
        List<PointResult> results,
        Decision decision,
        String decisionRationale
) {

    /** Copy share of production {@code findAll()} p50 at or above which removing the copy is justified. */
    public static final double MEANINGFUL_TIME_SHARE = 0.10;

    /** Copy share of production {@code findAll()} allocated bytes at or above which removing it is justified. */
    public static final double MEANINGFUL_ALLOCATION_SHARE = 0.30;

    /** Every store must reach this scale for the decision to be informative. */
    public static final int MIN_DECISION_SCALE = 10_000;

    public enum Decision {
        /** At N >= 10,000 the copy reaches the time or allocation threshold in at least one store. */
        COPY_SHARE_MEANINGFUL_REMOVE_COPY,
        /** The copy stays below both thresholds in every store. */
        COPY_SHARE_NEGLIGIBLE,
        /** Not enough evidence (no timing samples or a store without an N >= 10,000 point). */
        INCONCLUSIVE
    }

    /**
     * One store at one scale point. Latencies are per {@code findAll()} call; bytes are the median per-call
     * allocation on the measuring thread, or -1 when allocation accounting is unavailable.
     */
    public record PointResult(
            StoreKind store,
            int corpusSize,
            int dimensions,
            int sampleCount,
            PhaseLatency productionFindAll,
            PhaseLatency copyingReplica,
            PhaseLatency adoptingReplica,
            long productionBytes,
            long copyingBytes,
            long adoptingBytes) {

        public PointResult {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(productionFindAll, "productionFindAll");
            Objects.requireNonNull(copyingReplica, "copyingReplica");
            Objects.requireNonNull(adoptingReplica, "adoptingReplica");
        }

        /** Bytes of one {@code float[dimensions]} with a 16-byte header, 8-byte aligned (compressed oops). */
        public long expectedCopyBytesPerVector() {
            return (16L + 4L * dimensions + 7L) & ~7L;
        }

        /** {@code corpusSize} times {@link #expectedCopyBytesPerVector()}: what removing the copy should save. */
        public long expectedCopyBytes() {
            return corpusSize * expectedCopyBytesPerVector();
        }

        /** (copying p50 - adopting p50) / production p50; 0 when unmeasured. */
        public double timeShare() {
            return productionFindAll.p50Nanos() == 0 ? 0.0
                    : (double) (copyingReplica.p50Nanos() - adoptingReplica.p50Nanos()) / productionFindAll.p50Nanos();
        }

        /** (copying p95 - adopting p95) / production p95; 0 when unmeasured. */
        public double timeShareP95() {
            return productionFindAll.p95Nanos() == 0 ? 0.0
                    : (double) (copyingReplica.p95Nanos() - adoptingReplica.p95Nanos()) / productionFindAll.p95Nanos();
        }

        public boolean allocationMeasured() {
            return productionBytes >= 0 && copyingBytes >= 0 && adoptingBytes >= 0;
        }

        /** (copying bytes - adopting bytes) / production bytes; 0 when unmeasured. */
        public double allocationShare() {
            return !allocationMeasured() || productionBytes == 0 ? 0.0
                    : (double) (copyingBytes - adoptingBytes) / productionBytes;
        }
    }

    public VectorCopyShareReport {
        Objects.requireNonNull(seedDatasetName, "seedDatasetName");
        scalePoints = List.copyOf(Objects.requireNonNull(scalePoints, "scalePoints"));
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decisionRationale, "decisionRationale");
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Monada Vector Copy Share\n");
        sb.append("========================\n");
        sb.append("Seed Dataset: ").append(seedDatasetName).append(" (FileFrequencyStore corpus)\n");
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Frequency Store: FIXED_RAW, ").append(frequencyDimensions).append(" dimensions\n");
        sb.append("Speech Store: length-prefixed, ").append(speechDimensions)
                .append(" dimensions (Gaussian floats from the generator seed)\n");
        sb.append("Scale Points (N): ").append(scalePoints).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Allocation Accounting: ").append(allocationMeasured
                ? "ThreadMXBean.getCurrentThreadAllocatedBytes (per call, median)"
                : "not measured (per-thread allocation accounting unsupported or disabled)").append('\n');
        sb.append("Invocation: ./gradlew :monada-evaluation:runVectorCopyShare -q\n");
        sb.append("Timing Policy: Diagnostic wall-clock only; never asserted in CI. Both replicas are checked against\n");
        sb.append("  the real store's findAll() (same ids, same order, bit-identical floats) at every scale point before\n");
        sb.append("  anything is timed.\n");
        sb.append("Replicas: the copying replica materializes like production (new FrequencyVector(values) copies the\n");
        sb.append("  decoded array). The adopting replica wraps the same decoded array in a same-shape holder without\n");
        sb.append("  the copy. Copy share = (copying - adopting) / production.\n");
        sb.append("Cache Assumption: Warm OS page cache; single machine; results are exploratory.\n\n");

        sb.append("1. findAll() Latency p50 / p95 (microseconds per call)\n");
        sb.append("------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-9s | %-21s | %-21s | %-21s | %-15s%n",
                "Store", "Scale (N)", "Production findAll", "Copying replica", "Adopting replica", "Copy share p50/p95"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9s | %-9d | %-21s | %-21s | %-21s | %s / %s%n",
                    r.store(), r.corpusSize(), fmt(r.productionFindAll()), fmt(r.copyingReplica()),
                    fmt(r.adoptingReplica()), pct(r.timeShare()), pct(r.timeShareP95())));
        }
        sb.append('\n');

        sb.append("2. Allocated Bytes per findAll() Call (median)\n");
        sb.append("----------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-9s | %-14s | %-14s | %-14s | %-14s | %-14s | %-11s%n",
                "Store", "Scale (N)", "Production", "Copying", "Adopting", "Copy delta", "Expected delta",
                "Copy share"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9s | %-9d | %-14s | %-14s | %-14s | %-14s | %-14d | %-11s%n",
                    r.store(), r.corpusSize(), bytes(r.productionBytes()), bytes(r.copyingBytes()),
                    bytes(r.adoptingBytes()),
                    r.allocationMeasured() ? Long.toString(r.copyingBytes() - r.adoptingBytes()) : "n/a",
                    r.expectedCopyBytes(), r.allocationMeasured() ? pct(r.allocationShare()) : "n/a"));
        }
        sb.append("Expected delta is N x one float[dims] (16-byte header + 4 x dims, 8-byte aligned), the array that a\n");
        sb.append("no-copy construction path would stop allocating.\n\n");

        sb.append("3. Decision\n");
        sb.append("-----------\n");
        sb.append(String.format(Locale.ROOT,
                "Rule: at N >= %d, remove the copy if, in at least one store, it is >= %.0f%% of production findAll() p50\n",
                MIN_DECISION_SCALE, MEANINGFUL_TIME_SHARE * 100));
        sb.append(String.format(Locale.ROOT, "  or >= %.0f%% of the bytes production findAll() allocates.%n",
                MEANINGFUL_ALLOCATION_SHARE * 100));
        sb.append("Decision: ").append(decision).append('\n');
        sb.append("Rationale: ").append(decisionRationale).append('\n');
        sb.append("Policy: Evaluation only; reported vectors, ordering, public APIs, defaults and persisted formats\n");
        sb.append("  are unchanged by this harness.\n");
        return sb.toString();
    }

    private static String fmt(PhaseLatency l) {
        return String.format(Locale.ROOT, "%.1f / %.1f", l.p50Nanos() / 1000.0, l.p95Nanos() / 1000.0);
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100.0);
    }

    private static String bytes(long value) {
        return value < 0 ? "n/a" : Long.toString(value);
    }
}
