package com.monada.speech.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Result of comparing an actual {@link SpeechEvaluationReport} against a protected
 * {@link SpeechBenchmarkBaseline}.
 *
 * @param passed     true if every checked field matched the baseline within tolerance
 * @param mismatches human-readable descriptions of each field that did not match
 *                   (empty when {@code passed} is true)
 */
public record SpeechBenchmarkComparison(
        boolean passed,
        List<String> mismatches
) {
    public SpeechBenchmarkComparison {
        Objects.requireNonNull(mismatches, "mismatches");
        mismatches = List.copyOf(mismatches);
        if (passed && !mismatches.isEmpty()) {
            throw new IllegalArgumentException("passed comparison must have no mismatches");
        }
        if (!passed && mismatches.isEmpty()) {
            throw new IllegalArgumentException("failed comparison must list at least one mismatch");
        }
    }
}
