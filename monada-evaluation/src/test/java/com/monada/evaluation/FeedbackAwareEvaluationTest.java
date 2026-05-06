package com.monada.evaluation;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.evaluation.datasets.DefaultDatabasesDataset;
import com.monada.storage.feedback.FeedbackSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the retrieval-quality baseline against regressions introduced by
 * the feedback-aware ranking layer, and validates that feedback events can
 * improve or maintain protected metrics without introducing non-determinism.
 */
class FeedbackAwareEvaluationTest {

    @Test
    void baselineProtectedMetricsAreMaintainedOrImprovedWithPositiveFeedback(
            @TempDir Path baselineDir, @TempDir Path feedbackDir) {
        var dataset = DefaultDatabasesDataset.get();
        var baseline = new EvaluationRunner().run(dataset, baselineDir);

        // Seed the feedback directory with the same atoms and reinforce every
        // expected label for every query. Feedback is applied before evaluation
        // so the runner's subsequent resonate() calls observe the updated log.
        var labelToAtomId = loadAtomIds(dataset, feedbackDir);
        try (var memory = open(feedbackDir)) {
            for (EvaluationQuery query : dataset.queries()) {
                for (String expected : query.expectedLabels()) {
                    memory.memory().feedback(query.text(), labelToAtomId.get(expected), FeedbackSignal.POSITIVE);
                }
            }
        }

        var adjusted = new EvaluationRunner().run(dataset, feedbackDir);

        assertGreaterOrEqual(adjusted.averageHitByK().get(1),
                baseline.averageHitByK().get(1), "Average Hit@1");
        assertGreaterOrEqual(adjusted.averageRecallByK().get(3),
                baseline.averageRecallByK().get(3), "Average Recall@3");
        assertGreaterOrEqual(adjusted.averageRecallByK().get(5),
                baseline.averageRecallByK().get(5), "Average Recall@5");
        assertGreaterOrEqual(adjusted.meanReciprocalRank(),
                baseline.meanReciprocalRank(), "Mean Reciprocal Rank");
        // Precision@3 has room to improve on the default dataset; feedback must
        // at least not regress the mean value.
        assertGreaterOrEqual(adjusted.averagePrecisionByK().get(3),
                baseline.averagePrecisionByK().get(3), "Average Precision@3");
    }

    @Test
    void negativeFeedbackDemotesNonRelevantAtomWithoutRegressingBaseline(@TempDir Path dir) {
        var dataset = DefaultDatabasesDataset.get();
        var baseline = new EvaluationRunner().run(dataset, Path.of(dir.toString()));

        // Pick a non-relevant label that ranks above a relevant one for the
        // "sql relational transactions database" query (per the issue baseline,
        // ka_vector_db appears between the expected and other atoms).
        var query = "sql relational transactions database";
        var labelToAtomId = loadAtomIds(dataset, dir);

        var memory = MonadaMemory.open(dir);
        memory.feedback(query, labelToAtomId.get("ka_vector_db"), FeedbackSignal.NEGATIVE);
        memory.feedback(query, labelToAtomId.get("ka_orientdb"), FeedbackSignal.NEGATIVE);

        var adjusted = new EvaluationRunner().run(dataset, dir);

        assertGreaterOrEqual(adjusted.averageHitByK().get(1),
                baseline.averageHitByK().get(1), "Average Hit@1");
        assertGreaterOrEqual(adjusted.meanReciprocalRank(),
                baseline.meanReciprocalRank(), "Mean Reciprocal Rank");

        // The demoted atom must no longer appear before ka_postgresql for that query.
        var adjustedForQuery = adjusted.queryResults().stream()
                .filter(q -> q.queryText().equals(query))
                .findFirst()
                .orElseThrow();
        int relevantRank = adjustedForQuery.returnedLabels().indexOf("ka_postgresql");
        int demotedRank = adjustedForQuery.returnedLabels().indexOf("ka_vector_db");
        assertTrue(relevantRank >= 0, "relevant atom must still be returned");
        assertTrue(demotedRank == -1 || demotedRank > relevantRank,
                "ka_vector_db must be demoted below ka_postgresql after negative feedback");
    }

    @Test
    void noFeedbackEvaluationMatchesProtectedBaseline(
            @TempDir Path baselineDir, @TempDir Path noFeedbackDir) {
        var dataset = DefaultDatabasesDataset.get();

        // Two independent runs over the default dataset, neither one applies any
        // feedback events. The feedback-aware decorator must behave like a no-op
        // at evaluation level: identical labels, identical metrics, MAINTAINED
        // classification, and the protected baseline values.
        var baseline = new EvaluationRunner().run(dataset, baselineDir);
        var noFeedback = new EvaluationRunner().run(dataset, noFeedbackDir);

        var comparison = new EvaluationComparator().compare(baseline, noFeedback);
        assertEquals(RankingChange.MAINTAINED, comparison.aggregate(),
                "wrapping with FeedbackAwareResonanceIndex without feedback events must not change protected metrics");
        for (QueryRankingComparison perQuery : comparison.perQuery()) {
            assertEquals(RankingChange.MAINTAINED, perQuery.change(),
                    "per-query ranking must not change for query: " + perQuery.queryText());
            assertEquals(perQuery.beforeLabels(), perQuery.afterLabels(),
                    "returned labels must be identical for query: " + perQuery.queryText());
        }

        assertEquals(1.0, noFeedback.averageHitByK().get(1), 1e-15);
        assertEquals(1.0, noFeedback.averageRecallByK().get(3), 1e-15);
        assertEquals(1.0, noFeedback.averageRecallByK().get(5), 1e-15);
        assertEquals(1.0, noFeedback.meanReciprocalRank(), 1e-15);
    }

    @Test
    void positiveFeedbackComparisonClassifiesAsImprovedOrMaintained(
            @TempDir Path baselineDir, @TempDir Path feedbackDir) {
        var dataset = DefaultDatabasesDataset.get();
        var baseline = new EvaluationRunner().run(dataset, baselineDir);

        var labelToAtomId = loadAtomIds(dataset, feedbackDir);
        try (var memory = open(feedbackDir)) {
            for (EvaluationQuery query : dataset.queries()) {
                for (String expected : query.expectedLabels()) {
                    memory.memory().feedback(query.text(), labelToAtomId.get(expected), FeedbackSignal.POSITIVE);
                }
            }
        }
        var adjusted = new EvaluationRunner().run(dataset, feedbackDir);

        var comparison = new EvaluationComparator().compare(baseline, adjusted);
        assertTrue(comparison.aggregate() != RankingChange.DEGRADED,
                "positive feedback must not degrade protected metrics, got: " + comparison.aggregate());
        for (QueryRankingComparison perQuery : comparison.perQuery()) {
            assertTrue(perQuery.change() != RankingChange.DEGRADED,
                    "positive feedback must not degrade query '" + perQuery.queryText()
                            + "', got: " + perQuery.change());
        }
    }

    @Test
    void negativeFeedbackComparisonDoesNotDegradeProtectedMetrics(@TempDir Path dir) {
        var dataset = DefaultDatabasesDataset.get();
        var baseline = new EvaluationRunner().run(dataset, dir);

        // Apply negative feedback to ka_vector_db for the sql query so the
        // adjusted ranking demotes a non-relevant atom for that query.
        var query = "sql relational transactions database";
        var labelToAtomId = loadAtomIds(dataset, dir);
        var memory = MonadaMemory.open(dir);
        memory.feedback(query, labelToAtomId.get("ka_vector_db"), FeedbackSignal.NEGATIVE);

        var adjusted = new EvaluationRunner().run(dataset, dir);

        var comparison = new EvaluationComparator().compare(baseline, adjusted);
        assertTrue(comparison.aggregate() != RankingChange.DEGRADED,
                "negative feedback against a non-relevant atom must not degrade protected metrics, got: "
                        + comparison.aggregate());

        // The per-query comparison for the affected query must still classify
        // as not degraded (the relevant atom was already at rank 0).
        var affected = comparison.perQuery().stream()
                .filter(p -> p.queryText().equals(query))
                .findFirst()
                .orElseThrow();
        assertTrue(affected.change() != RankingChange.DEGRADED,
                "demoting a non-relevant atom must not degrade the affected query, got: "
                        + affected.change());

        // The demoted atom must drop in rank or disappear from the returned labels.
        int beforeRank = affected.beforeLabels().indexOf("ka_vector_db");
        int afterRank = affected.afterLabels().indexOf("ka_vector_db");
        assertTrue(beforeRank >= 0, "precondition: ka_vector_db should appear in baseline ranking");
        assertTrue(afterRank == -1 || afterRank > beforeRank,
                "ka_vector_db must drop in rank after negative feedback");
    }

    @Test
    void feedbackForOneQueryDoesNotAffectUnrelatedQueries(
            @TempDir Path baselineDir, @TempDir Path feedbackDir) {
        var dataset = DefaultDatabasesDataset.get();
        var baseline = new EvaluationRunner().run(dataset, baselineDir);

        var targetQuery = "database with graph and document model";
        var labelToAtomId = loadAtomIds(dataset, feedbackDir);
        var memory = MonadaMemory.open(feedbackDir);
        // Feedback applied only to the target query.
        memory.feedback(targetQuery, labelToAtomId.get("ka_arangodb"), FeedbackSignal.POSITIVE);
        memory.feedback(targetQuery, labelToAtomId.get("ka_orientdb"), FeedbackSignal.POSITIVE);

        var adjusted = new EvaluationRunner().run(dataset, feedbackDir);
        var comparison = new EvaluationComparator().compare(baseline, adjusted);

        for (int i = 0; i < baseline.queryResults().size(); i++) {
            var b = baseline.queryResults().get(i);
            var a = adjusted.queryResults().get(i);
            if (b.queryText().equals(targetQuery)) {
                continue;
            }
            assertEquals(b.returnedLabels(), a.returnedLabels(),
                    "unrelated query must not change: " + b.queryText());
            assertEquals(b.reciprocalRank(), a.reciprocalRank(), 1e-15,
                    "unrelated query reciprocal rank must not change: " + b.queryText());
            assertEquals(b.hitByK(), a.hitByK(),
                    "unrelated query Hit@K must not change: " + b.queryText());
            assertEquals(b.recallByK(), a.recallByK(),
                    "unrelated query Recall@K must not change: " + b.queryText());

            var perQuery = comparison.perQuery().get(i);
            assertEquals(RankingChange.MAINTAINED, perQuery.change(),
                    "unrelated query must classify as MAINTAINED: " + perQuery.queryText());
        }
    }

    @Test
    void feedbackRankingIsDeterministicAcrossFreshDirectories(
            @TempDir Path firstDir, @TempDir Path secondDir) {
        var dataset = DefaultDatabasesDataset.get();

        for (Path dir : List.of(firstDir, secondDir)) {
            var labelToAtomId = loadAtomIds(dataset, dir);
            var memory = MonadaMemory.open(dir);
            // Same events, same order.
            memory.feedback("database with graph and document model",
                    labelToAtomId.get("ka_arangodb"), FeedbackSignal.POSITIVE);
            memory.feedback("database with graph and document model",
                    labelToAtomId.get("ka_orientdb"), FeedbackSignal.POSITIVE);
            memory.feedback("sql relational transactions database",
                    labelToAtomId.get("ka_vector_db"), FeedbackSignal.NEGATIVE);
        }

        var first = new EvaluationRunner().run(dataset, firstDir);
        var second = new EvaluationRunner().run(dataset, secondDir);

        var firstLabels = new ArrayList<List<String>>();
        var secondLabels = new ArrayList<List<String>>();
        for (int i = 0; i < first.queryResults().size(); i++) {
            firstLabels.add(first.queryResults().get(i).returnedLabels());
            secondLabels.add(second.queryResults().get(i).returnedLabels());
        }
        assertEquals(firstLabels, secondLabels,
                "returned labels must be identical across fresh memory directories");
        assertEquals(first.averagePrecisionByK(), second.averagePrecisionByK());
        assertEquals(first.averageRecallByK(), second.averageRecallByK());
        assertEquals(first.averageHitByK(), second.averageHitByK());
        assertEquals(first.meanReciprocalRank(), second.meanReciprocalRank());
    }

    // ----- helpers -----

    private static void assertGreaterOrEqual(double actual, double expected, String label) {
        assertTrue(actual >= expected - 1e-12,
                label + " regressed: expected >= " + expected + " but got " + actual);
    }

    private static Map<String, String> loadAtomIds(EvaluationDataset dataset, Path dir) {
        // Remember each atom in the directory and map label -> stored atom id.
        // remember(...) is idempotent, so this is safe to call before the runner.
        var memory = MonadaMemory.open(dir);
        var map = new HashMap<String, String>();
        for (DatasetAtom atom : dataset.atoms()) {
            KnowledgeAtom stored = memory.remember(atom.content());
            map.put(atom.label(), stored.id());
        }
        return Map.copyOf(map);
    }

    /**
     * Small RAII wrapper so tests can scope a MonadaMemory usage without
     * leaking per-call open() calls through the code. MonadaMemory itself is
     * stateless across opens because files are the source of truth.
     */
    private static ScopedMemory open(Path dir) {
        return new ScopedMemory(MonadaMemory.open(dir));
    }

    private record ScopedMemory(MonadaMemory memory) implements AutoCloseable {
        @Override
        public void close() {
            // MonadaMemory does not hold long-lived resources in v1; nothing to close.
        }
    }
}
