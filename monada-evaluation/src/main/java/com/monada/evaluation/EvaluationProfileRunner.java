package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.storage.feedback.FeedbackSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

            for (var profile : profiles) {
                var report = reportByProfile.get(profile);
                returnedByProfile.put(profile, report.queryResults().get(qi).returnedLabels());
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
            var failureType = RetrievalFailureClassifier.classify(lastProfileQE);

            perQuery.add(new QueryProfileResult(
                    query.text(),
                    query.expectedLabels(),
                    returnedByProfile,
                    changeByProfile,
                    failureType));
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

        // Seed atoms exactly once with the profile's options and obtain the
        // atomId -> label mapping required to translate ranked results back.
        var idToLabel = evaluationRunner.seedAtoms(dataset, memory);

        // Invert idToLabel for feedback seeding by expected label.
        var labelToAtomId = new HashMap<String, String>();
        for (var entry : idToLabel.entrySet()) {
            labelToAtomId.put(entry.getValue(), entry.getKey());
        }

        // Seed deterministic positive feedback for feedback-aware profiles.
        if (profile.feedbackAware()) {
            for (var query : dataset.queries()) {
                // Use the lexicographically smallest expected label as the feedback target
                // to guarantee determinism across JVM runs (Set.copyOf iteration order is unspecified).
                var targetLabel = Collections.min(query.expectedLabels());
                var atomId = labelToAtomId.get(targetLabel);
                if (atomId != null) {
                    memory.feedback(query.text(), atomId, FeedbackSignal.POSITIVE);
                }
            }
        }

        return evaluationRunner.evaluate(dataset, memory, idToLabel);
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
