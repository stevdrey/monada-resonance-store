package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Timestamp-free report for the evaluation-only transcript/acoustic score-fusion sweep. */
public record SpeechHybridSweepReport(
        SpeechModalityEvidence evidence,
        String label,
        int corpusSize,
        int k,
        List<SpeechHybridProfileResult> profiles,
        SpeechHybridDecision decision
) {
    public SpeechHybridSweepReport {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (corpusSize <= 0) {
            throw new IllegalArgumentException("corpusSize must be positive");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Objects.requireNonNull(profiles, "profiles");
        profiles = List.copyOf(profiles);
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("profiles must not be empty");
        }
        Objects.requireNonNull(decision, "decision");
    }

    /** Renders only deterministic evidence; the sweep does not enable a production hybrid ranker. */
    public String render() {
        StringBuilder report = new StringBuilder();
        report.append("Monada Speech Hybrid Score-Fusion Sweep\n");
        report.append("========================================\n");
        report.append("Evidence: ").append(evidence.name()).append('\n');
        report.append("Policy: ").append(evidencePolicy()).append('\n');
        report.append("Label: ").append(label).append('\n');
        report.append("Corpus size: ").append(corpusSize).append('\n');
        report.append("Query count: ").append(profiles.getFirst().queryResults().size()).append('\n');
        report.append("k: ").append(k).append('\n');
        report.append("Fusion equation: transcriptWeight * normalizedTranscriptScore"
                + " + acousticWeight * normalizedAcousticScore\n");
        report.append("Normalization: per-query, per-modality min-max; constant arms normalize to 0.0.\n\n");

        report.append("Aggregate profiles\n");
        report.append("------------------\n");
        for (SpeechHybridProfileResult profile : profiles) {
            appendProfileSummary(report, profile);
        }
        report.append('\n');

        report.append("Per-query results\n");
        report.append("-----------------\n");
        for (SpeechHybridQueryContext context : profiles.getFirst().contexts()) {
            appendQuery(report, context);
            for (SpeechHybridProfileResult profile : profiles) {
                appendProfileQuery(report, profile.queryResult(context.query().queryId()));
            }
        }
        report.append('\n');
        report.append("Decision: ").append(decision.name()).append('\n');
        report.append("Evaluation only: no production/default hybrid behavior was changed.\n");
        return report.toString();
    }

    private String evidencePolicy() {
        return switch (evidence) {
            case GENERATED_CI -> "deterministic generated-fixture evidence (not a protected hybrid baseline)";
            case LOCAL_EXPLORATORY -> "exploratory local-corpus evidence (not a protected baseline)";
        };
    }

    private void appendProfileSummary(StringBuilder report, SpeechHybridProfileResult profile) {
        SpeechHybridProfileSummary summary = profile.summary();
        SpeechEvaluationMetrics metrics = summary.metrics();
        report.append(profile.weights().label())
                .append(profile.weights().isHybrid() ? " hybrid" : " control")
                .append(String.format(Locale.ROOT,
                        " (n=%d): P@k=%.4f R@k=%.4f Hit@k=%.4f MRR=%.4f%n",
                        metrics.queryCount(), metrics.precisionAtK(), metrics.recallAtK(),
                        metrics.hitRateAtK(), metrics.mrr()));
        appendDelta(report, "  vs transcript", summary.vsTranscript());
        appendDelta(report, "  vs acoustic", summary.vsAcoustic());
        report.append("  promising=").append(summary.promising())
                .append(" controlHitLosses=").append(summary.controlHitLosses())
                .append(" transcript ").append(summary.transcriptComparisonCounts())
                .append(" acoustic ").append(summary.acousticComparisonCounts()).append('\n');
        report.append("  disagreement outcomes=").append(summary.disagreementOutcomeCounts()).append('\n');
    }

    private void appendDelta(StringBuilder report, String label, SpeechMetricDelta delta) {
        report.append(String.format(Locale.ROOT,
                "%s: dP=%.4f dR=%.4f dHit=%.4f dMRR=%.4f%n",
                label, delta.precisionAtK(), delta.recallAtK(), delta.hitRateAtK(), delta.mrr()));
    }

    private void appendQuery(StringBuilder report, SpeechHybridQueryContext context) {
        PairedSpeechQuery query = context.query();
        report.append(query.queryId()).append(": transcript=\"").append(query.transcript()).append("\"");
        report.append(" relevant=").append(query.relevantSampleIds().stream().sorted().toList()).append('\n');
        appendNormalization(report, "  transcript", context.transcriptNormalization());
        appendNormalization(report, "  acoustic", context.acousticNormalization());
        report.append("  availability=").append(context.availabilityIds()).append('\n');
        report.append("  controls outcome=").append(context.controlOutcome()).append('\n');
    }

    private void appendNormalization(StringBuilder report, String label, SpeechNormalizedScores scores) {
        report.append(String.format(Locale.ROOT, "%s normalization=%s rawRange=[%.6f, %.6f]%n",
                label, scores.status(), scores.minimumRawScore(), scores.maximumRawScore()));
    }

    private void appendProfileQuery(StringBuilder report, SpeechHybridProfileQueryResult result) {
        SpeechModalityQueryMetrics metrics = result.result().metrics();
        report.append("  ").append(result.weights().label()).append(" topK=");
        report.append(result.result().topResults().stream()
                .map(ranked -> renderRankedResult(ranked, result.breakdownsBySampleId().get(ranked.sampleId()),
                        result.weights().isHybrid()))
                .toList());
        report.append(String.format(Locale.ROOT,
                " P=%.4f R=%.4f Hit=%b firstRelevantRank=%d RR=%.4f ties=%d vsTranscript=%s vsAcoustic=%s outcome=%s%n",
                metrics.precisionAtK(), metrics.recallAtK(), metrics.hitAtK(), metrics.firstRelevantRank(),
                metrics.reciprocalRank(), result.tieCount(), result.vsTranscript(), result.vsAcoustic(),
                result.disagreementOutcome()));
    }

    private String renderRankedResult(
            SpeechModalityRankedResult ranked,
            SpeechHybridScoreBreakdown breakdown,
            boolean hybrid
    ) {
        if (!hybrid) {
            return String.format(Locale.ROOT, "%s@%d=%.6f", ranked.sampleId(), ranked.rank(), ranked.score());
        }
        return String.format(Locale.ROOT, "%s@%d=%.6f(T=%.6f,A=%.6f)",
                ranked.sampleId(), ranked.rank(), ranked.score(),
                breakdown.normalizedTranscriptScore(), breakdown.normalizedAcousticScore());
    }
}

record SpeechHybridProfileResult(
        SpeechFusionWeights weights,
        List<SpeechHybridProfileQueryResult> queryResults,
        SpeechHybridProfileSummary summary
) {
    SpeechHybridProfileResult {
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(queryResults, "queryResults");
        queryResults = List.copyOf(queryResults);
        Objects.requireNonNull(summary, "summary");
        if (summary.metrics().queryCount() != queryResults.size()) {
            throw new IllegalArgumentException("summary query count must match queryResults.size()");
        }
    }

    List<SpeechHybridQueryContext> contexts() {
        return queryResults.stream().map(SpeechHybridProfileQueryResult::context).toList();
    }

    SpeechHybridProfileQueryResult queryResult(String queryId) {
        return queryResults.stream()
                .filter(result -> result.context().query().queryId().equals(queryId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown query id: " + queryId));
    }
}

record SpeechHybridProfileQueryResult(
        SpeechHybridQueryContext context,
        SpeechFusionWeights weights,
        SpeechModalityQueryResult result,
        Map<String, SpeechHybridScoreBreakdown> breakdownsBySampleId,
        int tieCount,
        SpeechHybridComparison vsTranscript,
        SpeechHybridComparison vsAcoustic,
        SpeechHybridDisagreementOutcome disagreementOutcome
) {
    SpeechHybridProfileQueryResult {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(breakdownsBySampleId, "breakdownsBySampleId");
        breakdownsBySampleId = immutableStringMap(breakdownsBySampleId);
        if (tieCount < 0) {
            throw new IllegalArgumentException("tieCount must be non-negative");
        }
        Objects.requireNonNull(vsTranscript, "vsTranscript");
        Objects.requireNonNull(vsAcoustic, "vsAcoustic");
        Objects.requireNonNull(disagreementOutcome, "disagreementOutcome");
    }

    private static <T> Map<String, T> immutableStringMap(Map<String, T> source) {
        Map<String, T> copy = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> copy.put(
                        Objects.requireNonNull(entry.getKey(), "map key"),
                        Objects.requireNonNull(entry.getValue(), "map value")));
        return Collections.unmodifiableMap(copy);
    }
}

record SpeechHybridQueryContext(
        PairedSpeechQuery query,
        List<SpeechModalityRankedResult> transcriptRanking,
        List<SpeechModalityRankedResult> acousticRanking,
        SpeechModalityQueryResult transcriptControl,
        SpeechModalityQueryResult acousticControl,
        SpeechModalityOutcome controlOutcome,
        SpeechNormalizedScores transcriptNormalization,
        SpeechNormalizedScores acousticNormalization,
        int k,
        List<SpeechHybridCandidate> candidates
) {
    SpeechHybridQueryContext {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(transcriptRanking, "transcriptRanking");
        Objects.requireNonNull(acousticRanking, "acousticRanking");
        transcriptRanking = List.copyOf(transcriptRanking);
        acousticRanking = List.copyOf(acousticRanking);
        Objects.requireNonNull(transcriptControl, "transcriptControl");
        Objects.requireNonNull(acousticControl, "acousticControl");
        Objects.requireNonNull(controlOutcome, "controlOutcome");
        Objects.requireNonNull(transcriptNormalization, "transcriptNormalization");
        Objects.requireNonNull(acousticNormalization, "acousticNormalization");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Objects.requireNonNull(candidates, "candidates");
        candidates = List.copyOf(candidates);
    }

    Map<SpeechHybridCandidateAvailability, List<String>> availabilityIds() {
        Map<SpeechHybridCandidateAvailability, List<String>> ids = new EnumMap<>(SpeechHybridCandidateAvailability.class);
        for (SpeechHybridCandidateAvailability availability : SpeechHybridCandidateAvailability.values()) {
            ids.put(availability, candidates.stream()
                    .filter(candidate -> candidate.availability() == availability)
                    .map(SpeechHybridCandidate::sampleId)
                    .toList());
        }
        return Collections.unmodifiableMap(ids);
    }
}

record SpeechHybridScoreBreakdown(
        String sampleId,
        Double normalizedTranscriptScore,
        Double normalizedAcousticScore,
        Double fusedScore
) {
    SpeechHybridScoreBreakdown {
        Objects.requireNonNull(sampleId, "sampleId");
        validateScore(normalizedTranscriptScore, "normalizedTranscriptScore");
        validateScore(normalizedAcousticScore, "normalizedAcousticScore");
        validateScore(fusedScore, "fusedScore");
    }

    private static void validateScore(Double score, String label) {
        if (score != null && !Double.isFinite(score)) {
            throw new IllegalArgumentException(label + " must be finite when present");
        }
    }
}

record SpeechMetricDelta(double precisionAtK, double recallAtK, double hitRateAtK, double mrr) {
    SpeechMetricDelta {
        if (!Double.isFinite(precisionAtK) || !Double.isFinite(recallAtK)
                || !Double.isFinite(hitRateAtK) || !Double.isFinite(mrr)) {
            throw new IllegalArgumentException("metric deltas must be finite");
        }
    }

    static SpeechMetricDelta between(SpeechEvaluationMetrics candidate, SpeechEvaluationMetrics control) {
        return new SpeechMetricDelta(
                candidate.precisionAtK() - control.precisionAtK(),
                candidate.recallAtK() - control.recallAtK(),
                candidate.hitRateAtK() - control.hitRateAtK(),
                candidate.mrr() - control.mrr());
    }
}

record SpeechHybridProfileSummary(
        SpeechEvaluationMetrics metrics,
        SpeechMetricDelta vsTranscript,
        SpeechMetricDelta vsAcoustic,
        Map<SpeechHybridComparison, Integer> transcriptComparisonCounts,
        Map<SpeechHybridComparison, Integer> acousticComparisonCounts,
        Map<SpeechHybridDisagreementOutcome, Integer> disagreementOutcomeCounts,
        int controlHitLosses,
        boolean promising
) {
    SpeechHybridProfileSummary {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(vsTranscript, "vsTranscript");
        Objects.requireNonNull(vsAcoustic, "vsAcoustic");
        transcriptComparisonCounts = immutableCounts(transcriptComparisonCounts, SpeechHybridComparison.class);
        acousticComparisonCounts = immutableCounts(acousticComparisonCounts, SpeechHybridComparison.class);
        disagreementOutcomeCounts = immutableCounts(disagreementOutcomeCounts, SpeechHybridDisagreementOutcome.class);
        if (controlHitLosses < 0 || controlHitLosses > metrics.queryCount()) {
            throw new IllegalArgumentException("controlHitLosses must be between zero and the query count");
        }
    }

    private static <E extends Enum<E>> Map<E, Integer> immutableCounts(Map<E, Integer> source, Class<E> type) {
        Objects.requireNonNull(source, "counts");
        Map<E, Integer> copy = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            int count = source.getOrDefault(value, 0);
            if (count < 0) {
                throw new IllegalArgumentException("counts must be non-negative");
            }
            copy.put(value, count);
        }
        return Collections.unmodifiableMap(copy);
    }
}
