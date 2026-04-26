package com.monada.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileManifestStore implements ManifestStore {

    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"(version|vectorSegment|atomSegment)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern INT_FIELD = Pattern.compile(
            "\"(dimensions)\"\\s*:\\s*(-?\\d+)");

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
                  "atomSegment": "%s"
                }
                """.formatted(
                manifest.version(),
                manifest.dimensions(),
                manifest.vectorSegment(),
                manifest.atomSegment());
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static Manifest parse(String json) {
        String version = null;
        String vectorSegment = null;
        String atomSegment = null;
        Matcher stringMatcher = STRING_FIELD.matcher(json);
        while (stringMatcher.find()) {
            switch (stringMatcher.group(1)) {
                case "version" -> version = stringMatcher.group(2);
                case "vectorSegment" -> vectorSegment = stringMatcher.group(2);
                case "atomSegment" -> atomSegment = stringMatcher.group(2);
            }
        }
        Integer dimensions = null;
        Matcher intMatcher = INT_FIELD.matcher(json);
        if (intMatcher.find()) {
            dimensions = Integer.parseInt(intMatcher.group(2));
        }
        if (version == null || vectorSegment == null || atomSegment == null || dimensions == null) {
            throw new IllegalStateException(
                    "Invalid manifest.json: missing one of version/dimensions/vectorSegment/atomSegment");
        }
        return new Manifest(version, dimensions, vectorSegment, atomSegment);
    }
}
