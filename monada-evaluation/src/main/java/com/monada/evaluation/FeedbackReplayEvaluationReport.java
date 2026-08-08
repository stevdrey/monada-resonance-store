package com.monada.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Deterministic comparison report for baseline, synthetic, and persisted replay feedback. */
public record FeedbackReplayEvaluationReport(
        EvaluationReport baseline,
        EvaluationReport seededSynthetic,
        EvaluationReport replayedPersisted,
        EvaluationComparison seededVsBaseline,
        EvaluationComparison replayedVsBaseline,
        List<FeedbackReplayEventDiagnostic> replayDiagnostics
) {
    public FeedbackReplayEvaluationReport {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(seededSynthetic, "seededSynthetic");
        Objects.requireNonNull(replayedPersisted, "replayedPersisted");
        Objects.requireNonNull(seededVsBaseline, "seededVsBaseline");
        Objects.requireNonNull(replayedVsBaseline, "replayedVsBaseline");
        replayDiagnostics = List.copyOf(Objects.requireNonNull(replayDiagnostics, "replayDiagnostics"));
        if (!seededVsBaseline.before().equals(baseline)
                || !seededVsBaseline.after().equals(seededSynthetic)) {
            throw new IllegalArgumentException("seeded comparison does not reference the supplied reports");
        }
        if (!replayedVsBaseline.before().equals(baseline)
                || !replayedVsBaseline.after().equals(replayedPersisted)) {
            throw new IllegalArgumentException("replayed comparison does not reference the supplied reports");
        }
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Feedback Replay Evaluation\n");
        sb.append("==========================\n\n");
        sb.append("Modes\n");
        sb.append("-----\n");
        sb.append("BASELINE: feedback-aware ranking with an empty feedback log\n");
        sb.append("SEEDED_SYNTHETIC: one deterministic positive event per query\n");
        sb.append("REPLAYED_PERSISTED: explicit fixture events appended to the persisted JSONL log\n\n");

        sb.append("Aggregate metrics\n");
        sb.append("-----------------\n");
        appendAggregate(sb, "BASELINE", baseline, null);
        appendAggregate(sb, "SEEDED_SYNTHETIC", seededSynthetic, baseline);
        sb.append("  Classification vs BASELINE: ")
                .append(seededVsBaseline.aggregate()).append("\n\n");
        appendAggregate(sb, "REPLAYED_PERSISTED", replayedPersisted, baseline);
        sb.append("  Classification vs BASELINE: ")
                .append(replayedVsBaseline.aggregate()).append("\n\n");

        int maxK = baseline.averagePrecisionByK().keySet().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(5);
        sb.append("Per-query comparison\n");
        sb.append("--------------------\n");
        for (int index = 0; index < baseline.queryResults().size(); index++) {
            QueryEvaluation baselineQuery = baseline.queryResults().get(index);
            QueryEvaluation seededQuery = seededSynthetic.queryResults().get(index);
            QueryEvaluation replayedQuery = replayedPersisted.queryResults().get(index);
            sb.append("Query: ").append(baselineQuery.queryText()).append('\n');
            sb.append("Expected: ")
                    .append(String.join(", ", new TreeSet<>(baselineQuery.expectedLabels())))
                    .append('\n');
            appendTopK(sb, "BASELINE", baselineQuery, maxK);
            appendTopK(sb, "SEEDED_SYNTHETIC", seededQuery, maxK);
            sb.append("  [SEEDED_SYNTHETIC vs BASELINE] Change: ")
                    .append(seededVsBaseline.perQuery().get(index).change()).append('\n');
            appendQueryMetricDeltas(sb, "SEEDED_SYNTHETIC", baselineQuery, seededQuery);
            appendTopK(sb, "REPLAYED_PERSISTED", replayedQuery, maxK);
            sb.append("  [REPLAYED_PERSISTED vs BASELINE] Change: ")
                    .append(replayedVsBaseline.perQuery().get(index).change()).append('\n');
            appendQueryMetricDeltas(sb, "REPLAYED_PERSISTED", baselineQuery, replayedQuery);
            sb.append("  Evaluation Query Key: ")
                    .append(replayedQuery.queryKeyDiagnostic().queryKey()).append('\n');
            List<String> matchingOrdinals = replayDiagnostics.stream()
                    .filter(diagnostic -> diagnostic.matchedEvaluationQueries()
                            .contains(baselineQuery.queryText()))
                    .map(diagnostic -> Integer.toString(diagnostic.ordinal()))
                    .toList();
            sb.append("  Matching replay events: ")
                    .append(matchingOrdinals.isEmpty() ? "(none)" : String.join(", ", matchingOrdinals))
                    .append("\n\n");
        }

        sb.append("Replay event diagnostics\n");
        sb.append("------------------------\n");
        for (FeedbackReplayEventDiagnostic diagnostic : replayDiagnostics) {
            FeedbackReplayEvent event = diagnostic.event();
            sb.append("Event ").append(diagnostic.ordinal()).append('\n');
            sb.append("  Query: ").append(event.queryText()).append('\n');
            sb.append("  Query key: ").append(event.queryKey()).append('\n');
            sb.append("  Target label: ").append(event.targetLabel()).append('\n');
            sb.append("  Signal: ").append(event.signal()).append('\n');
            sb.append("  Delta: ").append(Double.toString(event.delta())).append('\n');
            sb.append("  Created at: ").append(event.createdAt()).append('\n');
            sb.append("  Expected scope: ").append(event.expectedScope()).append('\n');
            sb.append("  Matched evaluation queries: ")
                    .append(diagnostic.matchesEvaluationQuery()
                            ? diagnostic.matchedEvaluationQueries()
                            : "(none)")
                    .append("\n\n");
        }
        return sb.toString();
    }

    private void appendAggregate(
            StringBuilder sb,
            String mode,
            EvaluationReport report,
            EvaluationReport baselineReport) {
        sb.append('[').append(mode).append("]\n");
        appendMetricMap(sb, "Precision", report.averagePrecisionByK(),
                baselineReport == null ? null : baselineReport.averagePrecisionByK());
        appendMetricMap(sb, "Recall", report.averageRecallByK(),
                baselineReport == null ? null : baselineReport.averageRecallByK());
        appendMetricMap(sb, "Hit", report.averageHitByK(),
                baselineReport == null ? null : baselineReport.averageHitByK());
        if (baselineReport == null) {
            sb.append(String.format(Locale.ROOT, "  MRR: %.4f%n", report.meanReciprocalRank()));
        } else {
            sb.append(String.format(Locale.ROOT, "  MRR: %.4f (delta %+.4f)%n",
                    report.meanReciprocalRank(),
                    report.meanReciprocalRank() - baselineReport.meanReciprocalRank()));
        }
        if (baselineReport == null) {
            sb.append('\n');
        }
    }

    private void appendMetricMap(
            StringBuilder sb,
            String metric,
            Map<Integer, Double> values,
            Map<Integer, Double> baselineValues) {
        for (Map.Entry<Integer, Double> entry : values.entrySet()) {
            if (baselineValues == null) {
                sb.append(String.format(Locale.ROOT, "  %s@%d: %.4f%n",
                        metric, entry.getKey(), entry.getValue()));
            } else {
                double baselineValue = Objects.requireNonNull(
                        baselineValues.get(entry.getKey()),
                        "baseline " + metric + "@" + entry.getKey());
                sb.append(String.format(Locale.ROOT, "  %s@%d: %.4f (delta %+.4f)%n",
                        metric, entry.getKey(), entry.getValue(), entry.getValue() - baselineValue));
            }
        }
    }

    private void appendTopK(
            StringBuilder sb,
            String mode,
            QueryEvaluation query,
            int maxK) {
        int displayK = Math.min(maxK, query.returnedLabels().size());
        sb.append("  [").append(mode).append("] Top ").append(displayK).append(": ")
                .append(displayK == 0
                        ? "(none)"
                        : String.join(", ", query.returnedLabels().subList(0, displayK)))
                .append('\n');
    }

    private void appendQueryMetricDeltas(
            StringBuilder sb,
            String mode,
            QueryEvaluation baselineQuery,
            QueryEvaluation candidateQuery) {
        var deltas = new ArrayList<String>();
        collectMetricDeltas(deltas, "Precision", baselineQuery.precisionByK(), candidateQuery.precisionByK());
        collectMetricDeltas(deltas, "Recall", baselineQuery.recallByK(), candidateQuery.recallByK());
        collectMetricDeltas(deltas, "Hit", baselineQuery.hitByK(), candidateQuery.hitByK());
        double reciprocalRankDelta = candidateQuery.reciprocalRank() - baselineQuery.reciprocalRank();
        if (Math.abs(reciprocalRankDelta) > EvaluationComparator.DEFAULT_EPSILON) {
            deltas.add(String.format(Locale.ROOT, "MRR %+.4f", reciprocalRankDelta));
        }
        sb.append("  [").append(mode).append("] Metric deltas vs BASELINE: ")
                .append(deltas.isEmpty() ? "(none)" : String.join(", ", deltas))
                .append('\n');
    }

    private void collectMetricDeltas(
            List<String> deltas,
            String metric,
            Map<Integer, Double> baselineValues,
            Map<Integer, Double> candidateValues) {
        for (Map.Entry<Integer, Double> entry : candidateValues.entrySet()) {
            double baselineValue = Objects.requireNonNull(
                    baselineValues.get(entry.getKey()),
                    "baseline " + metric + "@" + entry.getKey());
            double delta = entry.getValue() - baselineValue;
            if (Math.abs(delta) > EvaluationComparator.DEFAULT_EPSILON) {
                deltas.add(String.format(Locale.ROOT, "%s@%d %+.4f",
                        metric, entry.getKey(), delta));
            }
        }
    }
}
