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
 * Opt-in end-to-end run of {@link BookLatencyMain} on the real {@code book-corpus-v1} body (Issue #168): binding to
 * {@code book-queries-v1}, the ladder check, scratch creation and removal, one child JVM, and both output files.
 * Skipped unless {@value #BODY_ENV} names the output of {@code VerifyBookCorpus.java --write-normalized}; tests never
 * download it. Timings are never asserted.
 */
class BookLatencyRealCorpusTest {
    private static final String BODY_ENV = "MONADA_BOOK_CORPUS_V1_BODY";

    @TempDir
    Path dir;

    @Test
    void aBoundedRunOnTheRealBodySucceedsAndCleansUp() throws Exception {
        String body = System.getenv(BODY_ENV);
        assumeTrue(body != null && !body.isBlank(), BODY_ENV + " is not set; the real corpus check is opt-in");
        // Gradle runs tests with the module directory as working directory.
        Path manifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        Path scratch = dir.resolve("scratch");
        Path report = dir.resolve("report.txt");
        Path summary = dir.resolve("summary.md");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = BookLatencyMain.run(new String[] {"--input", body, "--manifest", manifest.toString(),
                "--scratch", scratch.toString(), "--out", report.toString(), "--summary", summary.toString(),
                "--ladder", "25%", "--ingestWarmups", "0", "--ingestTrials", "1", "--openWarmups", "0",
                "--openTrials", "1", "--queryWarmupPasses", "0", "--queryPasses", "1", "--processes", "1"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        String text = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(text.contains("parity.status=PASS"), text);
        assertTrue(text.contains("scale.p25.chunks=21"), text);
        assertTrue(text.contains("chunks.count=87"), text);
        assertTrue(text.contains("scale.p25.freshProcess=RUN"), text);
        assertTrue(Files.readString(summary, StandardCharsets.UTF_8).contains("| 25% | REAL | 21 |"));
        assertFalse(Files.exists(scratch), "scratch is removed after a successful run");
    }
}
