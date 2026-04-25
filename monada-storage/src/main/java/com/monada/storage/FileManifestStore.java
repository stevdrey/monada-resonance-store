package com.monada.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileManifestStore implements ManifestStore {

    private final Path root;

    public FileManifestStore(Path root) {
        this.root = root;
    }

    @Override
    public void initialize() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("atoms"));
        Files.createDirectories(root.resolve("vectors"));
        Files.createDirectories(root.resolve("indexes"));
        Files.createDirectories(root.resolve("feedback"));

        Path manifest = root.resolve("manifest.json");
        if (Files.notExists(manifest)) {
            Files.writeString(manifest, """
                    {
                      "version": "0.1",
                      "vectorSegment": "vectors/segment-000001.f32",
                      "atomSegment": "atoms/segment-000001.log"
                    }
                    """);
        }
    }
}
