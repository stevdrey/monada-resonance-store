package com.monada.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Report containing multi-scale latency, structural scan metrics, retrieval quality,
 * and top-K comparison for the scaled linear-scan benchmark.
 */
public record LatencyScaleSweepReport(
        String seedDatasetName,
        long seed,
        int vectorDimensions,
        List<Integer> scalePoints,
        List<Integer> topKs,
        int warmupCount,
        int repetitionCount,
        List<LatencyScalePointResult> results,
        ScaleOptimizationDecision decision,
        String decisionRationale
) {
    public LatencyScaleSweepReport {
        Objects.requireNonNull(seedDatasetName, "seedDatasetName");
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be > 0");
        }
        scalePoints = List.copyOf(Objects.requireNonNull(scalePoints, "scalePoints"));
        topKs = List.copyOf(Objects.requireNonNull(topKs, "topKs"));
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be >= 0");
        }
        if (repetitionCount < 0) {
            throw new IllegalArgumentException("repetitionCount must be >= 0");
        }
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decisionRationale, "decisionRationale");
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Monada Scaled Linear-Scan Benchmark and Decision Gate\n");
        sb.append("=====================================================\n");
        sb.append("Seed Dataset: ").append(seedDatasetName).append('\n');
        sb.append("Generator Seed: ").append(seed).append('\n');
        sb.append("Vector Dimensions: ").append(vectorDimensions).append('\n');
        sb.append("Scale Points: ").append(scalePoints).append('\n');
        sb.append("Top-K Sweep: ").append(topKs).append('\n');
        sb.append("Warmup Iterations: ").append(warmupCount).append('\n');
        sb.append("Measured Repetitions: ").append(repetitionCount).append('\n');
        sb.append("Timing Policy: Best-effort wall-clock (CI asserts structural invariants only)\n\n");

        sb.append("1. Structural Scan Summary\n");
        sb.append("--------------------------\n");
        sb.append(String.format(Locale.ROOT,
                "%-10s | %-6s | %-8s | %-14s | %-14s | %-14s | %-14s%n",
                "Scale (N)", "Top-K", "Queries", "Scanned/Query", "Total Scanned", "Scan Fraction", "Total Returned"));
        sb.append("-----------+--------+----------+----------------+----------------+----------------+---------------\n");
        for (var r : results) {
            sb.append(String.format(Locale.ROOT,
                    "%-10d | %-6d | %-8d | %-14.1f | %-14d | %-14.4f | %-14d%n",
                    r.corpusSize(), r.topK(), r.queryCount(),
                    r.averageScannedPerQuery(), r.totalScanned(), r.scanFraction(), r.totalReturned()));
        }
        sb.append('\n');

        sb.append("2. Latency and Throughput (Best-Effort Timing)\n");
        sb.append("----------------------------------------------\n");
        sb.append(String.format(Locale.ROOT,
                "%-10s | %-6s | %-22s | %-18s | %-15s | %-15s | %-15s | %-15s | %-10s%n",
                "Scale (N)", "Top-K", "Standalone Encode (ns)", "Query Latency (ns)", "Min (ns)", "Median (ns)", "P95 (ns)", "Max (ns)", "QPS"));
        sb.append("-----------+--------+------------------------+--------------------+-----------------+-----------------+-----------------+-----------------+-----------\n");
        for (var r : results) {
            var t = r.timing();
            sb.append(String.format(Locale.ROOT,
                    "%-10d | %-6d | %-22d | %-18d | %-15d | %-15d | %-15d | %-15d | %-10.1f%n",
                    r.corpusSize(), r.topK(),
                    t.avgEncodeNanos(), t.avgNanos(),
                    t.minNanos(), t.medianNanos(), t.p95Nanos(), t.maxNanos(), t.queriesPerSecond()));
        }
        sb.append("Note: Standalone encode duration is measured separately as a diagnostic baseline;\n");
        sb.append("query latency is the full end-to-end resonance recall duration.\n\n");

        sb.append("3. Retrieval Quality by Scale Point\n");
        sb.append("-----------------------------------\n");
        sb.append(String.format(Locale.ROOT,
                "%-10s | %-6s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s%n",
                "Scale (N)", "Top-K", "P@1", "P@3", "P@5", "R@1", "R@3", "R@5", "Hit@1", "Hit@3", "Hit@5", "MRR"));
        sb.append("-----------+--------+----------+----------+----------+----------+----------+----------+----------+----------+----------+----------\n");
        for (var r : results) {
            var eval = r.evaluationReport();
            var p1 = r.topK() >= 1 ? String.format(Locale.ROOT, "%.4f", eval.averagePrecisionByK().getOrDefault(1, 0.0)) : "-";
            var p3 = r.topK() >= 3 ? String.format(Locale.ROOT, "%.4f", eval.averagePrecisionByK().getOrDefault(3, 0.0)) : "-";
            var p5 = r.topK() >= 5 ? String.format(Locale.ROOT, "%.4f", eval.averagePrecisionByK().getOrDefault(5, 0.0)) : "-";
            var r1 = r.topK() >= 1 ? String.format(Locale.ROOT, "%.4f", eval.averageRecallByK().getOrDefault(1, 0.0)) : "-";
            var r3 = r.topK() >= 3 ? String.format(Locale.ROOT, "%.4f", eval.averageRecallByK().getOrDefault(3, 0.0)) : "-";
            var r5 = r.topK() >= 5 ? String.format(Locale.ROOT, "%.4f", eval.averageRecallByK().getOrDefault(5, 0.0)) : "-";
            var h1 = r.topK() >= 1 ? String.format(Locale.ROOT, "%.4f", eval.averageHitByK().getOrDefault(1, 0.0)) : "-";
            var h3 = r.topK() >= 3 ? String.format(Locale.ROOT, "%.4f", eval.averageHitByK().getOrDefault(3, 0.0)) : "-";
            var h5 = r.topK() >= 5 ? String.format(Locale.ROOT, "%.4f", eval.averageHitByK().getOrDefault(5, 0.0)) : "-";
            sb.append(String.format(Locale.ROOT,
                    "%-10d | %-6d | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8s | %-8.4f%n",
                    r.corpusSize(), r.topK(), p1, p3, p5, r1, r3, r5, h1, h3, h5, eval.meanReciprocalRank()));
        }
        sb.append('\n');

        sb.append("4. Ranking Stability vs Seed Baseline\n");
        sb.append("-------------------------------------\n");
        sb.append(String.format(Locale.ROOT,
                "%-10s | %-6s | %-12s | %-10s | %-10s%n",
                "Scale (N)", "Top-K", "Maintained", "Improved", "Degraded"));
        sb.append("-----------+--------+--------------+------------+------------\n");
        for (var r : results) {
            sb.append(String.format(Locale.ROOT,
                    "%-10d | %-6d | %-12d | %-10d | %-10d%n",
                    r.corpusSize(), r.topK(),
                    r.maintainedQueryCount(), r.improvedQueryCount(), r.degradedQueryCount()));
        }

        boolean hasShifts = results.stream().anyMatch(r -> r.improvedQueryCount() > 0 || r.degradedQueryCount() > 0);
        if (hasShifts) {
            sb.append("\nObserved Ranking Shifts from Distractors:\n");
            for (var r : results) {
                var changedShifts = r.rankingShifts().stream()
                        .filter(s -> s.change() != RankingChange.MAINTAINED)
                        .toList();
                if (!changedShifts.isEmpty()) {
                    sb.append(String.format(Locale.ROOT, "  Scale N=%d, Top-K=%d:%n", r.corpusSize(), r.topK()));
                    for (var shift : changedShifts) {
                        var sortedExpected = new ArrayList<>(shift.expectedLabels());
                        Collections.sort(sortedExpected);
                        sb.append(String.format(Locale.ROOT,
                                "    - Query: \"%s\"%n"
                                        + "      expected=%s%n"
                                        + "      baselineReturned=%s (rank=%s)%n"
                                        + "      scaledReturned=%s (rank=%s) [%s]%n",
                                shift.queryText(), sortedExpected,
                                shift.baselineReturnedLabels(),
                                shift.baselineRank() == Integer.MAX_VALUE ? ">K" : String.valueOf(shift.baselineRank()),
                                shift.scaledReturnedLabels(),
                                shift.scaledRank() == Integer.MAX_VALUE ? ">K" : String.valueOf(shift.scaledRank()),
                                shift.change()));
                    }
                }
            }
        }
        sb.append('\n');

        sb.append("5. Top-K Work Invariance Evidence\n");
        sb.append("---------------------------------\n");
        if (hasMeasuredFullScanTopKInvariance()) {
            sb.append("Measured results establish full-scan top-K invariance: multiple top-K arms each\n");
            sb.append("reported a scan fraction of 1.0. Candidate scoring scans N candidates in O(N);\n");
            sb.append("the complete result sort costs O(M log M), where M <= N. Requested top-K does\n");
            sb.append("not bound either baseline operation.\n\n");
        } else {
            sb.append("Measured results did not establish full-scan top-K invariance. This requires at\n");
            sb.append("least two distinct top-K arms and a scan fraction of 1.0 for every result.\n");
            sb.append("Candidate scoring scans N candidates in O(N); complete result sorting costs\n");
            sb.append("O(M log M), where M <= N.\n\n");
        }

        sb.append("6. Optimization Decision Gate\n");
        sb.append("-----------------------------\n");
        sb.append("Decision: ").append(decision.name()).append('\n');
        sb.append("Rationale: ").append(decisionRationale).append('\n');
        sb.append("Policy: Evaluation only — no production scan algorithm changes were made.\n");

        return sb.toString();
    }

    private boolean hasMeasuredFullScanTopKInvariance() {
        long distinctTopKs = results.stream()
                .mapToInt(LatencyScalePointResult::topK)
                .distinct()
                .count();
        return distinctTopKs >= 2
                && results.stream().allMatch(result -> Double.compare(result.scanFraction(), 1.0) == 0);
    }
}
