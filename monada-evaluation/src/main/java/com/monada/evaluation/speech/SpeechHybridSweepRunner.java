package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;
import com.monada.speech.retrieval.SpeechSampleRetriever;
import com.monada.speech.storage.SpeechFeatureStore;
import com.monada.speech.storage.SpeechSampleStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates a fixed, global transcript/acoustic score-fusion sweep without changing either retriever.
 */
public final class SpeechHybridSweepRunner {

    private static final double COMPARISON_TOLERANCE = 1.0e-12;

    private final SpeechPairedRankingCollector rankingCollector = new SpeechPairedRankingCollector();
    private final SpeechRankingEvaluator rankingEvaluator = new SpeechRankingEvaluator();
    private final SpeechScoreNormalization scoreNormalization = new SpeechScoreNormalization();

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
                .map(pairedRanking -> createContext(pairedRanking, collected.corpusSampleIds(), k))
                .toList();
        List<SpeechHybridProfileResult> profiles = SpeechFusionWeights.defaultSweep().stream()
                .map(weights -> evaluateProfile(weights, contexts))
                .toList();
        return new SpeechHybridSweepReport(
                evidence,
                label,
                collected.corpusSize(),
                k,
                profiles,
                decide(profiles));
    }

    private SpeechHybridQueryContext createContext(
            SpeechPairedRanking pairedRanking,
            List<String> corpusSampleIds,
            int k
    ) {
        PairedSpeechQuery query = pairedRanking.query();
        SpeechModalityQueryResult transcriptControl = rankingEvaluator.evaluate(
                pairedRanking.transcriptRanking(), query.relevantSampleIds(), k);
        SpeechModalityQueryResult acousticControl = rankingEvaluator.evaluate(
                pairedRanking.acousticRanking(), query.relevantSampleIds(), k);
        Map<String, Double> transcriptScores = indexScores(pairedRanking.transcriptRanking(), "transcript");
        Map<String, Double> acousticScores = indexScores(pairedRanking.acousticRanking(), "acoustic");
        validateKnownCandidates(transcriptScores.keySet(), corpusSampleIds, "transcript");
        validateKnownCandidates(acousticScores.keySet(), corpusSampleIds, "acoustic");

        List<SpeechHybridCandidate> candidates = corpusSampleIds.stream()
                .sorted()
                .map(sampleId -> new SpeechHybridCandidate(
                        sampleId,
                        transcriptScores.get(sampleId),
                        acousticScores.get(sampleId),
                        availabilityFor(transcriptScores.containsKey(sampleId), acousticScores.containsKey(sampleId))))
                .toList();
        return new SpeechHybridQueryContext(
                query,
                pairedRanking.transcriptRanking(),
                pairedRanking.acousticRanking(),
                transcriptControl,
                acousticControl,
                classify(transcriptControl.metrics().hitAtK(), acousticControl.metrics().hitAtK()),
                scoreNormalization.normalize(transcriptScores),
                scoreNormalization.normalize(acousticScores),
                k,
                candidates);
    }

    private Map<String, Double> indexScores(List<SpeechModalityRankedResult> ranking, String modality) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (SpeechModalityRankedResult result : ranking) {
            Double prior = scores.putIfAbsent(result.sampleId(), result.score());
            if (prior != null) {
                throw new IllegalArgumentException(
                        "duplicate " + modality + " candidate sampleId: " + result.sampleId());
            }
        }
        return scores;
    }

    private void validateKnownCandidates(Set<String> rankedSampleIds, List<String> corpusSampleIds, String modality) {
        Set<String> unknown = new TreeSet<>(rankedSampleIds);
        unknown.removeAll(corpusSampleIds);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    modality + " ranking contains sample IDs absent from the corpus: " + unknown);
        }
    }

    private SpeechHybridCandidateAvailability availabilityFor(boolean transcriptPresent, boolean acousticPresent) {
        if (transcriptPresent && acousticPresent) {
            return SpeechHybridCandidateAvailability.BOTH_AVAILABLE;
        }
        if (transcriptPresent) {
            return SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING;
        }
        if (acousticPresent) {
            return SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING;
        }
        return SpeechHybridCandidateAvailability.BOTH_MISSING;
    }

    private SpeechHybridProfileResult evaluateProfile(
            SpeechFusionWeights weights,
            List<SpeechHybridQueryContext> contexts
    ) {
        List<SpeechHybridProfileQueryResult> queryResults = contexts.stream()
                .map(context -> evaluateProfileQuery(weights, context))
                .toList();
        SpeechHybridProfileSummary summary = summarize(queryResults);
        return new SpeechHybridProfileResult(weights, queryResults, summary);
    }

    SpeechHybridProfileQueryResult evaluateProfileQuery(
            SpeechFusionWeights weights,
            SpeechHybridQueryContext context
    ) {
        List<SpeechModalityRankedResult> ranking;
        Map<String, SpeechHybridScoreBreakdown> breakdowns;
        int tieCount;
        if (weights.isTranscriptControl()) {
            ranking = context.transcriptRanking();
            breakdowns = controlBreakdowns(context, true);
            tieCount = countTies(ranking);
        } else if (weights.isAcousticControl()) {
            ranking = context.acousticRanking();
            breakdowns = controlBreakdowns(context, false);
            tieCount = countTies(ranking);
        } else {
            FusionExecution execution = fuse(weights, context);
            ranking = execution.ranking();
            breakdowns = execution.breakdowns();
            tieCount = execution.tieCount();
        }
        SpeechModalityQueryResult result = rankingEvaluator.evaluate(
                ranking, context.query().relevantSampleIds(), context.k());
        return new SpeechHybridProfileQueryResult(
                context,
                weights,
                result,
                breakdowns,
                tieCount,
                compare(result.metrics(), context.transcriptControl().metrics()),
                compare(result.metrics(), context.acousticControl().metrics()),
                classifyDisagreement(context.controlOutcome(), result.metrics().hitAtK()));
    }

    private Map<String, SpeechHybridScoreBreakdown> controlBreakdowns(
            SpeechHybridQueryContext context,
            boolean transcriptControl
    ) {
        Map<String, SpeechHybridScoreBreakdown> breakdowns = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            Double rawScore = transcriptControl ? candidate.transcriptRawScore() : candidate.acousticRawScore();
            Double normalizedTranscript = context.transcriptNormalization().normalizedScoresBySampleId()
                    .get(candidate.sampleId());
            Double normalizedAcoustic = context.acousticNormalization().normalizedScoresBySampleId()
                    .get(candidate.sampleId());
            breakdowns.put(candidate.sampleId(), new SpeechHybridScoreBreakdown(
                    candidate.sampleId(), normalizedTranscript, normalizedAcoustic, rawScore));
        }
        return Map.copyOf(breakdowns);
    }

    private FusionExecution fuse(SpeechFusionWeights weights, SpeechHybridQueryContext context) {
        List<SpeechModalityRankedResult> unranked = new ArrayList<>();
        Map<String, SpeechHybridScoreBreakdown> breakdowns = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            Double normalizedTranscript = context.transcriptNormalization().normalizedScoresBySampleId()
                    .get(candidate.sampleId());
            Double normalizedAcoustic = context.acousticNormalization().normalizedScoresBySampleId()
                    .get(candidate.sampleId());
            Double fusedScore = null;
            if (candidate.availability() == SpeechHybridCandidateAvailability.BOTH_AVAILABLE) {
                fusedScore = weights.transcriptWeight() * normalizedTranscript
                        + weights.acousticWeight() * normalizedAcoustic;
                unranked.add(new SpeechModalityRankedResult(candidate.sampleId(), fusedScore, 1));
            }
            breakdowns.put(candidate.sampleId(), new SpeechHybridScoreBreakdown(
                    candidate.sampleId(), normalizedTranscript, normalizedAcoustic, fusedScore));
        }
        unranked.sort(Comparator.comparingDouble(SpeechModalityRankedResult::score)
                .reversed()
                .thenComparing(SpeechModalityRankedResult::sampleId));
        List<SpeechModalityRankedResult> ranking = new ArrayList<>(unranked.size());
        for (int index = 0; index < unranked.size(); index++) {
            SpeechModalityRankedResult result = unranked.get(index);
            ranking.add(new SpeechModalityRankedResult(result.sampleId(), result.score(), index + 1));
        }
        return new FusionExecution(List.copyOf(ranking), Map.copyOf(breakdowns), countTies(ranking));
    }

    private int countTies(List<SpeechModalityRankedResult> ranking) {
        int tieCount = 0;
        for (int index = 1; index < ranking.size(); index++) {
            if (Double.compare(ranking.get(index - 1).score(), ranking.get(index).score()) == 0) {
                tieCount++;
            }
        }
        return tieCount;
    }

    private SpeechHybridComparison compare(SpeechModalityQueryMetrics candidate, SpeechModalityQueryMetrics control) {
        int recallComparison = compareDouble(candidate.recallAtK(), control.recallAtK());
        if (recallComparison > 0) {
            return SpeechHybridComparison.WIN;
        }
        if (recallComparison < 0) {
            return SpeechHybridComparison.REGRESS;
        }
        int reciprocalRankComparison = compareDouble(candidate.reciprocalRank(), control.reciprocalRank());
        if (reciprocalRankComparison > 0) {
            return SpeechHybridComparison.WIN;
        }
        if (reciprocalRankComparison < 0) {
            return SpeechHybridComparison.REGRESS;
        }
        return SpeechHybridComparison.MAINTAIN;
    }

    private int compareDouble(double left, double right) {
        if (Math.abs(left - right) <= COMPARISON_TOLERANCE) {
            return 0;
        }
        return Double.compare(left, right);
    }

    private SpeechModalityOutcome classify(boolean transcriptHit, boolean acousticHit) {
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

    private SpeechHybridDisagreementOutcome classifyDisagreement(
            SpeechModalityOutcome controlOutcome,
            boolean hybridHit
    ) {
        return switch (controlOutcome) {
            case BOTH_SUCCEED -> hybridHit
                    ? SpeechHybridDisagreementOutcome.BOTH_SUCCEED_RETAINED
                    : SpeechHybridDisagreementOutcome.LOST_BOTH_SUCCEED;
            case TRANSCRIPT_ONLY -> hybridHit
                    ? SpeechHybridDisagreementOutcome.RECOVERED_ACOUSTIC_MISS
                    : SpeechHybridDisagreementOutcome.LOST_TRANSCRIPT_WIN;
            case ACOUSTIC_ONLY -> hybridHit
                    ? SpeechHybridDisagreementOutcome.RECOVERED_TRANSCRIPT_MISS
                    : SpeechHybridDisagreementOutcome.LOST_ACOUSTIC_WIN;
            case BOTH_FAIL -> hybridHit
                    ? SpeechHybridDisagreementOutcome.RECOVERED_BOTH_MISS
                    : SpeechHybridDisagreementOutcome.BOTH_MISS_RETAINED;
        };
    }

    private SpeechHybridProfileSummary summarize(List<SpeechHybridProfileQueryResult> queryResults) {
        double precision = 0.0;
        double recall = 0.0;
        double hit = 0.0;
        double reciprocalRank = 0.0;
        double transcriptPrecision = 0.0;
        double transcriptRecall = 0.0;
        double transcriptHit = 0.0;
        double transcriptRr = 0.0;
        double acousticPrecision = 0.0;
        double acousticRecall = 0.0;
        double acousticHit = 0.0;
        double acousticRr = 0.0;
        Map<SpeechHybridComparison, Integer> transcriptComparisons = emptyComparisonCounts();
        Map<SpeechHybridComparison, Integer> acousticComparisons = emptyComparisonCounts();
        Map<SpeechHybridDisagreementOutcome, Integer> disagreementCounts = emptyDisagreementCounts();
        int controlHitLosses = 0;
        for (SpeechHybridProfileQueryResult queryResult : queryResults) {
            SpeechModalityQueryMetrics metrics = queryResult.result().metrics();
            SpeechModalityQueryMetrics transcript = queryResult.context().transcriptControl().metrics();
            SpeechModalityQueryMetrics acoustic = queryResult.context().acousticControl().metrics();
            precision += metrics.precisionAtK();
            recall += metrics.recallAtK();
            hit += metrics.hitAtK() ? 1.0 : 0.0;
            reciprocalRank += metrics.reciprocalRank();
            transcriptPrecision += transcript.precisionAtK();
            transcriptRecall += transcript.recallAtK();
            transcriptHit += transcript.hitAtK() ? 1.0 : 0.0;
            transcriptRr += transcript.reciprocalRank();
            acousticPrecision += acoustic.precisionAtK();
            acousticRecall += acoustic.recallAtK();
            acousticHit += acoustic.hitAtK() ? 1.0 : 0.0;
            acousticRr += acoustic.reciprocalRank();
            transcriptComparisons.merge(queryResult.vsTranscript(), 1, Integer::sum);
            acousticComparisons.merge(queryResult.vsAcoustic(), 1, Integer::sum);
            disagreementCounts.merge(queryResult.disagreementOutcome(), 1, Integer::sum);
            if (queryResult.context().controlOutcome() != SpeechModalityOutcome.BOTH_FAIL
                    && !metrics.hitAtK()) {
                controlHitLosses++;
            }
        }
        int count = queryResults.size();
        SpeechEvaluationMetrics metrics = new SpeechEvaluationMetrics(
                precision / count, recall / count, hit / count, reciprocalRank / count, count);
        SpeechEvaluationMetrics transcriptMetrics = new SpeechEvaluationMetrics(
                transcriptPrecision / count,
                transcriptRecall / count,
                transcriptHit / count,
                transcriptRr / count,
                count);
        SpeechEvaluationMetrics acousticMetrics = new SpeechEvaluationMetrics(
                acousticPrecision / count,
                acousticRecall / count,
                acousticHit / count,
                acousticRr / count,
                count);
        return new SpeechHybridProfileSummary(
                metrics,
                SpeechMetricDelta.between(metrics, transcriptMetrics),
                SpeechMetricDelta.between(metrics, acousticMetrics),
                transcriptComparisons,
                acousticComparisons,
                disagreementCounts,
                controlHitLosses,
                isPromising(metrics, transcriptMetrics, acousticMetrics, controlHitLosses));
    }

    private Map<SpeechHybridComparison, Integer> emptyComparisonCounts() {
        Map<SpeechHybridComparison, Integer> counts = new EnumMap<>(SpeechHybridComparison.class);
        for (SpeechHybridComparison comparison : SpeechHybridComparison.values()) {
            counts.put(comparison, 0);
        }
        return counts;
    }

    private Map<SpeechHybridDisagreementOutcome, Integer> emptyDisagreementCounts() {
        Map<SpeechHybridDisagreementOutcome, Integer> counts = new EnumMap<>(SpeechHybridDisagreementOutcome.class);
        for (SpeechHybridDisagreementOutcome outcome : SpeechHybridDisagreementOutcome.values()) {
            counts.put(outcome, 0);
        }
        return counts;
    }

    private boolean isPromising(
            SpeechEvaluationMetrics metrics,
            SpeechEvaluationMetrics transcript,
            SpeechEvaluationMetrics acoustic,
            int controlHitLosses
    ) {
        return controlHitLosses == 0
                && notBelowBoth(metrics.precisionAtK(), transcript.precisionAtK(), acoustic.precisionAtK())
                && notBelowBoth(metrics.recallAtK(), transcript.recallAtK(), acoustic.recallAtK())
                && notBelowBoth(metrics.hitRateAtK(), transcript.hitRateAtK(), acoustic.hitRateAtK())
                && notBelowBoth(metrics.mrr(), transcript.mrr(), acoustic.mrr())
                && (strictlyAboveBoth(metrics.recallAtK(), transcript.recallAtK(), acoustic.recallAtK())
                || strictlyAboveBoth(metrics.hitRateAtK(), transcript.hitRateAtK(), acoustic.hitRateAtK())
                || strictlyAboveBoth(metrics.mrr(), transcript.mrr(), acoustic.mrr()));
    }

    private boolean notBelowBoth(double candidate, double transcript, double acoustic) {
        return candidate + COMPARISON_TOLERANCE >= transcript
                && candidate + COMPARISON_TOLERANCE >= acoustic;
    }

    private boolean strictlyAboveBoth(double candidate, double transcript, double acoustic) {
        return candidate > transcript + COMPARISON_TOLERANCE
                && candidate > acoustic + COMPARISON_TOLERANCE;
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

    private record FusionExecution(
            List<SpeechModalityRankedResult> ranking,
            Map<String, SpeechHybridScoreBreakdown> breakdowns,
            int tieCount
    ) {
    }
}
