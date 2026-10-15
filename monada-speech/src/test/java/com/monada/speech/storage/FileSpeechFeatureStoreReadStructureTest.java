package com.monada.speech.storage;

import com.monada.speech.storage.FileSpeechFeatureStore.ReadStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * counters instead of timing: bytes read into the window, window refills and per-vector fallbacks. The parity test
 * pins results and errors; these catch efficiency-only regressions (rereads, needless fallbacks, full-window reads
 * per sparse entry) that leave results unchanged.
 */
class FileSpeechFeatureStoreReadStructureTest {

    private static final String SEGMENT = "features/speech-features-000001.f32";
    private static final String INDEX = "indexes/speech-feature-map.idx";
    private static final int CAPACITY = FileSpeechFeatureStore.BULK_WINDOW_BYTES;
    private static final int GAP = FileSpeechFeatureStore.GAP_READ_BYTES;

    @TempDir
    Path root;

    @Test
    void appendOrderedSegmentSmallerThanTheWindowIsReadExactlyOnce() throws IOException {
        var frames = writeStore(Collections.nCopies(100, 64)); // 26,000 bytes: one gap read, then one refill
        long size = segmentSize();
        assertTrue(size > GAP && size < CAPACITY);

        var stats = findAll(frames.size());
        assertEquals(size, stats.windowBytesRead(), "no window byte may be read twice");
        assertEquals(2, stats.windowFills());
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
        long maxFrame = frameBytes(4_097);

        var stats = findAll(frames.size());
        assertEquals(size, stats.windowBytesRead(), "contiguous refills must keep the unread tail");
        assertEquals(0, stats.perVectorReads());
        // A GAP-sized first read, then refills to capacity that each keep less than one frame of tail.
        long refillBound = 1 + ceilDiv(size - GAP, CAPACITY - maxFrame);
        assertTrue(stats.windowFills() >= ceilDiv(size, CAPACITY), "fills=" + stats.windowFills());
        assertTrue(stats.windowFills() <= refillBound, "fills=" + stats.windowFills() + " bound=" + refillBound);
        assertTrue(stats.channelReads() >= stats.windowFills());
    }

    @Test
    void shuffledIndexNeverReadsAWindowByteTwice() throws IOException {
        var frameDims = new ArrayList<Integer>();
        for (int i = 0; i < 2_000; i++) {
            frameDims.add(1 + (i * 37) % 900);
        }
        var frames = writeStore(frameDims);
        var order = new ArrayList<>(frames);
        Collections.shuffle(order, new Random(136));
        writeIndex(order);
        long size = segmentSize();
        assertTrue(size > CAPACITY, "segment must span several windows");

        var stats = findAll(frames.size());
        // The window only moves forward and keeps its tail, so even a shuffled index stays within one segment read;
        // entries behind the window take the per-vector path instead of rereading a window.
        assertTrue(stats.windowBytesRead() <= size, "window bytes=" + stats.windowBytesRead() + " size=" + size);
        assertTrue(stats.windowBytesRead() <= size + (long) frames.size() * GAP);
        assertTrue(stats.perVectorReads() < frames.size());
    }

    @Test
    void sparseForwardIndexReadsAtMostOneGapReadPerEntry() throws IOException {
        var frames = writeStore(Collections.nCopies(1_000, 997)); // ~4 MB of 3,992-byte frames
        var sparse = new ArrayList<Frame>();
        for (int i = 0; i < frames.size(); i += 64) {
            sparse.add(frames.get(i));
        }
        writeIndex(sparse);

        var stats = findAll(sparse.size());
        assertTrue(stats.windowBytesRead() <= (long) sparse.size() * GAP,
                "window bytes=" + stats.windowBytesRead() + " for " + sparse.size() + " sparse entries");
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
    }

    @Test
    void frameOneValueLargerThanTheWindowUsesOnePerVectorRead() throws IOException {
        int above = (CAPACITY - Integer.BYTES) / Float.BYTES + 1;
        var frames = writeStore(List.of(64, above, 3));
        assertEquals(CAPACITY + Float.BYTES, frames.get(1).bytes());

        var stats = findAll(frames.size());
        assertEquals(1, stats.perVectorReads());
    }

    @Test
    void reversedIndexReadsTheLastFrameThroughTheWindowAndTheRestPerVector() throws IOException {
        int count = 50;
        var frames = writeStore(Collections.nCopies(count, 64));
        writeIndex(frames.reversed());

        var stats = findAll(count);
        assertEquals(count - 1, stats.perVectorReads());
        assertEquals(1, stats.windowFills());
        assertEquals(frames.getLast().bytes(), stats.windowBytesRead());
    }

    @Test
    void publicFindAllCountsNothing() throws IOException {
        writeStore(List.of(64, 3, 777));
        assertEquals(3, new FileSpeechFeatureStore(root).findAll().size());
        var none = ReadStats.NONE;
        assertEquals(List.of(0L, 0L, 0L, 0L),
                List.of(none.windowFills(), none.channelReads(), none.windowBytesRead(), none.perVectorReads()));
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

    private record Frame(String sampleId, long offset, int bytes) {
    }

    /** Writes frames of the given dimensions back to back with an append-ordered index; returns them in order. */
    private List<Frame> writeStore(List<Integer> dimensions) throws IOException {
        new FileSpeechFeatureStore(root);
        long total = dimensions.stream().mapToLong(FileSpeechFeatureStoreReadStructureTest::frameBytes).sum();
        var segment = ByteBuffer.allocate(Math.toIntExact(total));
        var frames = new ArrayList<Frame>(dimensions.size());
        for (int i = 0; i < dimensions.size(); i++) {
            int d = dimensions.get(i);
            frames.add(new Frame("sample-" + i, segment.position(), Math.toIntExact(frameBytes(d))));
            segment.putInt(d);
            for (int v = 0; v < d; v++) {
                segment.putFloat(i * 10_000f + v + 0.5f);
            }
        }
        Files.write(root.resolve(SEGMENT), segment.array());
        writeIndex(frames);
        return frames;
    }

    private void writeIndex(List<Frame> frames) throws IOException {
        var index = new StringBuilder();
        for (var frame : frames) {
            index.append(frame.sampleId()).append('\t').append(frame.offset()).append('\n');
        }
        Files.writeString(root.resolve(INDEX), index, StandardCharsets.UTF_8);
    }

    private long segmentSize() throws IOException {
        return Files.size(root.resolve(SEGMENT));
    }

    private static long frameBytes(int dimensions) {
        return Integer.BYTES + (long) dimensions * Float.BYTES;
    }

    private static long ceilDiv(long a, long b) {
        return Math.ceilDiv(a, b);
    }
}
