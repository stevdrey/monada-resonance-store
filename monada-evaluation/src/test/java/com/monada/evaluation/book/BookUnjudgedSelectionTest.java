package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookUnjudgedSelection.Hit;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import com.monada.evaluation.book.BookUnjudgedSelection.Stratum;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The predeclared selection of book-queries-v2: reproducible from the frozen trace, DEV-only and stratified. */
class BookUnjudgedSelectionTest {
    private static final String KEY = "book-corpus-v1/book-chunking-v1/";
    private static BookQueryDataset BASE;
    private static byte[] REPORT;

    @BeforeAll
    static void load() throws Exception {
        BASE = BookQueryDataset.load();
        // Gradle runs tests with the module directory as working directory.
        REPORT = Files.readAllBytes(Path.of(System.getProperty("user.dir"))
                .resolve("../" + BookUnjudgedSelection.BASELINE_REPORT).normalize());
    }

    @Test
    void theFrozenBaselineIsUnchanged() {
        assertEquals(BookUnjudgedSelection.BASELINE_SHA256, BookQueryDataset.sha256(REPORT));
    }

    @Test
    void theCommittedReceiptIsReproducedByteForByte() {
        String receipt = BookUnjudgedSelection.render(
                BookUnjudgedSelection.select(BookUnjudgedSelection.parseTrace(REPORT, BASE)));
        assertArrayEquals(BookQueryDatasetV2.resource("selection.psv"), receipt.getBytes(StandardCharsets.UTF_8));
        assertEquals(BookUnjudgedSelection.RECEIPT_SHA256, BookQueryDataset.sha256(receipt.getBytes(StandardCharsets.UTF_8)),
                "the receipt digest pinned in code must be the one the rule reproduces");
    }

    @Test
    void theTraceHasTheReportedCounts() {
        List<Hit> hits = BookUnjudgedSelection.parseTrace(REPORT, BASE);
        assertEquals(320, hits.size());
        long answerableU = hits.stream().filter(h -> h.category() != Category.NO_ANSWER && h.unjudged()).count();
        assertEquals(256, answerableU, "256 of the 270 answerable top-10 occurrences are U");
        assertEquals(229, hits.stream().filter(h -> h.split() == Split.DEV && h.unjudged()).count());
    }

    @Test
    void theSelectionIsStratifiedDevOnlyAndDistinct() {
        List<Pair> pairs = BookUnjudgedSelection.select(BookUnjudgedSelection.parseTrace(REPORT, BASE));
        assertEquals(BookUnjudgedSelection.TARGET, pairs.size());
        assertTrue(pairs.stream().allMatch(p -> p.split() == Split.DEV), "HOLDOUT must never be selected");
        assertEquals(pairs.size(), pairs.stream().map(p -> p.queryId() + "|" + p.chunkKey()).distinct().count());
        Map<Stratum, Long> strata = pairs.stream().collect(Collectors.groupingBy(Pair::stratum, Collectors.counting()));
        assertEquals(Map.of(Stratum.TOP1, 22L, Stratum.TOP3, 24L, Stratum.HUB, 4L, Stratum.SAMPLE, 10L), strata);
        assertTrue(pairs.stream().filter(p -> p.stratum() == Stratum.TOP1).allMatch(p -> p.rank() == 1));
        assertTrue(pairs.stream().filter(p -> p.stratum() == Stratum.TOP3)
                .allMatch(p -> p.rank() == 2 || p.rank() == 3));
        assertEquals(BASE.casesOf(Split.DEV).size(),
                pairs.stream().filter(p -> p.stratum() == Stratum.TOP3).map(Pair::queryId).distinct().count(),
                "every DEV query gets a rank-2/3 pair");
        for (Category category : Category.values()) {
            assertTrue(pairs.stream().anyMatch(p -> p.category() == category), "no pair for " + category);
        }
    }

    @Test
    void selectsEveryEligiblePairWhenFewerThanTheTarget() {
        List<Hit> hits = List.of(
                hit("LIT-01", Split.DEV, 1, "U", "s001/c0001"),
                hit("LIT-01", Split.DEV, 2, "E2", "s001/c0004"),
                hit("LIT-01", Split.DEV, 5, "U", "s002/c0001"),
                hit("LIT-02", Split.HOLDOUT, 1, "U", "s001/c0002"),
                hit("NOA-01", Split.DEV, 3, "U", "s001/c0001"));
        List<Pair> pairs = BookUnjudgedSelection.select(hits);
        assertEquals(List.of("LIT-01|s001/c0001|TOP1", "NOA-01|s001/c0001|TOP3", "LIT-01|s002/c0001|HUB"),
                pairs.stream().map(p -> p.queryId() + "|" + p.chunkKey().substring(KEY.length()) + "|" + p.stratum())
                        .toList());
        assertEquals(2, pairs.getFirst().chunkDevUnjudged(), "hub count is DEV unjudged occurrences of the chunk");
    }

    @Test
    void aPairIdCollisionFailsInsteadOfSkippingAPair() {
        // Two distinct eligible pairs mapped to the same id must not make the second look already chosen.
        List<Hit> hits = List.of(hit("LIT-01", Split.DEV, 1, "U", "s001/c0001"),
                hit("LIT-03", Split.DEV, 1, "U", "s002/c0001"));
        var e = assertThrows(IllegalStateException.class,
                () -> BookUnjudgedSelection.select(hits, (query, chunk) -> "UJ-00000000"));
        assertTrue(e.getMessage().startsWith("pair id collision: UJ-00000000"), e.getMessage());
    }

    @Test
    void aSameTextAliasIsSelectedUnderItsFirstKey() {
        String line = firstHitLine(REPORT, "LIT-01");
        String key = line.substring(line.indexOf(KEY), line.indexOf(" atom="));
        String alias = line + " sameText=[" + key + ", " + KEY + "s012/c0001]";
        List<Hit> hits = BookUnjudgedSelection.parseTrace(edited(line, alias), BASE, sha(edited(line, alias)));
        Hit hit = hits.stream().filter(h -> h.queryId().equals("LIT-01") && h.rank() == 1).findFirst().orElseThrow();
        assertEquals(key, hit.chunkKey());
        assertEquals(List.of(key, KEY + "s012/c0001"), hit.keys());
    }

    @Test
    void rejectsAnotherBaseline() {
        byte[] other = (new String(REPORT, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(IllegalStateException.class, () -> BookUnjudgedSelection.parseTrace(other, BASE));
        assertTrue(e.getMessage().startsWith("baseline report SHA-256 is "), e.getMessage());
    }

    @Test
    void rejectsATraceWhoseLabelDisagreesWithV1() {
        String line = firstHitLine(REPORT, "LIT-01");
        byte[] bytes = edited(line, line.replace(" U  ", " E2 "));
        var e = assertThrows(IllegalStateException.class,
                () -> BookUnjudgedSelection.parseTrace(bytes, BASE, sha(bytes)));
        assertTrue(e.getMessage().contains("differs from book-queries-v1"), e.getMessage());
    }

    @Test
    void rejectsATraceWithAMovedSplit() {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        byte[] bytes = text.replace("\nLIT-01 DEV LITERAL", "\nLIT-01 HOLDOUT LITERAL").getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(IllegalStateException.class,
                () -> BookUnjudgedSelection.parseTrace(bytes, BASE, sha(bytes)));
        assertEquals("LIT-01: trace split/category differs from book-queries-v1", e.getMessage());
    }

    @Test
    void rejectsAReceiptThatIsNotValidUtf8() {
        byte[] bytes = BookQueryDatasetV2.resource("selection.psv").clone();
        bytes[bytes.length - 2] = (byte) 0xFF;
        var e = assertThrows(IllegalStateException.class, () -> BookUnjudgedSelection.parse(bytes));
        assertEquals("selection.psv is not valid UTF-8", e.getMessage());
    }

    @Test
    void rejectsAReceiptWithAForgedPairId() {
        List<String> rows = new ArrayList<>(List.of(new String(BookQueryDatasetV2.resource("selection.psv"),
                StandardCharsets.UTF_8).split("\n")));
        rows.set(1, "UJ-00000000" + rows.get(1).substring(11));
        byte[] bytes = (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(IllegalStateException.class, () -> BookUnjudgedSelection.parse(bytes));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    private static Hit hit(String query, Split split, int rank, String label, String chunk) {
        var c = BASE.cases().stream().filter(x -> x.id().equals(query)).findFirst().orElseThrow();
        return new Hit(query, split, c.category(), rank, "0.100000", label, KEY + chunk, "atom-" + chunk,
                List.of(KEY + chunk));
    }

    private static String firstHitLine(byte[] report, String query) {
        String text = new String(report, StandardCharsets.UTF_8);
        int at = text.indexOf("\n" + query + " ", text.indexOf("== Per-query trace"));
        int hit = text.indexOf("\n  #1 ", at) + 1;
        return text.substring(hit, text.indexOf('\n', hit));
    }

    private static byte[] edited(String line, String replacement) {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        int at = text.indexOf(line, text.indexOf("== Per-query trace"));
        return (text.substring(0, at) + replacement + text.substring(at + line.length()))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] bytes) {
        return BookQueryDataset.sha256(bytes);
    }
}
