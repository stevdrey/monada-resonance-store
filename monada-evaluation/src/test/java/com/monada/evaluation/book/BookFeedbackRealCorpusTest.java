package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in end-to-end run of {@link BookFeedbackMain} on the real {@code book-corpus-v1} body (Issue #170). Skipped
 * unless {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download
 * it. The regenerated report must equal the committed one byte for byte.
 */
class BookFeedbackRealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();

    @TempDir
    Path dir;

    @Test
    void theRealBodyReproducesTheCommittedReport() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        Path scratch = dir.resolve("scratch");
        Path report = dir.resolve("report.txt");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = BookFeedbackMain.run(new String[] {"--input", body, "--manifest",
                ROOT.resolve(BookChunkingMain.DEFAULT_MANIFEST).toString(), "--scratch", scratch.toString(),
                "--out", report.toString()}, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), ROOT);

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(ROOT.resolve(BookFeedbackMain.FEEDBACK_REPORT)),
                Files.readAllBytes(report), "regenerate with runBookFeedback and review the diff");
        assertFalse(Files.exists(scratch), "scratch is removed after a successful run");
    }
}
