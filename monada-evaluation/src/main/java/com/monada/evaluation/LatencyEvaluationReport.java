package com.monada.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Combines a standard {@link EvaluationReport} with recall latency and scan
 * diagnostics. Rendering is additive: the original quality report is preserved
 * and a latency/scan section is appended.
 */
public record LatencyEvaluationReport(
        EvaluationReport evaluationReport,
        List<QueryLatencyMetrics> queryMetrics,
        LatencySummary summary
) {
    public LatencyEvaluationReport {
        Objects.requireNonNull(evaluationReport, "evaluationReport");
        queryMetrics = List.copyOf(Objects.requireNonNull(queryMetrics, "queryMetrics"));
        Objects.requireNonNull(summary, "summary");
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(evaluationReport.render());
        sb.append("Latency and Scan Diagnostics\n");
        sb.append("============================\n\n");

        sb.append("Summary\n");
        sb.append("-------\n");
        sb.append(String.format(Locale.ROOT, "Corpus size: %d%n", summary.corpusSize()));
        sb.append(String.format(Locale.ROOT, "Query count: %d%n", summary.queryCount()));
        sb.append(String.format(Locale.ROOT, "Max K: %d%n", summary.maxK()));
        sb.append(String.format(Locale.ROOT, "Total scanned candidates: %d%n", summary.totalScanned()));
        sb.append(String.format(Locale.ROOT, "Total returned candidates: %d%n", summary.totalReturned()));
        sb.append(String.format(Locale.ROOT, "Total elapsed: %d ns%n", summary.totalElapsedNanos()));
        sb.append(String.format(Locale.ROOT, "Min elapsed: %d ns%n", summary.minElapsedNanos()));
        sb.append(String.format(Locale.ROOT, "Max elapsed: %d ns%n", summary.maxElapsedNanos()));
        sb.append(String.format(Locale.ROOT, "Avg elapsed: %d ns%n", summary.avgElapsedNanos()));
        sb.append("\n");

        sb.append("Per-Query Latency\n");
        sb.append("-----------------\n");
        for (var m : queryMetrics) {
            sb.append("Query: ").append(m.queryText()).append('\n');
            sb.append(String.format(Locale.ROOT, "  topK: %d%n", m.topK()));
            sb.append(String.format(Locale.ROOT, "  threshold: %.2f%n", m.threshold()));
            sb.append(String.format(Locale.ROOT, "  scanned: %d%n", m.scannedCandidates()));
            sb.append(String.format(Locale.ROOT, "  returned: %d%n", m.returnedCandidates()));
            sb.append(String.format(Locale.ROOT, "  elapsed: %d ns%n", m.elapsedNanos()));
            if (m.blankQuery()) {
                sb.append("  blank query: true (no resonance search executed)\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
