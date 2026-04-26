package com.monada.api;

import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MonadaMemoryManifestTest {

    @TempDir
    Path root;

    @Test
    void openPersistsManifestWithDefaultDimensionsOnFreshDirectory() throws Exception {
        MonadaMemory.open(root);
        Manifest manifest = new FileManifestStore(root).load().orElseThrow();
        assertEquals(128, manifest.dimensions());
        assertEquals("vectors/segment-000001.f32", manifest.vectorSegment());
        assertEquals("atoms/segment-000001.log", manifest.atomSegment());
        assertTrue(Files.exists(root.resolve("manifest.json")));
    }

    @Test
    void openRespectsManifestDimensionsOnReopen() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.1", 32, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        MonadaMemory memory = MonadaMemory.open(root);
        var atom = memory.remember("dimensions from manifest take precedence");

        // Re-open should keep the same 32-dim encoder and not fail validation.
        MonadaMemory reopened = MonadaMemory.open(root);
        var results = reopened.resonate("dimensions from manifest")
                .topK(1)
                .threshold(0.0)
                .execute();
        assertEquals(1, results.results().size());
        assertEquals(atom.id(), results.results().getFirst().atom().id());
    }

    @Test
    void openFailsWhenManifestDimensionsDisagreeWithStoredVectors() throws Exception {
        MonadaMemory memory = MonadaMemory.open(root);
        memory.remember("hello world");

        // Corrupt manifest to declare a different dimension than what was stored.
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.1", 64, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> MonadaMemory.open(root));
        assertTrue(ex.getCause().getMessage().toLowerCase().contains("does not match"),
                "Expected dimension mismatch message but got: " + ex.getCause().getMessage());
    }

    @Test
    void openFailsForUnrecognizedManifestVersion() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "99.0", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> MonadaMemory.open(root));
        assertTrue(ex.getCause().getMessage().contains("Unsupported manifest version"));
        assertTrue(ex.getCause().getMessage().contains("99.0"));
    }

    @Test
    void resonateNullQueryIsRejectedImmediately() {
        MonadaMemory memory = MonadaMemory.open(root);
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> memory.resonate(null));
        assertNotNull(ex.getMessage());
    }

    @Test
    void rememberPersistsVectorBeforeAtomSoOrphanVectorsAreSafelyIgnored() throws Exception {
        MonadaMemory memory = MonadaMemory.open(root);
        memory.remember("hello world");

        // Simulate a partial failure where the vector was persisted but the atom write was lost,
        // by truncating the atom log back to the size it had before the second remember.
        Path atomLog = root.resolve("atoms/segment-000001.log");
        long atomLogSizeBefore = Files.size(atomLog);
        memory.remember("orphan vector text");
        try (var ch = Files.newByteChannel(atomLog, java.nio.file.StandardOpenOption.WRITE)) {
            ch.truncate(atomLogSizeBefore);
        }

        MonadaMemory reopened = MonadaMemory.open(root);
        var results = reopened.resonate("hello world").topK(5).threshold(0.0).execute();
        assertEquals(1, results.results().size());
        assertEquals("hello world", results.results().getFirst().atom().content());
    }
}
