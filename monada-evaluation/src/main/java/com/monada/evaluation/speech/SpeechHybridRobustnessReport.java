package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Timestamp-free evidence for the one fixed hybrid candidate selected from #78. */
record SpeechHybridRobustnessReport(
        SpeechModalityEvidence evidence,
        String label,
        int corpusSize,
        int k,
        SpeechFusionWeights candidateWeights,
        List<SpeechHybridRobustnessQueryResult> queryResults,
        SpeechHybridRobustnessSummary aggregate,
        Map<String, SpeechHybridRobustnessSummary> byCondition,
        Map<String, SpeechHybridRobustnessSummary> byTask,
        Map<String, SpeechHybridRobustnessSummary> bySpeakerRelation,
        Map<String, SpeechHybridRobustnessSummary> byConflict,
        SpeechHybridRobustnessQueryResult worstRegression,
        SpeechHybridRobustnessDecision decision
) {
    SpeechHybridRobustnessReport {
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
        Objects.requireNonNull(candidateWeights, "candidateWeights");
        Objects.requireNonNull(queryResults, "queryResults");
        queryResults = List.copyOf(queryResults);
        if (queryResults.isEmpty()) {
            throw new IllegalArgumentException("queryResults must not be empty");
        }
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(byCondition, "byCondition");
        byCondition = Map.copyOf(byCondition);
        Objects.requireNonNull(byTask, "byTask");
        byTask = Map.copyOf(byTask);
        Objects.requireNonNull(bySpeakerRelation, "bySpeakerRelation");
        bySpeakerRelation = Map.copyOf(bySpeakerRelation);
        Objects.requireNonNull(byConflict, "byConflict");
        byConflict = Map.copyOf(byConflict);
        Objects.requireNonNull(decision, "decision");
    }

    String render() {
        StringBuilder report = new StringBuilder();
        report.append("Monada Speech Hybrid Robustness Study\n");
        report.append("=====================================\n");
        report.append("Evidence: ").append(evidence.name()).append('\n');
        report.append("Label: ").append(label).append('\n');
        report.append("Corpus size: ").append(corpusSize).append("  Query count: ").append(queryResults.size())
                .append("  k: ").append(k).append('\n');
        report.append("Fixed candidate inherited from #78: ").append(candidateWeights.label()).append('\n');
        report.append("Policy: per-query/per-modality min-max; incomplete candidates are excluded without imputation.\n\n");
        appendSummary(report, "Aggregate", aggregate);
        appendGroups(report, "condition", byCondition);
        appendGroups(report, "task", byTask);
        appendGroups(report, "speaker relation", bySpeakerRelation);
        appendGroups(report, "conflict category", byConflict);
        report.append("Per-query results\n-----------------\n");
        for (SpeechHybridRobustnessQueryResult result : queryResults) appendQuery(report, result);
        report.append("Worst regression: ");
        report.append(worstRegression == null ? "none" : worstRegression.stressCase().query().queryId());
        report.append('\n');
        report.append("Decision: ").append(decision.name()).append('\n');
        report.append("Evaluation only: no production/default hybrid behavior was changed.\n");
        return report.toString();
    }

    private void appendGroups(StringBuilder report, String name, Map<String, SpeechHybridRobustnessSummary> groups) {
        report.append("Metrics by ").append(name).append('\n');
        for (Map.Entry<String, SpeechHybridRobustnessSummary> entry : groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            appendSummary(report, "  " + entry.getKey(), entry.getValue());
        }
    }

    private void appendSummary(StringBuilder report, String label, SpeechHybridRobustnessSummary summary) {
        report.append(label).append(" (n=").append(summary.hybridMetrics().queryCount()).append(")\n");
        appendMetrics(report, "  transcript", summary.transcriptMetrics());
        appendMetrics(report, "  acoustic", summary.acousticMetrics());
        appendMetrics(report, "  hybrid", summary.hybridMetrics());
        report.append("  vs stronger controls=").append(summary.strongerControlComparisonCounts())
                .append(" groupRegression=").append(summary.groupRegression()).append('\n');
    }

    private void appendMetrics(StringBuilder report, String name, SpeechEvaluationMetrics metrics) {
        report.append(String.format(Locale.ROOT, "%s P@k=%.4f R@k=%.4f Hit@k=%.4f MRR=%.4f%n",
                name, metrics.precisionAtK(), metrics.recallAtK(), metrics.hitRateAtK(), metrics.mrr()));
    }

    private void appendQuery(StringBuilder report, SpeechHybridRobustnessQueryResult result) {
        SpeechHybridRobustnessCase stressCase = result.stressCase();
        SpeechHybridQueryContext context = result.context();
        report.append(stressCase.query().queryId()).append(" category=")
                .append(stressCase.expectedConflictCategory()).append(" speakerRelation=")
                .append(result.speakerRelation()).append(" strongerControl=").append(result.strongerControl())
                .append(" vsStronger=").append(result.vsStrongerControl()).append('\n');
        report.append("  transcript topK=").append(context.transcriptControl().topResults()).append('\n');
        report.append("  acoustic topK=").append(context.acousticControl().topResults()).append('\n');
        report.append("  hybrid topK=").append(result.hybrid().result().topResults()).append('\n');
        report.append("  firstRelevantRank transcript=").append(context.transcriptControl().metrics().firstRelevantRank())
                .append(" acoustic=").append(context.acousticControl().metrics().firstRelevantRank())
                .append(" hybrid=").append(result.hybrid().result().metrics().firstRelevantRank()).append('\n');
        report.append("  availability=").append(context.availabilityIds())
                .append(" outcome=").append(result.hybrid().disagreementOutcome()).append('\n');
    }
}
