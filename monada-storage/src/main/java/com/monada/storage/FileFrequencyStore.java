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
    /** Hard safety cap when we cannot cross-check the on-disk dimension header against a manifest. */
    private static final int MAX_LEGACY_DIMENSIONS = 1 << 20;

    private final Path vectorFile;
    private final Path vectorMap;
    /** When non-null, vectors are persisted as a raw float32 stream and every vector must match this length. */
    private final Integer fixedDimensions;

    public FileFrequencyStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT, null);
    }

    public FileFrequencyStore(Path root, String vectorSegment) throws IOException {
        this(root, vectorSegment, null);
    }

    public static Path resolveVectorMapPath(Path root) {
        return root.resolve("indexes").resolve("vector-map.idx");
    }

    public FileFrequencyStore(Path root, String vectorSegment, Integer fixedDimensions) throws IOException {
        if (fixedDimensions != null && fixedDimensions <= 0) {
            throw new IllegalArgumentException("fixedDimensions must be greater than zero");
        }
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = resolveVectorMapPath(root);
        this.fixedDimensions = fixedDimensions;
        Files.createDirectories(vectorFile.getParent());
        Files.createDirectories(vectorMap.getParent());
        if (Files.notExists(vectorFile)) {
            Files.createFile(vectorFile);
        }
        if (Files.notExists(vectorMap)) {
            Files.createFile(vectorMap);
        }
        if (fixedDimensions != null) {
            long frameSize = (long) fixedDimensions * Float.BYTES;
            long indexCount = readIndexEntries().size();
            long expected = indexCount * frameSize;
            long actual = Files.size(vectorFile);
            if (actual != expected) {
                throw new IOException(
                        "Vector segment size (" + actual + " bytes) does not match index entries x dimensions x 4 ("
                                + expected + " bytes); manifest dimensions may not match the on-disk segment");
            }
        }
    }

    @Override
    public void save(String atomId, FrequencyVector vector) throws IOException {
        float[] values = vector.values();
        if (fixedDimensions != null && values.length != fixedDimensions) {
            throw new IllegalArgumentException(
                    "Vector dimensions (" + values.length + ") do not match store dimensions (" + fixedDimensions
                            + ") for atomId=" + atomId);
        }
        long offset = Files.size(vectorFile);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(vectorFile, StandardOpenOption.APPEND))) {
            if (fixedDimensions == null) {
                output.writeInt(values.length);
            }
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
        long fileSize = Files.size(vectorFile);
        validateOffset(offset, fileSize, atomId);
        try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            file.seek(offset);
            return Optional.of(readVector(file, atomId, offset));
        }
    }

    @Override
    public List<StoredVector> findAll() throws IOException {
        List<IndexEntry> entries = readIndexEntries();
        List<StoredVector> vectors = new ArrayList<>(entries.size());
        long fileSize = Files.size(vectorFile);
        try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (IndexEntry entry : entries) {
                validateOffset(entry.offset(), fileSize, entry.atomId());
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

    private FrequencyVector readVector(RandomAccessFile file, String atomId, long offset) throws IOException {
        try {
            int dimensions;
            if (fixedDimensions != null) {
                dimensions = fixedDimensions;
            } else {
                dimensions = file.readInt();
                if (dimensions <= 0 || dimensions > MAX_LEGACY_DIMENSIONS) {
                    throw new IOException(
                            "Invalid vector dimensions (" + dimensions + ") for atomId=" + atomId
                                    + " at offset=" + offset);
                }
            }
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

    private void validateOffset(long offset, long fileSize, String atomId) throws IOException {
        if (offset < 0) {
            throw new IOException(
                    "Negative offset (" + offset + ") in vector index for atomId=" + atomId);
        }
        if (offset >= fileSize) {
            throw new IOException(
                    "Offset (" + offset + ") is beyond end of vector segment (" + fileSize
                            + " bytes) for atomId=" + atomId);
        }
        if (fixedDimensions != null) {
            long frameSize = (long) fixedDimensions * Float.BYTES;
            if (offset % frameSize != 0) {
                throw new IOException(
                        "Offset (" + offset + ") is not aligned to frame size (" + frameSize
                                + " bytes) for atomId=" + atomId);
            }
        }
    }

    private record IndexEntry(String atomId, long offset) {
    }
}
