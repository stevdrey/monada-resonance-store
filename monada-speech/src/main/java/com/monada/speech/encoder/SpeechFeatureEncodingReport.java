package com.monada.speech.encoder;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, deterministic outcome of a {@link SpeechFeatureEncodingJob} run.
 */
public record SpeechFeatureEncodingReport(
        int totalSamples,
        int encodedSamples,
        int skippedSamples,
        int existingFeatureSamples,
        Map<String, SpeechFeatureEncodingFailureGroup> failuresByReason,
        Map<Integer, Integer> vectorsByDimension,
        Map<String, SpeechFeatureEncodingCoverage> coverageBySpeaker,
        Map<SpeechCondition, SpeechFeatureEncodingCoverage> coverageByCondition,
        Map<SpeechTaskType, SpeechFeatureEncodingCoverage> coverageByTaskType
) {
    public SpeechFeatureEncodingReport {
        if (totalSamples < 0 || encodedSamples < 0 || skippedSamples < 0 || existingFeatureSamples < 0) {
            throw new IllegalArgumentException("sample counts must be non-negative");
        }
        if (encodedSamples + skippedSamples != totalSamples) {
            throw new IllegalArgumentException("encoded and skipped samples must add up to total samples");
        }
        if (existingFeatureSamples > skippedSamples) {
            throw new IllegalArgumentException("existingFeatureSamples must not exceed skippedSamples");
        }
        Objects.requireNonNull(failuresByReason, "failuresByReason");
        Objects.requireNonNull(vectorsByDimension, "vectorsByDimension");
        Objects.requireNonNull(coverageBySpeaker, "coverageBySpeaker");
        Objects.requireNonNull(coverageByCondition, "coverageByCondition");
        Objects.requireNonNull(coverageByTaskType, "coverageByTaskType");

        failuresByReason = immutableStringMap(failuresByReason);
        vectorsByDimension = immutableIntegerMap(vectorsByDimension);
        coverageBySpeaker = immutableStringMap(coverageBySpeaker);
        coverageByCondition = immutableEnumMap(coverageByCondition, SpeechCondition.class);
        coverageByTaskType = immutableEnumMap(coverageByTaskType, SpeechTaskType.class);

        for (Map.Entry<String, SpeechFeatureEncodingFailureGroup> entry : failuresByReason.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().reason())) {
                throw new IllegalArgumentException(
                        "failure map key must match group reason: " + entry.getKey());
            }
        }
        int failedSamples = skippedSamples - existingFeatureSamples;
        int groupedFailures = failuresByReason.values().stream()
                .mapToInt(SpeechFeatureEncodingFailureGroup::count)
                .sum();
        if (groupedFailures != failedSamples) {
            throw new IllegalArgumentException(
                    "failure groups (" + groupedFailures + ") must equal failed samples (" + failedSamples + ")");
        }
        int coveredSamples = encodedSamples + existingFeatureSamples;
        int dimensionCount = vectorsByDimension.values().stream().mapToInt(Integer::intValue).sum();
        if (dimensionCount != coveredSamples) {
            throw new IllegalArgumentException(
                    "vector dimension counts (" + dimensionCount + ") must equal covered samples (" + coveredSamples + ")");
        }
    }

    /** Number of samples with a persisted feature vector after this run. */
    public int coveredSamples() {
        return encodedSamples + existingFeatureSamples;
    }

    /** Number of samples whose audio could not be encoded. */
    public int failedSamples() {
        return skippedSamples - existingFeatureSamples;
    }

    /**
     * Renders a deterministic summary suitable for exploratory local-corpus output.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Monada Speech Feature Encoding Report\n");
        sb.append("====================================\n");
        sb.append("Total samples: ").append(totalSamples).append('\n');
        sb.append("Newly encoded: ").append(encodedSamples).append('\n');
        sb.append("Existing features: ").append(existingFeatureSamples).append('\n');
        sb.append("Skipped samples: ").append(skippedSamples).append('\n');
        sb.append("Failed samples: ").append(failedSamples()).append('\n');
        sb.append("Covered samples: ").append(coveredSamples()).append("\n\n");

        appendDimensions(sb);
        appendFailures(sb);
        appendCoverage(sb, "Coverage by speaker", coverageBySpeaker);
        appendCoverage(sb, "Coverage by condition", coverageByCondition);
        appendCoverage(sb, "Coverage by task type", coverageByTaskType);
        return sb.toString();
    }

    private void appendDimensions(StringBuilder sb) {
        if (vectorsByDimension.isEmpty()) {
            return;
        }
        sb.append("Vector dimensions\n");
        sb.append("-----------------\n");
        for (Map.Entry<Integer, Integer> entry : vectorsByDimension.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        sb.append('\n');
    }

    private void appendFailures(StringBuilder sb) {
        if (failuresByReason.isEmpty()) {
            return;
        }
        sb.append("Encoding failures\n");
        sb.append("-----------------\n");
        for (Map.Entry<String, SpeechFeatureEncodingFailureGroup> entry : failuresByReason.entrySet()) {
            var group = entry.getValue();
            sb.append(entry.getKey())
                    .append(" (n=").append(group.count()).append("): ")
                    .append(group.sampleIdExamples()).append('\n');
        }
        sb.append('\n');
    }

    private static <K> void appendCoverage(
            StringBuilder sb,
            String heading,
            Map<K, SpeechFeatureEncodingCoverage> coverage
    ) {
        if (coverage.isEmpty()) {
            return;
        }
        sb.append(heading).append('\n');
        sb.append("-------------------\n");
        for (Map.Entry<K, SpeechFeatureEncodingCoverage> entry : coverage.entrySet()) {
            var value = entry.getValue();
            sb.append(entry.getKey())
                    .append(": total=").append(value.totalSamples())
                    .append(" encoded=").append(value.encodedSamples())
                    .append(" existing=").append(value.existingFeatureSamples())
                    .append(" failed=").append(value.failedSamples())
                    .append(" covered=").append(value.coveredSamples())
                    .append('\n');
        }
        sb.append('\n');
    }

    private static <V> Map<String, V> immutableStringMap(Map<String, V> source) {
        var sorted = new TreeMap<String, V>();
        for (Map.Entry<String, V> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "map key");
            V value = Objects.requireNonNull(entry.getValue(), "map value");
            sorted.put(key, value);
        }
        return sorted.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<Integer, Integer> immutableIntegerMap(Map<Integer, Integer> source) {
        var sorted = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> entry : source.entrySet()) {
            Integer dimensions = entry.getKey();
            Integer count = entry.getValue();
            if (dimensions == null || dimensions <= 0) {
                throw new IllegalArgumentException("vector dimensions must be positive");
            }
            if (count == null || count <= 0) {
                throw new IllegalArgumentException("vector dimension count must be positive");
            }
            sorted.put(dimensions, count);
        }
        return sorted.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static <E extends Enum<E>> Map<E, SpeechFeatureEncodingCoverage> immutableEnumMap(
            Map<E, SpeechFeatureEncodingCoverage> source,
            Class<E> enumType
    ) {
        var values = new EnumMap<E, SpeechFeatureEncodingCoverage>(enumType);
        for (Map.Entry<E, SpeechFeatureEncodingCoverage> entry : source.entrySet()) {
            values.put(
                    Objects.requireNonNull(entry.getKey(), "map key"),
                    Objects.requireNonNull(entry.getValue(), "map value"));
        }
        return values.isEmpty() ? Map.of() : Collections.unmodifiableMap(values);
    }
}
