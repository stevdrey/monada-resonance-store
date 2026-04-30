package com.monada.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public record EvaluationReport(
        List<QueryEvaluation> queryResults,
        Map<Integer, Double> averagePrecisionByK,
        Map<Integer, Double> averageRecallByK,
        Map<Integer, Double> averageHitByK,
        double meanReciprocalRank
) {
    public EvaluationReport {
        queryResults = List.copyOf(Objects.requireNonNull(queryResults, "queryResults"));
        averagePrecisionByK = sortedCopy(averagePrecisionByK, "averagePrecisionByK");
        averageRecallByK = sortedCopy(averageRecallByK, "averageRecallByK");
        averageHitByK = sortedCopy(averageHitByK, "averageHitByK");
    }

    private static Map<Integer, Double> sortedCopy(Map<Integer, Double> source, String name) {
        var sorted = new TreeMap<>(Objects.requireNonNull(source, name));
        sorted.forEach((k, v) -> Objects.requireNonNull(v, name + " value"));
        return Collections.unmodifiableMap(sorted);
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

            int maxK = Stream.of(qr.precisionByK(), qr.recallByK(), qr.hitByK())
                    .flatMap(m -> m.keySet().stream())
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(3);
            int displayK = Math.min(qr.returnedLabels().size(), maxK);
            sb.append("Top ").append(displayK).append(": ")
                    .append(String.join(", ", qr.returnedLabels().subList(0, displayK)))
                    .append("\n\n");

            for (Map.Entry<Integer, Double> e : qr.precisionByK().entrySet()) {
                sb.append(String.format(Locale.ROOT, "Precision@%d: %.2f%n", e.getKey(), e.getValue()));
            }
            sb.append('\n');
            for (Map.Entry<Integer, Double> e : qr.recallByK().entrySet()) {
                sb.append(String.format(Locale.ROOT, "Recall@%d: %.2f%n", e.getKey(), e.getValue()));
            }
            sb.append('\n');
            for (Map.Entry<Integer, Double> e : qr.hitByK().entrySet()) {
                sb.append(String.format(Locale.ROOT, "Hit@%d: %.2f%n", e.getKey(), e.getValue()));
            }
            sb.append('\n');
            sb.append(String.format(Locale.ROOT, "Reciprocal Rank: %.2f%n", qr.reciprocalRank()));
            sb.append('\n');
        }

        sb.append("Aggregate\n");
        sb.append("---------\n");
        for (Map.Entry<Integer, Double> e : averagePrecisionByK.entrySet()) {
            sb.append(String.format(Locale.ROOT, "Average Precision@%d: %.2f%n", e.getKey(), e.getValue()));
        }
        sb.append('\n');
        for (Map.Entry<Integer, Double> e : averageRecallByK.entrySet()) {
            sb.append(String.format(Locale.ROOT, "Average Recall@%d: %.2f%n", e.getKey(), e.getValue()));
        }
        sb.append('\n');
        for (Map.Entry<Integer, Double> e : averageHitByK.entrySet()) {
            sb.append(String.format(Locale.ROOT, "Average Hit@%d: %.2f%n", e.getKey(), e.getValue()));
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "Mean Reciprocal Rank: %.2f%n", meanReciprocalRank));
        return sb.toString();
    }
}
