package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs transcript-only and acoustic-only retrieval independently over the same
 * paired query cases. The runner never combines modality scores.
 */
public final class SpeechModalityComparisonRunner {

    /**
     * Runs a paired comparison using a new, isolated transcript memory. The
     * supplied transcript memory path must not exist or must be an empty
     * directory.
     */
    public SpeechModalityComparisonReport run(
            SpeechModalityEvidence evidence,
            String label,
            List<PairedSpeechQuery> queries,
            int k,
            SpeechSampleRetriever acousticRetriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            Path transcriptMemoryPath
    ) throws IOException {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(acousticRetriever, "acousticRetriever");
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");
        Objects.requireNonNull(transcriptMemoryPath, "transcriptMemoryPath");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must not be empty");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        SpeechPairedRankingSet collected = new SpeechPairedRankingCollector().collect(
                queries, acousticRetriever, sampleStore, featureStore, transcriptMemoryPath);
        SpeechRankingEvaluator evaluator = new SpeechRankingEvaluator();
        List<SpeechModalityComparisonQueryResult> results = new ArrayList<>(collected.queryRankings().size());
        for (SpeechPairedRanking pairedRanking : collected.queryRankings()) {
            PairedSpeechQuery query = pairedRanking.query();
            SpeechModalityQueryResult transcript = evaluator.evaluate(
                    pairedRanking.transcriptRanking(), query.relevantSampleIds(), k);
            SpeechModalityQueryResult acoustic = evaluator.evaluate(
                    pairedRanking.acousticRanking(), query.relevantSampleIds(), k);
            results.add(new SpeechModalityComparisonQueryResult(
                    query,
                    transcript,
                    acoustic,
                    topKJaccard(transcript.topResults(), acoustic.topResults()),
                    classify(transcript.metrics().hitAtK(), acoustic.metrics().hitAtK())));
        }

        SpeechModalitySummary aggregate = summarize(results);
        Map<SpeechCondition, SpeechModalitySummary> byCondition = summarizeByCondition(results);
        Map<SpeechTaskType, SpeechModalitySummary> byTaskType = summarizeByTaskType(results);
        return new SpeechModalityComparisonReport(
                evidence,
                label,
                collected.corpusSize(),
                k,
                results,
                aggregate,
                byCondition,
                byTaskType,
                decide(aggregate));
    }

    SpeechModalityOutcome classify(boolean transcriptHit, boolean acousticHit) {
        if (transcriptHit && acousticHit) {
            return SpeechModalityOutcome.BOTH_SUCCEED;
        }
        if (transcriptHit) {
            return SpeechModalityOutcome.TRANSCRIPT_ONLY;
        }
        if (acousticHit) {
            return SpeechModalityOutcome.ACOUSTIC_ONLY;
        }
        return SpeechModalityOutcome.BOTH_FAIL;
    }

    double topKJaccard(
            List<SpeechModalityRankedResult> transcriptResults,
            List<SpeechModalityRankedResult> acousticResults
    ) {
        Set<String> transcriptIds = transcriptResults.stream()
                .map(SpeechModalityRankedResult::sampleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> acousticIds = acousticResults.stream()
                .map(SpeechModalityRankedResult::sampleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> union = new HashSet<>(transcriptIds);
        union.addAll(acousticIds);
        if (union.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(transcriptIds);
        intersection.retainAll(acousticIds);
        return (double) intersection.size() / union.size();
    }

    SpeechModalitySummary summarize(List<SpeechModalityComparisonQueryResult> results) {
        if (results.isEmpty()) {
            return new SpeechModalitySummary(
                    new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0),
                    new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0),
                    Map.of(), 0.0, 0.0);
        }
        Map<SpeechModalityOutcome, Integer> outcomeCounts = new EnumMap<>(SpeechModalityOutcome.class);
        for (SpeechModalityOutcome outcome : SpeechModalityOutcome.values()) {
            outcomeCounts.put(outcome, 0);
        }
        double transcriptPrecision = 0.0;
        double transcriptRecall = 0.0;
        double transcriptHit = 0.0;
        double transcriptRr = 0.0;
        double acousticPrecision = 0.0;
        double acousticRecall = 0.0;
        double acousticHit = 0.0;
        double acousticRr = 0.0;
        double outcomeAgreement = 0.0;
        double jaccard = 0.0;
        for (SpeechModalityComparisonQueryResult result : results) {
            SpeechModalityQueryMetrics transcript = result.transcript().metrics();
            SpeechModalityQueryMetrics acoustic = result.acoustic().metrics();
            transcriptPrecision += transcript.precisionAtK();
            transcriptRecall += transcript.recallAtK();
            transcriptHit += transcript.hitAtK() ? 1.0 : 0.0;
            transcriptRr += transcript.reciprocalRank();
            acousticPrecision += acoustic.precisionAtK();
            acousticRecall += acoustic.recallAtK();
            acousticHit += acoustic.hitAtK() ? 1.0 : 0.0;
            acousticRr += acoustic.reciprocalRank();
            if (transcript.hitAtK() == acoustic.hitAtK()) {
                outcomeAgreement += 1.0;
            }
            jaccard += result.topKJaccard();
            outcomeCounts.merge(result.outcome(), 1, Integer::sum);
        }
        int count = results.size();
        return new SpeechModalitySummary(
                new SpeechEvaluationMetrics(
                        transcriptPrecision / count,
                        transcriptRecall / count,
                        transcriptHit / count,
                        transcriptRr / count,
                        count),
                new SpeechEvaluationMetrics(
                        acousticPrecision / count,
                        acousticRecall / count,
                        acousticHit / count,
                        acousticRr / count,
                        count),
                outcomeCounts,
                outcomeAgreement / count,
                jaccard / count);
    }

    private Map<SpeechCondition, SpeechModalitySummary> summarizeByCondition(
            List<SpeechModalityComparisonQueryResult> results
    ) {
        Map<SpeechCondition, List<SpeechModalityComparisonQueryResult>> grouped = new EnumMap<>(SpeechCondition.class);
        for (SpeechModalityComparisonQueryResult result : results) {
            SpeechCondition condition = result.query().condition();
            if (condition != null) {
                grouped.computeIfAbsent(condition, ignored -> new ArrayList<>()).add(result);
            }
        }
        Map<SpeechCondition, SpeechModalitySummary> summaries = new EnumMap<>(SpeechCondition.class);
        for (Map.Entry<SpeechCondition, List<SpeechModalityComparisonQueryResult>> entry : grouped.entrySet()) {
            summaries.put(entry.getKey(), summarize(entry.getValue()));
        }
        return summaries;
    }

    private Map<SpeechTaskType, SpeechModalitySummary> summarizeByTaskType(
            List<SpeechModalityComparisonQueryResult> results
    ) {
        Map<SpeechTaskType, List<SpeechModalityComparisonQueryResult>> grouped = new EnumMap<>(SpeechTaskType.class);
        for (SpeechModalityComparisonQueryResult result : results) {
            SpeechTaskType taskType = result.query().taskType();
            if (taskType != null) {
                grouped.computeIfAbsent(taskType, ignored -> new ArrayList<>()).add(result);
            }
        }
        Map<SpeechTaskType, SpeechModalitySummary> summaries = new EnumMap<>(SpeechTaskType.class);
        for (Map.Entry<SpeechTaskType, List<SpeechModalityComparisonQueryResult>> entry : grouped.entrySet()) {
            summaries.put(entry.getKey(), summarize(entry.getValue()));
        }
        return summaries;
    }

    SpeechModalityDecision decide(SpeechModalitySummary summary) {
        boolean transcriptOnly = summary.outcomeCount(SpeechModalityOutcome.TRANSCRIPT_ONLY) > 0;
        boolean acousticOnly = summary.outcomeCount(SpeechModalityOutcome.ACOUSTIC_ONLY) > 0;
        if (transcriptOnly && acousticOnly) {
            return SpeechModalityDecision.HYBRID_EXPERIMENT_JUSTIFIED;
        }
        if (!transcriptOnly && !acousticOnly) {
            return SpeechModalityDecision.NO_COMPLEMENTARITY_OBSERVED;
        }
        return SpeechModalityDecision.INCONCLUSIVE;
    }
}
