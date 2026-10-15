package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.evaluation.ScanPhaseBreakdownReport.PhaseLatency;
import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.Decision;
import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.PointResult;
import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.StructuralCounts;
import com.monada.speech.storage.FileSpeechFeatureStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechFeatureLoadBreakdownRunnerTest {

    private static final String SEGMENT = VectorCopyShareRunner.SPEECH_SEGMENT;
    private static final String INDEX = VectorCopyShareRunner.SPEECH_INDEX;

    private static SpeechFeatureLoadBreakdownRunner smallRunner(int reps) {
        return new SpeechFeatureLoadBreakdownRunner(List.of(64, 128), List.of(33, 60), 0, reps, 42L);
    }

    private static FileSpeechFeatureStore store(Path root) throws IOException {
        return new FileSpeechFeatureStore(root, SEGMENT, INDEX);
    }

    private static PhaseLatency lat(long p50, long p95) {
        return new PhaseLatency(p50, p95);
    }

    private static final StructuralCounts NO_COUNTS = new StructuralCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** A point with a 100/200 ns replica, {@code io} ns of per-vector I/O and a {@code candidate} p50/p95. */
    private static PointResult point(int dims, int n, long io, long candidateP50, long candidateP95) {
        var zero = lat(0, 0);
        return new PointResult(n, dims, 1, zero, zero, zero, zero, lat(io, io), zero, lat(100, 200),
                lat(candidateP50, candidateP95), lat(100, 200), NO_COUNTS);
    }

    @Test
    void reportsEveryDimensionAtEveryScalePointWithDeterministicCounts(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(1).run(tempDir);
        assertEquals(4, report.results().size());
        for (var r : report.results()) {
            assertEquals(1, r.sampleCount());
            var c = r.counts();
            long n = r.corpusSize();
            assertEquals(n, c.indexLinesParsed());
            assertEquals(4 * n, c.seeks(), "seek, header, value and per-vector I/O passes");
            assertEquals(2 * n, c.headerReadCalls());
            assertEquals(2 * n, c.valueReadCalls());
            assertEquals(2 * n * (Integer.BYTES + Float.BYTES * (long) r.dimensions()), c.bytesRead());
            assertEquals(1, c.windowReadCalls(), "whole small segment fits in one window read");
            assertEquals(n * (Integer.BYTES + Float.BYTES * (long) r.dimensions()), c.windowBytesRead());
            assertEquals(0, c.perVectorFallbacks());
            assertEquals(n, c.vectorsMaterialized());
        }
        assertEquals(List.of(64, 64, 128, 128), report.results().stream().map(PointResult::dimensions).toList());
        assertEquals(Decision.INCONCLUSIVE, report.decision(), "N < 10,000");
        var rendered = report.render();
        assertTrue(rendered.contains("runSpeechFeatureLoadBreakdown"));
        assertTrue(rendered.contains("Decision: INCONCLUSIVE"));
    }

    @Test
    void zeroRepetitionsAreInconclusive(@TempDir Path tempDir) throws IOException {
        var report = smallRunner(0).run(tempDir);
        assertEquals(Decision.INCONCLUSIVE, report.decision());
        assertTrue(report.results().stream().allMatch(r -> r.sampleCount() == 0));
    }

    @Test
    void seedingWithDimensionsWritesTheSameBytesAsSave(@TempDir Path bulk, @TempDir Path saved) throws IOException {
        VectorCopyShareRunner.seedSpeechStore(bulk, 3, 7L, 128);
        var store = store(saved);
        var vectors = VectorCopyShareRunner.speechVectors(3, 7L, 128);
        for (int i = 0; i < vectors.size(); i++) {
            store.save(VectorCopyShareRunner.speechSampleId(i), new FrequencyVector(vectors.get(i)));
        }
        for (var file : List.of(SEGMENT, INDEX)) {
            assertArrayEquals(Files.readAllBytes(saved.resolve(file)), Files.readAllBytes(bulk.resolve(file)), file);
        }
        assertArrayEquals(VectorCopyShareRunner.speechVectors(3, 7L).getFirst(),
                VectorCopyShareRunner.speechVectors(3, 7L, 64).getFirst(), "default stays 64 dimensions");
    }

    @Test
    void replicaAndCandidateMatchTheStoreIncludingLastWinsAndReorderedIndexes(@TempDir Path tempDir)
            throws IOException {
        VectorCopyShareRunner.seedSpeechStore(tempDir, 5, 7L, 64);
        VectorCopyShareRunner.seedSpeechStore(tempDir, 3, 8L, 64); // overwrites the first three ids
        var probe = new SpeechFeatureLoadProbe(tempDir, SEGMENT, INDEX, 64);
        assertParity(tempDir, probe);

        Path index = tempDir.resolve(INDEX);
        Files.write(index, Files.readAllLines(index).reversed(), StandardCharsets.UTF_8);
        var counters = new SpeechFeatureLoadProbe.Counters();
        assertParity(tempDir, probe);
        probe.windowedCandidateFindAll(counters);
        assertEquals(8, counters.perVectorFallbacks, "out-of-order index uses the per-vector path");
        assertEquals(0, counters.windowReadCalls);
    }

    @Test
    void candidateRefillsTheWindowAcrossALargeSegment(@TempDir Path tempDir) throws IOException {
        int dims = 1_000; // 4,004-byte frames do not divide the window, so frames straddle its boundary
        int n = 3 * SpeechFeatureLoadProbe.WINDOW_BYTES / (Integer.BYTES + dims * Float.BYTES);
        VectorCopyShareRunner.seedSpeechStore(tempDir, n, 9L, dims);
        var probe = new SpeechFeatureLoadProbe(tempDir, SEGMENT, INDEX, dims);
        var counters = new SpeechFeatureLoadProbe.Counters();
        var expected = VectorCopyProbe.fromSpeechStore(store(tempDir).findAll());
        VectorCopyProbe.verifyParity("windowed", expected,
                VectorCopyProbe.fromSpeechStore(probe.windowedCandidateFindAll(counters)));
        assertTrue(counters.windowReadCalls >= 3, "segment spans at least three windows");
        assertEquals(0, counters.perVectorFallbacks);
    }

    @Test
    void decisionBranches() {
        assertEquals(Decision.PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 10_000, 40, 75, 150),
                        point(128, 10_000, 60, 50, 100))));
        assertEquals(Decision.PER_VECTOR_IO_NOT_DOMINANT,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 10_000, 39, 50, 100),
                        point(128, 10_000, 60, 50, 100))), "I/O below 40% in one dimension");
        assertEquals(Decision.PER_VECTOR_IO_NOT_DOMINANT,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 10_000, 60, 76, 100),
                        point(128, 10_000, 60, 50, 100))), "candidate p50 above 75%");
        assertEquals(Decision.PER_VECTOR_IO_NOT_DOMINANT,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 10_000, 60, 50, 151))),
                "candidate p95 above 75%");
        assertEquals(Decision.INCONCLUSIVE,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 10_000, 60, 50, 100),
                        point(128, 5_000, 60, 50, 100))), "a dimension below N=10,000");
        assertEquals(Decision.INCONCLUSIVE, SpeechFeatureLoadBreakdownRunner.decide(List.of()));
        // Only the largest point per dimension decides; a smaller failing point does not.
        assertEquals(Decision.PER_VECTOR_IO_DOMINANT_WINDOWED_READ_JUSTIFIED,
                SpeechFeatureLoadBreakdownRunner.decide(List.of(point(64, 100, 1, 99, 199),
                        point(64, 10_000, 60, 50, 100))));
        var rationale = SpeechFeatureLoadBreakdownRunner.rationale(Decision.PER_VECTOR_IO_NOT_DOMINANT,
                List.of(point(64, 10_000, 39, 50, 100)));
        assertTrue(rationale.contains("64 dims N=10000: per-vector I/O 39.0%"), rationale);
        assertFalse(rationale.contains("is justified"), rationale);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechFeatureLoadBreakdownRunner(List.of(), List.of(10), 0, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechFeatureLoadBreakdownRunner(List.of(64, 64), List.of(10), 0, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechFeatureLoadBreakdownRunner(List.of(64), List.of(0), 0, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechFeatureLoadBreakdownRunner(List.of(64), List.of(10), -1, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechFeatureLoadBreakdownRunner(List.of(64), List.of(10), 0, -1, 1L));
    }

    private static void assertParity(Path root, SpeechFeatureLoadProbe probe) throws IOException {
        var expected = VectorCopyProbe.fromSpeechStore(store(root).findAll());
        VectorCopyProbe.verifyParity("replica", expected, VectorCopyProbe.fromSpeechStore(probe.replicaFindAll()));
        VectorCopyProbe.verifyParity("windowed", expected,
                VectorCopyProbe.fromSpeechStore(probe.windowedCandidateFindAll(new SpeechFeatureLoadProbe.Counters())));
        var entries = probe.parseIndex(new SpeechFeatureLoadProbe.Counters());
        VectorCopyProbe.verifyParity("materialize", expected, VectorCopyProbe.fromSpeechStore(
                probe.materialize(entries, Files.readAllBytes(probe.vectorFile()), new SpeechFeatureLoadProbe.Counters())));
    }
}
