package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookJudgmentCoverage.Query;
import com.monada.evaluation.book.BookJudgmentCoverage.Result;
import com.monada.evaluation.book.BookJudgmentCoverage.Row;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookUnjudgedSelection.Hit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The v1/v2 projection on the committed inputs, and its fail-closed checks on edited ones (Issue #184). */
class BookJudgmentCoverageTest {
    private static final String KEY = "book-corpus-v1/book-chunking-v1/";
    private static byte[] REPORT;
    private static BookQueryDatasetV2 V2;
    private static Result RESULT;

    @BeforeAll
    static void load() throws Exception {
        // Gradle runs tests with the module directory as working directory.
        REPORT = Files.readAllBytes(Path.of(System.getProperty("user.dir"))
                .resolve("../" + BookUnjudgedSelection.BASELINE_REPORT).normalize());
        V2 = BookQueryDatasetV2.load();
        RESULT = BookJudgmentCoverage.load(REPORT);
    }

    @Test
    void theRankingIsTheFrozenTraceUnchanged() {
        List<Hit> hits = BookUnjudgedSelection.parseTrace(REPORT, V2.base());
        List<Row> rows = RESULT.rows();
        assertEquals(320, rows.size());
        assertEquals(hits.size(), rows.size());
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            Row r = rows.get(i);
            assertEquals(List.of(h.queryId(), String.valueOf(h.rank()), h.atomId(), h.score(), h.chunkKey(),
                    h.label()), List.of(r.queryId(), String.valueOf(r.rank()), r.atomId(), r.score(), r.chunkKey(),
                    r.v1()));
        }
        assertEquals(hits.stream().map(Hit::queryId).distinct().toList(),
                RESULT.queries().stream().map(Query::id).toList(), "query order is the frozen trace order");
        assertEquals(BookUnjudgedSelection.BASELINE_SHA256, RESULT.baselineSha256());
    }

    @Test
    void devGainsTheReviewedLabelsAndHoldoutIsUntouched() {
        List<Query> dev = RESULT.in("DEV");
        assertEquals(59, BookJudgmentCoverage.transitions(dev, r -> true, "U", "C0"));
        assertEquals(1, BookJudgmentCoverage.transitions(dev, r -> true, "U", "E1"));
        assertEquals(60, dev.stream().mapToInt(q -> q.added().size()).sum());
        List<Query> holdout = RESULT.in("HOLDOUT");
        assertTrue(holdout.stream().allMatch(q -> q.added().isEmpty()));
        assertTrue(holdout.stream().flatMap(q -> q.rows().stream()).noneMatch(Row::changed));
        assertTrue(holdout.stream().allMatch(q -> q.v1().equals(q.v2())), "HOLDOUT metrics are identical");
        // Unselected hits stay unknown: v2 never turns U into a negative by default.
        assertEquals(205, BookJudgmentCoverage.count(RESULT.in("ALL"), 10, Row::v2, "U"));
    }

    @Test
    void theOneAddedEvidenceChangesOneDenominatorOnly() {
        Query mul05 = RESULT.queries().stream().filter(q -> q.id().equals("MUL-05")).findFirst().orElseThrow();
        assertEquals(3, mul05.v1().relevant());
        assertEquals(4, mul05.v2().relevant());
        assertEquals(0.0, mul05.v1().reciprocalRank());
        assertEquals(1.0, mul05.v2().reciprocalRank());
        assertEquals(List.of("MUL-05"), RESULT.queries().stream().filter(q -> q.v1().relevant() != q.v2().relevant())
                .map(Query::id).toList());
    }

    @Test
    void theV1ProjectionReproducesTheFrozenTables() {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        for (String partition : BookJudgmentCoverage.PARTITIONS) {
            for (String line : BookJudgmentCoverage.partitionLines(partition, RESULT.in(partition))) {
                assertTrue(text.contains("\n" + line + "\n"), line);
            }
        }
    }

    @Test
    void theReportIsDeterministicAndClaimsNoImprovement() {
        String first = BookJudgmentCoverageReport.render(RESULT);
        assertEquals(first, BookJudgmentCoverageReport.render(BookJudgmentCoverage.load(REPORT)));
        assertTrue(first.contains("status=LABEL COVERAGE ONLY"));
        assertTrue(first.contains("rankingDigest=" + RESULT.rankingDigest()));
        assertTrue(first.contains("new searches 0"));
        assertTrue(!first.contains("\r") && first.endsWith("\n"));
    }

    // ---- drifted ranking inputs fail closed

    @Test
    void aReorderedRankIsRejected() {
        byte[] bytes = edited(firstHit("AMB-01"), firstHit("AMB-01").replace("  #1 ", "  #3 "));
        assertRejected(bytes, V2, "ranks are not contiguous");
    }

    @Test
    void aRaisedScoreBelowRankOneIsRejected() {
        String line = hitLine("AMB-01", 2);
        byte[] bytes = edited(line, line.replace(" 0.266776 ", " 0.966776 "));
        assertRejected(bytes, V2, "scores are not descending");
    }

    @Test
    void aChangedAtomOrScoreChangesTheRankingDigest() {
        String line = hitLine("AMB-01", 2);
        byte[] bytes = edited(line, line.replace(" 0.266776 ", " 0.266777 "));
        Result drifted = BookJudgmentCoverage.compare(bytes, BookQueryDataset.sha256(bytes), V2);
        assertNotEquals(RESULT.rankingDigest(), drifted.rankingDigest());
        // The pinned baseline itself never accepts these bytes.
        assertThrows(IllegalStateException.class, () -> BookJudgmentCoverage.load(bytes));
    }

    @Test
    void aFrozenMetricThatTheProjectionDoesNotReproduceIsRejected() {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        byte[] bytes = text.replaceFirst("MRR 0\\.1705 \\(n=20\\)", "MRR 0.1706 (n=20)")
                .getBytes(StandardCharsets.UTF_8);
        assertRejected(bytes, V2, "DEV: v1 projection does not reproduce the frozen line");
    }

    // ---- drifted label inputs fail closed

    @Test
    void anUnreviewedV2IsRejected() {
        Properties manifest = V2.manifest();
        manifest.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        BookQueryDatasetV2 pending = new BookQueryDatasetV2(manifest, V2.base(), V2.selection(), V2.reviews(),
                V2.cases());
        assertRejected(REPORT, pending, "is not REVIEWED");
    }

    @Test
    void aHoldoutJudgmentChangeIsRejected() {
        Case holdout = V2.cases().stream().filter(c -> c.split() == Split.HOLDOUT).findFirst().orElseThrow();
        String chunk = BookUnjudgedSelection.parseTrace(REPORT, V2.base()).stream()
                .filter(h -> h.queryId().equals(holdout.id()) && h.unjudged()).findFirst().orElseThrow().chunkKey();
        assertRejected(REPORT, withCase(holdout.id(), c -> addJudgment(c, chunk)), "HOLDOUT judgments must stay");
    }

    @Test
    void anOverwrittenV1JudgmentIsRejected() {
        BookQueryDatasetV2 edited = withCase("LIT-01", c -> {
            List<Judgment> all = new ArrayList<>(c.judgments());
            Judgment j = all.getFirst();
            all.set(0, new Judgment(j.chunkKey(), j.byteStart(), j.byteEnd(), j.spanSha256(), 1, j.role(), j.note()));
            return new Case(c.id(), c.split(), c.category(), c.answerable(), c.text(), c.rationale(), all);
        });
        assertRejected(REPORT, edited, "drops or overwrites a book-queries-v1 judgment");
    }

    @Test
    void anAddedJudgmentOutsideTheSelectionIsRejected() {
        Case dev = V2.cases().stream().filter(c -> c.id().equals("LIT-01")).findFirst().orElseThrow();
        assertEquals(Split.DEV, dev.split());
        assertRejected(REPORT, withCase("LIT-01", c -> addJudgment(c, KEY + "s012/c0000")), "is not a selected pair");
    }

    private static Case addJudgment(Case c, String chunk) {
        List<Judgment> all = new ArrayList<>(c.judgments());
        all.add(new Judgment(chunk, 0, 1, "0".repeat(64), 0, Role.CONFOUNDER, "edited"));
        return new Case(c.id(), c.split(), c.category(), c.answerable(), c.text(), c.rationale(), all);
    }

    private static BookQueryDatasetV2 withCase(String id, UnaryOperator<Case> edit) {
        List<Case> cases = V2.cases().stream().map(c -> c.id().equals(id) ? edit.apply(c) : c).toList();
        return new BookQueryDatasetV2(V2.manifest(), V2.base(), V2.selection(), V2.reviews(), cases);
    }

    private static void assertRejected(byte[] report, BookQueryDatasetV2 v2, String message) {
        var e = assertThrows(IllegalStateException.class,
                () -> BookJudgmentCoverage.compare(report, BookQueryDataset.sha256(report), v2));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static String firstHit(String query) {
        return hitLine(query, 1);
    }

    private static String hitLine(String query, int rank) {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        int at = text.indexOf("\n" + query + " ", text.indexOf("== Per-query trace"));
        int hit = text.indexOf("\n  #" + rank + " ", at) + 1;
        return text.substring(hit, text.indexOf('\n', hit));
    }

    private static byte[] edited(String line, String replacement) {
        String text = new String(REPORT, StandardCharsets.UTF_8);
        int at = text.indexOf(line, text.indexOf("== Per-query trace"));
        return (text.substring(0, at) + replacement + text.substring(at + line.length()))
                .getBytes(StandardCharsets.UTF_8);
    }
}
