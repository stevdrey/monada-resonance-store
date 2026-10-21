package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookHubProbe.ChunkMetrics;
import com.monada.evaluation.book.BookHubProbe.Control;
import com.monada.evaluation.book.BookHubProbe.PairScore;
import com.monada.evaluation.book.BookHubProbe.Result;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.evaluation.book.BookRecallEvaluator.RetrieverFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hub probe over the seeded fixture with the real API: it must reproduce the production ranking bit for bit,
 * decompose every score exactly, leave the Store untouched, render deterministically without book text or paths,
 * and refuse a changed baseline or a ranking it cannot reproduce.
 */
class BookHubProbeTest {
    @TempDir
    Path dir;

    private Path work;
    private BookQueryDataset dataset;

    @BeforeEach
    void seed() throws Exception {
        work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        dataset = BookRecallFixture.dataset(work);
    }

    private byte[] baseline(RetrieverFactory retriever) throws Exception {
        return BookRecallReport.render(BookRecallEvaluator.evaluate(work, dataset, retriever, dir))
                .getBytes(StandardCharsets.UTF_8);
    }

    private Result diagnose() throws Exception {
        byte[] baseline = baseline(BookRecallEvaluator.DEFAULT_RETRIEVER);
        return BookHubProbe.diagnose(work, dataset, baseline, BookRecallEvaluator.DEFAULT_RETRIEVER, dir);
    }

    @Test
    void recomputationMatchesProductionExactly() throws Exception {
        Result result = diagnose();

        assertEquals(6, result.parity().queries());
        assertTrue(result.parity().comparedHits() > 0, "some production hits were compared");
        assertEquals(0.0, result.parity().maxAbsDifference(), "bit-identical scores");
        assertEquals(9, result.parity().rankedAtoms(), "10 chunks, one repeated text: 9 atoms");
        assertEquals(9, result.parity().storedVectors());
        for (ChunkMetrics m : result.metrics()) {
            assertTrue(m.reencodeMatches(), "stored vector is the production encoding of " + m.key());
            assertEquals(1.0, m.norm(), 1e-6, m.key());
            assertTrue(m.utf8Bytes() >= m.codePoints() && m.codePoints() > 0, m.key());
            assertTrue(m.originalTokens() >= m.distinctOriginalTokens(), m.key());
            assertTrue(m.nonZeroBuckets() > 0 && m.maxAbsComponent() <= 1.0, m.key());
        }
        ChunkMetrics repeated = result.metrics().stream()
                .filter(m -> m.key().equals(BookRecallFixture.KEY + "s002/c0003")).findFirst().orElseThrow();
        assertEquals(1, repeated.aliases(), "s002/c0006 repeats this text");
    }

    @Test
    void decompositionSumsToTheProductionScore() throws Exception {
        Result result = diagnose();

        assertEquals(4, result.controls().size());
        for (Control control : result.controls()) {
            for (PairScore p : control.dev()) {
                assertTrue(p.residual() < 1e-12, control.role() + " " + p);
                assertEquals(p.score(), p.positive() + p.negative(), 1e-12, p.toString());
                assertTrue(p.rank() >= 1 && p.rank() <= 9, p.toString());
                assertTrue(p.top().size() <= BookHubProbe.TOP_BUCKETS);
                assertTrue(p.coveredTokens() <= p.queryTokens());
            }
        }
        // Every DEV top-K production hit equals the recomputation at the same rank.
        for (Control control : result.controls()) {
            for (PairScore p : control.dev()) {
                var hits = result.recall().cases().stream().filter(c -> c.query().id().equals(p.queryId()))
                        .findFirst().orElseThrow().hits();
                if (p.rank() <= hits.size()) {
                    assertEquals(hits.get(p.rank() - 1).score(), p.score());
                }
            }
        }
    }

    @Test
    void reportIsDeterministicReadOnlyAndHasNoBookTextOrPaths() throws Exception {
        String digest = BookStoreInventory.treeDigest(work);
        String first = BookHubReport.render(diagnose());
        String second = BookHubReport.render(diagnose());

        assertEquals(first, second);
        assertEquals(digest, BookStoreInventory.treeDigest(work), "the work directory is unchanged");
        assertTrue(first.contains("format=" + BookHubReport.FORMAT));
        assertTrue(first.contains("result=PASS"));
        assertFalse(first.contains(dir.toString()), "no paths");
        for (BookChunkFiles.Entry e : BookChunkFiles.load(work.resolve(ExperimentDirectoryGuard.CHUNKS_DIR))
                .entries()) {
            for (String line : e.text().split("\n")) {
                if (line.strip().length() >= 24) {
                    assertFalse(first.contains(line.strip()), "no chunk text: " + line);
                }
            }
        }
    }

    @Test
    void changedBaselineFailsClearly() throws Exception {
        byte[] baseline = baseline(BookRecallEvaluator.DEFAULT_RETRIEVER);
        byte[] altered = Arrays.copyOf(baseline, baseline.length + 1);
        altered[baseline.length] = '\n';

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> BookHubProbe.diagnose(work,
                dataset, altered, BookRecallEvaluator.DEFAULT_RETRIEVER, dir));
        assertTrue(e.getMessage().startsWith("pinned input changed"), e.getMessage());
    }

    @Test
    void rankingThatProductionDidNotProduceFailsParity() throws Exception {
        RetrieverFactory shifted = copy -> {
            var real = BookRecallEvaluator.DEFAULT_RETRIEVER.open(copy);
            return (query, k) -> real.search(query, k).stream().map(h -> new Hit(h.atomId(), h.score() + 1e-9))
                    .toList();
        };
        byte[] baseline = baseline(shifted);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BookHubProbe.diagnose(work, dataset, baseline, shifted, dir));
        assertTrue(e.getMessage().contains("score parity failed"), e.getMessage());
    }
}
