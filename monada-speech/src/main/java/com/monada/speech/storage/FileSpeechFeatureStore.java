package com.monada.speech.storage;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class FileSpeechFeatureStore implements SpeechFeatureStore {

    private static final String DEFAULT_SEGMENT = "features/speech-features-000001.f32";
    private static final String DEFAULT_INDEX_SEGMENT = "indexes/speech-feature-map.idx";
    private static final int MAX_DIMENSIONS = 1 << 20;

    private final Path vectorFile;
    private final Path vectorMap;

    public FileSpeechFeatureStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT, DEFAULT_INDEX_SEGMENT);
    }

    public FileSpeechFeatureStore(Path root, String vectorSegment, String indexSegment) throws IOException {
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = root.resolve(indexSegment);
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
    public void save(String sampleId, FrequencyVector vector) throws IOException {
        validateSampleId(sampleId);
        float[] values = vector.values();
        long offset = Files.size(vectorFile);
        try (var output = new DataOutputStream(Files.newOutputStream(vectorFile, StandardOpenOption.APPEND))) {
            output.writeInt(values.length);
            for (var value : values) {
                output.writeFloat(value);
            }
        }
        Files.writeString(vectorMap, sampleId + "\t" + offset + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Override
    public Optional<FrequencyVector> findBySampleId(String sampleId) throws IOException {
        validateSampleId(sampleId);
        var offset = findOffset(sampleId);
        if (offset == null) {
            return Optional.empty();
        }
        var fileSize = Files.size(vectorFile);
        validateOffset(offset, fileSize, sampleId);
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            file.seek(offset);
            return Optional.of(readVector(file, sampleId, offset));
        }
    }

    @Override
    public List<StoredSpeechFeatureVector> findAll() throws IOException {
        var byId = new LinkedHashMap<String, StoredSpeechFeatureVector>();
        var entries = readIndexEntries();
        var fileSize = Files.size(vectorFile);
        try (var file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (var entry : entries) {
                validateOffset(entry.offset(), fileSize, entry.sampleId());
                file.seek(entry.offset());
                var vector = readVector(file, entry.sampleId(), entry.offset());
                byId.put(entry.sampleId(), new StoredSpeechFeatureVector(entry.sampleId(), vector));
            }
        }
        return List.copyOf(byId.values());
    }

    private Long findOffset(String sampleId) throws IOException {
        try (var lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(FileSpeechFeatureStore::parseEntry)
                    .filter(entry -> entry.sampleId().equals(sampleId))
                    .reduce((first, second) -> second) // keep last (last-wins)
                    .map(IndexEntry::offset)
                    .orElse(null);
        }
    }

    private List<IndexEntry> readIndexEntries() throws IOException {
        var entries = new ArrayList<IndexEntry>();
        try (var lines = Files.lines(vectorMap, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank())
                    .map(FileSpeechFeatureStore::parseEntry)
                    .forEach(entry -> entries.add(entry));
        }
        return entries;
    }

    private static IndexEntry parseEntry(String line) {
        var parts = line.split("\t", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed feature index entry: " + line);
        }
        try {
            return new IndexEntry(parts[0], Long.parseLong(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid offset in feature index entry: " + line, e);
        }
    }

    private FrequencyVector readVector(RandomAccessFile file, String sampleId, long offset) throws IOException {
        try {
            var dimensions = file.readInt();
            if (dimensions <= 0 || dimensions > MAX_DIMENSIONS) {
                throw new IOException(
                        "Invalid vector dimensions (" + dimensions + ") for sampleId=" + sampleId
                                + " at offset=" + offset);
            }
            var values = new float[dimensions];
            for (var i = 0; i < dimensions; i++) {
                values[i] = file.readFloat();
            }
            return new FrequencyVector(values);
        } catch (EOFException e) {
            throw new IOException(
                    "Feature vector segment truncated while reading sampleId=" + sampleId + " at offset=" + offset, e);
        }
    }

    private void validateOffset(long offset, long fileSize, String sampleId) throws IOException {
        if (offset < 0) {
            throw new IOException(
                    "Negative offset (" + offset + ") in feature index for sampleId=" + sampleId);
        }
        if (offset >= fileSize) {
            throw new IOException(
                    "Offset (" + offset + ") is beyond end of feature segment (" + fileSize
                            + " bytes) for sampleId=" + sampleId);
        }
    }

    private static void validateSampleId(String sampleId) {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        if (sampleId.contains("\t") || sampleId.contains("\n") || sampleId.contains("\r")) {
            throw new IllegalArgumentException("sampleId must not contain tab or line breaks");
        }
    }

    private record IndexEntry(String sampleId, long offset) {
    }
}
