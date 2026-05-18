package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile isolation regression tests for Issue #22.
 *
 * <p>Before the fix, {@link EvaluationProfileRunner} delegated query execution to
 * {@link EvaluationRunner#run(EvaluationDataset, Path)}, which reopened memory
 * with default {@link MonadaMemoryOptions}. That silently forced every profile
 * (including {@link EvaluationProfile#RAW}) to use
 * {@code LexicalEnrichmentPipeline} for queries and feedback-aware ranking.
 *
 * <p>These tests prove that each profile is now applied end-to-end to both atom
 * encoding and query execution.
 */
class EvaluationProfileIsolationTest {

    /**
     * Synonym-required query: the only way to retrieve {@code ka_cache} from the
     * query {@code "temporary lookup"} is via the synonym mapping
     * {@code temporary lookup=cache,caching,redis} declared in
     * {@code monada-encoder/.../en/synonyms.properties}.
     *
     * <p>If RAW is truly applied at query time, the no-op normalizer cannot
     * expand the synonym and {@code ka_cache} must NOT be at rank 1.
     * LEXICAL_ENRICHED applies the synonym expansion and must rank
     * {@code ka_cache} at rank 1.
     */
    @Test
    void rawProfileDoesNotApplyLexicalEnrichmentAtQueryTime(@TempDir Path basePath) {
        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("ka_cache",
                                "cache caching redis store fast in memory retrieval"),
                        new DatasetAtom("ka_tree",
                                "binary search tree sorted key lookup ordered traversal")
                ),
                List.of(
                        new EvaluationQuery("temporary lookup", Set.of("ka_cache"))
                )
        );

        var profiles = List.of(EvaluationProfile.RAW, EvaluationProfile.LEXICAL_ENRICHED);
        var comparison = new EvaluationProfileRunner().run(dataset, profiles, basePath);

        var rawReport = comparison.reportByProfile().get(EvaluationProfile.RAW);
        var enrichedReport = comparison.reportByProfile().get(EvaluationProfile.LEXICAL_ENRICHED);

        var rawHit1 = rawReport.queryResults().get(0).hitByK().get(1);
        var enrichedHit1 = enrichedReport.queryResults().get(0).hitByK().get(1);

        assertEquals(0.0, rawHit1,
                "RAW must not retrieve ka_cache at rank 1 because the no-op "
                        + "normalizer cannot expand the 'temporary lookup' synonym. "
                        + "If this fails, query execution is silently using "
                        + "LexicalEnrichmentPipeline instead of the RAW profile's "
                        + "NoOpTextNormalizer.");
        assertEquals(1.0, enrichedHit1,
                "LEXICAL_ENRICHED must retrieve ka_cache at rank 1 via the "
                        + "'temporary lookup' synonym expansion.");

        // The aggregate ranking must reflect a genuine difference between profiles.
        assertNotEquals(
                rawReport.queryResults().get(0).returnedLabels(),
                enrichedReport.queryResults().get(0).returnedLabels(),
                "RAW and LEXICAL_ENRICHED must produce different ranked labels "
                        + "for a synonym-required query; if equal, profile "
                        + "isolation at query time is broken.");
    }

    /**
     * When the {@link EvaluationProfile#RAW} profile is used, feedback-aware
     * ranking must be fully disabled at query time: even a large positive delta
     * seeded for the weaker atom must not change the top-1 result.
     *
     * <p>Opens a memory with {@code RAW} options directly, queries before and
     * after seeding a {@code POSITIVE} feedback event with a delta large enough
     * to flip ranking if {@link com.monada.index.FeedbackAwareResonanceIndex}
     * were applied, and asserts that the top-ranked atom is the same.
     */
    @Test
    void rawProfileQueriesAreIndependentOfFeedbackAwareRanking(@TempDir Path basePath) {
        var query = "append only log immutable";
        var memory = MonadaMemory.open(basePath, EvaluationProfile.RAW.toMemoryOptions());

        var atomA = memory.remember("append only log storage immutable sequence");
        var atomB = memory.remember("binary search tree sorted key lookup");

        var before = memory.resonate(query).topK(2).threshold(Double.NEGATIVE_INFINITY).execute();
        assertTrue(before.results().size() >= 1,
                "RAW profile must produce at least one result for a matching query.");
        var topIdBefore = before.results().get(0).atom().id();

        // Seed a large positive feedback for the weaker atom (atomB). If
        // FeedbackAwareResonanceIndex were active, this would boost atomB's
        // score by 0.9 and could flip the ranking.
        memory.feedback(query, atomB.id(), FeedbackSignal.POSITIVE, 0.9);

        var after = memory.resonate(query).topK(2).threshold(Double.NEGATIVE_INFINITY).execute();
        var topIdAfter = after.results().get(0).atom().id();

        assertEquals(topIdBefore, topIdAfter,
                "RAW profile must not apply feedback-aware ranking: top-1 must remain "
                        + "the same atom before and after seeding positive feedback for the "
                        + "weaker atom. If this fails, FeedbackAwareResonanceIndex is being "
                        + "applied even though feedbackAware=false.");
        assertEquals(atomA.id(), topIdAfter,
                "The strongly matching atom (append/log/immutable tokens) must remain top-1 "
                        + "regardless of the feedback boost on the weaker atom.");
    }

    /**
     * The default {@link EvaluationRunner#run(EvaluationDataset, Path)} entry
     * point must remain equivalent to the explicit
     * {@code run(dataset, path, MonadaMemoryOptions.defaults())} overload.
     */
    @Test
    void defaultRunEqualsExplicitDefaultsOverload(@TempDir Path defaultDir,
                                                  @TempDir Path explicitDir) {
        var dataset = DefaultDatabasesDataset.get();
        var runner = new EvaluationRunner();

        var defaultReport = runner.run(dataset, defaultDir);
        var explicitReport = runner.run(dataset, explicitDir, MonadaMemoryOptions.defaults());

        assertEquals(defaultReport.queryResults().size(), explicitReport.queryResults().size());
        for (var i = 0; i < defaultReport.queryResults().size(); i++) {
            var a = defaultReport.queryResults().get(i);
            var b = explicitReport.queryResults().get(i);
            assertEquals(a.queryText(), b.queryText());
            assertEquals(a.returnedLabels(), b.returnedLabels(),
                    "default run() and run(..., defaults()) must produce "
                            + "identical ranked labels for query: " + a.queryText());
            assertEquals(a.reciprocalRank(), b.reciprocalRank(), 1e-12);
        }
        assertEquals(defaultReport.meanReciprocalRank(),
                explicitReport.meanReciprocalRank(), 1e-12);
    }
}
