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
 * <p>These thresholds represent the intentional, protected retrieval-quality
 * baseline for {@link DefaultDatabasesDataset} under the current deterministic
 * encoder and linear-scan resonance index. They are explicit so future changes
 * to encoders, storage, indexing, or ranking cannot silently degrade recall.
 *
 * <p>If a legitimate change requires lowering any of these, update the
 * constants here in the same commit with a rationale.
 */
class EvaluationBaselineRegressionTest {

    /** Average Hit@1 must stay perfect on the default dataset. */
    private static final double MIN_AVG_HIT_AT_1 = 1.0;
    /** Average Recall@3 must stay perfect on the default dataset. */
    private static final double MIN_AVG_RECALL_AT_3 = 1.0;
    /** Average Recall@5 must stay perfect on the default dataset. */
    private static final double MIN_AVG_RECALL_AT_5 = 1.0;
    /** Mean Reciprocal Rank must stay perfect on the default dataset. */
    private static final double MIN_MRR = 1.0;

    @Test
    void defaultDatasetMeetsBaselineThresholds(@TempDir Path tempDir) {
        var report = new EvaluationRunner()
                .run(DefaultDatabasesDataset.get(), tempDir);

        var hit1 = report.averageHitByK().get(1);
        var recall3 = report.averageRecallByK().get(3);
        var recall5 = report.averageRecallByK().get(5);
        var mrr = report.meanReciprocalRank();

        assertTrue(hit1 >= MIN_AVG_HIT_AT_1,
                "Average Hit@1 regressed: " + hit1 + " < " + MIN_AVG_HIT_AT_1);
        assertTrue(recall3 >= MIN_AVG_RECALL_AT_3,
                "Average Recall@3 regressed: " + recall3 + " < " + MIN_AVG_RECALL_AT_3);
        assertTrue(recall5 >= MIN_AVG_RECALL_AT_5,
                "Average Recall@5 regressed: " + recall5 + " < " + MIN_AVG_RECALL_AT_5);
        assertTrue(mrr >= MIN_MRR,
                "Mean Reciprocal Rank regressed: " + mrr + " < " + MIN_MRR);
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
