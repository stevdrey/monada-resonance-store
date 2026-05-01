package com.monada.storage.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only JSON-lines feedback log.
 *
 * <p>Each line is a single JSON object. The format is intentionally
 * hand-written (no external dependencies) to keep the log inspectable,
 * matching the style of {@code FileManifestStore}.
 */
public class FileFeedbackStore implements FeedbackStore {

    public static final String DEFAULT_SEGMENT = "feedback/feedback-000001.log";

    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"(query|atomId|signal|createdAt)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile(
            "\"(delta)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");

    private final Path logFile;

    public FileFeedbackStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT);
    }

    public FileFeedbackStore(Path root, String segment) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(segment, "segment");
        this.logFile = root.resolve(segment);
        Files.createDirectories(logFile.getParent());
        if (Files.notExists(logFile)) {
            Files.createFile(logFile);
        }
    }

    @Override
    public void append(FeedbackEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        // Text block keeps the on-disk JSON shape visible next to the code that writes it.
        String line = """
                {"query":"%s","atomId":"%s","signal":"%s","delta":%s,"createdAt":"%s"}
                """.formatted(
                escape(event.query()),
                escape(event.atomId()),
                event.signal().name(),
                formatDelta(event.delta()),
                event.createdAt().toString());
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    @Override
    public List<FeedbackEvent> findAll() throws IOException {
        var events = new ArrayList<FeedbackEvent>();
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            events.add(parse(line));
        }
        return List.copyOf(events);
    }

    @Override
    public List<FeedbackEvent> findByQuery(String query) throws IOException {
        Objects.requireNonNull(query, "query");
        var filtered = new ArrayList<FeedbackEvent>();
        for (FeedbackEvent event : findAll()) {
            if (event.query().equals(query)) {
                filtered.add(event);
            }
        }
        return List.copyOf(filtered);
    }

    private static FeedbackEvent parse(String line) {
        String query = null;
        String atomId = null;
        String signal = null;
        String createdAt = null;
        Matcher stringMatcher = STRING_FIELD.matcher(line);
        while (stringMatcher.find()) {
            String value = unescape(stringMatcher.group(2));
            switch (stringMatcher.group(1)) {
                case "query" -> query = value;
                case "atomId" -> atomId = value;
                case "signal" -> signal = value;
                case "createdAt" -> createdAt = value;
            }
        }
        Double delta = null;
        Matcher numberMatcher = NUMBER_FIELD.matcher(line);
        if (numberMatcher.find()) {
            delta = Double.parseDouble(numberMatcher.group(2));
        }
        if (query == null || atomId == null || signal == null || createdAt == null || delta == null) {
            throw new IllegalStateException("Malformed feedback log entry: " + line);
        }
        return new FeedbackEvent(
                query,
                atomId,
                FeedbackSignal.valueOf(signal),
                delta,
                Instant.parse(createdAt));
    }

    private static String formatDelta(double delta) {
        // Use Double.toString for a round-trippable representation.
        return Double.toString(delta);
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
                        throw new IllegalStateException("Invalid \\u escape in feedback log");
                    }
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalStateException("Invalid escape \\" + next + " in feedback log");
            }
        }
        return out.toString();
    }
}
