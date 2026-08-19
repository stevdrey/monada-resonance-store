package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechSample;
import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Stress-tests one #78-selected profile without changing retrieval behavior. */
final class SpeechHybridRobustnessRunner {

    static final SpeechFusionWeights FIXED_CANDIDATE = new SpeechFusionWeights(0.50, 0.50);

    private final SpeechPairedRankingCollector rankingCollector = new SpeechPairedRankingCollector();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();

    SpeechHybridRobustnessReport run(
            SpeechModalityEvidence evidence,
            String label,
            List<SpeechHybridRobustnessCase> cases,
            int k,
            SpeechSampleRetriever acousticRetriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            Path transcriptMemoryPath
    ) throws IOException {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(cases, "cases");
        if (label.isBlank() || cases.isEmpty() || k <= 0) {
            throw new IllegalArgumentException("label, cases, and k must be non-empty/positive");
        }
        Map<String, SpeechHybridRobustnessCase> casesById = indexCases(cases);
        SpeechPairedRankingSet collected = rankingCollector.collect(
                cases.stream().map(SpeechHybridRobustnessCase::query).toList(), acousticRetriever,
                sampleStore, featureStore, transcriptMemoryPath);
        Set<String> corpusSampleIdSet = Set.copyOf(collected.corpusSampleIds());
        Map<String, SpeechSample> samplesById = sampleStore.findAll().stream()
                .collect(Collectors.toMap(SpeechSample::id, sample -> sample));

        List<SpeechHybridRobustnessQueryResult> results = new ArrayList<>();
        for (SpeechPairedRanking collectedRanking : collected.queryRankings()) {
            SpeechHybridRobustnessCase stressCase = casesById.get(collectedRanking.query().queryId());
            SpeechPairedRanking masked = applyAvailabilityMask(collectedRanking, stressCase, corpusSampleIdSet);
            SpeechHybridQueryContext context = profileEvaluator.createContext(masked, collected.corpusSampleIds(), k);
            validateExpectedConflict(stressCase, context);
            SpeechHybridProfileQueryResult hybrid = profileEvaluator.evaluateProfileQuery(FIXED_CANDIDATE, context);
            SpeechHybridBetterControl stronger = strongerControl(context);
            SpeechHybridComparison vsStronger = profileEvaluator.compare(hybrid.result().metrics(), strongerMetrics(context, stronger));
            results.add(new SpeechHybridRobustnessQueryResult(
                    stressCase, context, hybrid, speakerRelation(stressCase, samplesById), stronger, vsStronger));
        }
        List<SpeechHybridRobustnessQueryResult> ordered = results.stream()
                .sorted(Comparator.comparing(result -> result.stressCase().query().queryId()))
                .toList();
        SpeechHybridRobustnessSummary aggregate = summarize(ordered);
        Map<String, SpeechHybridRobustnessSummary> byCondition = summarizeBy(ordered,
                result -> result.stressCase().query().condition().name());
        Map<String, SpeechHybridRobustnessSummary> byTask = summarizeBy(ordered,
                result -> result.stressCase().query().taskType().name());
        Map<String, SpeechHybridRobustnessSummary> bySpeaker = summarizeBy(ordered,
                result -> result.speakerRelation().name());
        Map<String, SpeechHybridRobustnessSummary> byConflict = summarizeBy(ordered,
                result -> result.stressCase().expectedConflictCategory().name());
        SpeechHybridRobustnessQueryResult worst = worstRegression(ordered);
        boolean groupRegression = hasGroupRegression(aggregate, byCondition, byTask, bySpeaker, byConflict);
        return new SpeechHybridRobustnessReport(evidence, label, collected.corpusSize(), k, FIXED_CANDIDATE,
                ordered, aggregate, byCondition, byTask, bySpeaker, byConflict, worst,
                decide(ordered, groupRegression));
    }

    SpeechHybridRobustnessDecision decide(
            List<SpeechHybridRobustnessQueryResult> results,
            boolean groupRegression
    ) {
        if (groupRegression || results.stream().anyMatch(result -> result.vsStrongerControl() == SpeechHybridComparison.REGRESS)) {
            return SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_RISKY;
        }
        if (!hasRequiredCoverage(results)) {
            return SpeechHybridRobustnessDecision.INCONCLUSIVE;
        }
        return SpeechHybridRobustnessDecision.HYBRID_CANDIDATE_ROBUST;
    }

    private Map<String, SpeechHybridRobustnessCase> indexCases(List<SpeechHybridRobustnessCase> cases) {
        Map<String, SpeechHybridRobustnessCase> byId = new HashMap<>();
        for (SpeechHybridRobustnessCase stressCase : cases) {
            Objects.requireNonNull(stressCase, "case");
            String queryId = stressCase.query().queryId();
            if (byId.putIfAbsent(queryId, stressCase) != null) {
                throw new IllegalArgumentException("duplicate robustness query id: " + queryId);
            }
        }
        return Map.copyOf(byId);
    }

    private SpeechPairedRanking applyAvailabilityMask(
            SpeechPairedRanking ranking,
            SpeechHybridRobustnessCase stressCase,
            Set<String> corpusSampleIds
    ) {
        validateKnownMaskIds(stressCase.transcriptUnavailableSampleIds(), corpusSampleIds, "transcript");
        validateKnownMaskIds(stressCase.acousticUnavailableSampleIds(), corpusSampleIds, "acoustic");
        return new SpeechPairedRanking(ranking.query(),
                filterAndRerank(ranking.transcriptRanking(), stressCase.transcriptUnavailableSampleIds()),
                filterAndRerank(ranking.acousticRanking(), stressCase.acousticUnavailableSampleIds()));
    }

    private void validateKnownMaskIds(Set<String> ids, Set<String> corpusSampleIds, String modality) {
        Set<String> unknown = ids.stream().filter(id -> !corpusSampleIds.contains(id))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(modality + " availability mask references unknown samples: " + unknown);
        }
    }

    private List<SpeechModalityRankedResult> filterAndRerank(
            List<SpeechModalityRankedResult> ranking,
            Set<String> unavailableIds
    ) {
        List<SpeechModalityRankedResult> present = ranking.stream()
                .filter(result -> !unavailableIds.contains(result.sampleId()))
                .toList();
        List<SpeechModalityRankedResult> reranked = new ArrayList<>(present.size());
        for (int index = 0; index < present.size(); index++) {
            SpeechModalityRankedResult result = present.get(index);
            reranked.add(new SpeechModalityRankedResult(result.sampleId(), result.score(), index + 1));
        }
        return List.copyOf(reranked);
    }

    private void validateExpectedConflict(SpeechHybridRobustnessCase stressCase, SpeechHybridQueryContext context) {
        SpeechHybridConflictCategory actual = classify(context, stressCase);
        if (actual != stressCase.expectedConflictCategory()) {
            throw new IllegalArgumentException("case " + stressCase.query().queryId() + " expected "
                    + stressCase.expectedConflictCategory() + " but observed " + actual);
        }
    }

    private SpeechHybridConflictCategory classify(SpeechHybridQueryContext context, SpeechHybridRobustnessCase stressCase) {
        if (!stressCase.transcriptUnavailableSampleIds().isEmpty()) {
            requireMissingRelevant(context.transcriptRanking(), context.query().relevantSampleIds(), "transcript");
            requireHit(context.acousticControl().metrics().hitAtK(), "acoustic", context.query().queryId());
            return SpeechHybridConflictCategory.TRANSCRIPT_MISSING;
        }
        if (!stressCase.acousticUnavailableSampleIds().isEmpty()) {
            requireMissingRelevant(context.acousticRanking(), context.query().relevantSampleIds(), "acoustic");
            requireHit(context.transcriptControl().metrics().hitAtK(), "transcript", context.query().queryId());
            return SpeechHybridConflictCategory.ACOUSTIC_MISSING;
        }
        boolean transcriptHit = context.transcriptControl().metrics().hitAtK();
        boolean acousticHit = context.acousticControl().metrics().hitAtK();
        if (transcriptHit && acousticHit) return SpeechHybridConflictCategory.AGREEMENT_RELEVANT;
        if (transcriptHit) {
            requireStrictWrongTop(context.acousticRanking(), context.query().relevantSampleIds(), "acoustic");
            return SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT;
        }
        if (acousticHit) {
            requireStrictWrongTop(context.transcriptRanking(), context.query().relevantSampleIds(), "transcript");
            return SpeechHybridConflictCategory.ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT;
        }
        return SpeechHybridConflictCategory.BOTH_AMBIGUOUS;
    }

    private void requireMissingRelevant(List<SpeechModalityRankedResult> ranking, Set<String> relevantIds, String modality) {
        if (ranking.stream().anyMatch(result -> relevantIds.contains(result.sampleId()))) {
            throw new IllegalArgumentException(modality + " missing-modality case retained a relevant candidate");
        }
    }

    private void requireHit(boolean hit, String modality, String queryId) {
        if (!hit) throw new IllegalArgumentException(modality + " must remain a hit for missing-modality case: " + queryId);
    }

    private void requireStrictWrongTop(List<SpeechModalityRankedResult> ranking, Set<String> relevantIds, String modality) {
        SpeechModalityRankedResult top = ranking.getFirst();
        SpeechModalityRankedResult relevant = ranking.stream().filter(result -> relevantIds.contains(result.sampleId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(modality + " conflict has no relevant candidate"));
        if (relevantIds.contains(top.sampleId()) || Double.compare(top.score(), relevant.score()) <= 0) {
            throw new IllegalArgumentException(modality + " conflict must have a strictly higher non-relevant top score");
        }
    }

    private SpeechSpeakerRelation speakerRelation(
            SpeechHybridRobustnessCase stressCase,
            Map<String, SpeechSample> samplesById
    ) {
        Set<String> speakers = stressCase.query().relevantSampleIds().stream()
                .map(samplesById::get).filter(Objects::nonNull).map(SpeechSample::speakerId).collect(Collectors.toSet());
        if (speakers.isEmpty()) return SpeechSpeakerRelation.UNKNOWN;
        boolean same = speakers.stream().allMatch(stressCase.querySpeakerId()::equals);
        boolean cross = speakers.stream().noneMatch(stressCase.querySpeakerId()::equals);
        return same ? SpeechSpeakerRelation.SAME_SPEAKER : cross ? SpeechSpeakerRelation.CROSS_SPEAKER
                : SpeechSpeakerRelation.MIXED_SPEAKERS;
    }

    private SpeechHybridBetterControl strongerControl(SpeechHybridQueryContext context) {
        SpeechHybridComparison comparison = profileEvaluator.compare(
                context.transcriptControl().metrics(), context.acousticControl().metrics());
        return switch (comparison) {
            case WIN -> SpeechHybridBetterControl.TRANSCRIPT;
            case REGRESS -> SpeechHybridBetterControl.ACOUSTIC;
            case MAINTAIN -> SpeechHybridBetterControl.TIED;
        };
    }

    private SpeechModalityQueryMetrics strongerMetrics(SpeechHybridQueryContext context, SpeechHybridBetterControl control) {
        return control == SpeechHybridBetterControl.ACOUSTIC ? context.acousticControl().metrics()
                : context.transcriptControl().metrics();
    }

    SpeechHybridRobustnessSummary summarize(List<SpeechHybridRobustnessQueryResult> results) {
        if (results.isEmpty()) return SpeechHybridRobustnessSummary.empty();
        double[] transcript = new double[4], acoustic = new double[4], hybrid = new double[4];
        Map<SpeechHybridComparison, Integer> comparisons = new EnumMap<>(SpeechHybridComparison.class);
        for (SpeechHybridComparison value : SpeechHybridComparison.values()) comparisons.put(value, 0);
        for (SpeechHybridRobustnessQueryResult result : results) {
            add(transcript, result.context().transcriptControl().metrics());
            add(acoustic, result.context().acousticControl().metrics());
            add(hybrid, result.hybrid().result().metrics());
            comparisons.merge(result.vsStrongerControl(), 1, Integer::sum);
        }
        int count = results.size();
        SpeechEvaluationMetrics transcriptMetrics = metrics(transcript, count);
        SpeechEvaluationMetrics acousticMetrics = metrics(acoustic, count);
        SpeechEvaluationMetrics hybridMetrics = metrics(hybrid, count);
        return new SpeechHybridRobustnessSummary(transcriptMetrics, acousticMetrics, hybridMetrics, comparisons,
                belowStrongerGroupControl(hybridMetrics, transcriptMetrics, acousticMetrics));
    }

    private void add(double[] totals, SpeechModalityQueryMetrics metrics) {
        totals[0] += metrics.precisionAtK(); totals[1] += metrics.recallAtK();
        totals[2] += metrics.hitAtK() ? 1.0 : 0.0; totals[3] += metrics.reciprocalRank();
    }

    private SpeechEvaluationMetrics metrics(double[] totals, int count) {
        return new SpeechEvaluationMetrics(totals[0] / count, totals[1] / count, totals[2] / count, totals[3] / count, count);
    }

    private boolean belowStrongerGroupControl(
            SpeechEvaluationMetrics hybrid,
            SpeechEvaluationMetrics transcript,
            SpeechEvaluationMetrics acoustic
    ) {
        double tolerance = SpeechHybridProfileEvaluator.COMPARISON_TOLERANCE;
        return hybrid.precisionAtK() + tolerance < Math.max(transcript.precisionAtK(), acoustic.precisionAtK())
                || hybrid.recallAtK() + tolerance < Math.max(transcript.recallAtK(), acoustic.recallAtK())
                || hybrid.hitRateAtK() + tolerance < Math.max(transcript.hitRateAtK(), acoustic.hitRateAtK())
                || hybrid.mrr() + tolerance < Math.max(transcript.mrr(), acoustic.mrr());
    }

    Map<String, SpeechHybridRobustnessSummary> summarizeBy(
            List<SpeechHybridRobustnessQueryResult> results,
            java.util.function.Function<SpeechHybridRobustnessQueryResult, String> key
    ) {
        Map<String, List<SpeechHybridRobustnessQueryResult>> groups = new LinkedHashMap<>();
        for (SpeechHybridRobustnessQueryResult result : results) {
            groups.computeIfAbsent(key.apply(result), ignored -> new ArrayList<>()).add(result);
        }
        Map<String, SpeechHybridRobustnessSummary> summaries = new LinkedHashMap<>();
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> summaries.put(entry.getKey(), summarize(entry.getValue())));
        return Map.copyOf(summaries);
    }

    private SpeechHybridRobustnessQueryResult worstRegression(List<SpeechHybridRobustnessQueryResult> results) {
        return results.stream().filter(result -> result.vsStrongerControl() == SpeechHybridComparison.REGRESS)
                .min(Comparator.comparingDouble((SpeechHybridRobustnessQueryResult result) ->
                        result.hybrid().result().metrics().recallAtK() - strongerMetrics(result.context(), result.strongerControl()).recallAtK())
                        .thenComparingDouble(result -> result.hybrid().result().metrics().reciprocalRank()
                                - strongerMetrics(result.context(), result.strongerControl()).reciprocalRank())
                        .thenComparing(result -> result.stressCase().query().queryId()))
                .orElse(null);
    }

    boolean hasGroupRegression(
            SpeechHybridRobustnessSummary aggregate,
            Map<String, SpeechHybridRobustnessSummary> byCondition,
            Map<String, SpeechHybridRobustnessSummary> byTask,
            Map<String, SpeechHybridRobustnessSummary> bySpeaker,
            Map<String, SpeechHybridRobustnessSummary> byConflict
    ) {
        return aggregate.groupRegression()
                || hasGroupRegression(byCondition)
                || hasGroupRegression(byTask)
                || hasGroupRegression(bySpeaker)
                || hasGroupRegression(byConflict);
    }

    private boolean hasGroupRegression(Map<String, SpeechHybridRobustnessSummary> summaries) {
        return summaries.values().stream().anyMatch(SpeechHybridRobustnessSummary::groupRegression);
    }

    private boolean hasRequiredCoverage(List<SpeechHybridRobustnessQueryResult> results) {
        Set<SpeechHybridConflictCategory> conflicts = results.stream()
                .map(result -> result.stressCase().expectedConflictCategory()).collect(Collectors.toSet());
        Set<String> conditions = results.stream().map(result -> result.stressCase().query().condition().name()).collect(Collectors.toSet());
        Set<String> tasks = results.stream().map(result -> result.stressCase().query().taskType().name()).collect(Collectors.toSet());
        Set<SpeechSpeakerRelation> speakers = results.stream().map(SpeechHybridRobustnessQueryResult::speakerRelation).collect(Collectors.toSet());
        return conflicts.containsAll(Set.of(SpeechHybridConflictCategory.values()))
                && conditions.containsAll(Set.of("CONTROL", "DYSARTHRIC"))
                && tasks.containsAll(Set.of("WORD", "SENTENCE", "COMMAND"))
                && speakers.containsAll(Set.of(SpeechSpeakerRelation.SAME_SPEAKER, SpeechSpeakerRelation.CROSS_SPEAKER));
    }
}

record SpeechHybridRobustnessQueryResult(
        SpeechHybridRobustnessCase stressCase,
        SpeechHybridQueryContext context,
        SpeechHybridProfileQueryResult hybrid,
        SpeechSpeakerRelation speakerRelation,
        SpeechHybridBetterControl strongerControl,
        SpeechHybridComparison vsStrongerControl
) {
}

record SpeechHybridRobustnessSummary(
        SpeechEvaluationMetrics transcriptMetrics,
        SpeechEvaluationMetrics acousticMetrics,
        SpeechEvaluationMetrics hybridMetrics,
        Map<SpeechHybridComparison, Integer> strongerControlComparisonCounts,
        boolean groupRegression
) {
    SpeechHybridRobustnessSummary {
        Objects.requireNonNull(transcriptMetrics, "transcriptMetrics");
        Objects.requireNonNull(acousticMetrics, "acousticMetrics");
        Objects.requireNonNull(hybridMetrics, "hybridMetrics");
        strongerControlComparisonCounts = immutableCounts(strongerControlComparisonCounts);
    }

    static SpeechHybridRobustnessSummary empty() {
        SpeechEvaluationMetrics empty = new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0);
        return new SpeechHybridRobustnessSummary(empty, empty, empty, Map.of(), false);
    }

    private static Map<SpeechHybridComparison, Integer> immutableCounts(
            Map<SpeechHybridComparison, Integer> source
    ) {
        Objects.requireNonNull(source, "strongerControlComparisonCounts");
        Map<SpeechHybridComparison, Integer> copy = new EnumMap<>(SpeechHybridComparison.class);
        for (SpeechHybridComparison comparison : SpeechHybridComparison.values()) {
            int count = source.getOrDefault(comparison, 0);
            if (count < 0) {
                throw new IllegalArgumentException("strongerControlComparisonCounts must be non-negative");
            }
            copy.put(comparison, count);
        }
        return Collections.unmodifiableMap(copy);
    }
}
