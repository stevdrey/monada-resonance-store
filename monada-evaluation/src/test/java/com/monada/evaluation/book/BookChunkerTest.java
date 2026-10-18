package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.evaluation.book.BookChunk.SectionKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookChunkerTest {
    private static final String HEADING = "CHAPTER [IVXLC]+\\.";

    private static BookChunkingPolicy policy(int max, int overlap) {
        return new BookChunkingPolicy("test-policy", HEADING, max, overlap);
    }

    private static BookChunker.Result chunk(String body, BookChunkingPolicy policy) throws BookInputException {
        return BookChunker.chunk(VerifiedBookSource.ofBytes("test-corpus", "1", "0".repeat(64),
                body.getBytes(StandardCharsets.UTF_8)), policy);
    }

    private static List<String> texts(BookChunker.Result result) {
        return result.chunks().stream().map(BookChunk::text).toList();
    }

    /** Every chunk re-resolves to its source span by bytes and by code points, and its digest matches. */
    private static void assertResolvable(String body, BookChunker.Result result) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int[] codePoints = body.codePoints().toArray();
        for (BookChunk chunk : result.chunks()) {
            String byBytes = new String(bytes, (int) chunk.byteStart(), (int) chunk.utf8Bytes(), StandardCharsets.UTF_8);
            String byCodePoints = new String(codePoints, (int) chunk.codePointStart(),
                    (int) (chunk.codePointEnd() - chunk.codePointStart()));
            assertEquals(chunk.text(), byBytes, chunk.key());
            assertEquals(chunk.text(), byCodePoints, chunk.key());
            assertEquals(VerifiedBookSource.sha256(chunk.text().getBytes(StandardCharsets.UTF_8)), chunk.sha256());
            assertTrue(chunk.utf8Bytes() <= result.policy().maxChunkBytes(), chunk.key());
        }
    }

    @Test
    void chaptersStartAtExactHeadingLinesAndFrontMatterIsKept() throws Exception {
        String body = "Title page\n\n CHAPTER I.  indented contents entry\n\nCHAPTER I.\nOne\n\nFirst text.\n\n"
                + "CHAPTER II.\nTwo\n\nSecond text.\n";
        BookChunker.Result result = chunk(body, policy(2048, 0));

        assertTrue(result.frontMatter());
        assertEquals(2, result.chapterCount());
        assertEquals(List.of("Title page\n\n CHAPTER I.  indented contents entry", "CHAPTER I.\nOne\n\nFirst text.",
                "CHAPTER II.\nTwo\n\nSecond text."), texts(result));
        BookChunk front = result.chunks().get(0);
        assertEquals(SectionKind.FRONT_MATTER, front.sectionKind());
        assertEquals(0, front.sectionOrdinal());
        assertNull(front.sectionHeading());
        BookChunk second = result.chunks().get(2);
        assertEquals(SectionKind.CHAPTER, second.sectionKind());
        assertEquals(2, second.sectionOrdinal());
        assertEquals("CHAPTER II.", second.sectionHeading());
        assertEquals("test-corpus/test-policy/s002/c0000", second.key());
        assertEquals(10, second.lineStart());
        assertEquals(13, second.lineEnd());
        assertResolvable(body, result);
    }

    @Test
    void blankFrontMatterIsNotASectionAndChapterOrdinalsStayStable() throws Exception {
        BookChunker.Result result = chunk("\n\nCHAPTER I.\nText.\n", policy(2048, 0));
        assertFalse(result.frontMatter());
        assertEquals(1, result.chunks().getFirst().sectionOrdinal());
        assertEquals("test-corpus/test-policy/s001/c0000", result.chunks().getFirst().key());
    }

    @Test
    void bodyWithoutHeadingsIsOneFrontMatterSection() throws Exception {
        BookChunker.Result result = chunk("Only prose.\n\nMore prose.\n", policy(2048, 0));
        assertTrue(result.frontMatter());
        assertEquals(0, result.chapterCount());
        assertEquals(List.of("Only prose.\n\nMore prose."), texts(result));
    }

    @Test
    void paragraphsArePackedUpToTheExactLimit() throws Exception {
        // "aaaaa\n\nbbbbbbbb" is 15 bytes; adding "\n\ncc" makes 19.
        String body = "aaaaa\n\nbbbbbbbb\n\ncc\n";
        assertEquals(List.of("aaaaa\n\nbbbbbbbb", "cc"), texts(chunk(body, policy(16, 0))));
        assertEquals(List.of("aaaaa\n\nbbbbbbbb", "cc"), texts(chunk(body, policy(17, 0))));
        assertEquals(List.of("aaaaa\n\nbbbbbbbb\n\ncc"), texts(chunk(body, policy(19, 0))));
    }

    @Test
    void oversizedParagraphSplitsAtWhitespace() throws Exception {
        String body = "alpha beta gamma delta epsilon zeta\n";
        BookChunker.Result result = chunk(body, policy(16, 0));
        assertEquals(List.of("alpha beta gamma", "delta epsilon", "zeta"), texts(result));
        assertResolvable(body, result);
    }

    @Test
    void oversizedParagraphWithoutWhitespaceIsHardSplitWithoutLosingText() throws Exception {
        String word = "x".repeat(40);
        BookChunker.Result result = chunk(word + "\n", policy(16, 0));
        assertEquals(List.of("x".repeat(16), "x".repeat(16), "x".repeat(8)), texts(result));
        assertEquals(word, String.join("", texts(result)));
    }

    @Test
    void hardSplitNeverBreaksASurrogatePair() throws Exception {
        // 15 ASCII bytes then a 4-byte emoji: the emoji cannot fit in a 16-byte piece and must move whole.
        String body = "y".repeat(15) + "🌊" + "z".repeat(5) + "\n";
        BookChunker.Result result = chunk(body, policy(16, 0));
        assertEquals(List.of("y".repeat(15), "🌊" + "zzzzz"), texts(result));
        BookChunk second = result.chunks().get(1);
        assertEquals(15, second.byteStart());
        assertEquals(15, second.codePointStart());
        assertEquals(24, second.byteEnd());
        assertEquals(21, second.codePointEnd());
        assertResolvable(body, result);
    }

    @Test
    void hardSplitDoesNotSeparateACombiningMarkFromItsBase() throws Exception {
        // "q" + U+0307 has no precomposed form, so it stays decomposed under NFC. A plain cut at 16 bytes would
        // fall right before the combining mark; the cut moves before the base letter instead.
        String body = "w".repeat(15) + "q̇" + "v".repeat(4) + "\n";
        BookChunker.Result result = chunk(body, policy(16, 0));
        assertEquals(List.of("w".repeat(15), "q̇vvvv"), texts(result));
        assertResolvable(body, result);
    }

    @Test
    void combiningSequenceLongerThanTheBudgetIsRejectedNotDetached() {
        // "q" plus nine U+0307 marks is 19 bytes, NFC-stable, and cannot fit a 16-byte piece in one sequence.
        String body = "q" + "\u0307".repeat(9) + "\n";
        BookInputException e = assertThrows(BookInputException.class, () -> chunk(body, policy(16, 0)));
        assertEquals(BookInputException.Reason.INVALID_INPUT, e.reason());
        assertTrue(e.getMessage().contains("byte offset 0"), e.getMessage());
    }

    @Test
    void multiByteTextReportsDistinctByteAndCodePointOffsets() throws Exception {
        String body = "Café — déjà vu.\n\nCHAPTER I.\nÜber.\n";
        BookChunker.Result result = chunk(body, policy(2048, 0));
        BookChunk front = result.chunks().get(0);
        assertEquals(15, front.codePointEnd());
        assertEquals(20, front.byteEnd());
        BookChunk chapter = result.chunks().get(1);
        assertEquals(17, chapter.codePointStart());
        assertEquals(22, chapter.byteStart());
        assertResolvable(body, result);
    }

    @Test
    void repeatedContentGetsDistinctKeysButCollidesOnContentDerivedStoreId() throws Exception {
        String repeated = "The tide turns twice a day.";
        String body = "CHAPTER I.\n" + "a".repeat(30) + "\n\n" + repeated + "\n\nCHAPTER II.\n" + "b".repeat(30)
                + "\n\n" + repeated + "\n";
        BookChunker.Result result = chunk(body, policy(32, 0));
        List<BookChunk> same = result.chunks().stream().filter(c -> c.text().equals(repeated)).toList();

        assertEquals(2, same.size());
        assertNotEquals(same.get(0).key(), same.get(1).key());
        assertEquals(same.get(0).sha256(), same.get(1).sha256());
        assertNull(same.get(0).duplicateOf());
        assertEquals(same.get(0).key(), same.get(1).duplicateOf());
        // Store atom IDs are derived from content only, so both chunks would map to one atom; the sidecar key
        // is what keeps their provenance apart.
        assertEquals(KnowledgeAtom.text(same.get(0).text()).id(), KnowledgeAtom.text(same.get(1).text()).id());
    }

    @Test
    void overlapRepeatsWholeWordsFromThePreviousChunkWithinTheBound() throws Exception {
        String body = "one two three four five six seven eight nine ten\n";
        BookChunker.Result result = chunk(body, policy(24, 8));
        // Cores are packed to 24 - 8 = 16 bytes; each later chunk starts at the earliest word within 8 bytes before
        // its core, so every chunk stays within 24 bytes.
        assertEquals(List.of("one two three", "three four five six", "six seven eight nine", "nine ten"),
                texts(result));
        assertResolvable(body, result);
    }

    @Test
    void overlapNeverCrossesAChapterBoundary() throws Exception {
        String body = "CHAPTER I.\nend of one\n\nCHAPTER II.\nstart of two\n";
        BookChunker.Result result = chunk(body, policy(24, 8));
        // Inside chapter I the second chunk does overlap ("I." repeats); chapter II starts clean at its heading.
        assertEquals(List.of("CHAPTER I.\nend", "I.\nend of one", "CHAPTER II.", "II.\nstart of two"),
                texts(result));
        assertEquals(List.of(1, 1, 2, 2), result.chunks().stream().map(BookChunk::sectionOrdinal).toList());
    }

    @Test
    void noNonWhitespaceTextDisappears() throws Exception {
        String body = "Front matter\twith tabs.\n\n   \n  CHAPTER I.\n\nCHAPTER I.\n  indented line\n"
                + "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod\n\n\n \nend\n";
        for (int max : new int[] {16, 24, 40, 2048}) {
            BookChunker.Result result = chunk(body, policy(max, 0));
            String all = String.join("", texts(result)).replaceAll("[\\s ]", "");
            assertEquals(body.replaceAll("[\\s ]", ""), all, "max=" + max);
            assertResolvable(body, result);
        }
    }

    @Test
    void sameInputGivesIdenticalChunksAndSidecarBytes() throws Exception {
        String body = "Front.\n\nCHAPTER I.\nCafé 🌊 text that is long enough to split somewhere.\n";
        BookChunkOutputWriter.Output first = BookChunkOutputWriter.render(chunk(body, policy(20, 4)));
        BookChunkOutputWriter.Output second = BookChunkOutputWriter.render(chunk(body, policy(20, 4)));
        assertEquals(new String(first.chunks(), StandardCharsets.UTF_8), new String(second.chunks(),
                StandardCharsets.UTF_8));
        assertEquals(new String(first.sidecar(), StandardCharsets.UTF_8), new String(second.sidecar(),
                StandardCharsets.UTF_8));
    }

    @Test
    void policyParametersAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> policy(BookChunkingPolicy.MIN_CHUNK_BYTES - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> policy(32, 16));
        assertThrows(IllegalArgumentException.class, () -> policy(32, -1));
        assertThrows(IllegalArgumentException.class, () -> new BookChunkingPolicy("Bad Id", HEADING, 32, 0));
        assertThrows(IllegalArgumentException.class, () -> new BookChunkingPolicy("ok", "CHAPTER (", 32, 0));
        assertEquals(2048, BookChunkingPolicy.V1.maxChunkBytes());
        assertEquals(0, BookChunkingPolicy.V1.overlapBytes());
    }

    @Test
    void jsonEscapingIsMinimalAndDeterministic() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\\u0001\\u2028é\"",
                BookChunkOutputWriter.string("a\"b\\c\nd\te\u0001 é"));
        assertEquals("null", BookChunkOutputWriter.string(null));
    }
}
