package com.monada.storage;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FileAtomStore implements AtomStore {

    private static final String DEFAULT_SEGMENT = "atoms/segment-000001.log";

    private final Path atomLog;

    // Incremental view of the log: last-wins per atom id, first-occurrence order (see findAll). Only bytes
    // appended since the previous refresh are parsed, so repeated lookups cost O(new bytes), not O(log).
    private final Map<String, KnowledgeAtom> byId = new LinkedHashMap<>();
    private long parsedBytes;
    private long parsedRecords;
    private KnowledgeAtom tail; // provisional unterminated final record, never committed to byId

    public FileAtomStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT);
    }

    public FileAtomStore(Path root, String atomSegment) throws IOException {
        this.atomLog = root.resolve(atomSegment);
        Files.createDirectories(atomLog.getParent());
        if (Files.notExists(atomLog)) {
            Files.createFile(atomLog);
        }
    }

    @Override
    public void save(KnowledgeAtom atom) throws IOException {
        if (!atom.metadata().isEmpty()) {
            throw new IllegalArgumentException(
                    "FileAtomStore does not persist atom metadata; received " + atom.metadata().size() + " entries for atom " + atom.id());
        }
        String encodedContent = Base64.getEncoder().encodeToString(atom.content().getBytes(StandardCharsets.UTF_8));
        String encodedAliases = encodeAliases(atom.aliases());
        String line = String.join("\t",
                atom.id(),
                atom.type().name(),
                Double.toString(atom.weight()),
                atom.createdAt().toString(),
                encodedContent,
                encodedAliases
        );
        Files.writeString(atomLog, line + System.lineSeparator(), StandardOpenOption.APPEND);
    }

    @Override
    public Optional<KnowledgeAtom> findById(String id) throws IOException {
        refresh();
        if (tail != null && tail.id().equals(id)) {
            return Optional.of(tail);
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<KnowledgeAtom> findAll() throws IOException {
        // Last-wins per atom id: if the same id appears multiple times (e.g. after an
        // alias merge), the most recently appended entry supersedes earlier ones.
        // Insertion order is preserved based on the first occurrence of each id.
        refresh();
        if (tail == null) {
            return List.copyOf(byId.values());
        }
        var withTail = new LinkedHashMap<>(byId);
        withTail.put(tail.id(), tail);
        return List.copyOf(withTail.values());
    }

    /** Number of log records parsed by this instance so far (diagnostic; an unterminated tail is re-parsed per lookup). */
    public long parsedRecordCount() {
        return parsedRecords;
    }

    /**
     * Brings the in-memory view up to date with the log: reparses from the start if the log shrank, otherwise
     * parses only the bytes appended since the last refresh. Only line-terminated records are committed to
     * {@code byId}/{@code parsedBytes}. A final record without a terminator is provisional: it is parsed into
     * {@code tail} on every refresh and overlaid on the committed view, so it disappears if the file loses it and
     * is superseded once it is completed. Bytes are decoded strictly: malformed UTF-8 fails like a full read would.
     */
    private void refresh() throws IOException {
        long size = Files.size(atomLog);
        if (size < parsedBytes) {
            byId.clear();
            parsedBytes = 0;
        }
        tail = null;
        if (size == parsedBytes) {
            return;
        }
        byte[] chunk = readFrom(parsedBytes, (int) (size - parsedBytes));
        int start = 0;
        while (start < chunk.length) {
            int end = start;
            while (end < chunk.length && chunk[end] != '\n') {
                end++;
            }
            boolean terminated = end < chunk.length;
            var atom = parseRecord(chunk, start, end);
            if (!terminated) {
                tail = atom;
                return;
            }
            if (atom != null) {
                byId.put(atom.id(), atom);
            }
            parsedBytes += end + 1 - start;
            start = end + 1;
        }
    }

    /** Parses one record's bytes; null for a blank line. */
    private KnowledgeAtom parseRecord(byte[] chunk, int start, int end) throws IOException {
        String line = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(chunk, start, end - start))
                .toString();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isBlank()) {
            return null;
        }
        var atom = parse(line);
        parsedRecords++;
        return atom;
    }

    private byte[] readFrom(long position, int length) throws IOException {
        try (FileChannel channel = FileChannel.open(atomLog, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long at = position;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, at);
                if (read < 0) {
                    break;
                }
                at += read;
            }
            return buffer.position() == length ? buffer.array() : Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private KnowledgeAtom parse(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 5 && parts.length != 6) {
            throw new IllegalStateException(
                    "Malformed atom log entry: expected 5 or 6 tab-delimited fields but found " + parts.length + " in line: " + line);
        }
        try {
            String content = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            List<String> aliases = parts.length == 6 ? decodeAliases(parts[5]) : List.of();
            return new KnowledgeAtom(
                    parts[0],
                    AtomType.valueOf(parts[1]),
                    content,
                    aliases,
                    Map.of(),
                    Double.parseDouble(parts[2]),
                    Instant.parse(parts[3])
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException("Corrupt atom log entry: " + line, e);
        }
    }

    private String encodeAliases(List<String> aliases) {
        return aliases.stream()
                .map(alias -> Base64.getEncoder().encodeToString(alias.getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.joining(","));
    }

    private List<String> decodeAliases(String encodedAliases) {
        if (encodedAliases.isBlank()) {
            return List.of();
        }
        return List.of(encodedAliases.split(",")).stream()
                .map(alias -> new String(Base64.getDecoder().decode(alias), StandardCharsets.UTF_8))
                .toList();
    }
}
