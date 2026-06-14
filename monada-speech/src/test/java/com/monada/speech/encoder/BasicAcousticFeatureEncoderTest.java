package com.monada.speech.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasicAcousticFeatureEncoderTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new BasicAcousticFeatureEncoder(0));
        assertThrows(IllegalArgumentException.class, () -> new BasicAcousticFeatureEncoder(-1));
    }

    @Test
    void rejectsDimensionsExceedingMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new BasicAcousticFeatureEncoder(1025));
    }

    @Test
    void validMonoWavProducesNormalizedVector() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("mono.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(32, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void validStereoWavDownmixesToMono() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("stereo.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 2, 16, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(32, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void valid8BitMonoWavProducesNormalizedVector() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("8bit.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(8000, 100, 1, 8, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(32, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void valid8BitStereoWavProducesNormalizedVector() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("8bit-stereo.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(8000, 100, 2, 8, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(32, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void truncatedWavThrowsClearException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("truncated.wav");
        byte[] truncatedData = WavTestFixtures.truncateWavAfterHeader(10);
        Files.write(wavFile, truncatedData);

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("truncated"));
    }

    @Test
    void fileTooShortThrowsClearException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("short.wav");
        Files.write(wavFile, "RIFF".getBytes());

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("too short"));
    }

    @Test
    void nonRiffFileThrowsClearException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("not-wav.txt");
        Files.writeString(wavFile, "This is not a WAV file content here, padded to exceed the minimum header size of 44 bytes");

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("RIFF"));
    }

    @Test
    void nonWaveFormatThrowsClearException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("not-wave.wav");
        // RIFF header but not WAVE
        byte[] data = new byte[44];
        System.arraycopy("RIFFXXXXAVI ".getBytes(), 0, data, 0, 12);
        Files.write(wavFile, data);

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("WAVE"));
    }

    @Test
    void silentAudioThrowsClearException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("silent.wav");
        byte[] wavData = WavTestFixtures.generateSilentWav(16000, 100, 1, 16);
        Files.write(wavFile, wavData);

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("silent") || ex.getMessage().contains("zero magnitude"));
    }

    @Test
    void encodingIsDeterministicAcrossRuns() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("deterministic.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        var first = encoder.encode(wavFile);
        var second = encoder.encode(wavFile);

        assertArrayEquals(first.values(), second.values());
    }

    @Test
    void differentWavsProduceDifferentVectors() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile1 = tempDir.resolve("tone1.wav");
        Path wavFile2 = tempDir.resolve("tone2.wav");

        byte[] wavData1 = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        byte[] wavData2 = WavTestFixtures.generateSineWave(16000, 200, 1, 16, 880.0f);

        Files.write(wavFile1, wavData1);
        Files.write(wavFile2, wavData2);

        var vector1 = encoder.encode(wavFile1);
        var vector2 = encoder.encode(wavFile2);

        // Vectors should be different (not all equal)
        boolean anyDifferent = false;
        float[] v1 = vector1.values();
        float[] v2 = vector2.values();
        for (int i = 0; i < v1.length; i++) {
            if (Math.abs(v1[i] - v2[i]) > 0.0001f) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "Different audio should produce different vectors");
    }

    @Test
    void vectorDimensionMatchesConfiguration() throws IOException {
        Path wavFile = tempDir.resolve("dims.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        int[] dimsToTest = {16, 32, 64, 128};
        for (int dims : dimsToTest) {
            var encoder = new BasicAcousticFeatureEncoder(dims);
            var vector = encoder.encode(wavFile);
            assertEquals(dims, vector.dimensions(), "Dimensions should match configured: " + dims);
            assertNormalized(vector.values());
        }
    }

    @Test
    void dimensionsSmallerThanFeaturesTruncatesCorrectly() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(8); // Less than feature count
        Path wavFile = tempDir.resolve("small.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(8, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void dimensionsLargerThanFeaturesSpreadsRemainder() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(64); // More than feature count
        Path wavFile = tempDir.resolve("large.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, wavData);

        var vector = encoder.encode(wavFile);

        assertEquals(64, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void rejectsAudioShorterThanEightSamples() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("tiny.wav");
        Files.write(wavFile, WavTestFixtures.generateTinySineWave(4));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("too short"));
    }

    @Test
    void rejectsNegativeDataChunkSize() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("negative-size.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withDataChunkSize(wavData, -1));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("negative"));
    }

    @Test
    void rejectsOverflowingDataChunkSize() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("overflow-size.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withDataChunkSize(wavData, Integer.MAX_VALUE));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("truncated"));
    }

    @Test
    void findsDataChunkAfterListChunkContainingDataBytes() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("list-chunk.wav");
        Files.write(wavFile, WavTestFixtures.generateSineWaveWithListChunk());

        var vector = encoder.encode(wavFile);

        assertEquals(32, vector.dimensions());
        assertNormalized(vector.values());
    }

    @Test
    void rejectsZeroChannels() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("zero-channels.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withChannels(wavData, 0));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("channel count"));
    }

    @Test
    void rejectsMoreThanTwoChannels() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("quad-channels.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withChannels(wavData, 4));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("channel count"));
    }

    @Test
    void rejectsZeroSampleRate() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("zero-rate.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withSampleRate(wavData, 0));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("sample rate"));
    }

    @Test
    void rejectsFmtChunkWithInvalidDeclaredSize() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("small-fmt.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withFmtChunkSize(wavData, 8));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("fmt chunk declares"));
    }

    @Test
    void rejectsPartialPcmFrames() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("partial-frame.wav");
        byte[] wavData = WavTestFixtures.generateSineWave(16000, 100, 1, 16, 440.0f);
        Files.write(wavFile, WavTestFixtures.withExtraDataByte(wavData));

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("partial PCM frame"));
    }

    @Test
    void rejectsDataChunkBeforeFmtChunk() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("data-before-fmt.wav");
        Files.write(wavFile, WavTestFixtures.generateWavWithDataBeforeFmt());

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("Missing data chunk") || ex.getMessage().contains("after fmt"));
    }

    @Test
    void truncatedFmtChunkThrowsIOException() throws IOException {
        var encoder = new BasicAcousticFeatureEncoder(32);
        Path wavFile = tempDir.resolve("truncated-fmt.wav");
        Files.write(wavFile, WavTestFixtures.generateTruncatedFmtChunk());

        var ex = assertThrows(IOException.class, () -> encoder.encode(wavFile));
        assertTrue(ex.getMessage().contains("fmt chunk truncated"));
    }

    private static void assertNormalized(float[] values) {
        double magnitude = 0.0;
        for (float v : values) {
            magnitude += v * v;
        }
        assertEquals(1.0, Math.sqrt(magnitude), 1e-5, "Vector should be L2-normalized");
    }
}
