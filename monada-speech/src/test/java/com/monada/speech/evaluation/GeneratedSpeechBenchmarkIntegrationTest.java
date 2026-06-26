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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protected, CI-enforced baseline for the speech benchmark over a deterministic
 * generated-fixture corpus (sine WAVs synthesized in-process).
 *
 * <p>This is the protected counterpart of the exploratory local-corpus benchmark
 * run via {@link SpeechBenchmarkMain}. The corpus is fully generated, requires no
 * downloads, and produces stable metrics, so any change to the acoustic encoder,
 * storage, retrieval, or evaluation that degrades recall is caught here.
 *
 * <p>If a legitimate change alters these pinned values, update the
 * {@link SpeechBenchmarkBaseline} constants in this file in the same commit with a
 * rationale (mirrors the policy in {@code EvaluationBaselineRegressionTest}).
 */
class GeneratedSpeechBenchmarkIntegrationTest {

    @TempDir
    Path tempDir;

    private static final int SAMPLE_RATE = 16000;
    private static final int DURATION_MS = 500;
    private static final int DIMENSIONS = 64;
    private static final int K = 2;

    // Protected baseline for the generated three-sample corpus and two queries below.
    // Each query reuses a stored sample's audio, so its same-frequency sample ranks first
    // (rank 1 -> hit, recall 1.0, RR 1.0); with k=2 and one relevant per query precision is 0.5.
    private static final int EXPECTED_CORPUS_SIZE = 3;
    private static final int EXPECTED_QUERY_COUNT = 2;
    private static final double EXPECTED_PRECISION_AT_K = 0.5;
    private static final double EXPECTED_RECALL_AT_K = 1.0;
    private static final double EXPECTED_HIT_RATE_AT_K = 1.0;
    private static final double EXPECTED_MRR = 1.0;
    private static final double TOLERANCE = 1e-9;

    @Test
    void generatedCorpusMeetsProtectedBaseline() throws IOException {
        Path storeRoot = tempDir.resolve("store");
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        var retriever = new SpeechSampleRetriever(encoder);

        Path wav440 = createSineWav("s440", 440.0f);
        Path wav880 = createSineWav("s880", 880.0f);
        Path wav1760 = createSineWav("s1760", 1760.0f);

        SpeechSample s440 = createSample("s_440", "M02", wav440, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        SpeechSample s880 = createSample("s_880", "C01", wav880, SpeechCondition.CONTROL, SpeechTaskType.WORD);
        SpeechSample s1760 = createSample("s_1760", "C02", wav1760, SpeechCondition.CONTROL, SpeechTaskType.SENTENCE);

        sampleStore.save(s440);
        sampleStore.save(s880);
        sampleStore.save(s1760);
        featureStore.save(s440.id(), encoder.encode(wav440));
        featureStore.save(s880.id(), encoder.encode(wav880));
        featureStore.save(s1760.id(), encoder.encode(wav1760));

        var queryA = new SpeechEvaluationQuery(
                "qA", wav440, Set.of("s_440"),
                new SpeechRetrievalOptions(K, null, null, null, null, null));
        var queryB = new SpeechEvaluationQuery(
                "qB", wav880, Set.of("s_880"),
                new SpeechRetrievalOptions(K, null, null, null, null, null));

        var runner = new SpeechBenchmarkRunner();
        var options = new SpeechEvaluationOptions(K, true);

        SpeechBenchmarkReport report = runner.run(
                SpeechBenchmarkMode.PROTECTED,
                "generated-sine-corpus",
                List.of(queryA, queryB),
                retriever,
                sampleStore,
                featureStore,
                options);

        assertEquals(SpeechBenchmarkMode.PROTECTED, report.mode());
        assertEquals(EXPECTED_CORPUS_SIZE, report.corpusSize());
        assertEquals(EXPECTED_QUERY_COUNT, report.queryCount());
        assertEquals(K, report.k());

        var baseline = new SpeechBenchmarkBaseline(
                EXPECTED_CORPUS_SIZE, EXPECTED_QUERY_COUNT, K,
                EXPECTED_PRECISION_AT_K, EXPECTED_RECALL_AT_K, EXPECTED_HIT_RATE_AT_K, EXPECTED_MRR,
                TOLERANCE);
        var comparison = baseline.compare(report.evaluationReport(), report.corpusSize());
        assertTrue(comparison.passed(),
                "protected baseline mismatch: " + comparison.mismatches());

        String rendered = report.render();
        assertTrue(rendered.contains("Mode: PROTECTED"), rendered);
        assertTrue(rendered.contains("enforced in CI"), rendered);
        assertTrue(rendered.contains("Label: generated-sine-corpus"), rendered);
    }

    @Test
    void renderIsDeterministicAcrossFreshStores() throws IOException {
        String first = runAndRender(tempDir.resolve("a"));
        String second = runAndRender(tempDir.resolve("b"));
        assertEquals(first, second, "benchmark render must be deterministic across fresh stores");
    }

    private String runAndRender(Path storeRoot) throws IOException {
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(storeRoot);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);
        var retriever = new SpeechSampleRetriever(encoder);

        Path wav440 = createSineWav(storeRoot.getFileName() + "-s440", 440.0f);
        Path wav880 = createSineWav(storeRoot.getFileName() + "-s880", 880.0f);

        SpeechSample s440 = createSample("s_440", "M02", wav440, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        SpeechSample s880 = createSample("s_880", "C01", wav880, SpeechCondition.CONTROL, SpeechTaskType.WORD);
        sampleStore.save(s440);
        sampleStore.save(s880);
        featureStore.save(s440.id(), encoder.encode(wav440));
        featureStore.save(s880.id(), encoder.encode(wav880));

        var queryA = new SpeechEvaluationQuery(
                "qA", wav440, Set.of("s_440"),
                new SpeechRetrievalOptions(K, null, null, null, null, null));

        var report = new SpeechBenchmarkRunner().run(
                SpeechBenchmarkMode.PROTECTED, "determinism-check",
                List.of(queryA), retriever, sampleStore, featureStore,
                new SpeechEvaluationOptions(K, true));
        // Strip the label line, which intentionally encodes the per-run store name.
        return report.render().lines()
                .filter(line -> !line.startsWith("Label:"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    // Helpers (sine-WAV synthesis mirrors SpeechRetrievalEvaluatorIntegrationTest)

    private Path createSineWav(String name, float frequency) throws IOException {
        Path wavFile = tempDir.resolve(name + ".wav");
        Files.write(wavFile, generateSineWave(SAMPLE_RATE, DURATION_MS, 1, 16, frequency));
        return wavFile;
    }

    private static byte[] generateSineWave(
            int sampleRate, int durationMs, int channels, int bitsPerSample, float frequencyHz) {
        int numSamples = (int) ((sampleRate * durationMs) / 1000.0);
        byte[] pcmData = new byte[numSamples * channels * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            double t = i / (double) sampleRate;
            double sample = Math.sin(2 * Math.PI * frequencyHz * t);
            short value = (short) (sample * 32767);
            for (int ch = 0; ch < channels; ch++) {
                buffer.putShort(value);
            }
        }
        return buildWavHeader(sampleRate, channels, bitsPerSample, pcmData.length, pcmData);
    }

    private static byte[] buildWavHeader(
            int sampleRate, int channels, int bitsPerSample, int pcmLength, byte[] pcm) {
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
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
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
            String id, String speakerId, Path audioPath,
            SpeechCondition condition, SpeechTaskType taskType) {
        return new SpeechSample(
                id, speakerId, SpeechDatasetSource.TORGO, audioPath,
                "test transcript", List.of(), condition, taskType, "en-US",
                new AudioMetadata(SAMPLE_RATE, 1, DURATION_MS, "dummy_hash"),
                Instant.EPOCH);
    }
}
