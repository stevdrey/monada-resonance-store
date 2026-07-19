package com.monada.speech.encoder;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechFeatureEncodingJobTest {

    @Test
    void aggregatesCoverageFailuresDimensionsAndExistingFeaturesDeterministically() throws IOException {
        var alpha = sample("alpha", "speaker-a", SpeechCondition.CONTROL, SpeechTaskType.WORD);
        var bravo = sample("bravo", "speaker-b", SpeechCondition.DYSARTHRIC, SpeechTaskType.SENTENCE);
        var charlie = sample("charlie", "speaker-a", SpeechCondition.CONTROL, SpeechTaskType.WORD);
        var sampleStore = new InMemorySampleStore(List.of(charlie, bravo, alpha));
        var featureStore = new InMemoryFeatureStore(Map.of("alpha", vector(8)));
        var encoder = new RecordingEncoder(
                Map.of(bravo.audioPath(), vector(4)),
                Map.of(charlie.audioPath(), "Audio appears to be silent"));

        var report = new SpeechFeatureEncodingJob(encoder).run(sampleStore, featureStore);

        assertEquals(3, report.totalSamples());
        assertEquals(1, report.encodedSamples());
        assertEquals(2, report.skippedSamples());
        assertEquals(1, report.existingFeatureSamples());
        assertEquals(1, report.failedSamples());
        assertEquals(2, report.coveredSamples());
        assertEquals(1, featureStore.findAllRequests());
        assertTrue(featureStore.findRequests().isEmpty());
        assertEquals(List.of(bravo.audioPath(), charlie.audioPath()), encoder.encodedPaths());
        assertTrue(featureStore.findBySampleId("bravo").isPresent());

        assertEquals(Map.of(4, 1, 8, 1), report.vectorsByDimension());
        var failure = report.failuresByReason().get("Audio appears to be silent");
        assertEquals(1, failure.count());
        assertEquals(List.of("charlie"), failure.sampleIdExamples());

        assertEquals(new SpeechFeatureEncodingCoverage(2, 0, 1, 1),
                report.coverageBySpeaker().get("speaker-a"));
        assertEquals(new SpeechFeatureEncodingCoverage(1, 1, 0, 0),
                report.coverageBySpeaker().get("speaker-b"));
        assertEquals(new SpeechFeatureEncodingCoverage(2, 0, 1, 1),
                report.coverageByCondition().get(SpeechCondition.CONTROL));
        assertEquals(new SpeechFeatureEncodingCoverage(1, 1, 0, 0),
                report.coverageByTaskType().get(SpeechTaskType.SENTENCE));
        assertFalse(report.render().contains("timestamp"));
    }

    @Test
    void capsFailureExamplesInSortedSampleOrder() throws IOException {
        var samples = new ArrayList<SpeechSample>();
        var failures = new LinkedHashMap<Path, String>();
        for (int i = 7; i >= 1; i--) {
            var sample = sample("sample-" + i, "speaker", SpeechCondition.UNKNOWN, SpeechTaskType.UNKNOWN);
            samples.add(sample);
            failures.put(sample.audioPath(), "unsupported PCM format");
        }

        var report = new SpeechFeatureEncodingJob(new RecordingEncoder(Map.of(), failures))
                .run(new InMemorySampleStore(samples), new InMemoryFeatureStore(Map.of()));

        assertEquals(7, report.failedSamples());
        assertEquals(7, report.skippedSamples());
        var failure = report.failuresByReason().get("unsupported PCM format");
        assertEquals(7, failure.count());
        assertEquals(List.of("sample-1", "sample-2", "sample-3", "sample-4", "sample-5"),
                failure.sampleIdExamples());
    }

    @Test
    void propagatesFeaturePreloadFailureWithoutEncodingSamples() {
        var sample = sample("alpha", "speaker", SpeechCondition.CONTROL, SpeechTaskType.WORD);
        var featureStore = new InMemoryFeatureStore(Map.of(), new IOException("cannot preload feature store"));
        var encoder = new RecordingEncoder(Map.of(sample.audioPath(), vector(4)), Map.of());

        var failure = assertThrows(IOException.class, () -> new SpeechFeatureEncodingJob(encoder)
                .run(new InMemorySampleStore(List.of(sample)), featureStore));

        assertEquals("cannot preload feature store", failure.getMessage());
        assertEquals(1, featureStore.findAllRequests());
        assertTrue(encoder.encodedPaths().isEmpty());
    }

    @Test
    void usesLastPreloadedVectorWhenAStoreReturnsDuplicateSampleIds() throws IOException {
        var sample = sample("alpha", "speaker", SpeechCondition.CONTROL, SpeechTaskType.WORD);
        var featureStore = new PreloadedFeatureStore(List.of(
                new StoredSpeechFeatureVector("alpha", vector(4)),
                new StoredSpeechFeatureVector("alpha", vector(8))));

        var report = new SpeechFeatureEncodingJob(new RecordingEncoder(Map.of(), Map.of()))
                .run(new InMemorySampleStore(List.of(sample)), featureStore);

        assertEquals(1, report.existingFeatureSamples());
        assertEquals(Map.of(8, 1), report.vectorsByDimension());
        assertEquals(1, featureStore.findAllRequests());
    }

    private static SpeechSample sample(
            String id,
            String speaker,
            SpeechCondition condition,
            SpeechTaskType taskType
    ) {
        return new SpeechSample(
                id,
                speaker,
                SpeechDatasetSource.TORGO,
                Path.of("/fixtures/" + id + ".wav"),
                "transcript " + id,
                List.of(),
                condition,
                taskType,
                "en",
                new AudioMetadata(16_000, 1, 100, "a".repeat(64)),
                Instant.EPOCH);
    }

    private static FrequencyVector vector(int dimensions) {
        var values = new float[dimensions];
        values[0] = 1.0f;
        return new FrequencyVector(values);
    }

    private static final class RecordingEncoder implements AcousticFeatureEncoder {
        private final Map<Path, FrequencyVector> vectors;
        private final Map<Path, String> failures;
        private final List<Path> encodedPaths = new ArrayList<>();

        private RecordingEncoder(Map<Path, FrequencyVector> vectors, Map<Path, String> failures) {
            this.vectors = vectors;
            this.failures = failures;
        }

        @Override
        public FrequencyVector encode(Path audioPath) throws IOException {
            encodedPaths.add(audioPath);
            String failure = failures.get(audioPath);
            if (failure != null) {
                throw new IOException(failure);
            }
            return vectors.get(audioPath);
        }

        List<Path> encodedPaths() {
            return List.copyOf(encodedPaths);
        }
    }

    private static final class InMemorySampleStore implements SpeechSampleStore {
        private final List<SpeechSample> samples;

        private InMemorySampleStore(List<SpeechSample> samples) {
            this.samples = new ArrayList<>(samples);
        }

        @Override
        public void save(SpeechSample sample) {
            samples.add(sample);
        }

        @Override
        public Optional<SpeechSample> findById(String sampleId) {
            return samples.stream().filter(sample -> sample.id().equals(sampleId)).findFirst();
        }

        @Override
        public List<SpeechSample> findBySpeakerId(String speakerId) {
            return samples.stream().filter(sample -> sample.speakerId().equals(speakerId)).toList();
        }

        @Override
        public List<SpeechSample> findAll() {
            return List.copyOf(samples);
        }
    }

    private static final class InMemoryFeatureStore implements SpeechFeatureStore {
        private final Map<String, FrequencyVector> features = new LinkedHashMap<>();
        private final List<String> findRequests = new ArrayList<>();
        private final IOException findAllFailure;
        private int findAllRequests;

        private InMemoryFeatureStore(Map<String, FrequencyVector> initialFeatures) {
            this(initialFeatures, null);
        }

        private InMemoryFeatureStore(Map<String, FrequencyVector> initialFeatures, IOException findAllFailure) {
            features.putAll(initialFeatures);
            this.findAllFailure = findAllFailure;
        }

        @Override
        public void save(String sampleId, FrequencyVector vector) {
            features.put(sampleId, vector);
        }

        @Override
        public Optional<FrequencyVector> findBySampleId(String sampleId) {
            findRequests.add(sampleId);
            return Optional.ofNullable(features.get(sampleId));
        }

        @Override
        public List<StoredSpeechFeatureVector> findAll() throws IOException {
            findAllRequests++;
            if (findAllFailure != null) {
                throw findAllFailure;
            }
            return features.entrySet().stream()
                    .map(entry -> new StoredSpeechFeatureVector(entry.getKey(), entry.getValue()))
                    .toList();
        }

        List<String> findRequests() {
            return List.copyOf(findRequests);
        }

        int findAllRequests() {
            return findAllRequests;
        }
    }

    private static final class PreloadedFeatureStore implements SpeechFeatureStore {
        private final List<StoredSpeechFeatureVector> vectors;
        private int findAllRequests;

        private PreloadedFeatureStore(List<StoredSpeechFeatureVector> vectors) {
            this.vectors = List.copyOf(vectors);
        }

        @Override
        public void save(String sampleId, FrequencyVector vector) {
            throw new AssertionError("existing sample must not be saved");
        }

        @Override
        public Optional<FrequencyVector> findBySampleId(String sampleId) {
            throw new AssertionError("batch job must not query individual sample IDs");
        }

        @Override
        public List<StoredSpeechFeatureVector> findAll() {
            findAllRequests++;
            return vectors;
        }

        int findAllRequests() {
            return findAllRequests;
        }
    }
}
