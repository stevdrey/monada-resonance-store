package com.monada.speech.importer;

import com.monada.speech.domain.AudioMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reads basic WAV metadata from a PCM WAV file using JDK NIO only.
 *
 * <p>Supports: RIFF/WAVE container, PCM format (audioFormat = 1),
 * 8-bit or 16-bit, mono or stereo, little-endian samples.
 *
 * <p>Throws {@link IOException} for truncated, malformed, or unsupported files.
 */
final class WavMetadataReader {

    private static final int MIN_HEADER_SIZE = 44;
    private static final String RIFF_MAGIC = "RIFF";
    private static final String WAVE_MAGIC = "WAVE";
    private static final String FMT_CHUNK  = "fmt ";
    private static final String DATA_CHUNK = "data";
    private static final int PCM_FORMAT    = 1;

    private WavMetadataReader() {
        // utility class
    }

    /**
     * Reads an {@link AudioMetadata} from the given WAV file.
     *
     * @param wavPath path to a WAV file
     * @return populated AudioMetadata
     * @throws IOException if the file cannot be read or is not a supported WAV
     */
    static AudioMetadata read(Path wavPath) throws IOException {
        byte[] data = Files.readAllBytes(wavPath);

        if (data.length < MIN_HEADER_SIZE) {
            throw new IOException("WAV file too short (" + data.length + " bytes): " + wavPath);
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        validateAscii(buf, 4, RIFF_MAGIC, wavPath);
        buf.position(8);
        validateAscii(buf, 4, WAVE_MAGIC, wavPath);

        // Scan for fmt  and data chunks
        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int dataChunkSize = 0;
        boolean foundFmt = false;
        boolean foundData = false;

        buf.position(12);
        while (buf.remaining() >= 8) {
            byte[] chunkIdBytes = new byte[4];
            buf.get(chunkIdBytes);
            String chunkId = new String(chunkIdBytes, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = buf.getInt();

            if (chunkSize < 0) {
                throw new IOException("Negative chunk size in WAV file: " + wavPath);
            }

            if (FMT_CHUNK.equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IOException("fmt chunk too small (" + chunkSize + "): " + wavPath);
                }
                int audioFormat = buf.getShort() & 0xFFFF;
                if (audioFormat != PCM_FORMAT) {
                    throw new IOException("Unsupported WAV audio format " + audioFormat +
                            " (only PCM=1 supported): " + wavPath);
                }
                channels = buf.getShort() & 0xFFFF;
                sampleRate = buf.getInt();
                buf.getInt(); // byteRate — skip
                buf.getShort(); // blockAlign — skip
                bitsPerSample = buf.getShort() & 0xFFFF;
                if (bitsPerSample != 8 && bitsPerSample != 16) {
                    throw new IOException("Unsupported bits-per-sample " + bitsPerSample +
                            " (only 8 or 16 supported): " + wavPath);
                }
                // skip any extra fmt bytes
                int extra = chunkSize - 16;
                if (extra > 0) {
                    buf.position(buf.position() + extra);
                }
                foundFmt = true;
            } else if (DATA_CHUNK.equals(chunkId)) {
                dataChunkSize = chunkSize;
                foundData = true;
                break;
            } else {
                // skip unknown chunk
                if (buf.remaining() < chunkSize) {
                    throw new IOException("WAV file truncated at chunk '" + chunkId + "': " + wavPath);
                }
                buf.position(buf.position() + chunkSize);
            }
        }

        if (!foundFmt) {
            throw new IOException("Missing fmt chunk in WAV file: " + wavPath);
        }
        if (!foundData) {
            throw new IOException("Missing data chunk in WAV file: " + wavPath);
        }
        if (sampleRate <= 0) {
            throw new IOException("Invalid sample rate " + sampleRate + " in WAV file: " + wavPath);
        }
        if (channels <= 0) {
            throw new IOException("Invalid channel count " + channels + " in WAV file: " + wavPath);
        }

        int bytesPerSample = bitsPerSample / 8;
        int bytesPerFrame = channels * bytesPerSample;
        long totalFrames = bytesPerFrame > 0 ? dataChunkSize / bytesPerFrame : 0;
        long durationMs = sampleRate > 0 ? (totalFrames * 1000L) / sampleRate : 0L;

        String sha256 = sha256Hex(data);

        return new AudioMetadata(sampleRate, channels, durationMs, sha256);
    }

    private static void validateAscii(ByteBuffer buf, int len, String expected, Path path)
            throws IOException {
        byte[] bytes = new byte[len];
        buf.get(bytes);
        String actual = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        if (!expected.equals(actual)) {
            throw new IOException("Expected '" + expected + "' but found '" + actual +
                    "' in WAV file: " + path);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
