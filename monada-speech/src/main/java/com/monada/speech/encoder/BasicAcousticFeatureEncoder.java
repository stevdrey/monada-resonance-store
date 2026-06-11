package com.monada.speech.encoder;

import com.monada.core.FrequencyVector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BasicAcousticFeatureEncoder implements AcousticFeatureEncoder {

    private static final int MAX_DIMENSIONS = 1 << 10; // 1024 max
    private static final int WAV_HEADER_SIZE = 44;

    private final int dimensions;

    public BasicAcousticFeatureEncoder(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be greater than zero: " + dimensions);
        }
        if (dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("dimensions exceeds maximum (" + MAX_DIMENSIONS + "): " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public FrequencyVector encode(Path audioPath) throws IOException {
        byte[] data = Files.readAllBytes(audioPath);

        if (data.length < WAV_HEADER_SIZE) {
            throw new IOException("WAV file too short (" + data.length + " bytes, minimum " + WAV_HEADER_SIZE + ")");
        }

        WavHeader header = parseHeader(data);

        int dataOffset = header.dataChunkOffset;
        int dataLength = header.dataChunkSize;

        if (dataOffset + dataLength > data.length) {
            throw new IOException("WAV file truncated: declared data size (" + dataLength +
                    ") exceeds file size at offset " + dataOffset);
        }

        float[] features = extractFeatures(data, dataOffset, dataLength, header);

        float[] vector = projectToVector(features, dimensions);
        normalize(vector);

        return new FrequencyVector(vector);
    }

    private record WavHeader(
            int sampleRate,
            int channels,
            int bitsPerSample,
            int dataChunkOffset,
            int dataChunkSize
    ) {
    }

    private WavHeader parseHeader(byte[] data) throws IOException {
        // RIFF header
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
            throw new IOException("Not a RIFF file (missing RIFF header)");
        }

        // WAVE format
        if (data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            throw new IOException("Not a WAVE file (missing WAVE marker)");
        }

        // fmt chunk
        int fmtOffset = findChunk(data, 12, "fmt ");
        if (fmtOffset < 0) {
            throw new IOException("Missing fmt chunk in WAV file");
        }

        // slice() resets the byte order, so apply LITTLE_ENDIAN after slicing
        ByteBuffer fmtBuffer = ByteBuffer.wrap(data, fmtOffset, 24).slice().order(ByteOrder.LITTLE_ENDIAN);
        int audioFormat = fmtBuffer.getShort() & 0xFFFF;
        int channels = fmtBuffer.getShort() & 0xFFFF;
        int sampleRate = fmtBuffer.getInt();
        fmtBuffer.getInt(); // skip byteRate
        fmtBuffer.getShort(); // skip blockAlign
        int bitsPerSample = fmtBuffer.getShort() & 0xFFFF;

        if (audioFormat != 1) { // PCM = 1
            throw new IOException("Unsupported audio format (not PCM): " + audioFormat);
        }

        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new IOException("Unsupported bits per sample (only 8 or 16): " + bitsPerSample);
        }

        // data chunk
        int dataOffset = findChunk(data, 12, "data");
        if (dataOffset < 0) {
            throw new IOException("Missing data chunk in WAV file");
        }

        int dataChunkSize = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(dataOffset - 4);

        return new WavHeader(sampleRate, channels, bitsPerSample, dataOffset, dataChunkSize);
    }

    private int findChunk(byte[] data, int start, String chunkId) {
        for (int i = start; i <= data.length - 8; i++) {
            if (data[i] == chunkId.charAt(0) &&
                data[i + 1] == chunkId.charAt(1) &&
                data[i + 2] == chunkId.charAt(2) &&
                data[i + 3] == chunkId.charAt(3)) {
                return i + 8; // Skip chunk ID (4) + chunk size (4) to get data
            }
        }
        return -1;
    }

    private float[] extractFeatures(byte[] data, int offset, int length, WavHeader header) throws IOException {
        int sampleCount;
        if (header.bitsPerSample == 8) {
            sampleCount = length / header.channels;
        } else {
            sampleCount = length / (2 * header.channels);
        }

        if (sampleCount == 0) {
            throw new IOException("WAV file contains no audio samples");
        }

        // Extract samples (downmix stereo to mono if needed)
        float[] samples = new float[sampleCount];
        double sumAbs = 0.0;
        double sumSquares = 0.0;
        int zeroCrossings = 0;
        float peakAmplitude = 0.0f;
        int silentSamples = 0;

        float prevSample = 0.0f;

        ByteBuffer pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < sampleCount; i++) {
            float sample;
            if (header.bitsPerSample == 8) {
                // 8-bit is unsigned (0-255), center at 128
                if (header.channels == 1) {
                    int val = data[offset + i] & 0xFF;
                    sample = (val - 128) / 128.0f;
                } else {
                    int left = data[offset + i * 2] & 0xFF;
                    int right = data[offset + i * 2 + 1] & 0xFF;
                    sample = ((left + right) / 2.0f - 128) / 128.0f;
                }
            } else {
                // 16-bit signed little-endian
                int frameOffset = offset + i * header.channels * 2;
                if (header.channels == 1) {
                    short val = pcm.getShort(frameOffset);
                    sample = val / 32768.0f;
                } else {
                    short left = pcm.getShort(frameOffset);
                    short right = pcm.getShort(frameOffset + 2);
                    sample = ((left + right) / 2.0f) / 32768.0f;
                }
            }

            samples[i] = sample;

            float absSample = Math.abs(sample);
            sumAbs += absSample;
            sumSquares += sample * sample;

            if (absSample > peakAmplitude) {
                peakAmplitude = absSample;
            }
            if (absSample < 0.01f) {
                silentSamples++;
            }

            // Zero crossing
            if (i > 0 && ((prevSample > 0 && sample <= 0) || (prevSample <= 0 && sample > 0))) {
                zeroCrossings++;
            }
            prevSample = sample;
        }

        // Windowed energy distribution (divide into windows)
        int numWindows = 8;
        float[] windowEnergies = new float[numWindows];
        int windowSize = Math.max(1, sampleCount / numWindows);

        for (int w = 0; w < numWindows; w++) {
            int start = w * windowSize;
            int end = Math.min(start + windowSize, sampleCount);
            double windowSumSquares = 0.0;
            for (int i = start; i < end; i++) {
                windowSumSquares += samples[i] * samples[i];
            }
            windowEnergies[w] = (float) Math.sqrt(windowSumSquares / (end - start));
        }

        // Assemble features
        // [0] duration in seconds
        // [1] average absolute amplitude
        // [2] RMS energy
        // [3] zero crossing rate (normalized by sample count)
        // [4] peak amplitude
        // [5] silence ratio
        // [6-13] windowed energy distribution (8 windows)

        float durationSec = (float) sampleCount / header.sampleRate;
        float avgAbsAmp = (float) (sumAbs / sampleCount);
        float rmsEnergy = (float) Math.sqrt(sumSquares / sampleCount);
        float zcrRate = (float) zeroCrossings / sampleCount;
        float silenceRatio = (float) silentSamples / sampleCount;

        if (rmsEnergy == 0.0f) {
            throw new IOException("Audio appears to be silent (zero RMS energy); cannot produce a meaningful vector");
        }

        return new float[]{
                durationSec,
                avgAbsAmp,
                rmsEnergy,
                zcrRate,
                peakAmplitude,
                silenceRatio,
                windowEnergies[0],
                windowEnergies[1],
                windowEnergies[2],
                windowEnergies[3],
                windowEnergies[4],
                windowEnergies[5],
                windowEnergies[6],
                windowEnergies[7]
        };
    }

    private float[] projectToVector(float[] features, int targetDimensions) {
        float[] vector = new float[targetDimensions];

        if (targetDimensions <= features.length) {
            // Truncate if dimensions smaller than feature count
            System.arraycopy(features, 0, vector, 0, targetDimensions);
        } else {
            // Copy features, then spread remainder across buckets
            System.arraycopy(features, 0, vector, 0, features.length);

            int remaining = targetDimensions - features.length;
            int bucketSize = Math.max(1, features.length / remaining);

            for (int i = 0; i < remaining; i++) {
                int featureIdx = (i * bucketSize) % features.length;
                vector[features.length + i] = features[featureIdx] * 0.5f;
            }
        }

        return vector;
    }

    private void normalize(float[] vector) throws IOException {
        double magnitude = 0.0;
        for (float value : vector) {
            magnitude += value * value;
        }

        if (magnitude == 0.0) {
            throw new IOException("Cannot normalize: audio appears to be silent (zero magnitude)");
        }

        float norm = (float) Math.sqrt(magnitude);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
