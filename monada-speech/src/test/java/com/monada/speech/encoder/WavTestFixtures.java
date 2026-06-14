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

    /**
     * Patches the fmt chunk size field (offset 16) of a canonical 44-byte-header WAV.
     */
    static byte[] withFmtChunkSize(byte[] wav, int declaredSize) {
        byte[] patched = wav.clone();
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putInt(16, declaredSize);
        return patched;
    }

    /**
     * Appends one extra byte to the PCM data section and updates the data chunk size field,
     * producing a data payload that is not a multiple of the PCM frame size (1 byte partial frame).
     */
    static byte[] withExtraDataByte(byte[] wav) {
        byte[] extended = new byte[wav.length + 1];
        System.arraycopy(wav, 0, extended, 0, wav.length);
        int originalDataSize = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40);
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putInt(40, originalDataSize + 1);
        return extended;
    }

    /**
     * Builds a WAVE file where the {@code data} chunk appears before the {@code fmt} chunk,
     * violating the required ordering.
     */
    static byte[] generateWavWithDataBeforeFmt() throws IOException {
        byte[] canonical = generateSineWave(16000, 100, 1, 16, 440.0f);
        byte[] pcmData = new byte[canonical.length - 44];
        System.arraycopy(canonical, 44, pcmData, 0, pcmData.length);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 4 + 8 + pcmData.length + 8 + 16 + 4); // total WAVE body size
        baos.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // data chunk first (wrong order)
        baos.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, pcmData.length);
        baos.write(pcmData);

        // fmt chunk after data (wrong order)
        baos.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 16);
        writeShortLE(baos, (short) 1);       // PCM
        writeShortLE(baos, (short) 1);       // mono
        writeIntLE(baos, 16000);             // sample rate
        writeIntLE(baos, 32000);             // byte rate
        writeShortLE(baos, (short) 2);       // block align
        writeShortLE(baos, (short) 16);      // bits per sample

        return baos.toByteArray();
    }

    /**
     * Generates a mono 16-bit sine WAV containing exactly {@code numSamples} samples.
     */
    static byte[] generateTinySineWave(int numSamples) throws IOException {
        int sampleRate = 16000;
        byte[] pcmData = new byte[numSamples * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) sampleRate;
            double sample = Math.sin(2 * Math.PI * 440.0 * t);
            buffer.putShort((short) (sample * 32767));
        }
        return buildWavHeader(sampleRate, 1, 16, pcmData.length, pcmData);
    }

    /**
     * Patches the channel count (offset 22) of a canonical 44-byte-header WAV.
     */
    static byte[] withChannels(byte[] wav, int channels) {
        byte[] patched = wav.clone();
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putShort(22, (short) channels);
        return patched;
    }

    /**
     * Patches the sample rate (offset 24) of a canonical 44-byte-header WAV.
     */
    static byte[] withSampleRate(byte[] wav, int sampleRate) {
        byte[] patched = wav.clone();
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putInt(24, sampleRate);
        return patched;
    }

    /**
     * Patches the data chunk size (offset 40) of a canonical 44-byte-header WAV.
     */
    static byte[] withDataChunkSize(byte[] wav, int dataChunkSize) {
        byte[] patched = wav.clone();
        ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putInt(40, dataChunkSize);
        return patched;
    }

    /**
     * Generates a valid WAV that contains a LIST chunk between the fmt and data
     * chunks whose payload includes the byte sequences "data" and "fmt ".
     */
    static byte[] generateSineWaveWithListChunk() throws IOException {
        byte[] canonical = generateSineWave(16000, 100, 1, 16, 440.0f);
        byte[] pcmData = new byte[canonical.length - 44];
        System.arraycopy(canonical, 44, pcmData, 0, pcmData.length);

        byte[] listPayload = "INFOdata fmt embedded text!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 36 + 8 + listPayload.length + pcmData.length);
        baos.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // fmt chunk copied verbatim from the canonical file (offsets 12..35)
        baos.write(canonical, 12, 24);

        // LIST chunk with misleading payload
        baos.write("LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, listPayload.length);
        baos.write(listPayload);

        // data chunk
        baos.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, pcmData.length);
        baos.write(pcmData);

        return baos.toByteArray();
    }

    /**
     * Generates a file (>= 44 bytes) whose fmt chunk starts near the end so its
     * 16 declared payload bytes extend past the end of the file.
     */
    static byte[] generateTruncatedFmtChunk() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 44);
        baos.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // Filler chunk pushing the fmt chunk towards the end of the file
        baos.write("JUNK".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 20);
        baos.write(new byte[20]);

        // fmt chunk declaring 16 bytes but only 4 are present
        baos.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeIntLE(baos, 16);
        baos.write(new byte[4]);

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
