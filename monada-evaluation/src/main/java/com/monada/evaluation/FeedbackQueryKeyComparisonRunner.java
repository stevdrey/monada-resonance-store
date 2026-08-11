package com.monada.evaluation;

import com.monada.api.ExactQueryKeyStrategy;
import com.monada.api.FeedbackQueryKeyStrategy;
import com.monada.api.LexicallyEnrichedQueryKeyStrategy;
import com.monada.api.NormalizedQueryKeyStrategy;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replays identical persisted-feedback cases through exact, normalized, and lexical query keys.
 *
 * <p>Every arm gets a separate real {@code FileFeedbackStore}; only the feedback key strategy
 * varies. The runner intentionally measures rather than changes runtime defaults.
 */
public final class FeedbackQueryKeyComparisonRunner {

    private final EvaluationRunner evaluationRunner;
    private final EvaluationComparator comparator;
    private final PersistedFeedbackReplayRunner persistedReplayRunner;

    public FeedbackQueryKeyComparisonRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public FeedbackQueryKeyComparisonRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.persistedReplayRunner = new PersistedFeedbackReplayRunner(
                this.evaluationRunner, this.comparator);
    }

    public FeedbackQueryKeyComparisonReport run(
            EvaluationDataset dataset,
            List<FeedbackQueryKeyComparisonCase> comparisonCases,
            Path basePath) {
        Objects.requireNonNull(dataset, "dataset");
        comparisonCases = List.copyOf(Objects.requireNonNull(comparisonCases, "comparisonCases"));
        Objects.requireNonNull(basePath, "basePath");
        validateCases(dataset, comparisonCases);

        var normalizer = new LexicalEnrichmentPipeline();
        var reports = new ArrayList<FeedbackQueryKeyStrategyReport>();
        for (StrategyDefinition definition : strategyDefinitions(normalizer)) {
            var preparedCases = prepareCases(comparisonCases, definition.queryKeyStrategy());
            var profile = new EvaluationProfile(
                    "FEEDBACK_QUERY_KEY_" + definition.strategy().name(),
                    normalizer,
                    true,
                    LexicalExpansionOptions.DEFAULT,
                    definition.queryKeyStrategy());
            var arm = persistedReplayRunner.run(
                    dataset,
                    preparedCases.stream().map(PreparedCase::event).toList(),
                    basePath.resolve(definition.strategy().directoryName()),
                    profile);
            reports.add(buildStrategyReport(definition, preparedCases, arm));
        }

        return new FeedbackQueryKeyComparisonReport(reports, recommendationFor(reports));
    }

    private List<StrategyDefinition> strategyDefinitions(LexicalEnrichmentPipeline normalizer) {
        return List.of(
                new StrategyDefinition(
                        FeedbackQueryKeyComparisonStrategy.EXACT,
                        new ExactQueryKeyStrategy()),
                new StrategyDefinition(
                        FeedbackQueryKeyComparisonStrategy.NORMALIZED,
                        new NormalizedQueryKeyStrategy(normalizer)),
                new StrategyDefinition(
                        FeedbackQueryKeyComparisonStrategy.LEXICALLY_ENRICHED,
                        new LexicallyEnrichedQueryKeyStrategy(normalizer)));
    }

    private List<PreparedCase> prepareCases(
            List<FeedbackQueryKeyComparisonCase> comparisonCases,
            FeedbackQueryKeyStrategy queryKeyStrategy) {
        var prepared = new ArrayList<PreparedCase>(comparisonCases.size());
        for (FeedbackQueryKeyComparisonCase comparisonCase : comparisonCases) {
            String seedKey = effectiveKey(queryKeyStrategy, comparisonCase.seedQueryText());
            String evaluationKey = effectiveKey(queryKeyStrategy, comparisonCase.evaluationQueryText());
            var expectedScope = seedKey.equals(evaluationKey)
                    ? FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY
                    : FeedbackReplayExpectedScope.UNMATCHED_EVALUATION_QUERY;
            var event = new FeedbackReplayEvent(
                    comparisonCase.seedQueryText(),
                    seedKey,
                    comparisonCase.targetLabel(),
                    comparisonCase.signal(),
                    comparisonCase.delta(),
                    comparisonCase.createdAt(),
                    expectedScope);
            prepared.add(new PreparedCase(comparisonCase, event, seedKey, evaluationKey));
        }
        return List.copyOf(prepared);
    }

    private String effectiveKey(FeedbackQueryKeyStrategy queryKeyStrategy, String queryText) {
        String key = queryKeyStrategy.keyFor(queryText);
        return key == null || key.isBlank() ? queryText : key;
    }

    private FeedbackQueryKeyStrategyReport buildStrategyReport(
            StrategyDefinition definition,
            List<PreparedCase> preparedCases,
            PersistedFeedbackReplayRunner.ReplayArm arm) {
        var baselineByQuery = indexByQueryText(arm.baseline());
        var replayedByQuery = indexByQueryText(arm.replayedPersisted());
        var observations = new ArrayList<FeedbackQueryKeyCaseObservation>(preparedCases.size());
        for (int index = 0; index < preparedCases.size(); index++) {
            PreparedCase prepared = preparedCases.get(index);
            FeedbackQueryKeyComparisonCase comparisonCase = prepared.comparisonCase();
            FeedbackReplayEventDiagnostic diagnostic = arm.replayDiagnostics().get(index);
            boolean diagnosticMatchesEvaluationQuery = diagnostic.matchedEvaluationQueries()
                    .contains(comparisonCase.evaluationQueryText());
            boolean keysMatched = prepared.seedQueryKey().equals(prepared.evaluationQueryKey());
            if (keysMatched != diagnosticMatchesEvaluationQuery) {
                throw new IllegalStateException(
                        "case " + comparisonCase.id() + " did not produce the expected key-match audit");
            }

            QueryEvaluation baselineQuery = requireQuery(
                    baselineByQuery, comparisonCase.evaluationQueryText(), "baseline");
            QueryEvaluation replayedQuery = requireQuery(
                    replayedByQuery, comparisonCase.evaluationQueryText(), "replayed");
            boolean targetRelevant = baselineQuery.expectedLabels().contains(comparisonCase.targetLabel());
            if (targetRelevant != comparisonCase.expectedTargetRelevant()) {
                throw new IllegalStateException(
                        "case " + comparisonCase.id() + " target relevance changed after validation");
            }

            var before = targetRank(
                    arm.baselineFullCorpusByQuery(), comparisonCase.evaluationQueryText(), comparisonCase.targetLabel());
            var after = targetRank(
                    arm.replayedFullCorpusByQuery(), comparisonCase.evaluationQueryText(), comparisonCase.targetLabel());
            RankingChange targetRankChange = rankChange(before, after);
            FeedbackQueryKeyCaseClassification classification = classify(
                    comparisonCase, keysMatched, before, after);
            observations.add(new FeedbackQueryKeyCaseObservation(
                    comparisonCase,
                    prepared.seedQueryKey(),
                    prepared.evaluationQueryKey(),
                    keysMatched,
                    targetRelevant,
                    before,
                    after,
                    targetRankChange,
                    baselineQuery.returnedLabels(),
                    replayedQuery.returnedLabels(),
                    metricDeltas(baselineQuery.precisionByK(), replayedQuery.precisionByK()),
                    metricDeltas(baselineQuery.recallByK(), replayedQuery.recallByK()),
                    metricDeltas(baselineQuery.hitByK(), replayedQuery.hitByK()),
                    replayedQuery.reciprocalRank() - baselineQuery.reciprocalRank(),
                    classification));
        }

        return new FeedbackQueryKeyStrategyReport(
                definition.strategy(),
                definition.queryKeyStrategy().getClass().getSimpleName(),
                arm.baseline(),
                arm.replayedPersisted(),
                arm.replayedVsBaseline(),
                observations,
                summarize(observations));
    }

    private Map<String, QueryEvaluation> indexByQueryText(EvaluationReport report) {
        var byQuery = new HashMap<String, QueryEvaluation>();
        for (QueryEvaluation query : report.queryResults()) {
            QueryEvaluation prior = byQuery.put(query.queryText(), query);
            if (prior != null) {
                throw new IllegalArgumentException("evaluation report contains duplicate query: " + query.queryText());
            }
        }
        return Map.copyOf(byQuery);
    }

    private QueryEvaluation requireQuery(
            Map<String, QueryEvaluation> queriesByText,
            String queryText,
            String phase) {
        QueryEvaluation query = queriesByText.get(queryText);
        if (query == null) {
            throw new IllegalStateException(phase + " report is missing query: " + queryText);
        }
        return query;
    }

    private FeedbackQueryKeyTargetRank targetRank(
            Map<String, List<PersistedFeedbackReplayRunner.FullCorpusRanking>> rankingsByQuery,
            String queryText,
            String targetLabel) {
        List<PersistedFeedbackReplayRunner.FullCorpusRanking> ranking = rankingsByQuery.get(queryText);
        if (ranking == null) {
            throw new IllegalStateException("full-corpus ranking is missing query: " + queryText);
        }
        return ranking.stream()
                .filter(result -> result.label().equals(targetLabel))
                .findFirst()
                .map(result -> new FeedbackQueryKeyTargetRank(result.rank(), result.score()))
                .orElseThrow(() -> new IllegalStateException(
                        "full-corpus ranking is missing target " + targetLabel + " for query " + queryText));
    }

    private RankingChange rankChange(
            FeedbackQueryKeyTargetRank before,
            FeedbackQueryKeyTargetRank after) {
        if (after.fullCorpusRank() < before.fullCorpusRank()) {
            return RankingChange.IMPROVED;
        }
        if (after.fullCorpusRank() > before.fullCorpusRank()) {
            return RankingChange.DEGRADED;
        }
        return RankingChange.MAINTAINED;
    }

    FeedbackQueryKeyCaseClassification classify(
            FeedbackQueryKeyComparisonCase comparisonCase,
            boolean keysMatched,
            FeedbackQueryKeyTargetRank before,
            FeedbackQueryKeyTargetRank after) {
        boolean targetMoved = before.fullCorpusRank() != after.fullCorpusRank()
                || Math.abs(before.score() - after.score()) > EvaluationComparator.DEFAULT_EPSILON;
        return switch (comparisonCase.category()) {
            case EXACT_CONTROL, NORMALIZATION_EQUIVALENT, LEXICAL_EQUIVALENCE -> keysMatched
                    ? FeedbackQueryKeyCaseClassification.INTENDED_TRANSFER
                    : FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER;
            case CONFUSABLE_NEIGHBOR, SHARED_GENERIC_PHRASE, ORDERED_TERMS_DIFFERENT_INTENT -> !keysMatched
                    ? FeedbackQueryKeyCaseClassification.ISOLATED_NO_TRANSFER
                    : targetMoved
                            ? FeedbackQueryKeyCaseClassification.CONTAMINATION
                            : FeedbackQueryKeyCaseClassification.SHARED_KEY_NO_MOVEMENT;
        };
    }

    private Map<Integer, Double> metricDeltas(
            Map<Integer, Double> baseline,
            Map<Integer, Double> replayed) {
        if (!baseline.keySet().equals(replayed.keySet())) {
            throw new IllegalArgumentException("metric K values differ between baseline and replayed reports");
        }
        var deltas = new TreeMap<Integer, Double>();
        for (Map.Entry<Integer, Double> entry : baseline.entrySet()) {
            deltas.put(entry.getKey(), replayed.get(entry.getKey()) - entry.getValue());
        }
        return Map.copyOf(deltas);
    }

    private FeedbackQueryKeyStrategySummary summarize(
            List<FeedbackQueryKeyCaseObservation> observations) {
        SummaryCounts counts = observations.stream()
                .map(this::summaryCountsFor)
                .reduce(new SummaryCounts(0, 0, 0, 0, 0, 0, 0), SummaryCounts::add);
        return counts.toSummary();
    }

    private SummaryCounts summaryCountsFor(FeedbackQueryKeyCaseObservation observation) {
        return switch (observation.classification()) {
            case INTENDED_TRANSFER -> intendedTransferCounts(observation);
            case ISOLATED_NO_TRANSFER -> observation.comparisonCase().category().intendedTransfer()
                    ? new SummaryCounts(0, 0, 0, 1, 0, 0, 0)
                    : new SummaryCounts(0, 0, 0, 0, 1, 0, 0);
            case SHARED_KEY_NO_MOVEMENT -> new SummaryCounts(0, 0, 0, 0, 0, 1, 0);
            case CONTAMINATION -> new SummaryCounts(0, 0, 0, 0, 0, 0, 1);
        };
    }

    private SummaryCounts intendedTransferCounts(FeedbackQueryKeyCaseObservation observation) {
        if (!observation.comparisonCase().category().intendedTransfer()) {
            throw new IllegalStateException("negative case cannot be an intended transfer");
        }
        return switch (observation.targetRankChange()) {
            case IMPROVED -> new SummaryCounts(1, 0, 0, 0, 0, 0, 0);
            case MAINTAINED -> new SummaryCounts(0, 1, 0, 0, 0, 0, 0);
            case DEGRADED -> new SummaryCounts(0, 0, 1, 0, 0, 0, 0);
        };
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

    private void validateCases(
            EvaluationDataset dataset,
            List<FeedbackQueryKeyComparisonCase> comparisonCases) {
        if (comparisonCases.isEmpty()) {
            throw new IllegalArgumentException("comparisonCases must not be empty");
        }
        Set<String> labels = dataset.atoms().stream()
                .map(DatasetAtom::label)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, EvaluationQuery> queriesByText = dataset.queries().stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                EvaluationQuery::text,
                                Function.identity(),
                                (first, duplicate) -> {
                                    throw new IllegalArgumentException(
                                            "dataset has duplicate query text: " + first.text());
                                },
                                LinkedHashMap::new),
                        Map::copyOf));
        requireDistinct(comparisonCases, FeedbackQueryKeyComparisonCase::id,
                "duplicate comparison case id: ");
        requireDistinct(comparisonCases, FeedbackQueryKeyComparisonCase::evaluationQueryText,
                "comparison cases must use distinct evaluation queries: ");
        for (FeedbackQueryKeyComparisonCase comparisonCase : comparisonCases) {
            if (!labels.contains(comparisonCase.targetLabel())) {
                throw new IllegalArgumentException(
                        "case " + comparisonCase.id() + " references unknown target label: "
                                + comparisonCase.targetLabel());
            }
            EvaluationQuery evaluationQuery = queriesByText.get(comparisonCase.evaluationQueryText());
            if (evaluationQuery == null) {
                throw new IllegalArgumentException(
                        "case " + comparisonCase.id() + " references unknown evaluation query: "
                                + comparisonCase.evaluationQueryText());
            }
            boolean actualRelevant = evaluationQuery.expectedLabels().contains(comparisonCase.targetLabel());
            if (actualRelevant != comparisonCase.expectedTargetRelevant()) {
                throw new IllegalArgumentException(
                        "case " + comparisonCase.id() + " expected target relevance "
                                + comparisonCase.expectedTargetRelevant() + " but dataset relevance is "
                                + actualRelevant);
            }
        }
    }

    private void requireDistinct(
            List<FeedbackQueryKeyComparisonCase> comparisonCases,
            Function<FeedbackQueryKeyComparisonCase, String> selector,
            String messagePrefix) {
        comparisonCases.stream()
                .collect(Collectors.groupingBy(selector, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(duplicate -> {
                    throw new IllegalArgumentException(messagePrefix + duplicate);
                });
    }

    private record SummaryCounts(
            int intendedImproved,
            int intendedMaintained,
            int intendedDegraded,
            int intendedIsolated,
            int negativeIsolated,
            int sharedWithoutMovement,
            int contamination) {

        private SummaryCounts add(SummaryCounts other) {
            return new SummaryCounts(
                    intendedImproved + other.intendedImproved,
                    intendedMaintained + other.intendedMaintained,
                    intendedDegraded + other.intendedDegraded,
                    intendedIsolated + other.intendedIsolated,
                    negativeIsolated + other.negativeIsolated,
                    sharedWithoutMovement + other.sharedWithoutMovement,
                    contamination + other.contamination);
        }

        private FeedbackQueryKeyStrategySummary toSummary() {
            int falseSharing = sharedWithoutMovement + contamination;
            return new FeedbackQueryKeyStrategySummary(
                    intendedImproved,
                    intendedMaintained,
                    intendedDegraded,
                    intendedIsolated,
                    negativeIsolated,
                    sharedWithoutMovement,
                    contamination,
                    falseSharing,
                    negativeIsolated + falseSharing);
        }
    }

    private record StrategyDefinition(
            FeedbackQueryKeyComparisonStrategy strategy,
            FeedbackQueryKeyStrategy queryKeyStrategy) {
    }

    private record PreparedCase(
            FeedbackQueryKeyComparisonCase comparisonCase,
            FeedbackReplayEvent event,
            String seedQueryKey,
            String evaluationQueryKey) {
    }
}
