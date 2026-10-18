package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Timestamp-free evidence for the #128 missing-modality policy study. */
record SpeechMissingModalityPolicyReport(
        SpeechModalityEvidence evidence,
        SpeechFusionWeights candidateWeights,
        List<SpeechMissingModalityStudySplit> splits,
        List<SpeechMissingModalityPolicySplitResult> splitResults,
        List<SpeechMissingModalityPolicyVerdict> verdicts,
        SpeechMissingModalityPolicyDecision decision
) {
    SpeechMissingModalityPolicyReport {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(candidateWeights, "candidateWeights");
        splits = List.copyOf(Objects.requireNonNull(splits, "splits"));
        splitResults = List.copyOf(Objects.requireNonNull(splitResults, "splitResults"));
        verdicts = List.copyOf(Objects.requireNonNull(verdicts, "verdicts"));
        Objects.requireNonNull(decision, "decision");
    }

    SpeechMissingModalityPolicySplitResult result(String split, SpeechMissingModalityPolicy policy) {
        return splitResults.stream().filter(result -> result.split().equals(split) && result.policy() == policy)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown split/policy: " + split + "/" + policy));
    }

    SpeechMissingModalityPolicyVerdict verdict(SpeechMissingModalityPolicy policy) {
        return verdicts.stream().filter(verdict -> verdict.policy() == policy).findFirst().orElseThrow();
    }

    String render() {
        StringBuilder report = new StringBuilder();
        report.append("Monada Speech Missing-Modality Policy Study (#128)\n");
        report.append("==================================================\n");
        report.append("Evidence: ").append(evidence.name()).append('\n');
        report.append("Fixed weights: ").append(candidateWeights.label())
                .append("  normalization: per-query/per-modality min-max over available scores\n");
        report.append("Splits:");
        for (SpeechMissingModalityStudySplit split : splits) {
            report.append(' ').append(split.name()).append('=').append(split.label())
                    .append(" (corpus=").append(split.prepared().corpusSize())
                    .append(" queries=").append(split.prepared().cases().size())
                    .append(" k=").append(split.prepared().k()).append(')');
        }
        report.append('\n');
        report.append("Predeclared policies (not tuned on these cases):\n");
        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            report.append("  ").append(policy.name()).append(": ").append(policy.description()).append('\n');
        }
        report.append("Rule: FURTHER_STUDY only with zero regressions vs stronger control, vs BOTH_REQUIRED, on")
                .append(" conflict/agreement cases and in every group on every split, plus at least one recovery;")
                .append(" otherwise RISK_REMAINS.\n\n");
        for (SpeechMissingModalityStudySplit split : splits) {
            report.append("Split ").append(split.name()).append('\n');
            report.append("-".repeat(6 + split.name().length())).append('\n');
            for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
                appendSplitPolicy(report, result(split.name(), policy));
            }
            appendQueries(report, split);
        }
        report.append("Policy verdicts\n---------------\n");
        for (SpeechMissingModalityPolicyVerdict verdict : verdicts) {
            report.append(verdict.policy().name()).append(": ").append(verdict.decision().name())
                    .append(" reasons=").append(verdict.reasons()).append('\n');
        }
        report.append("Decision: ").append(decision.name()).append('\n');
        report.append("Promotion: not permitted; HYBRID_CANDIDATE_RISKY from #79 remains binding.\n");
        report.append("Evaluation only: no production/default hybrid behavior or protected baseline was changed.\n");
        return report.toString();
    }

    private void appendSplitPolicy(StringBuilder report, SpeechMissingModalityPolicySplitResult result) {
        report.append("[").append(result.policy().name()).append("] regressionsVsStronger=")
                .append(result.regressionsVsStronger()).append(" regressionsVsBothRequired=")
                .append(result.regressionsVsBothRequired()).append(" conflictRegressions=")
                .append(result.conflictRegressions()).append(" recoveriesVsBothRequired=")
                .append(result.recoveriesVsBothRequired()).append(" abstentions=").append(result.abstentions())
                .append(" regressedGroups=").append(result.regressedGroups()).append('\n');
        for (SpeechMissingModalityGroupSummary group : result.groups()) {
            report.append("  ").append(group.group()).append(" (n=").append(group.policyMetrics().queryCount())
                    .append(')');
            appendMetrics(report, " transcript", group.transcriptMetrics());
            appendMetrics(report, " acoustic", group.acousticMetrics());
            appendMetrics(report, " policy", group.policyMetrics());
            report.append(" regression=").append(group.regression()).append('\n');
        }
    }

    private void appendMetrics(StringBuilder report, String name, SpeechEvaluationMetrics metrics) {
        report.append(String.format(Locale.ROOT, "%s[R@k=%.4f Hit@k=%.4f MRR=%.4f]",
                name, metrics.recallAtK(), metrics.hitRateAtK(), metrics.mrr()));
    }

    private void appendQueries(StringBuilder report, SpeechMissingModalityStudySplit split) {
        report.append("Per-query results (").append(split.name()).append(")\n");
        for (SpeechHybridPreparedCase preparedCase : split.prepared().cases()) {
            String queryId = preparedCase.stressCase().query().queryId();
            SpeechHybridQueryContext context = preparedCase.context();
            report.append(queryId).append(" category=").append(preparedCase.stressCase().expectedConflictCategory())
                    .append(" condition=").append(preparedCase.stressCase().query().condition())
                    .append(" task=").append(preparedCase.stressCase().query().taskType())
                    .append(" speakerRelation=").append(preparedCase.speakerRelation())
                    .append(" relevant=").append(preparedCase.stressCase().query().relevantSampleIds().stream().sorted().toList())
                    .append('\n');
            report.append("  controls firstRelevantRank transcript=")
                    .append(context.transcriptControl().metrics().firstRelevantRank())
                    .append(" acoustic=").append(context.acousticControl().metrics().firstRelevantRank())
                    .append(" transcriptNorm=").append(context.transcriptNormalization().status())
                    .append(" acousticNorm=").append(context.acousticNormalization().status()).append('\n');
            for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
                SpeechMissingModalityPolicyQueryResult result = result(split.name(), policy).query(queryId);
                SpeechModalityQueryMetrics metrics = result.result().metrics();
                report.append("  [").append(policy.name()).append("] route=").append(result.ranking().route())
                        .append(" hit=").append(metrics.hitAtK())
                        .append(" firstRelevantRank=").append(metrics.firstRelevantRank())
                        .append(" strongerControl=").append(result.strongerControl())
                        .append(" vsStronger=").append(result.vsStrongerControl())
                        .append(" vsBothRequired=").append(result.vsBothRequired()).append('\n');
                for (String sampleId : reportedSampleIds(result)) {
                    appendComponent(report, result.ranking().componentsBySampleId().get(sampleId),
                            preparedCase.stressCase().query().relevantSampleIds().contains(sampleId));
                }
            }
        }
        report.append('\n');
    }

    private Set<String> reportedSampleIds(SpeechMissingModalityPolicyQueryResult result) {
        Set<String> ids = new LinkedHashSet<>();
        result.result().topResults().forEach(top -> ids.add(top.sampleId()));
        result.preparedCase().stressCase().query().relevantSampleIds().stream().sorted().forEach(ids::add);
        return ids;
    }

    private void appendComponent(StringBuilder report, SpeechMissingModalityComponentScore component, boolean relevant) {
        report.append("    ").append(relevant ? "relevant " : "top      ").append(component.sampleId())
                .append(" presence=").append(component.availability())
                .append(" T=").append(format(component.normalizedTranscriptScore()))
                .append(" A=").append(format(component.normalizedAcousticScore()))
                .append(" used=").append(component.componentsUsed().label())
                .append(" score=").append(format(component.policyScore()))
                .append(" rank=").append(component.rank() == null ? "-" : component.rank().toString())
                .append('\n');
    }

    private String format(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.6f", value);
    }
}
