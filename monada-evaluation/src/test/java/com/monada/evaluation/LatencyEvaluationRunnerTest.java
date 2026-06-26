package com.monada.evaluation;

import com.monada.api.MonadaMemoryOptions;
import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import com.monada.evaluation.datasets.ExpandedTechnologyDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LatencyEvaluationRunner}.
 *
 * <p>Focuses on deterministic structural metrics, ranking equivalence with the
 * baseline {@link EvaluationRunner}, and presence of best-effort timing. No
 * strict wall-clock thresholds are asserted.
 */
class LatencyEvaluationRunnerTest {

    @Test
    void runnerProducesLatencyReportWithStructuralMetrics(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var report = new LatencyEvaluationRunner().run(dataset, tempDir);

        assertNotNull(report);
        assertNotNull(report.evaluationReport());
        assertEquals(dataset.queries().size(), report.queryMetrics().size(),
                "latency metrics must contain one entry per query");

        var corpusSize = dataset.atoms().size();
        var maxK = EvaluationRunner.DEFAULT_KS.stream().mapToInt(Integer::intValue).max().orElseThrow();
        var summary = report.summary();

        assertEquals(dataset.queries().size(), summary.queryCount());
        assertEquals(corpusSize, summary.corpusSize());
        assertEquals(maxK, summary.maxK());
        assertEquals((long) corpusSize * dataset.queries().size(), summary.totalScanned(),
                "total scanned should equal corpusSize * queryCount for the linear scan baseline");
        assertTrue(summary.totalReturned() >= 0,
                "total returned must be non-negative");
        assertTrue(summary.totalElapsedNanos() >= 0,
                "total elapsed must be non-negative");
        assertTrue(summary.minElapsedNanos() >= 0,
                "min elapsed must be non-negative");
        assertTrue(summary.maxElapsedNanos() >= summary.minElapsedNanos(),
                "max elapsed must be >= min elapsed");
        assertTrue(summary.avgElapsedNanos() >= 0,
                "avg elapsed must be non-negative");

        for (var m : report.queryMetrics()) {
            assertEquals(maxK, m.topK());
            assertEquals(corpusSize, m.corpusSize());
            assertEquals(corpusSize, m.scannedCandidates(),
                    "linear scan baseline scores every stored vector");
            assertTrue(m.returnedCandidates() <= maxK,
                    "returned candidates must not exceed maxK");
            assertTrue(m.elapsedNanos() >= 0,
                    "elapsed time must be non-negative");
            assertTrue(m.threshold() <= 0.0,
                    "evaluation threshold must be <= 0");
        }
    }

    @Test
    void rankingMatchesBaselineEvaluationRunner(@TempDir Path tempDir,
                                                  @TempDir Path baselineDir) {
        var dataset = DefaultDatabasesDataset.get();
        var latencyReport = new LatencyEvaluationRunner().run(dataset, tempDir);
        var baselineReport = new EvaluationRunner().run(dataset, baselineDir);

        assertEquals(baselineReport.queryResults().size(), latencyReport.evaluationReport().queryResults().size(),
                "query result count must match baseline");
        for (var i = 0; i < baselineReport.queryResults().size(); i++) {
            var baseline = baselineReport.queryResults().get(i);
            var latency = latencyReport.evaluationReport().queryResults().get(i);
            assertEquals(baseline.queryText(), latency.queryText());
            assertEquals(baseline.returnedLabels(), latency.returnedLabels(),
                    "returned labels must match baseline for: " + baseline.queryText());
            assertEquals(baseline.precisionByK(), latency.precisionByK());
            assertEquals(baseline.recallByK(), latency.recallByK());
            assertEquals(baseline.hitByK(), latency.hitByK());
            assertEquals(baseline.reciprocalRank(), latency.reciprocalRank(), 1e-15);
        }
        assertEquals(baselineReport.averagePrecisionByK(), latencyReport.evaluationReport().averagePrecisionByK());
        assertEquals(baselineReport.averageRecallByK(), latencyReport.evaluationReport().averageRecallByK());
        assertEquals(baselineReport.averageHitByK(), latencyReport.evaluationReport().averageHitByK());
        assertEquals(baselineReport.meanReciprocalRank(), latencyReport.evaluationReport().meanReciprocalRank(), 1e-15);
    }

    @Test
    void expandedDatasetProducesNonTrivialMetrics(@TempDir Path tempDir) {
        var dataset = ExpandedTechnologyDataset.get();
        var report = new LatencyEvaluationRunner().run(dataset, tempDir);

        assertEquals(dataset.queries().size(), report.queryMetrics().size());
        assertEquals(dataset.atoms().size(), report.summary().corpusSize());
        assertTrue(report.summary().totalScanned() > 0,
                "expanded dataset must scan a positive number of candidates");
        assertTrue(report.summary().totalReturned() > 0,
                "expanded dataset must return a positive number of candidates");
    }

    @Test
    void customKsAreRespected(@TempDir Path tempDir) {
        var report = new LatencyEvaluationRunner(new EvaluationRunner(), List.of(2, 4))
                .run(DefaultDatabasesDataset.get(), tempDir);

        assertEquals(4, report.summary().maxK());
        for (var qr : report.evaluationReport().queryResults()) {
            assertEquals(2, qr.precisionByK().size());
            assertTrue(qr.precisionByK().containsKey(2));
            assertTrue(qr.precisionByK().containsKey(4));
        }
        for (var m : report.queryMetrics()) {
            assertEquals(4, m.topK());
        }
    }

    @Test
    void rejectsFeedbackAwareOptions(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var feedbackAwareOptions = MonadaMemoryOptions.defaults();
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEvaluationRunner().run(dataset, tempDir, feedbackAwareOptions),
                "feedbackAwareRanking=true must be rejected because the runner never seeds feedback events");
    }

    @Test
    void stopwordOnlyQueryShortCircuitsWithZeroScanned(@TempDir Path tempDir) {
        var dataset = DefaultDatabasesDataset.get();
        var stopwordOnlyDataset = new EvaluationDataset(
                dataset.atoms(),
                List.of(new EvaluationQuery("the and or", Set.of("ka_orientdb"))));
        var report = new LatencyEvaluationRunner().run(stopwordOnlyDataset, tempDir);

        assertEquals(1, report.queryMetrics().size());
        var m = report.queryMetrics().get(0);
        assertTrue(m.blankAfterNormalization(),
                "stopword-only query must be blank after normalization");
        assertEquals(0, m.scannedCandidates(),
                "stopword-only query must short-circuit with zero scanned candidates");
    }

    @Test
    void rejectsDuplicateKs() {
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEvaluationRunner(new EvaluationRunner(), List.of(1, 1, 3)));
    }

    @Test
    void rejectsNonPositiveKs() {
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyEvaluationRunner(new EvaluationRunner(), List.of(0, 3)));
    }
}
