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

    private final Path atomLog;

    public FileAtomStore(Path root) throws IOException {
        Path atomsDirectory = root.resolve("atoms");
        Files.createDirectories(atomsDirectory);
        this.atomLog = atomsDirectory.resolve("segment-000001.log");
        if (Files.notExists(atomLog)) {
            Files.createFile(atomLog);
        }
    }

    @Override
    public void save(KnowledgeAtom atom) throws IOException {
        String encodedContent = Base64.getEncoder().encodeToString(atom.content().getBytes(StandardCharsets.UTF_8));
        String line = String.join("\t",
                atom.id(),
                atom.type().name(),
                Double.toString(atom.weight()),
                atom.createdAt().toString(),
                encodedContent
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
        String[] parts = line.split("\t", 5);
        String content = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
        return new KnowledgeAtom(
                parts[0],
                AtomType.valueOf(parts[1]),
                content,
                Map.of(),
                Double.parseDouble(parts[2]),
                Instant.parse(parts[3])
        );
    }
}
