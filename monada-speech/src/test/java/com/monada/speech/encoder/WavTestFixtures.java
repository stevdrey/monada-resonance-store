package com.monada.speech.encoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WavTestFixtures {

    private WavTestFixtures() {
        // utility class
    }

    static byte[] generateSineWave(
            int sampleRate,
            int durationMs,
            int channels,
            int bitsPerSample,
            float frequencyHz
    ) throws IOException {
        int numSamples = (int) ((sampleRate * durationMs) / 1000.0);
        byte[] pcmData;

        if (bitsPerSample == 8) {
            pcmData = new byte[numSamples * channels];
            for (int i = 0; i < numSamples; i++) {
                double t = i / (double) sampleRate;
                double sample = Math.sin(2 * Math.PI * frequencyHz * t);
                // 8-bit unsigned: 0-255, center at 128
                byte value = (byte) ((sample + 1.0) * 127.5);
                for (int ch = 0; ch < channels; ch++) {
                    pcmData[i * channels + ch] = value;
                }
            }
        } else {
            // 16-bit
            pcmData = new byte[numSamples * channels * 2];
            ByteBuffer buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numSamples; i++) {
                double t = i / (double) sampleRate;
                double sample = Math.sin(2 * Math.PI * frequencyHz * t);
                short value = (short) (sample * 32767);
                for (int ch = 0; ch < channels; ch++) {
                    buffer.putShort(value);
                }
            }
        }

        return buildWavHeader(sampleRate, channels, bitsPerSample, pcmData.length, pcmData);
    }

    static byte[] generateSilentWav(
            int sampleRate,
            int durationMs,
            int channels,
            int bitsPerSample
    ) throws IOException {
        int numSamples = (int) ((sampleRate * durationMs) / 1000.0);
        byte[] pcmData;

        if (bitsPerSample == 8) {
            pcmData = new byte[numSamples * channels];
            // All zeros (128 offset) for silence
            java.util.Arrays.fill(pcmData, (byte) 128);
        } else {
            pcmData = new byte[numSamples * channels * 2];
            // All zeros for silence (already initialized to 0)
        }

        return buildWavHeader(sampleRate, channels, bitsPerSample, pcmData.length, pcmData);
    }

    static byte[] buildWavHeader(
            int sampleRate,
            int channels,
            int bitsPerSample,
            int dataSize,
            byte[] pcmData
    ) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);

        // RIFF chunk descriptor
        baos.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 36 + dataSize); // Chunk size
        baos.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // fmt sub-chunk
        baos.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 16); // Subchunk size (PCM)
        writeShortLE(baos, (short) 1); // Audio format (PCM)
        writeShortLE(baos, (short) channels);
        writeIntLE(baos, sampleRate);
        writeIntLE(baos, byteRate);
        writeShortLE(baos, (short) blockAlign);
        writeShortLE(baos, (short) bitsPerSample);

        // data sub-chunk
        baos.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, dataSize);
        baos.write(pcmData);

        return baos.toByteArray();
    }

    static byte[] truncateWavAfterHeader(int bytesToKeep) {
        // Generate a valid header but with truncated data
        try {
            byte[] full = generateSineWave(16000, 100, 1, 16, 440.0f);
            byte[] truncated = new byte[44 + bytesToKeep];
            System.arraycopy(full, 0, truncated, 0, truncated.length);
            // Fix the data chunk size in header to lie about actual data
            ByteBuffer buffer = ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(40, bytesToKeep + 1000); // Claim more data than exists
            return truncated;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream baos, int value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream baos, short value) throws IOException {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
    }
}
