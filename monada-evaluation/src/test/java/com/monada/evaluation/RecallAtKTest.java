package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecallAtKTest {

    private static final double DELTA = 1e-9;

    @Test
    void issueExampleAtK1() {
        var expected = Set.of("A", "B");
        var ranked = List.of("A", "C", "B");
        assertEquals(0.5, RecallAtK.compute(expected, ranked, 1), DELTA);
    }

    @Test
    void issueExampleAtK3() {
        var expected = Set.of("A", "B");
        var ranked = List.of("A", "C", "B");
        assertEquals(1.0, RecallAtK.compute(expected, ranked, 3), DELTA);
    }

    @Test
    void noneRelevantReturnsZero() {
        assertEquals(0.0,
                RecallAtK.compute(Set.of("X"), List.of("A", "B", "C"), 3), DELTA);
    }

    @Test
    void duplicatesInRankedAreCountedOnce() {
        var expected = Set.of("A", "B");
        var ranked = List.of("A", "A", "A");
        assertEquals(0.5, RecallAtK.compute(expected, ranked, 3), DELTA);
    }

    @Test
    void emptyRankedReturnsZero() {
        assertEquals(0.0, RecallAtK.compute(Set.of("A"), List.of(), 3), DELTA);
    }

    @Test
    void rejectsInvalidK() {
        assertThrows(IllegalArgumentException.class,
                () -> RecallAtK.compute(Set.of("A"), List.of("A"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> RecallAtK.compute(Set.of("A"), List.of("A"), -1));
    }

    @Test
    void rejectsEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> RecallAtK.compute(Set.of(), List.of("A"), 1));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> RecallAtK.compute(null, List.of("A"), 1));
        assertThrows(NullPointerException.class,
                () -> RecallAtK.compute(Set.of("A"), null, 1));
    }
}
