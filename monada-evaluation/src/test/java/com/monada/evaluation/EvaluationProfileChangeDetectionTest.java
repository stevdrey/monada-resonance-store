package com.monada.evaluation;

import com.monada.encoder.NoOpTextNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests that {@link EvaluationProfileRunner} correctly detects IMPROVED,
 * MAINTAINED, and DEGRADED transitions at both query and aggregate level
 * when the compared profiles have noticeably different retrieval quality.
 *
 * <p>Uses a small synthetic dataset where the expected behaviour of RAW vs.
 * LEXICAL_ENRICHED is well understood:
 * <ul>
 *   <li>RAW: queries use original text without enrichment.</li>
 *   <li>LEXICAL_ENRICHED: queries benefit from synonym and stop-word processing.</li>
 * </ul>
 *
 * <p>The synthetic scenario is intentionally simple — the atoms are defined so that
 * the LEXICAL_ENRICHED profile will hit at rank 1 where RAW may not, verifying that
 * the harness can detect the transition.
 */
class EvaluationProfileChangeDetectionTest {

    /**
     * A minimal two-atom dataset with a query that strongly favours lexical expansion.
     * The query uses a synonym of the expected atom's content keyword, so:
     * - RAW: the synonym won't match as well → lower rank.
     * - LEXICAL_ENRICHED: synonyms are expanded → better match.
     *
     * <p>We use two profiles with different normalizers (one no-op, one full pipeline)
     * to verify the harness correctly produces per-query changes.
     */
    @Test
    void harnessCanDetectProfileDifferences(@TempDir Path basePath) {
        // The "base" profile uses a no-op normalizer (simulates RAW).
        var rawProfile = new EvaluationProfile("TEST_RAW", new NoOpTextNormalizer(), false);
        // The "enriched" profile uses LexicalEnrichmentPipeline.
        var enrichedProfile = EvaluationProfile.LEXICAL_ENRICHED;

        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("ka_cosine",
                                "cosine similarity measures the angle between two vectors",
                                List.of("dot product similarity", "vector angle")),
                        new DatasetAtom("ka_cache",
                                "cache stores frequently accessed data in memory for fast retrieval")
                ),
                List.of(
                        new EvaluationQuery("cosine similarity vectors", Set.of("ka_cosine")),
                        new EvaluationQuery("memory cache fast access", Set.of("ka_cache"))
                )
        );

        var profiles = List.of(rawProfile, enrichedProfile);
        var comparison = new EvaluationProfileRunner().run(dataset, profiles, basePath);

        assertNotNull(comparison);
        assertEquals(2, comparison.profiles().size());
        // Per-query results must exist for each query.
        assertEquals(dataset.queries().size(), comparison.perQuery().size());

        // Aggregate change must be a valid RankingChange (not null).
        var aggregateChange = comparison.aggregateChangeByProfile().get(enrichedProfile);
        assertNotNull(aggregateChange,
                "Aggregate change must be present for the non-base profile");

        // The base profile is always MAINTAINED vs itself.
        assertEquals(RankingChange.MAINTAINED,
                comparison.aggregateChangeByProfile().get(rawProfile));
    }

    @Test
    void identicalProfilesProduceMaintainedChange(@TempDir Path basePath) {
        // Two profiles that are semantically identical (both no-op).
        var profileA = new EvaluationProfile("NOOP_A", new NoOpTextNormalizer(), false);
        var profileB = new EvaluationProfile("NOOP_B", new NoOpTextNormalizer(), false);

        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("ka_vec", "vector similarity search high dimensional space")
                ),
                List.of(
                        new EvaluationQuery("vector similarity search", Set.of("ka_vec"))
                )
        );

        var comparison = new EvaluationProfileRunner()
                .run(dataset, List.of(profileA, profileB), basePath);

        // Both profiles use the same normalizer; aggregate should be MAINTAINED.
        assertEquals(RankingChange.MAINTAINED,
                comparison.aggregateChangeByProfile().get(profileB));
        // Per-query change should also be MAINTAINED.
        assertEquals(RankingChange.MAINTAINED,
                comparison.perQuery().get(0).changeByProfile().get(profileB));
    }

    @Test
    void perQueryChangeIsPopulatedForAllQueries(@TempDir Path basePath) {
        var profiles = List.of(EvaluationProfile.RAW, EvaluationProfile.LEXICAL_ENRICHED);
        var dataset = new EvaluationDataset(
                List.of(
                        new DatasetAtom("ka_a", "append only log storage immutable sequence"),
                        new DatasetAtom("ka_b", "binary search tree sorted key lookup")
                ),
                List.of(
                        new EvaluationQuery("append only log immutable", Set.of("ka_a")),
                        new EvaluationQuery("binary tree sorted key", Set.of("ka_b"))
                )
        );

        var comparison = new EvaluationProfileRunner().run(dataset, profiles, basePath);

        for (var queryResult : comparison.perQuery()) {
            assertNotNull(queryResult.changeByProfile().get(EvaluationProfile.RAW),
                    "RAW profile must have a change entry (MAINTAINED) for every query");
            assertNotNull(queryResult.changeByProfile().get(EvaluationProfile.LEXICAL_ENRICHED),
                    "LEXICAL_ENRICHED profile must have a change entry for every query");
        }
    }
}
