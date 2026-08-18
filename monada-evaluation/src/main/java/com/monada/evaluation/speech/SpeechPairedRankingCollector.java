package com.monada.evaluation.speech;

import com.monada.api.MonadaMemory;
import com.monada.core.ResonanceResult;
import com.monada.speech.bridge.SpeechSampleAtomMapper;
import com.monada.speech.domain.SpeechSample;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Collects complete, independently ranked transcript and acoustic results for paired speech queries. */
final class SpeechPairedRankingCollector {

    private static final double EVALUATION_THRESHOLD = Double.NEGATIVE_INFINITY;

    SpeechPairedRankingSet collect(
            List<PairedSpeechQuery> queries,
            SpeechSampleRetriever acousticRetriever,
            SpeechSampleStore sampleStore,
            SpeechFeatureStore featureStore,
            Path transcriptMemoryPath
    ) throws IOException {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(acousticRetriever, "acousticRetriever");
        Objects.requireNonNull(sampleStore, "sampleStore");
        Objects.requireNonNull(featureStore, "featureStore");
        Objects.requireNonNull(transcriptMemoryPath, "transcriptMemoryPath");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must not be empty");
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

        List<SpeechPairedRanking> queryRankings = new ArrayList<>(orderedQueries.size());
        for (PairedSpeechQuery query : orderedQueries) {
            queryRankings.add(new SpeechPairedRanking(
                    query,
                    retrieveTranscript(query, transcriptMemory, sampleIdsByAtomId, transcriptAtomCount),
                    retrieveAcoustic(query, acousticRetriever, sampleStore, featureStore, samples.size())));
        }
        return new SpeechPairedRankingSet(
                samples.stream().map(SpeechSample::id).toList(),
                queryRankings);
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
                        "query " + query.queryId() + " references missing sample IDs: "
                                + missing.stream().sorted().toList());
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
}

record SpeechPairedRankingSet(
        List<String> corpusSampleIds,
        List<SpeechPairedRanking> queryRankings
) {
    SpeechPairedRankingSet {
        Objects.requireNonNull(corpusSampleIds, "corpusSampleIds");
        Objects.requireNonNull(queryRankings, "queryRankings");
        corpusSampleIds = List.copyOf(corpusSampleIds);
        queryRankings = List.copyOf(queryRankings);
        if (corpusSampleIds.isEmpty()) {
            throw new IllegalArgumentException("corpusSampleIds must not be empty");
        }
    }

    int corpusSize() {
        return corpusSampleIds.size();
    }
}

record SpeechPairedRanking(
        PairedSpeechQuery query,
        List<SpeechModalityRankedResult> transcriptRanking,
        List<SpeechModalityRankedResult> acousticRanking
) {
    SpeechPairedRanking {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(transcriptRanking, "transcriptRanking");
        Objects.requireNonNull(acousticRanking, "acousticRanking");
        transcriptRanking = List.copyOf(transcriptRanking);
        acousticRanking = List.copyOf(acousticRanking);
    }
}
