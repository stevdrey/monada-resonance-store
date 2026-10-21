package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command-line contract of {@link BookRecallMain}: usage errors, unsafe directories and foreign inputs. */
class BookRecallMainTest {
    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookRecallMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run().exit());
        assertEquals(2, run("--work").exit());
        assertEquals(2, run("--bogus", "x").exit());
        assertEquals(2, run("--work", "a", "--work", "b").exit());
    }

    @Test
    void missingOrUnseededWorkDirectoryExitsTwo() throws Exception {
        Run missing = run("--work", dir.resolve("absent").toString());
        assertEquals(2, missing.exit());
        assertTrue(missing.err().contains("work directory does not exist"), missing.err());
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertEquals(2, run("--work", plain.toString()).exit());
    }

    @Test
    void workDirectoryFromAnotherCorpusExitsOneAndWritesNothing() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        Path out = dir.resolve("report.txt");
        Run result = run("--work", work.toString(), "--out", out.toString());
        assertEquals(1, result.exit());
        assertEquals("foreign corpus input: the work directory's corpus is book-chunking-fixture, the judged dataset"
                + " expects book-corpus-v1\n", result.err());
        assertTrue(Files.notExists(out));
    }
}
