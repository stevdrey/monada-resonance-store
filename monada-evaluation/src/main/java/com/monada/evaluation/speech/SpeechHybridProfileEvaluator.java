package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Shared evaluation-only mechanics for transcript/acoustic control and hybrid profiles. */
final class SpeechHybridProfileEvaluator {

    static final double COMPARISON_TOLERANCE = 1.0e-12;

    private final SpeechRankingEvaluator rankingEvaluator = new SpeechRankingEvaluator();
    private final SpeechScoreNormalization scoreNormalization = new SpeechScoreNormalization();

    SpeechHybridQueryContext createContext(
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
                transcriptControl, acousticControl,
                classify(transcriptControl.metrics().hitAtK(), acousticControl.metrics().hitAtK()),
                scoreNormalization.normalize(transcriptScores), scoreNormalization.normalize(acousticScores),
                k, candidates);
    }

    SpeechHybridProfileResult evaluateProfile(SpeechFusionWeights weights, List<SpeechHybridQueryContext> contexts) {
        List<SpeechHybridProfileQueryResult> queryResults = contexts.stream()
                .map(context -> evaluateProfileQuery(weights, context))
                .toList();
        return new SpeechHybridProfileResult(weights, queryResults, summarize(queryResults));
    }

    SpeechHybridProfileQueryResult evaluateProfileQuery(SpeechFusionWeights weights, SpeechHybridQueryContext context) {
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
                context, weights, result, breakdowns, tieCount,
                compare(result.metrics(), context.transcriptControl().metrics()),
                compare(result.metrics(), context.acousticControl().metrics()),
                classifyDisagreement(context.controlOutcome(), result.metrics().hitAtK()));
    }

    SpeechHybridComparison compare(SpeechModalityQueryMetrics candidate, SpeechModalityQueryMetrics control) {
        int recallComparison = compareDouble(candidate.recallAtK(), control.recallAtK());
        if (recallComparison > 0) return SpeechHybridComparison.WIN;
        if (recallComparison < 0) return SpeechHybridComparison.REGRESS;
        int reciprocalRankComparison = compareDouble(candidate.reciprocalRank(), control.reciprocalRank());
        if (reciprocalRankComparison > 0) return SpeechHybridComparison.WIN;
        if (reciprocalRankComparison < 0) return SpeechHybridComparison.REGRESS;
        return SpeechHybridComparison.MAINTAIN;
    }

    int compareDouble(double left, double right) {
        if (Math.abs(left - right) <= COMPARISON_TOLERANCE) return 0;
        return Double.compare(left, right);
    }

    private Map<String, Double> indexScores(List<SpeechModalityRankedResult> ranking, String modality) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (SpeechModalityRankedResult result : ranking) {
            if (scores.putIfAbsent(result.sampleId(), result.score()) != null) {
                throw new IllegalArgumentException("duplicate " + modality + " candidate sampleId: " + result.sampleId());
            }
        }
        return scores;
    }

    private void validateKnownCandidates(Set<String> rankedSampleIds, List<String> corpusSampleIds, String modality) {
        Set<String> unknown = new TreeSet<>(rankedSampleIds);
        unknown.removeAll(corpusSampleIds);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(modality + " ranking contains sample IDs absent from the corpus: " + unknown);
        }
    }

    private SpeechHybridCandidateAvailability availabilityFor(boolean transcriptPresent, boolean acousticPresent) {
        if (transcriptPresent && acousticPresent) return SpeechHybridCandidateAvailability.BOTH_AVAILABLE;
        if (transcriptPresent) return SpeechHybridCandidateAvailability.ACOUSTIC_FEATURE_MISSING;
        if (acousticPresent) return SpeechHybridCandidateAvailability.TRANSCRIPT_MISSING;
        return SpeechHybridCandidateAvailability.BOTH_MISSING;
    }

    private Map<String, SpeechHybridScoreBreakdown> controlBreakdowns(
            SpeechHybridQueryContext context, boolean transcriptControl
    ) {
        Map<String, SpeechHybridScoreBreakdown> breakdowns = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            Double rawScore = transcriptControl ? candidate.transcriptRawScore() : candidate.acousticRawScore();
            breakdowns.put(candidate.sampleId(), new SpeechHybridScoreBreakdown(
                    candidate.sampleId(),
                    context.transcriptNormalization().normalizedScoresBySampleId().get(candidate.sampleId()),
                    context.acousticNormalization().normalizedScoresBySampleId().get(candidate.sampleId()), rawScore));
        }
        return Map.copyOf(breakdowns);
    }

    private FusionExecution fuse(SpeechFusionWeights weights, SpeechHybridQueryContext context) {
        List<SpeechModalityRankedResult> unranked = new ArrayList<>();
        Map<String, SpeechHybridScoreBreakdown> breakdowns = new LinkedHashMap<>();
        for (SpeechHybridCandidate candidate : context.candidates()) {
            Double transcript = context.transcriptNormalization().normalizedScoresBySampleId().get(candidate.sampleId());
            Double acoustic = context.acousticNormalization().normalizedScoresBySampleId().get(candidate.sampleId());
            Double fused = null;
            if (candidate.availability() == SpeechHybridCandidateAvailability.BOTH_AVAILABLE) {
                fused = weights.transcriptWeight() * transcript + weights.acousticWeight() * acoustic;
                unranked.add(new SpeechModalityRankedResult(candidate.sampleId(), fused, 1));
            }
            breakdowns.put(candidate.sampleId(), new SpeechHybridScoreBreakdown(candidate.sampleId(), transcript, acoustic, fused));
        }
        unranked.sort(Comparator.comparingDouble(SpeechModalityRankedResult::score).reversed()
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
            if (Double.compare(ranking.get(index - 1).score(), ranking.get(index).score()) == 0) tieCount++;
        }
        return tieCount;
    }

    private SpeechModalityOutcome classify(boolean transcriptHit, boolean acousticHit) {
        if (transcriptHit && acousticHit) return SpeechModalityOutcome.BOTH_SUCCEED;
        if (transcriptHit) return SpeechModalityOutcome.TRANSCRIPT_ONLY;
        if (acousticHit) return SpeechModalityOutcome.ACOUSTIC_ONLY;
        return SpeechModalityOutcome.BOTH_FAIL;
    }

    private SpeechHybridDisagreementOutcome classifyDisagreement(SpeechModalityOutcome outcome, boolean hybridHit) {
        return switch (outcome) {
            case BOTH_SUCCEED -> hybridHit ? SpeechHybridDisagreementOutcome.BOTH_SUCCEED_RETAINED
                    : SpeechHybridDisagreementOutcome.LOST_BOTH_SUCCEED;
            case TRANSCRIPT_ONLY -> hybridHit ? SpeechHybridDisagreementOutcome.RECOVERED_ACOUSTIC_MISS
                    : SpeechHybridDisagreementOutcome.LOST_TRANSCRIPT_WIN;
            case ACOUSTIC_ONLY -> hybridHit ? SpeechHybridDisagreementOutcome.RECOVERED_TRANSCRIPT_MISS
                    : SpeechHybridDisagreementOutcome.LOST_ACOUSTIC_WIN;
            case BOTH_FAIL -> hybridHit ? SpeechHybridDisagreementOutcome.RECOVERED_BOTH_MISS
                    : SpeechHybridDisagreementOutcome.BOTH_MISS_RETAINED;
        };
    }

    private SpeechHybridProfileSummary summarize(List<SpeechHybridProfileQueryResult> results) {
        double precision = 0.0, recall = 0.0, hit = 0.0, reciprocalRank = 0.0;
        double transcriptPrecision = 0.0, transcriptRecall = 0.0, transcriptHit = 0.0, transcriptRr = 0.0;
        double acousticPrecision = 0.0, acousticRecall = 0.0, acousticHit = 0.0, acousticRr = 0.0;
        Map<SpeechHybridComparison, Integer> transcriptComparisons = emptyComparisons();
        Map<SpeechHybridComparison, Integer> acousticComparisons = emptyComparisons();
        Map<SpeechHybridDisagreementOutcome, Integer> outcomes = emptyOutcomes();
        int controlHitLosses = 0;
        for (SpeechHybridProfileQueryResult query : results) {
            SpeechModalityQueryMetrics hybrid = query.result().metrics();
            SpeechModalityQueryMetrics transcript = query.context().transcriptControl().metrics();
            SpeechModalityQueryMetrics acoustic = query.context().acousticControl().metrics();
            precision += hybrid.precisionAtK(); recall += hybrid.recallAtK(); hit += hybrid.hitAtK() ? 1.0 : 0.0;
            reciprocalRank += hybrid.reciprocalRank();
            transcriptPrecision += transcript.precisionAtK(); transcriptRecall += transcript.recallAtK();
            transcriptHit += transcript.hitAtK() ? 1.0 : 0.0; transcriptRr += transcript.reciprocalRank();
            acousticPrecision += acoustic.precisionAtK(); acousticRecall += acoustic.recallAtK();
            acousticHit += acoustic.hitAtK() ? 1.0 : 0.0; acousticRr += acoustic.reciprocalRank();
            transcriptComparisons.merge(query.vsTranscript(), 1, Integer::sum);
            acousticComparisons.merge(query.vsAcoustic(), 1, Integer::sum);
            outcomes.merge(query.disagreementOutcome(), 1, Integer::sum);
            if (query.context().controlOutcome() != SpeechModalityOutcome.BOTH_FAIL && !hybrid.hitAtK()) controlHitLosses++;
        }
        int count = results.size();
        SpeechEvaluationMetrics hybrid = new SpeechEvaluationMetrics(precision / count, recall / count, hit / count, reciprocalRank / count, count);
        SpeechEvaluationMetrics transcript = new SpeechEvaluationMetrics(transcriptPrecision / count, transcriptRecall / count, transcriptHit / count, transcriptRr / count, count);
        SpeechEvaluationMetrics acoustic = new SpeechEvaluationMetrics(acousticPrecision / count, acousticRecall / count, acousticHit / count, acousticRr / count, count);
        return new SpeechHybridProfileSummary(hybrid, SpeechMetricDelta.between(hybrid, transcript),
                SpeechMetricDelta.between(hybrid, acoustic), transcriptComparisons, acousticComparisons,
                outcomes, controlHitLosses, isPromising(hybrid, transcript, acoustic, controlHitLosses));
    }

    private Map<SpeechHybridComparison, Integer> emptyComparisons() {
        Map<SpeechHybridComparison, Integer> counts = new EnumMap<>(SpeechHybridComparison.class);
        for (SpeechHybridComparison value : SpeechHybridComparison.values()) counts.put(value, 0);
        return counts;
    }

    private Map<SpeechHybridDisagreementOutcome, Integer> emptyOutcomes() {
        Map<SpeechHybridDisagreementOutcome, Integer> counts = new EnumMap<>(SpeechHybridDisagreementOutcome.class);
        for (SpeechHybridDisagreementOutcome value : SpeechHybridDisagreementOutcome.values()) counts.put(value, 0);
        return counts;
    }

    private boolean isPromising(SpeechEvaluationMetrics hybrid, SpeechEvaluationMetrics transcript,
                                SpeechEvaluationMetrics acoustic, int controlHitLosses) {
        return controlHitLosses == 0
                && notBelowBoth(hybrid.precisionAtK(), transcript.precisionAtK(), acoustic.precisionAtK())
                && notBelowBoth(hybrid.recallAtK(), transcript.recallAtK(), acoustic.recallAtK())
                && notBelowBoth(hybrid.hitRateAtK(), transcript.hitRateAtK(), acoustic.hitRateAtK())
                && notBelowBoth(hybrid.mrr(), transcript.mrr(), acoustic.mrr())
                && (strictlyAboveBoth(hybrid.recallAtK(), transcript.recallAtK(), acoustic.recallAtK())
                || strictlyAboveBoth(hybrid.hitRateAtK(), transcript.hitRateAtK(), acoustic.hitRateAtK())
                || strictlyAboveBoth(hybrid.mrr(), transcript.mrr(), acoustic.mrr()));
    }

    private boolean notBelowBoth(double candidate, double transcript, double acoustic) {
        return candidate + COMPARISON_TOLERANCE >= transcript && candidate + COMPARISON_TOLERANCE >= acoustic;
    }

    private boolean strictlyAboveBoth(double candidate, double transcript, double acoustic) {
        return candidate > transcript + COMPARISON_TOLERANCE && candidate > acoustic + COMPARISON_TOLERANCE;
    }

    private record FusionExecution(List<SpeechModalityRankedResult> ranking,
                                   Map<String, SpeechHybridScoreBreakdown> breakdowns, int tieCount) {
    }
}
