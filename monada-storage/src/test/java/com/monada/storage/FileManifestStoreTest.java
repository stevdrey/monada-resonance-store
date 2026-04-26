package com.monada.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileManifestStoreTest {

    @TempDir
    Path root;

    @Test
    void loadReturnsEmptyWhenManifestDoesNotExist() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Optional<Manifest> loaded = store.load();
        assertTrue(loaded.isEmpty());
        assertTrue(Files.isDirectory(root.resolve("atoms")));
        assertTrue(Files.isDirectory(root.resolve("vectors")));
        assertTrue(Files.isDirectory(root.resolve("indexes")));
        assertTrue(Files.isDirectory(root.resolve("feedback")));
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Manifest manifest = new Manifest("0.1", 64, "vectors/segment-000001.f32", "atoms/segment-000001.log");
        store.save(manifest);

        Optional<Manifest> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(manifest, loaded.get());
    }

    @Test
    void escapesAndUnescapesSpecialCharactersOnRoundTrip() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Manifest manifest = new Manifest(
                "0.1 \"beta\"\n\\release",
                64,
                "vectors/seg\t01.f32",
                "atoms/seg\"01.log");
        store.save(manifest);

        Manifest loaded = store.load().orElseThrow();
        assertEquals(manifest, loaded);
    }

    @Test
    void parseFailsOnMissingFields() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Files.createDirectories(root);
        Files.writeString(root.resolve("manifest.json"),
                "{\"version\":\"0.1\"}", StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class, store::load);
        assertTrue(ex.getMessage().contains("Invalid manifest"));
    }
}
