package com.monada.speech.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.AudioMetadata;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechDatasetSource;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.encoder.AcousticFeatureEncoder;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpeechRetrievalEvaluatorTest {

    @TempDir
    Path tempDir;

    private static final float[] QUERY_VECTOR = {1.0f, 0.0f, 0.0f, 0.0f};

    private final AcousticFeatureEncoder fakeEncoder = new FixedAcousticFeatureEncoder(QUERY_VECTOR);
    private final SpeechSampleRetriever retriever = new SpeechSampleRetriever(fakeEncoder);
    private final SpeechRetrievalEvaluator evaluator = new SpeechRetrievalEvaluator();

    @Test
    void rejectsNullParameters() throws IOException {
        var query = new SpeechEvaluationQuery(
                "q1", tempDir.resolve("query.wav"), Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        var options = new SpeechEvaluationOptions(5, true);

        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(null, retriever, sampleStore, featureStore, options));
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(List.of(query), null, sampleStore, featureStore, options));
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(List.of(query), retriever, null, featureStore, options));
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(List.of(query), retriever, sampleStore, null, options));
        assertThrows(NullPointerException.class,
                () -> evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, null));
    }

    @Test
    void rejectsEmptyQueryList() throws IOException {
        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        var options = new SpeechEvaluationOptions(5, true);

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of(), retriever, sampleStore, featureStore, options));
    }

    @Test
    void rejectsBlankQueryId() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechEvaluationQuery(
                        "  ", tempDir.resolve("query.wav"), Set.of("s1"),
                        new SpeechRetrievalOptions(5, null, null, null, null, null)));
    }

    @Test
    void rejectsEmptyRelevantSet() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechEvaluationQuery(
                        "q1", tempDir.resolve("query.wav"), Set.of(),
                        new SpeechRetrievalOptions(5, null, null, null, null, null)));
    }

    @Test
    void rejectsInvalidK() throws IOException {
        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        var query = new SpeechEvaluationQuery(
                "q1", tempDir.resolve("query.wav"), Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore,
                        new SpeechEvaluationOptions(0, true)));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore,
                        new SpeechEvaluationOptions(-1, true)));
    }

    @Test
    void perfectSingleQueryMatchProducesPerfectMetrics() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", queryAudio, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(1, report.queryCount());
        assertEquals(1.0, report.precisionAtK(), 1e-9);
        assertEquals(1.0, report.recallAtK(), 1e-9);
        assertEquals(1.0, report.hitRateAtK(), 1e-9);
        assertEquals(1.0, report.meanReciprocalRank(), 1e-9);

        var result = report.queryResults().get(0);
        assertEquals("q1", result.queryId());
        assertEquals(1, result.retrievedCount());
        assertEquals(1, result.relevantRetrievedCount());
        assertTrue(result.hitAtK());
        assertEquals(1.0, result.precisionAtK(), 1e-9);
        assertEquals(1.0, result.recallAtK(), 1e-9);
        assertEquals(1.0, result.reciprocalRank(), 1e-9);
        assertEquals(List.of("s1"), result.retrievedSampleIds());
        assertEquals(List.of(), result.missedRelevantSampleIds());
        assertEquals("s1", result.topResultSampleId());
        assertEquals(1.0, result.topResultScore(), 1e-9);
    }

    @Test
    void noHitSingleQueryProducesZeroMetrics() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        featureStore.save("s1", vector(0.0f, 1.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("missing"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(0.0, report.precisionAtK(), 1e-9);
        assertEquals(0.0, report.recallAtK(), 1e-9);
        assertEquals(0.0, report.hitRateAtK(), 1e-9);
        assertEquals(0.0, report.meanReciprocalRank(), 1e-9);

        var result = report.queryResults().get(0);
        assertEquals(1, result.retrievedCount());
        assertEquals(0, result.relevantRetrievedCount());
        assertFalse(result.hitAtK());
        assertEquals(0.0, result.precisionAtK(), 1e-9);
        assertEquals(0.0, result.recallAtK(), 1e-9);
        assertEquals(0.0, result.reciprocalRank(), 1e-9);
        assertEquals(List.of("s1"), result.retrievedSampleIds());
        assertEquals(List.of("missing"), result.missedRelevantSampleIds());
        assertNotNull(result.topResultSampleId());
        assertTrue(result.topResultScore() >= -1.0 && result.topResultScore() <= 1.0);
    }

    @Test
    void multipleRelevantIdsPerQuery() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("s3", "M03", tempDir.resolve("s3.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.5f, 0.5f, 0.0f, 0.0f));
        featureStore.save("s3", vector(0.0f, 1.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1", "s2"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(2, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        // Top 2 retrieved: s1 (score 1.0), s2 (score 0.707...)
        var result = report.queryResults().get(0);
        assertEquals(List.of("s1", "s2"), result.retrievedSampleIds());
        assertEquals(2, result.relevantRetrievedCount());
        assertEquals(1.0, result.precisionAtK(), 1e-9);
        assertEquals(1.0, result.recallAtK(), 1e-9);
        assertEquals(1.0, result.reciprocalRank(), 1e-9);
        assertEquals(List.of(), result.missedRelevantSampleIds());
    }

    @Test
    void multiQueryAggregateMetricsAreAveraged() throws IOException {
        Path queryAudio1 = tempDir.resolve("query1.wav");
        Path queryAudio2 = tempDir.resolve("query2.wav");
        Files.createFile(queryAudio1);
        Files.createFile(queryAudio2);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.0f, 1.0f, 0.0f, 0.0f));

        var query1 = new SpeechEvaluationQuery(
                "q1", queryAudio1, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query2 = new SpeechEvaluationQuery(
                "q2", queryAudio2, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query1, query2), retriever, sampleStore, featureStore, options);

        assertEquals(2, report.queryCount());
        assertEquals(1.0, report.precisionAtK(), 1e-9);
        assertEquals(1.0, report.recallAtK(), 1e-9);
        assertEquals(1.0, report.hitRateAtK(), 1e-9);
        assertEquals(1.0, report.meanReciprocalRank(), 1e-9);
    }

    @Test
    void multiQueryMixedMetricsAreAveragedCorrectly() throws IOException {
        Path queryAudio1 = tempDir.resolve("query1.wav");
        Path queryAudio2 = tempDir.resolve("query2.wav");
        Files.createFile(queryAudio1);
        Files.createFile(queryAudio2);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.0f, 1.0f, 0.0f, 0.0f));

        var query1 = new SpeechEvaluationQuery(
                "q1", queryAudio1, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query2 = new SpeechEvaluationQuery(
                "q2", queryAudio2, Set.of("missing"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query1, query2), retriever, sampleStore, featureStore, options);

        assertEquals(2, report.queryCount());
        assertEquals(0.5, report.precisionAtK(), 1e-9);
        assertEquals(0.5, report.recallAtK(), 1e-9);
        assertEquals(0.5, report.hitRateAtK(), 1e-9);
        assertEquals(0.5, report.meanReciprocalRank(), 1e-9);

        var result1 = report.queryResults().get(0);
        var result2 = report.queryResults().get(1);
        assertEquals("q1", result1.queryId());
        assertEquals("q2", result2.queryId());
        assertEquals(List.of("s1"), result1.retrievedSampleIds());
        assertEquals(List.of("s1"), result2.retrievedSampleIds());
    }

    @Test
    void emptyRetrievalResultProducesZeroMetrics() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(0.0, report.precisionAtK(), 1e-9);
        assertEquals(0.0, report.recallAtK(), 1e-9);
        assertEquals(0.0, report.hitRateAtK(), 1e-9);
        assertEquals(0.0, report.meanReciprocalRank(), 1e-9);

        var result = report.queryResults().get(0);
        assertEquals(0, result.retrievedCount());
        assertEquals(0, result.relevantRetrievedCount());
        assertFalse(result.hitAtK());
        assertEquals(List.of(), result.retrievedSampleIds());
        assertEquals(List.of("s1"), result.missedRelevantSampleIds());
        assertNull(result.topResultSampleId());
        assertEquals(0.0, result.topResultScore(), 1e-9);
    }

    @Test
    void queryOrderIsPreserved() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.5f, 0.5f, 0.0f, 0.0f));

        var query1 = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query2 = new SpeechEvaluationQuery(
                "q2", queryAudio, Set.of("s2"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query3 = new SpeechEvaluationQuery(
                "q3", queryAudio, Set.of("s1", "s2"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query3, query1, query2), retriever, sampleStore, featureStore, options);

        assertEquals(3, report.queryResults().size());
        assertEquals("q3", report.queryResults().get(0).queryId());
        assertEquals("q1", report.queryResults().get(1).queryId());
        assertEquals("q2", report.queryResults().get(2).queryId());
    }

    @Test
    void missedRelevantIdsAreSortedDeterministically() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("s3", "M03", tempDir.resolve("s3.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.0f, 1.0f, 0.0f, 0.0f));
        featureStore.save("s3", vector(-1.0f, 0.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s3", "s1", "s2"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        assertEquals(List.of("s2", "s3"), result.missedRelevantSampleIds());
    }

    @Test
    void perQueryDiagnosticsAreEmptyWhenDisabled() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, false);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertEquals(1, report.queryCount());
        assertEquals(1.0, report.precisionAtK(), 1e-9);
        assertTrue(report.queryResults().isEmpty());
    }

    @Test
    void groupedMetricsByConditionAndTaskType() throws IOException {
        Path queryAudio1 = tempDir.resolve("query1.wav");
        Path queryAudio2 = tempDir.resolve("query2.wav");
        Path queryAudio3 = tempDir.resolve("query3.wav");
        Files.createFile(queryAudio1);
        Files.createFile(queryAudio2);
        Files.createFile(queryAudio3);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", queryAudio1, SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", queryAudio2, SpeechCondition.DYSARTHRIC, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("s3", "M03", queryAudio3, SpeechCondition.CONTROL, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.0f, 1.0f, 0.0f, 0.0f));
        featureStore.save("s3", vector(0.0f, 0.0f, 1.0f, 0.0f));

        var query1 = new SpeechEvaluationQuery(
                "q1", queryAudio1, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query2 = new SpeechEvaluationQuery(
                "q2", queryAudio2, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var query3 = new SpeechEvaluationQuery(
                "q3", queryAudio3, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query1, query2, query3), retriever, sampleStore, featureStore, options);

        assertEquals(2, report.metricsByCondition().size());
        assertEquals(2, report.metricsByTaskType().size());

        var dysarthricMetrics = report.metricsByCondition().get(SpeechCondition.DYSARTHRIC);
        assertNotNull(dysarthricMetrics);
        assertEquals(2, dysarthricMetrics.queryCount());
        assertEquals(1.0, dysarthricMetrics.precisionAtK(), 1e-9);
        assertEquals(1.0, dysarthricMetrics.hitRateAtK(), 1e-9);

        var controlMetrics = report.metricsByCondition().get(SpeechCondition.CONTROL);
        assertNotNull(controlMetrics);
        assertEquals(1, controlMetrics.queryCount());
        assertEquals(1.0, controlMetrics.precisionAtK(), 1e-9);

        var wordMetrics = report.metricsByTaskType().get(SpeechTaskType.WORD);
        assertNotNull(wordMetrics);
        assertEquals(2, wordMetrics.queryCount());
        assertEquals(1.0, wordMetrics.precisionAtK(), 1e-9);

        var sentenceMetrics = report.metricsByTaskType().get(SpeechTaskType.SENTENCE);
        assertNotNull(sentenceMetrics);
        assertEquals(1, sentenceMetrics.queryCount());
        assertEquals(1.0, sentenceMetrics.precisionAtK(), 1e-9);
    }

    @Test
    void unknownGroupingWhenQueryAudioNotInStore() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        assertTrue(report.metricsByCondition().containsKey(SpeechCondition.UNKNOWN));
        assertTrue(report.metricsByTaskType().containsKey(SpeechTaskType.UNKNOWN));
        var unknownConditionMetrics = report.metricsByCondition().get(SpeechCondition.UNKNOWN);
        assertEquals(1, unknownConditionMetrics.queryCount());
        assertEquals(1.0, unknownConditionMetrics.precisionAtK(), 1e-9);
    }

    @Test
    void reciprocalRankIsFirstRelevantRank() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("s3", "M03", tempDir.resolve("s3.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        // Query vector [1,0,0,0], so scores: s1=1.0, s2=0.707, s3=0.0
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.5f, 0.5f, 0.0f, 0.0f));
        featureStore.save("s3", vector(0.0f, 1.0f, 0.0f, 0.0f));

        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s2"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        assertEquals(List.of("s1", "s2", "s3"), result.retrievedSampleIds());
        assertEquals(0.5, result.reciprocalRank(), 1e-9);
        assertEquals(1, result.relevantRetrievedCount());
        assertEquals(1.0 / 5, result.precisionAtK(), 1e-9);
        assertEquals(1.0, result.recallAtK(), 1e-9);
    }

    @Test
    void reciprocalRankIsNonZeroWhenRelevantResultIsBeyondEvaluatorK() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        // Query vector [1,0,0,0].
        // s1=[1,0,0,0] cosine=1.0 (rank 1), s2=[0.5,0.5,0,0] cosine≈0.707 (rank 2),
        // s3=[0,1,0,0] cosine=0.0 (rank 3, tie broken by id "s3" > "s2")
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        sampleStore.save(sample("s3", "M03", tempDir.resolve("s3.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.5f, 0.5f, 0.0f, 0.0f));
        featureStore.save("s3", vector(0.0f, 1.0f, 0.0f, 0.0f));

        // evaluator k=1, retriever topK=5 (returns all 3), only s3 is relevant (rank 3 in full list)
        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s3"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(1, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        assertEquals(1, result.retrievedCount());
        assertEquals(0, result.relevantRetrievedCount(), "s3 is not in top-1 so not counted in @k metrics");
        assertFalse(result.hitAtK(), "hit@k must be false since relevant is not in top-1");
        assertEquals(0.0, result.precisionAtK(), 1e-9);
        assertEquals(0.0, result.recallAtK(), 1e-9);
        // MRR scans the full retrieved list: [s1, s2, s3] → s3 is at position 3 → RR = 1/3
        assertEquals(1.0 / 3, result.reciprocalRank(), 1e-9,
                "MRR must use the first relevant rank in the full retrieved list, not just top-k");
        assertEquals(1.0 / 3, report.meanReciprocalRank(), 1e-9);
    }

    @Test
    void precisionUsesFixedKDenominatorEvenWhenCorpusSmallerThanK() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));

        // k=5 but only 1 sample exists — retrieved.size()=1 < k=5
        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        assertEquals(1, result.retrievedCount());
        assertEquals(1, result.relevantRetrievedCount());
        assertEquals(1.0 / 5, result.precisionAtK(), 1e-9,
                "precision must use k=5 as denominator even if only 1 sample was returned");
        assertEquals(1.0, result.recallAtK(), 1e-9);
        assertEquals(1.0 / 5, report.precisionAtK(), 1e-9);
    }

    @Test
    void reciprocalRankUsesPositionInEvaluatorSliceNotRetrieverRank() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        sampleStore.save(sample("s2", "M02", tempDir.resolve("s2.wav"), SpeechCondition.CONTROL, SpeechTaskType.SENTENCE));
        // Query vector [1,0,0,0]: s1 scores 1.0 (rank 1), s2 scores 0.707 (rank 2)
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.5f, 0.5f, 0.0f, 0.0f));

        // retriever topK=10 (bigger than corpus), evaluator k=2, relevant=s2
        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s2"),
                new SpeechRetrievalOptions(10, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(2, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        assertEquals(List.of("s1", "s2"), result.retrievedSampleIds());
        // s2 is at position 2 in the evaluator slice → RR must be 0.5
        assertEquals(0.5, result.reciprocalRank(), 1e-9,
                "RR must reflect position within the evaluator slice, not the retriever's rank field");
        assertEquals(0.5, report.meanReciprocalRank(), 1e-9);
    }

    @Test
    void missedRelevantIdsAreComputedCorrectlyWithManyRetrievedIds() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        // s1..s4 are in both stores; s5 is in sampleStore only (no feature vector)
        for (int i = 1; i <= 5; i++) {
            sampleStore.save(sample("s" + i, "M0" + i, tempDir.resolve("s" + i + ".wav"),
                    SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        }
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));
        featureStore.save("s2", vector(0.0f, 1.0f, 0.0f, 0.0f));
        featureStore.save("s3", vector(0.0f, 0.0f, 1.0f, 0.0f));
        featureStore.save("s4", vector(0.0f, 0.0f, 0.0f, 1.0f));
        // s5 intentionally has no feature vector so it is never a retrieval candidate

        // relevant includes s3 (retrieved at rank 3) and s5 (never retrieved)
        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s3", "s5"),
                new SpeechRetrievalOptions(5, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        var report = evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options);

        var result = report.queryResults().get(0);
        // s3 is retrieved, s5 has no feature vector so it is never in retrievedIds
        assertTrue(result.missedRelevantSampleIds().contains("s5"),
                "s5 has no feature vector so must appear in missedRelevantSampleIds");
        assertFalse(result.missedRelevantSampleIds().contains("s3"),
                "s3 was retrieved so must not appear in missedRelevantSampleIds");
        assertEquals(result.missedRelevantSampleIds(), result.missedRelevantSampleIds().stream().sorted().toList(),
                "missedRelevantSampleIds must be sorted");
    }

    @Test
    void rejectsQueryWhereRetrievalTopKIsLessThanEvaluationK() throws IOException {
        Path queryAudio = tempDir.resolve("query.wav");
        Files.createFile(queryAudio);

        var sampleStore = new InMemorySampleStore();
        var featureStore = new InMemoryFeatureStore();
        sampleStore.save(sample("s1", "M01", tempDir.resolve("s1.wav"), SpeechCondition.DYSARTHRIC, SpeechTaskType.WORD));
        featureStore.save("s1", vector(1.0f, 0.0f, 0.0f, 0.0f));

        // retrievalOptions.topK()=2 < evaluator k=5
        var query = new SpeechEvaluationQuery(
                "q1", queryAudio, Set.of("s1"),
                new SpeechRetrievalOptions(2, null, null, null, null, null));
        var options = new SpeechEvaluationOptions(5, true);

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of(query), retriever, sampleStore, featureStore, options),
                "retrievalOptions.topK() < evaluation k must throw IllegalArgumentException");
    }

    @Test
    void rejectsInconsistentRetrievedCountAndSampleIds() {
        assertThrows(IllegalArgumentException.class, () ->
                new SpeechQueryEvaluationResult(
                        "q1",
                        3,
                        0,
                        false,
                        0.0,
                        0.0,
                        0.0,
                        List.of("s1", "s2"),
                        List.of(),
                        null,
                        0.0),
                "retrievedCount=3 but only 2 IDs must throw IllegalArgumentException");
    }

    // Helpers

    private static FrequencyVector vector(float... values) {
        return new FrequencyVector(values);
    }

    private static SpeechSample sample(
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
                new AudioMetadata(16000, 1, 500, "dummy_hash"),
                Instant.now()
        );
    }

    private static final class FixedAcousticFeatureEncoder implements AcousticFeatureEncoder {
        private final float[] values;

        FixedAcousticFeatureEncoder(float[] values) {
            this.values = values.clone();
        }

        @Override
        public FrequencyVector encode(Path audioPath) throws IOException {
            return new FrequencyVector(values);
        }
    }

    private static final class InMemorySampleStore implements SpeechSampleStore {
        private final Map<String, SpeechSample> samples = new HashMap<>();

        @Override
        public void save(SpeechSample sample) throws IOException {
            samples.put(sample.id(), sample);
        }

        @Override
        public Optional<SpeechSample> findById(String sampleId) throws IOException {
            return Optional.ofNullable(samples.get(sampleId));
        }

        @Override
        public List<SpeechSample> findBySpeakerId(String speakerId) throws IOException {
            return samples.values().stream()
                    .filter(s -> s.speakerId().equals(speakerId))
                    .toList();
        }

        @Override
        public List<SpeechSample> findAll() throws IOException {
            return List.copyOf(samples.values());
        }
    }

    private static final class InMemoryFeatureStore implements SpeechFeatureStore {
        private final Map<String, FrequencyVector> vectors = new HashMap<>();

        @Override
        public void save(String sampleId, FrequencyVector vector) throws IOException {
            vectors.put(sampleId, vector);
        }

        @Override
        public Optional<FrequencyVector> findBySampleId(String sampleId) throws IOException {
            return Optional.ofNullable(vectors.get(sampleId));
        }

        @Override
        public List<StoredSpeechFeatureVector> findAll() throws IOException {
            List<StoredSpeechFeatureVector> result = new ArrayList<>();
            for (Map.Entry<String, FrequencyVector> entry : vectors.entrySet()) {
                result.add(new StoredSpeechFeatureVector(entry.getKey(), entry.getValue()));
            }
            return result;
        }
    }
}
