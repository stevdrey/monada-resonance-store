package com.monada.evaluation;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.evaluation.BoundedTopKExperimentReport.Decision;
import com.monada.evaluation.BoundedTopKExperimentReport.ParityCounts;
import com.monada.evaluation.BoundedTopKExperimentReport.PointResult;
import com.monada.evaluation.BoundedTopKExperimentReport.RetentionCounts;
import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.index.ExactBoundedTopKSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedTopKExperimentRunnerTest {

    private static BoundedTopKExperimentRunner smallRunner(int reps) {
        return new BoundedTopKExperimentRunner("expanded-technology", ExpandedTechnologyDataset.get(),
                List.of(33, 60), List.of(1, 5), 0, reps, 42L);
    }

    private static PhaseLatency lat(long p50) {
        return new PhaseLatency(p50, p50);
    }

    private static PointResult synthetic(int n, int k, long mismatches, long oracleSel, long candidateSel,
                                         long productionSearch, long candidateSearch) {
        var parity = new ParityCounts(1, 2, mismatches, 2, 0);
        var retention = new RetentionCounts(n, n, k, n, k, k);
        return new PointResult(n, k, 1, 1, parity, retention, lat(oracleSel), lat(candidateSel),
                lat(productionSearch), lat(candidateSearch), 0, 0);
    }

    private static ResonanceResult result(String id, double score) {
        return new ResonanceResult(new KnowledgeAtom(id, AtomType.TEXT, "content-" + id, Map.of(), 1.0,
                Instant.parse("2024-01-01T00:00:00Z")), score);
    }

    @Test
    void parityHoldsAndRetainedCandidatesAreBoundedByK(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(1).run(tempDir);
        assertEquals(4, report.results().size());
        for (var r : report.results()) {
            var p = r.parity();
            assertEquals(0, p.mismatches(), "N=" + r.corpusSize() + " K=" + r.topK());
            assertEquals(2L * r.queryCount(), p.selectorChecks());
            assertTrue(p.searchChecks() >= r.queryCount());
            var c = r.retention();
            assertEquals((long) r.corpusSize() * r.queryCount(), c.candidates(), "threshold is -inf, ids unique");
            assertEquals(c.candidates(), c.oracleRetained());
            assertTrue(c.maxCandidatePeakRetainedPerQuery() <= r.topK());
            assertTrue(c.candidatePeakRetained() <= (long) r.topK() * r.queryCount());
            assertEquals(c.candidatePeakRetained(), c.resultsReturned());
        }
        assertTrue(report.decision() != Decision.NEEDS_FIX);
    }

    @Test
    void structuralOutputIsDeterministicAcrossRuns(@TempDir Path a, @TempDir Path b) throws IOException {
        var first = smallRunner(0).run(a);
        var second = smallRunner(0).run(b);
        assertEquals(first.results().size(), second.results().size());
        for (int i = 0; i < first.results().size(); i++) {
            assertEquals(first.results().get(i).parity(), second.results().get(i).parity());
            assertEquals(first.results().get(i).retention(), second.results().get(i).retention());
        }
    }

    @Test
    void zeroRepetitionsSkipTimingAndReportNoBenefit(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(0).run(tempDir);
        assertEquals(Decision.NO_BENEFIT, report.decision());
        assertTrue(report.decisionRationale().contains("insufficient evidence"));
        assertTrue(report.results().stream().allMatch(r -> r.sampleCount() == 0));
    }

    @Test
    void renderListsMetadataSeedAndSections(@TempDir Path tempDir) throws IOException {
        var text = smallRunner(1).run(tempDir).render();
        for (var expected : List.of("Generator Seed: 42", "Vector Dimensions: 128", "Scale Points (N): [33, 60]",
                "Top-K Sweep: [1, 5]", "Invocation: ./gradlew :monada-evaluation:runBoundedTopKExperiment -q",
                "1. Oracle Parity", "2. Retained Candidates and Allocation", "3. Selection Phase Latency",
                "4. End-to-End Search Latency", "5. Decision", "No automatic production rollout.")) {
            assertTrue(text.contains(expected), "missing: " + expected);
        }
    }

    @Test
    void decisionBranches() {
        // Any parity mismatch wins over timing.
        assertEquals(Decision.NEEDS_FIX, BoundedTopKExperimentRunner.decide(
                List.of(synthetic(10_000, 1, 1, 100, 10, 1_000, 900))));
        assertTrue(BoundedTopKExperimentRunner.rationale(Decision.NEEDS_FIX,
                List.of(synthetic(10_000, 1, 1, 100, 10, 1_000, 900))).contains("1 parity mismatch"));
        // Small scale: no retain decision.
        assertEquals(Decision.NO_BENEFIT, BoundedTopKExperimentRunner.decide(
                List.of(synthetic(1_000, 1, 0, 100, 10, 1_000, 900))));
        // Faster selection and not-slower search for every K.
        assertEquals(Decision.RETAIN_EXPERIMENT, BoundedTopKExperimentRunner.decide(
                List.of(synthetic(10_000, 1, 0, 100, 10, 1_000, 900), synthetic(10_000, 5, 0, 100, 80, 1_000, 1_000))));
        // Selection not fast enough for one K.
        assertEquals(Decision.NO_BENEFIT, BoundedTopKExperimentRunner.decide(
                List.of(synthetic(10_000, 1, 0, 100, 10, 1_000, 900), synthetic(10_000, 5, 0, 100, 81, 1_000, 900))));
        // End-to-end slower despite faster selection.
        assertEquals(Decision.NO_BENEFIT, BoundedTopKExperimentRunner.decide(
                List.of(synthetic(10_000, 1, 0, 100, 10, 1_000, 1_001))));
        assertTrue(BoundedTopKExperimentRunner.rationale(Decision.RETAIN_EXPERIMENT,
                List.of(synthetic(10_000, 1, 0, 100, 10, 1_000, 900))).contains("N=10000 K=1"));
    }

    @Test
    void oracleHelpersMatchSelectorAndDetectDivergence() {
        var candidates = List.of(result("c", 0.5), result("a", 0.5), result("b", 0.9), result("d", -0.0),
                result("e", 0.0));
        var hashOrder = BoundedTopKExperimentRunner.productionHashOrder(candidates);
        assertEquals(candidates.size(), hashOrder.size());
        for (int k = 1; k <= candidates.size() + 1; k++) {
            assertTrue(BoundedTopKExperimentRunner.sameResults(
                    BoundedTopKExperimentRunner.oracleSelect(hashOrder, k),
                    ExactBoundedTopKSelector.select(hashOrder, k).results()));
        }
        assertEquals(List.of("b", "a", "c"), BoundedTopKExperimentRunner.oracleSelect(candidates, 3).stream()
                .map(r -> r.atom().id()).toList());
        assertFalse(BoundedTopKExperimentRunner.sameResults(
                List.of(result("a", 0.0)), List.of(result("a", -0.0))), "score bits must differ");
        assertFalse(BoundedTopKExperimentRunner.sameResults(
                List.of(result("a", 0.5), result("b", 0.5)), List.of(result("b", 0.5), result("a", 0.5))));
    }

    @Test
    void rejectsInvalidConfiguration() {
        var data = ExpandedTechnologyDataset.get();
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopKExperimentRunner(
                "d", data, List.of(50, 50), List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopKExperimentRunner(
                "d", data, List.of(50), List.of(0), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopKExperimentRunner(
                "d", data, List.of(50), List.of(1, 1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopKExperimentRunner(
                "d", data, List.of(1), List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopKExperimentRunner(
                "d", data, List.of(50), List.of(1), -1, 1, 42L));
    }
}
