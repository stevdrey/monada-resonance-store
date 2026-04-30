package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HitAtKTest {

    private static final double DELTA = 1e-9;

    @Test
    void hitAt1MissesWhenRelevantNotFirst() {
        assertEquals(0.0,
                HitAtK.compute(Set.of("A"), List.of("C", "D", "A"), 1), DELTA);
    }

    @Test
    void hitAt3FindsRelevantAtTail() {
        assertEquals(1.0,
                HitAtK.compute(Set.of("A"), List.of("C", "D", "A"), 3), DELTA);
    }

    @Test
    void noneRelevantReturnsZero() {
        assertEquals(0.0,
                HitAtK.compute(Set.of("X"), List.of("A", "B", "C"), 3), DELTA);
    }

    @Test
    void anyRelevantReturnsOne() {
        assertEquals(1.0,
                HitAtK.compute(Set.of("A", "B"), List.of("B", "X", "Y"), 3), DELTA);
    }

    @Test
    void emptyRankedReturnsZero() {
        assertEquals(0.0, HitAtK.compute(Set.of("A"), List.of(), 5), DELTA);
    }

    @Test
    void rejectsInvalidK() {
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(Set.of("A"), List.of("A"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(Set.of("A"), List.of("A"), -1));
    }

    @Test
    void rejectsEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(Set.of(), List.of("A"), 1));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> HitAtK.compute(null, List.of("A"), 1));
        assertThrows(NullPointerException.class,
                () -> HitAtK.compute(Set.of("A"), null, 1));
    }

    @Test
    void rejectsBlankExpectedElements() {
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(Set.of(" "), List.of("A"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(Set.of(""), List.of("A"), 1));
    }

    @Test
    void rejectsNullExpectedElements() {
        var withNull = new HashSet<String>();
        withNull.add(null);
        withNull.add("A");
        assertThrows(IllegalArgumentException.class,
                () -> HitAtK.compute(withNull, List.of("A"), 1));
    }
}
