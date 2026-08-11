package com.monada.evaluation;

import com.monada.api.ExactQueryKeyStrategy;
import com.monada.api.NormalizedQueryKeyStrategy;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.storage.feedback.FeedbackSignal;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates normalized feedback sharing over realistic project-memory queries. */
public final class ProjectMemoryFeedbackKeyValidationRunner {

    private static final int EXACT_CONTROL_COUNT = 1;
    private static final int NORMALIZATION_TRANSFER_COUNT = 8;
    private static final int NEGATIVE_CASE_COUNT = 8;
    private static final double EXACT_DELTA = 0.10;
    private static final double TRANSFER_DELTA = 0.50;
    private static final double NEGATIVE_DELTA = 10.0;
    private static final Set<FeedbackQueryKeyComparisonCaseCategory> REQUIRED_NORMALIZATION_CATEGORIES =
            Set.copyOf(EnumSet.of(
                    FeedbackQueryKeyComparisonCaseCategory.CASE_NORMALIZATION,
                    FeedbackQueryKeyComparisonCaseCategory.PUNCTUATION_SEPARATOR_NORMALIZATION,
                    FeedbackQueryKeyComparisonCaseCategory.WHITESPACE_NORMALIZATION,
                    FeedbackQueryKeyComparisonCaseCategory.STOP_WORD_NORMALIZATION,
                    FeedbackQueryKeyComparisonCaseCategory.PLURAL_NORMALIZATION,
                    FeedbackQueryKeyComparisonCaseCategory.COMBINED_NORMALIZATION));

    private final FeedbackQueryKeyExperimentRunner experimentRunner;

    public ProjectMemoryFeedbackKeyValidationRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public ProjectMemoryFeedbackKeyValidationRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.experimentRunner = new FeedbackQueryKeyExperimentRunner(
                Objects.requireNonNull(evaluationRunner, "evaluationRunner"),
                Objects.requireNonNull(comparator, "comparator"));
    }

    public ProjectMemoryFeedbackKeyValidationReport run(
            EvaluationDataset dataset,
            List<FeedbackQueryKeyComparisonCase> comparisonCases,
            Path basePath) {
        comparisonCases = List.copyOf(Objects.requireNonNull(comparisonCases, "comparisonCases"));
        validateExperimentShape(comparisonCases);
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
                                new NormalizedQueryKeyStrategy(normalizer))));
        return new ProjectMemoryFeedbackKeyValidationReport(reports, conclusionFor(reports));
    }

    ProjectMemoryFeedbackKeyConclusion conclusionFor(
            List<FeedbackQueryKeyStrategyReport> reports) {
        FeedbackQueryKeyStrategyReport normalized = requireNormalized(reports);
        if (normalized.meritsFurtherInvestigation()) {
            return ProjectMemoryFeedbackKeyConclusion.CONTINUE_NORMALIZED_INVESTIGATION;
        }
        FeedbackQueryKeyStrategySummary summary = normalized.summary();
        boolean unsafe = summary.falseSharingCount() > 0
                || summary.intendedTransferDegraded() > 0
                || normalized.replayedVsBaseline().aggregate() == RankingChange.DEGRADED;
        return unsafe
                ? ProjectMemoryFeedbackKeyConclusion.KEEP_EXACT_ONLY
                : ProjectMemoryFeedbackKeyConclusion.INCONCLUSIVE_KEEP_EXACT;
    }

    private FeedbackQueryKeyStrategyReport requireNormalized(
            List<FeedbackQueryKeyStrategyReport> reports) {
        return reports.stream()
                .filter(report -> report.strategy() == FeedbackQueryKeyComparisonStrategy.NORMALIZED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing normalized strategy report"));
    }

    private void validateExperimentShape(List<FeedbackQueryKeyComparisonCase> comparisonCases) {
        long exactControls = comparisonCases.stream()
                .filter(comparisonCase -> comparisonCase.category()
                        == FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL)
                .count();
        long normalizationTransfers = comparisonCases.stream()
                .filter(comparisonCase -> comparisonCase.category().intendedTransfer())
                .filter(comparisonCase -> comparisonCase.category()
                        != FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL)
                .count();
        long negativeCases = comparisonCases.stream()
                .filter(comparisonCase -> !comparisonCase.category().intendedTransfer())
                .count();
        if (exactControls != EXACT_CONTROL_COUNT
                || normalizationTransfers != NORMALIZATION_TRANSFER_COUNT
                || negativeCases != NEGATIVE_CASE_COUNT) {
            throw new IllegalArgumentException(
                    "project-memory validation requires exactly 1 exact control, 8 normalization transfers, "
                            + "and 8 negative cases");
        }
        if (comparisonCases.stream().map(FeedbackQueryKeyComparisonCase::seedQueryText)
                .distinct().count() != comparisonCases.size()) {
            throw new IllegalArgumentException(
                    "project-memory validation requires distinct seed queries");
        }
        if (comparisonCases.stream().map(FeedbackQueryKeyComparisonCase::createdAt)
                .distinct().count() != comparisonCases.size()) {
            throw new IllegalArgumentException(
                    "project-memory validation requires distinct timestamps");
        }

        Set<FeedbackQueryKeyComparisonCaseCategory> categories = comparisonCases.stream()
                .map(FeedbackQueryKeyComparisonCase::category)
                .collect(Collectors.toUnmodifiableSet());
        if (!categories.containsAll(REQUIRED_NORMALIZATION_CATEGORIES)) {
            throw new IllegalArgumentException(
                    "project-memory validation is missing required normalization categories: "
                            + REQUIRED_NORMALIZATION_CATEGORIES.stream()
                            .filter(category -> !categories.contains(category))
                            .toList());
        }

        for (FeedbackQueryKeyComparisonCase comparisonCase : comparisonCases) {
            if (comparisonCase.signal() != FeedbackSignal.POSITIVE) {
                throw new IllegalArgumentException(
                        "project-memory validation cases must use positive feedback: " + comparisonCase.id());
            }
            double expectedDelta = expectedDelta(comparisonCase);
            if (Double.compare(comparisonCase.delta(), expectedDelta) != 0) {
                throw new IllegalArgumentException(
                        "case " + comparisonCase.id() + " must use delta " + expectedDelta);
            }
            boolean exactControl = comparisonCase.category()
                    == FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL;
            if (exactControl != comparisonCase.seedQueryText().equals(comparisonCase.evaluationQueryText())) {
                throw new IllegalArgumentException(
                        "only the exact control may reuse the evaluation query text: " + comparisonCase.id());
            }
        }
    }

    private double expectedDelta(FeedbackQueryKeyComparisonCase comparisonCase) {
        if (comparisonCase.category() == FeedbackQueryKeyComparisonCaseCategory.EXACT_CONTROL) {
            return EXACT_DELTA;
        }
        return comparisonCase.category().intendedTransfer() ? TRANSFER_DELTA : NEGATIVE_DELTA;
    }
}
