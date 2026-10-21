package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.evaluation.book.BookHubConcentration.Chunk;
import com.monada.evaluation.book.BookHubConcentration.Occurrence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in check on the real seeded Store (Issue #176): the diagnostic reproduces the observed {@code s009/c0001}
 * concentration (top-1 for 6/32 queries, two of them NO_ANSWER) and the committed
 * {@code book-hub-diagnostics-v1-report.txt} byte for byte. Skipped unless {@value #WORK_ENV} names the seeded work
 * directory; tests never download anything.
 */
class BookHubDiagnosticsRealCorpusTest {
    private static final String WORK_ENV = "MONADA_BOOK_STORE_READBACK_V1_WORK";
    static final String REPORT = "docs/experiments/book-corpus-v1/book-hub-diagnostics-v1-report.txt";
    private static final String HUB = "book-corpus-v1/book-chunking-v1/s009/c0001";

    @TempDir
    Path dir;

    private static Path repo(String relative) {
        // Gradle runs tests with the module directory as working directory.
        return Path.of(System.getProperty("user.dir")).resolve("../" + relative).normalize();
    }

    @Test
    void reproducesTheObservedHubAndTheCommittedReport() throws Exception {
        String work = System.getenv(WORK_ENV);
        assumeTrue(work != null && !work.isBlank(), WORK_ENV + " is not set; the real corpus check is opt-in");
        Path seeded = ExperimentDirectoryGuard.requireSeeded(Path.of(work));
        byte[] baseline = Files.readAllBytes(repo(BookRecallRealCorpusTest.REPORT));

        BookHubProbe.Result result = BookHubProbe.diagnose(seeded, BookQueryDataset.load(), baseline);
        Chunk hub = result.distribution().chunks().getFirst();
        assertEquals(HUB, hub.key());
        assertEquals(6, hub.count("ALL", 1));
        assertEquals(32, result.distribution().queries().get("ALL"));
        assertEquals(2, hub.top1NoAnswer());
        assertEquals(List.of("NOA-01", "NOA-02"), hub.occurrences().stream()
                .filter(o -> o.rank() == 1 && !o.answerable()).map(Occurrence::queryId).toList());
        assertEquals(HUB, result.controls().getFirst().key(), "the DEV-only HUB rule picks the same chunk");

        Path out = dir.resolve("hub.txt");
        assertEquals(0, BookHubDiagnosticsMain.run(new String[] {"--work", work, "--baseline",
                repo(BookRecallRealCorpusTest.REPORT).toString(), "--out", out.toString()}, System.out, System.err));
        assertEquals(Files.readString(repo(REPORT), StandardCharsets.UTF_8),
                Files.readString(out, StandardCharsets.UTF_8),
                "regenerate " + REPORT + " with runBookHubDiagnostics if an input changed on purpose");
    }
}
