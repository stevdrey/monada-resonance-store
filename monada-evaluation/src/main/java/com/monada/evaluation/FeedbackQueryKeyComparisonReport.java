package com.monada.evaluation;

import java.util.EnumSet;
import java.util.List;
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
        return new FeedbackQueryKeyReportRenderer().render(
                "Feedback Query-Key Strategy Comparison",
                "EXACT, NORMALIZED, LEXICALLY_ENRICHED",
                strategyReports,
                recommendation);
    }
}
