package com.monada.speech.encoder;

import com.monada.core.FrequencyVector;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;
import com.monada.speech.storage.StoredSpeechFeatureVector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Sequentially encodes speech samples and reports deterministic feature coverage.
 *
 * <p>Existing feature vectors are loaded once before processing so duplicate checks do not
 * repeatedly scan the file-backed index. Samples with an existing vector are intentionally not
 * rewritten because the current feature store is append-only. Audio encoding failures are
 * recorded in the returned report; storage failures are propagated to the caller.
 */
public final class SpeechFeatureEncodingJob {

    private final AcousticFeatureEncoder encoder;

    public SpeechFeatureEncodingJob(AcousticFeatureEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /**
     * Encodes every sample visible from {@code sampleStore}, in ascending sample-ID order.
     *
     * @throws IOException if either store cannot be read or written
     */
    public SpeechFeatureEncodingReport run(
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore
    ) throws IOException {
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");

        List<SpeechSample> samples = new ArrayList<>(sampleStore.findAll());
        samples.sort(Comparator.comparing(SpeechSample::id));
        Map<String, FrequencyVector> existingVectors = loadExistingVectors(featureStore);

        var coverageBySpeaker = new TreeMap<String, CoverageAccumulator>();
        var coverageByCondition = new EnumMap<SpeechCondition, CoverageAccumulator>(SpeechCondition.class);
        var coverageByTaskType = new EnumMap<SpeechTaskType, CoverageAccumulator>(SpeechTaskType.class);
        var failuresByReason = new TreeMap<String, FailureAccumulator>();
        var vectorsByDimension = new TreeMap<Integer, Integer>();

        int encoded = 0;
        int existing = 0;
        int failed = 0;

        for (SpeechSample sample : samples) {
            CoverageAccumulator speakerCoverage = coverageBySpeaker.computeIfAbsent(
                    sample.speakerId(), ignored -> new CoverageAccumulator());
            CoverageAccumulator conditionCoverage = coverageByCondition.computeIfAbsent(
                    sample.condition(), ignored -> new CoverageAccumulator());
            CoverageAccumulator taskCoverage = coverageByTaskType.computeIfAbsent(
                    sample.taskType(), ignored -> new CoverageAccumulator());
            speakerCoverage.recordTotal();
            conditionCoverage.recordTotal();
            taskCoverage.recordTotal();

            FrequencyVector existingVector = existingVectors.get(sample.id());
            if (existingVector != null) {
                existing++;
                recordDimension(vectorsByDimension, existingVector);
                speakerCoverage.recordExisting();
                conditionCoverage.recordExisting();
                taskCoverage.recordExisting();
                continue;
            }

            FrequencyVector vector;
            try {
                vector = encoder.encode(sample.audioPath());
            } catch (IOException e) {
                failed++;
                String reason = failureReason(e);
                failuresByReason.computeIfAbsent(reason, ignored -> new FailureAccumulator())
                        .record(sample.id());
                speakerCoverage.recordFailed();
                conditionCoverage.recordFailed();
                taskCoverage.recordFailed();
                continue;
            }

            featureStore.save(sample.id(), vector);
            existingVectors.put(sample.id(), vector);
            encoded++;
            recordDimension(vectorsByDimension, vector);
            speakerCoverage.recordEncoded();
            conditionCoverage.recordEncoded();
            taskCoverage.recordEncoded();
        }

        return new SpeechFeatureEncodingReport(
                samples.size(),
                encoded,
                existing + failed,
                existing,
                toFailureGroups(failuresByReason),
                vectorsByDimension,
                toCoverage(coverageBySpeaker),
                toCoverage(coverageByCondition),
                toCoverage(coverageByTaskType));
    }

    private static Map<String, FrequencyVector> loadExistingVectors(SpeechFeatureStore featureStore)
            throws IOException {
        return featureStore.findAll().stream()
                .collect(Collectors.toMap(
                        StoredSpeechFeatureVector::sampleId,
                        StoredSpeechFeatureVector::vector,
                        (first, replacement) -> replacement,
                        LinkedHashMap::new));
    }

    private static void recordDimension(Map<Integer, Integer> vectorsByDimension, FrequencyVector vector) {
        vectorsByDimension.merge(vector.dimensions(), 1, Integer::sum);
    }

    private static String failureReason(IOException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static Map<String, SpeechFeatureEncodingFailureGroup> toFailureGroups(
            Map<String, FailureAccumulator> accumulators
    ) {
        return accumulators.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().toGroup(entry.getKey()),
                        (first, second) -> first,
                        TreeMap::new));
    }

    private static <K> Map<K, SpeechFeatureEncodingCoverage> toCoverage(
            Map<K, CoverageAccumulator> accumulators
    ) {
        return accumulators.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().toCoverage()));
    }

    private static final class CoverageAccumulator {
        private int total;
        private int encoded;
        private int existing;
        private int failed;

        void recordTotal() {
            total++;
        }

        void recordEncoded() {
            encoded++;
        }

        void recordExisting() {
            existing++;
        }

        void recordFailed() {
            failed++;
        }

        SpeechFeatureEncodingCoverage toCoverage() {
            return new SpeechFeatureEncodingCoverage(total, encoded, existing, failed);
        }
    }

    private static final class FailureAccumulator {
        private int count;
        private final List<String> sampleIdExamples = new ArrayList<>();

        void record(String sampleId) {
            count++;
            if (sampleIdExamples.size() < SpeechFeatureEncodingFailureGroup.MAX_EXAMPLES) {
                sampleIdExamples.add(sampleId);
            }
        }

        SpeechFeatureEncodingFailureGroup toGroup(String reason) {
            return new SpeechFeatureEncodingFailureGroup(reason, count, sampleIdExamples);
        }
    }
}
