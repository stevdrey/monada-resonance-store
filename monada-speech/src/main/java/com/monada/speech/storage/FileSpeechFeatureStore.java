package com.monada.speech.storage;

import com.monada.core.FrequencyVector;
import com.monada.core.io.ChunkedFloatReader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class FileSpeechFeatureStore implements SpeechFeatureStore {

    private static final String DEFAULT_SEGMENT = "features/speech-features-000001.f32";
    private static final String DEFAULT_INDEX_SEGMENT = "indexes/speech-feature-map.idx";
    private static final int MAX_DIMENSIONS = 1 << 20;

    /**
     * Read window of {@link #findAll()} (Issue #136): one reused heap array per call, so its read buffer is bounded
     * regardless of segment size. Frames larger than this use the per-vector path.
     */
    static final int BULK_WINDOW_BYTES = 1 << 20;

    /**
     * Bytes read when {@link #findAll()} jumps forward past its window (a gap, or the first read), unless the frame
     * needs more. Keeps sparse indexes from reading a full window per entry; a contiguous refill reads to capacity.
     */
    static final int GAP_READ_BYTES = 8 << 10;

    private final Path vectorFile;
    private final Path vectorMap;

    public FileSpeechFeatureStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT, DEFAULT_INDEX_SEGMENT);
    }

    public FileSpeechFeatureStore(Path root, String vectorSegment, String indexSegment) throws IOException {
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = root.resolve(indexSegment);
        Files.createDirectories(vectorFile.getParent());
        Files.createDirectories(vectorMap.getParent());
        if (Files.notExists(vectorFile)) {
            Files.createFile(vectorFile);
        }
        if (Files.notExists(vectorMap)) {
            Files.createFile(vectorMap);
        }
    }

    @Override
    public void save(String sampleId, FrequencyVector vector) throws IOException {
        validateSampleId(sampleId);
        float[] values = vector.values();
        long offset = Files.size(vectorFile);
        try (var output = new DataOutputStream(Files.newOutputStream(vectorFile, StandardOpenOption.APPEND))) {
            output.writeInt(values.length);
            for (var value : values) {
                output.writeFloat(value);
            }
        }
        Files.writeString(vectorMap, sampleId + "\t" + offset + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Override
    public Optional<FrequencyVector> findBySampleId(String sampleId) throws IOException {
        validateSampleId(sampleId);
        var offset = findOffset(sampleId);
        if (offset == null) {
            return Optional.empty();
        }
        var fileSize = Files.size(vectorFile);
        validateOffset(offset, fileSize, sampleId);
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            file.seek(offset);
            return Optional.of(readVector(file, sampleId, offset, new ChunkedFloatReader()));
        }
    }

    /**
     * Returns every active vector (last index entry per sample ID, in first-seen order).
     *
     * <p>The segment is read through one reused window of at most {@value #BULK_WINDOW_BYTES} bytes (the segment size
     * when smaller, with a 4-byte floor for one header) with positional reads, and each frame is decoded at its
     * indexed offset (Issue #136). The window only moves forward: a frame that continues the window keeps its
     * unread tail and refills it to capacity, a forward jump past the window reads at most {@value #GAP_READ_BYTES}
     * bytes or the frame, and no byte is read twice by contiguous refills. Entries whose offset is before the window
     * (out-of-order indexes) and frames larger than the window use the per-vector seek-and-read path, so an
     * append-ordered index needs a handful of reads, any other order still works, and no order rereads a whole
     * window per entry. Entries are validated and
     * read in index order, so results, offset and header checks, truncation messages and the precedence of errors
     * are unchanged. Read buffers are bounded by the window plus the 16 KiB chunk scratch of
     * {@link ChunkedFloatReader}, independent of segment size.
     */
    @Override
    public List<StoredSpeechFeatureVector> findAll() throws IOException {
        return findAll(ReadStats.NONE);
    }

    /** {@link #findAll()} that also counts its reads into {@code stats}; tests use it to pin the I/O structure. */
    List<StoredSpeechFeatureVector> findAll(ReadStats stats) throws IOException {
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        var entries = readIndexEntries();
        var fileSize = Files.size(vectorFile);
        var floatReader = new ChunkedFloatReader();
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            var window = new SegmentWindow(file.getChannel(), windowCapacity(fileSize), stats);
            for (var entry : entries) {
                validateOffset(entry.offset(), fileSize, entry.sampleId());
                var vector = entry.offset() < window.start()
                        ? readPerVector(file, entry.sampleId(), entry.offset(), floatReader, stats)
                        : readFromWindow(window, file, entry.sampleId(), entry.offset(), floatReader, stats);
                byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Validates the dimensions of every active stored vector without materializing float values.
     *
     * <p>The feature index remains append-only, so repeated sample IDs use the last index entry,
     * matching {@link #findAll()}. The returned count is the number of active vectors inspected.
     * This method validates offsets and vector-dimension headers only; callers that need vector
     * values must still use {@link #findAll()} or {@link #findBySampleId(String)}.
     *
     * @param expectedDimensions dimensions required by the caller
     * @return number of active vectors validated, or zero when the feature index is empty
     * @throws IOException if an active index entry or dimension header is invalid
     * @throws IllegalArgumentException if expected dimensions are non-positive or do not match
     */
    public int validateStoredVectorDimensions(int expectedDimensions) throws IOException {
        if (expectedDimensions <= 0) {
            throw new IllegalArgumentException("expected dimensions must be positive: " + expectedDimensions);
        }

        var activeEntries = new LinkedHashMap<String, IndexEntry>();
        for (IndexEntry entry : readIndexEntries()) {
            activeEntries.put(entry.sampleId(), entry);
        }

        var fileSize = Files.size(vectorFile);
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (IndexEntry entry : activeEntries.values()) {
                validateOffset(entry.offset(), fileSize, entry.sampleId());
                file.seek(entry.offset());
                int actualDimensions = readVectorDimensions(file, entry.sampleId(), entry.offset());
                if (actualDimensions != expectedDimensions) {
                    throw new IllegalArgumentException("feature vector dimensions for sample " + entry.sampleId()
                            + " are " + actualDimensions + ", expected " + expectedDimensions);
                }
            }
        }
        return activeEntries.size();
    }

    private Long findOffset(String sampleId) throws IOException {
        try (var lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(FileSpeechFeatureStore::parseEntry)
                    .filter(entry -> entry.sampleId().equals(sampleId))
                    .reduce((first, second) -> second) // keep last (last-wins)
                    .map(IndexEntry::offset)
                    .orElse(null);
        }
    }

    private List<IndexEntry> readIndexEntries() throws IOException {
        var entries = new ArrayList<IndexEntry>();
        try (var lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank())
                    .map(FileSpeechFeatureStore::parseEntry)
                    .forEach(entry -> entries.add(entry));
        }
        return entries;
    }

    private static IndexEntry parseEntry(String line) {
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

    /** Seeks to {@code offset} and reads the frame there with the per-vector {@link RandomAccessFile} path. */
    private FrequencyVector readPerVector(
            RandomAccessFile file, String sampleId, long offset, ChunkedFloatReader floatReader, ReadStats stats)
            throws IOException {
        stats.perVectorRead();
        file.seek(offset);
        return readVector(file, sampleId, offset, floatReader);
    }

    /**
     * Reads the frame at {@code offset} from {@code window}, with the same checks and messages as the per-vector
     * path: a header or values past the end of the segment are a truncation, and the dimension range is checked
     * before anything is allocated. Frames larger than the window are read from {@code file} by the per-vector path.
     */
    private FrequencyVector readFromWindow(
            SegmentWindow window, RandomAccessFile file, String sampleId, long offset, ChunkedFloatReader floatReader,
            ReadStats stats) throws IOException {
        if (!window.cover(offset, Integer.BYTES)) {
            throw truncated(sampleId, offset, new EOFException());
        }
        int dimensions = window.intAt(offset);
        if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
            throw invalidDimensions(dimensions, sampleId, offset);
        }
        long frameBytes = Integer.BYTES + (long) dimensions * Float.BYTES;
        if (frameBytes > window.capacity()) {
            return readPerVector(file, sampleId, offset, floatReader, stats);
        }
        if (!window.cover(offset, (int) frameBytes)) {
            throw truncated(sampleId, offset, new EOFException());
        }
        return FrequencyVector.read(floatReader, window.at(offset + Integer.BYTES), dimensions);
    }

    /** Reads the frame at the current file pointer, which the caller has positioned at {@code offset}. */
    private FrequencyVector readVector(
            RandomAccessFile file, String sampleId, long offset, ChunkedFloatReader floatReader)
            throws IOException {
        int dimensions = readVectorDimensions(file, sampleId, offset);
        try {
            return FrequencyVector.read(floatReader, file, dimensions);
        } catch (EOFException e) {
            throw truncated(sampleId, offset, e);
        }
    }

    private int readVectorDimensions(RandomAccessFile file, String sampleId, long offset) throws IOException {
        try {
            int dimensions = file.readInt();
            if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
                throw invalidDimensions(dimensions, sampleId, offset);
            }
            return dimensions;
        } catch (EOFException e) {
            throw truncated(sampleId, offset, e);
        }
    }

    private static IOException truncated(String sampleId, long offset, EOFException cause) {
        return new IOException(
                "Feature vector segment truncated while reading sampleId=" + sampleId + " at offset=" + offset, cause);
    }

    private static IOException invalidDimensions(int dimensions, String sampleId, long offset) {
        return new IOException(
                "Invalid vector dimensions (" + dimensions + ") for sampleId=" + sampleId + " at offset=" + offset);
    }

    /**
     * The segment size capped at {@value #BULK_WINDOW_BYTES}, so small stores do not allocate a full window, with a
     * floor of one 4-byte header so an empty or tiny truncated segment still gets a usable (4-byte) window.
     */
    private static int windowCapacity(long fileSize) {
        return (int) Math.min(BULK_WINDOW_BYTES, Math.max(fileSize, Integer.BYTES));
    }

    private void validateOffset(long offset, long fileSize, String sampleId) throws IOException {
        if (offset < 0) {
            throw new IOException(
                    "Negative offset (" + offset + ") in feature index for sampleId=" + sampleId);
        }
        if (offset >= fileSize) {
            throw new IOException(
                    "Offset (" + offset + ") is beyond end of feature segment (" + fileSize
                            + " bytes) for sampleId=" + sampleId);
        }
    }

    private static void validateSampleId(String sampleId) {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        if (sampleId.contains("\t") || sampleId.contains("\n") || sampleId.contains("\r")) {
            throw new IllegalArgumentException("sampleId must not contain tab or line breaks");
        }
    }

    private record IndexEntry(String sampleId, long offset) {
    }

    /**
     * Read counters of one {@link #findAll(ReadStats)} call: window refills, positional channel reads (including the
     * one that reports end of segment), bytes read into the window, and frames read by the per-vector path. They
     * describe the structure of the reads only (no timing), so tests can bound rereads and fallbacks
     * deterministically. {@link #NONE} discards everything and is what {@link #findAll()} uses. Not thread-safe.
     */
    static class ReadStats {
        static final ReadStats NONE = new ReadStats() {
            @Override
            void windowFill() {
            }

            @Override
            void channelRead(int bytes) {
            }

            @Override
            void perVectorRead() {
            }
        };

        private long windowFills;
        private long channelReads;
        private long windowBytesRead;
        private long perVectorReads;

        void windowFill() {
            windowFills++;
        }

        void channelRead(int bytes) {
            channelReads++;
            if (bytes > 0) {
                windowBytesRead += bytes;
            }
        }

        void perVectorRead() {
            perVectorReads++;
        }

        long windowFills() {
            return windowFills;
        }

        long channelReads() {
            return channelReads;
        }

        long windowBytesRead() {
            return windowBytesRead;
        }

        long perVectorReads() {
            return perVectorReads;
        }
    }

    /**
     * One reused view of at most {@value #BULK_WINDOW_BYTES} bytes of {@code [start, start + limit)} of the segment,
     * refilled with positional channel reads (which leave the file pointer alone), and a {@link java.io.DataInput}
     * over it positioned per frame so {@link FrequencyVector#read} decodes straight from the window. It only moves
     * forward. Not thread-safe.
     */
    private static final class SegmentWindow extends InputStream {
        private final FileChannel channel;
        private final ReadStats stats;
        private final byte[] bytes;
        private final ByteBuffer buffer;
        private final DataInputStream data = new DataInputStream(this);
        private long start;
        private int limit;
        private int position;

        SegmentWindow(FileChannel channel, int capacity, ReadStats stats) {
            this.channel = channel;
            this.stats = stats;
            this.bytes = new byte[capacity];
            this.buffer = ByteBuffer.wrap(bytes);
        }

        int capacity() {
            return bytes.length;
        }

        /** Segment offset of the first byte in the window; offsets before it are never read through the window. */
        long start() {
            return start;
        }

        /**
         * Makes {@code [offset, offset + length)} available for {@code offset >= start()}, refilling from
         * {@code offset} when it is not already in the window; false when the segment ends first. Bytes of the
         * window at or after {@code offset} are kept, not reread. The refill reads {@code length} bytes when the
         * window already starts at {@code offset} (a frame longer than what is buffered), up to capacity when
         * {@code offset} continues the window, and {@link #GAP_READ_BYTES} or {@code length} after a forward gap.
         * {@code length} must not exceed the window capacity.
         */
        boolean cover(long offset, int length) throws IOException {
            long end = start + limit;
            if (offset >= start && offset + length <= end) {
                return true;
            }
            boolean overlaps = limit > 0 && offset <= end;
            int keep = overlaps ? (int) (end - offset) : 0;
            int target;
            if (overlaps && offset == start) {
                target = length;
            } else if (overlaps) {
                target = bytes.length;
            } else {
                target = Math.min(bytes.length, Math.max(length, GAP_READ_BYTES));
            }
            if (keep > 0) {
                System.arraycopy(bytes, (int) (offset - start), bytes, 0, keep);
            }
            buffer.clear().limit(target).position(keep);
            stats.windowFill();
            while (buffer.hasRemaining()) { // positional reads until the target is buffered or the segment ends
                int read = channel.read(buffer, offset + buffer.position());
                stats.channelRead(read);
                if (read < 0) {
                    break;
                }
            }
            start = offset;
            limit = buffer.position();
            buffer.limit(bytes.length);
            return length <= limit;
        }

        int intAt(long offset) {
            return buffer.getInt((int) (offset - start));
        }

        DataInputStream at(long offset) {
            position = (int) (offset - start);
            return data;
        }

        @Override
        public int read() {
            return position < limit ? bytes[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] target, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (position >= limit) {
                return -1;
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(bytes, position, target, off, n);
            position += n;
            return n;
        }
    }
}
