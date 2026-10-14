package com.monada.index;

import com.monada.core.AtomType;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactBoundedTopKSelectorTest {

    private static KnowledgeAtom atom(String id) {
        return new KnowledgeAtom(id, AtomType.TEXT, "content-" + id, Map.of(), 1.0,
                Instant.parse("2024-01-01T00:00:00Z"));
    }

    private static ResonanceResult result(String id, double score) {
        return new ResonanceResult(atom(id), score);
    }

    /** The full-sort oracle, written exactly as the production linear scan does it. */
    private static List<ResonanceResult> oracle(List<ResonanceResult> candidates, int topK) {
        var results = new ArrayList<>(candidates);
        results.sort(java.util.Comparator.comparingDouble(ResonanceResult::score).reversed()
                .thenComparing(r -> r.atom().id()));
        if (results.size() > topK) {
            return List.copyOf(results.subList(0, topK));
        }
        return List.copyOf(results);
    }

    private static void assertMatchesOracle(List<ResonanceResult> candidates, int topK) {
        var expected = oracle(candidates, topK);
        var selection = ExactBoundedTopKSelector.select(candidates, topK);
        var actual = selection.results();
        assertEquals(expected.size(), actual.size(), "size for K=" + topK);
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).atom().id(), actual.get(i).atom().id(), "id at " + i + " for K=" + topK);
            assertEquals(Double.doubleToLongBits(expected.get(i).score()),
                    Double.doubleToLongBits(actual.get(i).score()), "score bits at " + i + " for K=" + topK);
        }
        assertEquals(candidates.size(), selection.offered());
        assertTrue(selection.peakRetained() <= Math.min(topK, candidates.size()),
                "retained " + selection.peakRetained() + " above min(K, M)");
    }

    @Test
    void rejectsNonPositiveTopK() {
        assertThrows(IllegalArgumentException.class, () -> ExactBoundedTopKSelector.select(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> ExactBoundedTopKSelector.select(List.of(), -1));
    }

    @Test
    void emptyCandidatesYieldEmptySelection() {
        var selection = ExactBoundedTopKSelector.select(List.of(), 5);
        assertEquals(List.of(), selection.results());
        assertEquals(0, selection.offered());
        assertEquals(0, selection.peakRetained());
    }

    @Test
    void matchesOracleForKOfOneEqualToMAndAboveM() {
        var candidates = List.of(result("c", 0.5), result("a", 0.9), result("b", 0.7), result("d", 0.1));
        for (var k = 1; k <= candidates.size() + 3; k++) {
            assertMatchesOracle(candidates, k);
        }
    }

    @Test
    void tiesAtTheCutBoundaryResolveByAtomIdAscending() {
        var candidates = List.of(result("e", 0.5), result("b", 0.5), result("z", 0.9),
                result("a", 0.5), result("d", 0.5));
        var selected = ExactBoundedTopKSelector.select(candidates, 3).results();
        assertEquals(List.of("z", "a", "b"), selected.stream().map(r -> r.atom().id()).toList());
        for (var k = 1; k <= 6; k++) {
            assertMatchesOracle(candidates, k);
        }
    }

    @Test
    void allTiedCandidatesKeepIdOrder() {
        var candidates = List.of(result("d", 1.0), result("b", 1.0), result("c", 1.0), result("a", 1.0));
        assertEquals(List.of("a", "b"),
                ExactBoundedTopKSelector.select(candidates, 2).results().stream().map(r -> r.atom().id()).toList());
        for (var k = 1; k <= 5; k++) {
            assertMatchesOracle(candidates, k);
        }
    }

    @Test
    void negativeAndSignedZeroScoresFollowDoubleCompare() {
        var candidates = List.of(result("n1", -0.25), result("pz", 0.0), result("nz", -0.0),
                result("n2", -1.0), result("p", 0.25), result("pz2", 0.0));
        var selected = ExactBoundedTopKSelector.select(candidates, 6).results();
        // Double.compare ranks +0.0 above -0.0; this must match the production comparator.
        assertEquals(List.of("p", "pz", "pz2", "nz", "n1", "n2"),
                selected.stream().map(r -> r.atom().id()).toList());
        for (var k = 1; k <= 7; k++) {
            assertMatchesOracle(candidates, k);
        }
    }

    @Test
    void resultIsIndependentOfCandidateInsertionOrder() {
        var base = new ArrayList<ResonanceResult>();
        for (var i = 0; i < 12; i++) {
            base.add(result("id-" + (char) ('a' + i), (i % 3) * 0.5 - 0.5));
        }
        var expected = ExactBoundedTopKSelector.select(base, 5).results();
        var random = new Random(7L);
        for (var permutation = 0; permutation < 20; permutation++) {
            var shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, random);
            assertEquals(expected, ExactBoundedTopKSelector.select(shuffled, 5).results());
            assertMatchesOracle(shuffled, 5);
        }
    }

    @Test
    void seededPropertyMatchesOracleAcrossSizesKsAndPermutations() {
        // Small score alphabet forces many ties; mixed signs and signed zeros included.
        double[] alphabet = {-1.0, -0.5, -0.0, 0.0, 0.25, 0.5, 0.5000000000000001, 1.0};
        var random = new Random(42L);
        for (var trial = 0; trial < 300; trial++) {
            var size = random.nextInt(40);
            var candidates = new ArrayList<ResonanceResult>(size);
            for (var i = 0; i < size; i++) {
                candidates.add(result(String.format("atom-%03d", random.nextInt(1000)) + "-" + i,
                        alphabet[random.nextInt(alphabet.length)]));
            }
            for (var permutation = 0; permutation < 3; permutation++) {
                Collections.shuffle(candidates, random);
                for (var k = 1; k <= size + 3; k++) {
                    assertMatchesOracle(candidates, k);
                }
            }
        }
    }

    @Test
    void peakRetainedNeverExceedsTopK() {
        var candidates = new ArrayList<ResonanceResult>();
        for (var i = 0; i < 1_000; i++) {
            candidates.add(result(String.format("a%04d", i), i / 1_000.0));
        }
        var selection = ExactBoundedTopKSelector.select(candidates, 5);
        assertEquals(5, selection.peakRetained());
        assertEquals(1_000, selection.offered());
        assertEquals(List.of("a0999", "a0998", "a0997", "a0996", "a0995"),
                selection.results().stream().map(r -> r.atom().id()).toList());
    }
}
