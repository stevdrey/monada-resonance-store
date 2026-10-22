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
    private static final String KEY_OTHER = "book-corpus-v1/book-chunking-v1/s000/c0000";
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
    void recordsTheProtocolTheWorksheetWasProducedUnder() {
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        assertTrue(sheet.contains("\nprotocol: " + BookQueryDatasetV2.REVIEW_PROTOCOL + "\n"));
        assertEquals(BookQueryDatasetV2.REVIEW_PROTOCOL, BookReviewWorksheet.protocolOf(sheet));
        // The first renderer wrote no protocol line (and showed chunk keys): such a sheet is the legacy protocol.
        String legacy = sheet.replace("protocol: " + BookQueryDatasetV2.REVIEW_PROTOCOL + "\n\n", "");
        assertEquals(BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL, BookReviewWorksheet.protocolOf(legacy));
        String forged = sheet.replace(BookQueryDatasetV2.REVIEW_PROTOCOL, "FULLY_BLIND_V9");
        var e = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.protocolOf(forged));
        assertEquals("worksheet title, protocol line or instructions were edited", e.getMessage());
    }

    @Test
    void rejectsInstructionsAppendedAfterTheCanonicalPreamble() {
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        String preamble = BookReviewWorksheet.preamble(false);
        String appended = preamble + "Note: here EVIDENCE means non-relevant.\n\n" + sheet.substring(preamble.length());
        var e = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.apply(appended, BODY, BASE, SELECTION));
        assertEquals("worksheet title, protocol line or instructions were edited", e.getMessage());
    }

    @Test
    void rejectsDecisionsMadeUnderEditedInstructions() {
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        // Swapping the meaning of the two labels would make the recorded protocol untrue.
        String swapped = sheet.replaceFirst("`EVIDENCE` \\(the chunk answers the query\\)",
                "`EVIDENCE` (judged non-relevant)");
        assertNotEquals(sheet, swapped);
        var e = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.apply(swapped, BODY, BASE, SELECTION));
        assertEquals("worksheet title, protocol line or instructions were edited", e.getMessage());
    }

    @Test
    void rejectsTextInsertedAmongAnEntrysFields() {
        Pair p = SELECTION.getFirst();
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        int fields = sheet.indexOf("decision: \n", sheet.indexOf("## " + p.pairId() + "\n"));
        for (String inserted : List.of(
                sheet.substring(0, fields) + "EVIDENCE now means non-relevant.\n" + sheet.substring(fields),
                sheet.substring(0, fields) + "decision: \ngrade: \nquote: \nnote: \nreminder: swap labels\n"
                        + sheet.substring(fields + "decision: \ngrade: \nquote: \nnote: \n".length()))) {
            var e = assertThrows(IllegalStateException.class,
                    () -> BookReviewWorksheet.apply(inserted, BODY, BASE, SELECTION));
            assertEquals(p.pairId() + ": worksheet entry must end with its decision, grade, quote and note lines", e.getMessage());
        }
        // An editor that drops the final blank line is fine.
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n",
                BookReviewWorksheet.apply(sheet.substring(0, sheet.length() - 1), BODY, BASE, SELECTION));
    }

    @Test
    void rejectsAnEntryWithADeletedOrMisspelledField() {
        Pair p = SELECTION.getFirst();
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        int at = sheet.indexOf("decision: \n", sheet.indexOf("## " + p.pairId() + "\n"));
        String deleted = sheet.substring(0, at) + sheet.substring(at + "decision: \n".length());
        String misspelled = sheet.substring(0, at) + "decison: \n" + sheet.substring(at + "decision: \n".length());
        for (String edited : List.of(deleted, misspelled)) {
            var e = assertThrows(IllegalStateException.class,
                    () -> BookReviewWorksheet.apply(edited, BODY, BASE, SELECTION));
            assertEquals(p.pairId() + ": worksheet entry must end with its decision, grade, quote and note lines", e.getMessage());
        }
    }

    @Test
    void rejectsANoteThatCopiesChunkText() {
        Pair p = SELECTION.getFirst();
        ChunkRef chunk = BASE.chunkMap().get(p.chunkKey());
        String text = new String(BODY, (int) chunk.byteStart(), 200, StandardCharsets.UTF_8);
        String[] words = text.trim().split("\\s+");
        String quote = String.join(" ", Arrays.copyOfRange(words, 2, 2 + BookReviewWorksheet.COPIED_WORDS));
        String sheet = fill(BookReviewWorksheet.render(BODY, BASE, SELECTION), p.pairId(), "CONFOUNDER", "0", quote,
                "the passage says " + quote);
        var e = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(sheet, BODY, BASE, SELECTION));
        assertTrue(e.getMessage().contains("note reproduces 6 or more consecutive words of the chunk"), e.getMessage());
        // Changing case or punctuation does not hide a copy.
        assertTrue(BookReviewWorksheet.copiesChunkText(quote.toUpperCase().replace(" ", ", ") + ".", text));
        // A shorter shared run (a name, a short phrase) is a paraphrase and stays allowed.
        String shortRun = String.join(" ", Arrays.copyOfRange(words, 2, 2 + BookReviewWorksheet.COPIED_WORDS - 1));
        assertFalse(BookReviewWorksheet.copiesChunkText("mentions " + shortRun, text));
    }

    @Test
    void anOverlappingRepeatIsAnAmbiguousQuote() {
        byte[] repeated = BODY.clone();
        ChunkRef chunk = BASE.chunkMap().get(SELECTION.getFirst().chunkKey());
        byte[] run = "a a a".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(run, 0, repeated, (int) chunk.byteStart() + 1, run.length);
        // "a a" occurs at offsets 0-3 and 2-5 of the run: the second match overlaps the first.
        var e = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.locate(repeated, chunk, "a a", "UJ-x"));
        assertTrue(e.getMessage().contains("occurs more than once"), e.getMessage());
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
        // Book text that looks like a field (same byte length, so the chunk map still applies) is not a decision.
        byte[] body = BODY.clone();
        ChunkRef chunk = BASE.chunkMap().get(SELECTION.getFirst().chunkKey());
        byte[] line = "decision: EVIDENCE\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(line, 0, body, (int) chunk.byteStart(), line.length);
        String sheet = BookReviewWorksheet.render(body, BASE, SELECTION);
        assertTrue(sheet.contains("~~~text\ndecision: EVIDENCE\n"));
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n", BookReviewWorksheet.apply(sheet, body, BASE, SELECTION));
    }

    @Test
    void rejectsADecisionOnEditedOrSwappedContext() {
        Pair p = SELECTION.getFirst();
        String sheet = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        int at = sheet.indexOf("## " + p.pairId() + "\n");
        int end = sheet.indexOf("\n## ", at);
        String section = sheet.substring(at, end);
        String query = section.substring(section.indexOf("query: "), section.indexOf('\n', section.indexOf("query: ")));
        String declared = section.substring(section.indexOf("declared: "),
                section.indexOf('\n', section.indexOf("declared: ")));
        int text = section.indexOf("~~~text\n") + 8;
        for (String edited : List.of(
                section.replace(query, query + " (edited)"),
                section.replace(declared, declared.contains("NO_ANSWER") ? "declared: answerable"
                        : "declared: NO_ANSWER (v1: the book does not answer it)"),
                section.substring(0, text) + "x" + section.substring(text + 1),
                section.replace("\n\n~~~text", "\nchunk: " + KEY_OTHER + " bytes [0,1)\n\n~~~text"))) {
            String sheetWithEdit = sheet.substring(0, at) + edited + sheet.substring(end);
            var e = assertThrows(IllegalStateException.class,
                    () -> BookReviewWorksheet.apply(sheetWithEdit, BODY, BASE, SELECTION));
            assertEdited(e);
        }
    }

    @Test
    void acceptsALegacySheetOnlyWithLegacyEntriesThroughout() {
        String current = BookReviewWorksheet.render(BODY, BASE, SELECTION);
        String legacy = current.replace(BookReviewWorksheet.PROTOCOL_LINE + BookQueryDatasetV2.REVIEW_PROTOCOL + "\n\n",
                "");
        for (Pair p : SELECTION) {
            legacy = withChunkLine(legacy, p);
        }
        assertEquals(BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL, BookReviewWorksheet.protocolOf(legacy));
        assertEquals(BookQueryDatasetV2.REVIEW_HEADER + "\n", BookReviewWorksheet.apply(legacy, BODY, BASE, SELECTION));

        // A current-protocol sheet with a legacy entry would claim the stricter blinding while exposing the key.
        Pair p = SELECTION.getFirst();
        String mixed = withChunkLine(current, p);
        var e = assertThrows(IllegalStateException.class, () -> BookReviewWorksheet.apply(mixed, BODY, BASE, SELECTION));
        assertEdited(e);
        // And a legacy sheet must not drop the chunk line from an entry either.
        int line = legacy.indexOf("\nchunk: ", legacy.indexOf("## " + p.pairId() + "\n"));
        String stripped = legacy.substring(0, line) + legacy.substring(legacy.indexOf('\n', line + 1));
        var missing = assertThrows(IllegalStateException.class,
                () -> BookReviewWorksheet.apply(stripped, BODY, BASE, SELECTION));
        assertEdited(missing);
    }

    /** The whole-sheet check: anything but a field value differs from the rendering. */
    private static void assertEdited(IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("worksheet differs from its rendering at line ")
                && e.getMessage().endsWith("only the decision, grade, quote and note values may be edited"),
                e.getMessage());
    }

    private static String withChunkLine(String sheet, Pair p) {
        ChunkRef chunk = BASE.chunkMap().get(p.chunkKey());
        int at = sheet.indexOf("~~~text", sheet.indexOf("## " + p.pairId() + "\n")) - 1;
        return sheet.substring(0, at) + "chunk: " + p.chunkKey() + " bytes [" + chunk.byteStart() + ","
                + chunk.byteEnd() + ")\n" + sheet.substring(at);
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
