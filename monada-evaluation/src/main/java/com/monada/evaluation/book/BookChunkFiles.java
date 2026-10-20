package com.monada.evaluation.book;

import com.monada.storage.JsonStrings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads back the {@code chunks.jsonl} and {@code sidecar.jsonl} pair written by {@link BookChunkOutputWriter} and
 * cross-checks them: the sidecar header must name the chunks file by digest and count, every sidecar line must match
 * its chunk by key, ordinal and text digest, and {@code duplicateOf} must name the first earlier chunk with the same
 * text. Any mismatch is an {@link BookInputException.Reason#INVALID_INPUT}, so a readback never runs against chunk
 * files that do not describe each other.
 */
public final class BookChunkFiles {

    private BookChunkFiles() {
    }

    /** One chunk as the expected content of the Store, with its sidecar provenance identity. */
    public record Entry(String key, int ordinal, String text, String sha256, String duplicateOf) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** The verified chunk set: corpus identity from the sidecar header plus the chunks in ordinal order. */
    public record Loaded(String corpus, String corpusVersion, String sourceSha256, String policyId,
                         String chunksFileSha256, List<Entry> entries) {
        public Loaded {
            entries = List.copyOf(entries);
        }
    }

    public static Loaded load(Path directory) throws IOException, BookInputException {
        return parse(Files.readAllBytes(directory.resolve(BookChunkOutputWriter.CHUNKS_FILE)),
                Files.readAllBytes(directory.resolve(BookChunkOutputWriter.SIDECAR_FILE)));
    }

    /** Cross-checks a chunks/sidecar pair given as bytes (for example rendered but not yet written). */
    public static Loaded parse(byte[] chunkBytes, byte[] sidecarBytes) throws BookInputException {
        List<String> sidecarLines = lines(sidecarBytes, BookChunkOutputWriter.SIDECAR_FILE);
        List<String> chunkLines = lines(chunkBytes, BookChunkOutputWriter.CHUNKS_FILE);
        if (sidecarLines.isEmpty()) {
            throw invalid("sidecar has no header line");
        }
        Map<String, String> header = header(sidecarLines.getFirst());
        require(BookChunkOutputWriter.SIDECAR_FORMAT.equals(header.get("format")), "sidecar format is not "
                + BookChunkOutputWriter.SIDECAR_FORMAT);
        require(String.valueOf(BookChunkOutputWriter.SIDECAR_FORMAT_VERSION).equals(header.get("formatVersion")),
                "unsupported sidecar formatVersion " + header.get("formatVersion"));
        String chunksSha256 = VerifiedBookSource.sha256(chunkBytes);
        require(chunksSha256.equals(header.get("chunksFileSha256")), "chunks file digest " + chunksSha256
                + " does not match the sidecar header " + header.get("chunksFileSha256"));
        int chunkCount = integer(header, "chunkCount");
        require(chunkLines.size() == chunkCount && sidecarLines.size() - 1 == chunkCount,
                "chunk count mismatch: header " + chunkCount + ", chunks file " + chunkLines.size()
                        + ", sidecar " + (sidecarLines.size() - 1));

        List<Entry> entries = new ArrayList<>(chunkCount);
        Map<String, String> firstKeyBySha = new HashMap<>();
        for (int i = 0; i < chunkCount; i++) {
            Map<String, String> chunk = parse(chunkLines.get(i), BookChunkOutputWriter.CHUNKS_FILE);
            Map<String, String> provenance = parse(sidecarLines.get(i + 1), BookChunkOutputWriter.SIDECAR_FILE);
            String key = required(chunk, "key");
            String text = required(chunk, "text");
            require(integer(chunk, "ordinal") == i && integer(provenance, "ordinal") == i,
                    "chunk " + key + " is out of ordinal order at line " + (i + 1));
            require(key.equals(provenance.get("key")), "sidecar line " + (i + 2) + " describes "
                    + provenance.get("key") + ", not chunk " + key);
            String sha256 = VerifiedBookSource.sha256(text.getBytes(StandardCharsets.UTF_8));
            require(sha256.equals(provenance.get("sha256")), "chunk " + key + " text digest " + sha256
                    + " does not match the sidecar " + provenance.get("sha256"));
            // parseFlat yields the bare token "null" for a JSON null; chunk keys are never the string "null".
            String rawDuplicateOf = required(provenance, "duplicateOf");
            String duplicateOf = "null".equals(rawDuplicateOf) ? null : rawDuplicateOf;
            require(Objects.equals(duplicateOf, firstKeyBySha.get(sha256)), "chunk " + key + " duplicateOf "
                    + duplicateOf + " does not name the first earlier chunk with the same text ("
                    + firstKeyBySha.get(sha256) + ")");
            firstKeyBySha.putIfAbsent(sha256, key);
            entries.add(new Entry(key, i, text, sha256, duplicateOf));
        }
        return new Loaded(required(header, "corpus"), required(header, "corpusVersion"),
                required(header, "sourceSha256"), required(header, "policy.id"), chunksSha256, entries);
    }

    /**
     * The sidecar header is flat except for the nested {@code policy} object, which is lifted out and its fields
     * prefixed with {@code policy.}.
     */
    private static Map<String, String> header(String line) throws BookInputException {
        String marker = "\"policy\":{";
        int start = line.indexOf(marker);
        require(start >= 0, "sidecar header has no policy object");
        int open = start + marker.length() - 1;
        int close = matchingBrace(line, open);
        Map<String, String> header = new HashMap<>(parse(line.substring(0, start)
                + "\"policy\":null" + line.substring(close + 1), BookChunkOutputWriter.SIDECAR_FILE));
        parse(line.substring(open, close + 1), BookChunkOutputWriter.SIDECAR_FILE)
                .forEach((name, value) -> header.put("policy." + name, value));
        return header;
    }

    private static int matchingBrace(String line, int open) throws BookInputException {
        boolean inString = false;
        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                throw invalid("sidecar policy object is nested deeper than format v1 allows");
            } else if (c == '}') {
                return i;
            }
        }
        throw invalid("sidecar policy object is not closed");
    }

    private static List<String> lines(byte[] bytes, String name) throws BookInputException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // Lossy decoding replaces malformed bytes, so a round trip that changes the bytes means invalid UTF-8.
        require(Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes), name + " is not valid UTF-8");
        require(text.isEmpty() || text.endsWith("\n"), name + " does not end with a line feed");
        return text.isEmpty() ? List.of() : List.of(text.substring(0, text.length() - 1).split("\n", -1));
    }

    private static Map<String, String> parse(String line, String name) throws BookInputException {
        try {
            return JsonStrings.parseFlat(line, name);
        } catch (IllegalStateException e) {
            throw invalid(e.getMessage());
        }
    }

    private static String required(Map<String, String> fields, String name) throws BookInputException {
        String value = fields.get(name);
        require(value != null, "missing field " + name);
        return value;
    }

    private static int integer(Map<String, String> fields, String name) throws BookInputException {
        try {
            return Integer.parseInt(required(fields, name));
        } catch (NumberFormatException e) {
            throw invalid("field " + name + " is not an integer: " + fields.get(name));
        }
    }

    private static void require(boolean condition, String message) throws BookInputException {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static BookInputException invalid(String message) {
        return new BookInputException(BookInputException.Reason.INVALID_INPUT, "invalid chunk files: " + message);
    }
}
