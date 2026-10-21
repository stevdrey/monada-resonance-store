package com.monada.evaluation.book;

import static com.monada.evaluation.book.BookRecallFailure.ABSTAINED;
import static com.monada.evaluation.book.BookRecallFailure.CHUNK_BOUNDARY_GAP;
import static com.monada.evaluation.book.BookRecallFailure.DISTRACTOR;
import static com.monada.evaluation.book.BookRecallFailure.FALSE_POSITIVE;
import static com.monada.evaluation.book.BookRecallFailure.MULTI_EVIDENCE_GAP;
import static com.monada.evaluation.book.BookRecallFailure.NONE;
import static com.monada.evaluation.book.BookRecallFailure.NO_RELEVANT_RESULT;
import static com.monada.evaluation.book.BookRecallFailure.WRONG_RANK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.monada.evaluation.book.BookRecallFailure.Ranked;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every failure class, and the precedence between them, from ranked keys and judgments alone. */
class BookRecallFailureTest {
    // Chunk "kN" has ordinal N. Evidence: k10 and k20; judged confounder: k30.
    private static final Set<String> EVIDENCE = Set.of("k10", "k20");
    private static final Set<String> CONFOUNDERS = Set.of("k30");
    private static final Set<Integer> EVIDENCE_ORDINALS = Set.of(10, 20);

    private static BookRecallFailure answerable(int... ordinals) {
        List<Ranked> ranked = java.util.Arrays.stream(ordinals).mapToObj(o -> new Ranked("k" + o, List.of(o)))
                .toList();
        return BookRecallFailure.classify(true, ranked, EVIDENCE, CONFOUNDERS, EVIDENCE_ORDINALS);
    }

    @Test
    void classifiesEachAnswerableOutcome() {
        assertEquals(NONE, answerable(10, 20));
        assertEquals(NONE, answerable(20, 50, 10));
        assertEquals(MULTI_EVIDENCE_GAP, answerable(10, 50));
        assertEquals(WRONG_RANK, answerable(50, 10, 20));
        assertEquals(CHUNK_BOUNDARY_GAP, answerable(11, 10));
        assertEquals(CHUNK_BOUNDARY_GAP, answerable(50, 19, 20));
        assertEquals(DISTRACTOR, answerable(30, 10));
        assertEquals(NO_RELEVANT_RESULT, answerable(30, 11, 50));
        assertEquals(NO_RELEVANT_RESULT, answerable());
    }

    @Test
    void mostSevereClassWins() {
        assertEquals(DISTRACTOR, answerable(11, 30, 10), "a judged confounder outranks a boundary neighbour");
        assertEquals(CHUNK_BOUNDARY_GAP, answerable(50, 21, 10), "a boundary neighbour outranks a plain wrong rank");
    }

    @Test
    void aRepeatedChunkIsAdjacentThroughAnyOfItsOrdinals() {
        List<Ranked> ranked = List.of(new Ranked("k50", List.of(50, 9)), new Ranked("k10", List.of(10)));
        assertEquals(CHUNK_BOUNDARY_GAP,
                BookRecallFailure.classify(true, ranked, EVIDENCE, CONFOUNDERS, EVIDENCE_ORDINALS));
    }

    @Test
    void classifiesNoAnswerQueries() {
        assertEquals(ABSTAINED, BookRecallFailure.classify(false, List.of(), Set.of(), CONFOUNDERS, Set.of()));
        assertEquals(FALSE_POSITIVE, BookRecallFailure.classify(false, List.of(new Ranked("k30", List.of(30))),
                Set.of(), CONFOUNDERS, Set.of()));
    }

    @Test
    void answerableQueryWithoutEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BookRecallFailure.classify(true, List.of(), Set.of(), CONFOUNDERS, Set.of()));
    }
}
