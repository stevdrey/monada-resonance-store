package com.monada.speech.evaluation;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpeechRetrievalEvaluatorIntegrationTest {

    @TempDir
    Path tempDir;

    private static final int SAMPLE_RATE = 16000;
    private static final int DURATION_MS = 500;
    private static final int DIMENSIONS = 64;

    @Test
    void integrationWithAcousticEncoderAndFileStores() throws IOException {
        Path storeRoot = tempDir.resolve("store1");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        var retriever = new SpeechSampleRetriever(encoder);
        var evaluator = new SpeechRetrievalEvaluator();

        // Create query audio and sample audios
        Path queryWav = createSineWav("query", 440.0f);
        Path similarWav = createSineWav("similar", 440.0f);
        Path differentWav = createSineWav("different", 880.0f);

        // Store samples
        SpeechSample similarSample = createSample("similar_sample", "M02", similarWav, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        SpeechSample differentSample = createSample("different_sample", "M03", differentWav, SpeechCondition.CONTROL, SpeechTaskType.SENTENCE);

        sampleStore.save(similarSample);
        sampleStore.save(differentSample);

        // Encode and store features
        featureStore.save(similarSample.id(), encoder.encode(similarWav));
        featureStore.save(differentSample.id(), encoder.encode(differentWav));

        // Evaluate: query should retrieve similar first, then different
        var query = new SpeechEvaluationQuery(
                "q1",
                queryWav,
                Set.of("similar_sample"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(2, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(1, report.queryCount());
        assertEquals(1.0, report.hitRateAtK(), 1e-9);
        assertEquals(0.5, report.precisionAtK(), 1e-9);
        assertEquals(1.0, report.recallAtK(), 1e-9);
        assertEquals(1.0, report.meanReciprocalRank(), 1e-9);

        var result = report.queryResults().get(0);
        assertEquals(2, result.retrievedCount());
        assertEquals("similar_sample", result.retrievedSampleIds().get(0));
        assertEquals("different_sample", result.retrievedSampleIds().get(1));
        assertEquals(1, result.relevantRetrievedCount());
        assertTrue(result.hitAtK());
        assertEquals(1.0, result.reciprocalRank(), 1e-9);
        assertEquals(List.of(), result.missedRelevantSampleIds());
        assertEquals("similar_sample", result.topResultSampleId());
        assertTrue(result.topResultScore() > 0.0);
    }

    @Test
    void integrationWithConditionFilterAndGroupedMetrics() throws IOException {
        Path storeRoot = tempDir.resolve("store2");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        var retriever = new SpeechSampleRetriever(encoder);
        var evaluator = new SpeechRetrievalEvaluator();

        Path dysWordWav = createSineWav("dys_word", 440.0f);
        Path dysSentenceWav = createSineWav("dys_sentence", 440.0f);
        Path controlWordWav = createSineWav("control_word", 880.0f);
        Path queryWav = dysWordWav;

        SpeechSample dysWord = createSample("dys_word", "M02", dysWordWav, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        SpeechSample dysSentence = createSample("dys_sentence", "M03", dysSentenceWav, SpeechCondition.DYSARTHRIC, SpeechTaskType.SENTENCE);
        SpeechSample controlWord = createSample("control_word", "M04", controlWordWav, SpeechCondition.CONTROL, SpeechTaskType.WORD);

        sampleStore.save(dysWord);
        sampleStore.save(dysSentence);
        sampleStore.save(controlWord);

        featureStore.save(dysWord.id(), encoder.encode(dysWordWav));
        featureStore.save(dysSentence.id(), encoder.encode(dysSentenceWav));
        featureStore.save(controlWord.id(), encoder.encode(controlWordWav));

        // Query with condition filter: only dysarthric samples
        var query = new SpeechEvaluationQuery(
                "q1",
                queryWav,
                Set.of("dys_word", "dys_sentence"),
                new SpeechRetrievalOptions(5, null, SpeechCondition.DYSARTHRIC, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(1, report.queryCount());
        var result = report.queryResults().get(0);
        assertEquals(2, result.retrievedCount());
        assertEquals(2, result.relevantRetrievedCount());
        assertEquals(2.0 / 5, result.precisionAtK(), 1e-9);
        assertEquals(1.0, result.recallAtK(), 1e-9);
        assertTrue(result.hitAtK());
        assertEquals(1.0, result.reciprocalRank(), 1e-9);

        assertTrue(result.retrievedSampleIds().contains("dys_word"));
        assertTrue(result.retrievedSampleIds().contains("dys_sentence"));
        assertFalse(result.retrievedSampleIds().contains("control_word"));

        var dysarthricMetrics = report.metricsByCondition().get(SpeechCondition.DYSARTHRIC);
        assertNotNull(dysarthricMetrics);
        assertEquals(1, dysarthricMetrics.queryCount());
        assertEquals(1.0, dysarthricMetrics.hitRateAtK(), 1e-9);
        assertTrue(report.queryResults().get(0).hitAtK());

        var wordMetrics = report.metricsByTaskType().get(SpeechTaskType.WORD);
        assertNotNull(wordMetrics);
        assertEquals(1, wordMetrics.queryCount());
    }

    @Test
    void integrationNoRelevantRetrievedProducesZeroHit() throws IOException {
        Path storeRoot = tempDir.resolve("store3");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        var retriever = new SpeechSampleRetriever(encoder);
        var evaluator = new SpeechRetrievalEvaluator();

        Path queryWav = createSineWav("query", 440.0f);
        Path retrievedWav = createSineWav("retrieved", 880.0f);

        SpeechSample retrievedSample = createSample("retrieved", "M02", retrievedWav, SpeechCondition.CONTROL, SpeechTaskType.SENTENCE);

        sampleStore.save(retrievedSample);

        featureStore.save(retrievedSample.id(), encoder.encode(retrievedWav));

        var query = new SpeechEvaluationQuery(
                "q1",
                queryWav,
                Set.of("missing_sample"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(0.0, report.hitRateAtK(), 1e-9);
        assertEquals(0.0, report.precisionAtK(), 1e-9);
        assertEquals(0.0, report.recallAtK(), 1e-9);
        assertEquals(0.0, report.meanReciprocalRank(), 1e-9);

        var result = report.queryResults().get(0);
        assertEquals(1, result.retrievedCount());
        assertEquals(0, result.relevantRetrievedCount());
        assertEquals(List.of("retrieved"), result.retrievedSampleIds());
        assertEquals(List.of("missing_sample"), result.missedRelevantSampleIds());
    }

    // Helpers

    private Path createSineWav(String name, float frequency) throws IOException {
        Path wavFile = tempDir.resolve(name + ".wav");
        byte[] wavData = generateSineWave(SAMPLE_RATE, DURATION_MS, 1, 16, frequency);
        Files.write(wavFile, wavData);
        return wavFile;
    }

    private static byte[] generateSineWave(
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
                byte value = (byte) ((sample + 1.0) * 127.5);
                for (int ch = 0; ch < channels; ch++) {
                    pcmData[i * channels + ch] = value;
                }
            }
        } else {
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

    private static byte[] buildWavHeader(int sampleRate, int channels, int bitsPerSample, int pcmLength, byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + pcmLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, (short) 1);
        writeShortLE(out, (short) channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, (short) blockAlign);
        writeShortLE(out, (short) bitsPerSample);
        writeAscii(out, "data");
        writeIntLE(out, pcmLength);
        out.write(pcm);

        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
            out.write(b);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static SpeechSample createSample(
            String id,
            String speakerId,
            Path audioPath,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                audioPath,
                "test transcript",
                List.of(),
                condition,
                taskType,
                "en-US",
                new AudioMetadata(SAMPLE_RATE, 1, DURATION_MS, "dummy_hash"),
                Instant.now()
        );
    }
}
