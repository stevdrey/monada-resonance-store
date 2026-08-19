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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class FileFrequencyStore implements FrequencyStore {

    private static final String DEFAULT_SEGMENT = "vectors/segment-000001.f32";
    /** Hard safety cap when we cannot cross-check the on-disk dimension header against a manifest. */
    private static final int MAX_LEGACY_DIMENSIONS = 1 << 20;

    private final Path vectorFile;
    private final Path vectorMap;
    /** Retained for source-compatible direct fixed-dimension use without a manifest profile. */
    private final Integer fixedDimensions;
    private final VectorFormatProfile vectorFormatProfile;

    public FileFrequencyStore(Path root) throws IOException {
        this(root, DEFAULT_SEGMENT, null, null, false);
    }

    public FileFrequencyStore(Path root, String vectorSegment) throws IOException {
        this(root, vectorSegment, null, null, false);
    }

    public FileFrequencyStore(Path root, String vectorSegment, Integer fixedDimensions) throws IOException {
        this(root, vectorSegment, fixedDimensions, null, false);
    }

    public static FileFrequencyStore create(Path root, String vectorSegment, VectorFormatProfile vectorFormatProfile)
            throws IOException {
        return new FileFrequencyStore(root, vectorSegment, null, vectorFormatProfile, false);
    }

    public static FileFrequencyStore openExisting(Path root, String vectorSegment,
                                                  VectorFormatProfile vectorFormatProfile) throws IOException {
        return new FileFrequencyStore(root, vectorSegment, null, vectorFormatProfile, true);
    }

    public static Path resolveVectorMapPath(Path root) {
        return root.resolve("indexes").resolve("vector-map.idx");
    }

    private FileFrequencyStore(Path root, String vectorSegment, Integer fixedDimensions,
                               VectorFormatProfile vectorFormatProfile, boolean requireExisting) throws IOException {
        if (fixedDimensions != null && fixedDimensions <= 0) {
            throw new IllegalArgumentException("fixedDimensions must be greater than zero");
        }
        if (vectorFormatProfile != null) {
            vectorFormatProfile.requireSupportedRuntime();
        }
        this.vectorFile = root.resolve(vectorSegment);
        this.vectorMap = resolveVectorMapPath(root);
        this.fixedDimensions = fixedDimensions;
        this.vectorFormatProfile = vectorFormatProfile;

        if (requireExisting) {
            requireExistingArtifacts();
        } else {
            ensureArtifacts();
        }
        validatePhysicalStructure();
    }

    @Override
    public void save(String atomId, FrequencyVector vector) throws IOException {
        float[] values = vector.values();
        Integer dimensions = declaredDimensions();
        if (dimensions != null && values.length != dimensions) {
            throw new IllegalArgumentException(
                    "Vector dimensions (" + values.length + ") do not match store dimensions (" + dimensions
                            + ") for atomId=" + atomId);
        }
        long offset = Files.size(vectorFile);
        try (DataOutputStream output = new DataOutputStream(
                Files.newOutputStream(vectorFile, StandardOpenOption.APPEND))) {
            if (usesLengthPrefix()) {
                output.writeInt(values.length);
            }
            for (float value : values) {
                output.writeFloat(value);
            }
        }
        Files.writeString(vectorMap, atomId + "\t" + offset + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
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

    private void ensureArtifacts() throws IOException {
        Files.createDirectories(vectorFile.getParent());
        Files.createDirectories(vectorMap.getParent());
        if (Files.notExists(vectorFile)) {
            Files.createFile(vectorFile);
        }
        if (Files.notExists(vectorMap)) {
            Files.createFile(vectorMap);
        }
    }

    private void requireExistingArtifacts() throws IOException {
        if (Files.notExists(vectorFile)) {
            throw new IOException("Vector segment is missing: " + vectorFile);
        }
        if (Files.notExists(vectorMap)) {
            throw new IOException("Vector index is missing: " + vectorMap);
        }
    }

    private void validatePhysicalStructure() throws IOException {
        if (vectorFormatProfile == null) {
            validateLegacyFixedDimensionStructure();
            return;
        }

        List<IndexEntry> entries = readIndexEntries();
        long frameSize = vectorFormatProfile.frameSizeInBytes();
        long expectedSize = entries.size() * frameSize;
        long actualSize = Files.size(vectorFile);
        if (actualSize != expectedSize) {
            throw new IOException(
                    "Vector segment size (" + actualSize + " bytes) does not match " + entries.size()
                            + " indexed " + vectorFormatProfile.framing() + " frames of " + frameSize
                            + " bytes; manifest physical metadata or on-disk bytes are incompatible");
        }

        Set<Long> expectedOffsets = new HashSet<>();
        for (long offset = 0; offset < expectedSize; offset += frameSize) {
            expectedOffsets.add(offset);
        }
        for (IndexEntry entry : entries) {
            validateOffset(entry.offset(), actualSize, entry.atomId());
            if (!expectedOffsets.remove(entry.offset())) {
                throw new IOException(
                        "Duplicate or unexpected offset (" + entry.offset() + ") in vector index for atomId="
                                + entry.atomId());
            }
        }
        if (!expectedOffsets.isEmpty()) {
            throw new IOException("Vector index does not cover every physical vector frame");
        }

        if (vectorFormatProfile.framing() == VectorFormatProfile.Framing.LEGACY_LENGTH_PREFIXED) {
            validateLengthPrefixedHeaders(entries);
        }
    }

    private void validateLegacyFixedDimensionStructure() throws IOException {
        if (fixedDimensions == null) {
            return;
        }
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

    private void validateLengthPrefixedHeaders(List<IndexEntry> entries) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(vectorFile.toFile(), "r")) {
            for (IndexEntry entry : entries) {
                file.seek(entry.offset());
                int storedDimensions = file.readInt();
                if (storedDimensions != vectorFormatProfile.dimensions()) {
                    throw new IOException(
                            "Length-prefixed vector dimensions (" + storedDimensions + ") do not match manifest "
                                    + "physical dimensions (" + vectorFormatProfile.dimensions() + ") for atomId="
                                    + entry.atomId());
                }
            }
        } catch (EOFException e) {
            throw new IOException("Vector segment truncated while validating length-prefixed frames", e);
        }
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
        if (parts.length != 2 || parts[0].isBlank()) {
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
            Integer dimensions = declaredDimensions();
            if (usesLengthPrefix()) {
                int storedDimensions = file.readInt();
                if (dimensions == null) {
                    dimensions = storedDimensions;
                    if (dimensions <= 0 || dimensions > MAX_LEGACY_DIMENSIONS) {
                        throw new IOException(
                                "Invalid vector dimensions (" + dimensions + ") for atomId=" + atomId
                                        + " at offset=" + offset);
                    }
                } else if (storedDimensions != dimensions) {
                    throw new IOException(
                            "Length-prefixed vector dimensions (" + storedDimensions + ") do not match store "
                                    + "dimensions (" + dimensions + ") for atomId=" + atomId + " at offset=" + offset);
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
        Integer dimensions = declaredDimensions();
        if (dimensions != null) {
            long frameSize = vectorFormatProfile == null
                    ? (long) dimensions * Float.BYTES
                    : vectorFormatProfile.frameSizeInBytes();
            if (offset % frameSize != 0) {
                throw new IOException(
                        "Offset (" + offset + ") is not aligned to frame size (" + frameSize
                                + " bytes) for atomId=" + atomId);
            }
        }
    }

    private Integer declaredDimensions() {
        if (vectorFormatProfile == null) {
            return fixedDimensions;
        }
        return vectorFormatProfile.dimensions();
    }

    private boolean usesLengthPrefix() {
        return vectorFormatProfile == null
                ? fixedDimensions == null
                : vectorFormatProfile.framing() == VectorFormatProfile.Framing.LEGACY_LENGTH_PREFIXED;
    }

    private record IndexEntry(String atomId, long offset) {
    }
}
