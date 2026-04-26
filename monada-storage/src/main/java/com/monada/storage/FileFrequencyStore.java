package com.monada.storage;

import com.monada.core.FrequencyVector;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class FileFrequencyStore implements FrequencyStore {

    private static final String DEFAULT_SEGMENT = "vectors/segment-000001.f32";

    private final Path vectorFile;
    private final Path vectorMap;

    public FileFrequencyStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT);
    }

    public FileFrequencyStore(Path root, String vectorSegment) throws IOException {
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = root.resolve("indexes").resolve("vector-map.idx");
        Files.createDirectories(vectorFile.getParent());
        Files.createDirectories(vectorMap.getParent());
        if (Files.notExists(vectorFile)) {
            Files.createFile(vectorFile);
        }
        if (Files.notExists(vectorMap)) {
            Files.createFile(vectorMap);
        }
    }

    @Override
    public void save(String atomId, FrequencyVector vector) throws IOException {
        long offset = Files.size(vectorFile);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(vectorFile, StandardOpenOption.APPEND))) {
            float[] values = vector.values();
            output.writeInt(values.length);
            for (float value : values) {
                output.writeFloat(value);
            }
        }
        Files.writeString(vectorMap, atomId + "\t" + offset + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Override
    public Optional<FrequencyVector> findByAtomId(String atomId) throws IOException {
        Long offset = findOffset(atomId);
        if (offset == null) {
            return Optional.empty();
        }
        try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            file.seek(offset);
            return Optional.of(readVector(file, atomId, offset));
        }
    }

    @Override
    public List<StoredVector> findAll() throws IOException {
        List<IndexEntry> entries = readIndexEntries();
        List<StoredVector> vectors = new ArrayList<>(entries.size());
        try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (IndexEntry entry : entries) {
                file.seek(entry.offset());
                vectors.add(new StoredVector(entry.atomId(), readVector(file, entry.atomId(), entry.offset())));
            }
        }
        return vectors;
    }

    private Long findOffset(String atomId) throws IOException {
        try (Stream<String> lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(FileFrequencyStore::parseEntry)
                    .filter(entry -> entry.atomId().equals(atomId))
                    .map(IndexEntry::offset)
                    .findFirst()
                    .orElse(null);
        }
    }

    private List<IndexEntry> readIndexEntries() throws IOException {
        try (Stream<String> lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(FileFrequencyStore::parseEntry)
                    .toList();
        }
    }

    private static IndexEntry parseEntry(String line) {
        String[] parts = line.split("\t", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed vector index entry: " + line);
        }
        try {
            return new IndexEntry(parts[0], Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid offset in vector index entry: " + line, e);
        }
    }

    private static FrequencyVector readVector(RandomAccessFile file, String atomId, long offset) throws IOException {
        try {
            int dimensions = file.readInt();
            float[] values = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                values[i] = file.readFloat();
            }
            return new FrequencyVector(values);
        } catch (EOFException e) {
            throw new IOException(
                    "Vector segment truncated while reading atomId=" + atomId + " at offset=" + offset, e);
        }
    }

    private record IndexEntry(String atomId, long offset) {
    }
}
