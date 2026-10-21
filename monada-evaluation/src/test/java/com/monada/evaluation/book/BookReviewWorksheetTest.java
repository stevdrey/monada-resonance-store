package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The score-blind worksheet and its conversion to {@code review.psv}. The body is synthetic (unique numbered words,
 * the real body's length) so the real chunk map applies without any book text.
 */
class BookReviewWorksheetTest {
    private static BookQueryDataset BASE;
    private static List<Pair> SELECTION;
    private static byte[] BODY;

    @BeforeAll
    static void load() {
        BASE = BookQueryDataset.load();
        SELECTION = BookUnjudgedSelection.parse(BookQueryDatasetV2.resource("selection.psv"));
        int size = Integer.parseInt(BASE.manifest().getProperty("source.sizeBytes"));
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < size; i++) {
            text.append(String.format("w%06d", i)).append(i % 10 == 9 ? '\n' : ' ');
        }
        BODY = text.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void showsNoRankingMetadataAndShufflesTheOrder() {
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        assertTrue(sheet.startsWith(BookReviewWorksheet.TITLE));
        for (Pair p : SELECTION) {
            assertEquals(1, count(sheet, "## " + p.pairId() + "\n"), p.pairId());
            assertFalse(sheet.contains(p.score()), "score of " + p.pairId() + " leaks");
            assertFalse(sheet.contains(p.atomId()), "atom id of " + p.pairId() + " leaks");
            // A repeated chunk key would expose hub chunks; offsets would expose the story position.
            assertFalse(sheet.contains(p.chunkKey()), "chunk key of " + p.pairId() + " leaks");
            ChunkRef chunk = BASE.chunkMap().get(p.chunkKey());
            assertFalse(sheet.contains("[" + chunk.byteStart() + ","), "byte range of " + p.pairId() + " leaks");
        }
        for (String hidden : List.of("TOP1", "TOP3", "HUB", "SAMPLE", "rank", "score", "stratum", "chunkDevUnjudged")) {
            assertFalse(Pattern.compile("(?i)\\b" + hidden + "\\b").matcher(sheet).find(),
                    hidden + " must not appear in the worksheet");
        }
        List<String> order = ids(sheet);
        assertNotEquals(SELECTION.stream().map(Pair::pairId).toList(), order, "worksheet keeps the selection order");
        assertEquals(sheet, BookReviewWorksheet.render(BODY, BASE, SELECTION), "worksheet is deterministic");
    }

    @Test
    void anUnfilledWorksheetLeavesEveryPairPending() {
        String review = BookReviewWorksheet.apply(BookReviewWorksheet.render(BODY, BASE, SELECTION), BODY, BASE,
                SELECTION);
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n", review);
    }

    @Test
    void appliesDecisionsAsByteSpansWithDigests() {
        Pair first = SELECTION.get(0);
        Pair second = SELECTION.get(1);
        ChunkRef chunk = BASE.chunkMap().get(first.chunkKey());
        // Three words across a line break, quoted with plain spaces.
        String text = new String(BODY, (int) chunk.byteStart(), 200, StandardCharsets.UTF_8);
        int lineEnd = text.indexOf('\n', 20);
        String quote = text.substring(lineEnd - 15, lineEnd + 8).replace('\n', ' ');
        String sheet = fill(BookReviewWorksheet.render(BODY, BASE, SELECTION), first.pairId(),
                "EVIDENCE", "2", quote, "states the asked fact");
        sheet = fill(sheet, second.pairId(), "UNRESOLVED", "", "", "not enough context to decide");
        String[] rows = BookReviewWorksheet.apply(sheet, BODY, BASE, SELECTION).split("\n");
        assertEquals(3, rows.length);
        String firstRow = Arrays.stream(rows).filter(r -> r.startsWith(first.pairId())).findFirst().orElseThrow();
        String[] f = firstRow.split("\\|");
        long start = Long.parseLong(f[5]);
        long end = Long.parseLong(f[6]);
        assertEquals(chunk.byteStart() + lineEnd - 15, start);
        assertEquals(start + 23, end);
        assertEquals(BookQueryDataset.sha256(Arrays.copyOfRange(BODY, (int) start, (int) end)), f[7]);
        assertEquals("EVIDENCE", f[3]);
        assertTrue(Arrays.stream(rows).anyMatch(r -> r.equals(String.join("|", second.pairId(), second.queryId(),
                second.chunkKey(), "UNRESOLVED", "-", "-", "-", "-", "not enough context to decide"))));
    }

    @Test
    void rejectsAQuoteThatIsNotInTheChunk() {
        Pair p = SELECTION.getFirst();
        String sheet = fill(BookReviewWorksheet.render(BODY, BASE, SELECTION), p.pairId(), "CONFOUNDER", "0",
                "not in this chunk", "n");
        var e = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(sheet, BODY, BASE, SELECTION));
        assertEquals(p.pairId() + ": quote does not occur in " + p.chunkKey(), e.getMessage());
    }

    @Test
    void rejectsAnAmbiguousQuote() {
        byte[] repeated = BODY.clone();
        ChunkRef chunk = BASE.chunkMap().get(SELECTION.getFirst().chunkKey());
        System.arraycopy("same".getBytes(StandardCharsets.UTF_8), 0, repeated, (int) chunk.byteStart(), 4);
        System.arraycopy("same".getBytes(StandardCharsets.UTF_8), 0, repeated, (int) chunk.byteStart() + 40, 4);
        var e = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.locate(repeated, chunk, "same", "UJ-x"));
        assertTrue(e.getMessage().contains("occurs more than once"), e.getMessage());
    }

    @Test
    void rejectsADecisionWithoutAQuoteOrWithANoteContainingAPipe() {
        Pair p = SELECTION.getFirst();
        String base = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        var missing = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(
                fill(base, p.pairId(), "EVIDENCE", "2", "", "n"), BODY, BASE, SELECTION));
        assertTrue(missing.getMessage().contains("a quote from the chunk is required"), missing.getMessage());
        var pipe = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(
                fill(base, p.pairId(), "UNRESOLVED", "", "", "a|b"), BODY, BASE, SELECTION));
        assertTrue(pipe.getMessage().contains("contain no |"), pipe.getMessage());
        var grade = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(
                fill(base, p.pairId(), "UNRESOLVED", "1", "", "reason"), BODY, BASE, SELECTION));
        assertTrue(grade.getMessage().contains("UNRESOLVED takes no grade"), grade.getMessage());
    }

    @Test
    void rejectsAWorksheetWithAMissingOrRepeatedEntry() {
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        String id = SELECTION.getFirst().pairId();
        // A mistyped heading drops the entry: its recorded decision must not silently become pending.
        String mistyped = sheet.replace("## " + id + "\n", "## " + id.toUpperCase() + "\n");
        var missing = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.apply(mistyped, BODY, BASE, SELECTION));
        assertEquals("worksheet is missing 1 selected entries: [" + id + "]", missing.getMessage());
        int at = sheet.indexOf("## " + id + "\n");
        String section = sheet.substring(at, sheet.indexOf("\n## ", at) + 1);
        var twice = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.apply(sheet + section, BODY, BASE, SELECTION));
        assertEquals(id + ": worksheet has this entry twice", twice.getMessage());
    }

    @Test
    void ignoresFieldLikeLinesInsideTheChunkText() {
        Pair p = SELECTION.getFirst();
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        int at = sheet.indexOf("~~~text\n", sheet.indexOf("## " + p.pairId())) + 8;
        String planted = sheet.substring(0, at) + "decision: EVIDENCE\n" + sheet.substring(at);
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n", BookReviewWorksheet.apply(planted, BODY, BASE,
                SELECTION));
    }

    private static String fill(String sheet, String pairId, String decision, String grade, String quote,
                               String note) {
        int at = sheet.indexOf("decision: \n", sheet.indexOf("## " + pairId + "\n"));
        String blank = "decision: \ngrade: \nquote: \nnote: \n";
        return sheet.substring(0, at) + "decision: " + decision + "\ngrade: " + grade + "\nquote: " + quote
                + "\nnote: " + note + "\n" + sheet.substring(at + blank.length());
    }

    private static List<String> ids(String sheet) {
        Matcher m = Pattern.compile("(?m)^## (UJ-[0-9a-f]{8})$").matcher(sheet);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
