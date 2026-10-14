package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.VectorLoadBreakdownReport.Decision;
import com.monada.evaluation.VectorLoadBreakdownReport.PointResult;
import com.monada.evaluation.VectorLoadBreakdownReport.StructuralCounts;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorLoadBreakdownRunnerTest {

    private static VectorLoadBreakdownRunner smallRunner(int reps) {
        return new VectorLoadBreakdownRunner("expanded-technology", ExpandedTechnologyDataset.get(),
                List.of(33, 60), 0, reps, 42L);
    }

    private static PhaseLatency lat(long p50) {
        return new PhaseLatency(p50, p50);
    }

    private static PointResult synthetic(int n, long perFloat, long bulkCandidate, long replica) {
        var counts = new StructuralCounts(n, n, n * 128L, n * 512L, n, n * 512L, n * 128L, 2L * n, 2L * n);
        return new PointResult(n, 1, lat(1), lat(1), lat(perFloat), lat(1), lat(1), lat(1), lat(replica),
                lat(bulkCandidate), lat(replica), counts);
    }

    @Test
    void structuralCountsFollowCorpusSizeAndFixedRawFraming(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(1).run(tempDir);
        assertEquals(2, report.results().size());
        assertEquals("FIXED_RAW", report.vectorFraming());
        for (var r : report.results()) {
            long n = r.corpusSize();
            var c = r.counts();
            assertEquals(n, c.indexLinesParsed());
            assertEquals(n, c.seeks());
            assertEquals(n * 128, c.perFloatReadCalls(), "fixed-raw frames have no length prefix");
            assertEquals(n * 512, c.perFloatBytesRead());
            assertEquals(n, c.bulkReadCalls());
            assertEquals(c.perFloatBytesRead(), c.bulkBytesRead());
            assertEquals(n * 128, c.floatsDecoded());
            assertEquals(2 * n, c.arraysAllocated());
            assertEquals(2 * n, c.objectsAllocated());
            assertEquals(1, r.sampleCount());
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
    void smallScalesOrZeroRepetitionsAreInconclusive(@TempDir Path a, @TempDir Path b) throws IOException {
        assertEquals(Decision.INCONCLUSIVE, smallRunner(1).run(a).decision());
        var zero = smallRunner(0).run(b);
        assertEquals(Decision.INCONCLUSIVE, zero.decision());
        assertTrue(zero.results().stream().allMatch(r -> r.sampleCount() == 0));
    }

    @Test
    void renderListsMetadataAndSections(@TempDir Path tempDir) throws IOException {
        var text = smallRunner(1).run(tempDir).render();
        for (var expected : List.of("Generator Seed: 42", "Vector Dimensions: 128", "Vector Framing: FIXED_RAW",
                "Scale Points (N): [33, 60]", "Invocation: ./gradlew :monada-evaluation:runVectorLoadBreakdown -q",
                "Cache Assumption: Warm OS page cache", "1. Structural Counts", "2. Sub-Phase Latency",
                "3. Sub-Phase Share", "4. Whole Load", "5. Decision", "Decision: INCONCLUSIVE")) {
            assertTrue(text.contains(expected), "missing: " + expected);
        }
    }

    @Test
    void decisionBranches() {
        assertEquals(Decision.INCONCLUSIVE, VectorLoadBreakdownRunner.decide(List.of(synthetic(1_000, 90, 10, 100))));
        assertEquals(Decision.INCONCLUSIVE, VectorLoadBreakdownRunner.decide(List.of()));
        assertEquals(Decision.PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED, VectorLoadBreakdownRunner.decide(
                List.of(synthetic(1_000, 10, 90, 100), synthetic(10_000, 90, 10, 100))));
        assertEquals(Decision.PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED, VectorLoadBreakdownRunner.decide(
                List.of(synthetic(10_000, 50, 50, 100))), "thresholds are inclusive");
        assertEquals(Decision.PER_FLOAT_READ_NOT_DOMINANT, VectorLoadBreakdownRunner.decide(
                List.of(synthetic(10_000, 40, 10, 100))), "per-float reads not dominant");
        assertEquals(Decision.PER_FLOAT_READ_NOT_DOMINANT, VectorLoadBreakdownRunner.decide(
                List.of(synthetic(10_000, 90, 60, 100))), "bulk candidate not material");
        var rationale = VectorLoadBreakdownRunner.rationale(Decision.PER_FLOAT_READ_DOMINANT_BULK_READ_JUSTIFIED,
                List.of(synthetic(10_000, 90, 10, 100)));
        assertTrue(rationale.contains("N=10000"));
        assertTrue(rationale.contains("hypothesis is supported"));
        assertTrue(VectorLoadBreakdownRunner.rationale(Decision.PER_FLOAT_READ_NOT_DOMINANT,
                List.of(synthetic(10_000, 40, 10, 100))).contains("not supported"));
    }

    @Test
    void rejectsInvalidConfiguration() {
        var data = ExpandedTechnologyDataset.get();
        assertThrows(IllegalArgumentException.class, () -> new VectorLoadBreakdownRunner(
                "d", data, List.of(), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorLoadBreakdownRunner(
                "d", data, List.of(50, 50), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorLoadBreakdownRunner(
                "d", data, List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorLoadBreakdownRunner(
                "d", data, List.of(50), -1, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorLoadBreakdownRunner(
                "d", data, List.of(50), 0, -1, 42L));
    }
}
