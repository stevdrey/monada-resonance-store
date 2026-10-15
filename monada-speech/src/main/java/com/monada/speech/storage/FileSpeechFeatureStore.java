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
     * <p>When the index offsets are non-decreasing (the append order {@link #save} produces), the segment is read
     * sequentially through one reused window of at most {@value #BULK_WINDOW_BYTES} bytes (never more than the
     * segment) with positional reads, and each frame is decoded at its indexed offset; a frame that is not fully
     * inside the window triggers a refill starting at that frame (Issue #136). Out-of-order indexes and frames larger than the window use the
     * per-vector seek-and-read path. Either way entries are validated and read in index order, so results, offset
     * and header checks, truncation messages and the precedence of errors are unchanged. Read buffers are bounded
     * by the window plus the 16 KiB chunk scratch of {@link ChunkedFloatReader}, independent of segment size.
     */
    @Override
    public List<StoredSpeechFeatureVector> findAll() throws IOException {
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        var entries = readIndexEntries();
        var fileSize = Files.size(vectorFile);
        var floatReader = new ChunkedFloatReader();
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            var window = offsetsNonDecreasing(entries)
                    ? new SegmentWindow(file.getChannel(), windowCapacity(fileSize))
                    : null;
            for (var entry : entries) {
                validateOffset(entry.offset(), fileSize, entry.sampleId());
                var vector = window == null
                        ? readVectorAt(file, entry.sampleId(), entry.offset(), floatReader)
                        : readVector(window, file, entry.sampleId(), entry.offset(), floatReader);
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

    private FrequencyVector readVectorAt(
            RandomAccessFile file, String sampleId, long offset, ChunkedFloatReader floatReader) throws IOException {
        file.seek(offset);
        return readVector(file, sampleId, offset, floatReader);
    }

    /**
     * Reads the frame at {@code offset} from {@code window}, with the same checks and messages as the per-vector
     * path: a header or values past the end of the segment are a truncation, and the dimension range is checked
     * before anything is allocated. Frames larger than the window are read from {@code file} by the per-vector path.
     */
    private FrequencyVector readVector(
            SegmentWindow window, RandomAccessFile file, String sampleId, long offset, ChunkedFloatReader floatReader)
            throws IOException {
        if (!window.cover(offset, Integer.BYTES)) {
            throw truncated(sampleId, offset, new EOFException());
        }
        int dimensions = window.intAt(offset);
        if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
            throw invalidDimensions(dimensions, sampleId, offset);
        }
        long frameBytes = Integer.BYTES + (long) dimensions * Float.BYTES;
        if (frameBytes > window.capacity()) {
            return readVectorAt(file, sampleId, offset, floatReader);
        }
        if (!window.cover(offset, (int) frameBytes)) {
            throw truncated(sampleId, offset, new EOFException());
        }
        return FrequencyVector.read(floatReader, window.at(offset + Integer.BYTES), dimensions);
    }

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

    /** The window never exceeds the segment, so small stores do not allocate the full {@value #BULK_WINDOW_BYTES}. */
    private static int windowCapacity(long fileSize) {
        return (int) Math.min(BULK_WINDOW_BYTES, Math.max(fileSize, Integer.BYTES));
    }

    private static boolean offsetsNonDecreasing(List<IndexEntry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).offset() < entries.get(i - 1).offset()) {
                return false;
            }
        }
        return true;
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
     * One reused view of at most {@value #BULK_WINDOW_BYTES} bytes of {@code [start, start + limit)} of the segment, refilled
     * with positional channel reads (which leave the file pointer alone), and a {@link java.io.DataInput} over it
     * positioned per frame so {@link FrequencyVector#read} decodes straight from the window. Not thread-safe.
     */
    private static final class SegmentWindow extends InputStream {
        private final FileChannel channel;
        private final byte[] bytes;
        private final ByteBuffer buffer;
        private final DataInputStream data = new DataInputStream(this);
        private long start;
        private int limit;
        private int position;

        SegmentWindow(FileChannel channel, int capacity) {
            this.channel = channel;
            this.bytes = new byte[capacity];
            this.buffer = ByteBuffer.wrap(bytes);
        }

        int capacity() {
            return bytes.length;
        }

        /**
         * Makes {@code [offset, offset + length)} available, refilling from {@code offset} when it is not already
         * in the window; false when the segment ends first. {@code length} must not exceed the window.
         */
        boolean cover(long offset, int length) throws IOException {
            if (offset >= start && offset + length <= start + limit) {
                return true;
            }
            buffer.clear();
            start = offset;
            while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) >= 0) {
                // positional reads until the window is full or the segment ends
            }
            limit = buffer.position();
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
