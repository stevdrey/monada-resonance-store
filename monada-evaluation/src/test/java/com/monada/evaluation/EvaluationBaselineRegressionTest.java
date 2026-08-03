package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the default evaluation dataset.
 *
 * <p>The versioned snapshot for {@link DefaultDatabasesDataset} represents the
 * intentional, protected retrieval-quality baseline under the current deterministic
 * encoder and linear-scan resonance index. It is explicit so future changes to
 * encoders, storage, indexing, or ranking cannot silently alter recall.
 *
 * <p>If a legitimate change alters the metrics, update the snapshot in the same
 * commit with a rationale.
 */
class EvaluationBaselineRegressionTest {

    @Test
    void defaultDatasetMatchesVersionedProtectedBaseline(@TempDir Path tempDir) {
        var report = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), tempDir);
        var versionedReport = new VersionedTextEvaluationReport(
                TextEvaluationCatalog.DEFAULT_DATABASES, report);
        var baseline = TextBaselineRegistry.loadDefault()
                .require(TextEvaluationCatalog.DEFAULT_DATABASES);
        var comparison = baseline.compare(versionedReport);

        assertTrue(comparison.passed(), comparison.render());
    }

    @Test
    void rankingIsStableAcrossFreshMemoryDirectories(@TempDir Path firstDir,
                                                     @TempDir Path secondDir) {
        var first = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), firstDir);
        var second = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), secondDir);

        assertEquals(first.queryResults().size(), second.queryResults().size(),
                "query result count must match between fresh runs");

        var firstLabels = new ArrayList<List<String>>();
        var secondLabels = new ArrayList<List<String>>();
        for (var i = 0; i < first.queryResults().size(); i++) {
            var a = first.queryResults().get(i);
            var b = second.queryResults().get(i);
            assertEquals(a.queryText(), b.queryText(),
                    "query order must match between fresh runs");
            firstLabels.add(a.returnedLabels());
            secondLabels.add(b.returnedLabels());
        }
        assertEquals(firstLabels, secondLabels,
                "returned labels must be identical across fresh memory directories");

        assertEquals(first.averagePrecisionByK(), second.averagePrecisionByK());
        assertEquals(first.averageRecallByK(), second.averageRecallByK());
        assertEquals(first.averageHitByK(), second.averageHitByK());
        assertEquals(first.meanReciprocalRank(), second.meanReciprocalRank(),
                "MRR must be identical across fresh memory directories");
    }
}
