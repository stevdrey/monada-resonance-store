package com.monada.speech.storage;

import com.monada.speech.storage.FileSpeechFeatureStore.ReadStats;
import com.monada.speech.storage.SpeechFeatureSegmentFixture.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the I/O structure of the windowed {@link FileSpeechFeatureStore#findAll()} (Issue #136) with deterministic
 * counters instead of timing: bytes read and reread through the window, window refills, and per-vector fallbacks
 * with the bytes they read. The parity test pins results and errors; these catch efficiency-only regressions
 * (rereads, needless fallbacks, full-window reads per sparse entry) that leave results unchanged.
 */
class FileSpeechFeatureStoreReadStructureTest {

    private static final int CAPACITY = FileSpeechFeatureStore.BULK_WINDOW_BYTES;
    private static final int GAP = FileSpeechFeatureStore.GAP_READ_BYTES;

    @TempDir
    Path root;

    @Test
    void appendOrderedSegmentSmallerThanTheWindowIsReadExactlyOnce() throws IOException {
        var frames = writeStore(Collections.nCopies(100, 64)); // 26,000 bytes: the window is the segment
        long size = segmentSize();
        assertTrue(size < CAPACITY);

        var stats = findAll(frames.size());
        assertEquals(size, stats.windowBytesRead(), "the whole segment is read through the window");
        assertEquals(0, stats.windowBytesReread());
        assertFillsWithin(stats, size, size, frames.getFirst().bytes());
        assertEquals(0, stats.perVectorReads());
    }

    @Test
    void appendOrderedMultiWindowSegmentIsReadExactlyOnceWithBoundedRefills() throws IOException {
        // Mixed frame sizes that do not divide the window, so frames straddle every window boundary.
        int[] dims = {1_000, 3, 777, 64, 4_097};
        var frameDims = new ArrayList<Integer>();
        long bytes = 0;
        while (bytes < 5L * CAPACITY / 2) {
            int d = dims[frameDims.size() % dims.length];
            frameDims.add(d);
            bytes += frameBytes(d);
        }
        var frames = writeStore(frameDims);
        long size = segmentSize();

        var stats = findAll(frames.size());
        assertEquals(size, stats.windowBytesRead(), "contiguous refills must keep the unread tail");
        assertEquals(0, stats.windowBytesReread());
        assertFillsWithin(stats, size, CAPACITY, frameBytes(4_097));
        assertTrue(stats.channelReads() >= stats.windowFills());
        assertEquals(0, stats.perVectorReads());
    }

    @Test
    void shuffledIndexNeverReadsAWindowByteTwice() throws IOException {
        var frameDims = new ArrayList<Integer>();
        for (int i = 0; i < 2_000; i++) {
            frameDims.add(1 + (i * 37) % 900);
        }
        var frames = writeStore(frameDims);
        long size = segmentSize();
        assertTrue(size > CAPACITY, "segment must span several windows");
        // Blocks of 8 keep the index nearly sorted, so frames often continue the window (tail carry-over); one block
        // of every frame is a full shuffle, where most entries jump forward or fall behind the window.
        for (int block : new int[]{8, frames.size()}) {
            var random = new Random(136);
            var order = new ArrayList<Frame>(frames.size());
            for (int from = 0; from < frames.size(); from += block) {
                var chunk = new ArrayList<>(frames.subList(from, Math.min(from + block, frames.size())));
                Collections.shuffle(chunk, random);
                order.addAll(chunk);
            }
            SpeechFeatureSegmentFixture.writeIndex(root, order);

            var stats = findAll(frames.size());
            // The window only moves forward and keeps its tail; entries behind it take the per-vector path, which
            // reads exactly their frames.
            assertEquals(0, stats.windowBytesReread(), "block=" + block);
            assertTrue(stats.windowBytesRead() <= size, "block=" + block + " window bytes=" + stats.windowBytesRead());
            assertTrue(stats.windowFills() > 1, "a shuffled index must still use the window");
            assertTrue(stats.perVectorReads() > 0 && stats.perVectorReads() < frames.size(), "block=" + block);
            assertTrue(stats.perVectorBytesRead() <= size, "block=" + block);
        }
    }

    @Test
    void sparseForwardIndexReadsAtMostOneGapReadPerEntry() throws IOException {
        var frames = writeStore(Collections.nCopies(1_000, 997)); // ~4 MB of 3,992-byte frames
        var sparse = new ArrayList<Frame>();
        for (int i = 0; i < frames.size(); i += 64) {
            sparse.add(frames.get(i));
        }
        SpeechFeatureSegmentFixture.writeIndex(root, sparse);

        var stats = findAll(sparse.size());
        assertTrue(stats.windowBytesRead() <= (long) sparse.size() * GAP,
                "window bytes=" + stats.windowBytesRead() + " for " + sparse.size() + " sparse entries");
        assertEquals(0, stats.windowBytesReread());
        assertEquals(sparse.size(), stats.windowFills());
        assertEquals(0, stats.perVectorReads());
    }

    @Test
    void exactlyWindowSizedFrameIsDecodedFromTheWindow() throws IOException {
        int exact = (CAPACITY - Integer.BYTES) / Float.BYTES;
        var frames = writeStore(List.of(64, exact));
        assertEquals(CAPACITY, frames.get(1).bytes());

        var stats = findAll(frames.size());
        assertEquals(0, stats.perVectorReads());
        assertEquals(segmentSize(), stats.windowBytesRead());
        assertEquals(0, stats.windowBytesReread());
    }

    @Test
    void frameOneValueLargerThanTheWindowUsesOnePerVectorReadOfExactlyItsFrame() throws IOException {
        int above = (CAPACITY - Integer.BYTES) / Float.BYTES + 1;
        var frames = writeStore(List.of(64, above, 3));
        assertEquals(CAPACITY + Float.BYTES, frames.get(1).bytes());

        var stats = findAll(frames.size());
        assertEquals(1, stats.perVectorReads());
        assertEquals(frames.get(1).bytes(), stats.perVectorBytesRead());
        assertEquals(0, stats.windowBytesReread());
    }

    @Test
    void reversedIndexReadsTheLastFrameThroughTheWindowAndTheRestPerVector() throws IOException {
        int count = 50;
        var frames = writeStore(Collections.nCopies(count, 64));
        SpeechFeatureSegmentFixture.writeIndex(root, frames.reversed());

        var stats = findAll(count);
        assertEquals(count - 1, stats.perVectorReads());
        assertEquals(segmentSize() - frames.getLast().bytes(), stats.perVectorBytesRead(),
                "each fallback reads exactly its own frame");
        assertEquals(1, stats.windowFills());
        assertEquals(frames.getLast().bytes(), stats.windowBytesRead());
    }

    @Test
    void countersAreDeterministicAndThePublicPathRecordsNothing() throws IOException {
        var frames = writeStore(List.of(64, 3, 777, 4_097, 1));
        var order = new ArrayList<>(frames);
        Collections.shuffle(order, new Random(7));
        SpeechFeatureSegmentFixture.writeIndex(root, order);

        // NONE discards every record, even when called directly.
        var none = ReadStats.NONE;
        none.windowFill();
        none.channelRead(0, 100);
        try (var file = new RandomAccessFile(root.resolve(SpeechFeatureSegmentFixture.SEGMENT).toFile(), "r")) {
            file.seek(40);
            none.perVectorRead(file, 0);
        }
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L), counts(none));

        // Fresh counters give identical counts on every run, and the public findAll() leaves them untouched.
        var first = findAll(frames.size());
        var firstCounts = counts(first);
        new FileSpeechFeatureStore(root).findAll();
        assertEquals(firstCounts, counts(first));
        assertEquals(firstCounts, counts(findAll(frames.size())));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Runs the counted {@code findAll}, checking it returns the same vectors as the public one. */
    private ReadStats findAll(int expectedCount) throws IOException {
        var store = new FileSpeechFeatureStore(root);
        var stats = new ReadStats();
        var counted = store.findAll(stats);
        var expected = store.findAll();
        assertEquals(expectedCount, counted.size());
        assertEquals(expected.stream().map(StoredSpeechFeatureVector::sampleId).toList(),
                counted.stream().map(StoredSpeechFeatureVector::sampleId).toList());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).vector(), counted.get(i).vector());
        }
        return stats;
    }

    /**
     * An append-ordered read of {@code size} bytes needs at least {@code ceil(size / capacity)} fills and at most
     * one gap read of {@link FileSpeechFeatureStore#GAP_READ_BYTES} followed by refills to capacity that each keep
     * less than one frame of tail.
     */
    private static void assertFillsWithin(ReadStats stats, long size, long capacity, long maxFrame) {
        long min = Math.ceilDiv(size, capacity);
        long max = 1 + Math.ceilDiv(Math.max(0, size - GAP), capacity - maxFrame);
        assertTrue(stats.windowFills() >= min && stats.windowFills() <= max,
                "fills=" + stats.windowFills() + " expected within [" + min + ", " + max + "]");
    }

    private static List<Long> counts(ReadStats stats) {
        return List.of(stats.windowFills(), stats.channelReads(), stats.windowBytesRead(),
                stats.windowBytesReread(), stats.perVectorReads(), stats.perVectorBytesRead());
    }

    /** Writes frames of the given dimensions back to back with an append-ordered index; returns them in order. */
    private List<Frame> writeStore(List<Integer> dimensions) throws IOException {
        var rawBits = new ArrayList<int[]>(dimensions.size());
        for (int i = 0; i < dimensions.size(); i++) {
            int[] bits = new int[dimensions.get(i)];
            for (int v = 0; v < bits.length; v++) {
                bits[v] = Float.floatToRawIntBits(i * 10_000f + v + 0.5f);
            }
            rawBits.add(bits);
        }
        return SpeechFeatureSegmentFixture.write(root, rawBits);
    }

    private long segmentSize() throws IOException {
        return Files.size(root.resolve(SpeechFeatureSegmentFixture.SEGMENT));
    }

    private static long frameBytes(int dimensions) {
        return Integer.BYTES + (long) dimensions * Float.BYTES;
    }
}
