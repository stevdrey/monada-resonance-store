package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechSample;
import com.monada.speech.encoder.BasicAcousticFeatureEncoder;
import com.monada.speech.importer.TorgoDatasetImporter;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.FileSpeechFeatureStore;
import com.monada.speech.storage.FileSpeechSampleStore;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exploratory local-corpus smoke test.
 *
 * <p>Disabled (skipped) unless {@code MONADA_SPEECH_BENCHMARK_DIR} points at a local
 * TORGO-style corpus. This guarantees CI failures come only from the protected
 * generated-fixture baseline, never from an absent real dataset. When enabled it imports
 * the corpus, encodes features, and runs the {@link SpeechBenchmarkMode#EXPLORATORY}
 * benchmark with each sample used as a self-query (relevant = itself), asserting the
 * pipeline runs end-to-end on real audio.
 */
@EnabledIfEnvironmentVariable(named = "MONADA_SPEECH_BENCHMARK_DIR", matches = ".+")
class LocalSpeechBenchmarkTest {

    private static final int DIMENSIONS = 64;
    private static final int K = 5;

    @TempDir
    Path tempDir;

    @Test
    void runsExploratoryBenchmarkOnLocalCorpus() throws IOException {
        Path corpusDir = Path.of(System.getenv("MONADA_SPEECH_BENCHMARK_DIR"));
        SpeechSampleStore sampleStore = new FileSpeechSampleStore(tempDir);
        SpeechFeatureStore featureStore = new FileSpeechFeatureStore(tempDir);
        var encoder = new BasicAcousticFeatureEncoder(DIMENSIONS);

        new TorgoDatasetImporter().importFrom(corpusDir, sampleStore);
        List<SpeechSample> samples = sampleStore.findAll();
        assertFalse(samples.isEmpty(), "local corpus produced no importable samples: " + corpusDir);

        for (SpeechSample sample : samples) {
            featureStore.save(sample.id(), encoder.encode(sample.audioPath()));
        }

        List<SpeechEvaluationQuery> queries = new ArrayList<>();
        for (SpeechSample sample : samples) {
            queries.add(new SpeechEvaluationQuery(
                    sample.id(),
                    sample.audioPath(),
                    Set.of(sample.id()),
                    new SpeechRetrievalOptions(K, null, null, null, null, null)));
        }

        var report = new SpeechBenchmarkRunner().run(
                SpeechBenchmarkMode.EXPLORATORY,
                "local-corpus",
                queries,
                new SpeechSampleRetriever(encoder),
                sampleStore,
                featureStore,
                new SpeechEvaluationOptions(K, true));

        assertEquals(samples.size(), report.corpusSize());
        assertEquals(samples.size(), report.queryCount());
        String rendered = report.render();
        assertTrue(rendered.contains("Mode: EXPLORATORY"), rendered);
        // Each sample retrieves itself at rank 1, so hit rate must be perfect.
        assertEquals(1.0, report.evaluationReport().hitRateAtK(), 1e-9);
    }
}
