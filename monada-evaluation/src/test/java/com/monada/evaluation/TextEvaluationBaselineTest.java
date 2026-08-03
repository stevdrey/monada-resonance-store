package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEvaluationBaselineTest {

    private static final TextEvaluationMetadata PROTECTED = new TextEvaluationMetadata(
            "dataset", "1", "PROFILE", TextEvaluationMode.PROTECTED);

    @Test
    void exactPolicyPassesWithinTolerance() {
        var baseline = baseline(
                1e-3,
                Map.of("averageHit@1", new TextBaselineExpectation(TextBaselinePolicy.EXACT, 0.8)));

        var comparison = baseline.compare(run(PROTECTED, report(0.8005, 1.0, 1.0, 1.0)));

        assertTrue(comparison.passed(), comparison.render());
    }

    @Test
    void exactPolicyReportsActionableDiff() {
        var baseline = baseline(
                1e-15,
                Map.of("averageHit@1", new TextBaselineExpectation(TextBaselinePolicy.EXACT, 1.0)));

        var comparison = baseline.compare(run(PROTECTED, report(0.75, 1.0, 1.0, 1.0)));

        assertFalse(comparison.passed());
        var rendered = comparison.render();
        assertTrue(rendered.contains("Dataset: dataset"), rendered);
        assertTrue(rendered.contains("Dataset version: 1"), rendered);
        assertTrue(rendered.contains("Profile: PROFILE"), rendered);
        assertTrue(rendered.contains("metric=averageHit@1"), rendered);
        assertTrue(rendered.contains("policy=EXACT"), rendered);
        assertTrue(rendered.contains("expected=1.000000000000000"), rendered);
        assertTrue(rendered.contains("actual=0.750000000000000"), rendered);
        assertTrue(rendered.contains("delta="), rendered);
        assertTrue(rendered.contains("tolerance="), rendered);
    }

    @Test
    void minimumPolicyAllowsImprovementsAndRejectsRegressions() {
        var baseline = baseline(
                1e-12,
                Map.of("meanReciprocalRank",
                        new TextBaselineExpectation(TextBaselinePolicy.MINIMUM, 0.8)));

        var improved = baseline.compare(run(PROTECTED, report(1.0, 1.0, 1.0, 0.9)));
        var regressed = baseline.compare(run(PROTECTED, report(1.0, 1.0, 1.0, 0.7)));

        assertTrue(improved.passed(), improved.render());
        assertFalse(regressed.passed());
        assertTrue(regressed.render().contains("policy=MINIMUM"), regressed.render());
    }

    @Test
    void collectsEveryMetricMismatch() {
        var baseline = baseline(
                1e-15,
                Map.of(
                        "averageHit@1", new TextBaselineExpectation(TextBaselinePolicy.EXACT, 1.0),
                        "meanReciprocalRank", new TextBaselineExpectation(TextBaselinePolicy.EXACT, 1.0)));

        var comparison = baseline.compare(run(PROTECTED, report(0.5, 1.0, 1.0, 0.5)));

        assertFalse(comparison.passed());
        assertEquals(2, comparison.mismatches().size(), comparison.render());
        assertTrue(comparison.render().contains("metric=averageHit@1"), comparison.render());
        assertTrue(comparison.render().contains("metric=meanReciprocalRank"), comparison.render());
    }

    @Test
    void missingActualMetricIsAnActionableMismatch() {
        var baseline = baseline(
                1e-15,
                Map.of("averagePrecision@3",
                        new TextBaselineExpectation(TextBaselinePolicy.EXACT, 0.5)));
        var report = new EvaluationReport(
                List.of(), Map.of(1, 0.5), Map.of(1, 1.0), Map.of(1, 1.0), 1.0);

        var comparison = baseline.compare(run(PROTECTED, report));

        assertFalse(comparison.passed());
        assertTrue(comparison.render().contains(
                "metric=averagePrecision@3 is missing from the actual report"), comparison.render());
    }

    @Test
    void rejectsExploratoryReports() {
        var exploratory = new TextEvaluationMetadata(
                "dataset", "1", "PROFILE", TextEvaluationMode.EXPLORATORY);

        assertThrows(IllegalArgumentException.class,
                () -> baseline(1e-15, exactHit()).compare(
                        run(exploratory, report(1.0, 1.0, 1.0, 1.0))));
    }

    @Test
    void rejectsMismatchedDatasetVersionOrProfile() {
        var baseline = baseline(1e-15, exactHit());

        assertThrows(IllegalArgumentException.class, () -> baseline.compare(run(
                new TextEvaluationMetadata("other", "1", "PROFILE", TextEvaluationMode.PROTECTED),
                report(1.0, 1.0, 1.0, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> baseline.compare(run(
                new TextEvaluationMetadata("dataset", "2", "PROFILE", TextEvaluationMode.PROTECTED),
                report(1.0, 1.0, 1.0, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> baseline.compare(run(
                new TextEvaluationMetadata("dataset", "1", "OTHER", TextEvaluationMode.PROTECTED),
                report(1.0, 1.0, 1.0, 1.0))));
    }

    @Test
    void rejectsUnsupportedMetricsAndInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> baseline(1e-15, Map.of(
                        "unknownMetric",
                        new TextBaselineExpectation(TextBaselinePolicy.EXACT, 1.0))));
        assertThrows(IllegalArgumentException.class,
                () -> new TextBaselineExpectation(TextBaselinePolicy.EXACT, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new TextBaselineExpectation(TextBaselinePolicy.EXACT, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> baseline(Double.POSITIVE_INFINITY, exactHit()));
    }

    private TextEvaluationBaseline baseline(
            double tolerance,
            Map<String, TextBaselineExpectation> metrics) {
        return new TextEvaluationBaseline("dataset", "1", "PROFILE", tolerance, metrics);
    }

    private Map<String, TextBaselineExpectation> exactHit() {
        return Map.of(
                "averageHit@1",
                new TextBaselineExpectation(TextBaselinePolicy.EXACT, 1.0));
    }

    private VersionedTextEvaluationReport run(
            TextEvaluationMetadata metadata,
            EvaluationReport report) {
        return new VersionedTextEvaluationReport(metadata, report);
    }

    private EvaluationReport report(
            double hitAt1,
            double recallAt3,
            double recallAt5,
            double mrr) {
        return new EvaluationReport(
                List.of(),
                Map.of(1, 1.0, 3, 0.5, 5, 0.2),
                Map.of(1, 0.5, 3, recallAt3, 5, recallAt5),
                Map.of(1, hitAt1, 3, hitAt1, 5, hitAt1),
                mrr);
    }
}
