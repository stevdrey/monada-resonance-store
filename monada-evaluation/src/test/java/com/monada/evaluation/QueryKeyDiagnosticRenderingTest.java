package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void profileComparisonRendersQueryKeyDiagnostics() {
        var exactDiagnostic = QueryKeyDiagnostic.withoutFeedback(
                "exact:temporary lookup store", "ExactQueryKeyStrategy");
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
                Map.of(rawProfile, exactDiagnostic, lexicalProfile, lexicalDiagnostic));

        // Create minimal reports for each profile (required by EvaluationProfileComparison validation)
        var rawReport = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);
        var lexicalReport = new EvaluationReport(List.of(), Map.of(), Map.of(), Map.of(), 0.0);

        var comparison = new EvaluationProfileComparison(
                List.of(rawProfile, lexicalProfile),
                Map.of(rawProfile, rawReport, lexicalProfile, lexicalReport),
                List.of(queryResult),
                Map.of(lexicalProfile, RankingChange.IMPROVED));

        var rendered = comparison.render();

        // Verify RAW profile diagnostics
        assertTrue(rendered.contains("[RAW] Feedback Aware: false"),
                "Comparison should show RAW is not feedback-aware");
        assertTrue(rendered.contains("[RAW] Query Key Strategy: ExactQueryKeyStrategy"),
                "Comparison should show RAW uses exact strategy");
        assertTrue(rendered.contains("[RAW] Evaluation Query Key: exact:temporary lookup store"),
                "Comparison should show RAW query key");

        // Verify LEXICAL profile diagnostics
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Aware: true"),
                "Comparison should show LEXICAL is feedback-aware");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Query Key Strategy: LexicallyEnrichedQueryKeyStrategy"),
                "Comparison should show LEXICAL uses lexical strategy");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Evaluation Query Key: lexical-expansion:"),
                "Comparison should show LEXICAL query key");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Seed Key:"),
                "Comparison should show LEXICAL seed key");
        assertTrue(rendered.contains("[LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY] Feedback Key Match:"),
                "Comparison should show LEXICAL key match status");
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
}
