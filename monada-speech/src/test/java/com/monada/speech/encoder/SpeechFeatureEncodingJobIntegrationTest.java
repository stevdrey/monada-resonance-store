package com.monada.speech.encoder;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechFeatureEncodingJobIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void encodesValidAudioAndReportsSilentMalformedUnsupportedAndExistingSamples() throws IOException {
        Path valid = write("valid.wav", WavTestFixtures.generateSineWave(16_000, 100, 1, 16, 440.0f));
        Path silent = write("silent.wav", WavTestFixtures.generateSilentWav(16_000, 100, 1, 16));
        Path malformed = write("malformed.wav", new byte[44]);
        byte[] unsupportedWav = WavTestFixtures.generateSineWave(16_000, 100, 1, 16, 440.0f);
        ByteBuffer.wrap(unsupportedWav).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 3);
        Path unsupported = write("unsupported.wav", unsupportedWav);

        var sampleStore = new FileSpeechSampleStore(tempDir);
        var featureStore = new FileSpeechFeatureStore(tempDir);
        FrequencyVector existingVector = new FrequencyVector(new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        featureStore.save("existing", existingVector);

        sampleStore.save(sample("unsupported", unsupported, "speaker-b", SpeechCondition.DYSARTHRIC, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("existing", tempDir.resolve("not-read.wav"), "speaker-a", SpeechCondition.CONTROL, SpeechTaskType.WORD));
        sampleStore.save(sample("malformed", malformed, "speaker-a", SpeechCondition.CONTROL, SpeechTaskType.WORD));
        sampleStore.save(sample("silent", silent, "speaker-b", SpeechCondition.DYSARTHRIC, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("valid", valid, "speaker-c", SpeechCondition.UNKNOWN, SpeechTaskType.COMMAND));

        var job = new SpeechFeatureEncodingJob(new BasicAcousticFeatureEncoder(16));
        var report = job.run(sampleStore, featureStore);

        assertEquals(5, report.totalSamples());
        assertEquals(1, report.encodedSamples());
        assertEquals(4, report.skippedSamples());
        assertEquals(1, report.existingFeatureSamples());
        assertEquals(3, report.failedSamples());
        assertEquals(2, report.coveredSamples());
        assertEquals(1, report.vectorsByDimension().get(8));
        assertEquals(1, report.vectorsByDimension().get(16));
        assertTrue(report.failuresByReason().keySet().stream()
                .anyMatch(reason -> reason.contains("silent")));
        assertTrue(report.failuresByReason().keySet().stream()
                .anyMatch(reason -> reason.contains("RIFF")));
        assertTrue(report.failuresByReason().keySet().stream()
                .anyMatch(reason -> reason.contains("Unsupported audio format")));
        assertTrue(featureStore.findBySampleId("valid").isPresent());
        assertFalse(featureStore.findBySampleId("silent").isPresent());
        assertArrayEquals(existingVector.values(), featureStore.findBySampleId("existing").orElseThrow().values());

        Path segment = tempDir.resolve("features/speech-features-000001.f32");
        long featureSegmentSize = Files.size(segment);
        var rerun = job.run(sampleStore, featureStore);

        assertEquals(0, rerun.encodedSamples());
        assertEquals(2, rerun.existingFeatureSamples());
        assertEquals(3, rerun.failedSamples());
        assertEquals(featureSegmentSize, Files.size(segment));
        assertEquals(2, featureStore.findAll().size());
    }

    private Path write(String fileName, byte[] contents) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.write(path, contents);
        return path;
    }

    private static SpeechSample sample(
            String id,
            Path audioPath,
            String speaker,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
        return new SpeechSample(
                id,
                speaker,
                SpeechDatasetSource.TORGO,
                audioPath,
                "transcript " + id,
                List.of(),
                condition,
                taskType,
                "en",
                new AudioMetadata(16_000, 1, 100, "b".repeat(64)),
                Instant.EPOCH);
    }
}
