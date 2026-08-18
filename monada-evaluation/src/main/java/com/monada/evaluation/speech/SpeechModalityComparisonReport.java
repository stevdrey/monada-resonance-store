package com.monada.evaluation.speech;

import com.monada.speech.domain.SpeechCondition;
import com.monada.speech.domain.SpeechTaskType;
import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Deterministic report comparing transcript-only and acoustic-only retrieval. */
public record SpeechModalityComparisonReport(
        SpeechModalityEvidence evidence,
        String label,
        int corpusSize,
        int k,
        List<SpeechModalityComparisonQueryResult> queryResults,
        SpeechModalitySummary aggregate,
        Map<SpeechCondition, SpeechModalitySummary> summariesByCondition,
        Map<SpeechTaskType, SpeechModalitySummary> summariesByTaskType,
        SpeechModalityDecision decision
) {
    public SpeechModalityComparisonReport {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (corpusSize < 0) {
            throw new IllegalArgumentException("corpusSize must be non-negative");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        Objects.requireNonNull(queryResults, "queryResults");
        queryResults = List.copyOf(queryResults);
        Objects.requireNonNull(aggregate, "aggregate");
        if (aggregate.transcriptMetrics().queryCount() != queryResults.size()) {
            throw new IllegalArgumentException("aggregate query count must equal queryResults.size()");
        }
        Objects.requireNonNull(summariesByCondition, "summariesByCondition");
        Objects.requireNonNull(summariesByTaskType, "summariesByTaskType");
        summariesByCondition = immutableEnumMap(summariesByCondition, SpeechCondition.class);
        summariesByTaskType = immutableEnumMap(summariesByTaskType, SpeechTaskType.class);
        Objects.requireNonNull(decision, "decision");
    }

    private static <E extends Enum<E>> Map<E, SpeechModalitySummary> immutableEnumMap(
            Map<E, SpeechModalitySummary> source,
            Class<E> enumType
    ) {
        if (source.isEmpty()) {
            return Map.of();
        }
        var copy = new EnumMap<E, SpeechModalitySummary>(enumType);
        for (Map.Entry<E, SpeechModalitySummary> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "group key"),
                    Objects.requireNonNull(entry.getValue(), "group summary"));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Renders a timestamp-free report suitable for deterministic comparison. */
    public String render() {
        StringBuilder report = new StringBuilder();
        report.append("Monada Speech Modality Comparison\n");
        report.append("=================================\n");
        report.append("Evidence: ").append(evidence.name()).append('\n');
        report.append("Policy: ").append(evidencePolicy()).append('\n');
        report.append("Label: ").append(label).append('\n');
        report.append("Corpus size: ").append(corpusSize).append('\n');
        report.append("Query count: ").append(queryResults.size()).append('\n');
        report.append("k: ").append(k).append("\n\n");

        report.append("Aggregate metrics\n");
        report.append("-----------------\n");
        appendMetrics(report, "Transcript", aggregate.transcriptMetrics());
        appendMetrics(report, "Acoustic", aggregate.acousticMetrics());
        appendAgreement(report, aggregate);
        report.append('\n');

        appendGroups(report, "condition", summariesByCondition);
        appendGroups(report, "task type", summariesByTaskType);

        report.append("Per-query results\n");
        report.append("-----------------\n");
        for (SpeechModalityComparisonQueryResult result : queryResults) {
            appendQueryResult(report, result);
        }
        report.append('\n');

        report.append("Decision: ").append(decision.name()).append('\n');
        report.append("No hybrid score was computed; transcript and acoustic scores remain separate.\n");
        return report.toString();
    }

    private String evidencePolicy() {
        return switch (evidence) {
            case GENERATED_CI -> "deterministic generated-fixture evidence (safe for CI)";
            case LOCAL_EXPLORATORY -> "exploratory local-corpus evidence (not a protected baseline)";
        };
    }

    private void appendMetrics(StringBuilder report, String label, SpeechEvaluationMetrics metrics) {
        report.append(String.format(
                Locale.ROOT,
                "%s (n=%d): P@k=%.4f R@k=%.4f Hit@k=%.4f MRR=%.4f%n",
                label,
                metrics.queryCount(),
                metrics.precisionAtK(),
                metrics.recallAtK(),
                metrics.hitRateAtK(),
                metrics.mrr()));
    }

    private void appendAgreement(StringBuilder report, SpeechModalitySummary summary) {
        report.append(String.format(
                Locale.ROOT,
                "Outcome agreement=%.4f Mean top-K Jaccard=%.4f%n",
                summary.outcomeAgreementRate(),
                summary.meanTopKJaccard()));
        for (SpeechModalityOutcome outcome : SpeechModalityOutcome.values()) {
            report.append(String.format(
                    Locale.ROOT,
                    "%s: %d (%.4f)%n",
                    outcome.name(),
                    summary.outcomeCount(outcome),
                    summary.outcomeRate(outcome)));
        }
    }

    private <E extends Enum<E>> void appendGroups(
            StringBuilder report,
            String label,
            Map<E, SpeechModalitySummary> summaries
    ) {
        if (summaries.isEmpty()) {
            return;
        }
        report.append("Metrics by ").append(label).append('\n');
        report.append("----------------");
        report.append("\n");
        for (Map.Entry<E, SpeechModalitySummary> entry : summaries.entrySet()) {
            report.append(entry.getKey().name()).append('\n');
            appendMetrics(report, "  Transcript", entry.getValue().transcriptMetrics());
            appendMetrics(report, "  Acoustic", entry.getValue().acousticMetrics());
            report.append(String.format(
                    Locale.ROOT,
                    "  Agreement=%.4f Jaccard=%.4f%n",
                    entry.getValue().outcomeAgreementRate(),
                    entry.getValue().meanTopKJaccard()));
        }
        report.append('\n');
    }

    private void appendQueryResult(StringBuilder report, SpeechModalityComparisonQueryResult result) {
        PairedSpeechQuery query = result.query();
        report.append(query.queryId()).append(": transcript=\"").append(query.transcript()).append("\"");
        report.append(" relevant=").append(query.relevantSampleIds().stream().sorted().toList()).append('\n');
        appendQueryArm(report, "  transcript", result.transcript());
        appendQueryArm(report, "  acoustic", result.acoustic());
        report.append(String.format(
                Locale.ROOT,
                "  jaccard=%.4f outcome=%s%n",
                result.topKJaccard(),
                result.outcome().name()));
    }

    private void appendQueryArm(StringBuilder report, String label, SpeechModalityQueryResult result) {
        report.append(label).append(" topK=");
        report.append(result.topResults().stream()
                .map(value -> String.format(Locale.ROOT, "%s@%d=%.6f", value.sampleId(), value.rank(), value.score()))
                .toList());
        SpeechModalityQueryMetrics metrics = result.metrics();
        report.append(String.format(
                Locale.ROOT,
                " P=%.4f R=%.4f Hit=%b firstRelevantRank=%d RR=%.4f%n",
                metrics.precisionAtK(),
                metrics.recallAtK(),
                metrics.hitAtK(),
                metrics.firstRelevantRank(),
                metrics.reciprocalRank()));
    }
}
