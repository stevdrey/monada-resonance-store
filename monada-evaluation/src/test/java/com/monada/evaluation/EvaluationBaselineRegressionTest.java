package com.monada.evaluation;

import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guards for the default evaluation dataset.
 *
 * <p>These values represent the intentional, protected retrieval-quality
 * baseline for {@link DefaultDatabasesDataset} under the current deterministic
 * encoder and linear-scan resonance index. They are explicit so future changes
 * to encoders, storage, indexing, or ranking cannot silently degrade recall.
 *
 * <p>If a legitimate change alters any of these, update the constants here in
 * the same commit with a rationale.
 */
class EvaluationBaselineRegressionTest {

    /** Average Hit@1 must stay perfect on the default dataset. */
    private static final double EXPECTED_AVG_HIT_AT_1 = 1.0;
    /** Average Recall@3 must stay perfect on the default dataset. */
    private static final double EXPECTED_AVG_RECALL_AT_3 = 1.0;
    /** Average Recall@5 must stay perfect on the default dataset. */
    private static final double EXPECTED_AVG_RECALL_AT_5 = 1.0;
    /** Mean Reciprocal Rank must stay perfect: every query has a relevant label at rank 1. */
    private static final double EXPECTED_MRR = 1.0;

    @Test
    void defaultDatasetMeetsBaselineThresholds(@TempDir Path tempDir) {
        var report = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), tempDir);

        var hit1 = report.averageHitByK().get(1);
        var recall3 = report.averageRecallByK().get(3);
        var recall5 = report.averageRecallByK().get(5);
        var mrr = report.meanReciprocalRank();

        assertEquals(EXPECTED_AVG_HIT_AT_1, hit1,
                "Average Hit@1 baseline changed");
        assertEquals(EXPECTED_AVG_RECALL_AT_3, recall3,
                "Average Recall@3 baseline changed");
        assertEquals(EXPECTED_AVG_RECALL_AT_5, recall5,
                "Average Recall@5 baseline changed");
        assertEquals(EXPECTED_MRR, mrr,
                "Mean Reciprocal Rank baseline changed");
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
