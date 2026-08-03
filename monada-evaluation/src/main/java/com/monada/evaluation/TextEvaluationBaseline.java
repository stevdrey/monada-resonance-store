package com.monada.evaluation;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Versioned aggregate-metric snapshot for one protected dataset/profile pair. */
record TextEvaluationBaseline(
        String datasetName,
        String datasetVersion,
        String profileName,
        double tolerance,
        Map<String, TextBaselineExpectation> metrics
) {
    TextEvaluationBaseline {
        datasetName = requireNonBlank(datasetName, "datasetName");
        datasetVersion = requireNonBlank(datasetVersion, "datasetVersion");
        profileName = requireNonBlank(profileName, "profileName");
        if (!Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative: " + tolerance);
        }
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("metrics must not be empty");
        }
        var sortedMetrics = new TreeMap<String, TextBaselineExpectation>();
        for (var entry : metrics.entrySet()) {
            var metricKey = requireNonBlank(entry.getKey(), "metric key");
            if (!TextEvaluationMetrics.supports(metricKey)) {
                throw new IllegalArgumentException("unsupported metric key: " + metricKey);
            }
            sortedMetrics.put(metricKey, Objects.requireNonNull(entry.getValue(), metricKey));
        }
        metrics = Map.copyOf(sortedMetrics);
    }

    TextBaselineComparison compare(VersionedTextEvaluationReport actual) {
        Objects.requireNonNull(actual, "actual");
        var metadata = actual.metadata();
        if (metadata.mode() != TextEvaluationMode.PROTECTED) {
            throw new IllegalArgumentException(
                    "baseline comparison requires a PROTECTED report but was " + metadata.mode());
        }
        requireIdentity("datasetName", datasetName, metadata.datasetName());
        requireIdentity("datasetVersion", datasetVersion, metadata.datasetVersion());
        requireIdentity("profileName", profileName, metadata.profileName());

        var mismatches = new ArrayList<String>();
        for (var entry : new TreeMap<>(metrics).entrySet()) {
            var metricKey = entry.getKey();
            var expectation = entry.getValue();
            var actualValue = TextEvaluationMetrics.resolve(actual.evaluationReport(), metricKey);
            if (actualValue.isEmpty()) {
                mismatches.add("metric=" + metricKey + " is missing from the actual report");
                continue;
            }
            compareMetric(metricKey, expectation, actualValue.getAsDouble(), mismatches);
        }
        return new TextBaselineComparison(metadata, mismatches.isEmpty(), mismatches);
    }

    private void compareMetric(
            String metricKey,
            TextBaselineExpectation expectation,
            double actual,
            ArrayList<String> mismatches) {
        double expected = expectation.expectedValue();
        double delta = actual - expected;
        boolean matches = switch (expectation.policy()) {
            case EXACT -> Math.abs(delta) <= tolerance;
            case MINIMUM -> actual + tolerance >= expected;
        };
        if (!matches) {
            mismatches.add(String.format(
                    Locale.ROOT,
                    "metric=%s policy=%s expected=%.15f actual=%.15f delta=%+.3e tolerance=%.3e",
                    metricKey, expectation.policy(), expected, actual, delta, tolerance));
        }
    }

    private void requireIdentity(String field, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    field + " does not match baseline: expected '" + expected + "' but was '" + actual + "'");
        }
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
