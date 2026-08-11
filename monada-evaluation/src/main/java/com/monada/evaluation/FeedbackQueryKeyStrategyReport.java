package com.monada.evaluation;

import java.util.List;
import java.util.Objects;

/** Structured result for one isolated strategy arm. */
public record FeedbackQueryKeyStrategyReport(
        FeedbackQueryKeyComparisonStrategy strategy,
        String strategyClassName,
        EvaluationReport baseline,
        EvaluationReport replayedPersisted,
        EvaluationComparison replayedVsBaseline,
        List<FeedbackQueryKeyCaseObservation> observations,
        FeedbackQueryKeyStrategySummary summary
) {
    public FeedbackQueryKeyStrategyReport {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(strategyClassName, "strategyClassName");
        if (strategyClassName.isBlank()) {
            throw new IllegalArgumentException("strategyClassName must not be blank");
        }
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(replayedPersisted, "replayedPersisted");
        Objects.requireNonNull(replayedVsBaseline, "replayedVsBaseline");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        Objects.requireNonNull(summary, "summary");
        if (!replayedVsBaseline.before().equals(baseline)
                || !replayedVsBaseline.after().equals(replayedPersisted)) {
            throw new IllegalArgumentException("comparison must reference the supplied reports");
        }
    }

    public boolean meritsFurtherInvestigation() {
        return hasImprovedGeneralizationTransfer()
                && summary.falseSharingCount() == 0
                && replayedVsBaseline.aggregate() != RankingChange.DEGRADED;
    }

    private boolean hasImprovedGeneralizationTransfer() {
        return observations.stream().anyMatch(observation -> {
            FeedbackQueryKeyComparisonCaseCategory category = observation.comparisonCase().category();
            return category.intendedTransfer()
                    && category != FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL
                    && observation.classification() == FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER
                    && observation.targetRankChange() == RankingChange.IMPROVED;
        });
    }
}
