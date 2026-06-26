package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the combined latency/scan diagnostics report renders correctly.
 *
 * <p>Only structural content and formatting are asserted; timing values are
 * verified to be present, not checked against strict thresholds.
 */
class LatencyReportRenderingTest {

    @Test
    void rendersLatencyAndScanSections(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var report = new LatencyEvaluationRunner().run(dataset, tempDir);
        var rendered = report.render();

        assertTrue(rendered.contains("Latency and Scan Diagnostics"),
                "report must contain latency/scan header");
        assertTrue(rendered.contains("Summary"),
                "report must contain summary section");
        assertTrue(rendered.contains("Per-Query Latency"),
                "report must contain per-query latency section");
        assertTrue(rendered.contains("Corpus size: " + dataset.atoms().size()),
                "report must show corpus size");
        assertTrue(rendered.contains("Query count: " + dataset.queries().size()),
                "report must show query count");
        assertTrue(rendered.contains("Total scanned candidates:"),
                "report must show total scanned candidates");
        assertTrue(rendered.contains("Total returned candidates:"),
                "report must show total returned candidates");
        assertTrue(rendered.contains("Total elapsed:"),
                "report must show total elapsed time");
        assertTrue(rendered.contains("Min elapsed:"),
                "report must show min elapsed time");
        assertTrue(rendered.contains("Max elapsed:"),
                "report must show max elapsed time");
        assertTrue(rendered.contains("Avg elapsed:"),
                "report must show avg elapsed time");
    }

    @Test
    void rendersPerQueryLatencyEntries(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var report = new LatencyEvaluationRunner().run(dataset, tempDir);
        var rendered = report.render();

        for (var query : dataset.queries()) {
            assertTrue(rendered.contains("Query: " + query.text()),
                    "report must contain latency entry for query: " + query.text());
        }

        assertTrue(rendered.contains("scanned:"),
                "per-query entry must show scanned candidates");
        assertTrue(rendered.contains("returned:"),
                "per-query entry must show returned candidates");
        assertTrue(rendered.contains("elapsed:"),
                "per-query entry must show elapsed time");
        assertTrue(rendered.contains(" ns"),
                "elapsed time must be formatted in nanoseconds");
    }

    @Test
    void rejectsMismatchedQueryMetricsAndEvaluationReportSizes() {
        var emptyReport = new EvaluationReport(
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                0.0);
        var oneMetric = new QueryLatencyMetrics("query", 1, Double.NEGATIVE_INFINITY, 5, 5, 1, 100L, false);
        var summary = LatencySummary.from(List.of(oneMetric), 1, 5);
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEvaluationReport(emptyReport, List.of(oneMetric), summary),
                "queryMetrics.size()=1 != evaluationReport.queryResults().size()=0 must throw");
    }

    @Test
    void preservesOriginalQualityReportContent(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var report = new LatencyEvaluationRunner().run(dataset, tempDir);
        var rendered = report.render();

        assertTrue(rendered.contains("Monada Resonance Store Evaluation Report"),
                "rendered report must preserve original evaluation report header");
        assertTrue(rendered.contains("Aggregate"),
                "rendered report must preserve aggregate section");
        assertTrue(rendered.contains("Mean Reciprocal Rank"),
                "rendered report must preserve MRR line");
    }
}
