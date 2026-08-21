package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyScaleSweepRunnerTest {

    @Test
    void structuralScanCountMatchesLinearScanInvariant(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 50);
        var topKs = List.of(1);

        var runner = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertNotNull(report);
        assertEquals(2, report.results().size());

        for (var res : report.results()) {
            assertEquals(res.corpusSize() * res.queryCount(), res.totalScanned(),
                    "Linear scan must scan all stored candidates for every query");
            assertEquals(1.0, res.scanFraction(), 1e-9,
                    "Linear scan fraction must be 1.0");
            assertTrue(res.totalReturned() > 0);
        }
    }

    @Test
    void topKSweepShowsWorkInvariance(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(50);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertEquals(2, report.results().size());
        var resK1 = report.results().get(0);
        var resK5 = report.results().get(1);

        assertEquals(1, resK1.topK());
        assertEquals(5, resK5.topK());

        // In linear scan, scanned candidates is identical regardless of topK!
        assertEquals(resK1.totalScanned(), resK5.totalScanned());
        assertEquals(resK1.scanFraction(), resK5.scanFraction(), 1e-9);
    }

    @Test
    void rankingChangeTrackingValid(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(60);
        var topKs = List.of(3);

        var runner = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        var res = report.results().getFirst();
        int totalTracked = res.maintainedQueryCount() + res.improvedQueryCount() + res.degradedQueryCount();
        assertEquals(base.queries().size(), totalTracked);
    }

    @Test
    void decisionGateInconclusiveWhenSmallScale(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 60);
        var topKs = List.of(1);

        var runner = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, report.decision());
        assertTrue(report.decisionRationale().contains("below the multi-thousand"));
    }

    @Test
    void deterministicQualityResultsAcrossRuns(@TempDir Path tempDir1, @TempDir Path tempDir2) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(40);
        var topKs = List.of(1);

        var report1 = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 99L).run(tempDir1);
        var report2 = new LatencyScaleSweepRunner(base, scalePoints, topKs, 0, 1, 99L).run(tempDir2);

        var res1 = report1.results().getFirst();
        var res2 = report2.results().getFirst();

        assertEquals(res1.evaluationReport().meanReciprocalRank(),
                res2.evaluationReport().meanReciprocalRank(), 1e-15);
        assertEquals(res1.evaluationReport().averagePrecisionByK(),
                res2.evaluationReport().averagePrecisionByK());
        assertEquals(res1.evaluationReport().averageRecallByK(),
                res2.evaluationReport().averageRecallByK());
        assertEquals(res1.evaluationReport().averageHitByK(),
                res2.evaluationReport().averageHitByK());
        assertEquals(res1.totalScanned(), res2.totalScanned());
    }
}
