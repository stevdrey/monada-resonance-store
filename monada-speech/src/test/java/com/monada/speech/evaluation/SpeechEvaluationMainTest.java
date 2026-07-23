package com.monada.speech.evaluation;

import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
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
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechEvaluationMainTest {

    private static final int DIMENSIONS = 64;
    private static final int SAMPLE_RATE = 16000;
    private static final int DURATION_MS = 500;

    @TempDir
    Path tempDir;

    @Test
    void runsExistingStoreWithExtendedQueriesAndDeterministicReport() throws IOException {
        Fixture fixture = createFixture();
        Map<String, String> properties = configuration(fixture.storeRoot(), fixture.manifest());

        RunResult first = run(properties);
        RunResult second = run(properties);

        assertEquals(0, first.exitCode());
        assertEquals("", first.stderr());
        assertEquals(first.stdout(), second.stdout());
        assertTrue(first.stdout().contains("Mode: EXPLORATORY"), first.stdout());
        assertTrue(first.stdout().contains("Label: fixture-store"), first.stdout());
        assertTrue(first.stdout().contains("Query count: 2"), first.stdout());
        assertTrue(first.stdout().contains("q_440: retrieved=[s_440]"), first.stdout());
        assertTrue(first.stdout().contains("q_880: retrieved=[s_880]"), first.stdout());
        assertTrue(first.stdout().indexOf("q_440:") < first.stdout().indexOf("q_880:"), first.stdout());
        assertFalse(first.stdout().contains("scan: vectors="), first.stdout());
    }

    @Test
    void optInDiagnosticsRenderDeterministicScanAndRelevantCandidateFields() throws IOException {
        Fixture fixture = createFixture();
        Map<String, String> properties = new java.util.HashMap<>(
                configuration(fixture.storeRoot(), fixture.manifest()));
        properties.put("monada.speech.evaluation.diagnostics", "true");

        RunResult first = run(Map.copyOf(properties));
        RunResult second = run(Map.copyOf(properties));

        assertEquals(0, first.exitCode());
        assertEquals("", first.stderr());
        assertEquals(first.stdout(), second.stdout());
        assertTrue(first.stdout().contains(
                "scan: vectors=2 orphan=0 incompatible=0 filtered=1 scored=1 ties=0"), first.stdout());
        assertTrue(first.stdout().contains(
                "relevant s_440: status=RETRIEVED_AT_K rank=1"), first.stdout());
    }

    @Test
    void reportsConfigurationFailuresWithoutSystemExit() throws IOException {
        RunResult missing = run(Map.of());
        assertEquals(2, missing.exitCode());
        assertTrue(missing.stderr().contains("provide both"), missing.stderr());

        Path incompleteStore = tempDir.resolve("incomplete-store");
        Files.createDirectories(incompleteStore);
        Path manifest = tempDir.resolve("queries.tsv");
        Files.writeString(manifest, "", StandardCharsets.UTF_8);
        RunResult incomplete = run(configuration(incompleteStore, manifest));
        assertEquals(2, incomplete.exitCode());
        assertTrue(incomplete.stderr().contains("speech sample log"), incomplete.stderr());
    }

    @Test
    void rejectsFeatureDimensionsThatDoNotMatchTheConfiguredEncoder() throws IOException {
        Fixture fixture = createFixture();
        Map<String, String> properties = new java.util.HashMap<>(configuration(fixture.storeRoot(), fixture.manifest()));
        properties.put("monada.speech.evaluation.dims", "32");

        RunResult result = run(Map.copyOf(properties));

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("feature vector dimensions"), result.stderr());
    }

    @Test
    void systemPropertyOverridesEnvironmentConfiguration() {
        Path propertyStore = tempDir.resolve("property-store");
        Path propertyManifest = tempDir.resolve("property.tsv");
        var configuration = SpeechEvaluationMain.parseConfiguration(
                Map.of(
                        "monada.speech.evaluation.store", propertyStore.toString(),
                        "monada.speech.evaluation.queries", propertyManifest.toString(),
                        "monada.speech.evaluation.k", "2",
                        "monada.speech.evaluation.diagnostics", "true"),
                Map.of(
                        "MONADA_SPEECH_EVALUATION_STORE", tempDir.resolve("environment-store").toString(),
                        "MONADA_SPEECH_EVALUATION_QUERIES", tempDir.resolve("environment.tsv").toString(),
                        "MONADA_SPEECH_EVALUATION_K", "4",
                        "MONADA_SPEECH_EVALUATION_DIAGNOSTICS", "false"));

        assertEquals(propertyStore, configuration.storeRoot());
        assertEquals(propertyManifest, configuration.queryManifest());
        assertEquals(2, configuration.k());
        assertTrue(configuration.diagnostics());

        var environmentOnly = SpeechEvaluationMain.parseConfiguration(
                Map.of(),
                Map.of(
                        "MONADA_SPEECH_EVALUATION_STORE", tempDir.resolve("environment-store").toString(),
                        "MONADA_SPEECH_EVALUATION_QUERIES", tempDir.resolve("environment.tsv").toString(),
                        "MONADA_SPEECH_EVALUATION_K", "4",
                        "MONADA_SPEECH_EVALUATION_DIAGNOSTICS", "true"));
        assertEquals(tempDir.resolve("environment-store"), environmentOnly.storeRoot());
        assertEquals(tempDir.resolve("environment.tsv"), environmentOnly.queryManifest());
        assertEquals(4, environmentOnly.k());
        assertTrue(environmentOnly.diagnostics());

        assertThrows(
                IllegalArgumentException.class,
                () -> SpeechEvaluationMain.parseConfiguration(
                        Map.of(
                                "monada.speech.evaluation.store", propertyStore.toString(),
                                "monada.speech.evaluation.queries", propertyManifest.toString(),
                                "monada.speech.evaluation.diagnostics", "yes"),
                        Map.of()));
    }

    private Fixture createFixture() throws IOException {
        Path storeRoot = tempDir.resolve("fixture-store");
        var sampleStore = new FileSpeechSampleStore(storeRoot);
        var featureStore = new FileSpeechFeatureStore(storeRoot);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);

        Path wav440 = writeSineWav("q440", 440.0f);
        Path wav880 = writeSineWav("q880", 880.0f);
        SpeechSample sample440 = sample("s_440", "M01", wav440, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD);
        SpeechSample sample880 = sample("s_880", "C01", wav880, SpeechCondition.CONTROL, SpeechTaskType.SENTENCE);
        sampleStore.save(sample440);
        sampleStore.save(sample880);
        featureStore.save(sample440.id(), encoder.encode(wav440));
        featureStore.save(sample880.id(), encoder.encode(wav880));

        Path manifest = tempDir.resolve("queries.tsv");
        Files.writeString(manifest, "# deterministic fixture\n"
                + "q_440\tq440.wav\ts_440\t1\tTORGO\tDYSARTHRIC\tWORD\tM01\ten-US\n"
                + "q_880\tq880.wav\ts_880\t1\tTORGO\tCONTROL\tSENTENCE\tC01\ten-US\n",
                StandardCharsets.UTF_8);
        return new Fixture(storeRoot, manifest);
    }

    private Map<String, String> configuration(Path storeRoot, Path manifest) {
        return Map.of(
                "monada.speech.evaluation.store", storeRoot.toString(),
                "monada.speech.evaluation.queries", manifest.toString(),
                "monada.speech.evaluation.k", "1",
                "monada.speech.evaluation.dims", Integer.toString(DIMENSIONS));
    }

    private RunResult run(Map<String, String> properties) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = SpeechEvaluationMain.run(
                properties,
                Map.of(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new RunResult(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private Path writeSineWav(String name, float frequencyHz) throws IOException {
        Path wav = tempDir.resolve(name + ".wav");
        Files.write(wav, sineWav(frequencyHz));
        return wav;
    }

    private static byte[] sineWav(float frequencyHz) {
        int sampleCount = SAMPLE_RATE * DURATION_MS / 1000;
        byte[] pcm = new byte[sampleCount * 2];
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            samples.putShort((short) (Math.sin(2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE) * 32767));
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

    private static SpeechSample sample(
            String id, String speakerId, Path audioPath, SpeechCondition condition, SpeechTaskType taskType) {
        return new SpeechSample(
                id, speakerId, SpeechDatasetSource.TORGO, audioPath, "fixture", java.util.List.of(),
                condition, taskType, "en-US", new AudioMetadata(SAMPLE_RATE, 1, DURATION_MS, "fixture"), Instant.EPOCH);
    }

    private record Fixture(Path storeRoot, Path manifest) {
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
    }
}
