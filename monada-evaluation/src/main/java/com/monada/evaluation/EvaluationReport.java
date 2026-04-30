package com.monada.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public record EvaluationReport(
        List<QueryEvaluation> queryResults,
        Map<Integer, Double> averagePrecisionByK
) {
    public EvaluationReport {
        queryResults = List.copyOf(Objects.requireNonNull(queryResults, "queryResults"));
        var sortedAveragePrecisionByK =
                new TreeMap<>(Objects.requireNonNull(averagePrecisionByK, "averagePrecisionByK"));
        sortedAveragePrecisionByK.forEach((k, v) -> Objects.requireNonNull(v, "averagePrecisionByK value"));
        averagePrecisionByK = Collections.unmodifiableMap(sortedAveragePrecisionByK);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Monada Resonance Store Evaluation Report\n");
        sb.append("=========================================\n\n");

        for (QueryEvaluation qr : queryResults) {
            sb.append("Query: ").append(qr.queryText()).append('\n');
            sb.append("Expected: ")
                    .append(String.join(", ", new TreeSet<>(qr.expectedLabels())))
                    .append('\n');

            int displayK = Math.min(qr.returnedLabels().size(),
                    qr.precisionByK().keySet().stream().mapToInt(Integer::intValue).max().orElse(3));
            sb.append("Top ").append(displayK).append(": ")
                    .append(String.join(", ", qr.returnedLabels().subList(0, displayK)))
                    .append('\n');

            for (Map.Entry<Integer, Double> e : qr.precisionByK().entrySet()) {
                sb.append(String.format(Locale.ROOT, "Precision@%d: %.2f%n", e.getKey(), e.getValue()));
            }
            sb.append('\n');
        }

        sb.append("Aggregate\n");
        sb.append("---------\n");
        for (Map.Entry<Integer, Double> e : averagePrecisionByK.entrySet()) {
            sb.append(String.format(Locale.ROOT, "Average Precision@%d: %.2f%n", e.getKey(), e.getValue()));
        }
        return sb.toString();
    }
}
