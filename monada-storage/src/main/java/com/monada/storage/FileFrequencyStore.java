package com.monada.storage;

import com.monada.core.FrequencyVector;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FileFrequencyStore implements FrequencyStore {

    private final Path vectorFile;
    private final Path vectorMap;

    public FileFrequencyStore(Path root) throws IOException {
        Path vectorsDirectory = root.resolve("vectors");
        Path indexesDirectory = root.resolve("indexes");
        Files.createDirectories(vectorsDirectory);
        Files.createDirectories(indexesDirectory);
        this.vectorFile = vectorsDirectory.resolve("segment-000001.f32");
        this.vectorMap = indexesDirectory.resolve("vector-map.idx");
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
        return findAll().stream()
                .filter(storedVector -> storedVector.atomId().equals(atomId))
                .map(StoredVector::vector)
                .findFirst();
    }

    @Override
    public List<StoredVector> findAll() throws IOException {
        List<StoredVector> vectors = new ArrayList<>();
        List<String> atomIds = Files.readAllLines(vectorMap).stream()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\t", 2)[0])
                .toList();

        try (DataInputStream input = new DataInputStream(Files.newInputStream(vectorFile))) {
            for (String atomId : atomIds) {
                int dimensions = input.readInt();
                float[] values = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    values[i] = input.readFloat();
                }
                vectors.add(new StoredVector(atomId, new FrequencyVector(values)));
            }
        } catch (EOFException ignored) {
        }

        return vectors;
    }
}
