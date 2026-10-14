package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report of the evaluation-only exact bounded top-K experiment (Issue #120): oracle parity of the
 * experimental {@code ExactBoundedTopKSelector} against the production full sort, retained-candidate
 * and allocation counts, isolated selection-phase latency, end-to-end search latency, and a decision
 * that never enables the candidate automatically.
 */
public record BoundedTopKExperimentReport(
        String seedDatasetName,
        long seed,
        int vectorDimensions,
        List<Integer> scalePoints,
        List<Integer> topKs,
        int warmupCount,
        int repetitionCount,
        boolean allocationMeasured,
        List<PointResult> results,
        Decision decision,
        String decisionRationale
) {

    /** Candidate selection p50 must be at most this fraction of the oracle sort+truncate p50. */
    public static final double SELECTION_SPEEDUP_RATIO = 0.80;

    /**
     * Noise tolerance for the end-to-end criterion: the candidate's search p50 may exceed production's by
     * at most this fraction and still count as "not slower", so sub-microsecond jitter cannot flip the decision.
     */
    public static final double END_TO_END_TOLERANCE = 0.02;

    /** Smallest largest-scale point at which a retain decision is considered informative. */
    public static final int MIN_DECISION_SCALE = ScanPhaseBreakdownReport.MIN_DECISION_SCALE;

    /** Marker for allocation figures when per-thread allocation accounting is unavailable. */
    public static final long NOT_MEASURED = -1L;

    public enum Decision {
        /** Exact parity holds and the candidate measurably beats the oracle at the largest scale for every K. */
        RETAIN_EXPERIMENT,
        /** Exact parity holds but the candidate shows no material benefit (or the evidence is insufficient). */
        NO_BENEFIT,
        /** At least one parity mismatch: the candidate is incorrect and must not be adopted. */
        NEEDS_FIX
    }

    /** Oracle-parity outcome, summed over all non-blank queries of one arm. */
    public record ParityCounts(
            long queriesChecked,
            long selectorChecks,
            long selectorMismatches,
            long searchChecks,
            long searchMismatches) {

        public long mismatches() {
            return selectorMismatches + searchMismatches;
        }
    }

    /**
     * Retained-candidate counts summed over queries, plus per-query maxima. The oracle retains every
     * deduplicated candidate, so its retained count is {@link #candidates()}; the bounded selector holds at
     * most K at once ({@link #candidatePeakRetained()}).
     */
    public record RetentionCounts(
            long candidates,
            long candidatePeakRetained,
            int maxCandidatesPerQuery,
            int maxCandidatePeakRetainedPerQuery,
            long resultsReturned) {}

    public record PointResult(
            int corpusSize,
            int topK,
            int queryCount,
            int sampleCount,
            ParityCounts parity,
            RetentionCounts retention,
            PhaseLatency oracleSelection,
            PhaseLatency candidateSelection,
            PhaseLatency productionSearch,
            PhaseLatency candidateSearch,
            long oracleAllocatedBytesP50,
            long candidateAllocatedBytesP50) {

        /** Candidate / oracle selection p50; 0 when unmeasured. */
        public double selectionRatio() {
            return oracleSelection.p50Nanos() == 0 ? 0.0
                    : (double) candidateSelection.p50Nanos() / oracleSelection.p50Nanos();
        }

        /** Candidate / production end-to-end search p50; 0 when unmeasured. */
        public double searchRatio() {
            return productionSearch.p50Nanos() == 0 ? 0.0
                    : (double) candidateSearch.p50Nanos() / productionSearch.p50Nanos();
        }
    }

    public BoundedTopKExperimentReport {
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
        sb.append("Monada Exact Bounded Top-K Experiment\n");
        sb.append("=====================================\n");
        sb.append("Seed Dataset: ").append(seedDatasetName).append('\n');
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Vector Dimensions: ").append(vectorDimensions).append('\n');
        sb.append("Scale Points (N): ").append(scalePoints).append('\n');
        sb.append("Top-K Sweep: ").append(topKs).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Invocation: ./gradlew :monada-evaluation:runBoundedTopKExperiment -q\n");
        sb.append("Oracle: LinearScanResonanceIndex full sort (score desc, atom id asc) + truncate.\n");
        sb.append("Candidate: ExactBoundedTopKSelector (bounded min-heap over deduplicated candidates), reached via\n");
        sb.append("  ExperimentalBoundedTopKLinearScanIndex; experimental and never selected by default.\n");
        sb.append("Timing Policy: Diagnostic wall-clock only; never asserted in CI. Parity is checked before timing.\n\n");

        sb.append("1. Oracle Parity (exact atom IDs, bit-identical scores, same order)\n");
        sb.append("-------------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-8s | %-15s | %-19s | %-13s | %-17s%n",
                "Scale (N)", "Top-K", "Queries", "Selector checks", "Selector mismatches",
                "Search checks", "Search mismatches"));
        for (var r : results) {
            var p = r.parity();
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-8d | %-15d | %-19d | %-13d | %-17d%n",
                    r.corpusSize(), r.topK(), p.queriesChecked(), p.selectorChecks(), p.selectorMismatches(),
                    p.searchChecks(), p.searchMismatches()));
        }
        sb.append("Selector checks: candidates in production hash order and a seeded shuffle vs the full-sort oracle.\n");
        sb.append("Search checks: ExperimentalBoundedTopKLinearScanIndex vs LinearScanResonanceIndex at the evaluation\n");
        sb.append("  threshold (-Infinity) and at two exact-boundary thresholds: the score at the top-K cut and the\n");
        sb.append("  median candidate score.\n\n");

        sb.append("2. Retained Candidates and Allocation\n");
        sb.append("-------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-11s | %-15s | %-18s | %-17s | %-17s | %-17s%n",
                "Scale (N)", "Top-K", "Candidates", "Oracle retained", "Candidate retained",
                "Max M per query", "Oracle alloc B p50", "Cand. alloc B p50"));
        for (var r : results) {
            var c = r.retention();
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-11d | %-15d | %-18d | %-17d | %-18s | %-17s%n",
                    r.corpusSize(), r.topK(), c.candidates(), c.candidates(), c.candidatePeakRetained(),
                    c.maxCandidatesPerQuery(), bytes(r.oracleAllocatedBytesP50()),
                    bytes(r.candidateAllocatedBytesP50())));
        }
        sb.append("Retained counts are summed over queries: the oracle materializes and sorts every deduplicated\n");
        sb.append("candidate (M per query); the candidate holds at most K at once. Allocation is per selection call\n");
        sb.append(allocationMeasured
                ? "  (com.sun.management.ThreadMXBean thread-allocated bytes; includes the result list).\n\n"
                : "  (n/a: per-thread allocation accounting is unavailable or disabled on this JVM).\n\n");

        sb.append("3. Selection Phase Latency p50 / p95 (microseconds per query, same candidates)\n");
        sb.append("------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-21s | %-21s | %-17s%n",
                "Scale (N)", "Top-K", "Oracle sort+truncate", "Candidate bounded", "Candidate/oracle"));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-21s | %-21s | %-17s%n",
                    r.corpusSize(), r.topK(), fmt(r.oracleSelection()), fmt(r.candidateSelection()),
                    ratio(r.selectionRatio())));
        }
        sb.append('\n');

        sb.append("4. End-to-End Search Latency p50 / p95 (microseconds per query)\n");
        sb.append("---------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "%-10s | %-5s | %-21s | %-21s | %-17s%n",
                "Scale (N)", "Top-K", "Production search", "Candidate search", "Candidate/prod."));
        for (var r : results) {
            sb.append(String.format(Locale.ROOT, "%-10d | %-5d | %-21s | %-21s | %-17s%n",
                    r.corpusSize(), r.topK(), fmt(r.productionSearch()), fmt(r.candidateSearch()),
                    ratio(r.searchRatio())));
        }
        sb.append("Each pair alternates which arm runs first on each repetition. Selection and end-to-end search are\n");
        sb.append("measured in separate loops (each with its own warm-up), so selection garbage never lands between\n");
        sb.append("timed searches.\n");
        sb.append("End-to-end search includes atom load, vector load and scoring, which the selector does not change;\n");
        sb.append("a smaller K is not a guarantee of lower end-to-end latency.\n\n");

        sb.append("5. Decision\n");
        sb.append("-----------\n");
        sb.append("Decision: ").append(decision).append('\n');
        sb.append("Rationale: ").append(decisionRationale).append('\n');
        sb.append("Policy: Evaluation only; LinearScanResonanceIndex, MonadaMemory/ExecutionMemory recall, public\n");
        sb.append("  defaults and persisted formats are unchanged. No automatic production rollout.\n");
        return sb.toString();
    }

    private static String fmt(PhaseLatency l) {
        return String.format(Locale.ROOT, "%.1f / %.1f", l.p50Nanos() / 1000.0, l.p95Nanos() / 1000.0);
    }

    private static String ratio(double value) {
        return value == 0.0 ? "n/a" : String.format(Locale.ROOT, "%.2fx", value);
    }

    private static String bytes(long value) {
        return value == NOT_MEASURED ? "n/a" : Long.toString(value);
    }
}
