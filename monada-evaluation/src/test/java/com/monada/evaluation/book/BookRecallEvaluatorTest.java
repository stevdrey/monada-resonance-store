package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.evaluation.book.BookRecallEvaluator.Result;
import com.monada.evaluation.book.BookRecallEvaluator.RetrieverFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Book recall evaluation over the seeded original fixture: scripted retrieval pins every metric and failure class
 * (positive, repeated-text, multi-relevant, confusable and negative queries), the real API proves the report is
 * deterministic, and foreign inputs, invalid gold and unstable ordering fail.
 */
class BookRecallEvaluatorTest {
    @TempDir
    Path dir;

    private Path work;
    private BookQueryDataset dataset;

    @BeforeEach
    void seed() throws Exception {
        work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        dataset = BookRecallFixture.dataset(work);
    }

    /** Scripted retrieval: query text to chunk suffixes, scored 0.9, 0.8, ... in that order. */
    private RetrieverFactory scripted(Map<String, List<String>> ranking) throws Exception {
        Map<String, List<Hit>> hits = new HashMap<>();
        for (var entry : ranking.entrySet()) {
            List<Hit> list = new ArrayList<>();
            for (int i = 0; i < entry.getValue().size(); i++) {
                list.add(new Hit(BookRecallFixture.atomId(work, entry.getValue().get(i)), 0.9 - 0.1 * i));
            }
            hits.put(entry.getKey(), list);
        }
        return copy -> (query, k) -> hits.getOrDefault(query, List.of());
    }

    private Map<String, List<String>> script() {
        return Map.of(
                "How many gulls circled the breakwater?", List.of("s000/c0000", "s001/c0000"),
                "The tide turns twice a day.", List.of("s002/c0003"),
                "Where does a lantern appear?", List.of("s002/c0002", "s002/c0004"),
                "Which song names the rope and the oar?", List.of("s002/c0002", "s000/c0000", "s001/c0001"),
                "What colour is the keeper's coat?", List.of("s002/c0001"));
    }

    private static CaseResult byId(Result result, String id) {
        return result.cases().stream().filter(r -> r.query().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void scriptedRetrievalPinsMetricsLabelsAndFailureClasses() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir);

        assertEquals(List.of("CON-01", "LIT-01", "LIT-02", "LIT-03", "MUL-01", "NOA-01"),
                result.cases().stream().map(r -> r.query().id()).toList(), "queries are reported in id order");

        CaseResult positive = byId(result, "LIT-01");
        assertEquals(BookRecallFailure.CHUNK_BOUNDARY_GAP, positive.failure(), "front matter is adjacent to evidence");
        assertEquals(2, positive.firstRelevantRank());
        assertEquals(0.5, positive.reciprocalRank());
        assertEquals(List.of("U", "E2"), positive.hits().stream().map(BookRecallEvaluator.RankedHit::label).toList());

        CaseResult repeated = byId(result, "LIT-02");
        assertEquals(BookRecallFailure.NONE, repeated.failure(), "gold on the repeat counts for the shared atom");
        assertEquals(BookRecallFixture.KEY + "s002/c0003", repeated.hits().getFirst().key());
        assertEquals(List.of(BookRecallFixture.KEY + "s002/c0003", BookRecallFixture.KEY + "s002/c0006"),
                repeated.hits().getFirst().keys());
        assertEquals("E2", repeated.hits().getFirst().label());
        assertEquals(1.0, repeated.recall().get(10));

        assertEquals(BookRecallFailure.NO_RELEVANT_RESULT, byId(result, "LIT-03").failure());
        assertEquals(0.0, byId(result, "LIT-03").precision().get(1));

        CaseResult multi = byId(result, "MUL-01");
        assertEquals(BookRecallFailure.MULTI_EVIDENCE_GAP, multi.failure());
        assertEquals(1.0, multi.precision().get(1));
        assertEquals(1.0 / 3, multi.precision().get(3), 1e-12);
        assertEquals(0.5, multi.recall().get(10));
        assertEquals(1.0, multi.directHit().get(1));

        CaseResult confusable = byId(result, "CON-01");
        assertEquals(BookRecallFailure.DISTRACTOR, confusable.failure());
        assertEquals(List.of("C0", "U", "E2"),
                confusable.hits().stream().map(BookRecallEvaluator.RankedHit::label).toList());

        CaseResult negative = byId(result, "NOA-01");
        assertEquals(BookRecallFailure.FALSE_POSITIVE, negative.failure());
        assertTrue(negative.precision().isEmpty(), "NO_ANSWER has no relevant set and no ranking metric");

        BookRecallEvaluator.Aggregate all = result.partitions().get(2);
        assertEquals("ALL", all.name());
        assertEquals(6, all.queries());
        assertEquals(5, all.answerable());
        assertEquals(1, all.negatives());
        assertEquals(1, all.negativesWithHits());
        assertEquals(0.4, all.hit().get(1), 1e-12);
        assertEquals((0.5 + 1 + 0 + 1 + 1.0 / 3) / 5, all.mrr(), 1e-12);
        assertEquals(1, all.confounderHits().get(1));
        assertEquals(3, all.unjudgedHits().get(10));
        assertEquals(5, result.partitions().get(0).answerable() + result.partitions().get(1).answerable(),
                "DEV and HOLDOUT partition the answerable queries");
    }

    @Test
    void negativeQueryThatReturnsNothingAbstains() throws Exception {
        Map<String, List<String>> ranking = new HashMap<>(script());
        ranking.remove("What colour is the keeper's coat?");
        Result result = BookRecallEvaluator.evaluate(work, dataset, scripted(ranking), dir);
        assertEquals(BookRecallFailure.ABSTAINED, byId(result, "NOA-01").failure());
        assertEquals(0, result.partitions().get(2).negativesWithHits());
    }

    @Test
    void thresholdExplorationCountsFalseHitsAndKeptEvidence() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir);
        var all = result.thresholds().stream().filter(p -> p.threshold() == 0.85).toList();
        // At 0.85 only rank-1 hits (0.9) survive: LIT-02, MUL-01 keep evidence; NOA-01 still returns a hit.
        assertEquals(2, all.stream().mapToInt(BookRecallEvaluator.ThresholdPoint::answerableKept).sum());
        assertEquals(1, all.stream().mapToInt(BookRecallEvaluator.ThresholdPoint::negativesWithHit).sum());
    }

    @Test
    void realApiReportIsDeterministicAndVersioned() throws Exception {
        String first = BookRecallReport.render(BookRecallEvaluator.evaluate(work, dataset,
                BookRecallEvaluator.DEFAULT_RETRIEVER, dir));
        String second = BookRecallReport.render(BookRecallEvaluator.evaluate(work, dataset,
                BookRecallEvaluator.DEFAULT_RETRIEVER, dir));
        assertEquals(first, second);
        assertTrue(first.contains("format=monada-book-recall-report\nformatVersion=1\n"));
        for (String field : List.of("corpus=book-chunking-fixture", "source.sha256=", "chunking.policy=fixture-max200",
                "chunking.maxChunkBytes=200", "chunking.overlapBytes=0", "queries.sha256=", "judgments.sha256=",
                "split.digest=", "profile=MonadaMemoryOptions.defaults()", "k=[1, 3, 5, 10]", "store.fingerprint=")) {
            assertTrue(first.contains("\n" + field), "missing " + field);
        }
        assertTrue(first.contains("-- Example 1: "), "failure examples are rendered");
        assertFalse(first.contains(dir.toString()), "no paths in the report");
        assertFalse(first.contains("The keeper wrote"), "no chunk text in the report");
        // Exact text recalls its own atom first, and the repeat's gold resolves to the shared atom.
        CaseResult repeated = byId(BookRecallEvaluator.evaluate(work, dataset), "LIT-02");
        assertEquals(1, repeated.firstRelevantRank());
        assertEquals(2, repeated.hits().getFirst().keys().size());
    }

    @Test
    void foreignCorpusInputIsRejected() throws Exception {
        Path other = BookRecallFixture.seededWork(dir, "other",
                new BookChunkingPolicy("fixture-max300", "CHAPTER [IVXLC]+\\.", 300, 0));
        BookQueryDataset foreign = BookRecallFixture.dataset(other, List.of(
                "LIT-01|LITERAL|How many gulls circled the breakwater?|Counted in chapter one."),
                List.of("LIT-01|s001/c0000|194|275|2|EVIDENCE|gull count"));
        BookInputException e = assertThrows(BookInputException.class,
                () -> BookRecallEvaluator.evaluate(work, foreign, scripted(script()), dir));
        assertEquals("foreign corpus input: the work directory's chunking.policy is fixture-max200, the judged"
                + " dataset expects fixture-max300", e.getMessage());
    }

    @Test
    void invalidGoldReferencesAreRejected() throws Exception {
        BookQueryDataset.Case lit = dataset.cases().stream().filter(c -> c.id().equals("LIT-01")).findFirst()
                .orElseThrow();
        var orphan = new BookQueryDataset.Judgment(BookRecallFixture.KEY + "s009/c0000", 0, 1, BookRecallFixture.SPAN,
                2, BookQueryDataset.Role.EVIDENCE, "unknown chunk");
        assertGold(withCase(new BookQueryDataset.Case("LIT-01", lit.split(), lit.category(), true, lit.text(),
                lit.rationale(), List.of(orphan))), "LIT-01: invalid gold reference to unknown chunk "
                + BookRecallFixture.KEY + "s009/c0000");
        assertGold(withCase(new BookQueryDataset.Case("LIT-01", lit.split(), lit.category(), true, lit.text(),
                lit.rationale(), List.of())), "LIT-01: empty gold, answerable query has no evidence");
        assertGold(withCase(new BookQueryDataset.Case("NOA-01", lit.split(), BookQueryDataset.Category.NO_ANSWER,
                false, lit.text(), lit.rationale(), lit.judgments())),
                "NOA-01: invalid gold, NO_ANSWER query has evidence");
        assertGold(new BookQueryDataset(dataset.manifest(), List.of(), dataset.chunkMap()),
                "the judged dataset has no queries");
    }

    private BookQueryDataset withCase(BookQueryDataset.Case replacement) {
        return new BookQueryDataset(dataset.manifest(), List.of(replacement), dataset.chunkMap());
    }

    private void assertGold(BookQueryDataset invalid, String message) {
        BookInputException e = assertThrows(BookInputException.class,
                () -> BookRecallEvaluator.evaluate(work, invalid, scripted(script()), dir));
        assertEquals(message, e.getMessage());
    }

    @Test
    void storeWithRecordedFeedbackIsRejected() throws Exception {
        Path log = work.resolve("store/feedback/feedback-000001.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "{\"recorded\":\"feedback\"}\n");
        BookInputException e = assertThrows(BookInputException.class,
                () -> BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir));
        assertTrue(e.getMessage().contains("feedback"), e.getMessage());
    }

    @Test
    void identityBindsRankingBytesButNotTimestamps() throws Exception {
        // A second seeding of the same corpus: same vectors, but the atom log records new creation times.
        Path again = BookRecallFixture.seededWork(dir, "again", BookRecallFixture.POLICY);
        assertNotEquals(BookStoreInventory.treeDigest(work.resolve("store")),
                BookStoreInventory.treeDigest(again.resolve("store")));
        String first = BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir).identity().fields()
                .get("store.vectorsSha256");
        String second = BookRecallEvaluator.evaluate(again, BookRecallFixture.dataset(again), scripted(script()), dir)
                .identity().fields().get("store.vectorsSha256");
        assertTrue(first.matches("[0-9a-f]{64}"), first);
        assertEquals(first, second, "reseeding the same corpus must reproduce the report identity");

        // Altered vector bytes change the identity.
        Path vectors = work.resolve("store/vectors/segment-000001.f32");
        byte[] bytes = Files.readAllBytes(vectors);
        bytes[0] ^= 1;
        Files.write(vectors, bytes);
        assertNotEquals(first, BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir).identity()
                .fields().get("store.vectorsSha256"));
    }

    @Test
    void repeatedEvidenceIsAdjacentThroughItsOtherOccurrence() throws Exception {
        // LIT-02's gold is s002/c0006 (ordinal 9); its text repeats at s002/c0003 (ordinal 6). s002/c0002 (ordinal 5)
        // neighbours only the earlier occurrence.
        Map<String, List<String>> ranking = new HashMap<>(script());
        ranking.put("The tide turns twice a day.", List.of("s002/c0002", "s002/c0003"));
        Result result = BookRecallEvaluator.evaluate(work, dataset, scripted(ranking), dir);
        assertEquals(BookRecallFailure.CHUNK_BOUNDARY_GAP, byId(result, "LIT-02").failure());
    }

    @Test
    void failureExamplesCoverEachClassInOrderAndAreCapped() throws Exception {
        Result result = BookRecallEvaluator.evaluate(work, dataset, scripted(script()), dir);
        List<CaseResult> examples = BookRecallReport.examples(result.cases());
        assertEquals(List.of("LIT-03", "CON-01", "LIT-01", "MUL-01", "NOA-01"),
                examples.stream().map(r -> r.query().id()).toList(), "one per failure class, in class order");
        assertTrue(examples.size() >= 3, "the issue requires at least three failure examples");

        CaseResult template = byId(result, "LIT-03");
        List<CaseResult> many = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            var q = template.query();
            many.add(new CaseResult(new BookQueryDataset.Case("LIT-0" + i, q.split(), q.category(), q.answerable(),
                    q.text(), q.rationale(), q.judgments()), template.hits(), template.precision(), template.recall(),
                    template.hit(), template.directHit(), template.reciprocalRank(), template.firstRelevantRank(),
                    template.failure()));
        }
        assertEquals(List.of("LIT-01", "LIT-02", "LIT-03", "LIT-04", "LIT-05", "LIT-06"),
                BookRecallReport.examples(many).stream().map(r -> r.query().id()).toList());
    }

    @Test
    void resultsThatDifferBetweenRunsFail() throws Exception {
        String a = BookRecallFixture.atomId(work, "s001/c0000");
        String b = BookRecallFixture.atomId(work, "s001/c0001");
        AtomicInteger opened = new AtomicInteger();
        RetrieverFactory flaky = copy -> {
            boolean second = opened.incrementAndGet() > 1;
            return (query, k) -> second ? List.of(new Hit(b, 0.5), new Hit(a, 0.4))
                    : List.of(new Hit(a, 0.5), new Hit(b, 0.4));
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BookRecallEvaluator.evaluate(work, dataset, flaky, dir));
        assertTrue(e.getMessage().startsWith("CON-01: unstable result ordering, two runs on fresh instances differ"),
                e.getMessage());
    }

    @Test
    void tiesOutOfAtomIdOrderFail() throws Exception {
        List<String> ids = new ArrayList<>(List.of(BookRecallFixture.atomId(work, "s001/c0000"),
                BookRecallFixture.atomId(work, "s001/c0001")));
        ids.sort(null);
        List<Hit> reversed = List.of(new Hit(ids.get(1), 0.5), new Hit(ids.get(0), 0.5));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BookRecallEvaluator.evaluate(work, dataset, copy -> (query, k) -> reversed, dir));
        assertTrue(e.getMessage().startsWith("CON-01: unstable result ordering at rank 2"), e.getMessage());
    }

    @Test
    void hitsOutsideTheChunkSetFail() throws Exception {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> BookRecallEvaluator.evaluate(work,
                dataset, copy -> (query, k) -> List.of(new Hit("not-a-chunk", 0.5)), dir));
        assertEquals("CON-01: hit not-a-chunk is not a chunk of this corpus", e.getMessage());
    }
}
