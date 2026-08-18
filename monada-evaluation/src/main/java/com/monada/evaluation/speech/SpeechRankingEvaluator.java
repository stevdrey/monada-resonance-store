package com.monada.evaluation.speech;

import com.monada.evaluation.HitAtK;
import com.monada.evaluation.PrecisionAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Evaluates a deterministic speech-sample ranking against one relevance set. */
final class SpeechRankingEvaluator {

    SpeechModalityQueryResult evaluate(
            List<SpeechModalityRankedResult> ranking,
            Set<String> relevantSampleIds,
            int k
    ) {
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(relevantSampleIds, "relevantSampleIds");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        List<SpeechModalityRankedResult> topResults = ranking.subList(0, Math.min(k, ranking.size()));
        List<String> rankedSampleIds = ranking.stream().map(SpeechModalityRankedResult::sampleId).toList();
        List<String> topSampleIds = topResults.stream().map(SpeechModalityRankedResult::sampleId).toList();
        int relevantRetrievedCount = (int) topSampleIds.stream()
                .filter(relevantSampleIds::contains)
                .count();
        int firstRelevantRank = firstRelevantRank(ranking, relevantSampleIds);
        double reciprocalRank = ReciprocalRank.compute(relevantSampleIds, rankedSampleIds);
        return new SpeechModalityQueryResult(topResults, new SpeechModalityQueryMetrics(
                topResults.size(),
                relevantRetrievedCount,
                HitAtK.compute(relevantSampleIds, rankedSampleIds, k) > 0.0,
                PrecisionAtK.compute(relevantSampleIds, rankedSampleIds, k),
                RecallAtK.compute(relevantSampleIds, rankedSampleIds, k),
                reciprocalRank,
                firstRelevantRank));
    }

    private int firstRelevantRank(List<SpeechModalityRankedResult> ranking, Set<String> relevantSampleIds) {
        for (SpeechModalityRankedResult result : ranking) {
            if (relevantSampleIds.contains(result.sampleId())) {
                return result.rank();
            }
        }
        return 0;
    }
}
