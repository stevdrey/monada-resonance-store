package com.monada.evaluation;

import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.VectorCopyProbe.Materialization;
import com.monada.evaluation.VectorCopyProbe.StoreKind;
import com.monada.evaluation.VectorCopyShareReport.Decision;
import com.monada.evaluation.VectorCopyShareReport.PointResult;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import com.monada.speech.storage.FileSpeechFeatureStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorCopyShareRunnerTest {

    private static VectorCopyShareRunner smallRunner(int reps) {
        return new VectorCopyShareRunner("expanded-technology", ExpandedTechnologyDataset.get(),
                List.of(33, 60), 0, reps, 42L);
    }

    private static PhaseLatency lat(long p50) {
        return new PhaseLatency(p50, p50);
    }

    /** A point whose copy costs {@code copyNanos} of a 100 ns production load and {@code copyBytes} of 1,000. */
    private static PointResult point(StoreKind store, int n, long copyNanos, long copyBytes) {
        return new PointResult(store, n, 64, 1, lat(100), lat(100), lat(100 - copyNanos), 1_000, 1_000,
                1_000 - copyBytes);
    }

    private static List<PointResult> bothStores(int n, long frequencyNanos, long frequencyBytes,
                                                long speechNanos, long speechBytes) {
        return List.of(point(StoreKind.FREQUENCY, n, frequencyNanos, frequencyBytes),
                point(StoreKind.SPEECH, n, speechNanos, speechBytes));
    }

    @Test
    void reportsBothStoresAtEveryScalePoint(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(1).run(tempDir);
        assertEquals(4, report.results().size());
        for (var r : report.results()) {
            assertEquals(1, r.sampleCount());
            assertEquals(r.store() == StoreKind.FREQUENCY ? 128 : 64, r.dimensions());
            assertTrue(List.of(33, 60).contains(r.corpusSize()));
        }
    }

    @Test
    void replicasMatchTheRealSpeechStoreIncludingLastWins(@TempDir Path tempDir) throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        VectorCopyShareRunner.seedSpeechStore(store, 5, 7L);
        VectorCopyShareRunner.seedSpeechStore(store, 3, 8L); // overwrites the first three ids
        var expected = VectorCopyProbe.fromSpeechStore(store.findAll());
        assertEquals(5, expected.size());
        var probe = VectorCopyProbe.speech(tempDir);
        VectorCopyProbe.verifyParity("copy", expected, probe.load(Materialization.COPY));
        VectorCopyProbe.verifyParity("adopt", expected, probe.load(Materialization.ADOPT));
    }

    @Test
    void parityCheckRejectsDivergentValues(@TempDir Path tempDir) throws IOException {
        var store = new FileSpeechFeatureStore(tempDir);
        VectorCopyShareRunner.seedSpeechStore(store, 2, 7L);
        var probe = VectorCopyProbe.speech(tempDir);
        var expected = probe.load(Materialization.ADOPT);
        var tampered = probe.load(Materialization.ADOPT);
        ((VectorCopyProbe.AdoptedVector) tampered.get(1).vector()).values()[3] += 1.0f;
        assertThrows(IllegalStateException.class, () -> VectorCopyProbe.verifyParity("t", expected, tampered));
        assertThrows(IllegalStateException.class,
                () -> VectorCopyProbe.verifyParity("t", expected, expected.subList(0, 1)));
    }

    @Test
    void expectedCopyBytesAreOneAlignedFloatArrayPerVector() {
        var r = new PointResult(StoreKind.FREQUENCY, 10, 128, 1, lat(1), lat(1), lat(1), 0, 0, 0);
        assertEquals(528, r.expectedCopyBytesPerVector());
        assertEquals(5_280, r.expectedCopyBytes());
        var odd = new PointResult(StoreKind.SPEECH, 1, 3, 1, lat(1), lat(1), lat(1), 0, 0, 0);
        assertEquals(32, odd.expectedCopyBytesPerVector(), "16 + 12 rounded up to 8");
        var unmeasured = new PointResult(StoreKind.SPEECH, 1, 3, 1, lat(1), lat(1), lat(1), -1, -1, -1);
        assertEquals(0.0, unmeasured.allocationShare());
    }

    @Test
    void sharesAreRelativeToTheCopyingPathNotProduction() {
        // Post-#135 shape: production no longer copies, so it matches the adopting replica.
        var r = new PointResult(StoreKind.FREQUENCY, 10_000, 128, 1, new PhaseLatency(70, 140),
                new PhaseLatency(100, 200), new PhaseLatency(70, 140), 700, 1_000, 700);
        assertEquals(0.30, r.timeShare(), 1e-9, "not (100 - 70) / 70");
        assertEquals(0.30, r.timeShareP95(), 1e-9);
        assertEquals(0.30, r.allocationShare(), 1e-9, "not (1000 - 700) / 700");
    }

    @Test
    void smallScalesOrZeroRepetitionsAreInconclusive(@TempDir Path a, @TempDir Path b) throws IOException {
        assertEquals(Decision.INCONCLUSIVE, smallRunner(1).run(a).decision());
        var zero = smallRunner(0).run(b);
        assertEquals(Decision.INCONCLUSIVE, zero.decision());
        assertTrue(zero.results().stream().allMatch(r -> r.sampleCount() == 0));
    }

    @Test
    void decisionBranches() {
        assertEquals(Decision.INCONCLUSIVE, VectorCopyShareRunner.decide(List.of()));
        assertEquals(Decision.INCONCLUSIVE, VectorCopyShareRunner.decide(bothStores(5_000, 50, 500, 50, 500)),
                "below N=10,000");
        assertEquals(Decision.INCONCLUSIVE, VectorCopyShareRunner.decide(
                List.of(point(StoreKind.FREQUENCY, 10_000, 50, 500))), "speech store missing");
        assertEquals(Decision.COPY_SHARE_NEGLIGIBLE, VectorCopyShareRunner.decide(bothStores(10_000, 9, 299, 9, 299)));
        assertEquals(Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY,
                VectorCopyShareRunner.decide(bothStores(10_000, 10, 0, 0, 0)), "time threshold is inclusive");
        assertEquals(Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY,
                VectorCopyShareRunner.decide(bothStores(10_000, 0, 0, 0, 300)), "allocation threshold, one store");
        var unmeasured = new PointResult(StoreKind.SPEECH, 10_000, 64, 1, lat(100), lat(100), lat(100), -1, -1, -1);
        var unmeasuredDecision = List.of(point(StoreKind.FREQUENCY, 10_000, 0, 0), unmeasured);
        assertEquals(Decision.INCONCLUSIVE, VectorCopyShareRunner.decide(unmeasuredDecision),
                "allocation criterion never observed for one store");
        assertTrue(VectorCopyShareRunner.rationale(Decision.INCONCLUSIVE, unmeasuredDecision)
                .contains("allocation was not measured"));
        var unmeasuredSlowCopy = new PointResult(StoreKind.SPEECH, 10_000, 64, 1, lat(100), lat(100), lat(90),
                -1, -1, -1);
        assertEquals(Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY, VectorCopyShareRunner.decide(
                List.of(point(StoreKind.FREQUENCY, 10_000, 0, 0), unmeasuredSlowCopy)),
                "an observed time share still justifies removal without allocation data");
        assertTrue(VectorCopyShareRunner.rationale(Decision.INCONCLUSIVE, List.of())
                .contains("largest scale point"));

        var rationale = VectorCopyShareRunner.rationale(Decision.COPY_SHARE_MEANINGFUL_REMOVE_COPY,
                bothStores(10_000, 20, 400, 5, 100));
        assertTrue(rationale.contains("FREQUENCY N=10000: the copy is 20.0%"));
        assertTrue(rationale.contains("SPEECH N=10000"));
        assertTrue(rationale.contains("stage 2) is justified"));
        assertTrue(VectorCopyShareRunner.rationale(Decision.COPY_SHARE_NEGLIGIBLE,
                bothStores(10_000, 1, 1, 1, 1)).contains("not justified"));
    }

    @Test
    void renderListsMetadataAndSections(@TempDir Path tempDir) throws IOException {
        var text = smallRunner(1).run(tempDir).render();
        for (var expected : List.of("Generator Seed: 42", "Frequency Store: FIXED_RAW, 128 dimensions",
                "Speech Store: length-prefixed, 64 dimensions", "Scale Points (N): [33, 60]",
                "Invocation: ./gradlew :monada-evaluation:runVectorCopyShare -q", "1. findAll() Latency",
                "2. Allocated Bytes", "3. Decision", "Decision: INCONCLUSIVE")) {
            assertTrue(text.contains(expected), "missing: " + expected);
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        var data = ExpandedTechnologyDataset.get();
        assertThrows(IllegalArgumentException.class, () -> new VectorCopyShareRunner("d", data, List.of(), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorCopyShareRunner("d", data, List.of(1), 0, 1, 42L));
        assertThrows(IllegalArgumentException.class, () -> new VectorCopyShareRunner("d", data, List.of(50), -1, 1, 42L));
    }
}
