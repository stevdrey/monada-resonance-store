package com.monada.api;

import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.NoOpTextNormalizer;
import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonadaMemoryMigrationTest {

    @TempDir
    Path root;

    @Test
    void freshStoreWritesEncodingProfile() throws Exception {
        MonadaMemory.open(root);
        FileManifestStore manifestStore = new FileManifestStore(root);
        Manifest manifest = manifestStore.load().orElseThrow();

        assertEquals("0.3", manifest.version());
        assertNotNull(manifest.encodingProfile());
        assertEquals("SimpleFrequencyEncoder", manifest.encodingProfile().encoder());
        assertEquals("LexicalEnrichmentPipeline", manifest.encodingProfile().normalizer());
        assertEquals(128, manifest.encodingProfile().dimensions());
        assertEquals(1.0, manifest.encodingProfile().originalWeight());
        assertEquals(0.25, manifest.encodingProfile().expansionWeight());
    }

    @Test
    void openThrowsOnIncompatibleEncodingProfile() throws Exception {
        MonadaMemory.open(root);

        MonadaMemoryOptions incompatibleOptions = new MonadaMemoryOptions(
                new NoOpTextNormalizer(),
                true,
                new LexicalExpansionOptions(1.0, 0.5)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, incompatibleOptions));

        assertTrue(ex.getMessage().contains("Requested encoding profile does not match"));
        assertTrue(ex.getMessage().contains("Please rebuild vectors using VectorRebuilder"));
    }

    @Test
    void openAllowsMatchingLegacyStores() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.1", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        MonadaMemory memory = MonadaMemory.open(root, MonadaMemoryOptions.defaults());
        assertNotNull(memory);
    }

    @Test
    void openRejectsIncompatibleLegacyStore() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.1", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        MonadaMemoryOptions incompatibleOptions = new MonadaMemoryOptions(
                new NoOpTextNormalizer(),
                true
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, incompatibleOptions));

        assertTrue(ex.getMessage().contains("is incompatible with legacy 0.1 store"));
    }

    @Test
    void rebuildMigratesStoreSuccessfully() throws Exception {
        MonadaMemory memory = MonadaMemory.open(root);
        var atom1 = memory.remember("spring framework", List.of("springboot"));
        MonadaMemoryOptions targetOptions = new MonadaMemoryOptions(
                new NoOpTextNormalizer(),
                true,
                new LexicalExpansionOptions(1.0, 0.8)
        );

        assertThrows(IllegalArgumentException.class, () -> MonadaMemory.open(root, targetOptions));

        VectorRebuilder.rebuild(root, targetOptions);

        MonadaMemory migrated = MonadaMemory.open(root, targetOptions);
        assertNotNull(migrated);

        var results = migrated.resonate("springboot")
                .topK(5)
                .threshold(0.0)
                .execute();

        System.out.println("Migrated search results: " + results.results());
        assertTrue(results.results().size() >= 1);
        assertEquals(atom1.id(), results.results().getFirst().atom().id());

        FileManifestStore manifestStore = new FileManifestStore(root);
        Manifest manifest = manifestStore.load().orElseThrow();
        assertEquals("0.3", manifest.version());
        assertEquals("NoOpTextNormalizer", manifest.encodingProfile().normalizer());
        assertEquals(0.8, manifest.encodingProfile().expansionWeight());
    }
}
