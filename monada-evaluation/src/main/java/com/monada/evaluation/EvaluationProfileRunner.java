package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.storage.feedback.FeedbackSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the same {@link EvaluationDataset} across multiple {@link EvaluationProfile}s
 * and produces an {@link EvaluationProfileComparison}.
 *
 * <p>Each profile is evaluated in a fresh subdirectory under {@code basePath} to
 * avoid cross-profile contamination. The first profile in the list is treated as
 * the baseline for {@link RankingChange} classification.
 *
 * <p>For profiles with {@link EvaluationProfile#feedbackAware()} == {@code true},
 * the runner seeds one deterministic positive feedback event per query (query text →
 * lexicographically smallest expected atom's id) before measuring. This allows the
 * comparison report to show the measurable effect of feedback-aware ranking.
 */
public final class EvaluationProfileRunner {

    private final EvaluationRunner evaluationRunner;
    private final EvaluationComparator comparator;

    public EvaluationProfileRunner() {
        this(new EvaluationRunner(), new EvaluationComparator());
    }

    public EvaluationProfileRunner(EvaluationRunner evaluationRunner, EvaluationComparator comparator) {
        this.evaluationRunner = Objects.requireNonNull(evaluationRunner, "evaluationRunner");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
    }

    /**
     * Evaluates {@code dataset} under each profile and returns a comparison.
     *
     * @param dataset   the dataset to evaluate; must be non-null and non-empty
     * @param profiles  ordered list of profiles; the first is the baseline
     * @param basePath  directory under which per-profile subdirectories are created
     */
    public EvaluationProfileComparison run(
            EvaluationDataset dataset,
            List<EvaluationProfile> profiles,
            Path basePath) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(basePath, "basePath");
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("profiles must not be empty");
        }
        var sanitizedNames = new HashSet<String>();
        for (var p : profiles) {
            var sanitized = sanitize(p.name());
            if (!sanitizedNames.add(sanitized)) {
                throw new IllegalArgumentException(
                        "profiles produce duplicate sanitized directory name: '" + sanitized + "'");
            }
        }

        var reportByProfile = new LinkedHashMap<EvaluationProfile, EvaluationReport>();
        
        for (var profile : profiles) {
            var profileDir = basePath.resolve(sanitize(profile.name()));
            try {
                Files.createDirectories(profileDir);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }

            var report = runProfile(dataset, profile, profileDir);
            reportByProfile.put(profile, report);
        }

        var baseProfile = profiles.get(0);
        var baseReport = reportByProfile.get(baseProfile);

        // Build per-query results.
        var perQuery = new ArrayList<QueryProfileResult>(dataset.queries().size());
        for (var qi = 0; qi < dataset.queries().size(); qi++) {
            var query = dataset.queries().get(qi);
            var returnedByProfile = new LinkedHashMap<EvaluationProfile, List<String>>();
            var changeByProfile = new LinkedHashMap<EvaluationProfile, RankingChange>();
            var diagnosticByProfile = new LinkedHashMap<EvaluationProfile, QueryKeyDiagnostic>();

            for (var profile : profiles) {
                var report = reportByProfile.get(profile);
                returnedByProfile.put(profile, report.queryResults().get(qi).returnedLabels());
                // Extract query key diagnostic from query result if present
                var diagnostic = report.queryResults().get(qi).queryKeyDiagnostic();
                if (diagnostic != null) {
                    diagnosticByProfile.put(profile, diagnostic);
                }
            }

            // Classify change vs. base (base profile gets MAINTAINED by definition).
            changeByProfile.put(baseProfile, RankingChange.MAINTAINED);
            for (var pi = 1; pi < profiles.size(); pi++) {
                var profile = profiles.get(pi);
                var beforeQE = baseReport.queryResults().get(qi);
                var afterQE = reportByProfile.get(profile).queryResults().get(qi);
                changeByProfile.put(profile, classifyQueryChange(beforeQE, afterQE));
            }

            // Classify failure using the last profile in the list (which is the base profile when only one is provided).
            var lastProfileQE = reportByProfile.get(profiles.get(profiles.size() - 1)).queryResults().get(qi);
            var failureTypeOpt = RetrievalFailureClassifier.classify(lastProfileQE);
            var failureType = failureTypeOpt.orElse(null);

            perQuery.add(new QueryProfileResult(
                    query.text(),
                    query.expectedLabels(),
                    returnedByProfile,
                    changeByProfile,
                    failureType,
                    diagnosticByProfile));
        }

        // Build aggregate change map.
        var aggregateChange = new LinkedHashMap<EvaluationProfile, RankingChange>();
        aggregateChange.put(baseProfile, RankingChange.MAINTAINED);
        for (var pi = 1; pi < profiles.size(); pi++) {
            var profile = profiles.get(pi);
            var comparison = comparator.compare(baseReport, reportByProfile.get(profile));
            aggregateChange.put(profile, comparison.aggregate());
        }

        return new EvaluationProfileComparison(profiles, reportByProfile, perQuery, aggregateChange);
    }

    private EvaluationReport runProfile(EvaluationDataset dataset, EvaluationProfile profile, Path profileDir) {
        var options = profile.toMemoryOptions();
        var memory = MonadaMemory.open(profileDir, options);
        var queryKeyStrategy = options.feedbackQueryKeyStrategy();

        // Seed atoms exactly once with the profile's options and obtain both
        // atomId->label and label->atomId mappings required for ranking and feedback.
        var seeding = evaluationRunner.seedAtoms(dataset, memory);

        // Track seed query keys for feedback-aware profiles
        Map<String, String> seedQueryKeys = new HashMap<>();

        // Seed deterministic positive feedback for feedback-aware profiles.
        if (profile.feedbackAware()) {
            var labelToAtomId = seeding.labelToAtomId();
            for (var query : dataset.queries()) {
                // Use the lexicographically smallest expected label as the feedback target
                // to guarantee determinism across JVM runs (Set.copyOf iteration order is unspecified).
                var targetLabel = Collections.min(query.expectedLabels());
                var atomId = labelToAtomId.get(targetLabel);
                if (atomId != null) {
                    memory.feedback(query.text(), atomId, FeedbackSignal.POSITIVE);
                }
                // queryKeyStrategy is always non-null (validated by MonadaMemoryOptions/EvaluationProfile)
                seedQueryKeys.put(query.text(), queryKeyStrategy.keyFor(query.text()));
            }
        }

        // Evaluate with query-key diagnostics
        var report = evaluationRunner.evaluate(
                dataset, memory, seeding.idToLabel(),
                queryKeyStrategy, profile.feedbackAware());

        // Enhance query evaluations with complete diagnostics including seed keys
        return enhanceWithSeedQueryKeys(report, seedQueryKeys, profile.feedbackAware(),
                queryKeyStrategy.getClass().getSimpleName());
    }

    /**
     * Enhances query evaluations with complete query-key diagnostics including seed keys.
     *
     * <p>For feedback-aware profiles, the existing diagnostic (if any) is upgraded to
     * include the seed key. If no existing diagnostic is present but a seed key was
     * captured, a minimal feedback-aware diagnostic is synthesized using
     * {@code strategyName} so the seed key is never silently lost.
     */
    private EvaluationReport enhanceWithSeedQueryKeys(
            EvaluationReport report,
            Map<String, String> seedQueryKeys,
            boolean feedbackAware,
            String strategyName) {
        if (seedQueryKeys.isEmpty()) {
            return report;
        }

        var enhancedResults = new ArrayList<QueryEvaluation>(report.queryResults().size());
        for (var qe : report.queryResults()) {
            QueryKeyDiagnostic enhancedDiag = null;

            var seedKey = feedbackAware ? seedQueryKeys.get(qe.queryText()) : null;
            var existingDiag = qe.queryKeyDiagnostic();
            if (existingDiag != null) {
                if (seedKey != null) {
                    enhancedDiag = QueryKeyDiagnostic.withFeedback(
                            existingDiag.queryKey(),
                            existingDiag.strategyName(),
                            seedKey);
                } else {
                    enhancedDiag = existingDiag;
                }
            } else if (seedKey != null) {
                // No existing diagnostic but feedback was seeded: synthesize one so the
                // seed key is not silently lost for this query.
                enhancedDiag = QueryKeyDiagnostic.withFeedback(seedKey, strategyName, seedKey);
            }

            if (enhancedDiag != null) {
                enhancedResults.add(new QueryEvaluation(
                        qe.queryText(),
                        qe.expectedLabels(),
                        qe.returnedLabels(),
                        qe.precisionByK(),
                        qe.recallByK(),
                        qe.hitByK(),
                        qe.reciprocalRank(),
                        enhancedDiag));
            } else {
                enhancedResults.add(qe);
            }
        }

        return new EvaluationReport(
                enhancedResults,
                report.averagePrecisionByK(),
                report.averageRecallByK(),
                report.averageHitByK(),
                report.meanReciprocalRank());
    }

    /**
     * Classifies query-level change: compares rank of the first expected label.
     * Returns {@link RankingChange#MAINTAINED} when returned labels are identical.
     */
    private static RankingChange classifyQueryChange(QueryEvaluation before, QueryEvaluation after) {
        if (before.returnedLabels().equals(after.returnedLabels())) {
            return RankingChange.MAINTAINED;
        }
        var beforeRank = firstExpectedRank(before.expectedLabels(), before.returnedLabels());
        var afterRank = firstExpectedRank(after.expectedLabels(), after.returnedLabels());
        if (afterRank < beforeRank) {
            return RankingChange.IMPROVED;
        }
        if (afterRank > beforeRank) {
            return RankingChange.DEGRADED;
        }
        return RankingChange.MAINTAINED;
    }

    private static int firstExpectedRank(java.util.Set<String> expected, List<String> returned) {
        for (var i = 0; i < returned.size(); i++) {
            if (expected.contains(returned.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Converts a profile name to a safe directory component. */
    private static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }
}
