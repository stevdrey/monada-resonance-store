package com.monada.speech.storage;

import com.monada.core.FrequencyVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link FileSpeechFeatureStore#findAll()} and {@link FileSpeechFeatureStore#findBySampleId} return
 * exactly what the pre-Issue #132 per-float read path returned (same ids, same order, bit-identical floats)
 * and fail with the same exception type and message on corrupt segments and indexes. The oracle below is a
 * verbatim copy of that previous read path.
 */
class FileSpeechFeatureStoreReadParityTest {

    private static final String SEGMENT = "features/speech-features-000001.f32";
    private static final String INDEX = "indexes/speech-feature-map.idx";
    private static final int MAX_DIMENSIONS = 1 << 20;
    private static final int DIMS = 64;

    @TempDir
    Path root;

    // ---------------------------------------------------------------------------------------------
    // Happy paths
    // ---------------------------------------------------------------------------------------------

    @Test
    void arbitraryRawBitPatternsIncludingNanPayloadsAreBitIdentical() throws IOException {
        var random = new Random(42);
        var vectors = new ArrayList<int[]>();
        for (int v = 0; v < 300; v++) {
            int[] bits = new int[DIMS];
            for (int d = 0; d < DIMS; d++) {
                bits[d] = random.nextInt();
            }
            vectors.add(bits);
        }
        vectors.add(new int[]{0x7fc0_1234, 0xffa0_0001, 0x7f80_0001}); // quiet and signaling NaN payloads
        vectors.add(new int[]{0x8000_0000, 0x0000_0001, 0x7f80_0000, 0xff80_0000}); // -0.0, MIN_VALUE, +Inf, -Inf
        vectors.add(new int[]{0x0000_0000, 0x007f_ffff, 0x8000_0001}); // +0.0 and subnormals
        writeRawStore(vectors);

        var store = new FileSpeechFeatureStore(root);
        assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertSameFindBySampleId(store, ids(vectors.size()));
    }

    @Test
    void duplicateSampleIdsKeepTheLastEntryInFirstSeenOrder() throws IOException {
        var store = new FileSpeechFeatureStore(root);
        store.save("a", new FrequencyVector(new float[]{1f, 2f}));
        store.save("b", new FrequencyVector(new float[]{3f, 4f, 5f}));
        store.save("a", new FrequencyVector(new float[]{6f, 7f, 8f, 9f}));
        store.save("c", new FrequencyVector(new float[]{10f}));
        store.save("b", new FrequencyVector(new float[]{-0.0f}));

        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertEquals(List.of("a", "b", "c"), result.stream().map(StoredSpeechFeatureVector::sampleId).toList());
        assertEquals(4, result.getFirst().vector().dimensions());
        assertSameFindBySampleId(store, List.of("a", "b", "c", "missing"));
    }

    @Test
    void reorderedIndexIsReadInIndexOrder() throws IOException {
        var store = new FileSpeechFeatureStore(root);
        for (int i = 0; i < 5; i++) {
            store.save("sample-" + i, new FrequencyVector(new float[]{i, -i, i / 3f}));
        }
        Path index = root.resolve(INDEX);
        Files.write(index, Files.readAllLines(index).reversed(), StandardCharsets.UTF_8);

        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertEquals("sample-4", result.getFirst().sampleId());
        assertSameFindBySampleId(store, ids(5));
    }

    @Test
    void vectorsSpanningSeveralReadChunksAndMixedDimensionsAreBitIdentical() throws IOException {
        // Values are read in 4,096-float chunks; cover below, at, just above and well above, mixed in one segment.
        var store = new FileSpeechFeatureStore(root);
        int[] dims = {4_095, 4_096, 4_097, 8_193, 1, DIMS, 10_000};
        for (int i = 0; i < dims.length; i++) {
            store.save("sample-" + i, new FrequencyVector(sequence(dims[i], i)));
        }
        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertEquals(8_193, result.get(3).vector().dimensions());
        assertEquals(10_000, result.get(6).vector().dimensions());
        assertSameFindBySampleId(store, ids(dims.length));
    }

    @Test
    void scratchGrowsFromSmallToLargeVectorsBitIdentically() throws IOException {
        // The read scratch is sized to the vector and only grows; cover small-to-large and small-after-large.
        var store = new FileSpeechFeatureStore(root);
        int[] dims = {1, 3, DIMS, 5_000, 2, 4_096};
        for (int i = 0; i < dims.length; i++) {
            store.save("sample-" + i, new FrequencyVector(sequence(dims[i], i)));
        }
        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertEquals(5_000, result.get(3).vector().dimensions());
        assertSameFindBySampleId(store, ids(dims.length));
    }

    @Test
    void emptyStoreMatchesTheOracle() throws IOException {
        var store = new FileSpeechFeatureStore(root);
        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertTrue(result.isEmpty());
        assertSameFindBySampleId(store, List.of("missing"));
    }

    @Test
    void blankIndexLinesAreSkippedIdentically() throws IOException {
        var store = seedThree();
        Path index = root.resolve(INDEX);
        var interleaved = new ArrayList<String>();
        for (var line : Files.readAllLines(index)) {
            interleaved.add("");
            interleaved.add(line);
            interleaved.add("   ");
        }
        Files.write(index, interleaved, StandardCharsets.UTF_8);

        var result = assertSameOutcome(oracleFindAll(), outcome(store::findAll));
        assertEquals(List.of("a", "b", "c"), result.stream().map(StoredSpeechFeatureVector::sampleId).toList());
        assertSameFindBySampleId(store, List.of("a", "b", "c", "missing"));
    }

    // ---------------------------------------------------------------------------------------------
    // Corruption: same exception type and message as the previous path
    // ---------------------------------------------------------------------------------------------

    @Test
    void truncationInsideALaterReadChunkFailsIdentically() throws IOException {
        var store = new FileSpeechFeatureStore(root);
        store.save("a", new FrequencyVector(sequence(3, 0)));
        store.save("b", new FrequencyVector(sequence(10_000, 1)));
        // Keep the header and the first two 4,096-float chunks of "b", cut inside the third one.
        truncateTo(offsetOf("b") + Integer.BYTES + (8_192 + 100) * Float.BYTES);
        var message = assertSameError(oracleFindAll(), outcome(store::findAll));
        assertTrue(message.contains("Feature vector segment truncated while reading sampleId=b at offset="), message);
        assertSameFindBySampleId(store, List.of("a", "b"));
    }

    @Test
    void truncationInsideTheLastVectorValuesFailsIdentically() throws IOException {
        var store = seedThree();
        truncateTo(Files.size(root.resolve(SEGMENT)) - 6);
        var message = assertSameError(oracleFindAll(), outcome(store::findAll));
        assertTrue(message.contains("Feature vector segment truncated while reading sampleId=c at offset="), message);
        assertSameFindBySampleId(store, List.of("a", "b", "c"));
    }

    @Test
    void truncationInsideTheDimensionHeaderFailsIdentically() throws IOException {
        var store = seedThree();
        truncateTo(offsetOf("c") + 2);
        var message = assertSameError(oracleFindAll(), outcome(store::findAll));
        assertTrue(message.contains("Feature vector segment truncated while reading sampleId=c"), message);
        assertSameFindBySampleId(store, List.of("c"));
    }

    @Test
    void corruptDimensionHeadersFailIdentically() throws IOException {
        // 2 shifts the frame (still readable), 2^20 is in range but runs past EOF, the rest are rejected.
        for (int header : new int[]{2, -1, 0, MAX_DIMENSIONS + 1, MAX_DIMENSIONS}) {
            var dir = root.resolve("header" + header);
            var store = seedThree(dir);
            try (var file = new RandomAccessFile(dir.resolve(SEGMENT).toFile(), "rw")) {
                file.seek(offsetOf(dir, "b"));
                file.writeInt(header);
            }
            assertSameOutcome(oracleFindAll(dir), outcome(store::findAll));
            assertSameFindBySampleId(store, dir, List.of("a", "b", "c"));
        }
    }

    @Test
    void negativeAndOutOfRangeOffsetsFailIdentically() throws IOException {
        for (var badOffset : List.of("-4", "999999")) {
            var dir = root.resolve("offset" + badOffset);
            var store = seedThree(dir);
            Files.writeString(dir.resolve(INDEX), "bad\t" + badOffset + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            var message = assertSameError(oracleFindAll(dir), outcome(store::findAll));
            assertTrue(message.contains("sampleId=bad"), message);
            assertSameFindBySampleId(store, dir, List.of("bad"));
        }
    }

    @Test
    void malformedAndNonNumericIndexLinesFailIdentically() throws IOException {
        for (var line : List.of("no-tab", "bad\tnot-a-number")) {
            var dir = root.resolve("index" + line.length());
            var store = seedThree(dir);
            Files.writeString(dir.resolve(INDEX), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            assertSameError(oracleFindAll(dir), outcome(store::findAll));
            assertSameFindBySampleId(store, dir, List.of("a", "bad"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Oracle: verbatim pre-#132 read path (per-float RandomAccessFile reads)
    // ---------------------------------------------------------------------------------------------

    private Outcome<List<StoredSpeechFeatureVector>> oracleFindAll() {
        return oracleFindAll(root);
    }

    private static Outcome<List<StoredSpeechFeatureVector>> oracleFindAll(Path dir) {
        return outcome(() -> {
            Path vectorFile = dir.resolve(SEGMENT);
            var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
            var entries = readIndexEntries(dir);
            var fileSize = Files.size(vectorFile);
            try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
                for (var entry : entries) {
                    validateOffset(entry.offset(), fileSize, entry.sampleId());
                    file.seek(entry.offset());
                    var vector = readVector(file, entry.sampleId(), entry.offset());
                    byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
                }
            }
            return List.copyOf(byId.values());
        });
    }

    private static Outcome<Optional<FrequencyVector>> oracleFindBySampleId(Path dir, String sampleId) {
        return outcome(() -> {
            Path vectorFile = dir.resolve(SEGMENT);
            Long offset;
            try (var lines = Files.lines(dir.resolve(INDEX), StandardCharsets.UTF_8)) {
                offset = lines
                        .filter(line -> !line.isBlank())
                        .map(FileSpeechFeatureStoreReadParityTest::parseEntry)
                        .filter(entry -> entry.sampleId().equals(sampleId))
                        .reduce((first, second) -> second) // keep last (last-wins)
                        .map(IndexEntry::offset)
                        .orElse(null);
            }
            if (offset == null) {
                return Optional.empty();
            }
            var fileSize = Files.size(vectorFile);
            validateOffset(offset, fileSize, sampleId);
            try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
                file.seek(offset);
                return Optional.of(readVector(file, sampleId, offset));
            }
        });
    }

    private static List<IndexEntry> readIndexEntries(Path dir) throws IOException {
        var entries = new ArrayList<IndexEntry>();
        try (var lines = Files.lines(dir.resolve(INDEX), StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank())
                    .map(FileSpeechFeatureStoreReadParityTest::parseEntry)
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

    private static FrequencyVector readVector(RandomAccessFile file, String sampleId, long offset) throws IOException {
        int dimensions = readVectorDimensions(file, sampleId, offset);
        try {
            var values = new float[dimensions];
            for (var i = 0; i < dimensions; i++) {
                values[i] = file.readFloat();
            }
            return new FrequencyVector(values);
        } catch (EOFException e) {
            throw new IOException(
                    "Feature vector segment truncated while reading sampleId=" + sampleId + " at offset=" + offset, e);
        }
    }

    private static int readVectorDimensions(RandomAccessFile file, String sampleId, long offset) throws IOException {
        try {
            int dimensions = file.readInt();
            if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
                throw new IOException(
                        "Invalid vector dimensions (" + dimensions + ") for sampleId=" + sampleId
                                + " at offset=" + offset);
            }
            return dimensions;
        } catch (EOFException e) {
            throw new IOException(
                    "Feature vector segment truncated while reading sampleId=" + sampleId + " at offset=" + offset, e);
        }
    }

    private static void validateOffset(long offset, long fileSize, String sampleId) throws IOException {
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

    private record IndexEntry(String sampleId, long offset) {
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private record Outcome<T>(T value, Throwable error) {
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> Outcome<T> outcome(IoSupplier<T> supplier) {
        try {
            return new Outcome<>(supplier.get(), null);
        } catch (IOException | RuntimeException e) {
            return new Outcome<>(null, e);
        }
    }

    /** Asserts identical results or identical failures; returns the (shared) result list. */
    private static List<StoredSpeechFeatureVector> assertSameOutcome(
            Outcome<List<StoredSpeechFeatureVector>> expected, Outcome<List<StoredSpeechFeatureVector>> actual) {
        if (expected.error() != null || actual.error() != null) {
            assertSameError(expected, actual);
            return List.of();
        }
        var e = expected.value();
        var a = actual.value();
        assertEquals(e.size(), a.size());
        for (int i = 0; i < e.size(); i++) {
            assertEquals(e.get(i).sampleId(), a.get(i).sampleId(), "sample id at index " + i);
            assertBitIdentical(e.get(i).vector(), a.get(i).vector(), e.get(i).sampleId());
        }
        return a;
    }

    /** Asserts both sides failed with the same type, message and cause type; returns the message. */
    private static String assertSameError(Outcome<?> expected, Outcome<?> actual) {
        assertTrue(expected.error() != null, "oracle was expected to fail, got " + expected.value());
        assertTrue(actual.error() != null, "store was expected to fail like the oracle: " + expected.error());
        assertEquals(expected.error().getClass(), actual.error().getClass());
        assertEquals(expected.error().getMessage(), actual.error().getMessage());
        Class<?> expectedCause = expected.error().getCause() == null ? null : expected.error().getCause().getClass();
        Class<?> actualCause = actual.error().getCause() == null ? null : actual.error().getCause().getClass();
        assertEquals(expectedCause, actualCause);
        return actual.error().getMessage();
    }

    private void assertSameFindBySampleId(FileSpeechFeatureStore store, List<String> sampleIds) {
        assertSameFindBySampleId(store, root, sampleIds);
    }

    private static void assertSameFindBySampleId(FileSpeechFeatureStore store, Path dir, List<String> sampleIds) {
        for (var sampleId : sampleIds) {
            var expected = oracleFindBySampleId(dir, sampleId);
            var actual = outcome(() -> store.findBySampleId(sampleId));
            if (expected.error() != null || actual.error() != null) {
                assertSameError(expected, actual);
                continue;
            }
            assertEquals(expected.value().isPresent(), actual.value().isPresent(), sampleId);
            if (expected.value().isPresent()) {
                assertBitIdentical(expected.value().get(), actual.value().get(), sampleId);
            } else {
                assertNull(actual.value().orElse(null));
            }
        }
    }

    private static void assertBitIdentical(FrequencyVector expected, FrequencyVector actual, String sampleId) {
        float[] e = expected.values();
        float[] a = actual.values();
        assertEquals(e.length, a.length, "dimensions for sampleId=" + sampleId);
        for (int d = 0; d < e.length; d++) {
            assertEquals(Float.floatToRawIntBits(e[d]), Float.floatToRawIntBits(a[d]),
                    "raw float bits for sampleId=" + sampleId + " at dimension " + d);
        }
    }

    private FileSpeechFeatureStore seedThree() throws IOException {
        return seedThree(root);
    }

    private static FileSpeechFeatureStore seedThree(Path dir) throws IOException {
        var store = new FileSpeechFeatureStore(dir);
        store.save("a", new FrequencyVector(new float[]{1f, 2f, 3f}));
        store.save("b", new FrequencyVector(new float[]{-4f, 0.25f, 6e10f}));
        store.save("c", new FrequencyVector(new float[]{7f, -0.0f, Float.MIN_VALUE}));
        return store;
    }

    /** Writes segment and index bytes directly so arbitrary raw float bits (e.g. NaN payloads) persist. */
    private void writeRawStore(List<int[]> vectors) throws IOException {
        new FileSpeechFeatureStore(root);
        int bytes = vectors.stream().mapToInt(bits -> Integer.BYTES + bits.length * Float.BYTES).sum();
        var segment = ByteBuffer.allocate(bytes);
        var index = new StringBuilder();
        for (int v = 0; v < vectors.size(); v++) {
            index.append("sample-").append(v).append('\t').append(segment.position()).append('\n');
            segment.putInt(vectors.get(v).length);
            for (int bits : vectors.get(v)) {
                segment.putInt(bits);
            }
        }
        Files.write(root.resolve(SEGMENT), segment.array());
        Files.writeString(root.resolve(INDEX), index, StandardCharsets.UTF_8);
    }

    private void truncateTo(long size) throws IOException {
        try (var channel = Files.newByteChannel(root.resolve(SEGMENT), StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
    }

    private long offsetOf(String sampleId) throws IOException {
        return offsetOf(root, sampleId);
    }

    /** Offset of the last (active) index entry for {@code sampleId}. */
    private static long offsetOf(Path dir, String sampleId) throws IOException {
        return readIndexEntries(dir).stream()
                .filter(entry -> entry.sampleId().equals(sampleId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .offset();
    }

    private static List<String> ids(int count) {
        var ids = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            ids.add("sample-" + i);
        }
        return ids;
    }

    private static float[] sequence(int dimensions, int seed) {
        float[] values = new float[dimensions];
        for (int d = 0; d < dimensions; d++) {
            values[d] = seed * 100_000f + d + 0.25f;
        }
        return values;
    }
}
