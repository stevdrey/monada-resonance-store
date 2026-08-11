package com.monada.evaluation;

import com.monada.api.ExactQueryKeyStrategy;
import com.monada.api.LexicallyEnrichedQueryKeyStrategy;
import com.monada.api.NormalizedQueryKeyStrategy;
import com.monada.encoder.LexicalEnrichmentPipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Replays persisted feedback through exact, normalized, and lexical query keys. */
public final class FeedbackQueryKeyComparisonRunner {

    private final FeedbackQueryKeyExperimentRunner experimentRunner;

    public FeedbackQueryKeyComparisonRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public FeedbackQueryKeyComparisonRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.experimentRunner = new FeedbackQueryKeyExperimentRunner(
                Objects.requireNonNull(evaluationRunner, "evaluationRunner"),
                Objects.requireNonNull(comparator, "comparator"));
    }

    public FeedbackQueryKeyComparisonReport run(
            EvaluationDataset dataset,
            List<FeedbackQueryKeyComparisonCase> comparisonCases,
            Path basePath) {
        var normalizer = new LexicalEnrichmentPipeline();
        var reports = experimentRunner.run(
                dataset,
                comparisonCases,
                basePath,
                normalizer,
                List.of(
                        new FeedbackQueryKeyExperimentRunner.StrategyDefinition(
                                FeedbackQueryKeyComparisonStrategy.EXACT,
                                new ExactQueryKeyStrategy()),
                        new FeedbackQueryKeyExperimentRunner.StrategyDefinition(
                                FeedbackQueryKeyComparisonStrategy.NORMALIZED,
                                new NormalizedQueryKeyStrategy(normalizer)),
                        new FeedbackQueryKeyExperimentRunner.StrategyDefinition(
                                FeedbackQueryKeyComparisonStrategy.LEXICALLY_ENRICHED,
                                new LexicallyEnrichedQueryKeyStrategy(normalizer))));
        return new FeedbackQueryKeyComparisonReport(reports, recommendationFor(reports));
    }

    FeedbackQueryKeyCaseClassification classify(
            FeedbackQueryKeyComparisonCase comparisonCase,
            boolean keysMatched,
            FeedbackQueryKeyTargetRank before,
            FeedbackQueryKeyTargetRank after) {
        return experimentRunner.classify(comparisonCase, keysMatched, before, after);
    }

    private FeedbackQueryKeyRecommendation recommendationFor(
            List<FeedbackQueryKeyStrategyReport> reports) {
        FeedbackQueryKeyStrategyReport normalized = requireStrategy(
                reports, FeedbackQueryKeyComparisonStrategy.NORMALIZED);
        FeedbackQueryKeyStrategyReport lexical = requireStrategy(
                reports, FeedbackQueryKeyComparisonStrategy.LEXICALLY_ENRICHED);
        if (normalized.meritsFurtherInvestigation()) {
            return FeedbackQueryKeyRecommendation.INVESTIGATE_NORMALIZED;
        }
        if (lexical.meritsFurtherInvestigation()) {
            return FeedbackQueryKeyRecommendation.INVESTIGATE_LEXICALLY_ENRICHED;
        }
        boolean generalizedFalseSharing = normalized.summary().falseSharingCount() > 0
                || lexical.summary().falseSharingCount() > 0;
        return generalizedFalseSharing
                ? FeedbackQueryKeyRecommendation.KEEP_EXACT_DEFAULT
                : FeedbackQueryKeyRecommendation.INCONCLUSIVE_KEEP_EXACT_DEFAULT;
    }

    private FeedbackQueryKeyStrategyReport requireStrategy(
            List<FeedbackQueryKeyStrategyReport> reports,
            FeedbackQueryKeyComparisonStrategy strategy) {
        return reports.stream()
                .filter(report -> report.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing strategy report: " + strategy));
    }
}
