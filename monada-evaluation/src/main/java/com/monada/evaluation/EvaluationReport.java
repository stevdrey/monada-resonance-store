package com.monada.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.monada.evaluation.EvaluationMaps.sortedCopy;
import static com.monada.evaluation.EvaluationMaps.validateUnitInterval;

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
        meanReciprocalRank = validateUnitInterval(meanReciprocalRank, "meanReciprocalRank");
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

            appendMetricMap(sb, "Precision", qr.precisionByK());
            appendMetricMap(sb, "Recall", qr.recallByK());
            appendMetricMap(sb, "Hit", qr.hitByK());
            appendScalar(sb, "Reciprocal Rank", qr.reciprocalRank());

            QueryRetrievalDiagnostic diagnostic = QueryRetrievalDiagnostic.compute(qr);
            sb.append("Present expected: ").append(diagnostic.presentExpectedLabels()).append('\n');
            sb.append("Missing expected: ").append(diagnostic.missingExpectedLabels()).append('\n');
            sb.append("First expected rank: ").append(diagnostic.firstExpectedRank()).append('\n');
            sb.append("Failure: ").append(diagnostic.failureType().map(Enum::name).orElse("None")).append('\n');
            sb.append("Note: ").append(diagnostic.note()).append('\n');

            // Query Key Diagnostic
            if (qr.queryKeyDiagnostic() != null) {
                var qkd = qr.queryKeyDiagnostic();
                sb.append("Query Key: ").append(qkd.queryKey()).append('\n');
                sb.append("Query Key Strategy: ").append(qkd.strategyName()).append('\n');
                sb.append("Feedback Aware: ").append(qkd.feedbackAware()).append('\n');
                if (qkd.hasSeedQueryKey()) {
                    sb.append("Feedback Seed Key: ").append(qkd.seedQueryKey()).append('\n');
                    sb.append("Feedback Key Match: ").append(qkd.feedbackKeyMatch()).append('\n');
                }
            }

            sb.append('\n');
        }

        sb.append("Aggregate\n");
        sb.append("---------\n");
        appendMetricMap(sb, "Average Precision", averagePrecisionByK);
        appendMetricMap(sb, "Average Recall", averageRecallByK);
        appendMetricMap(sb, "Average Hit", averageHitByK);
        appendScalar(sb, "Mean Reciprocal Rank", meanReciprocalRank);
        return sb.toString();
    }

    private static void appendMetricMap(StringBuilder sb, String label, Map<Integer, Double> map) {
        for (Map.Entry<Integer, Double> e : map.entrySet()) {
            sb.append(String.format(Locale.ROOT, "%s@%d: %.2f%n", label, e.getKey(), e.getValue()));
        }
        sb.append('\n');
    }

    private static void appendScalar(StringBuilder sb, String label, double value) {
        sb.append(String.format(Locale.ROOT, "%s: %.2f%n", label, value));
    }
}
