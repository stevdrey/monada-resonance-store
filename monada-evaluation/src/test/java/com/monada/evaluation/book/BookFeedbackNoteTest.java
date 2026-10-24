package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The explicit note of Phase C (Issue #170): original wording with a cited source, a sidecar that round-trips, a
 * preflight that refuses a note the corpus already holds, and a verification state that is never claimed without a
 * named reviewer and date.
 */
class BookFeedbackNoteTest {
    private static BookChunkFiles.Loaded chunks(String... texts) {
        List<BookChunkFiles.Entry> entries = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            entries.add(new BookChunkFiles.Entry("c/p/s000/c000" + i, i, texts[i],
                    VerifiedBookSource.sha256(texts[i].getBytes(StandardCharsets.UTF_8)), null));
        }
        return new BookChunkFiles.Loaded("c", "1", "0".repeat(64), "p", "0".repeat(64), entries);
    }

    @Test
    void sidecarRoundTripsWithoutPathsOrTimestamps() {
        String line = BookFeedbackNote.sidecarLine(BookFeedbackNote.atomId());
        assertTrue(line.endsWith("}\n"), line);
        assertTrue(BookFeedbackNote.readsBack(line, BookFeedbackNote.atomId()));
        assertFalse(BookFeedbackNote.readsBack(line, BookFeedbackExperiment.UNKNOWN_ATOM));
        assertTrue(line.contains("\"source\":\"" + BookFeedbackNote.SOURCE + "\""), line);
        assertTrue(line.contains("\"lifecycle\":\"APPEND_ONLY"), line);
        assertFalse(line.contains("/tmp") || line.contains("createdAt"), line);
        assertEquals(KnowledgeAtom.text(BookFeedbackNote.TEXT).id(), BookFeedbackNote.atomId());
    }

    @Test
    void preflightRefusesANoteTheCorpusAlreadyHolds() {
        assertDoesNotThrow(() -> BookFeedbackNote.preflight(chunks("an unrelated chunk"), Set.of()));
        BookInputException inside = assertThrows(BookInputException.class, () -> BookFeedbackNote.preflight(
                chunks("before " + BookFeedbackNote.TEXT + " after"), Set.of()));
        assertTrue(inside.getMessage().contains("occurs in chunk"), inside.getMessage());
        BookInputException atom = assertThrows(BookInputException.class, () -> BookFeedbackNote.preflight(
                chunks("x"), Set.of(BookFeedbackNote.atomId())));
        assertTrue(atom.getMessage().contains("already a chunk"), atom.getMessage());
    }

    @Test
    void verificationIsNeverClaimedWithoutAReviewerAndDate() {
        assertTrue(Set.of("PENDING_HUMAN_VERIFICATION", "VERIFIED").contains(BookFeedbackNote.VERIFICATION_STATUS));
        if (BookFeedbackNote.VERIFICATION_STATUS.equals("VERIFIED")) {
            assertNotEquals("NONE", BookFeedbackNote.VERIFIED_BY);
            assertTrue(BookFeedbackNote.VERIFIED_AT.matches("\\d{4}-\\d{2}-\\d{2}"), BookFeedbackNote.VERIFIED_AT);
        } else {
            assertEquals("NONE", BookFeedbackNote.VERIFIED_BY);
            assertEquals("NONE", BookFeedbackNote.VERIFIED_AT);
        }
        assertEquals(2, BookFeedbackNote.PROBES.size());
    }
}
