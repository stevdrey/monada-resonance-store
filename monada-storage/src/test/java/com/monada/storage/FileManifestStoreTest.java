package com.monada.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileManifestStoreTest {

    @TempDir
    Path root;

    @Test
    void loadReturnsEmptyWhenManifestDoesNotExist() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Optional<Manifest> loaded = store.load();
        assertTrue(loaded.isEmpty());
        assertTrue(Files.isDirectory(root.resolve("atoms")));
        assertTrue(Files.isDirectory(root.resolve("vectors")));
        assertTrue(Files.isDirectory(root.resolve("indexes")));
        assertTrue(Files.isDirectory(root.resolve("feedback")));
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Manifest manifest = new Manifest("0.1", 64, "vectors/segment-000001.f32", "atoms/segment-000001.log");
        store.save(manifest);

        Optional<Manifest> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(manifest, loaded.get());
    }

    @Test
    void savesAndLoadsPhysicalVectorFormatProfile() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Manifest manifest = new Manifest(
                "0.4", 64, "vectors/segment-000001.f32", "atoms/segment-000001.log",
                "feedback/feedback-000001.log", null, VectorFormatProfile.currentFixedRaw(64));

        store.save(manifest);

        Manifest loaded = store.load().orElseThrow();
        assertEquals(VectorFormatProfile.currentFixedRaw(64), loaded.vectorFormatProfile());
        String json = Files.readString(root.resolve("manifest.json"));
        assertTrue(json.contains("\"vectorFormatVersion\": 1"));
        assertTrue(json.contains("\"vectorScalarType\": \"FLOAT32\""));
        assertTrue(json.contains("\"vectorByteOrder\": \"BIG_ENDIAN\""));
        assertTrue(json.contains("\"vectorFraming\": \"FIXED_RAW\""));
        assertTrue(json.contains("\"vectorIndexFormatVersion\": 1"));
    }

    @Test
    void parseRejectsPartialPhysicalVectorFormatMetadata() throws IOException {
        Files.writeString(root.resolve("manifest.json"), """
                {
                  "version": "0.4",
                  "dimensions": 64,
                  "vectorSegment": "vectors/segment-000001.f32",
                  "atomSegment": "atoms/segment-000001.log",
                  "vectorScalarType": "FLOAT32"
                }
                """, StandardCharsets.UTF_8);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FileManifestStore(root).load());
        assertTrue(ex.getMessage().contains("physical vector format metadata"));
    }

    @ParameterizedTest
    @CsvSource({
            "vectorScalarType, FLOAT16, vectorScalarType",
            "vectorByteOrder, LITTLE_ENDIAN, vectorByteOrder",
            "vectorFraming, QUANTIZED, vectorFraming"
    })
    void parseRejectsUnknownPhysicalFormatValues(String field, String value, String expectedMessage) throws IOException {
        Files.writeString(root.resolve("manifest.json"), manifestWithPhysicalField(field, value), StandardCharsets.UTF_8);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FileManifestStore(root).load());
        assertTrue(ex.getMessage().contains(expectedMessage));
        assertTrue(ex.getMessage().contains(value));
    }

    @Test
    void manifestRejectsPhysicalDimensionsThatDifferFromManifestDimensions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Manifest("0.4", 64, "vectors/segment-000001.f32", "atoms/segment-000001.log",
                        "feedback/feedback-000001.log", null, VectorFormatProfile.currentFixedRaw(32)));
        assertTrue(ex.getMessage().contains("do not match manifest dimensions"));
    }

    @Test
    void escapesAndUnescapesSpecialCharactersOnRoundTrip() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Manifest manifest = new Manifest(
                "0.1 \"beta\"\n\\release",
                64,
                "vectors/seg\t01.f32",
                "atoms/seg\"01.log");
        store.save(manifest);

        Manifest loaded = store.load().orElseThrow();
        assertEquals(manifest, loaded);
    }

    @Test
    void parseFailsOnMissingFields() throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Files.createDirectories(root);
        Files.writeString(root.resolve("manifest.json"),
                "{\"version\":\"0.1\"}", StandardCharsets.UTF_8);
        IllegalStateException ex = assertThrows(IllegalStateException.class, store::load);
        assertTrue(ex.getMessage().contains("Invalid manifest"));
    }

    @ParameterizedTest
    @CsvSource({
            "originalWeight, NaN",
            "expansionWeight, Infinity",
            "aliasOriginalWeight, 0.0",
            "aliasExpansionWeight, -0.25"
    })
    void parseFailsOnInvalidEncodingProfileWeights(String field, String invalidValue) throws IOException {
        FileManifestStore store = new FileManifestStore(root);
        Files.createDirectories(root);
        Files.writeString(root.resolve("manifest.json"),
                manifestWithWeight(field, invalidValue), StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, store::load);
        assertEquals(field + " must be finite and positive", ex.getMessage());
    }

    private static String manifestWithWeight(String field, String value) {
        String originalWeight = "1.0";
        String expansionWeight = "0.25";
        String aliasOriginalWeight = "0.8";
        String aliasExpansionWeight = "0.2";

        switch (field) {
            case "originalWeight" -> originalWeight = value;
            case "expansionWeight" -> expansionWeight = value;
            case "aliasOriginalWeight" -> aliasOriginalWeight = value;
            case "aliasExpansionWeight" -> aliasExpansionWeight = value;
            default -> throw new IllegalArgumentException("Unknown weight field: " + field);
        }

        return """
                {
                  "version": "0.1",
                  "dimensions": 64,
                  "vectorSegment": "vectors/segment-000001.f32",
                  "atomSegment": "atoms/segment-000001.log",
                  "feedbackSegment": "feedback/feedback-000001.log",
                  "encoder": "SimpleFrequencyEncoder",
                  "encoderVersion": "1",
                  "normalizer": "LexicalEnrichmentPipeline",
                  "normalizerVersion": "1",
                  "weightingStrategy": "WeightedTokens",
                  "originalWeight": %s,
                  "expansionWeight": %s,
                  "aliasOriginalWeight": %s,
                  "aliasExpansionWeight": %s
                }
                """.formatted(originalWeight, expansionWeight, aliasOriginalWeight, aliasExpansionWeight);
    }

    private static String manifestWithPhysicalField(String field, String value) {
        String scalarType = "FLOAT32";
        String byteOrder = "BIG_ENDIAN";
        String framing = "FIXED_RAW";
        switch (field) {
            case "vectorScalarType" -> scalarType = value;
            case "vectorByteOrder" -> byteOrder = value;
            case "vectorFraming" -> framing = value;
            default -> throw new IllegalArgumentException("Unknown physical format field: " + field);
        }
        return """
                {
                  "version": "0.4",
                  "dimensions": 64,
                  "vectorSegment": "vectors/segment-000001.f32",
                  "atomSegment": "atoms/segment-000001.log",
                  "feedbackSegment": "feedback/feedback-000001.log",
                  "vectorFormatVersion": 1,
                  "vectorScalarType": "%s",
                  "vectorByteOrder": "%s",
                  "vectorFraming": "%s",
                  "vectorDimensions": 64,
                  "vectorIndexFormatVersion": 1
                }
                """.formatted(scalarType, byteOrder, framing);
    }
}
