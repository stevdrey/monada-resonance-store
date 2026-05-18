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
