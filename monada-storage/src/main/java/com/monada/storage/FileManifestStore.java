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
            "\"(version|vectorSegment|atomSegment)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
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
                escape(manifest.version()),
                manifest.dimensions(),
                escape(manifest.vectorSegment()),
                escape(manifest.atomSegment()));
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static Manifest parse(String json) {
        String version = null;
        String vectorSegment = null;
        String atomSegment = null;
        Matcher stringMatcher = STRING_FIELD.matcher(json);
        while (stringMatcher.find()) {
            String value = unescape(stringMatcher.group(2));
            switch (stringMatcher.group(1)) {
                case "version" -> version = value;
                case "vectorSegment" -> vectorSegment = value;
                case "atomSegment" -> atomSegment = value;
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

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new IllegalStateException("Invalid \\u escape in manifest.json");
                    }
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalStateException("Invalid escape \\" + next + " in manifest.json");
            }
        }
        return out.toString();
    }
}
