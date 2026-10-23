package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Deterministic data-size ladder of the book latency benchmark (Issue #168). */
class BookLatencyScaleTest {
    /** 87 distinct chunks, the size of the frozen book-chunking-v1 set. */
    private static final List<String> CHUNKS = IntStream.range(0, 87).mapToObj(i -> "chunk text " + i).toList();

    @Test
    void defaultLadderKeepsDeclaredOrderAndKinds() {
        List<BookLatencyScale> ladder = BookLatencyScale.parseLadder(BookLatencyScale.DEFAULT_LADDER);
        assertEquals(List.of("p25", "p50", "p100", "x4", "x16"), ladder.stream().map(BookLatencyScale::id).toList());
        assertEquals(List.of(BookLatencyScale.Kind.REAL, BookLatencyScale.Kind.REAL, BookLatencyScale.Kind.REAL,
                BookLatencyScale.Kind.DERIVED, BookLatencyScale.Kind.DERIVED),
                ladder.stream().map(BookLatencyScale::kind).toList());
    }

    @Test
    void badSpecsAreRejected() {
        for (String bad : List.of("0%", "101%", "x1", "x0", "50", "abc", "", "50%,50%", "-5%", "2.5%")) {
            assertThrows(IllegalArgumentException.class, () -> BookLatencyScale.parseLadder(bad), bad);
        }
    }

    @Test
    void fractionsAreEvenlySpacedAcrossTheWholeBook() {
        BookLatencyScale.Selected quarter = BookLatencyScale.parse("25%").select(CHUNKS);
        assertEquals(21, quarter.texts().size(), "floor(87 * 25 / 100)");
        List<Integer> ordinals = quarter.ordinals();
        assertEquals(3, ordinals.getFirst());
        assertEquals(83, ordinals.getLast(), "the last quarter of the book is represented");
        for (int i = 1; i < ordinals.size(); i++) {
            assertEquals(4, ordinals.get(i) - ordinals.get(i - 1));
        }
        assertEquals(ordinals.stream().map(CHUNKS::get).toList(), quarter.texts(), "texts are unmodified chunks");

        BookLatencyScale.Selected half = BookLatencyScale.parse("50%").select(CHUNKS);
        assertEquals(43, half.texts().size());
        BookLatencyScale.Selected third = BookLatencyScale.parse("33%").select(CHUNKS);
        for (int i = 1; i < third.ordinals().size(); i++) {
            int gap = third.ordinals().get(i) - third.ordinals().get(i - 1);
            assertTrue(gap == 3 || gap == 4, "Bresenham gaps differ by at most one: " + gap);
        }
    }

    @Test
    void fullScaleIsTheIdentity() {
        BookLatencyScale.Selected all = BookLatencyScale.parse("100%").select(CHUNKS);
        assertEquals(CHUNKS, all.texts());
        assertEquals(87, all.uniqueAtoms());
        assertEquals(CHUNKS.stream().mapToLong(String::length).sum(), all.textBytes());
    }

    @Test
    void replicasGetTheirOwnAtomsAndAreDerived() {
        BookLatencyScale x4 = BookLatencyScale.parse("x4");
        assertEquals(BookLatencyScale.Kind.DERIVED, x4.kind());
        assertTrue(x4.label().startsWith("DERIVED"));
        BookLatencyScale.Selected selected = x4.select(CHUNKS);
        assertEquals(4 * 87, selected.texts().size());
        assertEquals(4 * 87, selected.uniqueAtoms(), "the replica prefix keeps content-derived ids apart");
        assertEquals(CHUNKS, selected.texts().subList(0, 87), "the real chunks come first, unmodified");
        assertEquals("[replica 3] chunk text 86", selected.texts().getLast());
        assertEquals(selected.texts().size(), selected.ordinals().size());
    }

    @Test
    void duplicateChunksStayOneAtom() {
        List<String> withRepeat = List.of("a", "b", "a", "c");
        BookLatencyScale.Selected all = BookLatencyScale.parse("100%").select(withRepeat);
        assertEquals(4, all.texts().size());
        assertEquals(3, all.uniqueAtoms());
        assertEquals(6, BookLatencyScale.parse("x2").select(withRepeat).uniqueAtoms());
    }

    @Test
    void selectionIsDeterministic() {
        for (BookLatencyScale scale : BookLatencyScale.parseLadder(BookLatencyScale.DEFAULT_LADDER)) {
            assertEquals(scale.select(CHUNKS), scale.select(List.copyOf(CHUNKS)));
        }
    }

    @Test
    void aScaleThatSelectsNothingIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> BookLatencyScale.parse("5%").select(CHUNKS.subList(0, 10)));
    }
}
