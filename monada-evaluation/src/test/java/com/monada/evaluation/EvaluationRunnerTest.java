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
        var dataset = DefaultDatabasesDataset.get();
        var report = new EvaluationRunner().run(dataset, tempDir);

        assertNotNull(report);
        assertEquals(dataset.queries().size(), report.queryResults().size(),
                "report must contain one entry per query");

        var maxK = List.of(1, 3, 5).stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (var qr : report.queryResults()) {
            assertTrue(qr.returnedLabels().size() <= maxK,
                    "returned labels must not exceed maxK for: " + qr.queryText());

            for (var k : List.of(1, 3, 5)) {
                var p = qr.precisionByK().get(k);
                assertNotNull(p, "missing precision for k=" + k);
                assertTrue(p >= 0.0 && p <= 1.0,
                        "precision out of range for k=" + k + ": " + p);

                var r = qr.recallByK().get(k);
                assertNotNull(r, "missing recall for k=" + k);
                assertTrue(r >= 0.0 && r <= 1.0,
                        "recall out of range for k=" + k + ": " + r);

                var h = qr.hitByK().get(k);
                assertNotNull(h, "missing hit for k=" + k);
                assertTrue(h == 0.0 || h == 1.0,
                        "hit must be 0 or 1 for k=" + k + ": " + h);
            }

            assertTrue(qr.reciprocalRank() >= 0.0 && qr.reciprocalRank() <= 1.0,
                    "reciprocal rank out of range: " + qr.reciprocalRank());
        }

        for (var k : List.of(1, 3, 5)) {
            var avg = report.averagePrecisionByK().get(k);
            assertNotNull(avg);
            assertTrue(avg >= 0.0 && avg <= 1.0,
                    "average precision out of range for k=" + k + ": " + avg);

            var avgR = report.averageRecallByK().get(k);
            assertNotNull(avgR);
            assertTrue(avgR >= 0.0 && avgR <= 1.0,
                    "average recall out of range for k=" + k + ": " + avgR);

            var avgH = report.averageHitByK().get(k);
            assertNotNull(avgH);
            assertTrue(avgH >= 0.0 && avgH <= 1.0,
                    "average hit out of range for k=" + k + ": " + avgH);
        }

        assertTrue(report.meanReciprocalRank() >= 0.0 && report.meanReciprocalRank() <= 1.0,
                "MRR out of range: " + report.meanReciprocalRank());

        var rendered = report.render();
        assertTrue(rendered.contains("Monada Resonance Store Evaluation Report"));
        assertTrue(rendered.contains("Average Precision@1"));
        assertTrue(rendered.contains("Recall@1"));
        assertTrue(rendered.contains("Hit@1"));
        assertTrue(rendered.contains("Reciprocal Rank"));
        assertTrue(rendered.contains("Average Recall@3"));
        assertTrue(rendered.contains("Average Hit@5"));
        assertTrue(rendered.contains("Mean Reciprocal Rank"));
    }

    @Test
    void rendersHumanReadableReport(@TempDir Path tempDir) {
        var report = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), tempDir);
        var rendered = report.render();
        // Sanity check on structure: every query line is present
        for (var query : DefaultDatabasesDataset.get().queries()) {
            assertTrue(rendered.contains(query.text()),
                    "rendered report missing query: " + query.text());
        }
    }

    @Test
    void customKsAreRespected(@TempDir Path tempDir) {
        var report = new EvaluationRunner(List.of(2, 4))
                .run(DefaultDatabasesDataset.get(), tempDir);
        for (var qr : report.queryResults()) {
            assertEquals(2, qr.precisionByK().size());
            assertTrue(qr.precisionByK().containsKey(2));
            assertTrue(qr.precisionByK().containsKey(4));
            assertEquals(2, qr.recallByK().size());
            assertTrue(qr.recallByK().containsKey(2));
            assertTrue(qr.recallByK().containsKey(4));
            assertEquals(2, qr.hitByK().size());
            assertTrue(qr.hitByK().containsKey(2));
            assertTrue(qr.hitByK().containsKey(4));
        }
    }

    @Test
    void rejectsDuplicateKs() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationRunner(List.of(1, 1, 3)));
    }
}
