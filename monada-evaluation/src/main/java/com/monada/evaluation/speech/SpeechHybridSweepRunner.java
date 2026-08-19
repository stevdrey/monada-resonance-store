package com.monada.evaluation.speech;

import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Evaluates a fixed global transcript/acoustic score-fusion sweep without changing either retriever. */
public final class SpeechHybridSweepRunner {

    private final SpeechPairedRankingCollector rankingCollector = new SpeechPairedRankingCollector();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();

    public SpeechHybridSweepReport run(
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
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        SpeechPairedRankingSet collected = rankingCollector.collect(
                queries, acousticRetriever, sampleStore, featureStore, transcriptMemoryPath);
        List<SpeechHybridQueryContext> contexts = collected.queryRankings().stream()
                .map(ranking -> profileEvaluator.createContext(ranking, collected.corpusSampleIds(), k))
                .toList();
        List<SpeechHybridProfileResult> profiles = SpeechFusionWeights.defaultSweep().stream()
                .map(weights -> profileEvaluator.evaluateProfile(weights, contexts))
                .toList();
        return new SpeechHybridSweepReport(evidence, label, collected.corpusSize(), k, profiles, decide(profiles));
    }

    SpeechHybridProfileQueryResult evaluateProfileQuery(SpeechFusionWeights weights, SpeechHybridQueryContext context) {
        return profileEvaluator.evaluateProfileQuery(weights, context);
    }

    SpeechHybridDecision decide(List<SpeechHybridProfileResult> profiles) {
        List<SpeechHybridProfileResult> hybridProfiles = profiles.stream()
                .filter(profile -> profile.weights().isHybrid())
                .toList();
        for (int index = 1; index < hybridProfiles.size(); index++) {
            if (hybridProfiles.get(index - 1).summary().promising()
                    && hybridProfiles.get(index).summary().promising()) {
                return SpeechHybridDecision.HYBRID_ROBUSTNESS_STUDY_JUSTIFIED;
            }
        }
        if (hybridProfiles.stream().noneMatch(profile -> profile.summary().promising())) {
            return SpeechHybridDecision.KEEP_SINGLE_MODALITY;
        }
        return SpeechHybridDecision.INCONCLUSIVE;
    }
}
