package com.monada.evaluation;

import com.monada.api.NormalizedQueryKeyStrategy;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.encoder.LexicalExpansionOptions;
import com.monada.evaluation.datasets.NormalizedQueryKeyStressDataset;
import com.monada.storage.feedback.FeedbackSignal;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Runs isolated persisted-feedback replay for adversarial normalized query pairs. */
public final class NormalizedQueryKeyStressRunner {

    private static final int REQUIRED_CASE_COUNT = 24;
    private static final Map<NormalizationStressCategory, Integer> REQUIRED_CATEGORY_COUNTS = Map.ofEntries(
            Map.entry(NormalizationStressCategory.CASING, 1),
            Map.entry(NormalizationStressCategory.PUNCTUATION_SEPARATOR, 2),
            Map.entry(NormalizationStressCategory.WHITESPACE, 1),
            Map.entry(NormalizationStressCategory.PLURAL_MAPPING, 2),
            Map.entry(NormalizationStressCategory.HARMLESS_STOP_WORD, 2),
            Map.entry(NormalizationStressCategory.STOP_WORD_COORDINATION, 2),
            Map.entry(NormalizationStressCategory.STOP_WORD_RELATION, 4),
            Map.entry(NormalizationStressCategory.TOKEN_ORDER, 2),
            Map.entry(NormalizationStressCategory.NUMERIC_VERSION_IDENTIFIER, 2),
            Map.entry(NormalizationStressCategory.NEGATION_EXCLUSION, 2),
            Map.entry(NormalizationStressCategory.TECHNICAL_MODULE_TERM, 2),
            Map.entry(NormalizationStressCategory.BLANK_FALLBACK_BOUNDARY, 2));

    private final PersistedFeedbackReplayRunner persistedReplayRunner;

    public NormalizedQueryKeyStressRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public NormalizedQueryKeyStressRunner(
            EvaluationRunner evaluationRunner,
            EvaluationComparator comparator) {
        persistedReplayRunner = new PersistedFeedbackReplayRunner(
                Objects.requireNonNull(evaluationRunner, "evaluationRunner"),
                Objects.requireNonNull(comparator, "comparator"));
    }

    public NormalizedQueryKeyStressReport run(
            List<NormalizedQueryKeyStressCase> stressCases,
            Path basePath) {
        stressCases = List.copyOf(Objects.requireNonNull(stressCases, "stressCases"));
        Objects.requireNonNull(basePath, "basePath");
        validateExperimentShape(stressCases);

        var normalizer = new LexicalEnrichmentPipeline();
        var strategy = new NormalizedQueryKeyStrategy(normalizer);
        var profile = new EvaluationProfile(
                "NORMALIZED_QUERY_KEY_STRESS",
                normalizer,
                true,
                LexicalExpansionOptions.DEFAULT,
                strategy);
        var observations = new ArrayList<NormalizedQueryKeyStressObservation>(stressCases.size());
        for (NormalizedQueryKeyStressCase stressCase : stressCases) {
            String normalizedA = normalizer.normalize(stressCase.queryA()).normalized();
            String normalizedB = normalizer.normalize(stressCase.queryB()).normalized();
            String queryKeyA = effectiveKey(strategy.keyFor(stressCase.queryA()), stressCase.queryA());
            String queryKeyB = effectiveKey(strategy.keyFor(stressCase.queryB()), stressCase.queryB());
            boolean keysMatched = queryKeyA.equals(queryKeyB);
            validateExpectedKeyRelation(stressCase, keysMatched);

            var replayEvent = new FeedbackReplayEvent(
                    stressCase.queryA(),
                    queryKeyA,
                    stressCase.feedbackTargetLabel(),
                    FeedbackSignal.POSITIVE,
                    stressCase.delta(),
                    stressCase.createdAt(),
                    keysMatched
                            ? FeedbackReplayExpectedScope.MATCHING_EVALUATION_QUERY
                            : FeedbackReplayExpectedScope.UNMATCHED_EVALUATION_QUERY);
            var dataset = NormalizedQueryKeyStressDataset.forCase(stressCase);
            var arm = persistedReplayRunner.run(
                    dataset,
                    List.of(replayEvent),
                    basePath.resolve(stressCase.id()),
                    profile);
            QueryEvaluation baselineQuery = arm.baseline().queryResults().getFirst();
            QueryEvaluation replayedQuery = arm.replayedPersisted().queryResults().getFirst();
            List<PersistedFeedbackReplayRunner.FullCorpusRanking> baselineFullCorpus = fullCorpusRanking(
                    arm.baselineFullCorpusByQuery(), stressCase.queryB());
            List<PersistedFeedbackReplayRunner.FullCorpusRanking> replayedFullCorpus = fullCorpusRanking(
                    arm.replayedFullCorpusByQuery(), stressCase.queryB());
            FeedbackQueryKeyTargetRank before = targetRank(
                    baselineFullCorpus, stressCase.queryB(), stressCase.feedbackTargetLabel());
            FeedbackQueryKeyTargetRank after = targetRank(
                    replayedFullCorpus, stressCase.queryB(), stressCase.feedbackTargetLabel());
            RankingChange rankChange = rankChange(before, after);
            boolean scoreChanged = Math.abs(before.score() - after.score())
                    > EvaluationComparator.DEFAULT_EPSILON;
            var classification = classify(stressCase.semanticRelationship(), keysMatched,
                    rankChange != RankingChange.MAINTAINED || scoreChanged);
            observations.add(new NormalizedQueryKeyStressObservation(
                    stressCase,
                    normalizedA,
                    normalizedB,
                    queryKeyA,
                    queryKeyB,
                    keysMatched,
                    before,
                    after,
                    rankChange,
                    scoreChanged,
                    baselineQuery.returnedLabels(),
                    replayedQuery.returnedLabels(),
                    isTopKPrefixConsistent(baselineQuery.returnedLabels(), baselineFullCorpus),
                    isTopKPrefixConsistent(replayedQuery.returnedLabels(), replayedFullCorpus),
                    classification));
        }
        List<NormalizedQueryKeyStressObservation> immutableObservations = List.copyOf(observations);
        return new NormalizedQueryKeyStressReport(
                immutableObservations,
                summarize(immutableObservations),
                decisionFor(immutableObservations));
    }

    NormalizedQueryKeyStressClassification classify(
            NormalizationSemanticRelationship relationship,
            boolean keysMatched,
            boolean targetMoved) {
        Objects.requireNonNull(relationship, "relationship");
        if (relationship == NormalizationSemanticRelationship.EQUIVALENT) {
            if (!keysMatched) {
                throw new IllegalArgumentException("equivalent stress pairs must share a key");
            }
            return NormalizedQueryKeyStressClassification.SAFE_EQUIVALENT_SHARING;
        }
        if (!keysMatched) {
            return NormalizedQueryKeyStressClassification.SAFE_ISOLATION;
        }
        return targetMoved
                ? NormalizedQueryKeyStressClassification.CONTAMINATION
                : NormalizedQueryKeyStressClassification.COLLISION_WITHOUT_MOVEMENT;
    }

    NormalizedQueryKeyStressDecision decisionFor(
            List<NormalizedQueryKeyStressObservation> observations) {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        boolean risk = observations.stream().anyMatch(observation ->
                observation.stressCase().semanticRelationship()
                        == NormalizationSemanticRelationship.DISTINCT
                        && observation.keysMatched());
        if (risk) {
            return NormalizedQueryKeyStressDecision.NORMALIZED_STRESS_RISK;
        }
        boolean allEquivalentShared = observations.stream()
                .filter(observation -> observation.stressCase().semanticRelationship()
                        == NormalizationSemanticRelationship.EQUIVALENT)
                .allMatch(NormalizedQueryKeyStressObservation::keysMatched);
        boolean replaySensitivityDemonstrated = observations.stream()
                .filter(observation -> observation.stressCase().semanticRelationship()
                        == NormalizationSemanticRelationship.EQUIVALENT)
                .anyMatch(NormalizedQueryKeyStressObservation::targetMoved);
        return allEquivalentShared && replaySensitivityDemonstrated
                ? NormalizedQueryKeyStressDecision.NORMALIZED_STRESS_PASS
                : NormalizedQueryKeyStressDecision.INCONCLUSIVE;
    }

    private List<NormalizedQueryKeyStressCategorySummary> summarize(
            List<NormalizedQueryKeyStressObservation> observations) {
        var summaries = new ArrayList<NormalizedQueryKeyStressCategorySummary>();
        for (NormalizationStressCategory category : NormalizationStressCategory.values()) {
            List<NormalizedQueryKeyStressObservation> categoryObservations = observations.stream()
                    .filter(observation -> observation.stressCase().category() == category)
                    .toList();
            int equivalentCount = count(categoryObservations,
                    observation -> observation.stressCase().semanticRelationship()
                            == NormalizationSemanticRelationship.EQUIVALENT);
            int equivalentMatches = count(categoryObservations,
                    observation -> observation.stressCase().semanticRelationship()
                            == NormalizationSemanticRelationship.EQUIVALENT
                            && observation.keysMatched());
            int distinctCount = categoryObservations.size() - equivalentCount;
            int distinctCollisions = count(categoryObservations,
                    observation -> observation.stressCase().semanticRelationship()
                            == NormalizationSemanticRelationship.DISTINCT
                            && observation.keysMatched());
            int contamination = count(categoryObservations,
                    observation -> observation.classification()
                            == NormalizedQueryKeyStressClassification.CONTAMINATION);
            summaries.add(new NormalizedQueryKeyStressCategorySummary(
                    category,
                    categoryObservations.size(),
                    equivalentCount,
                    equivalentMatches,
                    distinctCount,
                    distinctCollisions,
                    contamination));
        }
        return List.copyOf(summaries);
    }

    private int count(
            List<NormalizedQueryKeyStressObservation> observations,
            Predicate<NormalizedQueryKeyStressObservation> predicate) {
        return Math.toIntExact(observations.stream().filter(predicate).count());
    }

    private void validateExperimentShape(List<NormalizedQueryKeyStressCase> stressCases) {
        if (stressCases.size() != REQUIRED_CASE_COUNT) {
            throw new IllegalArgumentException(
                    "normalized query-key stress requires exactly " + REQUIRED_CASE_COUNT + " cases");
        }
        var ids = new HashSet<String>();
        var timestamps = new HashSet<Instant>();
        var categoryCounts = new EnumMap<NormalizationStressCategory, Integer>(
                NormalizationStressCategory.class);
        for (NormalizedQueryKeyStressCase stressCase : stressCases) {
            if (!ids.add(stressCase.id())) {
                throw new IllegalArgumentException("duplicate stress case id: " + stressCase.id());
            }
            if (!timestamps.add(stressCase.createdAt())) {
                throw new IllegalArgumentException("duplicate stress case timestamp: " + stressCase.createdAt());
            }
            categoryCounts.merge(stressCase.category(), 1, Integer::sum);
        }
        if (!categoryCounts.equals(REQUIRED_CATEGORY_COUNTS)) {
            throw new IllegalArgumentException(
                    "normalized query-key stress category counts must be " + REQUIRED_CATEGORY_COUNTS
                            + " but were " + categoryCounts);
        }
    }

    private void validateExpectedKeyRelation(
            NormalizedQueryKeyStressCase stressCase,
            boolean keysMatched) {
        boolean expectationFailed = switch (stressCase.expectedKeyRelation()) {
            case MATCH -> !keysMatched;
            case DISTINCT -> keysMatched;
            case DISCOVER -> false;
        };
        if (expectationFailed) {
            throw new IllegalArgumentException(
                    "case " + stressCase.id() + " expected key relation "
                            + stressCase.expectedKeyRelation() + " but keysMatched=" + keysMatched);
        }
    }

    private String effectiveKey(String strategyKey, String queryText) {
        return strategyKey == null || strategyKey.isBlank() ? queryText : strategyKey;
    }

    private List<PersistedFeedbackReplayRunner.FullCorpusRanking> fullCorpusRanking(
            Map<String, List<PersistedFeedbackReplayRunner.FullCorpusRanking>> rankingsByQuery,
            String queryText) {
        List<PersistedFeedbackReplayRunner.FullCorpusRanking> rankings = rankingsByQuery.get(queryText);
        if (rankings == null) {
            throw new IllegalStateException("full-corpus ranking is missing query: " + queryText);
        }
        return rankings;
    }

    private FeedbackQueryKeyTargetRank targetRank(
            List<PersistedFeedbackReplayRunner.FullCorpusRanking> rankings,
            String queryText,
            String targetLabel) {
        return rankings.stream()
                .filter(result -> result.label().equals(targetLabel))
                .findFirst()
                .map(result -> new FeedbackQueryKeyTargetRank(result.rank(), result.score()))
                .orElseThrow(() -> new IllegalStateException(
                        "full-corpus ranking is missing target " + targetLabel + " for query " + queryText));
    }

    private boolean isTopKPrefixConsistent(
            List<String> normalTopK,
            List<PersistedFeedbackReplayRunner.FullCorpusRanking> fullCorpusRanking) {
        if (normalTopK.size() > fullCorpusRanking.size()) {
            return false;
        }
        for (int index = 0; index < normalTopK.size(); index++) {
            if (!normalTopK.get(index).equals(fullCorpusRanking.get(index).label())) {
                return false;
            }
        }
        return true;
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
}
