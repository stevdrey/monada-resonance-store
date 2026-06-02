package com.monada.storage.feedback;

import com.monada.storage.JsonStrings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Append-only JSON-lines feedback log.
 *
 * <p>Each line is a single JSON object. The format is intentionally
 * hand-written (no external dependencies) to keep the log inspectable,
 * matching the style of {@code FileManifestStore}.
 */
public class FileFeedbackStore implements FeedbackStore {

    private final Path logFile;

    public FileFeedbackStore(Path root) throws IOException {
        this(root, FeedbackStore.DEFAULT_SEGMENT);
    }

    public FileFeedbackStore(Path root, String segment) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(segment, "segment");
        if (segment.isBlank()) {
            throw new IllegalArgumentException("segment must not be blank");
        }
        if (segment.endsWith("/") || segment.endsWith("\\")) {
            throw new IllegalArgumentException("segment must resolve to a file path: " + segment);
        }
        Path resolved = root.resolve(segment).toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot) || resolved.getParent() == null) {
            throw new IllegalArgumentException("segment must resolve to a file under root: " + segment);
        }
        this.logFile = resolved;
        Files.createDirectories(resolved.getParent());
        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("segment must resolve to a file, not a directory: " + segment);
        }
        if (Files.notExists(logFile)) {
            Files.createFile(logFile);
        }
    }

    @Override
    public void append(FeedbackEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        String line = """
                {"query":"%s","queryKey":"%s","atomId":"%s","signal":"%s","delta":%s,"createdAt":"%s"}
                """.formatted(
                JsonStrings.escape(event.query()),
                JsonStrings.escape(event.queryKey()),
                JsonStrings.escape(event.atomId()),
                event.signal().name(),
                formatDelta(event.delta()),
                event.createdAt().toString()).strip();
        Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    @Override
    public List<FeedbackEvent> findAll() throws IOException {
        try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(FileFeedbackStore::parse)
                    .toList();
        }
    }

    @Override
    public List<FeedbackEvent> findByQuery(String query) throws IOException {
        Objects.requireNonNull(query, "query");
        try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(FileFeedbackStore::parse)
                    .filter(event -> event.query().equals(query))
                    .toList();
        }
    }

    @Override
    public List<FeedbackEvent> findByQueryKey(String queryKey) throws IOException {
        Objects.requireNonNull(queryKey, "queryKey");
        try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(FileFeedbackStore::parse)
                    .filter(event -> event.queryKey().equals(queryKey))
                    .toList();
        }
    }

    private static FeedbackEvent parse(String line) {
        try {
            var fields = JsonStrings.parseFlat(line, "feedback log");
            String query = fields.get("query");
            String queryKey = fields.getOrDefault("queryKey", query);
            String atomId = fields.get("atomId");
            String signal = fields.get("signal");
            String delta = fields.get("delta");
            String createdAt = fields.get("createdAt");
            if (query == null || atomId == null || signal == null || delta == null || createdAt == null) {
                throw new IllegalStateException("Malformed feedback log entry: " + line);
            }
            return new FeedbackEvent(
                    query,
                    queryKey,
                    atomId,
                    FeedbackSignal.valueOf(signal),
                    Double.parseDouble(delta),
                    Instant.parse(createdAt));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Malformed feedback log entry: " + line, e);
        }
    }

    private static String formatDelta(double delta) {
        // Use Double.toString for a round-trippable representation.
        return Double.toString(delta);
    }
}
