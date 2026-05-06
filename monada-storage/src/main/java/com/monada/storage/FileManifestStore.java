package com.monada.storage;

import com.monada.storage.feedback.FeedbackStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileManifestStore implements ManifestStore {

    private final Path root;

    public FileManifestStore(Path root) {
        this.root = root;
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("atoms"));
        Files.createDirectories(root.resolve("vectors"));
        Files.createDirectories(root.resolve("indexes"));
        Files.createDirectories(root.resolve("feedback"));
    }

    @Override
    public Optional<Manifest> load() throws IOException {
        ensureDirectories();
        Path manifestFile = root.resolve("manifest.json");
        if (Files.notExists(manifestFile)) {
            return Optional.empty();
        }
        String json = Files.readString(manifestFile, StandardCharsets.UTF_8);
        return Optional.of(parse(json));
    }

    @Override
    public void save(Manifest manifest) throws IOException {
        ensureDirectories();
        String json = """
                {
                  "version": "%s",
                  "dimensions": %d,
                  "vectorSegment": "%s",
                  "atomSegment": "%s",
                  "feedbackSegment": "%s"
                }
                """.formatted(
                JsonStrings.escape(manifest.version()),
                manifest.dimensions(),
                JsonStrings.escape(manifest.vectorSegment()),
                JsonStrings.escape(manifest.atomSegment()),
                JsonStrings.escape(manifest.feedbackSegment()));
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static Manifest parse(String json) {
        var fields = JsonStrings.parseFlat(json, "manifest.json");
        String version = fields.get("version");
        String vectorSegment = fields.get("vectorSegment");
        String atomSegment = fields.get("atomSegment");
        String feedbackSegment = fields.getOrDefault("feedbackSegment", FeedbackStore.DEFAULT_SEGMENT);
        String dimensions = fields.get("dimensions");
        if (version == null || vectorSegment == null || atomSegment == null || dimensions == null) {
            throw new IllegalStateException(
                    "Invalid manifest.json: missing one of version/dimensions/vectorSegment/atomSegment");
        }
        return new Manifest(version, Integer.parseInt(dimensions), vectorSegment, atomSegment, feedbackSegment);
    }
}
