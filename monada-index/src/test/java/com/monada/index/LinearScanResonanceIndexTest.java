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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearScanResonanceIndexTest {

    private static KnowledgeAtom atom(String id) {
        return new KnowledgeAtom(id, AtomType.TEXT, "content-" + id, Map.of(), 1.0,
                Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void rejectsNonPositiveTopK() {
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(
                new InMemoryAtomStore(List.of()), new InMemoryFrequencyStore(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> index.search(new FrequencyVector(new float[]{1f}), 0, 0.0));
    }

    @Test
    void returnsResultsSortedByScoreLimitedByTopK() throws IOException {
        FrequencyVector vA = new FrequencyVector(new float[]{1f, 0f});
        FrequencyVector vB = new FrequencyVector(new float[]{0f, 1f});
        FrequencyVector vC = new FrequencyVector(new float[]{1f, 1f});

        AtomStore atoms = new InMemoryAtomStore(List.of(atom("a"), atom("b"), atom("c")));
        FrequencyStore vectors = new InMemoryFrequencyStore(List.of(
                new StoredVector("a", vA),
                new StoredVector("b", vB),
                new StoredVector("c", vC)
        ));
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(atoms, vectors);

        List<ResonanceResult> results = index.search(new FrequencyVector(new float[]{1f, 0f}), 2, 0.0);
        assertEquals(2, results.size());
        assertEquals("a", results.get(0).atom().id());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void filtersByThreshold() throws IOException {
        AtomStore atoms = new InMemoryAtomStore(List.of(atom("a"), atom("b")));
        FrequencyStore vectors = new InMemoryFrequencyStore(List.of(
                new StoredVector("a", new FrequencyVector(new float[]{1f, 0f})),
                new StoredVector("b", new FrequencyVector(new float[]{0f, 1f}))
        ));
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(atoms, vectors);

        List<ResonanceResult> results = index.search(new FrequencyVector(new float[]{1f, 0f}), 10, 0.5);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).atom().id());
    }

    @Test
    void searchLoadsAtomsOnceAndDoesNotUseFindById() throws IOException {
        int[] findAllCalls = {0};
        int[] findByIdCalls = {0};
        AtomStore atoms = new AtomStore() {
            @Override public void save(KnowledgeAtom atom) { }
            @Override public Optional<KnowledgeAtom> findById(String id) { findByIdCalls[0]++; return Optional.empty(); }
            @Override public List<KnowledgeAtom> findAll() { findAllCalls[0]++; return List.of(atom("a"), atom("b")); }
        };
        FrequencyStore vectors = new InMemoryFrequencyStore(List.of(
                new StoredVector("a", new FrequencyVector(new float[]{1f})),
                new StoredVector("b", new FrequencyVector(new float[]{1f}))
        ));
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(atoms, vectors);

        index.search(new FrequencyVector(new float[]{1f}), 5, 0.0);
        assertEquals(1, findAllCalls[0]);
        assertEquals(0, findByIdCalls[0]);
    }

    @Test
    void propagatesIOExceptionFromAtomStore() {
        IOException expected = new IOException("disk failure");
        AtomStore atoms = new AtomStore() {
            @Override public void save(KnowledgeAtom atom) { }
            @Override public Optional<KnowledgeAtom> findById(String id) { return Optional.empty(); }
            @Override public List<KnowledgeAtom> findAll() throws IOException { throw expected; }
        };
        FrequencyStore vectors = new InMemoryFrequencyStore(List.of(
                new StoredVector("a", new FrequencyVector(new float[]{1f}))
        ));
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(atoms, vectors);

        IOException thrown = assertThrows(IOException.class,
                () -> index.search(new FrequencyVector(new float[]{1f}), 1, 0.0));
        assertSame(expected, thrown);
    }

    @Test
    void tieBreaksByAtomIdAscending() throws IOException {
        // Two atoms share an identical vector -> identical cosine score.
        // Regardless of insertion order, atom id "a" must precede "b".
        FrequencyVector shared = new FrequencyVector(new float[]{1f, 0f});

        AtomStore atomsForward = new InMemoryAtomStore(List.of(atom("a"), atom("b")));
        FrequencyStore vectorsForward = new InMemoryFrequencyStore(List.of(
                new StoredVector("a", shared),
                new StoredVector("b", shared)
        ));
        List<ResonanceResult> forward = new LinearScanResonanceIndex(atomsForward, vectorsForward)
                .search(new FrequencyVector(new float[]{1f, 0f}), 5, 0.0);

        AtomStore atomsReversed = new InMemoryAtomStore(List.of(atom("b"), atom("a")));
        FrequencyStore vectorsReversed = new InMemoryFrequencyStore(List.of(
                new StoredVector("b", shared),
                new StoredVector("a", shared)
        ));
        List<ResonanceResult> reversed = new LinearScanResonanceIndex(atomsReversed, vectorsReversed)
                .search(new FrequencyVector(new float[]{1f, 0f}), 5, 0.0);

        assertEquals(2, forward.size());
        assertEquals("a", forward.get(0).atom().id());
        assertEquals("b", forward.get(1).atom().id());

        assertEquals(2, reversed.size());
        assertEquals("a", reversed.get(0).atom().id());
        assertEquals("b", reversed.get(1).atom().id());
    }

    @Test
    void orderingIsStableAcrossRepeatedCalls() throws IOException {
        AtomStore atoms = new InMemoryAtomStore(List.of(atom("c"), atom("a"), atom("b")));
        FrequencyVector shared = new FrequencyVector(new float[]{1f, 0f});
        FrequencyStore vectors = new InMemoryFrequencyStore(List.of(
                new StoredVector("c", shared),
                new StoredVector("a", shared),
                new StoredVector("b", new FrequencyVector(new float[]{1f, 1f}))
        ));
        LinearScanResonanceIndex index = new LinearScanResonanceIndex(atoms, vectors);
        FrequencyVector query = new FrequencyVector(new float[]{1f, 0f});

        List<String> first = index.search(query, 5, 0.0).stream().map(r -> r.atom().id()).toList();
        List<String> second = index.search(query, 5, 0.0).stream().map(r -> r.atom().id()).toList();
        List<String> third = index.search(query, 5, 0.0).stream().map(r -> r.atom().id()).toList();

        assertEquals(first, second);
        assertEquals(second, third);
        // Tied "a" and "c" must appear in id ascending order, ahead of lower-scoring "b".
        assertEquals(List.of("a", "c", "b"), first);
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
