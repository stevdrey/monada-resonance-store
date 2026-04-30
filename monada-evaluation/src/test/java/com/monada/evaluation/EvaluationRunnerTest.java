package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunnerTest {

    @Test
    void runnerCompletesAndProducesReport(@TempDir Path tempDir) {
        EvaluationDataset dataset = DefaultDatabasesDataset.get();
        EvaluationReport report = new EvaluationRunner().run(dataset, tempDir);

        assertNotNull(report);
        assertEquals(dataset.queries().size(), report.queryResults().size(),
                "report must contain one entry per query");

        int maxK = List.of(1, 3, 5).stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (QueryEvaluation qr : report.queryResults()) {
            assertTrue(qr.returnedLabels().size() <= maxK,
                    "returned labels must not exceed maxK for: " + qr.queryText());

            for (Integer k : List.of(1, 3, 5)) {
                Double p = qr.precisionByK().get(k);
                assertNotNull(p, "missing precision for k=" + k);
                assertTrue(p >= 0.0 && p <= 1.0,
                        "precision out of range for k=" + k + ": " + p);
            }
        }

        for (Integer k : List.of(1, 3, 5)) {
            Double avg = report.averagePrecisionByK().get(k);
            assertNotNull(avg);
            assertTrue(avg >= 0.0 && avg <= 1.0,
                    "average precision out of range for k=" + k + ": " + avg);
        }

        String rendered = report.render();
        assertTrue(rendered.contains("Monada Resonance Store Evaluation Report"));
        assertTrue(rendered.contains("Average Precision@1"));
    }

    @Test
    void rendersHumanReadableReport(@TempDir Path tempDir) {
        EvaluationReport report = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), tempDir);
        String rendered = report.render();
        // Sanity check on structure: every query line is present
        for (EvaluationQuery query : DefaultDatabasesDataset.get().queries()) {
            assertTrue(rendered.contains(query.text()),
                    "rendered report missing query: " + query.text());
        }
    }

    @Test
    void customKsAreRespected(@TempDir Path tempDir) {
        EvaluationReport report = new EvaluationRunner(List.of(2, 4))
                .run(DefaultDatabasesDataset.get(), tempDir);
        for (QueryEvaluation qr : report.queryResults()) {
            assertEquals(2, qr.precisionByK().size());
            assertTrue(qr.precisionByK().containsKey(2));
            assertTrue(qr.precisionByK().containsKey(4));
        }
    }

    @Test
    void rejectsDuplicateKs() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationRunner(List.of(1, 1, 3)));
    }
}
