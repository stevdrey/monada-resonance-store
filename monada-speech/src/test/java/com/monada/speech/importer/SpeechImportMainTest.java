package com.monada.speech.importer;

import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechImportMainTest {

    private static final int DIMENSIONS = 64;
    private static final int SAMPLE_RATE = 16_000;
    private static final int DURATION_MS = 500;

    @TempDir
    Path tempDir;

    @Test
    void importsAndEncodesCorpusIntoPersistentStore() throws IOException {
        Path corpus = createCorpus();
        Path store = tempDir.resolve("speech-store");

        RunResult result = run(configuration(corpus, store, DIMENSIONS));

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Monada Speech Import Report"), result.stdout());
        assertTrue(result.stdout().contains("Imported samples: 1"), result.stdout());
        assertTrue(result.stdout().contains("Newly encoded: 1"), result.stdout());
        assertTrue(Files.isRegularFile(store.resolve("samples/speech-samples-000001.jsonl")));
        assertTrue(Files.isRegularFile(store.resolve("features/speech-features-000001.f32")));
        assertTrue(Files.isRegularFile(store.resolve("indexes/speech-feature-map.idx")));
        assertEquals(1, new FileSpeechSampleStore(store).findAll().size());
        assertEquals(1, new FileSpeechFeatureStore(store).findAll().size());
    }

    @Test
    void rerunReusesExistingFeatureVectors() throws IOException {
        Path corpus = createCorpus();
        Path store = tempDir.resolve("speech-store");
        Map<String, String> configuration = configuration(corpus, store, DIMENSIONS);
        assertEquals(0, run(configuration).exitCode());
        Path featureSegment = store.resolve("features/speech-features-000001.f32");
        long featureSize = Files.size(featureSegment);

        RunResult rerun = run(configuration);

        assertEquals(0, rerun.exitCode());
        assertTrue(rerun.stdout().contains("Newly encoded: 0"), rerun.stdout());
        assertTrue(rerun.stdout().contains("Existing features: 1"), rerun.stdout());
        assertEquals(featureSize, Files.size(featureSegment));
    }

    @Test
    void rejectsIncompatibleExistingFeatureDimensionsBeforeImporting() throws IOException {
        Path corpus = createCorpus();
        Path store = tempDir.resolve("speech-store");
        assertEquals(0, run(configuration(corpus, store, DIMENSIONS)).exitCode());

        RunResult result = run(configuration(corpus, store, 32));

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("feature vector dimensions"), result.stderr());
    }

    @Test
    void readsEnvironmentConfigurationWhenPropertiesAreAbsent() throws IOException {
        Path corpus = createCorpus();
        Path store = tempDir.resolve("speech-store");

        RunResult result = run(Map.of(), Map.of(
                "MONADA_SPEECH_IMPORT_DIR", corpus.toString(),
                "MONADA_SPEECH_IMPORT_STORE", store.toString(),
                "MONADA_SPEECH_IMPORT_DIMS", Integer.toString(DIMENSIONS)));

        assertEquals(0, result.exitCode());
        assertEquals(1, new FileSpeechFeatureStore(store).findAll().size());
    }

    private Path createCorpus() throws IOException {
        Path audioDirectory = tempDir.resolve("corpus/M01/Session1/wav_arrayMic");
        Path promptDirectory = tempDir.resolve("corpus/M01/Session1/prompts");
        Files.createDirectories(audioDirectory);
        Files.createDirectories(promptDirectory);
        Files.write(audioDirectory.resolve("hello.wav"), sineWav());
        Files.writeString(promptDirectory.resolve("hello.txt"), "hello", StandardCharsets.UTF_8);
        return tempDir.resolve("corpus");
    }

    private Map<String, String> configuration(Path corpus, Path store, int dimensions) {
        return Map.of(
                "monada.speech.import.dir", corpus.toString(),
                "monada.speech.import.store", store.toString(),
                "monada.speech.import.dims", Integer.toString(dimensions));
    }

    private RunResult run(Map<String, String> properties) {
        return run(properties, Map.of());
    }

    private RunResult run(Map<String, String> properties, Map<String, String> environment) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = SpeechImportMain.run(
                properties,
                environment,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new RunResult(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static byte[] sineWav() {
        int sampleCount = SAMPLE_RATE * DURATION_MS / 1_000;
        byte[] pcm = new byte[sampleCount * 2];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            samples.putShort((short) (Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 32_767));
        }
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(36 + pcm.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(SAMPLE_RATE);
        wav.putInt(SAMPLE_RATE * 2);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
