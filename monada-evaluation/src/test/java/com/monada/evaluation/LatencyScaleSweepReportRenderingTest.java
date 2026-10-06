package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyScaleSweepReportRenderingTest {

    @Test
    void rendersAllSectionsAndTables(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 50);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 1, 2, 42L);
        var report = runner.run(tempDir);
        var rendered = report.render();

        assertTrue(rendered.contains("Monada Scaled Linear-Scan Benchmark and Decision Gate"));
        assertTrue(rendered.contains("Vector Dimensions: 128"));
        assertTrue(rendered.contains("1. Structural Scan Summary"));
        assertTrue(rendered.contains("2. Latency and Throughput (Best-Effort Timing)"));
        assertTrue(rendered.contains("3. Retrieval Quality by Scale Point"));
        assertTrue(rendered.contains("4. Ranking Stability vs Seed Baseline"));
        assertTrue(rendered.contains("5. Top-K Work Invariance Evidence"));
        assertTrue(rendered.contains("6. Optimization Decision Gate"));

        assertTrue(rendered.contains("Decision: " + report.decision().name()));
        assertTrue(rendered.contains("Scale (N)"));
        assertTrue(rendered.contains("Scan Fraction"));
        assertTrue(rendered.contains("Standalone Encode (ns)"));
        assertTrue(rendered.contains("Query Latency (ns)"));
        assertTrue(rendered.contains("Min (ns)"));
        assertTrue(rendered.contains("QPS"));
        assertTrue(rendered.contains("P@1"));
        assertTrue(rendered.contains("R@1"));
        assertTrue(rendered.contains("Hit@5"));
        assertTrue(rendered.contains("MRR"));
        assertTrue(rendered.contains("Measured results establish full-scan top-K invariance"));
        assertTrue(rendered.contains("Candidate scoring scans N candidates in O(N)"));
        assertTrue(rendered.contains("complete result sort costs O(M log M), where M <= N"));
    }

    @Test
    void doesNotClaimInvarianceWithoutCompleteMultiArmEvidence() {
        var evaluation = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);
        var timing = new ScaleTimingStatistics(1, 1, 1, 1, 1, 1, 1, 1, 1, 1.0);
        var fullScanPoint = new LatencyScalePointResult(
                100, 1, 1, 0, 1, 100, 1.0, 1, timing, evaluation, 0, 1, 0, List.of());
        var partialScanPoint = new LatencyScalePointResult(
                100, 1, 5, 0, 1, 75, 0.75, 1, timing, evaluation, 0, 1, 0, List.of());

        var singleArmReport = new LatencyScaleSweepReport(
                "test", 1L, 128, List.of(100), List.of(1), 0, 1, List.of(fullScanPoint),
                ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, "test rationale");
        var partialScanReport = new LatencyScaleSweepReport(
                "test", 1L, 128, List.of(100), List.of(1, 5), 0, 1, List.of(fullScanPoint, partialScanPoint),
                ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, "test rationale");

        for (var rendered : List.of(singleArmReport.render(), partialScanReport.render())) {
            assertTrue(rendered.contains("Measured results did not establish full-scan top-K invariance"));
            assertFalse(rendered.contains("Measured results establish full-scan top-K invariance"));
        }

        assertThrows(IllegalArgumentException.class, () -> new LatencyScaleSweepReport(
                "test", 1L, 0, List.of(100), List.of(1), 0, 1, List.of(fullScanPoint),
                ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, "test rationale"));
    }
}
