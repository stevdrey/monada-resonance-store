package com.monada.evaluation;

import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates dataset integrity and metric sanity for {@link ExpandedTechnologyDataset}.
 *
 * <p>These tests do <b>not</b> lock specific metric values; they only assert structural
 * correctness and that the evaluation completes with valid results.
 */
class ExpandedTechnologyDatasetTest {

    @Test
    void datasetIsNonEmpty() {
        var dataset = ExpandedTechnologyDataset.get();
        assertTrue(dataset.atoms().size() >= 30,
                "expanded dataset must contain at least 30 atoms, got: " + dataset.atoms().size());
        assertTrue(dataset.queries().size() >= 15,
                "expanded dataset must contain at least 15 queries, got: " + dataset.queries().size());
    }

    @Test
    void queryTextsAreUnique() {
        var dataset = ExpandedTechnologyDataset.get();
        var texts = new HashSet<String>();
        for (var query : dataset.queries()) {
            assertTrue(texts.add(query.text()),
                    "duplicate query text: " + query.text());
        }
    }

    @Test
    void evaluationCompletesWithValidMetrics(@TempDir Path tempDir) {
        var dataset = ExpandedTechnologyDataset.get();
        var report = new EvaluationRunner().run(dataset, tempDir);

        assertNotNull(report, "report must not be null");
        assertEquals(dataset.queries().size(), report.queryResults().size(),
                "report must have one result per query");

        for (var qr : report.queryResults()) {
            assertNotNull(qr.queryText());
            assertFalse(qr.returnedLabels().isEmpty(),
                    "returned labels must not be empty for query: " + qr.queryText());

            assertFiniteUnitInterval(qr.reciprocalRank(), "reciprocalRank for: " + qr.queryText());

            for (var entry : qr.precisionByK().entrySet()) {
                assertFiniteUnitInterval(entry.getValue(),
                        "precision@" + entry.getKey() + " for: " + qr.queryText());
            }
            for (var entry : qr.recallByK().entrySet()) {
                assertFiniteUnitInterval(entry.getValue(),
                        "recall@" + entry.getKey() + " for: " + qr.queryText());
            }
            for (var entry : qr.hitByK().entrySet()) {
                assertFiniteUnitInterval(entry.getValue(),
                        "hit@" + entry.getKey() + " for: " + qr.queryText());
            }
        }

        // Aggregate metrics
        for (var entry : report.averagePrecisionByK().entrySet()) {
            assertFiniteUnitInterval(entry.getValue(), "averagePrecision@" + entry.getKey());
        }
        for (var entry : report.averageRecallByK().entrySet()) {
            assertFiniteUnitInterval(entry.getValue(), "averageRecall@" + entry.getKey());
        }
        for (var entry : report.averageHitByK().entrySet()) {
            assertFiniteUnitInterval(entry.getValue(), "averageHit@" + entry.getKey());
        }
        assertFiniteUnitInterval(report.meanReciprocalRank(), "meanReciprocalRank");
    }

    @Test
    void datasetIncludesAllQueryDifficultyTypes() {
        var dataset = ExpandedTechnologyDataset.get();

        var hasMultiRelevant = dataset.queries().stream()
                .anyMatch(q -> q.expectedLabels().size() > 1);
        assertTrue(hasMultiRelevant, "dataset must include at least one multi-relevant query");

        var hasDirect = dataset.queries().stream()
                .anyMatch(q -> q.expectedLabels().size() == 1);
        assertTrue(hasDirect, "dataset must include at least one direct query");

        assertQueryExists(dataset, "graph database for nodes and relationships");
        assertQueryExists(dataset, "graph and document database model");
        assertQueryExists(dataset, "fast memory cache for temporary lookups");
        assertQueryExists(dataset, "similarity search over high dimensional representations");
        assertQueryExists(dataset, "memory recall improved by user signals");
    }

    @Test
    void datasetIncludesAliasesForTargetedLexicalConcepts() {
        var dataset = ExpandedTechnologyDataset.get();

        assertAliasesContain(dataset, "ka_cqrs",
                "command query responsibility segregation",
                "separate read write model",
                "read path write path");
        assertAliasesContain(dataset, "ka_reranking", "second pass model");
        assertAliasesContain(dataset, "ka_caching", "temporary lookup");
        assertAliasesContain(dataset, "ka_redis", "temporary lookup cache");
        assertAliasesContain(dataset, "ka_resonance_recall", "frequency closeness");
    }

    @Test
    void lexicalEnrichmentTargetsParaphraseCases(@TempDir Path tempDir) {
        var report = new EvaluationRunner().run(ExpandedTechnologyDataset.get(), tempDir);

        assertTopKContains(report, "separate read and write paths for optimization", 3, "ka_cqrs");
        assertTopKContains(report, "improving search results order using a second pass model", 3, "ka_reranking");
        assertTopKContains(report, "fast memory cache for temporary lookups", 5, "ka_caching", "ka_redis");
        assertTopKContains(report, "resonance activation retrieving stored knowledge by frequency closeness",
                3, "ka_resonance_recall");
    }

    private static void assertQueryExists(EvaluationDataset dataset, String text) {
        var exists = dataset.queries().stream()
                .anyMatch(q -> q.text().equals(text));
        assertTrue(exists, "dataset must include query: " + text);
    }

    private static void assertAliasesContain(EvaluationDataset dataset, String label, String... aliases) {
        var atom = dataset.atoms().stream()
                .filter(candidate -> candidate.label().equals(label))
                .findFirst()
                .orElseThrow();
        for (var alias : aliases) {
            assertTrue(atom.aliases().contains(alias), label + " must include alias: " + alias);
        }
    }

    private static void assertTopKContains(EvaluationReport report, String query, int k, String... labels) {
        var result = report.queryResults().stream()
                .filter(candidate -> candidate.queryText().equals(query))
                .findFirst()
                .orElseThrow();
        var topK = Set.copyOf(result.returnedLabels().subList(0, Math.min(k, result.returnedLabels().size())));
        for (String label : labels) {
            assertTrue(topK.contains(label), "top " + k + " for query '" + query + "' must contain " + label
                    + " but was " + result.returnedLabels());
        }
    }

    private static void assertFiniteUnitInterval(double value, String label) {
        assertTrue(Double.isFinite(value), label + " must be finite, got: " + value);
        assertTrue(value >= 0.0, label + " must be >= 0.0, got: " + value);
        assertTrue(value <= 1.0, label + " must be <= 1.0, got: " + value);
    }
}
