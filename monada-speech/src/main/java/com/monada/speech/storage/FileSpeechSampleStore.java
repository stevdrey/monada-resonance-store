package com.monada.speech.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;

/**
 * File-based implementation of {@link SpeechSampleStore} using append-only JSONL.
 *
 * <p><strong>Concurrency note:</strong> While each append operation is atomic at the OS level,
 * concurrent writes from multiple processes may result in interleaved or partial lines if
 * the operation is interrupted. Malformed entries are rejected on read with a clear
 * {@link IllegalStateException}. Duplicate IDs are handled via last-wins semantics in
 * {@link #findAll()}.
 */
public class FileSpeechSampleStore implements SpeechSampleStore {

    private static final String DEFAULT_SEGMENT = "samples/speech-samples-000001.jsonl";
    private static final String MANIFEST_SEGMENT = "manifest.json";

    private final Path root;
    private final Path sampleLog;

    public FileSpeechSampleStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT);
    }

    public FileSpeechSampleStore(Path root, String sampleSegment) throws IOException {
        this.root = root;
        this.sampleLog = root.resolve(sampleSegment);
        Files.createDirectories(sampleLog.getParent());
        if (Files.notExists(sampleLog)) {
            Files.createFile(sampleLog);
        }
    }

    @Override
    public void save(SpeechSample sample) throws IOException {
        String line = toJson(sample);
        Files.writeString(sampleLog, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * Returns whether the sample log contains at least one non-blank entry without parsing it.
     *
     * <p>This is intended for lightweight store validation. Call {@link #findAll()} when sample
     * metadata is required or full JSONL validation is needed.
     */
    public boolean hasSamples() throws IOException {
        try (var lines = Files.lines(sampleLog, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> !line.isBlank());
        }
    }

    @Override
    public Optional<SpeechSample> findById(String sampleId) throws IOException {
        return findAll().stream()
                .filter(sample -> sample.id().equals(sampleId))
                .findFirst();
    }

    @Override
    public List<SpeechSample> findBySpeakerId(String speakerId) throws IOException {
        return findAll().stream()
                .filter(sample -> sample.speakerId().equals(speakerId))
                .toList();
    }

    @Override
    public List<SpeechSample> findAll() throws IOException {
        try (var lines = Files.lines(sampleLog, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(this::parse)
                    .collect(Collectors.toMap(
                            SpeechSample::id,
                            Function.identity(),
                            (existing, replacement) -> replacement, // last-wins
                            LinkedHashMap::new
                    ))
                    .values()
                    .stream()
                    .toList();
        }
    }

    private String toJson(SpeechSample sample) {
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(escapeJson(sample.id())).append("\",");
        sb.append("\"speakerId\":\"").append(escapeJson(sample.speakerId())).append("\",");
        sb.append("\"datasetSource\":\"").append(sample.datasetSource().name()).append("\",");
        sb.append("\"audioPath\":\"").append(escapeJson(sample.audioPath().toString())).append("\",");
        sb.append("\"transcript\":\"").append(escapeJson(sample.transcript())).append("\",");
        sb.append("\"aliases\":[");
        sb.append(sample.aliases().stream()
                .map(a -> "\"" + escapeJson(a) + "\"")
                .collect(Collectors.joining(",")));
        sb.append("],");
        sb.append("\"condition\":\"").append(sample.condition().name()).append("\",");
        sb.append("\"taskType\":\"").append(sample.taskType().name()).append("\",");
        sb.append("\"language\":\"").append(escapeJson(sample.language())).append("\",");
        sb.append("\"audioMetadata\":{");
        sb.append("\"sampleRate\":").append(sample.audioMetadata().sampleRate()).append(",");
        sb.append("\"channels\":").append(sample.audioMetadata().channels()).append(",");
        sb.append("\"durationMs\":").append(sample.audioMetadata().durationMs()).append(",");
        sb.append("\"sha256\":\"").append(escapeJson(sample.audioMetadata().sha256())).append("\"");
        sb.append("},");
        sb.append("\"createdAt\":\"").append(sample.createdAt().toString()).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private SpeechSample parse(String line) {
        try {
            var id = extractString(line, "\"id\":\"");
            var speakerId = extractString(line, "\"speakerId\":\"");
            var datasetSourceStr = extractString(line, "\"datasetSource\":\"");
            var audioPathStr = extractString(line, "\"audioPath\":\"");
            var transcript = extractString(line, "\"transcript\":\"");
            var aliasesJson = extractArray(line, "\"aliases\":");
            var conditionStr = extractString(line, "\"condition\":\"");
            var taskTypeStr = extractString(line, "\"taskType\":\"");
            var language = extractString(line, "\"language\":\"");
            var audioMetadataJson = extractObject(line, "\"audioMetadata\":");
            var createdAtStr = extractString(line, "\"createdAt\":\"");

            var aliases = parseStringArray(aliasesJson);
            var audioMetadata = parseAudioMetadata(audioMetadataJson);

            return new SpeechSample(
                    id,
                    speakerId,
                    SpeechDatasetSource.valueOf(datasetSourceStr),
                    Path.of(audioPathStr),
                    transcript,
                    aliases,
                    SpeechCondition.valueOf(conditionStr),
                    SpeechTaskType.valueOf(taskTypeStr),
                    language,
                    audioMetadata,
                    Instant.parse(createdAtStr)
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException("Malformed speech sample JSONL entry: " + line, e);
        }
    }

    private List<String> parseStringArray(String json) {
        if (json.isEmpty() || json.equals("[]")) {
            return List.of();
        }
        var content = json.substring(1, json.length() - 1);
        if (content.isEmpty()) {
            return List.of();
        }
        var result = new java.util.ArrayList<String>();
        var sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                if (c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f' || c == 'n' || c == 'r' || c == 't' || c == 'u') {
                    if (c == 'n') sb.append('\n');
                    else if (c == 't') sb.append('\t');
                    else if (c == 'r') sb.append('\r');
                    else if (c == 'b') sb.append('\b');
                    else if (c == 'f') sb.append('\f');
                    else if (c == '"') sb.append('"');
                    else if (c == '\\') sb.append('\\');
                    else if (c == '/') sb.append('/');
                    else sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (c == ',' && !inString) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (!sb.isEmpty()) {
            result.add(sb.toString());
        }
        return List.copyOf(result);
    }

    private AudioMetadata parseAudioMetadata(String json) {
        int sampleRate = extractInt(json, "\"sampleRate\":");
        int channels = extractInt(json, "\"channels\":");
        long durationMs = extractLong(json, "\"durationMs\":");
        String sha256 = extractString(json, "\"sha256\":\"");
        return new AudioMetadata(sampleRate, channels, durationMs, sha256);
    }

    private String extractString(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + prefix);
        }
        start += prefix.length();
        var sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f' || c == 'n' || c == 'r' || c == 't' || c == 'u') {
                    if (c == 'n') sb.append('\n');
                    else if (c == 't') sb.append('\t');
                    else if (c == 'r') sb.append('\r');
                    else if (c == 'b') sb.append('\b');
                    else if (c == 'f') sb.append('\f');
                    else if (c == '"') sb.append('"');
                    else if (c == '\\') sb.append('\\');
                    else if (c == '/') sb.append('/');
                    else sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String extractArray(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + prefix);
        }
        start += prefix.length();
        int depth = 0;
        var sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                sb.append(c);
            } else if (c == '\\') {
                escaped = true;
                sb.append(c);
            } else if (c == '"') {
                inString = !inString;
                sb.append(c);
            } else if (c == '[' && !inString) {
                depth++;
                sb.append(c);
            } else if (c == ']' && !inString) {
                depth--;
                sb.append(c);
                if (depth == 0) {
                    return sb.toString();
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated array");
    }

    private String extractObject(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + prefix);
        }
        start += prefix.length();
        int depth = 0;
        var sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                sb.append(c);
            } else if (c == '\\') {
                escaped = true;
                sb.append(c);
            } else if (c == '"') {
                inString = !inString;
                sb.append(c);
            } else if (c == '{' && !inString) {
                depth++;
                sb.append(c);
            } else if (c == '}' && !inString) {
                depth--;
                sb.append(c);
                if (depth == 0) {
                    return sb.toString();
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated object");
    }

    private int extractInt(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + prefix);
        }
        start += prefix.length();
        int end = start;
        while (end < json.length() && (json.charAt(end) == '-' || (json.charAt(end) >= '0' && json.charAt(end) <= '9'))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private long extractLong(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + prefix);
        }
        start += prefix.length();
        int end = start;
        while (end < json.length() && (json.charAt(end) == '-' || (json.charAt(end) >= '0' && json.charAt(end) <= '9'))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
