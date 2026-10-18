package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Compares the predeclared #128 missing-modality policies against both controls and the #79 reference
 * arm over separate development and holdout splits. Evaluation only: no ranking behavior is changed.
 */
final class SpeechMissingModalityPolicyRunner {

    /** Cases where a complete relevant candidate exists; a policy must never lose them. */
    static final Set<SpeechHybridConflictCategory> CONFLICT_GUARDED = EnumSet.of(
            SpeechHybridConflictCategory.AGREEMENT_RELEVANT,
            SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT,
            SpeechHybridConflictCategory.ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT,
            SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR,
            SpeechHybridConflictCategory.ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR);

    /** Categories the untouched holdout must contain before any policy may reach FURTHER_STUDY. */
    static final Set<SpeechHybridConflictCategory> REQUIRED_HOLDOUT_COVERAGE = Collections.unmodifiableSet(EnumSet.of(
            SpeechHybridConflictCategory.TRANSCRIPT_MISSING,
            SpeechHybridConflictCategory.ACOUSTIC_MISSING,
            SpeechHybridConflictCategory.BOTH_MISSING,
            SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_ACOUSTIC_CONFLICT,
            SpeechHybridConflictCategory.ACOUSTIC_CORRECT_TRANSCRIPT_CONFLICT,
            SpeechHybridConflictCategory.TRANSCRIPT_CORRECT_PARTIAL_DISTRACTOR,
            SpeechHybridConflictCategory.ACOUSTIC_CORRECT_PARTIAL_DISTRACTOR));

    static final String DEVELOPMENT_SPLIT = "development";
    static final String HOLDOUT_SPLIT = "holdout";

    private final SpeechMissingModalityPolicyEvaluator policyEvaluator = new SpeechMissingModalityPolicyEvaluator();
    private final SpeechRankingEvaluator rankingEvaluator = new SpeechRankingEvaluator();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();

    SpeechMissingModalityPolicyReport run(SpeechModalityEvidence evidence, List<SpeechMissingModalityStudySplit> splits) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(splits, "splits");
        if (splits.isEmpty()) {
            throw new IllegalArgumentException("at least one split is required");
        }
        validateDistinctSplits(splits);
        List<SpeechMissingModalityPolicySplitResult> results = new ArrayList<>();
        for (SpeechMissingModalityStudySplit split : splits) {
            Map<String, SpeechModalityQueryResult> reference = referenceResults(split);
            for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
                results.add(evaluateSplit(split, policy, reference));
            }
        }
        List<SpeechMissingModalityPolicyVerdict> verdicts = new ArrayList<>();
        for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
            verdicts.add(decide(policy, results.stream().filter(result -> result.policy() == policy).toList()));
        }
        return new SpeechMissingModalityPolicyReport(evidence, SpeechHybridRobustnessRunner.FIXED_CANDIDATE,
                splits, results, verdicts, overall(verdicts));
    }

    SpeechMissingModalityPolicyVerdict decide(
            SpeechMissingModalityPolicy policy,
            List<SpeechMissingModalityPolicySplitResult> splitResults
    ) {
        List<String> reasons = new ArrayList<>();
        if (policy == SpeechMissingModalityPolicy.BOTH_REQUIRED) {
            reasons.add("reference arm inherits HYBRID_CANDIDATE_RISKY from #79");
            return new SpeechMissingModalityPolicyVerdict(policy, SpeechMissingModalityPolicyDecision.RISK_REMAINS, reasons);
        }
        if (count(splitResults, DEVELOPMENT_SPLIT) != 1 || count(splitResults, HOLDOUT_SPLIT) != 1) {
            reasons.add("requires exactly one " + DEVELOPMENT_SPLIT + " and one untouched " + HOLDOUT_SPLIT + " split");
        }
        int recoveries = 0;
        for (SpeechMissingModalityPolicySplitResult result : splitResults) {
            String split = result.split();
            if (split.equals(HOLDOUT_SPLIT)) {
                Set<SpeechHybridConflictCategory> missing = EnumSet.noneOf(SpeechHybridConflictCategory.class);
                missing.addAll(REQUIRED_HOLDOUT_COVERAGE);
                missing.removeAll(result.coveredCategories());
                if (!missing.isEmpty()) {
                    reasons.add(split + ": lacks required categories " + missing);
                }
            }
            if (result.regressionsVsStronger() > 0) {
                reasons.add(split + ": " + result.regressionsVsStronger() + " regression(s) vs stronger control");
            }
            if (result.regressionsVsBothRequired() > 0) {
                reasons.add(split + ": " + result.regressionsVsBothRequired() + " regression(s) vs BOTH_REQUIRED");
            }
            if (result.conflictRegressions() > 0) {
                reasons.add(split + ": " + result.conflictRegressions() + " conflict/agreement regression(s)");
            }
            if (!result.regressedGroups().isEmpty()) {
                reasons.add(split + ": grouped regression in " + result.regressedGroups());
            }
            recoveries += result.recoveriesVsBothRequired();
        }
        if (recoveries == 0) {
            reasons.add("no recovery over BOTH_REQUIRED");
        }
        return new SpeechMissingModalityPolicyVerdict(policy, reasons.isEmpty()
                ? SpeechMissingModalityPolicyDecision.FURTHER_STUDY
                : SpeechMissingModalityPolicyDecision.RISK_REMAINS, reasons);
    }

    SpeechMissingModalityPolicyDecision overall(List<SpeechMissingModalityPolicyVerdict> verdicts) {
        return verdicts.stream().anyMatch(verdict -> verdict.policy() != SpeechMissingModalityPolicy.BOTH_REQUIRED
                && verdict.decision() == SpeechMissingModalityPolicyDecision.FURTHER_STUDY)
                ? SpeechMissingModalityPolicyDecision.FURTHER_STUDY
                : SpeechMissingModalityPolicyDecision.RISK_REMAINS;
    }

    private void validateDistinctSplits(List<SpeechMissingModalityStudySplit> splits) {
        Map<String, SpeechMissingModalityStudySplit> byName = new LinkedHashMap<>();
        Set<String> labels = new HashSet<>();
        for (SpeechMissingModalityStudySplit split : splits) {
            Objects.requireNonNull(split, "split");
            if (byName.putIfAbsent(split.name(), split) != null) {
                throw new IllegalArgumentException("duplicate split name: " + split.name());
            }
            if (!labels.add(split.label())) {
                throw new IllegalArgumentException("split fixture label reused across splits: " + split.label());
            }
        }
        SpeechMissingModalityStudySplit development = byName.get(DEVELOPMENT_SPLIT);
        SpeechMissingModalityStudySplit holdout = byName.get(HOLDOUT_SPLIT);
        if (development != null && holdout != null) {
            Set<String> shared = new TreeSet<>(corpusSampleIds(development));
            shared.retainAll(corpusSampleIds(holdout));
            if (!shared.isEmpty()) {
                throw new IllegalArgumentException("holdout split shares corpus samples with development: " + shared);
            }
        }
    }

    /** Every prepared case carries the whole corpus as candidates, so the first case is enough. */
    private List<String> corpusSampleIds(SpeechMissingModalityStudySplit split) {
        return split.prepared().cases().getFirst().context().candidates().stream()
                .map(SpeechHybridCandidate::sampleId).toList();
    }

    private Map<String, SpeechModalityQueryResult> referenceResults(SpeechMissingModalityStudySplit split) {
        Map<String, SpeechModalityQueryResult> reference = new LinkedHashMap<>();
        for (SpeechHybridPreparedCase preparedCase : split.prepared().cases()) {
            SpeechHybridQueryContext context = preparedCase.context();
            reference.put(context.query().queryId(), rankingEvaluator.evaluate(policyEvaluator.rank(
                    SpeechMissingModalityPolicy.BOTH_REQUIRED, SpeechHybridRobustnessRunner.FIXED_CANDIDATE, context)
                    .ranking(), context.query().relevantSampleIds(), context.k()));
        }
        return reference;
    }

    private long count(List<SpeechMissingModalityPolicySplitResult> results, String split) {
        return results.stream().filter(result -> result.split().equals(split)).count();
    }

    private SpeechMissingModalityPolicySplitResult evaluateSplit(
            SpeechMissingModalityStudySplit split,
            SpeechMissingModalityPolicy policy,
            Map<String, SpeechModalityQueryResult> reference
    ) {
        List<SpeechMissingModalityPolicyQueryResult> queries = new ArrayList<>();
        Set<SpeechHybridConflictCategory> covered = EnumSet.noneOf(SpeechHybridConflictCategory.class);
        for (SpeechHybridPreparedCase preparedCase : split.prepared().cases()) {
            queries.add(evaluateQuery(policy, preparedCase,
                    reference.get(preparedCase.stressCase().query().queryId())));
            covered.add(preparedCase.stressCase().expectedConflictCategory());
        }
        List<SpeechMissingModalityGroupSummary> groups = new ArrayList<>();
        groups.add(summarize("aggregate", queries));
        groups.addAll(summarizeBy("condition", queries, query -> query.preparedCase().stressCase().query().condition().name()));
        groups.addAll(summarizeBy("task", queries, query -> query.preparedCase().stressCase().query().taskType().name()));
        groups.addAll(summarizeBy("speaker", queries, query -> query.preparedCase().speakerRelation().name()));
        groups.addAll(summarizeBy("conflict", queries,
                query -> query.preparedCase().stressCase().expectedConflictCategory().name()));
        return new SpeechMissingModalityPolicySplitResult(split.name(), policy, queries, groups,
                count(queries, query -> query.vsStrongerControl() == SpeechHybridComparison.REGRESS),
                count(queries, query -> query.vsBothRequired() == SpeechHybridComparison.REGRESS),
                count(queries, SpeechMissingModalityPolicyQueryResult::conflictRegression),
                groups.stream().filter(SpeechMissingModalityGroupSummary::regression)
                        .map(SpeechMissingModalityGroupSummary::group).toList(),
                count(queries, query -> query.vsBothRequired() == SpeechHybridComparison.WIN),
                count(queries, query -> query.ranking().route() == SpeechMissingModalityRoute.ABSTAINED),
                covered);
    }

    private SpeechMissingModalityPolicyQueryResult evaluateQuery(
            SpeechMissingModalityPolicy policy,
            SpeechHybridPreparedCase preparedCase,
            SpeechModalityQueryResult reference
    ) {
        SpeechHybridQueryContext context = preparedCase.context();
        SpeechMissingModalityPolicyRanking ranking = policyEvaluator.rank(
                policy, SpeechHybridRobustnessRunner.FIXED_CANDIDATE, context);
        SpeechModalityQueryResult result = rankingEvaluator.evaluate(
                ranking.ranking(), context.query().relevantSampleIds(), context.k());
        SpeechHybridBetterControl stronger = SpeechHybridControlComparison.strongerControl(context);
        return new SpeechMissingModalityPolicyQueryResult(policy, preparedCase, ranking, result, stronger,
                profileEvaluator.compare(result.metrics(), SpeechHybridControlComparison.strongerMetrics(context, stronger)),
                profileEvaluator.compare(result.metrics(), reference.metrics()));
    }

    private List<SpeechMissingModalityGroupSummary> summarizeBy(
            String dimension,
            List<SpeechMissingModalityPolicyQueryResult> queries,
            Function<SpeechMissingModalityPolicyQueryResult, String> key
    ) {
        Map<String, List<SpeechMissingModalityPolicyQueryResult>> groups = new LinkedHashMap<>();
        for (SpeechMissingModalityPolicyQueryResult query : queries) {
            groups.computeIfAbsent(key.apply(query), ignored -> new ArrayList<>()).add(query);
        }
        return groups.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> summarize(dimension + "=" + entry.getKey(), entry.getValue()))
                .toList();
    }

    private SpeechMissingModalityGroupSummary summarize(String group, List<SpeechMissingModalityPolicyQueryResult> queries) {
        SpeechHybridControlComparison.MeanMetrics transcript = new SpeechHybridControlComparison.MeanMetrics();
        SpeechHybridControlComparison.MeanMetrics acoustic = new SpeechHybridControlComparison.MeanMetrics();
        SpeechHybridControlComparison.MeanMetrics policy = new SpeechHybridControlComparison.MeanMetrics();
        for (SpeechMissingModalityPolicyQueryResult query : queries) {
            transcript.add(query.preparedCase().context().transcriptControl().metrics());
            acoustic.add(query.preparedCase().context().acousticControl().metrics());
            policy.add(query.result().metrics());
        }
        SpeechEvaluationMetrics transcriptMetrics = transcript.mean();
        SpeechEvaluationMetrics acousticMetrics = acoustic.mean();
        SpeechEvaluationMetrics policyMetrics = policy.mean();
        return new SpeechMissingModalityGroupSummary(group, transcriptMetrics, acousticMetrics, policyMetrics,
                SpeechHybridControlComparison.belowStrongerGroupControl(policyMetrics, transcriptMetrics, acousticMetrics));
    }

    private int count(
            List<SpeechMissingModalityPolicyQueryResult> queries,
            Predicate<SpeechMissingModalityPolicyQueryResult> predicate
    ) {
        return (int) queries.stream().filter(predicate).count();
    }
}

/** Exploratory-only outcome: the study has no promotion or "robust" result by construction. */
enum SpeechMissingModalityPolicyDecision {
    RISK_REMAINS,
    FURTHER_STUDY
}

/** One named fixture split (development or holdout) prepared by {@link SpeechHybridRobustnessRunner#prepare}. */
record SpeechMissingModalityStudySplit(String name, String label, SpeechHybridPreparedCases prepared) {
    SpeechMissingModalityStudySplit {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(prepared, "prepared");
        if (name.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("split name and label must not be blank");
        }
    }
}

record SpeechMissingModalityPolicyQueryResult(
        SpeechMissingModalityPolicy policy,
        SpeechHybridPreparedCase preparedCase,
        SpeechMissingModalityPolicyRanking ranking,
        SpeechModalityQueryResult result,
        SpeechHybridBetterControl strongerControl,
        SpeechHybridComparison vsStrongerControl,
        SpeechHybridComparison vsBothRequired
) {
    SpeechMissingModalityPolicyQueryResult {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(preparedCase, "preparedCase");
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(strongerControl, "strongerControl");
        Objects.requireNonNull(vsStrongerControl, "vsStrongerControl");
        Objects.requireNonNull(vsBothRequired, "vsBothRequired");
    }

    boolean conflictRegression() {
        return SpeechMissingModalityPolicyRunner.CONFLICT_GUARDED.contains(preparedCase.stressCase().expectedConflictCategory())
                && (vsStrongerControl == SpeechHybridComparison.REGRESS || vsBothRequired == SpeechHybridComparison.REGRESS);
    }
}

record SpeechMissingModalityGroupSummary(
        String group,
        SpeechEvaluationMetrics transcriptMetrics,
        SpeechEvaluationMetrics acousticMetrics,
        SpeechEvaluationMetrics policyMetrics,
        boolean regression
) {
    SpeechMissingModalityGroupSummary {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(transcriptMetrics, "transcriptMetrics");
        Objects.requireNonNull(acousticMetrics, "acousticMetrics");
        Objects.requireNonNull(policyMetrics, "policyMetrics");
    }
}

record SpeechMissingModalityPolicySplitResult(
        String split,
        SpeechMissingModalityPolicy policy,
        List<SpeechMissingModalityPolicyQueryResult> queries,
        List<SpeechMissingModalityGroupSummary> groups,
        int regressionsVsStronger,
        int regressionsVsBothRequired,
        int conflictRegressions,
        List<String> regressedGroups,
        int recoveriesVsBothRequired,
        int abstentions,
        Set<SpeechHybridConflictCategory> coveredCategories
) {
    SpeechMissingModalityPolicySplitResult {
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(policy, "policy");
        queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        regressedGroups = List.copyOf(Objects.requireNonNull(regressedGroups, "regressedGroups"));
        Set<SpeechHybridConflictCategory> covered = EnumSet.noneOf(SpeechHybridConflictCategory.class);
        covered.addAll(Objects.requireNonNull(coveredCategories, "coveredCategories"));
        coveredCategories = Collections.unmodifiableSet(covered);
        if (regressionsVsStronger < 0 || regressionsVsBothRequired < 0 || conflictRegressions < 0
                || recoveriesVsBothRequired < 0 || abstentions < 0) {
            throw new IllegalArgumentException("split counts must be non-negative");
        }
    }

    SpeechMissingModalityPolicyQueryResult query(String queryId) {
        return queries.stream().filter(query -> query.preparedCase().stressCase().query().queryId().equals(queryId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown query id: " + queryId));
    }
}

record SpeechMissingModalityPolicyVerdict(
        SpeechMissingModalityPolicy policy,
        SpeechMissingModalityPolicyDecision decision,
        List<String> reasons
) {
    SpeechMissingModalityPolicyVerdict {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(decision, "decision");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
