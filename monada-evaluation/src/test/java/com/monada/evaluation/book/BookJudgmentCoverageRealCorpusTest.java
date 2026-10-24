package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.evaluation.book.BookJudgmentCoverage.Row;
import com.monada.evaluation.book.BookRecallEvaluator.RankedHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check on the real seeded Store (Issue #184): the coverage report's only ranking input, the frozen trace, is
 * still what a fresh evaluation ranks (atom ids, ranks, score tokens), and the raw score bits agree between two fresh
 * evaluations. The report itself never searches; this test is the only place that does. Skipped unless
 * {@value #WORK_ENV} names the seeded work directory; tests never download anything.
 */
class BookJudgmentCoverageRealCorpusTest {
    private static final String WORK_ENV = "MONADA_BOOK_STORE_READBACK_V1_WORK";

    @Test
    void theFrozenTraceIsStillTheRanking() throws Exception {
        String work = System.getenv(WORK_ENV);
        assumeTrue(work != null && !work.isBlank(), WORK_ENV + " is not set; the real corpus check is opt-in");
        Path seeded = ExperimentDirectoryGuard.requireSeeded(Path.of(work));
        // Gradle runs tests with the module directory as working directory.
        byte[] baseline = Files.readAllBytes(Path.of(System.getProperty("user.dir"))
                .resolve("../" + BookUnjudgedSelection.BASELINE_REPORT).normalize());
        List<Row> rows = BookJudgmentCoverage.load(baseline).rows();

        List<RankedHit> first = fresh(seeded);
        List<RankedHit> second = fresh(seeded);
        assertEquals(rows.size(), first.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            RankedHit hit = first.get(i);
            assertEquals(List.of(row.rank(), row.atomId(), row.score()), List.of(hit.rank(), hit.atomId(),
                    String.format(Locale.ROOT, "%.6f", hit.score())), row.queryId() + " #" + row.rank());
            assertEquals(Double.doubleToLongBits(hit.score()), Double.doubleToLongBits(second.get(i).score()),
                    row.queryId() + " #" + row.rank() + ": score bits differ between fresh evaluations");
        }
    }

    private static List<RankedHit> fresh(Path seeded) throws Exception {
        // The trace lists queries in id order.
        return BookRecallEvaluator.evaluate(seeded, BookQueryDataset.load()).cases().stream()
                .sorted(Comparator.comparing(c -> c.query().id()))
                .flatMap(c -> c.hits().stream()).toList();
    }
}
