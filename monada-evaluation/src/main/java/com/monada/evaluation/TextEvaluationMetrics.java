package com.monada.evaluation;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/** Resolves stable snapshot metric keys against an {@link EvaluationReport}. */
final class TextEvaluationMetrics {

    static final String MEAN_RECIPROCAL_RANK = "meanReciprocalRank";
    private static final Pattern INDEXED_METRIC = Pattern.compile(
            "average(Precision|Recall|Hit)@([1-9][0-9]*)");

    private TextEvaluationMetrics() {
    }

    static boolean supports(String metricKey) {
        Objects.requireNonNull(metricKey, "metricKey");
        if (MEAN_RECIPROCAL_RANK.equals(metricKey)) {
            return true;
        }
        var matcher = INDEXED_METRIC.matcher(metricKey);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return Integer.parseInt(matcher.group(2)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static OptionalDouble resolve(EvaluationReport report, String metricKey) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(metricKey, "metricKey");
        if (MEAN_RECIPROCAL_RANK.equals(metricKey)) {
            return OptionalDouble.of(report.meanReciprocalRank());
        }

        var matcher = INDEXED_METRIC.matcher(metricKey);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported metric key: " + metricKey);
        }
        int k = Integer.parseInt(matcher.group(2));
        Map<Integer, Double> values = switch (matcher.group(1)) {
            case "Precision" -> report.averagePrecisionByK();
            case "Recall" -> report.averageRecallByK();
            case "Hit" -> report.averageHitByK();
            default -> throw new IllegalStateException("unsupported metric family: " + matcher.group(1));
        };
        var value = values.get(k);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }
}
