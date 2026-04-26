package com.monada.storage;

import com.monada.core.FrequencyVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileFrequencyStoreTest {

    @TempDir
    Path root;

    private FrequencyVector vector(float... values) {
        return new FrequencyVector(values);
    }

    @Test
    void savesAndRetrievesByAtomIdUsingOffset() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f, 2f, 3f));
        store.save("b", vector(4f, 5f));
        store.save("c", vector(6f));

        Optional<FrequencyVector> b = store.findByAtomId("b");
        assertTrue(b.isPresent());
        assertArrayEquals(new float[]{4f, 5f}, b.get().values());
    }

    @Test
    void findByAtomIdReturnsEmptyForUnknownId() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f));
        assertTrue(store.findByAtomId("missing").isEmpty());
    }

    @Test
    void findAllRespectsRecordedOffsetsEvenWhenIndexIsReordered() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f, 1f));
        store.save("b", vector(2f, 2f));
        store.save("c", vector(3f, 3f));

        // Reverse the order of entries in the index file; offsets must still drive the read.
        Path indexFile = root.resolve("indexes/vector-map.idx");
        List<String> originalLines = Files.readAllLines(indexFile);
        List<String> reversed = originalLines.reversed();
        Files.write(indexFile, reversed, StandardCharsets.UTF_8);

        List<StoredVector> all = store.findAll();
        assertEquals(3, all.size());
        assertEquals("c", all.get(0).atomId());
        assertArrayEquals(new float[]{3f, 3f}, all.get(0).vector().values());
        assertEquals("a", all.get(2).atomId());
        assertArrayEquals(new float[]{1f, 1f}, all.get(2).vector().values());
    }

    @Test
    void truncatedSegmentSurfacesAsIOExceptionWithContext() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f, 2f, 3f));
        store.save("b", vector(4f, 5f, 6f));

        Path segment = root.resolve("vectors/segment-000001.f32");
        long size = Files.size(segment);
        // Truncate the second vector.
        try (var ch = Files.newByteChannel(segment, StandardOpenOption.WRITE)) {
            ch.truncate(size - 8);
        }

        IOException ex = assertThrows(IOException.class, store::findAll);
        assertTrue(ex.getMessage().contains("atomId=b"));
        assertTrue(ex.getMessage().contains("offset="));
    }

    @Test
    void malformedIndexEntryFailsFast() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f));

        Path indexFile = root.resolve("indexes/vector-map.idx");
        Files.writeString(indexFile, "broken-line-no-tab" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Malformed vector index entry"));
    }

    @Test
    void fixedDimensionStoreWritesRawFloatsAndEnforcesDimension() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root, "vectors/segment-000001.f32", 3);
        store.save("a", vector(1f, 2f, 3f));
        store.save("b", vector(4f, 5f, 6f));

        // Each vector is exactly dimensions * 4 bytes; no per-entry int header.
        long size = Files.size(root.resolve("vectors/segment-000001.f32"));
        assertEquals(2L * 3 * Float.BYTES, size);

        Optional<FrequencyVector> b = store.findByAtomId("b");
        assertTrue(b.isPresent());
        assertArrayEquals(new float[]{4f, 5f, 6f}, b.get().values());

        assertThrows(IllegalArgumentException.class, () -> store.save("c", vector(1f, 2f)));
    }

    @Test
    void fixedDimensionConstructorRejectsMismatchedSegmentSize() throws IOException {
        // Pre-populate using legacy header mode so segment has a different on-disk layout.
        FileFrequencyStore legacy = new FileFrequencyStore(root);
        legacy.save("a", vector(1f, 2f, 3f, 4f));

        IOException ex = assertThrows(IOException.class,
                () -> new FileFrequencyStore(root, "vectors/segment-000001.f32", 3));
        assertTrue(ex.getMessage().toLowerCase().contains("does not match"));
    }

    @Test
    void legacyHeaderRejectsCorruptDimensions() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f, 2f));

        Path segment = root.resolve("vectors/segment-000001.f32");
        // Overwrite the dimensions header (first 4 bytes) with a negative value.
        try (var raf = new java.io.RandomAccessFile(segment.toFile(), "rw")) {
            raf.seek(0);
            raf.writeInt(-1);
        }

        IOException ex = assertThrows(IOException.class, store::findAll);
        assertTrue(ex.getMessage().toLowerCase().contains("invalid vector dimensions"));
    }

    @Test
    void invalidOffsetFailsFast() throws IOException {
        FileFrequencyStore store = new FileFrequencyStore(root);
        store.save("a", vector(1f));

        Path indexFile = root.resolve("indexes/vector-map.idx");
        Files.writeString(indexFile, "b\tNOT_A_NUMBER" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IllegalStateException ex = assertThrows(IllegalStateException.class, store::findAll);
        assertTrue(ex.getMessage().contains("Invalid offset"));
    }
}
