package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalFailureClassifierTest {

    // ----- perfect result → empty Optional -----

    @Test
    void perfectSingleLabelResultReturnsEmpty() {
        var eval = queryEval("cosine similarity", Set.of("ka_cosine"), List.of("ka_cosine", "ka_cache", "ka_index"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertTrue(result.isEmpty(), "Perfect Hit@1 with single label should yield no failure");
    }

    @Test
    void perfectMultiLabelResultReturnsEmpty() {
        var eval = queryEval("search index", Set.of("ka_index", "ka_search"),
                List.of("ka_index", "ka_search", "ka_cache"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertTrue(result.isEmpty(), "Perfect multi-label result at rank 1 should yield no failure");
    }

    // ----- missing expected atom entirely -----

    @Test
    void expectedAtomCompletelyMissingClassifiesAsMissingExpectedAtom() {
        var eval = queryEval("cosine similarity", Set.of("ka_cosine"),
                List.of("ka_cache", "ka_index", "ka_tree", "ka_bloom", "ka_graph"));

        var result = RetrievalFailureClassifier.classify(eval);

        // Query "cosine similarity" shares token "cosine" with label "ka_cosine", so no alias gap.
        assertEquals(RetrievalFailureType.MISSING_EXPECTED_ATOM, result.orElseThrow());
    }

    @Test
    void noTokenOverlapWithLabelClassifiesAsPossibleDatasetAliasGap() {
        // Query "approximate recall" has no tokens matching label "ka_xyz_unknown_concept".
        var eval = queryEval("approximate recall", Set.of("ka_xyz_unknown_concept"),
                List.of("ka_cache", "ka_index", "ka_tree", "ka_bloom", "ka_graph"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertEquals(RetrievalFailureType.POSSIBLE_DATASET_ALIAS_GAP, result.orElseThrow());
    }

    // ----- confusable atom ranked higher -----

    @Test
    void expectedAtomAtRank2ClassifiesAsConfusableAtomRankedHigher() {
        // Expected "ka_cosine" is at index 1 (rank 2), non-expected atom is at rank 1.
        var eval = queryEval("cosine similarity", Set.of("ka_cosine"),
                List.of("ka_cache", "ka_cosine", "ka_index", "ka_tree", "ka_bloom"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertEquals(RetrievalFailureType.CONFUSABLE_ATOM_RANKED_HIGHER, result.orElseThrow());
    }

    // ----- expected atom present but at low rank -----

    @Test
    void expectedAtomAtRank3OrBeyondClassifiesAsLowRank() {
        var eval = queryEval("cosine distance", Set.of("ka_cosine"),
                List.of("ka_cache", "ka_index", "ka_cosine", "ka_tree", "ka_bloom"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertEquals(RetrievalFailureType.EXPECTED_ATOM_PRESENT_BUT_LOW_RANK, result.orElseThrow());
    }

    // ----- multi-relevant recall gap -----

    @Test
    void multiLabelQueryWithPartialRecallClassifiesAsMultiRelevantRecallGap() {
        // Two expected labels; only "ka_index" is in top-5.
        var eval = queryEval("index search storage", Set.of("ka_index", "ka_storage"),
                List.of("ka_index", "ka_cache", "ka_tree", "ka_bloom", "ka_graph"));

        var result = RetrievalFailureClassifier.classify(eval);

        assertEquals(RetrievalFailureType.MULTI_RELEVANT_RECALL_GAP, result.orElseThrow());
    }

    // ----- custom K values -----

    @Test
    void perfectResultWithCustomKSetReturnsEmpty() {
        // EvaluationRunner configured with K={2,4} — keys 1 and 5 are absent.
        // The expected atom is at index 0 (rank 1) in the returned list, so the
        // result is perfect; the classifier must return empty regardless of absent keys.
        var hitByK = new TreeMap<Integer, Double>();
        hitByK.put(2, 1.0);
        hitByK.put(4, 1.0);
        var recallByK = new TreeMap<Integer, Double>();
        recallByK.put(2, 1.0);
        recallByK.put(4, 1.0);

        var result = RetrievalFailureClassifier.classify(
                "cosine similarity",
                Set.of("ka_cosine"),
                List.of("ka_cosine", "ka_cache"),
                hitByK,
                recallByK);

        assertTrue(result.isEmpty(),
                "Perfect result with custom K={2,4} (no key 1 or 5) must yield no failure");
    }

    @Test
    void confusableAtomWithCustomKSetClassifiedCorrectly() {
        // EvaluationRunner configured with K={2,4}.
        // returned[0] is a wrong atom, returned[1] is the expected atom.
        // Hit@2 == 1.0 but the expected atom is NOT first → CONFUSABLE_ATOM_RANKED_HIGHER.
        var hitByK = new TreeMap<Integer, Double>();
        hitByK.put(2, 1.0);
        hitByK.put(4, 1.0);
        var recallByK = new TreeMap<Integer, Double>();
        recallByK.put(2, 1.0);
        recallByK.put(4, 1.0);

        var result = RetrievalFailureClassifier.classify(
                "cosine similarity",
                Set.of("ka_cosine"),
                List.of("ka_cache", "ka_cosine", "ka_index", "ka_tree"),
                hitByK,
                recallByK);

        assertEquals(RetrievalFailureType.CONFUSABLE_ATOM_RANKED_HIGHER, result.orElseThrow(),
                "Expected atom at rank 2 with custom K={2,4} must yield CONFUSABLE_ATOM_RANKED_HIGHER");
    }

    @Test
    void missingAtomWithCustomKSetClassifiesCorrectly() {
        // EvaluationRunner configured with K={2,4} — expected atom completely absent.
        var hitByK = new TreeMap<Integer, Double>();
        hitByK.put(2, 0.0);
        hitByK.put(4, 0.0);
        var recallByK = new TreeMap<Integer, Double>();
        recallByK.put(2, 0.0);
        recallByK.put(4, 0.0);

        var result = RetrievalFailureClassifier.classify(
                "cosine similarity",
                Set.of("ka_cosine"),
                List.of("ka_cache", "ka_index", "ka_tree", "ka_bloom"),
                hitByK,
                recallByK);

        // "cosine" overlaps with "ka_cosine" label token → MISSING_EXPECTED_ATOM, not alias gap.
        assertEquals(RetrievalFailureType.MISSING_EXPECTED_ATOM, result.orElseThrow(),
                "Absent atom with custom K={2,4} must still classify as MISSING_EXPECTED_ATOM");
    }

    // ----- helpers -----

    private static QueryEvaluation queryEval(String text, Set<String> expected, List<String> returned) {
        return new QueryEvaluation(
                text,
                expected,
                returned,
                metricByK(expected, returned, PrecisionAtK::compute),
                metricByK(expected, returned, RecallAtK::compute),
                metricByK(expected, returned, HitAtK::compute),
                ReciprocalRank.compute(expected, returned));
    }

    private static Map<Integer, Double> metricByK(Set<String> expected, List<String> returned, KMetric metric) {
        var map = new TreeMap<Integer, Double>();
        for (int k : EvaluationRunner.DEFAULT_KS) {
            map.put(k, metric.apply(expected, returned, k));
        }
        return map;
    }

    @FunctionalInterface
    private interface KMetric {
        double apply(Set<String> expected, List<String> ranked, int k);
    }
}
