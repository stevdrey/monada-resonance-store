package com.monada.speech.encoder;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic aggregation of samples skipped for one encoder failure reason.
 */
public record SpeechFeatureEncodingFailureGroup(
        String reason,
        int count,
        List<String> sampleIdExamples
) {

    /** Maximum number of sample IDs retained for a compact report. */
    public static final int MAX_EXAMPLES = 5;

    public SpeechFeatureEncodingFailureGroup {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Objects.requireNonNull(sampleIdExamples, "sampleIdExamples");
        if (sampleIdExamples.isEmpty()) {
            throw new IllegalArgumentException("sampleIdExamples must not be empty");
        }
        if (sampleIdExamples.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException(
                    "sampleIdExamples must not exceed " + MAX_EXAMPLES + " entries");
        }
        if (sampleIdExamples.size() > count) {
            throw new IllegalArgumentException("sampleIdExamples must not exceed count");
        }
        sampleIdExamples = List.copyOf(sampleIdExamples);
    }
}
