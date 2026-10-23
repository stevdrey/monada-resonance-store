package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in end-to-end run of {@link BookFootprintMain} on the real {@code book-corpus-v1} body (Issue #169). Skipped
 * unless {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never download
 * it. Memory is never asserted.
 */
class BookFootprintRealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @TempDir
    Path dir;

    @Test
    void aBoundedRunOnTheRealBodyPassesEveryCheckAndCleansUp() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        Path scratch = dir.resolve("scratch");
        Path report = dir.resolve("report.txt");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = BookFootprintMain.run(new String[] {"--input", body, "--manifest", manifest.toString(),
                "--scratch", scratch.toString(), "--out", report.toString(), "--ladder", "25%,100%"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        String text = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(text.contains("checks.status=PASS"), text);
        assertTrue(text.contains("input.chunks.count=87"), text);
        assertTrue(text.contains("scale.p100.FRESH_IMPORT.atoms=unique=87 physical=87 historyDuplicates=0"), text);
        assertFalse(Files.exists(scratch), "scratch is removed after a successful run");
    }
}
