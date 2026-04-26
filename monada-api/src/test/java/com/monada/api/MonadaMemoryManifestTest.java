package com.monada.api;

import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MonadaMemory.open(root));
        assertTrue(ex.getMessage().contains("dimensions"));
    }
}
