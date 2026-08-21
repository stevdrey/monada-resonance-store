package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyScaleSweepReportRenderingTest {

    @Test
    void rendersAllSectionsAndTables(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 50);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner(base, scalePoints, topKs, 1, 2, 42L);
        var report = runner.run(tempDir);
        var rendered = report.render();

        assertTrue(rendered.contains("Monada Scaled Linear-Scan Benchmark and Decision Gate"));
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
        assertTrue(rendered.contains("QPS"));
        assertTrue(rendered.contains("MRR"));
    }
}
