package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report of the evaluation-only speech feature load breakdown (Issue #136): {@code FileSpeechFeatureStore.findAll()}
 * split into index parse, seek, header read, value read, per-vector I/O and materialization, with a windowed
 * sequential-read candidate and a decision on replacing the per-vector reads.
 */
public record SpeechFeatureLoadBreakdownReport(
        long seed,
        List<Integer> dimensions,
        List<Integer> scalePoints,
        int warmupCount,
        int repetitionCount,
        int windowBytes,
        List<PointResult> results,
        Decision decision,
        String decisionRationale
) {

    /** Per-vector I/O (seek + header + values, no decode) share of the replica p50 at or above which it dominates. */
    public static final double PER_VECTOR_IO_DOMINANT_SHARE = 0.40;

    /** The windowed candidate must need at most this fraction of the replica time, at both p50 and p95. */
    public static final double WINDOWED_CANDIDATE_MAX_RATIO = 0.75;

    /** Every dimension must reach this scale for the decision to be informative. */
    public static final int MIN_DECISION_SCALE = 10_000;

    public enum Decision {
        /** At N >= 10,000, for every dimension, per-vector I/O dominates and the windowed candidate is materially faster. */
        PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED,
        /** At least one dimension misses a threshold. */
        PER_VECTOR_IO_NOT_DOMINANT,
        /** No timing samples, or a dimension without an N >= 10,000 point. */
        INCONCLUSIVE
    }

    /** Machine-independent counts from one untimed pass of every operation at one point. */
    public record StructuralCounts(
            long indexLinesParsed,
            long seeks,
            long headerReadCalls,
            long valueReadCalls,
            long bytesRead,
            long windowReadCalls,
            long windowBytesRead,
            long perVectorFallbacks,
            long vectorsMaterialized) {
    }

    /** One dimension at one scale point; latencies are per pass call. */
    public record PointResult(
            int corpusSize,
            int dimensions,
            int sampleCount,
            PhaseLatency indexParse,
            PhaseLatency seek,
            PhaseLatency headerRead,
            PhaseLatency valueRead,
            PhaseLatency perVectorIo,
            PhaseLatency materialize,
            PhaseLatency replicaFindAll,
            PhaseLatency windowedCandidate,
            PhaseLatency productionFindAll,
            StructuralCounts counts) {

        public PointResult {
            Objects.requireNonNull(indexParse, "indexParse");
            Objects.requireNonNull(seek, "seek");
            Objects.requireNonNull(headerRead, "headerRead");
            Objects.requireNonNull(valueRead, "valueRead");
            Objects.requireNonNull(perVectorIo, "perVectorIo");
            Objects.requireNonNull(materialize, "materialize");
            Objects.requireNonNull(replicaFindAll, "replicaFindAll");
            Objects.requireNonNull(windowedCandidate, "windowedCandidate");
            Objects.requireNonNull(productionFindAll, "productionFindAll");
            Objects.requireNonNull(counts, "counts");
        }

        /** {@code phase} p50 / replica p50; 0 when the replica was not measured. */
        public double share(PhaseLatency phase) {
            return replicaFindAll.p50Nanos() == 0 ? 0.0 : (double) phase.p50Nanos() / replicaFindAll.p50Nanos();
        }

        /** Windowed candidate p95 / replica p95; 0 when the replica was not measured. */
        public double windowedRatioP95() {
            return replicaFindAll.p95Nanos() == 0 ? 0.0
                    : (double) windowedCandidate.p95Nanos() / replicaFindAll.p95Nanos();
        }

        /** Replica p50 / {@code phase} p50; 0 when {@code phase} was not measured. */
        public double speedupVersusReplica(PhaseLatency phase) {
            return phase.p50Nanos() == 0 ? 0.0 : (double) replicaFindAll.p50Nanos() / phase.p50Nanos();
        }
    }

    public SpeechFeatureLoadBreakdownReport {
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
        scalePoints = List.copyOf(Objects.requireNonNull(scalePoints, "scalePoints"));
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decisionRationale, "decisionRationale");
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Monada Speech Feature Load Breakdown\n");
        sb.append("====================================\n");
        sb.append("Store: FileSpeechFeatureStore (length-prefixed frames, last-wins text index)\n");
        sb.append("Corpus: seeded Gaussian vectors, ids speech-sample-NNNNNN, append order\n");
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Dimensions: ").append(dimensions).append('\n');
        sb.append("Scale Points (N): ").append(scalePoints).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Candidate Window: ").append(windowBytes).append(" bytes (one reused heap array per findAll() call)\n");
        sb.append("Invocation: ./gradlew :monada-evaluation:runSpeechFeatureLoadBreakdown -q\n");
        sb.append("Timing Policy: Diagnostic wall-clock only; never asserted in CI. The replica, the windowed candidate\n");
        sb.append("  and materialization are checked against the real store's findAll() (same ids, same order,\n");
        sb.append("  bit-identical floats) at every point before anything is timed.\n");
        sb.append("Passes: header read and value read each include their own seek; per-vector I/O is seek + readInt +\n");
        sb.append("  readFully without decode; materialize decodes from segment bytes already in memory. The passes are\n");
        sb.append("  not an exact decomposition of a combined load. Shares are relative to the replica (the pre-#136\n");
        sb.append("  per-vector path), so they stay comparable after production changes. The windowed candidate is a\n");
        sb.append("  frozen snapshot of the stage-1 proposal; the production column measures the shipped read path.\n");
        sb.append("Cache Assumption: Warm OS page cache; single machine; results are exploratory.\n\n");

        sb.append("1. Pass Latency p50 / p95 (microseconds per call)\n");
        sb.append("-------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-4s | %-6s | %-15s | %-15s | %-15s | %-15s | %-15s | %-15s%n",
                "Dims", "N", "Index parse", "Seek", "Header read", "Value read", "Per-vector I/O", "Materialize"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-4d | %-6d | %-15s | %-15s | %-15s | %-15s | %-15s | %-15s%n",
                    r.dimensions(), r.corpusSize(), fmt(r.indexParse()), fmt(r.seek()), fmt(r.headerRead()),
                    fmt(r.valueRead()), fmt(r.perVectorIo()), fmt(r.materialize())));
        }
        sb.append('\n');

        sb.append("2. Combined Loads p50 / p95 (microseconds per call)\n");
        sb.append("---------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-4s | %-6s | %-19s | %-19s | %-19s | %-14s | %-14s%n",
                "Dims", "N", "Replica (pre-#136)", "Windowed candidate", "Production", "Candidate p50", "Candidate p95"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-4d | %-6d | %-19s | %-19s | %-19s | %-14s | %-14s%n",
                    r.dimensions(), r.corpusSize(), fmt(r.replicaFindAll()), fmt(r.windowedCandidate()),
                    fmt(r.productionFindAll()), pct(r.share(r.windowedCandidate())), pct(r.windowedRatioP95())));
        }
        sb.append("Candidate columns are candidate / replica.\n\n");

        sb.append("3. Shares of the Replica p50\n");
        sb.append("----------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-4s | %-6s | %-11s | %-7s | %-11s | %-10s | %-14s | %-11s%n",
                "Dims", "N", "Index parse", "Seek", "Header read", "Value read", "Per-vector I/O", "Materialize"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-4d | %-6d | %-11s | %-7s | %-11s | %-10s | %-14s | %-11s%n",
                    r.dimensions(), r.corpusSize(), pct(r.share(r.indexParse())), pct(r.share(r.seek())),
                    pct(r.share(r.headerRead())), pct(r.share(r.valueRead())), pct(r.share(r.perVectorIo())),
                    pct(r.share(r.materialize()))));
        }
        sb.append('\n');

        sb.append("4. Structural Counts (one untimed pass of every operation)\n");
        sb.append("----------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-4s | %-6s | %-11s | %-6s | %-12s | %-11s | %-10s | %-12s | %-12s | %-9s | %-12s%n",
                "Dims", "N", "Index lines", "Seeks", "Header reads", "Value reads", "Bytes read", "Window reads",
                "Window bytes", "Fallbacks", "Materialized"));
        for (var r : results) {
            var c = r.counts();
            sb.append(String.format(Locale.ROOT, "%-4d | %-6d | %-11d | %-6d | %-12d | %-11d | %-10d | %-12d | %-12d | %-9d | %-12d%n",
                    r.dimensions(), r.corpusSize(), c.indexLinesParsed(), c.seeks(), c.headerReadCalls(),
                    c.valueReadCalls(), c.bytesRead(), c.windowReadCalls(), c.windowBytesRead(),
                    c.perVectorFallbacks(), c.vectorsMaterialized()));
        }
        sb.append("Seeks, header reads, value reads and bytes read sum the seek, header, value and per-vector I/O\n");
        sb.append("passes; window reads/bytes and fallbacks come from the windowed candidate. These are Java-level\n");
        sb.append("API calls, not an exact syscall count.\n\n");

        sb.append("5. Decision\n");
        sb.append("-----------\n");
        sb.append(String.format(Locale.ROOT,
                "Rule: at N >= %d, for every dimension, the windowed read is justified when per-vector I/O is >= %.0f%%%n",
                MIN_DECISION_SCALE, PER_VECTOR_IO_DOMINANT_SHARE * 100));
        sb.append(String.format(Locale.ROOT,
                "  of the replica p50 and the windowed candidate needs <= %.0f%% of the replica at both p50 and p95.%n",
                WINDOWED_CANDIDATE_MAX_RATIO * 100));
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
}
