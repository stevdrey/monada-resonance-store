package com.monada.evaluation.speech;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic min-max normalization for a single query and retrieval modality. */
final class SpeechScoreNormalization {

    SpeechNormalizedScores normalize(Map<String, Double> rawScoresBySampleId) {
        Objects.requireNonNull(rawScoresBySampleId, "rawScoresBySampleId");
        if (rawScoresBySampleId.isEmpty()) {
            return new SpeechNormalizedScores(
                    SpeechScoreNormalizationStatus.NO_AVAILABLE_SCORES, 0.0, 0.0, Map.of());
        }

        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : rawScoresBySampleId.entrySet()) {
            validateEntry(entry);
            double score = entry.getValue();
            minimum = Math.min(minimum, score);
            maximum = Math.max(maximum, score);
        }

        Map<String, Double> normalized = new LinkedHashMap<>();
        if (Double.compare(minimum, maximum) == 0) {
            rawScoresBySampleId.keySet().stream().sorted()
                    .forEach(sampleId -> normalized.put(sampleId, 0.0));
            return new SpeechNormalizedScores(
                    SpeechScoreNormalizationStatus.CONSTANT_NO_DISCRIMINATION,
                    minimum,
                    maximum,
                    normalized);
        }

        double minimumRawScore = minimum;
        double range = maximum - minimumRawScore;
        rawScoresBySampleId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> normalized.put(entry.getKey(), (entry.getValue() - minimumRawScore) / range));
        return new SpeechNormalizedScores(SpeechScoreNormalizationStatus.MIN_MAX, minimum, maximum, normalized);
    }

    private void validateEntry(Map.Entry<String, Double> entry) {
        String sampleId = Objects.requireNonNull(entry.getKey(), "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId must not be blank");
        }
        Double score = Objects.requireNonNull(entry.getValue(), "raw score");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("raw score must be finite for sampleId=" + sampleId + ": " + score);
        }
    }
}

enum SpeechScoreNormalizationStatus {
    MIN_MAX,
    CONSTANT_NO_DISCRIMINATION,
    NO_AVAILABLE_SCORES
}

record SpeechNormalizedScores(
        SpeechScoreNormalizationStatus status,
        double minimumRawScore,
        double maximumRawScore,
        Map<String, Double> normalizedScoresBySampleId
) {
    SpeechNormalizedScores {
        Objects.requireNonNull(status, "status");
        if (!Double.isFinite(minimumRawScore) || !Double.isFinite(maximumRawScore)) {
            throw new IllegalArgumentException("normalization range must be finite");
        }
        if (minimumRawScore > maximumRawScore) {
            throw new IllegalArgumentException("minimumRawScore must not exceed maximumRawScore");
        }
        Objects.requireNonNull(normalizedScoresBySampleId, "normalizedScoresBySampleId");
        Map<String, Double> copy = new LinkedHashMap<>();
        normalizedScoresBySampleId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String sampleId = Objects.requireNonNull(entry.getKey(), "sampleId");
                    Double score = Objects.requireNonNull(entry.getValue(), "normalized score");
                    if (sampleId.isBlank() || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                        throw new IllegalArgumentException("invalid normalized score entry for sampleId=" + sampleId);
                    }
                    copy.put(sampleId, score);
                });
        normalizedScoresBySampleId = Map.copyOf(copy);
        if (status == SpeechScoreNormalizationStatus.NO_AVAILABLE_SCORES
                && !normalizedScoresBySampleId.isEmpty()) {
            throw new IllegalArgumentException("NO_AVAILABLE_SCORES must not contain normalized scores");
        }
        if (status != SpeechScoreNormalizationStatus.NO_AVAILABLE_SCORES
                && normalizedScoresBySampleId.isEmpty()) {
            throw new IllegalArgumentException("normalization status requires at least one score");
        }
    }
}
