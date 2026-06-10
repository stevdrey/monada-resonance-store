package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that query-key diagnostics are rendered in evaluation reports.
 */
class QueryKeyDiagnosticRenderingTest {

    @Test
    void evaluationReportRendersQueryKeyDiagnostic() {
        var diagnostic = QueryKeyDiagnostic.withoutFeedback(
                "test query", "ExactQueryKeyStrategy");
        var queryEval = new QueryEvaluation(
                "test query",
                Set.of("atom1"),
                List.of("atom1", "atom2"),
                Map.of(1, 1.0, 3, 0.5),
                Map.of(1, 1.0, 3, 1.0),
                Map.of(1, 1.0, 3, 1.0),
                1.0,
                diagnostic);

        var report = new EvaluationReport(
                List.of(queryEval),
                Map.of(1, 1.0, 3, 0.5),
                Map.of(1, 1.0, 3, 1.0),
                Map.of(1, 1.0, 3, 1.0),
                1.0);

        var rendered = report.render();

        assertTrue(rendered.contains("Query Key: test query"),
                "Report should contain query key");
        assertTrue(rendered.contains("Query Key Strategy: ExactQueryKeyStrategy"),
                "Report should contain query key strategy");
        assertTrue(rendered.contains("Feedback Aware: false"),
                "Report should indicate feedback is not aware");
    }

    @Test
    void evaluationReportRendersFeedbackAwareDiagnostic() {
        var diagnostic = QueryKeyDiagnostic.withFeedback(
                "lexical-expansion:cache redis", "LexicallyEnrichedQueryKeyStrategy",
                "lexical-expansion:cache redis");
        var queryEval = new QueryEvaluation(
                "cache redis store",
                Set.of("ka_redis"),
                List.of("ka_redis", "ka_memcached"),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0,
                diagnostic);

        var report = new EvaluationReport(
                List.of(queryEval),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0);

        var rendered = report.render();

        assertTrue(rendered.contains("Query Key: lexical-expansion:cache redis"),
                "Report should contain lexical query key");
        assertTrue(rendered.contains("Query Key Strategy: LexicallyEnrichedQueryKeyStrategy"),
                "Report should contain lexical strategy name");
        assertTrue(rendered.contains("Feedback Aware: true"),
                "Report should indicate feedback is aware");
        assertTrue(rendered.contains("Feedback Seed Key:"),
                "Report should contain seed key");
        assertTrue(rendered.contains("Feedback Key Match: true"),
                "Report should indicate keys match");
    }

    @Test
    void profileComparisonRendersQueryKeyDiagnosticsForAllProfiles() {
        // Issue #36 expects the comparison report to show diagnostics for every profile,
        // including non-feedback-aware ones (RAW, LEXICAL_ENRICHED, etc.).
        var rawDiagnostic = QueryKeyDiagnostic.withoutFeedback(
                "temporary lookup store", "ExactQueryKeyStrategy");
        var lexicalDiagnostic = QueryKeyDiagnostic.withFeedback(
                "lexical-expansion:cache redis lookup store temporary",
                "LexicallyEnrichedQueryKeyStrategy",
                "lexical-expansion:cache redis lookup store temporary");

        var rawProfile = EvaluationProfile.RAW;
        var lexicalProfile = EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY;

        var queryResult = new QueryProfileResult(
                "temporary lookup store",
                Set.of("ka_redis"),
                Map.of(rawProfile, List.of("ka_temporary_lookup", "ka_redis"),
                        lexicalProfile, List.of("ka_redis", "ka_temporary_lookup")),
                Map.of(rawProfile, RankingChange.MAINTAINED,
                        lexicalProfile, RankingChange.IMPROVED),
                null,
                Map.of(rawProfile, rawDiagnostic,
                        lexicalProfile, lexicalDiagnostic));

        var rawReport = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);
        var lexicalReport = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);

        var comparison = new EvaluationProfileComparison(
                List.of(rawProfile, lexicalProfile),
                Map.of(rawProfile, rawReport, lexicalProfile, lexicalReport),
                List.of(queryResult),
                Map.of(lexicalProfile, RankingChange.IMPROVED));

        var rendered = comparison.render();

        // RAW profile renders its non-feedback diagnostic (no seed key, no key-match)
        assertTrue(rendered.contains("[RAW] Feedback Aware: false"),
                "RAW profile must render Feedback Aware: false");
        assertTrue(rendered.contains("[RAW] Query Key Strategy: ExactQueryKeyStrategy"),
                "RAW profile must render its strategy name");
        assertTrue(rendered.contains("[RAW] Evaluation Query Key: temporary lookup store"),
                "RAW profile must render its evaluation query key");
        assertFalse(rendered.contains("[RAW] Feedback Seed Key:"),
                "RAW profile must not render a seed key line");
        assertFalse(rendered.contains("[RAW] Feedback Key Match:"),
                "RAW profile must not render a key-match line");

        // LEXICAL_FEEDBACK_KEY profile renders its full diagnostic including seed and match
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Aware: true"),
                "Feedback-aware profile should render Feedback Aware: true");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Query Key Strategy: LexicallyEnrichedQueryKeyStrategy"),
                "Feedback-aware profile should render its strategy name");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Evaluation Query Key: lexical-expansion:"),
                "Feedback-aware profile should render its evaluation query key");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Seed Key:"),
                "Feedback-aware profile should render its seed key");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Key Match:"),
                "Feedback-aware profile should render the key match result");
    }

    @Test
    void profileComparisonShowsKeyMismatchWhenSeedDiffers() {
        var diagnostic = QueryKeyDiagnostic.withFeedback(
                "lexical-expansion:cache redis", "LexicallyEnrichedQueryKeyStrategy",
                "exact:seed query");  // Different seed key

        var profile = EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY;

        var queryResult = new QueryProfileResult(
                "cache redis",
                Set.of("ka_redis"),
                Map.of(profile, List.of("ka_redis")),
                Map.of(profile, RankingChange.MAINTAINED),
                null,
                Map.of(profile, diagnostic));

        // Create minimal report for the profile (required by EvaluationProfileComparison validation)
        var profileReport = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);

        var comparison = new EvaluationProfileComparison(
                List.of(profile),
                Map.of(profile, profileReport),
                List.of(queryResult),
                Map.of());

        var rendered = comparison.render();

        assertTrue(rendered.contains("Feedback Key Match: false"),
                "Comparison should indicate key mismatch when seed differs from evaluation key");
    }

    // ---- P2: blank key from strategy falls back to raw query text ----

    @Test
    void evaluationReportShowsFallbackQueryKeyWhenStrategyReturnsBlank() {
        // Simulate a strategy that returns blank → effective key must be the raw query text
        var diagnostic = QueryKeyDiagnostic.withoutFeedback(
                "cache redis", "BlankKeyStrategy");  // effective key after fallback
        var queryEval = new QueryEvaluation(
                "cache redis",
                Set.of("ka_redis"),
                List.of("ka_redis"),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0,
                diagnostic);

        var report = new EvaluationReport(
                List.of(queryEval),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0);

        var rendered = report.render();

        assertTrue(rendered.contains("Query Key: cache redis"),
                "Fallback key should equal the raw query text when strategy returns blank");
    }

    // ---- P3: anonymous strategy name falls back to full class name ----

    @Test
    void evaluationReportShowsFullClassNameForAnonymousStrategy() {
        // Anonymous lambda / anonymous class → getSimpleName() returns ""
        var diagnostic = QueryKeyDiagnostic.withoutFeedback(
                "cache redis",
                // Simulate the resolved name that EvaluationRunner would produce
                "com.monada.evaluation.QueryKeyDiagnosticRenderingTest$$Lambda");
        var queryEval = new QueryEvaluation(
                "cache redis",
                Set.of("ka_redis"),
                List.of("ka_redis"),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0,
                diagnostic);

        var report = new EvaluationReport(
                List.of(queryEval),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0);

        var rendered = report.render();

        assertTrue(rendered.contains("Query Key Strategy: com.monada.evaluation"),
                "Report should contain a non-empty strategy identifier even for anonymous classes");
    }

    // ---- P3: feedbackKeyMatch must not show true when feedbackAware is false ----

    @Test
    void evaluationReportDoesNotShowFeedbackKeyMatchWhenNotFeedbackAware() {
        // Non-feedback-aware diagnostic: no seed key, no key-match line in the report
        var diagnostic = QueryKeyDiagnostic.withoutFeedback("exact:query", "ExactQueryKeyStrategy");
        var queryEval = new QueryEvaluation(
                "query",
                Set.of("ka_atom"),
                List.of("ka_atom"),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0,
                diagnostic);

        var report = new EvaluationReport(
                List.of(queryEval),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                Map.of(1, 1.0),
                1.0);

        var rendered = report.render();

        assertTrue(rendered.contains("Feedback Aware: false"),
                "Report should indicate feedback is not aware");
        assertFalse(rendered.contains("Feedback Seed Key:"),
                "Non-feedback-aware report must not render a seed key line");
        assertFalse(rendered.contains("Feedback Key Match:"),
                "Non-feedback-aware report must not render a key-match line");
    }
}
