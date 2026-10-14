package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report of the evaluation-only vector-load breakdown: where the time of one
 * {@code FileFrequencyStore.findAll()} call goes between index parse, seek, per-float unbuffered
 * read, bulk frame read, float decode and materialization, plus machine-independent counts and a
 * reasoned decision on a bulk-read optimization of the production read path.
 */
public record VectorLoadBreakdownReport(
        String seedDatasetName,
        long seed,
        int vectorDimensions,
        String vectorFraming,
        List<Integer> scalePoints,
        int warmupCount,
        int repetitionCount,
        List<PointResult> results,
        Decision decision,
        String decisionRationale
) {

    /** Share of the per-float replica load above which per-float reads are considered dominant. */
    public static final double PER_FLOAT_DOMINANT_SHARE = 0.50;

    /** Bulk candidate p50 must be at most this fraction of the replica p50 (>= 2x projected). */
    public static final double BULK_CANDIDATE_MAX_RATIO = 0.50;

    /** Smallest largest-scale point at which the decision is considered informative. */
    public static final int MIN_DECISION_SCALE = 5_000;

    public enum Decision {
        /** Per-float reads dominate the load and a bulk frame read projects at least a 2x reduction. */
        PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED,
        /** Per-float reads are not dominant, or a bulk read does not project a material reduction. */
        PER_FLOAT_READ_NOT_DOMINANT,
        /** Not enough evidence (no timing samples or no large-scale point). */
        INCONCLUSIVE
    }

    /** Machine-independent counts for one vector load (one {@code findAll()} call) at one scale point. */
    public record StructuralCounts(
            long indexLinesParsed,
            long seeks,
            long perFloatReadCalls,
            long perFloatBytesRead,
            long bulkReadCalls,
            long bulkBytesRead,
            long floatsDecoded,
            long arraysAllocated,
            long objectsAllocated) {}

    public record PointResult(
            int corpusSize,
            int sampleCount,
            PhaseLatency indexParse,
            PhaseLatency seek,
            PhaseLatency perFloatRead,
            PhaseLatency bulkRead,
            PhaseLatency decode,
            PhaseLatency materialize,
            PhaseLatency replicaFindAll,
            PhaseLatency bulkCandidateFindAll,
            PhaseLatency productionFindAll,
            StructuralCounts counts) {

        /** Fraction of the median per-float replica load spent in the given median, 0 when unmeasured. */
        public double share(PhaseLatency phase) {
            return replicaFindAll.p50Nanos() == 0 ? 0.0 : (double) phase.p50Nanos() / replicaFindAll.p50Nanos();
        }

        /** Median replica load divided by the given median, 0 when unmeasured. */
        public double speedupVersusReplica(PhaseLatency other) {
            return other.p50Nanos() == 0 ? 0.0 : (double) replicaFindAll.p50Nanos() / other.p50Nanos();
        }
    }

    public VectorLoadBreakdownReport {
        Objects.requireNonNull(seedDatasetName, "seedDatasetName");
        Objects.requireNonNull(vectorFraming, "vectorFraming");
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be > 0");
        }
        scalePoints = List.copyOf(Objects.requireNonNull(scalePoints, "scalePoints"));
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decisionRationale, "decisionRationale");
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Monada Vector-Load Breakdown\n");
        sb.append("============================\n");
        sb.append("Seed Dataset: ").append(seedDatasetName).append('\n');
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Vector Dimensions: ").append(vectorDimensions).append('\n');
        sb.append("Vector Framing: ").append(vectorFraming).append(" (FLOAT32, big-endian)\n");
        sb.append("Scale Points (N): ").append(scalePoints).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Invocation: ./gradlew :monada-evaluation:runVectorLoadBreakdown -q\n");
        sb.append("Timing Policy: Diagnostic wall-clock only; never asserted in CI. Every split pass, the replica and\n");
        sb.append("  the bulk candidate are checked against FileFrequencyStore.findAll() (same ids, same order,\n");
        sb.append("  bit-identical floats) at every scale point before anything is timed.\n");
        sb.append("Cache Assumption: Warm OS page cache. The segment is written during setup and re-read on every\n");
        sb.append("  repetition; caches are never dropped, so disk latency is excluded and per-call I/O and JVM\n");
        sb.append("  overhead dominate. Single machine; results are exploratory.\n\n");

        sb.append("1. Structural Counts (machine-independent, per vector load)\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-11s | %-7s | %-16s | %-12s | %-11s | %-14s | %-10s | %-9s%n",
                "Scale (N)", "Index lines", "Seeks", "Per-float reads", "Bulk reads", "Bytes read",
                "Floats decoded", "Arrays", "Objects"));
        for (var r : results) {
            var c = r.counts();
            sb.append(String.format(Locale.ROOT, "%-9d | %-11d | %-7d | %-16d | %-12d | %-11d | %-14d | %-10d | %-9d%n",
                    r.corpusSize(), c.indexLinesParsed(), c.seeks(), c.perFloatReadCalls(), c.bulkReadCalls(),
                    c.bulkBytesRead(), c.floatsDecoded(), c.arraysAllocated(), c.objectsAllocated()));
        }
        sb.append("Per-float reads count Java-level RandomAccessFile.readInt/readFloat calls and bulk reads count\n");
        sb.append("readFully calls (one per frame); both read the same bytes. These are API calls, not an exact syscall\n");
        sb.append("count: each readFloat issues at least one underlying read and readFully may issue several.\n");
        sb.append("Arrays/objects count the per-vector float[] plus FrequencyVector's defensive copy, and\n");
        sb.append("FrequencyVector plus StoredVector.\n\n");

        sb.append("2. Sub-Phase Latency p50 / p95 (microseconds per vector load)\n");
        sb.append("-------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-21s | %-21s | %-21s | %-21s | %-21s | %-21s%n",
                "Scale (N)", "Index parse", "Seek", "Per-float read", "Bulk read", "Float decode", "Materialize"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-21s | %-21s | %-21s | %-21s | %-21s | %-21s%n",
                    r.corpusSize(), fmt(r.indexParse()), fmt(r.seek()), fmt(r.perFloatRead()), fmt(r.bulkRead()),
                    fmt(r.decode()), fmt(r.materialize())));
        }
        sb.append("Split passes: per-float read and bulk read each include their own seeks; float decode runs on\n");
        sb.append("bytes already in memory; materialize copies already-decoded floats. Passes are alternatives, not\n");
        sb.append("an exact decomposition, so they do not sum to the replica load.\n\n");

        sb.append("3. Sub-Phase Share of Median Per-Float Replica Load\n");
        sb.append("---------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-11s | %-7s | %-14s | %-9s | %-12s | %-11s%n",
                "Scale (N)", "Index parse", "Seek", "Per-float read", "Bulk read", "Float decode", "Materialize"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-11s | %-7s | %-14s | %-9s | %-12s | %-11s%n",
                    r.corpusSize(), pct(r.share(r.indexParse())), pct(r.share(r.seek())),
                    pct(r.share(r.perFloatRead())), pct(r.share(r.bulkRead())), pct(r.share(r.decode())),
                    pct(r.share(r.materialize()))));
        }
        sb.append('\n');

        sb.append("4. Whole Load: Replica vs Bulk Candidate vs Production (microseconds, p50 / p95)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-21s | %-21s | %-21s | %-17s | %-17s%n",
                "Scale (N)", "Per-float replica", "Bulk candidate", "Production findAll", "Replica/Bulk", "Replica/Prod"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-21s | %-21s | %-21s | %-17s | %-17s%n",
                    r.corpusSize(), fmt(r.replicaFindAll()), fmt(r.bulkCandidateFindAll()), fmt(r.productionFindAll()),
                    ratio(r.speedupVersusReplica(r.bulkCandidateFindAll())),
                    ratio(r.speedupVersusReplica(r.productionFindAll()))));
        }
        sb.append("The replica keeps the per-float read path (FileFrequencyStore before Issue #130). Replica/Prod near\n");
        sb.append("1.00x means production still reads per float; above 1.00x it measures the production speedup.\n");
        sb.append("Passes rotate their start position on every repetition to avoid a fixed cache-warmth advantage.\n\n");

        sb.append("5. Decision\n");
        sb.append("-----------\n");
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

    private static String ratio(double value) {
        return String.format(Locale.ROOT, "%.2fx", value);
    }
}
