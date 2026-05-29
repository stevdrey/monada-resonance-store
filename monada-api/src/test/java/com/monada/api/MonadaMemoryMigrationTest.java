package com.monada.api;

import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.encoder.NoOpTextNormalizer;
import com.monada.storage.FileManifestStore;
import com.monada.storage.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void rebuildRollsBackOnFailure() throws Exception {
        var memory = MonadaMemory.open(root);
        var vectorFile = root.resolve("vectors/segment-000001.f32");
        var vectorMap = root.resolve("indexes/vector-map.idx");
        assertTrue(Files.exists(vectorFile));
        assertTrue(Files.exists(vectorMap));
        var originalVectorSize = Files.size(vectorFile);
        var originalMapSize = Files.size(vectorMap);
        var originalVectorBytes = Files.readAllBytes(vectorFile);
        var originalMapBytes = Files.readAllBytes(vectorMap);

        var targetOptions = new MonadaMemoryOptions(
                new NoOpTextNormalizer(),
                true,
                new LexicalExpansionOptions(1.0, 0.8)
        );

        // Make manifest.json read-only to force manifestStore.save to fail
        var manifestFile = root.resolve("manifest.json");
        try {
            manifestFile.toFile().setWritable(false);
            root.toFile().setWritable(false); // Also make directory read-only for safety

            assertThrows(java.io.IOException.class, () -> VectorRebuilder.rebuild(root, targetOptions));

            // Verify original files are restored
            assertTrue(Files.exists(vectorFile));
            assertTrue(Files.exists(vectorMap));
            assertEquals(originalVectorSize, Files.size(vectorFile));
            assertEquals(originalMapSize, Files.size(vectorMap));
            assertTrue(Arrays.equals(originalVectorBytes, Files.readAllBytes(vectorFile)));
            assertTrue(Arrays.equals(originalMapBytes, Files.readAllBytes(vectorMap)));

            // The manifest must remain intact and the store must stay openable.
            assertTrue(Files.exists(manifestFile));
        } finally {
            // Restore write permissions for JUnit cleanup
            manifestFile.toFile().setWritable(true);
            root.toFile().setWritable(true);
        }

        // Store still opens with its original (default) profile after the failed rebuild.
        assertNotNull(MonadaMemory.open(root));
    }

    @Test
    void openRejectsCustomLexicalResourcesAgainstDefaultStore() throws Exception {
        MonadaMemory.open(root);

        var customPipeline = new LexicalEnrichmentPipeline(
                Set.of("customstop"),
                Map.of("databases", "database"),
                Map.of("db", List.of("database")));
        var customOptions = new MonadaMemoryOptions(
                customPipeline,
                true,
                LexicalExpansionOptions.DEFAULT);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, customOptions));
        assertTrue(ex.getMessage().contains("Requested encoding profile does not match"));
    }

    @Test
    void openAllowsIdenticalLexicalResources() throws Exception {
        MonadaMemory.open(root);
        // A fresh default pipeline has the same fingerprint as the one used to create the store.
        assertNotNull(MonadaMemory.open(root, MonadaMemoryOptions.defaults()));
    }

    @Test
    void openRejectsCustomExpansionOptionsForLegacy02Store() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.2", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        MonadaMemoryOptions customExpansion = new MonadaMemoryOptions(
                new LexicalEnrichmentPipeline(),
                true,
                new LexicalExpansionOptions(1.0, 1.0));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, customExpansion));
        assertTrue(ex.getMessage().contains("cannot be proven compatible with legacy 0.2 store"));
    }

    @Test
    void openAllowsDefaultExpansionOptionsForLegacy02Store() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.2", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        assertNotNull(MonadaMemory.open(root, MonadaMemoryOptions.defaults()));
    }

    @Test
    void openRejectsCustomLexicalResourcesForLegacy02Store() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.2", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        var customPipeline = new LexicalEnrichmentPipeline(
                Set.of("customstop"),
                Map.of("databases", "database"),
                Map.of("db", List.of("database")));
        var customOptions = new MonadaMemoryOptions(
                customPipeline,
                true,
                LexicalExpansionOptions.DEFAULT);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, customOptions));
        assertTrue(ex.getMessage().contains("has custom lexical resources which cannot be proven compatible with legacy 0.2 store"));
    }

    @Test
    void openRejectsCustomLexicalResourcesForLegacy01Store() throws Exception {
        FileManifestStore manifestStore = new FileManifestStore(root);
        manifestStore.save(new Manifest(
                "0.1", 128, "vectors/segment-000001.f32", "atoms/segment-000001.log"));

        var customPipeline = new LexicalEnrichmentPipeline(
                Set.of("customstop"),
                Map.of("databases", "database"),
                Map.of("db", List.of("database")));
        var customOptions = new MonadaMemoryOptions(
                customPipeline,
                true,
                LexicalExpansionOptions.DEFAULT);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MonadaMemory.open(root, customOptions));
        assertTrue(ex.getMessage().contains("has custom lexical resources which cannot be proven compatible with legacy 0.1 store"));
    }
}
