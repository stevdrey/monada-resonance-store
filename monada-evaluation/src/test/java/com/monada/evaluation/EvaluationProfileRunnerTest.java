package com.monada.evaluation;

import com.monada.api.LexicallyEnrichedQueryKeyStrategy;
import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.encoder.LexicalEnrichmentPipeline;
import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EvaluationProfileRunner} using {@link DefaultDatabasesDataset}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The RAW profile runs successfully and produces a valid report.</li>
 *   <li>The LEXICAL_ENRICHED profile runs successfully and produces a valid report.</li>
 *   <li>The comparison detects aggregate change between profiles.</li>
 *   <li>The per-query structure has correct shape.</li>
 *   <li>The existing protected baseline is unaffected (the runner uses its own dirs).</li>
 * </ul>
 */
class EvaluationProfileRunnerTest {

    @Test
    void rawProfileRunsSuccessfully(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW);
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        assertNotNull(comparison);
        assertEquals(1, comparison.profiles().size());
        assertEquals(EvaluationProfile.RAW, comparison.profiles().get(0));
        assertNotNull(comparison.reportByProfile().get(EvaluationProfile.RAW));
        assertEquals(DefaultDatabasesDataset.get().queries().size(), comparison.perQuery().size());
    }

    @Test
    void lexicalEnrichedProfileRunsSuccessfully(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.LEXICAL_ENRICHED);
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        assertNotNull(comparison);
        var report = comparison.reportByProfile().get(EvaluationProfile.LEXICAL_ENRICHED);
        assertNotNull(report);
        assertFalse(report.queryResults().isEmpty());
    }

    @Test
    void feedbackProfileRunsSuccessfully(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK);
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        assertNotNull(comparison);
        var report = comparison.reportByProfile().get(EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK);
        assertNotNull(report);
    }

    @Test
    void twoProfileComparisonProducesCorrectShape(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW, EvaluationProfile.LEXICAL_ENRICHED);
        var dataset = DefaultDatabasesDataset.get();

        var comparison = new EvaluationProfileRunner()
                .run(dataset, profiles, basePath);

        assertEquals(2, comparison.profiles().size());
        assertEquals(dataset.queries().size(), comparison.perQuery().size());
        // Aggregate change map has an entry for each profile.
        assertEquals(2, comparison.aggregateChangeByProfile().size());
        // Base profile always MAINTAINED vs itself.
        assertEquals(RankingChange.MAINTAINED,
                comparison.aggregateChangeByProfile().get(EvaluationProfile.RAW));
    }

    @Test
    void allThreeProfilesRunSuccessfully(@TempDir Path basePath) {
        var profiles = List.of(
                EvaluationProfile.RAW,
                EvaluationProfile.LEXICAL_ENRICHED,
                EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK
        );
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        assertEquals(3, comparison.profiles().size());
        assertEquals(3, comparison.reportByProfile().size());
        assertEquals(3, comparison.aggregateChangeByProfile().size());
    }

    @Test
    void lexicalFeedbackKeyProfileRunsSuccessfullyAndReportsChange(@TempDir Path basePath) {
        var profiles = List.of(
                EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK,
                EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY
        );
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        assertEquals(2, comparison.profiles().size());
        assertNotNull(comparison.reportByProfile().get(
                EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY));
        assertNotNull(comparison.aggregateChangeByProfile().get(
                EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY));
    }

    @Test
    void lexicalFeedbackKeyTransfersFeedbackAcrossDifferentQueryPhrasings(
            @TempDir Path exactDir, @TempDir Path lexicalDir) {
        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("ka_redis",
                                "Redis is an in-memory key-value data store commonly used for caching and fast lookups.",
                                List.of("temporary lookup cache", "fast repeated access", "cache layer")),
                        new DatasetAtom("ka_temporary_lookup",
                                "A temporary lookup store keeps values briefly for later retrieval.")
                ),
                List.of(new EvaluationQuery("temporary lookup store", Set.of("ka_redis")))
        );
        var runner = new EvaluationRunner();

        var exactMemory = MonadaMemory.open(exactDir);
        var exactSeeding = runner.seedAtoms(dataset, exactMemory);
        exactMemory.feedback("temporary lookup", exactSeeding.labelToAtomId().get("ka_redis"), FeedbackSignal.POSITIVE, 1.0);
        var exact = runner.evaluate(dataset, exactMemory, exactSeeding.idToLabel());

        var normalizer = new LexicalEnrichmentPipeline();
        var lexicalOptions = MonadaMemoryOptions.defaults()
                .withFeedbackQueryKeyStrategy(new LexicallyEnrichedQueryKeyStrategy(normalizer));
        var lexicalMemory = MonadaMemory.open(lexicalDir, lexicalOptions);
        var lexicalSeeding = runner.seedAtoms(dataset, lexicalMemory);
        lexicalMemory.feedback("temporary lookup", lexicalSeeding.labelToAtomId().get("ka_redis"), FeedbackSignal.POSITIVE, 1.0);
        var lexical = runner.evaluate(dataset, lexicalMemory, lexicalSeeding.idToLabel());

        var exactLabels = exact.queryResults().getFirst().returnedLabels();
        var lexicalLabels = lexical.queryResults().getFirst().returnedLabels();
        assertEquals("ka_temporary_lookup", exactLabels.getFirst(),
                "exact feedback must not transfer from the seed query to a different evaluated query");
        assertEquals("ka_redis", lexicalLabels.getFirst(),
                "lexical feedback key must transfer feedback across related query phrasings");

        var comparison = new EvaluationComparator().compare(exact, lexical);
        assertEquals(RankingChange.IMPROVED, comparison.aggregate(),
                "lexical feedback transfer should improve the targeted cross-phrasing scenario");
    }

    @Test
    void lexicalProfileMeetsOrExceedsRawOnDefaultDataset(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW, EvaluationProfile.LEXICAL_ENRICHED);
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        var rawReport = comparison.reportByProfile().get(EvaluationProfile.RAW);
        var lexicalReport = comparison.reportByProfile().get(EvaluationProfile.LEXICAL_ENRICHED);

        // Lexical enrichment should not degrade MRR on the default dataset.
        var rawMrr = rawReport.meanReciprocalRank();
        var lexicalMrr = lexicalReport.meanReciprocalRank();
        // Aggregate change must be IMPROVED or MAINTAINED (never DEGRADED).
        var aggregateChange = comparison.aggregateChangeByProfile().get(EvaluationProfile.LEXICAL_ENRICHED);
        assertNotEquals(RankingChange.DEGRADED, aggregateChange,
                "Lexical enrichment must not degrade aggregate metrics vs RAW; MRR: raw="
                        + rawMrr + ", lexical=" + lexicalMrr);
    }

    @Test
    void renderProducesNonEmptyOutput(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW, EvaluationProfile.LEXICAL_ENRICHED);
        var comparison = new EvaluationProfileRunner()
                .run(DefaultDatabasesDataset.get(), profiles, basePath);

        var rendered = comparison.render();
        assertFalse(rendered.isBlank(), "render() must not produce blank output");
        // Basic structural markers.
        assertNotNull(rendered);
        assertTrue(rendered.contains("A/B Evaluation Profile Comparison"));
        assertTrue(rendered.contains("RAW"));
        assertTrue(rendered.contains("LEXICAL_ENRICHED"));
        assertTrue(rendered.contains("Aggregate"));
    }

    @Test
    void emptyProfileListThrows(@TempDir Path basePath) {
        assertThrows(IllegalArgumentException.class, () ->
                new EvaluationProfileRunner().run(DefaultDatabasesDataset.get(), List.of(), basePath));
    }

    // ---- Query-key diagnostic production from real runner execution ----

    @Test
    void rawProfileProducesNonFeedbackAwareDiagnosticsFromRealExecution(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW);
        var dataset = DefaultDatabasesDataset.get();

        var comparison = new EvaluationProfileRunner()
                .run(dataset, profiles, basePath);

        // Every query result must carry a diagnostic for RAW (strategy is ExactQueryKeyStrategy)
        for (var qpr : comparison.perQuery()) {
            var diagnostic = qpr.queryKeyDiagnosticByProfile().get(EvaluationProfile.RAW);
            assertNotNull(diagnostic,
                    "RAW profile must produce a diagnostic for query: " + qpr.queryText());
            assertFalse(diagnostic.feedbackAware(),
                    "RAW diagnostic must have feedbackAware=false");
            assertFalse(diagnostic.hasSeedQueryKey(),
                    "RAW diagnostic must not carry a seed key");
            assertEquals("ExactQueryKeyStrategy", diagnostic.strategyName(),
                    "RAW profile uses ExactQueryKeyStrategy by default");
            // ExactQueryKeyStrategy returns the query text as-is
            assertEquals(qpr.queryText(), diagnostic.queryKey(),
                    "ExactQueryKeyStrategy must produce the query text as the evaluation key");
        }
    }

    @Test
    void feedbackAwareProfileProducesDiagnosticsWithSeedKeyFromRealExecution(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK);
        var dataset = DefaultDatabasesDataset.get();

        var comparison = new EvaluationProfileRunner()
                .run(dataset, profiles, basePath);

        // Every query result must carry a feedback-aware diagnostic
        for (var qpr : comparison.perQuery()) {
            var diagnostic = qpr.queryKeyDiagnosticByProfile().get(EvaluationProfile.LEXICAL_ENRICHED_WITH_FEEDBACK);
            assertNotNull(diagnostic,
                    "Feedback-aware profile must produce a diagnostic for query: " + qpr.queryText());
            assertTrue(diagnostic.feedbackAware(),
                    "Feedback-aware diagnostic must have feedbackAware=true");
            assertTrue(diagnostic.hasSeedQueryKey(),
                    "Feedback-aware diagnostic must carry a seed key");
            // ExactQueryKeyStrategy: evaluation key == query text, seed key == query text → match=true
            assertTrue(diagnostic.feedbackKeyMatch(),
                    "ExactQueryKeyStrategy: evaluation and seed keys must match for: " + qpr.queryText());
        }
    }

    @Test
    void lexicalFeedbackKeyProfileProducesDiagnosticsWithLexicalKeysFromRealExecution(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY);
        var dataset = DefaultDatabasesDataset.get();

        var comparison = new EvaluationProfileRunner()
                .run(dataset, profiles, basePath);

        // Every query result must carry a feedback-aware diagnostic with lexical strategy name
        for (var qpr : comparison.perQuery()) {
            var diagnostic = qpr.queryKeyDiagnosticByProfile().get(EvaluationProfile.LEXICAL_ENRICHED_WITH_LEXICAL_FEEDBACK_KEY);
            assertNotNull(diagnostic,
                    "Lexical feedback-key profile must produce a diagnostic for query: " + qpr.queryText());
            assertTrue(diagnostic.feedbackAware(),
                    "Lexical feedback-key diagnostic must have feedbackAware=true");
            assertEquals("LexicallyEnrichedQueryKeyStrategy", diagnostic.strategyName(),
                    "Profile must report the lexical strategy name");
            assertTrue(diagnostic.hasSeedQueryKey(),
                    "Lexical feedback-key diagnostic must carry a seed key");
            // For the lexical strategy, evaluation key and seed key are derived from the same
            // query text using the same strategy, so they must match
            assertTrue(diagnostic.feedbackKeyMatch(),
                    "Lexical strategy: evaluation and seed keys must match for: " + qpr.queryText());
        }
    }

    @Test
    void twoProfilesProduceDifferentEvaluationQueryKeysForDifferentQueryTexts(@TempDir Path basePath) {
        // Verifies that different query texts produce different evaluation query keys (exact strategy)
        var profiles = List.of(EvaluationProfile.RAW);
        var dataset = DefaultDatabasesDataset.get();
        var queries = dataset.queries();

        // Need at least 2 queries to assert difference
        assertTrue(queries.size() >= 2, "Dataset must have at least 2 queries for this test");

        var comparison = new EvaluationProfileRunner()
                .run(dataset, profiles, basePath);

        var keys = comparison.perQuery().stream()
                .map(qpr -> qpr.queryKeyDiagnosticByProfile().get(EvaluationProfile.RAW))
                .filter(d -> d != null)
                .map(d -> d.queryKey())
                .toList();

        // All keys must be distinct (exact strategy → key == query text)
        var distinctKeys = keys.stream().distinct().toList();
        assertEquals(keys.size(), distinctKeys.size(),
                "ExactQueryKeyStrategy must produce a unique key per distinct query text");
    }
}
