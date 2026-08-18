package com.monada.evaluation.speech;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import com.monada.evaluation.HitAtK;
import com.monada.evaluation.PrecisionAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;
import com.monada.speech.bridge.SpeechSampleAtomMapper;
import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import com.monada.speech.retrieval.SpeechRetrievalOptions;
import com.monada.speech.retrieval.SpeechRetrievalResult;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs transcript-only and acoustic-only retrieval independently over the same
 * paired query cases. The runner never combines modality scores.
 */
public final class SpeechModalityComparisonRunner {

    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;

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

        List<SpeechSample> samples = sampleStore.findAll().stream()
                .sorted(Comparator.comparing(SpeechSample::id))
                .toList();
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("sampleStore must contain at least one sample");
        }
        Map<String, SpeechSample> samplesById = indexSamples(samples);
        List<PairedSpeechQuery> orderedQueries = orderAndValidateQueries(queries, samplesById.keySet());

        validateFreshTranscriptMemoryPath(transcriptMemoryPath);
        MonadaMemory transcriptMemory = MonadaMemory.open(transcriptMemoryPath);
        Map<String, List<String>> sampleIdsByAtomId = seedTranscriptMemory(samples, transcriptMemory);
        int transcriptAtomCount = sampleIdsByAtomId.size();

        List<SpeechModalityComparisonQueryResult> results = new ArrayList<>(orderedQueries.size());
        for (PairedSpeechQuery query : orderedQueries) {
            List<SpeechModalityRankedResult> transcriptRanking = retrieveTranscript(
                    query, transcriptMemory, sampleIdsByAtomId, transcriptAtomCount);
            List<SpeechModalityRankedResult> acousticRanking = retrieveAcoustic(
                    query, acousticRetriever, sampleStore, featureStore, samples.size());
            SpeechModalityQueryResult transcript = evaluateRanking(transcriptRanking, query.relevantSampleIds(), k);
            SpeechModalityQueryResult acoustic = evaluateRanking(acousticRanking, query.relevantSampleIds(), k);
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
                samples.size(),
                k,
                results,
                aggregate,
                byCondition,
                byTaskType,
                decide(aggregate));
    }

    private void validateFreshTranscriptMemoryPath(Path transcriptMemoryPath) throws IOException {
        if (Files.notExists(transcriptMemoryPath)) {
            return;
        }
        if (!Files.isDirectory(transcriptMemoryPath)) {
            throw new IllegalArgumentException(
                    "transcriptMemoryPath must be a nonexistent path or an empty directory: " + transcriptMemoryPath);
        }
        try (var entries = Files.list(transcriptMemoryPath)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException(
                        "transcriptMemoryPath must be a nonexistent path or an empty directory: " + transcriptMemoryPath);
            }
        }
    }

    private Map<String, SpeechSample> indexSamples(List<SpeechSample> samples) {
        Map<String, SpeechSample> samplesById = new LinkedHashMap<>();
        for (SpeechSample sample : samples) {
            SpeechSample prior = samplesById.putIfAbsent(sample.id(), sample);
            if (prior != null) {
                throw new IllegalArgumentException("duplicate sample id: " + sample.id());
            }
        }
        return samplesById;
    }

    private List<PairedSpeechQuery> orderAndValidateQueries(
            List<PairedSpeechQuery> queries,
            Set<String> availableSampleIds
    ) {
        Set<String> queryIds = new HashSet<>();
        List<PairedSpeechQuery> ordered = new ArrayList<>(queries);
        for (PairedSpeechQuery query : ordered) {
            Objects.requireNonNull(query, "query");
        }
        ordered.sort(Comparator.comparing(PairedSpeechQuery::queryId));
        for (PairedSpeechQuery query : ordered) {
            if (!queryIds.add(query.queryId())) {
                throw new IllegalArgumentException("duplicate query id: " + query.queryId());
            }
            if (!Files.isRegularFile(query.queryAudio())) {
                throw new IllegalArgumentException("query audio does not exist or is not a file: " + query.queryAudio());
            }
            Set<String> missing = new LinkedHashSet<>(query.relevantSampleIds());
            missing.removeAll(availableSampleIds);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "query " + query.queryId() + " references missing sample IDs: " + missing.stream().sorted().toList());
            }
        }
        return List.copyOf(ordered);
    }

    private Map<String, List<String>> seedTranscriptMemory(
            List<SpeechSample> samples,
            MonadaMemory transcriptMemory
    ) {
        Map<String, List<String>> mutableSampleIdsByAtomId = new HashMap<>();
        for (SpeechSample sample : samples) {
            var transcriptAtom = SpeechSampleAtomMapper.toTranscriptAtom(sample);
            var storedAtom = transcriptMemory.remember(transcriptAtom.content(), transcriptAtom.aliases());
            mutableSampleIdsByAtomId.computeIfAbsent(storedAtom.id(), ignored -> new ArrayList<>()).add(sample.id());
        }
        Map<String, List<String>> sampleIdsByAtomId = new LinkedHashMap<>();
        mutableSampleIdsByAtomId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<String> sampleIds = entry.getValue().stream().sorted().toList();
                    sampleIdsByAtomId.put(entry.getKey(), sampleIds);
                });
        return Map.copyOf(sampleIdsByAtomId);
    }

    private List<SpeechModalityRankedResult> retrieveTranscript(
            PairedSpeechQuery query,
            MonadaMemory transcriptMemory,
            Map<String, List<String>> sampleIdsByAtomId,
            int transcriptAtomCount
    ) {
        List<ResonanceResult> atomResults = transcriptMemory.resonate(query.transcript())
                .topK(transcriptAtomCount)
                .threshold(EVALUATION_THRESHOLD)
                .execute()
                .results();
        List<SpeechModalityRankedResult> expanded = new ArrayList<>();
        for (ResonanceResult atomResult : atomResults) {
            List<String> sampleIds = sampleIdsByAtomId.get(atomResult.atom().id());
            if (sampleIds == null) {
                throw new IllegalStateException("ranked transcript atom was not seeded: " + atomResult.atom().id());
            }
            for (String sampleId : sampleIds) {
                expanded.add(new SpeechModalityRankedResult(sampleId, atomResult.score(), expanded.size() + 1));
            }
        }
        return List.copyOf(expanded);
    }

    private List<SpeechModalityRankedResult> retrieveAcoustic(
            PairedSpeechQuery query,
            SpeechSampleRetriever acousticRetriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            int corpusSize
    ) throws IOException {
        List<SpeechRetrievalResult> results = acousticRetriever.search(
                query.queryAudio(),
                sampleStore,
                featureStore,
                new SpeechRetrievalOptions(corpusSize, null, null, null, null, null));
        return results.stream()
                .map(result -> new SpeechModalityRankedResult(
                        result.sample().id(), result.score(), result.rank()))
                .toList();
    }

    private SpeechModalityQueryResult evaluateRanking(
            List<SpeechModalityRankedResult> ranking,
            Set<String> relevantSampleIds,
            int k
    ) {
        List<SpeechModalityRankedResult> topResults = ranking.subList(0, Math.min(k, ranking.size()));
        List<String> rankedSampleIds = ranking.stream().map(SpeechModalityRankedResult::sampleId).toList();
        List<String> topSampleIds = topResults.stream().map(SpeechModalityRankedResult::sampleId).toList();
        int relevantRetrievedCount = (int) topSampleIds.stream()
                .filter(relevantSampleIds::contains)
                .count();
        int firstRelevantRank = firstRelevantRank(ranking, relevantSampleIds);
        double reciprocalRank = ReciprocalRank.compute(relevantSampleIds, rankedSampleIds);
        SpeechModalityQueryMetrics metrics = new SpeechModalityQueryMetrics(
                topResults.size(),
                relevantRetrievedCount,
                HitAtK.compute(relevantSampleIds, rankedSampleIds, k) > 0.0,
                PrecisionAtK.compute(relevantSampleIds, rankedSampleIds, k),
                RecallAtK.compute(relevantSampleIds, rankedSampleIds, k),
                reciprocalRank,
                firstRelevantRank);
        return new SpeechModalityQueryResult(topResults, metrics);
    }

    private int firstRelevantRank(List<SpeechModalityRankedResult> ranking, Set<String> relevantSampleIds) {
        for (SpeechModalityRankedResult result : ranking) {
            if (relevantSampleIds.contains(result.sampleId())) {
                return result.rank();
            }
        }
        return 0;
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
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> acousticIds = acousticResults.stream()
                .map(SpeechModalityRankedResult::sampleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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
