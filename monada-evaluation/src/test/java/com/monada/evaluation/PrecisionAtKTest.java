package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrecisionAtKTest {

    private static final double DELTA = 1e-9;

    @Test
    void allRelevantInTopKReturnsOne() {
        Set<String> expected = Set.of("a", "b");
        List<String> ranked = List.of("a", "b", "c");
        assertEquals(1.0, PrecisionAtK.compute(expected, ranked, 2), DELTA);
    }

    @Test
    void noneRelevantReturnsZero() {
        Set<String> expected = Set.of("x");
        List<String> ranked = List.of("a", "b", "c");
        assertEquals(0.0, PrecisionAtK.compute(expected, ranked, 3), DELTA);
    }

    @Test
    void partialMatchUsesIssueExample() {
        // From the issue: top 3 = [ka_arangodb, ka_postgresql, ka_orientdb]
        // expected = {ka_arangodb, ka_orientdb} -> 2 / 3 = 0.6667
        Set<String> expected = Set.of("ka_arangodb", "ka_orientdb");
        List<String> ranked = List.of("ka_arangodb", "ka_postgresql", "ka_orientdb");
        assertEquals(2.0 / 3.0, PrecisionAtK.compute(expected, ranked, 3), DELTA);
    }

    @Test
    void precisionAtOneOnlyConsidersFirstResult() {
        Set<String> expected = Set.of("b");
        List<String> ranked = List.of("a", "b", "c");
        assertEquals(0.0, PrecisionAtK.compute(expected, ranked, 1), DELTA);

        List<String> rankedHit = List.of("b", "a", "c");
        assertEquals(1.0, PrecisionAtK.compute(expected, rankedHit, 1), DELTA);
    }

    @Test
    void rankedSmallerThanKDividesByK() {
        // Only one returned result, k=5 -> 1 hit / 5 = 0.2
        Set<String> expected = Set.of("a");
        List<String> ranked = List.of("a");
        assertEquals(0.2, PrecisionAtK.compute(expected, ranked, 5), DELTA);
    }

    @Test
    void emptyRankedReturnsZero() {
        assertEquals(0.0, PrecisionAtK.compute(Set.of("a"), List.of(), 3), DELTA);
    }

    @Test
    void rejectsInvalidK() {
        assertThrows(IllegalArgumentException.class,
                () -> PrecisionAtK.compute(Set.of("a"), List.of("a"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> PrecisionAtK.compute(Set.of("a"), List.of("a"), -1));
    }

    @Test
    void rejectsEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> PrecisionAtK.compute(Set.of(), List.of("a"), 1));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> PrecisionAtK.compute(null, List.of("a"), 1));
        assertThrows(NullPointerException.class,
                () -> PrecisionAtK.compute(Set.of("a"), null, 1));
    }
}
