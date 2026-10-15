package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.evaluation.VectorLoadBreakdownReport.StructuralCounts;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.StoredVector;
import com.monada.storage.VectorFormatProfile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Evaluation-only reimplementation of the {@code FileFrequencyStore.findAll()} read path over the
 * persisted vector map and segment files, split into separately runnable sub-phases: index parse,
 * seek, per-float unbuffered read (the read strategy of {@code FileFrequencyStore.findAll()} before
 * Issue #130), bulk frame read, float decode, and materialization.
 *
 * <p>Each split pass does only its own work so its cost can be timed in isolation; the passes are
 * not an exact decomposition of a combined load. Two combined loads are provided as references: a
 * replica of the per-float read path and a bulk-read candidate. Both must return the same vectors as
 * {@link FileFrequencyStore#findAll()} (same ids, same order, bit-identical floats), which callers
 * check with {@link #verifyParity}. Only profiled stores (declared dimensions) are supported.
 */
final class VectorLoadProbe {

    private final Path vectorFile;
    private final Path vectorMap;
    private final int dimensions;
    private final boolean lengthPrefixed;
    private final int frameBytes;

    VectorLoadProbe(Path root, String vectorSegment, VectorFormatProfile profile) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(vectorSegment, "vectorSegment");
        Objects.requireNonNull(profile, "profile");
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = FileFrequencyStore.resolveVectorMapPath(root);
        this.dimensions = profile.dimensions();
        this.lengthPrefixed = profile.framing() == VectorFormatProfile.Framing.LEGACY_LENGTH_PREFIXED;
        this.frameBytes = Math.toIntExact(profile.frameSizeInBytes());
    }

    record IndexEntry(String atomId, long offset) {
    }

    /** Machine-independent counters; each split pass increments only its own fields. */
    static final class Counters {
        long indexLinesParsed;
        long seeks;
        long perFloatReadCalls;
        long perFloatBytesRead;
        long bulkReadCalls;
        long bulkBytesRead;
        long floatsDecoded;
        long arraysAllocated;
        long objectsAllocated;

        StructuralCounts toCounts() {
            return new StructuralCounts(indexLinesParsed, seeks, perFloatReadCalls, perFloatBytesRead,
                    bulkReadCalls, bulkBytesRead, floatsDecoded, arraysAllocated, objectsAllocated);
        }
    }

    int dimensions() {
        return dimensions;
    }

    Path vectorFile() {
        return vectorFile;
    }

    /** Index parse: same line filtering and tab/offset parsing as the production vector map reader. */
    List<IndexEntry> parseIndex(Counters counters) throws IOException {
        try (Stream<String> lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            var entries = lines
                    .filter(line -> !line.isBlank())
                    .map(VectorLoadProbe::parseEntry)
                    .toList();
            counters.indexLinesParsed += entries.size();
            return entries;
        }
    }

    /** Seek only: open the segment and position at every indexed offset without reading. */
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

    /**
     * Per-float unbuffered read: seek, then {@code readInt} (length prefix) and {@code readFloat} per
     * value on a {@link RandomAccessFile}, into one reused array (no per-vector allocation). Returns
     * the sum of raw float bits so the work cannot be eliminated and can be cross-checked.
     */
    long perFloatRead(List<IndexEntry> entries, Counters counters) throws IOException {
        var values = new float[dimensions];
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                if (lengthPrefixed) {
                    requireDimensions(file.readInt(), entry);
                    counters.perFloatReadCalls++;
                    counters.perFloatBytesRead += Integer.BYTES;
                }
                for (int i = 0; i < dimensions; i++) {
                    values[i] = file.readFloat();
                }
                counters.perFloatReadCalls += dimensions;
                counters.perFloatBytesRead += (long) dimensions * Float.BYTES;
                checksum += rawBitsSum(values, 0, dimensions);
            }
        }
        return checksum;
    }

    /**
     * Bulk raw byte read: seek, then one {@code readFully} of the whole frame into a reused buffer.
     * When {@code capture} is non-null (parity pass only, never timed), frame {@code e} is also copied
     * to {@code capture[e * frameBytes]} so it can be checked with {@link #verifyBulkFrames}.
     */
    long bulkRead(List<IndexEntry> entries, Counters counters, byte[] capture) throws IOException {
        var frame = new byte[frameBytes];
        long checksum = 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (int e = 0; e < entries.size(); e++) {
                file.seek(entries.get(e).offset());
                file.readFully(frame);
                counters.bulkReadCalls++;
                counters.bulkBytesRead += frameBytes;
                checksum += frame[frameBytes - 1];
                if (capture != null) {
                    System.arraycopy(frame, 0, capture, e * frameBytes, frameBytes);
                }
            }
        }
        return checksum;
    }

    /** Bytes needed to capture every frame of {@code entries} with {@link #bulkRead}. */
    int captureSize(List<IndexEntry> entries) {
        return Math.multiplyExact(entries.size(), frameBytes);
    }

    /**
     * Throws unless each captured frame equals the segment bytes at its indexed offset, proving the bulk
     * read pass read the right bytes for the right entries.
     */
    void verifyBulkFrames(List<IndexEntry> entries, byte[] segmentBytes, byte[] captured) {
        for (int e = 0; e < entries.size(); e++) {
            int offset = Math.toIntExact(entries.get(e).offset());
            if (!Arrays.equals(captured, e * frameBytes, (e + 1) * frameBytes,
                    segmentBytes, offset, offset + frameBytes)) {
                throw new IllegalStateException("bulk read diverged from segment bytes for atomId="
                        + entries.get(e).atomId() + " at offset=" + offset);
            }
        }
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

    /**
     * Float decode only: big-endian decode of every indexed frame from segment bytes already in memory
     * into {@code target} (index order, {@code dimensions} floats per entry). Returns the sum of raw
     * float bits, which must equal {@link #perFloatRead}'s for the same entries.
     */
    long decode(List<IndexEntry> entries, byte[] segmentBytes, float[] target, Counters counters) {
        int valueOffset = lengthPrefixed ? Integer.BYTES : 0;
        int valueBytes = dimensions * Float.BYTES;
        for (int e = 0; e < entries.size(); e++) {
            int start = Math.toIntExact(entries.get(e).offset()) + valueOffset;
            ByteBuffer.wrap(segmentBytes, start, valueBytes).order(ByteOrder.BIG_ENDIAN)
                    .asFloatBuffer().get(target, e * dimensions, dimensions);
            counters.floatsDecoded += dimensions;
        }
        return rawBitsSum(target, 0, entries.size() * dimensions);
    }

    /**
     * Materialization only: per entry a new {@code float[]} filled from decoded values, a
     * {@link FrequencyVector} (which takes its own defensive copy) and a {@link StoredVector}.
     */
    List<StoredVector> materialize(List<IndexEntry> entries, float[] decoded, Counters counters) {
        var vectors = new ArrayList<StoredVector>(entries.size());
        for (int e = 0; e < entries.size(); e++) {
            var values = new float[dimensions];
            System.arraycopy(decoded, e * dimensions, values, 0, dimensions);
            vectors.add(new StoredVector(entries.get(e).atomId(), new FrequencyVector(values)));
            counters.arraysAllocated += 2;
            counters.objectsAllocated += 2;
        }
        return vectors;
    }

    /** Combined replica of the per-float read path: parse, seek, per-float read, materialize. */
    List<StoredVector> replicaFindAll() throws IOException {
        var entries = parseIndex(new Counters());
        var vectors = new ArrayList<StoredVector>(entries.size());
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                if (lengthPrefixed) {
                    requireDimensions(file.readInt(), entry);
                }
                var values = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    values[i] = file.readFloat();
                }
                vectors.add(new StoredVector(entry.atomId(), new FrequencyVector(values)));
            }
        }
        return vectors;
    }

    /** Combined bulk-read candidate: parse, seek, one frame read, big-endian decode, materialize. */
    List<StoredVector> bulkCandidateFindAll() throws IOException {
        var entries = parseIndex(new Counters());
        var vectors = new ArrayList<StoredVector>(entries.size());
        var frame = new byte[frameBytes];
        int valueOffset = lengthPrefixed ? Integer.BYTES : 0;
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                file.seek(entry.offset());
                file.readFully(frame);
                var buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
                if (lengthPrefixed) {
                    requireDimensions(buffer.getInt(0), entry);
                }
                var values = new float[dimensions];
                buffer.position(valueOffset);
                buffer.asFloatBuffer().get(values);
                vectors.add(new StoredVector(entry.atomId(), new FrequencyVector(values)));
            }
        }
        return vectors;
    }

    /**
     * Throws unless {@code actual} has the same atom ids in the same order as {@code expected} and
     * every float has identical raw bits. Delegates to the store-neutral {@link VectorCopyProbe#verifyParity}.
     */
    static void verifyParity(String what, List<StoredVector> expected, List<StoredVector> actual) {
        VectorCopyProbe.verifyParity(what, "FileFrequencyStore.findAll()",
                VectorCopyProbe.fromFrequencyStore(expected), VectorCopyProbe.fromFrequencyStore(actual));
    }

    static long rawBitsSum(float[] values, int from, int to) {
        long sum = 0;
        for (int i = from; i < to; i++) {
            sum += Float.floatToRawIntBits(values[i]);
        }
        return sum;
    }

    private void requireDimensions(int storedDimensions, IndexEntry entry) throws IOException {
        if (storedDimensions != dimensions) {
            throw new IOException("Length-prefixed vector dimensions (" + storedDimensions
                    + ") do not match profile dimensions (" + dimensions + ") for atomId=" + entry.atomId());
        }
    }

    /** Parses one {@code id\toffset} index line; shared with {@link VectorCopyProbe}. */
    static IndexEntry parseEntry(String line) {
        String[] parts = line.split("\t", 2);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw new IllegalStateException("Malformed vector index entry: " + line);
        }
        try {
            return new IndexEntry(parts[0], Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid offset in vector index entry: " + line, e);
        }
    }
}
