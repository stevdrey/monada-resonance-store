package com.monada.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report of the evaluation-only phase breakdown of linear-scan recall: where time goes between
 * atom load, vector load, cosine scoring, dedup, full sort and truncation, plus machine-independent
 * structural counts and a reasoned decision on an exact bounded top-K prototype.
 */
public record ScanPhaseBreakdownReport(
        String seedDatasetName,
        long seed,
        int vectorDimensions,
        List<Integer> scalePoints,
        List<Integer> topKs,
        int warmupCount,
        int repetitionCount,
        List<PointResult> results,
        Decision decision,
        String decisionRationale
) {

    /** Share of the instrumented scan above which the full sort is considered material. */
    public static final double SORT_MATERIAL_SHARE = 0.20;

    /** Smallest largest-scale point at which the decision is considered informative. */
    public static final int MIN_DECISION_SCALE = 5_000;

    public enum Decision {
        /** Full sort is a material share of query-time work at the largest scale for every K. */
        BOUNDED_TOP_K_PROTOTYPE_JUSTIFIED,
        /** Full sort is not material; a bounded top-K prototype could only recover a small ceiling. */
        BOUNDED_TOP_K_PROTOTYPE_NOT_JUSTIFIED,
        /** Not enough evidence (no timing samples or no large-scale point). */
        INCONCLUSIVE
    }

    /** Median and 95th percentile of one phase, in nanoseconds. */
    public record PhaseLatency(long p50Nanos, long p95Nanos) {
        public PhaseLatency {
            if (p50Nanos < 0 || p95Nanos < 0) {
                throw new IllegalArgumentException("latency must be >= 0");
            }
        }
    }

    /** Machine-independent structural counts, summed over all non-blank queries of one arm. */
    public record StructuralCounts(
            long atomsLoaded,
            long vectorsVisited,
            long orphanVectors,
            long cosineEvaluations,
            long candidates,
            long resultsReturned,
            long sortCostModel,
            long topKCostModel) {}

    public record PointResult(
            int corpusSize,
            int topK,
            int queryCount,
            int sampleCount,
            PhaseLatency production,
            PhaseLatency atomLoad,
            PhaseLatency vectorLoad,
            PhaseLatency scoring,
            PhaseLatency dedup,
            PhaseLatency sort,
            PhaseLatency truncate,
            PhaseLatency instrumentedTotal,
            PhaseLatency sumOfPhases,
            StructuralCounts counts) {

        /** Fraction of the median sum-of-phases spent in the given phase median, 0 when unmeasured. */
        public double share(PhaseLatency phase) {
            return sumOfPhases.p50Nanos() == 0 ? 0.0 : (double) phase.p50Nanos() / sumOfPhases.p50Nanos();
        }
    }

    public ScanPhaseBreakdownReport {
        Objects.requireNonNull(seedDatasetName, "seedDatasetName");
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be > 0");
        }
        scalePoints = List.copyOf(Objects.requireNonNull(scalePoints, "scalePoints"));
        topKs = List.copyOf(Objects.requireNonNull(topKs, "topKs"));
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decisionRationale, "decisionRationale");
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Monada Linear-Scan Phase Breakdown\n");
        sb.append("==================================\n");
        sb.append("Seed Dataset: ").append(seedDatasetName).append('\n');
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Vector Dimensions: ").append(vectorDimensions).append('\n');
        sb.append("Scale Points (N): ").append(scalePoints).append('\n');
        sb.append("Top-K Sweep: ").append(topKs).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Invocation: ./gradlew :monada-evaluation:runScanPhaseBreakdown -q\n");
        sb.append("Timing Policy: Diagnostic wall-clock only; never asserted in CI. Structural counts and\n");
        sb.append("  top-K/candidate parity with LinearScanResonanceIndex are verified for every measured query.\n\n");

        sb.append("1. Structural Counts (machine-independent, summed over queries)\n");
        sb.append("---------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-8s | %-12s | %-10s | %-12s | %-11s | %-9s%n",
                "Scale (N)", "Top-K", "Queries", "Vec Visited", "Orphans", "Cosine Evals",
                "Candidates", "Returned"));
        for (var r : results) {
            var c = r.counts();
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-8d | %-12d | %-10d | %-12d | %-11d | %-9d%n",
                    r.corpusSize(), r.topK(), r.queryCount(), c.vectorsVisited(), c.orphanVectors(),
                    c.cosineEvaluations(), c.candidates(), c.resultsReturned()));
        }
        sb.append('\n');

        sb.append("1b. Illustrative Cost Model (NOT measured comparisons)\n");
        sb.append("-------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-24s | %-24s%n",
                "Scale (N)", "Top-K", "Sort model M*ceil(log2 M)", "TopK model M*ceil(log2 K+1)"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-25d | %-27d%n",
                    r.corpusSize(), r.topK(), r.counts().sortCostModel(), r.counts().topKCostModel()));
        }
        sb.append("These are upper-bound heuristics summed over queries (M = deduped candidates), not counted\n");
        sb.append("comparisons: real sort and heap costs depend on input order. They are not evidence for or\n");
        sb.append("against a bounded top-K prototype; the measured phase latencies below are.\n\n");

        sb.append("2. Phase Latency p50 / p95 (microseconds per query)\n");
        sb.append("---------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-5s | %-17s | %-17s | %-17s | %-17s | %-17s | %-17s%n",
                "Scale (N)", "Top-K", "Atom load", "Vector load", "Cosine scoring", "Threshold+dedup",
                "Full sort", "Truncate"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-5d | %-17s | %-17s | %-17s | %-17s | %-17s | %-17s%n",
                    r.corpusSize(), r.topK(), fmt(r.atomLoad()), fmt(r.vectorLoad()), fmt(r.scoring()),
                    fmt(r.dedup()), fmt(r.sort()), fmt(r.truncate())));
        }
        sb.append('\n');

        sb.append("3. Phase Share of Median Sum-of-Phases\n");
        sb.append("---------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-5s | %-10s | %-11s | %-8s | %-7s | %-9s | %-8s%n",
                "Scale (N)", "Top-K", "Atom load", "Vector load", "Scoring", "Dedup", "Full sort", "Truncate"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-5d | %-10s | %-11s | %-8s | %-7s | %-9s | %-8s%n",
                    r.corpusSize(), r.topK(), pct(r.share(r.atomLoad())), pct(r.share(r.vectorLoad())),
                    pct(r.share(r.scoring())), pct(r.share(r.dedup())), pct(r.share(r.sort())),
                    pct(r.share(r.truncate()))));
        }
        sb.append('\n');

        sb.append("4. Measurement Overhead (microseconds per query, p50 / p95)\n");
        sb.append("-----------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-9s | %-5s | %-17s | %-17s | %-17s%n",
                "Scale (N)", "Top-K", "Production search", "Instrumented", "Sum of phases"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-9d | %-5d | %-17s | %-17s | %-17s%n",
                    r.corpusSize(), r.topK(), fmt(r.production()), fmt(r.instrumentedTotal()), fmt(r.sumOfPhases())));
        }
        sb.append("Production and instrumented scans alternate which runs first on each repetition to avoid a fixed\n");
        sb.append("cache-warmth advantage.\n");
        sb.append("Overhead sources: System.nanoTime() reads between phases, a separate score array (scoring and\n");
        sb.append("dedup are fused in production). The instrumented total can\n");
        sb.append("therefore exceed the production total; phase shares are estimates, not an exact decomposition.\n");
        sb.append("Atom load is cheap after the first call (FileAtomStore caches); vector load re-reads the vector\n");
        sb.append("map and file on every call. Setup (corpus generation, seeding, query encoding) is untimed.\n\n");

        sb.append("5. Decision\n");
        sb.append("-----------\n");
        sb.append("Decision: ").append(decision).append('\n');
        sb.append("Rationale: ").append(decisionRationale).append('\n');
        sb.append("Policy: Evaluation only; LinearScanResonanceIndex, public APIs and defaults are unchanged.\n");
        return sb.toString();
    }

    private static String fmt(PhaseLatency l) {
        return String.format(Locale.ROOT, "%.1f / %.1f", l.p50Nanos() / 1000.0, l.p95Nanos() / 1000.0);
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100.0);
    }
}
