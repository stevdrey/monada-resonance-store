package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in check that the committed {@code book-recall-v1-report.txt} is exactly what the evaluation produces on the
 * real seeded Store. Skipped unless {@value #WORK_ENV} names a work directory seeded by
 * {@code runBookStoreReadback seed} from the verified {@code book-corpus-v1} body; tests never download anything.
 */
class BookRecallRealCorpusTest {
    private static final String WORK_ENV = "MONADA_BOOK_STORE_READBACK_V1_WORK";
    static final String REPORT = "docs/experiments/book-corpus-v1/book-recall-v1-report.txt";

    @TempDir
    Path dir;

    @Test
    void committedReportMatchesAFreshRun() throws Exception {
        String work = System.getenv(WORK_ENV);
        assumeTrue(work != null && !work.isBlank(), WORK_ENV + " is not set; the real corpus check is opt-in");
        Path out = dir.resolve("report.txt");
        assertEquals(0, BookRecallMain.run(new String[] {"--work", work, "--out", out.toString()}, System.out,
                System.err));
        // Gradle runs tests with the module directory as working directory.
        Path committed = Path.of(System.getProperty("user.dir")).resolve("../" + REPORT).normalize();
        assertEquals(Files.readString(committed, StandardCharsets.UTF_8), Files.readString(out, StandardCharsets.UTF_8),
                "regenerate " + REPORT + " with runBookRecall if an input changed on purpose");
    }
}
