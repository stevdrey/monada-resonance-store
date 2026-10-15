package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.core.io.ChunkedFloatReader;
import com.monada.evaluation.SpeechFeatureLoadBreakdownReport.StructuralCounts;
import com.monada.speech.storage.StoredSpeechFeatureVector;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Evaluation-only reimplementation of the {@code FileSpeechFeatureStore.findAll()} read path (Issue #136) over the
 * persisted feature index and segment, split into separately runnable sub-phases: index parse, seek, dimension
 * header read, value read, per-vector I/O (all three together) and in-memory materialization.
 *
 * <p>Each split pass does only its own work so its cost can be timed in isolation; the passes are not an exact
 * decomposition of a combined load (the header and value passes each include their own seek). Two combined loads
 * are provided: a replica of the pre-#136 per-vector path and a windowed candidate that reads the segment in
 * bounded sequential windows of {@link #WINDOW_BYTES} and decodes frames at the indexed offsets. Both must return
 * the same vectors as {@code FileSpeechFeatureStore.findAll()} (same ids, same last-wins order, bit-identical
 * floats), which callers check with {@link VectorCopyProbe#verifyParity}. The split passes assume every frame has
 * the seeded {@code dimensions}; the combined loads read the header of each frame like production.
 */
final class SpeechFeatureLoadProbe {

    /** Maximum window of the candidate; one reused heap array, never larger than the segment or 1 MiB. */
    static final int WINDOW_BYTES = 1 << 20;

    private static final int MAX_DIMENSIONS = 1 << 20;

    private final Path vectorFile;
    private final Path vectorMap;
    private final int dimensions;

    SpeechFeatureLoadProbe(Path root, String vectorSegment, String indexSegment, int dimensions) {
        Objects.requireNonNull(root, "root");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0");
        }
        this.vectorFile = root.resolve(Objects.requireNonNull(vectorSegment, "vectorSegment"));
        this.vectorMap = root.resolve(Objects.requireNonNull(indexSegment, "indexSegment"));
        this.dimensions = dimensions;
    }

    record IndexEntry(String sampleId, long offset) {
    }

    /** Machine-independent counters; each pass increments only its own fields. */
    static final class Counters {
        long indexLinesParsed;
        long seeks;
        long headerReadCalls;
        long valueReadCalls;
        long bytesRead;
        long windowReadCalls;
        long windowBytesRead;
        long perVectorFallbacks;
        long vectorsMaterialized;

        StructuralCounts toCounts() {
            return new StructuralCounts(indexLinesParsed, seeks, headerReadCalls, valueReadCalls, bytesRead,
                    windowReadCalls, windowBytesRead, perVectorFallbacks, vectorsMaterialized);
        }
    }

    Path vectorFile() {
        return vectorFile;
    }

    /** Index parse: same blank-line filter and tab/offset parse as {@code FileSpeechFeatureStore.readIndexEntries()}. */
    List<IndexEntry> parseIndex(Counters counters) throws IOException {
        try (Stream<String> lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            var entries = new ArrayList<IndexEntry>();
            lines.filter(line -> !line.isBlank())
                    .map(SpeechFeatureLoadProbe::parseEntry)
                    .forEach(entries::add);
            counters.indexLinesParsed += entries.size();
            return entries;
        }
    }

    /** Seek only: position at every indexed offset without reading; returns the sum of file pointers. */
    long seekOnly(List<IndexEntry> entries, Counters counters) throws IOException {
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                checksum += file.getFilePointer();
                counters.seeks++;
            }
        }
        return checksum;
    }

    /** Throws unless the seek pass visited every indexed offset (sum of file pointers). */
    static void verifySeeks(List<IndexEntry> entries, long seekChecksum) {
        long expected = 0;
        for (var entry : entries) {
            expected += entry.offset();
        }
        if (expected != seekChecksum) {
            throw new IllegalStateException("seek pass visited offsets summing to " + seekChecksum
                    + ", expected " + expected);
        }
    }

    /** Seek + dimension header: one {@code readInt} per entry; returns the sum of headers. */
    long headerRead(List<IndexEntry> entries, Counters counters) throws IOException {
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                checksum += file.readInt();
                counters.seeks++;
                counters.headerReadCalls++;
                counters.bytesRead += Integer.BYTES;
            }
        }
        return checksum;
    }

    /**
     * Seek past the header + raw value bytes: one {@code readFully} of {@code dimensions} floats per entry into a
     * reused scratch, with no decode or allocation. Returns the sum of the last value byte of every frame.
     */
    long valueRead(List<IndexEntry> entries, Counters counters) throws IOException {
        var scratch = new byte[dimensions * Float.BYTES];
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset() + Integer.BYTES);
                file.readFully(scratch);
                checksum += scratch[scratch.length - 1];
                counters.seeks++;
                counters.valueReadCalls++;
                counters.bytesRead += scratch.length;
            }
        }
        return checksum;
    }

    /** The I/O of the per-vector path without decode: seek, {@code readInt} and {@code readFully} per entry. */
    long perVectorIo(List<IndexEntry> entries, Counters counters) throws IOException {
        var scratch = new byte[dimensions * Float.BYTES];
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                checksum += file.readInt();
                file.readFully(scratch);
                checksum += scratch[scratch.length - 1];
                counters.seeks++;
                counters.headerReadCalls++;
                counters.valueReadCalls++;
                counters.bytesRead += Integer.BYTES + scratch.length;
            }
        }
        return checksum;
    }

    /**
     * Materialization only: from the segment bytes already in memory, read each header, decode the values with
     * {@link FrequencyVector#read} and build the last-wins result exactly like production, with no file I/O.
     */
    List<StoredSpeechFeatureVector> materialize(List<IndexEntry> entries, byte[] segmentBytes, Counters counters)
            throws IOException {
        var window = new ByteWindow(segmentBytes);
        var header = ByteBuffer.wrap(segmentBytes);
        var reader = new ChunkedFloatReader();
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        for (var entry : entries) {
            int offset = Math.toIntExact(entry.offset());
            int dims = header.getInt(offset);
            var vector = FrequencyVector.read(reader, window.at(offset + Integer.BYTES), dims);
            byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
            counters.vectorsMaterialized++;
        }
        return List.copyOf(byId.values());
    }

    /** Combined replica of the pre-#136 per-vector path: parse, then per entry seek, header and chunked values. */
    List<StoredSpeechFeatureVector> replicaFindAll() throws IOException {
        var entries = parseIndex(new Counters());
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        var reader = new ChunkedFloatReader();
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                int dims = requireDimensions(file.readInt(), entry);
                var vector = FrequencyVector.read(reader, file, dims);
                byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Windowed candidate: when the indexed offsets are non-decreasing, read the segment through one reused
     * {@link #WINDOW_BYTES} window with positional channel reads and decode every frame from the window; a frame
     * that is not fully inside the window triggers a refill starting at its offset. Out-of-order indexes and
     * frames larger than the window use the per-vector path, so memory stays bounded and no index order rereads
     * a whole window per entry.
     */
    List<StoredSpeechFeatureVector> windowedCandidateFindAll(Counters counters) throws IOException {
        var entries = parseIndex(new Counters());
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        var reader = new ChunkedFloatReader();
        boolean sequential = offsetsNonDecreasing(entries);
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            int capacity = (int) Math.min(WINDOW_BYTES, Math.max(file.length(), Integer.BYTES));
            var window = sequential ? new ChannelWindow(file.getChannel(), capacity, counters) : null;
            for (var entry : entries) {
                long offset = entry.offset();
                FrequencyVector vector;
                if (window != null && window.cover(offset, Integer.BYTES)) {
                    int dims = requireDimensions(window.intAt(offset), entry);
                    long frameBytes = Integer.BYTES + (long) dims * Float.BYTES;
                    if (frameBytes <= capacity) {
                        if (!window.cover(offset, (int) frameBytes)) {
                            throw new EOFException("frame past end of segment for sampleId=" + entry.sampleId());
                        }
                        vector = FrequencyVector.read(reader, window.at(offset + Integer.BYTES), dims);
                    } else {
                        counters.perVectorFallbacks++;
                        file.seek(offset + Integer.BYTES);
                        vector = FrequencyVector.read(reader, file, dims);
                    }
                } else {
                    counters.perVectorFallbacks++;
                    file.seek(offset);
                    vector = FrequencyVector.read(reader, file, requireDimensions(file.readInt(), entry));
                }
                byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
            }
        }
        return List.copyOf(byId.values());
    }

    static boolean offsetsNonDecreasing(List<IndexEntry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).offset() < entries.get(i - 1).offset()) {
                return false;
            }
        }
        return true;
    }

    private static int requireDimensions(int dims, IndexEntry entry) throws IOException {
        if (dims <= 0 || dims > MAX_DIMENSIONS) {
            throw new IOException("Invalid vector dimensions (" + dims + ") for sampleId=" + entry.sampleId());
        }
        return dims;
    }

    static IndexEntry parseEntry(String line) {
        var parts = line.split("\t", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed feature index entry: " + line);
        }
        try {
            return new IndexEntry(parts[0], Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid offset in feature index entry: " + line, e);
        }
    }

    /** A positionable {@link java.io.DataInput} over a byte array; one instance is reused for every frame. */
    private static class ByteWindow extends InputStream {
        final byte[] bytes;
        int limit;
        private int position;
        private final DataInputStream data = new DataInputStream(this);

        ByteWindow(byte[] bytes) {
            this.bytes = bytes;
            this.limit = bytes.length;
        }

        DataInputStream at(int index) {
            position = index;
            return data;
        }

        @Override
        public int read() {
            return position < limit ? bytes[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (position >= limit) {
                return -1;
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(bytes, position, b, off, n);
            position += n;
            return n;
        }
    }

    /** A {@link ByteWindow} refilled from a channel with positional reads; covers {@code [start, start + limit)}. */
    private static final class ChannelWindow extends ByteWindow {
        private final FileChannel channel;
        private final ByteBuffer buffer;
        private final Counters counters;
        private long start;

        ChannelWindow(FileChannel channel, int capacity, Counters counters) {
            super(new byte[capacity]);
            this.channel = channel;
            this.buffer = ByteBuffer.wrap(bytes);
            this.counters = counters;
            this.limit = 0;
        }

        /** True when {@code [offset, offset + length)} is in the window after at most one refill at {@code offset}. */
        boolean cover(long offset, int length) throws IOException {
            if (offset >= start && offset + length <= start + limit) {
                return true;
            }
            buffer.clear();
            start = offset;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, offset + buffer.position());
                if (n < 0) {
                    break;
                }
                counters.windowReadCalls++;
                counters.windowBytesRead += n;
            }
            limit = buffer.position();
            return length <= limit;
        }

        int intAt(long offset) {
            return buffer.getInt((int) (offset - start));
        }

        DataInputStream at(long offset) {
            return at((int) (offset - start));
        }
    }
}
