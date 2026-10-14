package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.Decision;
import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.ScanPhaseBreakdownReport.PointResult;
import com.monada.evaluation.ScanPhaseBreakdownReport.StructuralCounts;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanPhaseBreakdownRunnerTest {

    private static ScanPhaseBreakdownRunner smallRunner(int reps) {
        return new ScanPhaseBreakdownRunner("expanded-technology", ExpandedTechnologyDataset.get(),
                List.of(33, 60), List.of(1, 5), 0, reps, 42L);
    }

    private static PhaseLatency lat(long p50) {
        return new PhaseLatency(p50, p50);
    }

    private static PointResult synthetic(int n, int k, long sort, long load) {
        var counts = new StructuralCounts(n, n, 0, n, n, k, 0, 0);
        return new PointResult(n, k, 1, 1, lat(sort + load), lat(0), lat(load), lat(0), lat(0), lat(sort), lat(0),
                lat(sort + load), lat(sort + load), counts);
    }

    @Test
    void structuralCountsMatchLinearScanInvariantAndParityHolds(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(1).run(tempDir);
        assertEquals(4, report.results().size());
        for (var r : report.results()) {
            var c = r.counts();
            assertEquals((long) r.corpusSize() * r.queryCount(), c.vectorsVisited());
            assertEquals(c.vectorsVisited(), c.cosineEvaluations(), "no orphans in a seeded store");
            assertEquals(0, c.orphanVectors());
            assertEquals(c.cosineEvaluations(), c.candidates(), "threshold is -inf and ids are unique");
            assertTrue(c.resultsReturned() <= (long) r.topK() * r.queryCount());
            assertTrue(c.topKCostModel() <= c.sortCostModel());
        }
    }

    @Test
    void structuralOutputIsDeterministicAcrossRuns(@TempDir Path a, @TempDir Path b) throws IOException {
        var first = smallRunner(0).run(a);
        var second = smallRunner(0).run(b);
        for (int i = 0; i < first.results().size(); i++) {
            assertEquals(first.results().get(i).counts(), second.results().get(i).counts());
        }
    }

    @Test
    void zeroRepetitionsSkipTimingAndAreInconclusive(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(0).run(tempDir);
        assertEquals(Decision.INCONCLUSIVE, report.decision());
        assertTrue(report.results().stream().allMatch(r -> r.sampleCount() == 0));
    }

    @Test
    void renderListsMetadataSeedAndSections(@TempDir Path tempDir) throws IOException {
        var text = smallRunner(1).run(tempDir).render();
        for (var expected : List.of("Generator Seed: 42", "Vector Dimensions: 128", "Scale Points (N): [33, 60]",
                "Top-K Sweep: [1, 5]", "Invocation: ./gradlew :monada-evaluation:runScanPhaseBreakdown -q",
                "1. Structural Counts", "1b. Illustrative Cost Model (NOT measured comparisons)", "2. Phase Latency", "3. Phase Share", "4. Measurement Overhead",
                "5. Decision")) {
            assertTrue(text.contains(expected), "missing: " + expected);
        }
    }

    @Test
    void decisionBranches() {
        assertEquals(Decision.INCONCLUSIVE, ScanPhaseBreakdownRunner.decide(List.of(synthetic(1_000, 1, 50, 50))));
        assertEquals(Decision.BOUNDED_TOP_K_PROTOTYPE_JUSTIFIED, ScanPhaseBreakdownRunner.decide(
                List.of(synthetic(10_000, 1, 40, 60), synthetic(10_000, 5, 30, 70))));
        assertEquals(Decision.BOUNDED_TOP_K_PROTOTYPE_NOT_JUSTIFIED, ScanPhaseBreakdownRunner.decide(
                List.of(synthetic(10_000, 1, 5, 95), synthetic(10_000, 5, 40, 60))));
        assertTrue(ScanPhaseBreakdownRunner.rationale(Decision.BOUNDED_TOP_K_PROTOTYPE_NOT_JUSTIFIED,
                List.of(synthetic(10_000, 1, 5, 95))).contains("N=10000 K=1"));
    }

    @Test
    void rejectsInvalidConfiguration() {
        var data = ExpandedTechnologyDataset.get();
        assertThrows(IllegalArgumentException.class, () -> new ScanPhaseBreakdownRunner(
                "d", data, List.of(50, 50), List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new ScanPhaseBreakdownRunner(
                "d", data, List.of(50), List.of(0), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new ScanPhaseBreakdownRunner(
                "d", data, List.of(50), List.of(1, 1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new ScanPhaseBreakdownRunner(
                "d", data, List.of(1), List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new ScanPhaseBreakdownRunner(
                "d", data, List.of(50), List.of(1), -1, 1, 42L));
    }
}
