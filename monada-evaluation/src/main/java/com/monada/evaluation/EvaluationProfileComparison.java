package com.monada.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Result of running the same {@link EvaluationDataset} across multiple
 * {@link EvaluationProfile}s.
 *
 * <p>Provides:
 * <ul>
 *   <li>The ordered list of profiles that were evaluated.</li>
 *   <li>One {@link EvaluationReport} per profile.</li>
 *   <li>Per-query comparison across all profiles ({@link QueryProfileResult}).</li>
 *   <li>Aggregate {@link RankingChange} for each non-base profile, computed by
 *       {@link EvaluationComparator} against the first (base) profile.</li>
 * </ul>
 *
 * <p>Use {@link #render()} to produce a human-readable comparison report.
 */
public record EvaluationProfileComparison(
        List<EvaluationProfile> profiles,
        Map<EvaluationProfile, EvaluationReport> reportByProfile,
        List<QueryProfileResult> perQuery,
        Map<EvaluationProfile, RankingChange> aggregateChangeByProfile
) {
    public EvaluationProfileComparison {
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        reportByProfile = Map.copyOf(Objects.requireNonNull(reportByProfile, "reportByProfile"));
        perQuery = List.copyOf(Objects.requireNonNull(perQuery, "perQuery"));
        aggregateChangeByProfile = Map.copyOf(Objects.requireNonNull(aggregateChangeByProfile, "aggregateChangeByProfile"));
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("profiles must not be empty");
        }
        for (EvaluationProfile profile : profiles) {
            if (!reportByProfile.containsKey(profile)) {
                throw new IllegalArgumentException("missing report for profile: " + profile.name());
            }
        }
        // Every non-base profile must have an aggregate change entry so render() is consistent.
        for (int i = 1; i < profiles.size(); i++) {
            EvaluationProfile profile = profiles.get(i);
            if (!aggregateChangeByProfile.containsKey(profile)) {
                throw new IllegalArgumentException(
                        "missing aggregateChangeByProfile entry for non-base profile: " + profile.name());
            }
        }
    }

    /**
     * Renders a human-readable A/B comparison report.
     *
     * <p>Format:
     * <pre>
     * A/B Evaluation Profile Comparison
     * ==================================
     *
     * Query: ...
     * Expected: ...
     * [profile] Top 5: ...
     * Change vs RAW: IMPROVED / MAINTAINED / DEGRADED
     * Failure: CONFUSABLE_ATOM_RANKED_HIGHER   (only when present)
     *
     * Aggregate
     * ---------
     * [profile] Precision@1: 0.94 ...
     * [profile vs base] Change: IMPROVED
     * </pre>
     */
    public String render() {
        var sb = new StringBuilder();
        sb.append("A/B Evaluation Profile Comparison\n");
        sb.append("==================================\n\n");

        EvaluationProfile baseProfile = profiles.get(0);
        EvaluationReport baseReport = reportByProfile.get(baseProfile);
        int maxK = baseReport.averagePrecisionByK().keySet().stream().mapToInt(Integer::intValue).max().orElse(5);

        // Per-query section.
        for (var queryResult : perQuery) {
            sb.append("Query: ").append(queryResult.queryText()).append('\n');
            sb.append("Expected: ")
                    .append(String.join(", ", new TreeSet<>(queryResult.expectedLabels())))
                    .append('\n');
            for (var profile : profiles) {
                var labels = queryResult.returnedLabelsByProfile().getOrDefault(profile, List.of());
                int displayK = Math.min(labels.size(), maxK);
                sb.append(String.format(Locale.ROOT, "  [%s] Top %d: %s%n",
                        profile.name(), displayK,
                        displayK == 0 ? "(none)" : String.join(", ", labels.subList(0, displayK))));

                // Query Key Diagnostic per profile
                var diagnostic = queryResult.queryKeyDiagnosticByProfile().get(profile);
                if (diagnostic != null) {
                    sb.append(String.format(Locale.ROOT, "  [%s] Feedback Aware: %s%n",
                            profile.name(), diagnostic.feedbackAware()));
                    sb.append(String.format(Locale.ROOT, "  [%s] Query Key Strategy: %s%n",
                            profile.name(), diagnostic.strategyName()));
                    sb.append(String.format(Locale.ROOT, "  [%s] Evaluation Query Key: %s%n",
                            profile.name(), diagnostic.queryKey()));
                    if (diagnostic.hasSeedQueryKey()) {
                        sb.append(String.format(Locale.ROOT, "  [%s] Feedback Seed Key: %s%n",
                                profile.name(), diagnostic.seedQueryKey()));
                        sb.append(String.format(Locale.ROOT, "  [%s] Feedback Key Match: %s%n",
                                profile.name(), diagnostic.feedbackKeyMatch()));
                    }
                }

                if (profile != baseProfile) {
                    var change = queryResult.changeByProfile().getOrDefault(profile, RankingChange.MAINTAINED);
                    sb.append(String.format(Locale.ROOT, "  [%s vs %s] Change: %s%n",
                            profile.name(), baseProfile.name(), change));
                }
            }
            if (queryResult.hasFailure()) {
                sb.append("  Failure: ").append(queryResult.failureType()).append('\n');
            }
            sb.append('\n');
        }

        // Aggregate section.
        sb.append("Aggregate\n");
        sb.append("---------\n");
        for (var profile : profiles) {
            var report = reportByProfile.get(profile);
            sb.append(String.format(Locale.ROOT, "[%s]%n", profile.name()));
            appendMetricMap(sb, "  Precision", report.averagePrecisionByK());
            appendMetricMap(sb, "  Recall", report.averageRecallByK());
            appendMetricMap(sb, "  Hit", report.averageHitByK());
            sb.append(String.format(Locale.ROOT, "  MRR: %.4f%n", report.meanReciprocalRank()));
            if (profile != baseProfile) {
                var change = aggregateChangeByProfile.getOrDefault(profile, RankingChange.MAINTAINED);
                sb.append(String.format(Locale.ROOT, "  vs %s: %s%n", baseProfile.name(), change));
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static void appendMetricMap(StringBuilder sb, String label, Map<Integer, Double> map) {
        for (var e : map.entrySet()) {
            sb.append(String.format(Locale.ROOT, "%s@%d: %.4f%n", label, e.getKey(), e.getValue()));
        }
    }
}
