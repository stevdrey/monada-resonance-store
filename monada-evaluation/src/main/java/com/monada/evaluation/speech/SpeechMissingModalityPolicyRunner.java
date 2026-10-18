package com.monada.evaluation.speech;

import com.monada.speech.evaluation.SpeechEvaluationMetrics;

import java.util.ArrayList;
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

    static final String DEVELOPMENT_SPLIT = "development";
    static final String HOLDOUT_SPLIT = "holdout";

    private final SpeechMissingModalityPolicyEvaluator policyEvaluator = new SpeechMissingModalityPolicyEvaluator();
    private final SpeechRankingEvaluator rankingEvaluator = new SpeechRankingEvaluator();
    private final SpeechHybridProfileEvaluator profileEvaluator = new SpeechHybridProfileEvaluator();
    private final SpeechHybridRobustnessRunner robustnessRunner = new SpeechHybridRobustnessRunner();

    SpeechMissingModalityPolicyReport run(SpeechModalityEvidence evidence, List<SpeechMissingModalityStudySplit> splits) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(splits, "splits");
        if (splits.isEmpty()) {
            throw new IllegalArgumentException("at least one split is required");
        }
        validateDistinctSplits(splits);
        List<SpeechMissingModalityPolicySplitResult> results = new ArrayList<>();
        for (SpeechMissingModalityStudySplit split : splits) {
            for (SpeechMissingModalityPolicy policy : SpeechMissingModalityPolicy.values()) {
                results.add(evaluateSplit(split, policy));
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
        Set<String> names = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (SpeechMissingModalityStudySplit split : splits) {
            Objects.requireNonNull(split, "split");
            if (!names.add(split.name())) {
                throw new IllegalArgumentException("duplicate split name: " + split.name());
            }
            if (!labels.add(split.label())) {
                throw new IllegalArgumentException("split fixture label reused across splits: " + split.label());
            }
        }
        Map<String, SpeechMissingModalityStudySplit> byName = new LinkedHashMap<>();
        splits.forEach(split -> byName.put(split.name(), split));
        SpeechMissingModalityStudySplit development = byName.get(DEVELOPMENT_SPLIT);
        SpeechMissingModalityStudySplit holdout = byName.get(HOLDOUT_SPLIT);
        if (development != null && holdout != null) {
            Set<String> shared = new TreeSet<>(sampleIds(development));
            shared.retainAll(sampleIds(holdout));
            if (!shared.isEmpty()) {
                throw new IllegalArgumentException("holdout split shares corpus samples with development: " + shared);
            }
        }
    }

    private Set<String> sampleIds(SpeechMissingModalityStudySplit split) {
        Set<String> ids = new HashSet<>();
        split.prepared().cases().forEach(preparedCase -> preparedCase.context().candidates()
                .forEach(candidate -> ids.add(candidate.sampleId())));
        return ids;
    }

    private long count(List<SpeechMissingModalityPolicySplitResult> results, String split) {
        return results.stream().filter(result -> result.split().equals(split)).count();
    }

    private SpeechMissingModalityPolicySplitResult evaluateSplit(
            SpeechMissingModalityStudySplit split,
            SpeechMissingModalityPolicy policy
    ) {
        List<SpeechMissingModalityPolicyQueryResult> queries = new ArrayList<>();
        for (SpeechHybridPreparedCase preparedCase : split.prepared().cases()) {
            queries.add(evaluateQuery(policy, preparedCase));
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
                count(queries, query -> query.ranking().route() == SpeechMissingModalityRoute.ABSTAINED));
    }

    private SpeechMissingModalityPolicyQueryResult evaluateQuery(
            SpeechMissingModalityPolicy policy,
            SpeechHybridPreparedCase preparedCase
    ) {
        SpeechHybridQueryContext context = preparedCase.context();
        SpeechFusionWeights weights = SpeechHybridRobustnessRunner.FIXED_CANDIDATE;
        SpeechMissingModalityPolicyRanking ranking = policyEvaluator.rank(policy, weights, context);
        SpeechModalityQueryResult result = rankingEvaluator.evaluate(
                ranking.ranking(), context.query().relevantSampleIds(), context.k());
        SpeechModalityQueryResult reference = policy == SpeechMissingModalityPolicy.BOTH_REQUIRED ? result
                : rankingEvaluator.evaluate(policyEvaluator.rank(SpeechMissingModalityPolicy.BOTH_REQUIRED, weights, context)
                .ranking(), context.query().relevantSampleIds(), context.k());
        SpeechHybridBetterControl stronger = robustnessRunner.strongerControl(context);
        return new SpeechMissingModalityPolicyQueryResult(policy, preparedCase, ranking, result, stronger,
                profileEvaluator.compare(result.metrics(), robustnessRunner.strongerMetrics(context, stronger)),
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
        double[] transcript = new double[4], acoustic = new double[4], policy = new double[4];
        for (SpeechMissingModalityPolicyQueryResult query : queries) {
            add(transcript, query.preparedCase().context().transcriptControl().metrics());
            add(acoustic, query.preparedCase().context().acousticControl().metrics());
            add(policy, query.result().metrics());
        }
        int count = queries.size();
        SpeechEvaluationMetrics transcriptMetrics = metrics(transcript, count);
        SpeechEvaluationMetrics acousticMetrics = metrics(acoustic, count);
        SpeechEvaluationMetrics policyMetrics = metrics(policy, count);
        return new SpeechMissingModalityGroupSummary(group, transcriptMetrics, acousticMetrics, policyMetrics,
                robustnessRunner.belowStrongerGroupControl(policyMetrics, transcriptMetrics, acousticMetrics));
    }

    private void add(double[] totals, SpeechModalityQueryMetrics metrics) {
        totals[0] += metrics.precisionAtK(); totals[1] += metrics.recallAtK();
        totals[2] += metrics.hitAtK() ? 1.0 : 0.0; totals[3] += metrics.reciprocalRank();
    }

    private SpeechEvaluationMetrics metrics(double[] totals, int count) {
        return new SpeechEvaluationMetrics(totals[0] / count, totals[1] / count, totals[2] / count, totals[3] / count, count);
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
        int abstentions
) {
    SpeechMissingModalityPolicySplitResult {
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(policy, "policy");
        queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        regressedGroups = List.copyOf(Objects.requireNonNull(regressedGroups, "regressedGroups"));
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
