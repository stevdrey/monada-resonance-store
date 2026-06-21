package com.monada.speech.evaluation;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechSample;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.retrieval.SpeechRetrievalResult;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluates ranked speech retrieval results against explicit relevance sets.
 *
 * <p>The evaluator delegates actual retrieval to {@link SpeechSampleRetriever} and only
 * computes metrics, producing deterministic aggregate and per-query reports.
 *
 * <p><b>Performance note (MVP):</b> {@link SpeechSampleRetriever#search} reloads all samples
 * and feature vectors from the backing stores on every query call, resulting in
 * O(#queries &times; #samples) disk reads with file-backed stores. This is acceptable for
 * small evaluation sets but should be addressed (e.g. by pre-loading stores once) before
 * using this evaluator with large corpora.
 */
public final class SpeechRetrievalEvaluator {

    /**
     * Evaluates a list of queries and produces a report.
     *
     * @param queries      queries to evaluate (must be non-empty)
     * @param retriever    retriever to rank candidates
     * @param sampleStore  source of speech sample metadata
     * @param featureStore source of acoustic feature vectors
     * @param options      evaluation options
     * @return a deterministic evaluation report
     * @throws IOException    if a query audio file cannot be encoded
     * @throws NullPointerException if a required parameter is null
     * @throws IllegalArgumentException if query list is empty, a query is invalid, or k is invalid
     */
    public SpeechEvaluationReport evaluate(
            List<SpeechEvaluationQuery> queries,
            SpeechSampleRetriever retriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            SpeechEvaluationOptions options
    ) throws IOException {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(retriever, "retriever");
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");
        Objects.requireNonNull(options, "options");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must not be empty");
        }

        int k = options.k();
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }

        List<SpeechSample> allSamples = sampleStore.findAll();
        Map<Path, SpeechSample> samplesByPath = allSamples.stream()
                .collect(Collectors.toMap(
                        SpeechSample::audioPath,
                        Function.identity(),
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));

        Map<SpeechCondition, List<SpeechQueryEvaluationResult>> resultsByCondition = new EnumMap<>(SpeechCondition.class);
        Map<SpeechTaskType, List<SpeechQueryEvaluationResult>> resultsByTaskType = new EnumMap<>(SpeechTaskType.class);
        List<SpeechQueryEvaluationResult> queryResults = new ArrayList<>();

        for (SpeechEvaluationQuery query : queries) {
            validateQuery(query);
            List<SpeechRetrievalResult> retrieved = retriever.search(
                    query.queryAudio(),
                    sampleStore,
                    featureStore,
                    query.retrievalOptions()
            );

            List<SpeechRetrievalResult> topK = retrieved.subList(0, Math.min(retrieved.size(), k));
            List<String> retrievedIds = topK.stream()
                    .map(r -> r.sample().id())
                    .toList();
            Set<String> relevantIds = query.relevantSampleIds();

            int relevantRetrievedCount = 0;
            for (int i = 0; i < topK.size(); i++) {
                if (relevantIds.contains(topK.get(i).sample().id())) {
                    relevantRetrievedCount++;
                }
            }

            // MRR is computed over the full retrieved list so that a relevant result
            // just beyond the evaluation k is still counted (RR > 0).
            int firstRelevantRank = 0;
            for (int i = 0; i < retrieved.size(); i++) {
                if (relevantIds.contains(retrieved.get(i).sample().id())) {
                    firstRelevantRank = i + 1;
                    break;
                }
            }

            double reciprocalRank = firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank;
            Set<String> retrievedIdSet = new HashSet<>(retrievedIds);
            List<String> missedRelevantIds = relevantIds.stream()
                    .filter(id -> !retrievedIdSet.contains(id))
                    .sorted()
                    .toList();

            boolean hit = relevantRetrievedCount > 0;
            double precision = topK.isEmpty() ? 0.0 : (double) relevantRetrievedCount / topK.size();
            double recall = (double) relevantRetrievedCount / relevantIds.size();

            String topResultSampleId = topK.isEmpty() ? null : topK.get(0).sample().id();
            double topResultScore = topK.isEmpty() ? 0.0 : topK.get(0).score();

            SpeechQueryEvaluationResult queryResult = new SpeechQueryEvaluationResult(
                    query.queryId(),
                    topK.size(),
                    relevantRetrievedCount,
                    hit,
                    precision,
                    recall,
                    reciprocalRank,
                    retrievedIds,
                    missedRelevantIds,
                    topResultSampleId,
                    topResultScore
            );

            queryResults.add(queryResult);
            SpeechSample querySample = samplesByPath.get(query.queryAudio());
            SpeechCondition condition = querySample != null ? querySample.condition() : SpeechCondition.UNKNOWN;
            SpeechTaskType taskType = querySample != null ? querySample.taskType() : SpeechTaskType.UNKNOWN;
            resultsByCondition.computeIfAbsent(condition, c -> new ArrayList<>()).add(queryResult);
            resultsByTaskType.computeIfAbsent(taskType, t -> new ArrayList<>()).add(queryResult);
        }

        SpeechEvaluationMetrics aggregate = computeMetrics(queryResults);
        Map<SpeechCondition, SpeechEvaluationMetrics> metricsByCondition = new EnumMap<>(SpeechCondition.class);
        for (Map.Entry<SpeechCondition, List<SpeechQueryEvaluationResult>> entry : resultsByCondition.entrySet()) {
            metricsByCondition.put(entry.getKey(), computeMetrics(entry.getValue()));
        }
        Map<SpeechTaskType, SpeechEvaluationMetrics> metricsByTaskType = new EnumMap<>(SpeechTaskType.class);
        for (Map.Entry<SpeechTaskType, List<SpeechQueryEvaluationResult>> entry : resultsByTaskType.entrySet()) {
            metricsByTaskType.put(entry.getKey(), computeMetrics(entry.getValue()));
        }

        List<SpeechQueryEvaluationResult> reportedResults = options.includePerQueryDiagnostics()
                ? List.copyOf(queryResults)
                : List.of();

        return new SpeechEvaluationReport(
                queryResults.size(),
                aggregate.precisionAtK(),
                aggregate.recallAtK(),
                aggregate.hitRateAtK(),
                aggregate.mrr(),
                reportedResults,
                Map.copyOf(metricsByCondition),
                Map.copyOf(metricsByTaskType)
        );
    }

    private void validateQuery(SpeechEvaluationQuery query) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(query.queryId(), "queryId");
        if (query.queryId().isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        Objects.requireNonNull(query.queryAudio(), "queryAudio");
        Objects.requireNonNull(query.relevantSampleIds(), "relevantSampleIds");
        if (query.relevantSampleIds().isEmpty()) {
            throw new IllegalArgumentException("relevantSampleIds must not be empty for query: " + query.queryId());
        }
        Objects.requireNonNull(query.retrievalOptions(), "retrievalOptions");
    }

    private SpeechEvaluationMetrics computeMetrics(List<SpeechQueryEvaluationResult> results) {
        if (results.isEmpty()) {
            return new SpeechEvaluationMetrics(0.0, 0.0, 0.0, 0.0, 0);
        }
        double precision = results.stream()
                .mapToDouble(SpeechQueryEvaluationResult::precisionAtK)
                .average()
                .orElse(0.0);
        double recall = results.stream()
                .mapToDouble(SpeechQueryEvaluationResult::recallAtK)
                .average()
                .orElse(0.0);
        double hitRate = results.stream()
                .mapToDouble(r -> r.hitAtK() ? 1.0 : 0.0)
                .average()
                .orElse(0.0);
        double mrr = results.stream()
                .mapToDouble(SpeechQueryEvaluationResult::reciprocalRank)
                .average()
                .orElse(0.0);
        return new SpeechEvaluationMetrics(precision, recall, hitRate, mrr, results.size());
    }
}
