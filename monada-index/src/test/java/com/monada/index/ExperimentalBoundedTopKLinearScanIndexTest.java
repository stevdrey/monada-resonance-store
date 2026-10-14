package com.monada.index;

import com.monada.core.AtomType;
import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperimentalBoundedTopKLinearScanIndexTest {

    private static final FrequencyVector QUERY = new FrequencyVector(new float[]{1f, 0f});

    private static KnowledgeAtom atom(String id) {
        return new KnowledgeAtom(id, AtomType.TEXT, "content-" + id, Map.of(), 1.0,
                Instant.parse("2024-01-01T00:00:00Z"));
    }

    private static StoredVector vector(String atomId, float... values) {
        return new StoredVector(atomId, new FrequencyVector(values));
    }

    /**
     * Edge fixture: ties, a duplicate whose later vector scores higher (must replace),
     * a duplicate whose later vector scores lower (must not replace), negative and zero
     * similarity, a zero vector, and an orphan vector without an atom.
     */
    private static List<KnowledgeAtom> edgeAtoms() {
        return List.of(atom("a"), atom("b"), atom("c"), atom("d"), atom("e"), atom("f"), atom("g"), atom("h"));
    }

    private static List<StoredVector> edgeVectors() {
        return List.of(
                vector("a", 1f, 0f),          // 1.0
                vector("b", 1f, 0f),          // 1.0, ties with a
                vector("c", 0f, 1f),          // 0.0, replaced below by a higher later score
                vector("d", 1f, 1f),          // ~0.707
                vector("e", -1f, 0f),         // -1.0
                vector("f", 0f, 0f),          // zero vector -> 0.0
                vector("zz-orphan", 1f, 0f),  // skipped
                vector("c", 3f, 1f),          // ~0.949 replaces c's 0.0
                vector("a", 0f, 1f),          // 0.0 never replaces a's 1.0
                vector("g", -1f, 1f),         // ~-0.707
                vector("h", 0f, 1f)           // 0.0, ties with f
        );
    }

    private static void assertParity(List<KnowledgeAtom> atoms, List<StoredVector> vectors,
                                     FrequencyVector query, int topK, double threshold) throws IOException {
        AtomStore atomStore = new InMemoryAtomStore(atoms);
        FrequencyStore vectorStore = new InMemoryFrequencyStore(vectors);
        var expected = new LinearScanResonanceIndex(atomStore, vectorStore).search(query, topK, threshold);
        var actual = new ExperimentalBoundedTopKLinearScanIndex(atomStore, vectorStore).search(query, topK, threshold);
        var context = "K=" + topK + " threshold=" + threshold;
        assertEquals(ids(expected), ids(actual), context);
        assertEquals(scoreBits(expected), scoreBits(actual), context);
    }

    private static List<String> ids(List<ResonanceResult> results) {
        return results.stream().map(r -> r.atom().id()).toList();
    }

    private static List<Long> scoreBits(List<ResonanceResult> results) {
        return results.stream().map(r -> Double.doubleToLongBits(r.score())).toList();
    }

    @Test
    void rejectsNonPositiveTopK() {
        var index = new ExperimentalBoundedTopKLinearScanIndex(
                new InMemoryAtomStore(List.of()), new InMemoryFrequencyStore(List.of()));
        assertThrows(IllegalArgumentException.class, () -> index.search(QUERY, 0, 0.0));
    }

    @Test
    void matchesProductionAcrossThresholdsAndKs() throws IOException {
        var production = new LinearScanResonanceIndex(
                new InMemoryAtomStore(edgeAtoms()), new InMemoryFrequencyStore(edgeVectors()));
        var all = production.search(QUERY, Integer.MAX_VALUE, Double.NEGATIVE_INFINITY);
        assertEquals(8, all.size());
        // A threshold equal to an exact computed score exercises inclusive boundary filtering.
        var boundary = all.get(2).score();
        double[] thresholds = {Double.NEGATIVE_INFINITY, -0.5, -0.0, 0.0, boundary, 1.0, 1.5};
        for (var threshold : thresholds) {
            for (var k = 1; k <= edgeAtoms().size() + 2; k++) {
                assertParity(edgeAtoms(), edgeVectors(), QUERY, k, threshold);
            }
        }
    }

    @Test
    void duplicateReplacementAndTiesMatchExpectedOrder() throws IOException {
        var results = new ExperimentalBoundedTopKLinearScanIndex(
                new InMemoryAtomStore(edgeAtoms()), new InMemoryFrequencyStore(edgeVectors()))
                .search(QUERY, 4, Double.NEGATIVE_INFINITY);
        assertEquals(List.of("a", "b", "c", "d"), ids(results));
        assertEquals(1.0, results.get(0).score());
    }

    @Test
    void boundaryThresholdIsInclusive() throws IOException {
        var production = new LinearScanResonanceIndex(
                new InMemoryAtomStore(edgeAtoms()), new InMemoryFrequencyStore(edgeVectors()));
        var all = production.search(QUERY, Integer.MAX_VALUE, Double.NEGATIVE_INFINITY);
        var boundary = all.get(2).score();
        var results = new ExperimentalBoundedTopKLinearScanIndex(
                new InMemoryAtomStore(edgeAtoms()), new InMemoryFrequencyStore(edgeVectors()))
                .search(QUERY, 10, boundary);
        assertEquals(List.of("a", "b", "c"), ids(results));
    }

    @Test
    void noMatchesAndEmptyStoresYieldEmptyResults() throws IOException {
        assertEquals(List.of(), new ExperimentalBoundedTopKLinearScanIndex(
                new InMemoryAtomStore(edgeAtoms()), new InMemoryFrequencyStore(edgeVectors()))
                .search(QUERY, 5, 1.5));
        assertEquals(List.of(), new ExperimentalBoundedTopKLinearScanIndex(
                new InMemoryAtomStore(List.of()), new InMemoryFrequencyStore(List.of()))
                .search(QUERY, 5, Double.NEGATIVE_INFINITY));
        assertParity(List.of(), List.of(vector("zz-orphan", 1f, 0f)), QUERY, 3, Double.NEGATIVE_INFINITY);
    }

    @Test
    void insertionOrderPermutationsMatchProduction() throws IOException {
        var random = new Random(11L);
        for (var permutation = 0; permutation < 25; permutation++) {
            var atoms = new ArrayList<>(edgeAtoms());
            var vectors = new ArrayList<>(edgeVectors());
            Collections.shuffle(atoms, random);
            Collections.shuffle(vectors, random);
            for (var k : new int[]{1, 2, 3, 5, 8, 20}) {
                assertParity(atoms, vectors, QUERY, k, Double.NEGATIVE_INFINITY);
                assertParity(atoms, vectors, QUERY, k, 0.0);
            }
        }
    }

    @Test
    void seededRandomCorporaMatchProduction() throws IOException {
        var random = new Random(42L);
        for (var trial = 0; trial < 60; trial++) {
            var atomCount = 1 + random.nextInt(30);
            var atoms = new ArrayList<KnowledgeAtom>();
            for (var i = 0; i < atomCount; i++) {
                atoms.add(atom(String.format("atom-%02d", i)));
            }
            var vectors = new ArrayList<StoredVector>();
            var vectorCount = random.nextInt(atomCount * 2 + 1);
            for (var i = 0; i < vectorCount; i++) {
                // Small integer components force exact ties; some IDs repeat, some are orphans.
                var atomId = random.nextInt(10) == 0
                        ? "orphan-" + i
                        : String.format("atom-%02d", random.nextInt(atomCount));
                vectors.add(vector(atomId, random.nextInt(5) - 2, random.nextInt(5) - 2, random.nextInt(3) - 1));
            }
            var query = new FrequencyVector(new float[]{random.nextInt(5) - 2, random.nextInt(5) - 2, 1f});
            for (var k : new int[]{1, 2, 5, atomCount, atomCount + 3}) {
                assertParity(atoms, vectors, query, k, Double.NEGATIVE_INFINITY);
                assertParity(atoms, vectors, query, k, 0.0);
            }
        }
    }

    private static final class InMemoryAtomStore implements AtomStore {
        private final List<KnowledgeAtom> atoms;
        InMemoryAtomStore(List<KnowledgeAtom> atoms) { this.atoms = atoms; }
        @Override public void save(KnowledgeAtom atom) { }
        @Override public Optional<KnowledgeAtom> findById(String id) {
            return atoms.stream().filter(a -> a.id().equals(id)).findFirst();
        }
        @Override public List<KnowledgeAtom> findAll() { return atoms; }
    }

    private static final class InMemoryFrequencyStore implements FrequencyStore {
        private final List<StoredVector> vectors;
        InMemoryFrequencyStore(List<StoredVector> vectors) { this.vectors = vectors; }
        @Override public void save(String atomId, FrequencyVector vector) { }
        @Override public Optional<FrequencyVector> findByAtomId(String atomId) {
            return vectors.stream().filter(v -> v.atomId().equals(atomId)).map(StoredVector::vector).findFirst();
        }
        @Override public List<StoredVector> findAll() { return vectors; }
    }
}
