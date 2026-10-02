package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyScaleSweepRunnerTest {

    @Test
    void structuralScanCountMatchesLinearScanInvariant(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 50);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertNotNull(report);
        assertEquals(4, report.results().size());

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

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 42L);
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
        var topKs = List.of(3, 5);

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        var res = report.results().getFirst();
        int totalTracked = res.maintainedQueryCount() + res.improvedQueryCount() + res.degradedQueryCount();
        assertEquals(base.queries().size(), totalTracked);
        // All query rankings are preserved in rankingShifts
        assertEquals(base.queries().size(), res.rankingShifts().size());
        long changedCount = res.rankingShifts().stream()
                .filter(s -> s.change() != RankingChange.MAINTAINED)
                .count();
        assertEquals(res.improvedQueryCount() + res.degradedQueryCount(), changedCount);
    }

    @Test
    void decisionGateInconclusiveWhenSmallScale(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 60);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, report.decision());
        assertTrue(report.decisionRationale().contains("below the multi-thousand"));
    }

    @Test
    void decisionGateInconclusiveWhenSingleTopKArm(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 5000);
        var topKs = List.of(1); // single topK arm

        var runner = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE, report.decision());
        assertTrue(report.decisionRationale().contains("at least two distinct top-K arms"));
    }

    @Test
    void customDatasetNamePreservedInReport(@TempDir Path tempDir) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(33, 50);
        var topKs = List.of(1, 5);

        var runner = new LatencyScaleSweepRunner("custom-tech-dataset", base, scalePoints, topKs, 0, 1, 42L);
        var report = runner.run(tempDir);

        assertEquals("custom-tech-dataset", report.seedDatasetName());
        assertTrue(report.render().contains("Seed Dataset: custom-tech-dataset"));
    }

    @Test
    void rejectsDuplicateTopKArms() {
        var base = ExpandedTechnologyDataset.get();

        var exception = assertThrows(IllegalArgumentException.class, () -> new LatencyScaleSweepRunner(
                "expanded-technology", base, List.of(33), List.of(1, 5, 5), 0, 1, 42L));

        assertTrue(exception.getMessage().contains("distinct"));
    }

    @Test
    void decisionGateDistinctBranches() {
        var runner = new LatencyScaleSweepRunner();
        var dummyEval = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 1.0);

        // Branch 1: Insufficient scale (< 1000) -> INCONCLUSIVE_NEEDS_LARGER_SCALE
        var smallPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 10, 10, 10, 10, 10, 5, 100.0),
                dummyEval, 0, 10, 0, List.of());
        var smallPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 10, 10, 10, 10, 10, 5, 100.0),
                dummyEval, 0, 10, 0, List.of());
        var smallPoint3 = new LatencyScalePointResult(
                500, 10, 1, 0, 1, 5000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 50, 50, 50, 50, 50, 5, 20.0),
                dummyEval, 0, 10, 0, List.of());
        var smallPoint4 = new LatencyScalePointResult(
                500, 10, 5, 0, 1, 5000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 50, 50, 50, 50, 50, 5, 20.0),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE,
                runner.evaluateDecision(List.of(smallPoint1, smallPoint2, smallPoint3, smallPoint4)));

        // Branch 2: Structural scan fraction < 0.99 -> OPTIMIZATION_NOT_YET_JUSTIFIED
        var partialScanPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 500, 0.5, 10,
                new ScaleTimingStatistics(10, 1, 10, 10, 10, 10, 10, 10, 5, 100.0),
                dummyEval, 0, 10, 0, List.of());
        var partialScanPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 500, 0.5, 10,
                new ScaleTimingStatistics(10, 1, 10, 10, 10, 10, 10, 10, 5, 100.0),
                dummyEval, 0, 10, 0, List.of());
        var partialScanPoint3 = new LatencyScalePointResult(
                5000, 10, 1, 0, 1, 25000, 0.5, 10,
                new ScaleTimingStatistics(10, 1, 10, 1000, 1000, 1000, 1000, 1000, 5, 1.0),
                dummyEval, 0, 10, 0, List.of());
        var partialScanPoint4 = new LatencyScalePointResult(
                5000, 10, 5, 0, 1, 25000, 0.5, 10,
                new ScaleTimingStatistics(10, 1, 10, 1000, 1000, 1000, 1000, 1000, 5, 1.0),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.OPTIMIZATION_NOT_YET_JUSTIFIED,
                runner.evaluateDecision(List.of(partialScanPoint1, partialScanPoint2, partialScanPoint3, partialScanPoint4)));

        // Branch 3: Large scale but flat/noisy timing (no scale curve growth) -> INCONCLUSIVE_NEEDS_LARGER_SCALE
        var noisyPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 500, 500, 500, 500, 500, 50, 2.0),
                dummyEval, 0, 10, 0, List.of());
        var noisyPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 500, 500, 500, 500, 500, 50, 2.0),
                dummyEval, 0, 10, 0, List.of());
        var noisyPoint3 = new LatencyScalePointResult(
                5000, 10, 1, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 400, 400, 400, 400, 400, 50, 2.5),
                dummyEval, 0, 10, 0, List.of());
        var noisyPoint4 = new LatencyScalePointResult(
                5000, 10, 5, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 400, 400, 400, 400, 400, 50, 2.5),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE,
                runner.evaluateDecision(List.of(noisyPoint1, noisyPoint2, noisyPoint3, noisyPoint4)));

        // Branch 3b: Intermediate non-monotonic scale curve (100 -> 1000 -> 5000 where 1000 > 5000)
        var interPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 1000, 1000, 1000, 1000, 1000, 50, 1.0),
                dummyEval, 0, 10, 0, List.of());
        var interPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 1000, 1000, 1000, 1000, 1000, 50, 1.0),
                dummyEval, 0, 10, 0, List.of());
        var interPoint3 = new LatencyScalePointResult(
                1000, 10, 1, 0, 1, 10000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 50000, 50000, 50000, 50000, 50000, 50, 0.02),
                dummyEval, 0, 10, 0, List.of());
        var interPoint4 = new LatencyScalePointResult(
                1000, 10, 5, 0, 1, 10000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 50000, 50000, 50000, 50000, 50000, 50, 0.02),
                dummyEval, 0, 10, 0, List.of());
        var interPoint5 = new LatencyScalePointResult(
                5000, 10, 1, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 30000, 30000, 30000, 30000, 30000, 50, 0.03),
                dummyEval, 0, 10, 0, List.of());
        var interPoint6 = new LatencyScalePointResult(
                5000, 10, 5, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 30000, 30000, 30000, 30000, 30000, 50, 0.03),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE,
                runner.evaluateDecision(List.of(interPoint1, interPoint2, interPoint3, interPoint4, interPoint5, interPoint6)));

        // Branch 4: Large scale where latency is comparable to standalone encode (< 2x) -> OPTIMIZATION_NOT_YET_JUSTIFIED
        var fastPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 100, 100, 100, 100, 100, 80, 10.0),
                dummyEval, 0, 10, 0, List.of());
        var fastPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 100, 100, 100, 100, 100, 80, 10.0),
                dummyEval, 0, 10, 0, List.of());
        var fastPoint3 = new LatencyScalePointResult(
                5000, 10, 1, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 120, 120, 120, 120, 120, 80, 8.0),
                dummyEval, 0, 10, 0, List.of());
        var fastPoint4 = new LatencyScalePointResult(
                5000, 10, 5, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 120, 120, 120, 120, 120, 80, 8.0),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.OPTIMIZATION_NOT_YET_JUSTIFIED,
                runner.evaluateDecision(List.of(fastPoint1, fastPoint2, fastPoint3, fastPoint4)));

        // Branch 5: Large scale with full scan, >= 2 top-K arms, and clear scale-curve growth -> BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED
        var growthPoint1 = new LatencyScalePointResult(
                100, 10, 1, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 20_000, 20_000, 20_000, 20_000, 20_000, 100, 50.0),
                dummyEval, 0, 10, 0, List.of());
        var growthPoint2 = new LatencyScalePointResult(
                100, 10, 5, 0, 1, 1000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 20_000, 20_000, 20_000, 20_000, 20_000, 100, 50.0),
                dummyEval, 0, 10, 0, List.of());
        var growthPoint3 = new LatencyScalePointResult(
                5000, 10, 1, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 100, 1.0),
                dummyEval, 0, 10, 0, List.of());
        var growthPoint4 = new LatencyScalePointResult(
                5000, 10, 5, 0, 1, 50000, 1.0, 10,
                new ScaleTimingStatistics(10, 1, 10, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 100, 1.0),
                dummyEval, 0, 10, 0, List.of());
        assertEquals(ScaleOptimizationDecision.BOUNDED_EXACT_TOP_K_EXPERIMENT_JUSTIFIED,
                runner.evaluateDecision(List.of(growthPoint1, growthPoint2, growthPoint3, growthPoint4)));

        // Branch 6: Large scale with growth but single top-K arm -> INCONCLUSIVE_NEEDS_LARGER_SCALE
        assertEquals(ScaleOptimizationDecision.INCONCLUSIVE_NEEDS_LARGER_SCALE,
                runner.evaluateDecision(List.of(growthPoint1, growthPoint3)));
    }

    @Test
    void deterministicQualityResultsAcrossRuns(@TempDir Path tempDir1, @TempDir Path tempDir2) throws IOException {
        var base = ExpandedTechnologyDataset.get();
        var scalePoints = List.of(40);
        var topKs = List.of(1, 5);

        var report1 = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 99L).run(tempDir1);
        var report2 = new LatencyScaleSweepRunner("expanded-technology", base, scalePoints, topKs, 0, 1, 99L).run(tempDir2);

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
