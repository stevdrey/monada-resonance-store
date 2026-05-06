package com.monada.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryRankingComparisonTest {

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> new QueryRankingComparison(null, List.of(), List.of(), RankingChange.MAINTAINED));
        assertThrows(NullPointerException.class,
                () -> new QueryRankingComparison("q", null, List.of(), RankingChange.MAINTAINED));
        assertThrows(NullPointerException.class,
                () -> new QueryRankingComparison("q", List.of(), null, RankingChange.MAINTAINED));
        assertThrows(NullPointerException.class,
                () -> new QueryRankingComparison("q", List.of(), List.of(), null));
    }

    @Test
    void rejectsBlankQueryText() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryRankingComparison("   ", List.of(), List.of(), RankingChange.MAINTAINED));
    }

    @Test
    void copiesLabelListsToPreventExternalMutation() {
        var before = new ArrayList<>(List.of("a", "b"));
        var after = new ArrayList<>(List.of("b", "a"));

        var comparison = new QueryRankingComparison("q", before, after, RankingChange.IMPROVED);

        before.add("c");
        after.add("c");

        assertEquals(List.of("a", "b"), comparison.beforeLabels());
        assertEquals(List.of("b", "a"), comparison.afterLabels());
        assertNotSame(before, comparison.beforeLabels());
        assertNotSame(after, comparison.afterLabels());
    }
}
