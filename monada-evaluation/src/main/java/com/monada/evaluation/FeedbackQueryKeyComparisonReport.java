package com.monada.evaluation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Deterministic rendered evidence report for feedback query-key strategy comparison. */
public record FeedbackQueryKeyComparisonReport(
        List<FeedbackQueryKeyStrategyReport> strategyReports,
        FeedbackQueryKeyRecommendation recommendation
) {
    public FeedbackQueryKeyComparisonReport {
        strategyReports = List.copyOf(Objects.requireNonNull(strategyReports, "strategyReports"));
        if (strategyReports.size() != FeedbackQueryKeyComparisonStrategy.values().length) {
            throw new IllegalArgumentException("report must contain every comparison strategy");
        }
        var seen = EnumSet.noneOf(FeedbackQueryKeyComparisonStrategy.class);
        for (FeedbackQueryKeyStrategyReport strategyReport : strategyReports) {
            Objects.requireNonNull(strategyReport, "strategyReport");
            if (!seen.add(strategyReport.strategy())) {
                throw new IllegalArgumentException("duplicate strategy report: " + strategyReport.strategy());
            }
        }
        Objects.requireNonNull(recommendation, "recommendation");
    }

    public FeedbackQueryKeyStrategyReport reportFor(FeedbackQueryKeyComparisonStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return strategyReports.stream()
                .filter(report -> report.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing strategy report: " + strategy));
    }

    public String render() {
        var sb = new StringBuilder();
        sb.append("Feedback Query-Key Strategy Comparison\n");
        sb.append("======================================\n\n");
        sb.append("Mode: EXPLORATORY (production defaults are unchanged)\n");
        sb.append("Arms: EXACT, NORMALIZED, LEXICALLY_ENRICHED\n");
        sb.append("Store: isolated real FileFeedbackStore JSONL per strategy\n\n");

        for (FeedbackQueryKeyStrategyReport strategyReport : strategyReports) {
            appendStrategy(sb, strategyReport);
        }
        sb.append("Evidence conclusion: ").append(recommendation).append('\n');
        sb.append("ExactQueryKeyStrategy remains the production/default behavior.\n");
        return sb.toString();
    }

    private void appendStrategy(StringBuilder sb, FeedbackQueryKeyStrategyReport strategyReport) {
        sb.append("Strategy: ").append(strategyReport.strategy()).append('\n');
        sb.append("  Query-key strategy: ").append(strategyReport.strategyClassName()).append('\n');
        sb.append("  Aggregate metrics vs baseline:\n");
        appendMetricDeltas(sb, "Precision", strategyReport.baseline().averagePrecisionByK(),
                strategyReport.replayedPersisted().averagePrecisionByK());
        appendMetricDeltas(sb, "Recall", strategyReport.baseline().averageRecallByK(),
                strategyReport.replayedPersisted().averageRecallByK());
        appendMetricDeltas(sb, "Hit", strategyReport.baseline().averageHitByK(),
                strategyReport.replayedPersisted().averageHitByK());
        sb.append(String.format(Locale.ROOT, "    MRR: %.4f (delta %+.4f)%n",
                strategyReport.replayedPersisted().meanReciprocalRank(),
                strategyReport.replayedPersisted().meanReciprocalRank()
                        - strategyReport.baseline().meanReciprocalRank()));
        sb.append("  Aggregate classification: ")
                .append(strategyReport.replayedVsBaseline().aggregate()).append('\n');

        FeedbackQueryKeyStrategySummary summary = strategyReport.summary();
        sb.append("  Intended transfer cases: improved=").append(summary.intendedTransferImproved())
                .append(", maintained=").append(summary.intendedTransferMaintained())
                .append(", degraded=").append(summary.intendedTransferDegraded())
                .append(", isolated=").append(summary.intendedTransferIsolated()).append('\n');
        sb.append("  Negative cases: isolated=").append(summary.negativeIsolated())
                .append(", shared-no-movement=").append(summary.sharedNegativeWithoutMovement())
                .append(", contaminated=").append(summary.contaminationCount()).append('\n');
        sb.append(String.format(Locale.ROOT, "  False sharing: %d/%d (%.4f)%n%n",
                summary.falseSharingCount(), summary.negativeCaseCount(), summary.falseSharingRate()));

        sb.append("  Per-case evidence\n");
        for (FeedbackQueryKeyCaseObservation observation : strategyReport.observations()) {
            appendObservation(sb, observation);
        }
        sb.append('\n');
    }

    private void appendObservation(StringBuilder sb, FeedbackQueryKeyCaseObservation observation) {
        FeedbackQueryKeyComparisonCase comparisonCase = observation.comparisonCase();
        sb.append("  Case: ").append(comparisonCase.id())
                .append(" [").append(comparisonCase.category()).append("]\n");
        sb.append("    Seed query: ").append(comparisonCase.seedQueryText()).append('\n');
        sb.append("    Evaluation query: ").append(comparisonCase.evaluationQueryText()).append('\n');
        sb.append("    Seed query key: ").append(observation.seedQueryKey()).append('\n');
        sb.append("    Evaluation query key: ").append(observation.evaluationQueryKey()).append('\n');
        sb.append("    Keys matched: ").append(observation.keysMatched()).append('\n');
        sb.append("    Target: ").append(comparisonCase.targetLabel())
                .append(" (relevant to evaluation query: ")
                .append(observation.targetRelevantToEvaluationQuery()).append(")\n");
        sb.append(String.format(Locale.ROOT,
                "    Target baseline: full-corpus-rank=%d, score=%.6f%n",
                observation.targetBeforeReplay().fullCorpusRank(), observation.targetBeforeReplay().score()));
        sb.append(String.format(Locale.ROOT,
                "    Target replayed: full-corpus-rank=%d, score=%.6f%n",
                observation.targetAfterReplay().fullCorpusRank(), observation.targetAfterReplay().score()));
        sb.append("    Target rank change: ").append(observation.targetRankChange()).append('\n');
        sb.append("    Baseline top-K: ").append(observation.baselineTopK()).append('\n');
        sb.append("    Replayed top-K: ").append(observation.replayedTopK()).append('\n');
        appendCaseMetricDeltas(sb, observation);
        sb.append("    Classification: ").append(observation.classification()).append('\n');
    }

    private void appendMetricDeltas(
            StringBuilder sb,
            String metric,
            Map<Integer, Double> baseline,
            Map<Integer, Double> replayed) {
        for (Map.Entry<Integer, Double> entry : replayed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            double before = Objects.requireNonNull(baseline.get(entry.getKey()),
                    "baseline " + metric + "@" + entry.getKey());
            sb.append(String.format(Locale.ROOT, "    %s@%d: %.4f (delta %+.4f)%n",
                    metric, entry.getKey(), entry.getValue(), entry.getValue() - before));
        }
    }

    private void appendCaseMetricDeltas(
            StringBuilder sb,
        FeedbackQueryKeyCaseObservation observation) {
        sb.append("    Metric deltas: ");
        var deltas = new ArrayList<String>();
        deltas.addAll(renderMetricDeltas("Precision", observation.precisionDeltaByK()));
        deltas.addAll(renderMetricDeltas("Recall", observation.recallDeltaByK()));
        deltas.addAll(renderMetricDeltas("Hit", observation.hitDeltaByK()));
        deltas.add(String.format(Locale.ROOT, "MRR %+.4f", observation.reciprocalRankDelta()));
        sb.append(String.join(", ", deltas)).append('\n');
    }

    private List<String> renderMetricDeltas(
            String metric,
            Map<Integer, Double> deltas) {
        return deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> String.format(Locale.ROOT, "%s@%d %+.4f",
                        metric, entry.getKey(), entry.getValue()))
                .toList();
    }
}
