package com.monada.evaluation;

import com.monada.core.FrequencyVector;
import com.monada.core.KnowledgeAtom;
import com.monada.index.LinearScanResonanceIndex;
import com.monada.storage.AtomStore;
import com.monada.storage.FrequencyStore;
import com.monada.storage.StoredVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentedLinearScanTest {

    private static final FrequencyVector QUERY = new FrequencyVector(new float[]{1f, 0f});

    private static KnowledgeAtom atom(String id) {
        return new KnowledgeAtom(id, com.monada.core.AtomType.TEXT, "content " + id,
                java.util.Map.of(), 1.0, java.time.Instant.EPOCH);
    }

    private static StoredVector vec(String id, float x, float y) {
        return new StoredVector(id, new FrequencyVector(new float[]{x, y}));
    }

    private record Fixture(AtomStore atoms, FrequencyStore vectors) {}

    /**
     * Ties (a/b both 1.0), a duplicate row for "a" with a lower score, an orphan vector for a
     * missing atom, a zero vector (score 0.0), and a mid score.
     */
    private static Fixture edgeFixture() {
        var atoms = List.of(atom("b"), atom("a"), atom("c"), atom("d"));
        var vectors = List.of(
                vec("b", 1f, 0f),
                vec("a", 1f, 1f),
                vec("zz-orphan", 1f, 0f),
                vec("a", 1f, 0f),
                vec("c", 1f, 1f),
                vec("d", 0f, 0f));
        return new Fixture(new AtomStore() {
            @Override public void save(KnowledgeAtom atom) { throw new UnsupportedOperationException(); }
            @Override public Optional<KnowledgeAtom> findById(String id) {
                return atoms.stream().filter(a -> a.id().equals(id)).findFirst();
            }
            @Override public List<KnowledgeAtom> findAll() { return atoms; }
        }, new FrequencyStore() {
            @Override public void save(String atomId, FrequencyVector vector) { throw new UnsupportedOperationException(); }
            @Override public Optional<FrequencyVector> findByAtomId(String atomId) { return Optional.empty(); }
            @Override public List<StoredVector> findAll() { return vectors; }
        });
    }

    @Test
    void matchesProductionForTiesOrphansDuplicatesAndThresholds() throws IOException {
        var f = edgeFixture();
        var production = new LinearScanResonanceIndex(f.atoms(), f.vectors());
        for (double threshold : new double[]{Double.NEGATIVE_INFINITY, 0.0, 0.7, 1.0, 1.5}) {
            for (int topK : new int[]{1, 2, 3, 10}) {
                var trace = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, topK, threshold);
                InstrumentedLinearScan.verifyParity(production, QUERY, topK, threshold, trace);
            }
        }
    }

    @Test
    void tiesBreakByAtomIdAndDuplicatesKeepMaxScore() throws IOException {
        var f = edgeFixture();
        var trace = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 2, Double.NEGATIVE_INFINITY);
        assertEquals(List.of("a", "b"), trace.results().stream().map(r -> r.atom().id()).toList());
        assertEquals(1.0, trace.results().getFirst().score(), 1e-12);
    }

    @Test
    void structuralCountsReflectOrphansAndDedup() throws IOException {
        var f = edgeFixture();
        var trace = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 1, Double.NEGATIVE_INFINITY);
        assertEquals(4, trace.atomsLoaded());
        assertEquals(6, trace.vectorsVisited());
        assertEquals(1, trace.orphanVectors());
        assertEquals(5, trace.cosineEvaluations());
        assertEquals(4, trace.candidateCount());
        assertEquals(1, trace.resultsReturned());

        var filtered = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 10, 1.0);
        assertEquals(2, filtered.candidateCount(), "threshold is inclusive (>=)");
        assertEquals(5, filtered.cosineEvaluations(), "threshold does not reduce scoring work");
    }

    @Test
    void phaseTimesAreNonNegativeAndTotalCoversPhases() throws IOException {
        var f = edgeFixture();
        var t = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 3, Double.NEGATIVE_INFINITY);
        assertTrue(t.atomLoadNanos() >= 0 && t.vectorLoadNanos() >= 0 && t.scoringNanos() >= 0
                && t.dedupNanos() >= 0 && t.sortNanos() >= 0 && t.truncateNanos() >= 0);
        assertTrue(t.totalNanos() >= t.sumOfPhasesNanos());
    }

    @Test
    void rejectsNonPositiveTopK() {
        var f = edgeFixture();
        assertThrows(IllegalArgumentException.class,
                () -> InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 0, 0.0));
    }

    @Test
    void parityCheckDetectsDivergence() throws IOException {
        var f = edgeFixture();
        var production = new LinearScanResonanceIndex(f.atoms(), f.vectors());
        var wrongThreshold = InstrumentedLinearScan.search(f.atoms(), f.vectors(), QUERY, 10, 1.0);
        assertThrows(IllegalStateException.class, () -> InstrumentedLinearScan.verifyParity(
                production, QUERY, 10, Double.NEGATIVE_INFINITY, wrongThreshold));
    }

    @Test
    void emptyStoresYieldEmptyResults() throws IOException {
        AtomStore atoms = new AtomStore() {
            @Override public void save(KnowledgeAtom atom) { }
            @Override public Optional<KnowledgeAtom> findById(String id) { return Optional.empty(); }
            @Override public List<KnowledgeAtom> findAll() { return List.of(); }
        };
        FrequencyStore vectors = new FrequencyStore() {
            @Override public void save(String atomId, FrequencyVector vector) { }
            @Override public Optional<FrequencyVector> findByAtomId(String atomId) { return Optional.empty(); }
            @Override public List<StoredVector> findAll() { return List.of(); }
        };
        var trace = InstrumentedLinearScan.search(atoms, vectors, QUERY, 5, 0.0);
        assertTrue(trace.results().isEmpty());
        InstrumentedLinearScan.verifyParity(new LinearScanResonanceIndex(atoms, vectors), QUERY, 5, 0.0, trace);
    }
}
