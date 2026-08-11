package com.monada.evaluation;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/** Exact-vs-normalized persisted-feedback evidence over the project-memory corpus. */
public record ProjectMemoryFeedbackKeyValidationReport(
        List<FeedbackQueryKeyStrategyReport> strategyReports,
        ProjectMemoryFeedbackKeyConclusion conclusion
) {
    private static final Set<FeedbackQueryKeyComparisonStrategy> REQUIRED_STRATEGIES =
            Set.of(
                    FeedbackQueryKeyComparisonStrategy.EXACT,
                    FeedbackQueryKeyComparisonStrategy.NORMALIZED);

    public ProjectMemoryFeedbackKeyValidationReport {
        strategyReports = List.copyOf(Objects.requireNonNull(strategyReports, "strategyReports"));
        if (strategyReports.size() != REQUIRED_STRATEGIES.size()) {
            throw new IllegalArgumentException("report must contain exact and normalized strategies");
        }
        var seen = EnumSet.noneOf(FeedbackQueryKeyComparisonStrategy.class);
        for (FeedbackQueryKeyStrategyReport strategyReport : strategyReports) {
            Objects.requireNonNull(strategyReport, "strategyReport");
            if (!REQUIRED_STRATEGIES.contains(strategyReport.strategy())) {
                throw new IllegalArgumentException("unsupported strategy report: " + strategyReport.strategy());
            }
            if (!seen.add(strategyReport.strategy())) {
                throw new IllegalArgumentException("duplicate strategy report: " + strategyReport.strategy());
            }
        }
        if (!seen.equals(REQUIRED_STRATEGIES)) {
            throw new IllegalArgumentException("report must contain exact and normalized strategies");
        }
        if (!sameRetrievalEvidence(
                strategyReports.getFirst().baseline(),
                strategyReports.getLast().baseline())) {
            throw new IllegalArgumentException("exact and normalized arms must have identical baselines");
        }
        Objects.requireNonNull(conclusion, "conclusion");
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
                "Project-Memory Feedback Query-Key Validation",
                "EXACT, NORMALIZED",
                strategyReports,
                conclusion);
    }

    private boolean sameRetrievalEvidence(EvaluationReport first, EvaluationReport second) {
        return first.averagePrecisionByK().equals(second.averagePrecisionByK())
                && first.averageRecallByK().equals(second.averageRecallByK())
                && first.averageHitByK().equals(second.averageHitByK())
                && Double.compare(first.meanReciprocalRank(), second.meanReciprocalRank()) == 0
                && first.queryResults().size() == second.queryResults().size()
                && IntStream.range(0, first.queryResults().size())
                .allMatch(index -> sameRetrievalEvidence(
                        first.queryResults().get(index), second.queryResults().get(index)));
    }

    private boolean sameRetrievalEvidence(QueryEvaluation first, QueryEvaluation second) {
        return first.queryText().equals(second.queryText())
                && first.expectedLabels().equals(second.expectedLabels())
                && first.returnedLabels().equals(second.returnedLabels())
                && first.precisionByK().equals(second.precisionByK())
                && first.recallByK().equals(second.recallByK())
                && first.hitByK().equals(second.hitByK())
                && Double.compare(first.reciprocalRank(), second.reciprocalRank()) == 0
                && Objects.equals(first.textEncodingDiagnostic(), second.textEncodingDiagnostic());
    }
}
