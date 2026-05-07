package com.monada.storage;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FileAtomStore implements AtomStore {

    private static final String DEFAULT_SEGMENT = "atoms/segment-000001.log";

    private final Path atomLog;

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
        return findAll().stream().filter(atom -> atom.id().equals(id)).findFirst();
    }

    @Override
    public List<KnowledgeAtom> findAll() throws IOException {
        return Files.readAllLines(atomLog).stream()
                .filter(line -> !line.isBlank())
                .map(this::parse)
                .toList();
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
                .reduce((left, right) -> left + "," + right)
                .orElse("");
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
