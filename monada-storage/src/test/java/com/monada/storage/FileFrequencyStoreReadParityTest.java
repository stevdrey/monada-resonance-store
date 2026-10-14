package com.monada.storage;

import com.monada.core.FrequencyVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link FileFrequencyStore#findAll()} and {@link FileFrequencyStore#findByAtomId} return
 * exactly what the pre-Issue #130 per-float read path returned (same ids, same order, bit-identical
 * floats) and fail with the same exception type and message on corrupt segments and indexes, for
 * every supported framing. The oracle below is a verbatim copy of that previous read path.
 */
class FileFrequencyStoreReadParityTest {

    private static final String SEGMENT = "vectors/segment-000001.f32";
    private static final int DIMS = 3;

    @TempDir
    Path root;

    /** Every way a store can be opened, with the read parameters the previous path derived from it. */
    enum Mode {
        /** Default constructor: length-prefixed frames, no declared dimensions. */
        LEGACY_UNDECLARED(null, true, 0),
        /** Fixed-dimension constructor: raw frames, no profile. */
        FIXED_DIMENSIONS(DIMS, false, (long) DIMS * Float.BYTES),
        PROFILE_FIXED_RAW(DIMS, false, VectorFormatProfile.currentFixedRaw(DIMS).frameSizeInBytes()),
        PROFILE_LENGTH_PREFIXED(DIMS, true, VectorFormatProfile.legacyLengthPrefixed(DIMS).frameSizeInBytes());

        final Integer declaredDimensions;
        final boolean lengthPrefixed;
        final long frameSize;

        Mode(Integer declaredDimensions, boolean lengthPrefixed, long frameSize) {
            this.declaredDimensions = declaredDimensions;
            this.lengthPrefixed = lengthPrefixed;
            this.frameSize = frameSize;
        }

        FileFrequencyStore create(Path root) throws IOException {
            return switch (this) {
                case LEGACY_UNDECLARED -> new FileFrequencyStore(root);
                case FIXED_DIMENSIONS -> new FileFrequencyStore(root, SEGMENT, DIMS);
                case PROFILE_FIXED_RAW -> FileFrequencyStore.create(root, SEGMENT, VectorFormatProfile.currentFixedRaw(DIMS));
                case PROFILE_LENGTH_PREFIXED ->
                        FileFrequencyStore.create(root, SEGMENT, VectorFormatProfile.legacyLengthPrefixed(DIMS));
            };
        }

        FileFrequencyStore reopen(Path root) throws IOException {
            return switch (this) {
                case LEGACY_UNDECLARED, FIXED_DIMENSIONS -> create(root);
                case PROFILE_FIXED_RAW ->
                        FileFrequencyStore.openExisting(root, SEGMENT, VectorFormatProfile.currentFixedRaw(DIMS));
                case PROFILE_LENGTH_PREFIXED ->
                        FileFrequencyStore.openExisting(root, SEGMENT, VectorFormatProfile.legacyLengthPrefixed(DIMS));
            };
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Happy paths
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Mode.class)
    void arbitraryRawBitPatternsIncludingNanPayloadsAreBitIdentical(Mode mode) throws IOException {
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
        vectors.add(new int[]{0x8000_0000, 0x0000_0001, 0x7f80_0000}); // -0.0, MIN_VALUE, +Inf
        writeRawStore(mode, vectors);

        var store = mode.reopen(root);
        assertSameOutcome(oracleFindAll(mode), outcome(store::findAll));
        assertSameFindByAtomId(mode, store, ids(vectors.size()));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void reorderedIndexIsReadInIndexOrder(Mode mode) throws IOException {
        var store = mode.create(root);
        for (int i = 0; i < 5; i++) {
            store.save("atom-" + i, new FrequencyVector(new float[]{i, -i, i / 3f}));
        }
        Path index = FileFrequencyStore.resolveVectorMapPath(root);
        Files.write(index, Files.readAllLines(index).reversed(), StandardCharsets.UTF_8);

        var reopened = mode.reopen(root);
        var result = assertSameOutcome(oracleFindAll(mode), outcome(reopened::findAll));
        assertEquals("atom-4", result.getFirst().atomId());
        assertSameFindByAtomId(mode, reopened, ids(5));
    }

    @Test
    void undeclaredLengthPrefixedStoreWithVaryingDimensionsIsBitIdentical() throws IOException {
        var store = new FileFrequencyStore(root);
        int[] dims = {1, 3, 2, 7, 1, 5};
        for (int i = 0; i < dims.length; i++) {
            float[] values = new float[dims[i]];
            for (int d = 0; d < dims[i]; d++) {
                values[d] = i * 10 + d + 0.5f;
            }
            store.save("atom-" + i, new FrequencyVector(values));
        }
        var result = assertSameOutcome(oracleFindAll(Mode.LEGACY_UNDECLARED), outcome(store::findAll));
        assertEquals(7, result.get(3).vector().dimensions());
        assertSameFindByAtomId(Mode.LEGACY_UNDECLARED, store, ids(dims.length));
    }

    // ---------------------------------------------------------------------------------------------
    // Corruption: same exception type and message as the previous path
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Mode.class)
    void truncationInsideTheLastFrameValuesFailsIdentically(Mode mode) throws IOException {
        var store = seedThree(mode);
        truncateTo(Files.size(root.resolve(SEGMENT)) - 6);
        var result = assertSameError(oracleFindAll(mode), outcome(store::findAll));
        assertTrue(result.contains("Vector segment truncated while reading atomId=c at offset="), result);
        assertSameFindByAtomId(mode, store, List.of("a", "b", "c"));
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"LEGACY_UNDECLARED", "PROFILE_LENGTH_PREFIXED"})
    void truncationInsideTheLengthPrefixFailsIdentically(Mode mode) throws IOException {
        var store = seedThree(mode);
        long lastOffset = lastOffset();
        truncateTo(lastOffset + 2);
        var result = assertSameError(oracleFindAll(mode), outcome(store::findAll));
        assertTrue(result.contains("Vector segment truncated while reading atomId=c"), result);
        assertSameFindByAtomId(mode, store, List.of("c"));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void negativeOutOfRangeAndMisalignedOffsetsFailIdentically(Mode mode) throws IOException {
        for (var badOffset : List.of("-4", "999999", "1")) {
            var dir = root.resolve("offset" + badOffset);
            var store = seedThree(mode, dir);
            Files.writeString(FileFrequencyStore.resolveVectorMapPath(dir), "bad\t" + badOffset + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            var message = assertSameError(oracleFindAll(mode, dir), outcome(store::findAll));
            assertTrue(message.contains("atomId=bad"), message);
            assertSameFindByAtomId(mode, store, dir, List.of("bad"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"LEGACY_UNDECLARED", "PROFILE_LENGTH_PREFIXED"})
    void corruptLengthPrefixesFailIdentically(Mode mode) throws IOException {
        for (int header : new int[]{2, -1, 0, (1 << 20) + 1, 1 << 20}) {
            var dir = root.resolve("header" + header);
            var store = seedThree(mode, dir);
            try (var file = new RandomAccessFile(dir.resolve(SEGMENT).toFile(), "rw")) {
                file.seek(offsetOf(dir, "b"));
                file.writeInt(header);
            }
            assertSameOutcome(oracleFindAll(mode, dir), outcome(store::findAll));
            assertSameFindByAtomId(mode, store, dir, List.of("a", "b", "c"));
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void malformedIndexLinesFailIdentically(Mode mode) throws IOException {
        var store = seedThree(mode);
        Files.writeString(FileFrequencyStore.resolveVectorMapPath(root), "no-tab\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertSameError(oracleFindAll(mode), outcome(store::findAll));
    }

    // ---------------------------------------------------------------------------------------------
    // Oracle: verbatim pre-#130 read path (per-float RandomAccessFile reads)
    // ---------------------------------------------------------------------------------------------

    private Outcome<List<StoredVector>> oracleFindAll(Mode mode) {
        return oracleFindAll(mode, root);
    }

    private static Outcome<List<StoredVector>> oracleFindAll(Mode mode, Path dir) {
        return outcome(() -> {
            Path vectorFile = dir.resolve(SEGMENT);
            List<String[]> entries = readIndexEntries(dir);
            List<StoredVector> vectors = new ArrayList<>(entries.size());
            long fileSize = Files.size(vectorFile);
            try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
                for (String[] entry : entries) {
                    long offset = Long.parseLong(entry[1]);
                    validateOffset(mode, offset, fileSize, entry[0]);
                    file.seek(offset);
                    vectors.add(new StoredVector(entry[0], readVector(mode, file, entry[0], offset)));
                }
            }
            return vectors;
        });
    }

    private static Outcome<Optional<FrequencyVector>> oracleFindByAtomId(Mode mode, Path dir, String atomId) {
        return outcome(() -> {
            Path vectorFile = dir.resolve(SEGMENT);
            Long offset = readIndexEntries(dir).stream()
                    .filter(entry -> entry[0].equals(atomId))
                    .map(entry -> Long.parseLong(entry[1]))
                    .findFirst()
                    .orElse(null);
            if (offset == null) {
                return Optional.empty();
            }
            validateOffset(mode, offset, Files.size(vectorFile), atomId);
            try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
                file.seek(offset);
                return Optional.of(readVector(mode, file, atomId, offset));
            }
        });
    }

    private static List<String[]> readIndexEntries(Path dir) throws IOException {
        try (Stream<String> lines = Files.lines(FileFrequencyStore.resolveVectorMapPath(dir), StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).map(line -> {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    throw new IllegalStateException("Malformed vector index entry: " + line);
                }
                try {
                    return new String[]{parts[0], Long.toString(Long.parseLong(parts[1].trim()))};
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Invalid offset in vector index entry: " + line, e);
                }
            }).toList();
        }
    }

    private static FrequencyVector readVector(Mode mode, RandomAccessFile file, String atomId, long offset)
            throws IOException {
        try {
            Integer dimensions = mode.declaredDimensions;
            if (mode.lengthPrefixed) {
                int storedDimensions = file.readInt();
                if (dimensions == null) {
                    dimensions = storedDimensions;
                    if (dimensions <= 0 || dimensions > (1 << 20)) {
                        throw new IOException(
                                "Invalid vector dimensions (" + dimensions + ") for atomId=" + atomId
                                        + " at offset=" + offset);
                    }
                } else if (storedDimensions != dimensions) {
                    throw new IOException(
                            "Length-prefixed vector dimensions (" + storedDimensions + ") do not match store "
                                    + "dimensions (" + dimensions + ") for atomId=" + atomId + " at offset=" + offset);
                }
            }
            float[] values = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                values[i] = file.readFloat();
            }
            return new FrequencyVector(values);
        } catch (EOFException e) {
            throw new IOException(
                    "Vector segment truncated while reading atomId=" + atomId + " at offset=" + offset, e);
        }
    }

    private static void validateOffset(Mode mode, long offset, long fileSize, String atomId) throws IOException {
        if (offset < 0) {
            throw new IOException("Negative offset (" + offset + ") in vector index for atomId=" + atomId);
        }
        if (offset >= fileSize) {
            throw new IOException("Offset (" + offset + ") is beyond end of vector segment (" + fileSize
                    + " bytes) for atomId=" + atomId);
        }
        if (mode.declaredDimensions != null && offset % mode.frameSize != 0) {
            throw new IOException("Offset (" + offset + ") is not aligned to frame size (" + mode.frameSize
                    + " bytes) for atomId=" + atomId);
        }
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
    private static List<StoredVector> assertSameOutcome(
            Outcome<List<StoredVector>> expected, Outcome<List<StoredVector>> actual) {
        if (expected.error() != null || actual.error() != null) {
            assertSameError(expected, actual);
            return List.of();
        }
        var e = expected.value();
        var a = actual.value();
        assertEquals(e.size(), a.size());
        for (int i = 0; i < e.size(); i++) {
            assertEquals(e.get(i).atomId(), a.get(i).atomId(), "atom id at index " + i);
            assertBitIdentical(e.get(i).vector(), a.get(i).vector(), e.get(i).atomId());
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

    private void assertSameFindByAtomId(Mode mode, FileFrequencyStore store, List<String> atomIds) {
        assertSameFindByAtomId(mode, store, root, atomIds);
    }

    private static void assertSameFindByAtomId(Mode mode, FileFrequencyStore store, Path dir, List<String> atomIds) {
        for (var atomId : atomIds) {
            var expected = oracleFindByAtomId(mode, dir, atomId);
            var actual = outcome(() -> store.findByAtomId(atomId));
            if (expected.error() != null || actual.error() != null) {
                assertSameError(expected, actual);
                continue;
            }
            assertEquals(expected.value().isPresent(), actual.value().isPresent(), atomId);
            if (expected.value().isPresent()) {
                assertBitIdentical(expected.value().get(), actual.value().get(), atomId);
            } else {
                assertNull(actual.value().orElse(null));
            }
        }
    }

    private static void assertBitIdentical(FrequencyVector expected, FrequencyVector actual, String atomId) {
        float[] e = expected.values();
        float[] a = actual.values();
        assertEquals(e.length, a.length, "dimensions for atomId=" + atomId);
        for (int d = 0; d < e.length; d++) {
            assertEquals(Float.floatToRawIntBits(e[d]), Float.floatToRawIntBits(a[d]),
                    "raw float bits for atomId=" + atomId + " at dimension " + d);
        }
    }

    private FileFrequencyStore seedThree(Mode mode) throws IOException {
        return seedThree(mode, root);
    }

    private static FileFrequencyStore seedThree(Mode mode, Path dir) throws IOException {
        var store = mode.create(dir);
        store.save("a", new FrequencyVector(new float[]{1f, 2f, 3f}));
        store.save("b", new FrequencyVector(new float[]{-4f, 0.25f, 6e10f}));
        store.save("c", new FrequencyVector(new float[]{7f, -0.0f, Float.MIN_VALUE}));
        return store;
    }

    /** Writes segment and index bytes directly so arbitrary raw float bits (e.g. NaN payloads) persist. */
    private void writeRawStore(Mode mode, List<int[]> vectors) throws IOException {
        mode.create(root);
        int frame = (mode.lengthPrefixed ? Integer.BYTES : 0) + DIMS * Float.BYTES;
        var segment = ByteBuffer.allocate(frame * vectors.size());
        var index = new StringBuilder();
        for (int v = 0; v < vectors.size(); v++) {
            index.append("atom-").append(v).append('\t').append(segment.position()).append('\n');
            if (mode.lengthPrefixed) {
                segment.putInt(DIMS);
            }
            for (int bits : vectors.get(v)) {
                segment.putInt(bits);
            }
        }
        Files.write(root.resolve(SEGMENT), segment.array());
        Files.writeString(FileFrequencyStore.resolveVectorMapPath(root), index, StandardCharsets.UTF_8);
    }

    private void truncateTo(long size) throws IOException {
        try (var channel = Files.newByteChannel(root.resolve(SEGMENT), StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
    }

    private long lastOffset() throws IOException {
        return offsetOf(root, "c");
    }

    private static long offsetOf(Path dir, String atomId) throws IOException {
        return readIndexEntries(dir).stream()
                .filter(entry -> entry[0].equals(atomId))
                .mapToLong(entry -> Long.parseLong(entry[1]))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> ids(int count) {
        var ids = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            ids.add("atom-" + i);
        }
        return ids;
    }
}
