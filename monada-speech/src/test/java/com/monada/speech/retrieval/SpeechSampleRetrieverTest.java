package com.monada.speech.retrieval;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.encoder.AcousticFeatureEncoder;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeechSampleRetrieverTest {

    @TempDir
    Path tempDir;

    private final AcousticFeatureEncoder encoder = new BasicAcousticFeatureEncoder(64);
    private final SpeechSampleRetriever retriever = new SpeechSampleRetriever(encoder);

    @Test
    void rejectsNullEncoder() {
        assertThrows(NullPointerException.class, () -> new SpeechSampleRetriever(null));
    }

    @Test
    void rejectsNullParameters() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, null);

        assertThrows(NullPointerException.class, () -> retriever.search(null, sampleStore, featureStore, options));
        assertThrows(NullPointerException.class, () -> retriever.search(queryFile, null, featureStore, options));
        assertThrows(NullPointerException.class, () -> retriever.search(queryFile, sampleStore, null, options));
        assertThrows(NullPointerException.class, () -> retriever.search(queryFile, sampleStore, featureStore, null));
    }

    @Test
    void emptyFeatureStoreReturnsEmptyResults() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, null);

        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertTrue(results.isEmpty());
    }

    @Test
    void basicRetrievalReturnsNearestSampleFirst() throws IOException {
        // Create samples with different frequencies
        Path queryFile = createTestWav("query", 440.0f); // A4 note
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create similar sample (same frequency)
        SpeechSample similarSample = createSample("similar", "M01");
        Path similarWav = createTestWav("similar", 440.0f);
        FrequencyVector similarVector = encoder.encode(similarWav);
        sampleStore.save(similarSample);
        featureStore.save(similarSample.id(), similarVector);

        // Create different sample (different frequency)
        SpeechSample differentSample = createSample("different", "M02");
        Path differentWav = createTestWav("different", 880.0f); // A5 note
        FrequencyVector differentVector = encoder.encode(differentWav);
        sampleStore.save(differentSample);
        featureStore.save(differentSample.id(), differentVector);

        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(2, results.size());
        assertEquals(similarSample.id(), results.get(0).sample().id());
        assertEquals(differentSample.id(), results.get(1).sample().id());
        assertTrue(results.get(0).score() > results.get(1).score());
        assertEquals(1, results.get(0).rank());
        assertEquals(2, results.get(1).rank());
    }

    @Test
    void topKTruncationWorks() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create 5 samples
        for (int i = 0; i < 5; i++) {
            SpeechSample sample = createSample("sample" + i, "M0" + i);
            Path wavFile = createTestWav("sample" + i, 440.0f + i * 100.0f);
            FrequencyVector vector = encoder.encode(wavFile);
            sampleStore.save(sample);
            featureStore.save(sample.id(), vector);
        }

        SpeechRetrievalOptions options = new SpeechRetrievalOptions(3, null, null, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(3, results.size());
        assertEquals(1, results.get(0).rank());
        assertEquals(2, results.get(1).rank());
        assertEquals(3, results.get(2).rank());
    }

    @Test
    void rejectsInvalidTopK() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechRetrievalOptions(0, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SpeechRetrievalOptions(-1, null, null, null, null, null));
    }

    @Test
    void filteringByConditionWorks() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create dysarthric sample
        SpeechSample dysarthricSample = createSampleWithCondition("dysarthric", "M01", SpeechCondition.DYSARTHRIC);
        Path dysarthricWav = createTestWav("dysarthric", 440.0f);
        FrequencyVector dysarthricVector = encoder.encode(dysarthricWav);
        sampleStore.save(dysarthricSample);
        featureStore.save(dysarthricSample.id(), dysarthricVector);

        // Create control sample
        SpeechSample controlSample = createSampleWithCondition("control", "M02", SpeechCondition.CONTROL);
        Path controlWav = createTestWav("control", 440.0f);
        FrequencyVector controlVector = encoder.encode(controlWav);
        sampleStore.save(controlSample);
        featureStore.save(controlSample.id(), controlVector);

        // Filter for dysarthric only
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, SpeechCondition.DYSARTHRIC, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(1, results.size());
        assertEquals(dysarthricSample.id(), results.get(0).sample().id());
    }

    @Test
    void filteringByTaskTypeWorks() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create word sample
        SpeechSample wordSample = createSampleWithTaskType("word", "M01", SpeechTaskType.WORD);
        Path wordWav = createTestWav("word", 440.0f);
        FrequencyVector wordVector = encoder.encode(wordWav);
        sampleStore.save(wordSample);
        featureStore.save(wordSample.id(), wordVector);

        // Create sentence sample
        SpeechSample sentenceSample = createSampleWithTaskType("sentence", "M02", SpeechTaskType.SENTENCE);
        Path sentenceWav = createTestWav("sentence", 440.0f);
        FrequencyVector sentenceVector = encoder.encode(sentenceWav);
        sampleStore.save(sentenceSample);
        featureStore.save(sentenceSample.id(), sentenceVector);

        // Filter for words only
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, SpeechTaskType.WORD, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(1, results.size());
        assertEquals(wordSample.id(), results.get(0).sample().id());
    }

    @Test
    void filteringBySpeakerIdWorks() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create sample for M01
        SpeechSample m01Sample = createSample("m01_sample", "M01");
        Path m01Wav = createTestWav("m01_sample", 440.0f);
        FrequencyVector m01Vector = encoder.encode(m01Wav);
        sampleStore.save(m01Sample);
        featureStore.save(m01Sample.id(), m01Vector);

        // Create sample for M02
        SpeechSample m02Sample = createSample("m02_sample", "M02");
        Path m02Wav = createTestWav("m02_sample", 440.0f);
        FrequencyVector m02Vector = encoder.encode(m02Wav);
        sampleStore.save(m02Sample);
        featureStore.save(m02Sample.id(), m02Vector);

        // Filter for M01 only
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, "M01", null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(1, results.size());
        assertEquals(m01Sample.id(), results.get(0).sample().id());
    }

    @Test
    void filteringByLanguageWorks() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create English sample
        SpeechSample englishSample = createSampleWithLanguage("english", "M01", "en-US");
        Path englishWav = createTestWav("english", 440.0f);
        FrequencyVector englishVector = encoder.encode(englishWav);
        sampleStore.save(englishSample);
        featureStore.save(englishSample.id(), englishVector);

        // Create Spanish sample
        SpeechSample spanishSample = createSampleWithLanguage("spanish", "M02", "es-ES");
        Path spanishWav = createTestWav("spanish", 440.0f);
        FrequencyVector spanishVector = encoder.encode(spanishWav);
        sampleStore.save(spanishSample);
        featureStore.save(spanishSample.id(), spanishVector);

        // Filter for English only
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, "en-US");
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(1, results.size());
        assertEquals(englishSample.id(), results.get(0).sample().id());
    }

    @Test
    void orphanFeatureVectorsAreSkipped() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create orphan feature vector (no corresponding sample)
        Path orphanWav = createTestWav("orphan", 440.0f);
        FrequencyVector orphanVector = encoder.encode(orphanWav);
        featureStore.save("orphan_id", orphanVector);

        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertTrue(results.isEmpty());
    }

    @Test
    void deterministicTieBreakingBySampleId() throws IOException {
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create two samples with identical audio (same score)
        SpeechSample sampleA = createSample("a_sample", "M01");
        SpeechSample sampleB = createSample("b_sample", "M02");
        
        Path wavFile = createTestWav("identical", 440.0f);
        FrequencyVector vector = encoder.encode(wavFile);
        
        sampleStore.save(sampleA);
        sampleStore.save(sampleB);
        featureStore.save(sampleA.id(), vector);
        featureStore.save(sampleB.id(), vector);

        SpeechRetrievalOptions options = new SpeechRetrievalOptions(5, null, null, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        assertEquals(2, results.size());
        // Should be ordered by sample ID ascending for deterministic tie-breaking
        assertEquals("a_sample", results.get(0).sample().id());
        assertEquals("b_sample", results.get(1).sample().id());
        assertEquals(results.get(0).score(), results.get(1).score(), 0.001);
    }

    @Test
    void integrationStyleTest() throws IOException {
        // Complete workflow test
        Path queryFile = createTestWav("query", 440.0f);
        SpeechSampleStore sampleStore = createStore();
        SpeechFeatureStore featureStore = createFeatureStore();

        // Create multiple samples with different characteristics
        SpeechSample sample1 = createSampleFull("sample1", "M01", SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD, "en-US");
        SpeechSample sample2 = createSampleFull("sample2", "M02", SpeechCondition.CONTROL, SpeechTaskType.SENTENCE, "en-US");
        SpeechSample sample3 = createSampleFull("sample3", "M03", SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD, "es-ES");

        // Create audio files with different frequencies
        Path wav1 = createTestWav("sample1", 440.0f); // Most similar to query
        Path wav2 = createTestWav("sample2", 880.0f); // Less similar
        Path wav3 = createTestWav("sample3", 220.0f); // Least similar

        // Encode and store
        FrequencyVector vector1 = encoder.encode(wav1);
        FrequencyVector vector2 = encoder.encode(wav2);
        FrequencyVector vector3 = encoder.encode(wav3);

        sampleStore.save(sample1);
        sampleStore.save(sample2);
        sampleStore.save(sample3);

        featureStore.save(sample1.id(), vector1);
        featureStore.save(sample2.id(), vector2);
        featureStore.save(sample3.id(), vector3);

        // Search with filters
        SpeechRetrievalOptions options = new SpeechRetrievalOptions(2, SpeechDatasetSource.TORGO, SpeechCondition.DYSARTHRIC, null, null, null);
        List<SpeechRetrievalResult> results = retriever.search(queryFile, sampleStore, featureStore, options);

        // Should return only dysarthric samples, ordered by similarity
        assertEquals(2, results.size());
        assertEquals(sample1.id(), results.get(0).sample().id());
        assertEquals(sample3.id(), results.get(1).sample().id());
        assertTrue(results.get(0).score() > results.get(1).score());
    }

    // Helper methods

    private Path createTestWav(String name, float frequency) throws IOException {
        Path wavFile = tempDir.resolve(name + ".wav");
        byte[] wavData = generateSineWave(16000, 500, 1, 16, frequency);
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

    private static byte[] buildWavHeader(int sampleRate, int channels, int bitsPerSample, int pcmLength, byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + pcmLength);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, (short) 1); // PCM
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
        for (byte b : s.getBytes()) {
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

    private SpeechSampleStore createStore() throws IOException {
        return new FileSpeechSampleStore(tempDir.resolve("samples"));
    }

    private SpeechFeatureStore createFeatureStore() throws IOException {
        return new FileSpeechFeatureStore(tempDir.resolve("features"));
    }

    private SpeechSample createSample(String id, String speakerId) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                tempDir.resolve(id + ".wav"),
                "test transcript",
                List.of(),
                SpeechCondition.UNKNOWN,
                SpeechTaskType.UNKNOWN,
                "en-US",
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }

    private SpeechSample createSampleWithCondition(String id, String speakerId, SpeechCondition condition) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                tempDir.resolve(id + ".wav"),
                "test transcript",
                List.of(),
                condition,
                SpeechTaskType.UNKNOWN,
                "en-US",
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }

    private SpeechSample createSampleWithTaskType(String id, String speakerId, SpeechTaskType taskType) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                tempDir.resolve(id + ".wav"),
                "test transcript",
                List.of(),
                SpeechCondition.UNKNOWN,
                taskType,
                "en-US",
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }

    private SpeechSample createSampleWithLanguage(String id, String speakerId, String language) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                tempDir.resolve(id + ".wav"),
                "test transcript",
                List.of(),
                SpeechCondition.UNKNOWN,
                SpeechTaskType.UNKNOWN,
                language,
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }

    private SpeechSample createSampleFull(String id, String speakerId, SpeechCondition condition, SpeechTaskType taskType, String language) {
        return new SpeechSample(
                id,
                speakerId,
                SpeechDatasetSource.TORGO,
                tempDir.resolve(id + ".wav"),
                "test transcript",
                List.of(),
                condition,
                taskType,
                language,
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }
}
