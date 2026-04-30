package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReciprocalRankTest {

    private static final double DELTA = 1e-9;

    @Test
    void firstRelevantAtPositionTwoReturnsHalf() {
        assertEquals(0.5,
                ReciprocalRank.compute(Set.of("A"), List.of("C", "A", "D")), DELTA);
    }

    @Test
    void firstRelevantAtTopReturnsOne() {
        assertEquals(1.0,
                ReciprocalRank.compute(Set.of("A"), List.of("A", "B", "C")), DELTA);
    }

    @Test
    void noRelevantReturnsZero() {
        assertEquals(0.0,
                ReciprocalRank.compute(Set.of("Z"), List.of("A", "B", "C")), DELTA);
    }

    @Test
    void onlyFirstRelevantCounts() {
        // First relevant at rank 2 even if more appear later
        assertEquals(0.5,
                ReciprocalRank.compute(Set.of("A", "B"), List.of("X", "B", "A")), DELTA);
    }

    @Test
    void emptyRankedReturnsZero() {
        assertEquals(0.0, ReciprocalRank.compute(Set.of("A"), List.of()), DELTA);
    }

    @Test
    void rejectsEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> ReciprocalRank.compute(Set.of(), List.of("A")));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> ReciprocalRank.compute(null, List.of("A")));
        assertThrows(NullPointerException.class,
                () -> ReciprocalRank.compute(Set.of("A"), null));
    }

    @Test
    void rejectsBlankExpectedElements() {
        assertThrows(IllegalArgumentException.class,
                () -> ReciprocalRank.compute(Set.of(" "), List.of("A")));
        assertThrows(IllegalArgumentException.class,
                () -> ReciprocalRank.compute(Set.of(""), List.of("A")));
    }

    @Test
    void rejectsNullExpectedElements() {
        var withNull = new HashSet<String>();
        withNull.add(null);
        withNull.add("A");
        assertThrows(IllegalArgumentException.class,
                () -> ReciprocalRank.compute(withNull, List.of("A")));
    }
}
