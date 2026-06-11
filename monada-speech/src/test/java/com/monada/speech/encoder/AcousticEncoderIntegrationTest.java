package com.monada.speech.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.monada.core.FrequencyVector;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;

class AcousticEncoderIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void encodeAndPersistThroughFeatureStore() throws IOException {
        // Arrange: generate WAV and create encoder + store
        Path wavFile = tempDir.resolve("speech.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        var encoder = new BasicAcousticFeatureEncoder(32);
        var featureStore = new FileSpeechFeatureStore(tempDir);

        // Act: encode audio and save to store
        FrequencyVector vector = encoder.encode(wavFile);
        featureStore.save("sample-001", vector);

        // Assert: reload and verify
        var found = featureStore.findBySampleId("sample-001");
        assertTrue(found.isPresent(), "Vector should be found in store");
        assertArrayEquals(vector.values(), found.get().values(),
                "Retrieved vector should match encoded vector");
    }

    @Test
    void multipleSamplesCanBeEncodedAndStored() throws IOException {
        // Arrange
        var encoder = new BasicAcousticFeatureEncoder(32);
        var featureStore = new FileSpeechFeatureStore(tempDir);

        Path wav1 = tempDir.resolve("speech1.wav");
        Path wav2 = tempDir.resolve("speech2.wav");
        Path wav3 = tempDir.resolve("speech3.wav");

        Files.write(wav1, WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f));
        Files.write(wav2, WavTestFixtures.generateSineWave(16000, 100, 1, 16, 880.0f));
        Files.write(wav3, WavTestFixtures.generateSineWave(8000, 200, 1, 16, 220.0f));

        // Act
        featureStore.save("sample-001", encoder.encode(wav1));
        featureStore.save("sample-002", encoder.encode(wav2));
        featureStore.save("sample-003", encoder.encode(wav3));

        // Assert: findAll returns all three
        var all = featureStore.findAll();
        assertEquals(3, all.size(), "Should have 3 stored samples");
        assertTrue(all.stream().anyMatch(s -> s.sampleId().equals("sample-001")));
        assertTrue(all.stream().anyMatch(s -> s.sampleId().equals("sample-002")));
        assertTrue(all.stream().anyMatch(s -> s.sampleId().equals("sample-003")));
    }

    @Test
    void stereoAudioCanBeEncodedAndStored() throws IOException {
        // Arrange
        Path wavFile = tempDir.resolve("stereo.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 2, 16, 440.0f);
        Files.write(wavFile, wavData);

        var encoder = new BasicAcousticFeatureEncoder(32);
        var featureStore = new FileSpeechFeatureStore(tempDir);

        // Act
        FrequencyVector vector = encoder.encode(wavFile);
        featureStore.save("stereo-sample", vector);

        // Assert
        var found = featureStore.findBySampleId("stereo-sample");
        assertTrue(found.isPresent());
        assertEquals(32, found.get().dimensions());

        // Verify normalized
        float[] values = found.get().values();
        double magnitude = 0.0;
        for (float v : values) {
            magnitude += v * v;
        }
        assertEquals(1.0, Math.sqrt(magnitude), 1e-5, "Stored vector should be normalized");
    }

    @Test
    void storeSurvivesReopen() throws IOException {
        // Arrange: encode and save
        Path wavFile = tempDir.resolve("persistent.wav");
        Files.write(wavFile, WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f));

        var encoder = new BasicAcousticFeatureEncoder(32);
        var store1 = new FileSpeechFeatureStore(tempDir);
        FrequencyVector vector = encoder.encode(wavFile);
        store1.save("persist-sample", vector);

        // Act: reopen store
        var store2 = new FileSpeechFeatureStore(tempDir);

        // Assert
        var found = store2.findBySampleId("persist-sample");
        assertTrue(found.isPresent(), "Vector should survive store reopen");
        assertArrayEquals(vector.values(), found.get().values());
    }

    @Test
    void storedSpeechFeatureVectorHoldsCorrectData() throws IOException {
        Path wavFile = tempDir.resolve("holder.wav");
        Files.write(wavFile, WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f));

        var encoder = new BasicAcousticFeatureEncoder(32);
        var store = new FileSpeechFeatureStore(tempDir);

        FrequencyVector vector = encoder.encode(wavFile);
        store.save("holder-sample", vector);

        var all = store.findAll();
        assertEquals(1, all.size());

        StoredSpeechFeatureVector stored = all.get(0);
        assertEquals("holder-sample", stored.sampleId());
        assertEquals(32, stored.vector().dimensions());
        assertArrayEquals(vector.values(), stored.vector().values());
    }

    @Test
    void differentDimensionsWorkWithStore() throws IOException {
        Path wavFile = tempDir.resolve("dims.wav");
        Files.write(wavFile, WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f));

        // Test with different dimension configurations
        int[] dimensions = {16, 32, 64, 128};

        for (int dims : dimensions) {
            var encoder = new BasicAcousticFeatureEncoder(dims);
            var store = new FileSpeechFeatureStore(tempDir.resolve("store-" + dims));

            FrequencyVector vector = encoder.encode(wavFile);
            store.save("sample-" + dims, vector);

            var found = store.findBySampleId("sample-" + dims);
            assertTrue(found.isPresent(), "Should find sample with " + dims + " dimensions");
            assertEquals(dims, found.get().dimensions());
        }
    }
}
