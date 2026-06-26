package com.monada.evaluation;

import com.monada.evaluation.datasets.ProjectMemoryDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates dataset integrity and metric sanity for {@link ProjectMemoryDataset}.
 *
 * <p>These tests do not lock specific metric values; they only assert structural
 * correctness and that the evaluation completes with valid results.
 */
class ProjectMemoryDatasetTest {

    @Test
    void datasetIsNonEmpty() {
        var dataset = ProjectMemoryDataset.get();
        assertTrue(dataset.atoms().size() >= 20,
                "project memory dataset must contain at least 20 atoms, got: " + dataset.atoms().size());
        assertTrue(dataset.queries().size() >= 10,
                "project memory dataset must contain at least 10 queries, got: " + dataset.queries().size());
    }

    @Test
    void labelsAreUnique() {
        var dataset = ProjectMemoryDataset.get();
        var labels = new HashSet<String>();
        for (var atom : dataset.atoms()) {
            assertTrue(labels.add(atom.label()),
                    "duplicate atom label: " + atom.label());
        }
        assertEquals(dataset.atoms().size(), labels.size(),
                "every atom must have a unique label");
    }

    @Test
    void queryTextsAreUnique() {
        var dataset = ProjectMemoryDataset.get();
        var texts = new HashSet<String>();
        for (var query : dataset.queries()) {
            assertTrue(texts.add(query.text()),
                    "duplicate query text: " + query.text());
        }
    }

    @Test
    void expectedLabelsReferenceExistingAtoms() {
        var dataset = ProjectMemoryDataset.get();
        var labels = new HashSet<String>();
        for (var atom : dataset.atoms()) {
            labels.add(atom.label());
        }
        for (var query : dataset.queries()) {
            for (var expected : query.expectedLabels()) {
                assertTrue(labels.contains(expected),
                        "query '" + query.text() + "' references unknown atom label: " + expected);
            }
        }
    }

    @Test
    void querySetIncludesAllDifficultyTypes() {
        var dataset = ProjectMemoryDataset.get();

        var hasDirect = dataset.queries().stream()
                .anyMatch(q -> q.expectedLabels().size() == 1);
        assertTrue(hasDirect, "dataset must include at least one direct query");

        var hasMultiRelevant = dataset.queries().stream()
                .anyMatch(q -> q.expectedLabels().size() > 1);
        assertTrue(hasMultiRelevant, "dataset must include at least one multi-relevant query");

        assertQueryExistsForAtom(dataset, "ka_module_encoder");
        assertQueryExistsForAtom(dataset, "ka_text_recall_flow");
        assertQueryExistsForAtom(dataset, "ka_feedback_aware_ranking_flow");
        assertQueryExistsForAtom(dataset, "ka_module_learning");
        assertQueryExistsForAtom(dataset, "ka_roadmap_phase_p");
        assertQueryExistsForAtom(dataset, "ka_project_non_goals");
        assertQueryExistsForAtom(dataset, "ka_agent_spec_context");
    }

    @Test
    void evaluationCompletesWithValidMetrics(@TempDir Path tempDir) {
        var dataset = ProjectMemoryDataset.get();
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
    void datasetIncludesProjectSpecificAliases() {
        var dataset = ProjectMemoryDataset.get();

        assertAliasesContain(dataset, "ka_project_purpose",
                "memory layer for Monada Neuron", "embedded resonance memory");
        assertAliasesContain(dataset, "ka_module_encoder",
                "text normalization module", "deterministic encoder module");
        assertAliasesContain(dataset, "ka_module_storage",
                "persistence module", "manifest and log storage");
        assertAliasesContain(dataset, "ka_feedback_aware_ranking_flow",
                "feedback ranking pipeline", "feedback adjusted scores");
        assertAliasesContain(dataset, "ka_speech_extension",
                "speech retrieval architecture", "acoustic recall extension");
        assertAliasesContain(dataset, "ka_roadmap_phase_p",
                "speech evaluation phase", "Phase P");
    }

    private static void assertQueryExistsForAtom(EvaluationDataset dataset, String label) {
        var exists = dataset.queries().stream()
                .anyMatch(q -> q.expectedLabels().contains(label));
        assertTrue(exists, "dataset must include a query that expects atom: " + label);
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

    private static void assertFiniteUnitInterval(double value, String label) {
        assertTrue(Double.isFinite(value), label + " must be finite, got: " + value);
        assertTrue(value >= 0.0, label + " must be >= 0.0, got: " + value);
        assertTrue(value <= 1.0, label + " must be <= 1.0, got: " + value);
    }
}
