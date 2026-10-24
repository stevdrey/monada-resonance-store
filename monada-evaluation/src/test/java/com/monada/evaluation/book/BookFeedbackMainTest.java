package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The feedback command-line contract (Issue #170): usage errors, scratch and output safety before anything is
 * created, frozen-artifact protection, and a foreign corpus that fails without leaving scratch behind. The end-to-end
 * run on the real body is {@link BookFeedbackRealCorpusTest}.
 */
class BookFeedbackMainTest {
    /** The repository root (Gradle runs tests in the module directory). */
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private static Run cli(String... args) {
        return cliAt(ROOT, args);
    }

    private static Run cliAt(Path root, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookFeedbackMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), root);
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path copyFixture(Path target, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(BookRecallFixture.FIXTURE + name)) {
            Path file = target.resolve(name);
            Files.write(file, in.readAllBytes());
            return file;
        }
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, cli().exit());
        assertEquals(2, cli("--input", "body.txt").exit());
        assertEquals(2, cli("--bogus", "x").exit());
        Run run = cli("--input", "a", "--scratch", "b", "--keep", "maybe");
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("--keep must be yes or no"), run.err());
    }

    @Test
    void unsafeScratchAndOutputsAreRefusedBeforeAnything() throws Exception {
        Path existing = Files.createDirectory(dir.resolve("existing"));
        Run run = cli("--input", "missing.txt", "--scratch", existing.toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("must not exist"), run.err());

        Path repo = Files.createDirectories(dir.resolve("repo").resolve(".git")).getParent();
        run = cli("--input", "missing.txt", "--scratch", repo.resolve("scratch").toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("outside a Git work tree"), run.err());
        assertFalse(Files.exists(repo.resolve("scratch")));

        Path scratch = dir.resolve("scratch");
        run = cli("--input", "missing.txt", "--scratch", scratch.toString(), "--out",
                scratch.resolve("report.txt").toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("inside --scratch"), run.err());

        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path body = copyFixture(inputs, "body.txt");
        Path baseline = Files.writeString(inputs.resolve("baseline.txt"), "baseline\n");
        for (Path input : List.of(body, baseline)) {
            run = cli("--input", body.toString(), "--baseline", baseline.toString(), "--scratch", scratch.toString(),
                    "--out", input.toString());
            assertEquals(2, run.exit());
            assertTrue(run.err().contains("would overwrite the benchmark input"), run.err());
        }
        assertFalse(Files.exists(scratch));
    }

    @Test
    void aMissingBaselineIsAUsageErrorBeforeAnything() throws Exception {
        Path scratch = dir.resolve("scratch");
        Path missing = dir.resolve("no-baseline.txt");
        Run run = cli("--input", "missing-body.txt", "--baseline", missing.toString(), "--scratch",
                scratch.toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().startsWith("--baseline not found: " + missing), run.err());
        assertFalse(run.err().contains("Offline acquisition"), "the corpus hint is for the body only");
        assertFalse(Files.exists(scratch));
    }

    @Test
    void outputsMayNotReplaceFrozenArtifactsExceptTheirOwn() throws Exception {
        Path recall = ROOT.resolve(BookFeedbackMain.BASELINE_REPORT);
        byte[] before = Files.readAllBytes(recall);
        Path scratch = dir.resolve("scratch");
        for (String target : List.of(recall.toString(),
                ROOT.resolve(BookFootprintMain.FOOTPRINT_REPORT).toString(),
                ROOT.resolve("docs/experiments/book-corpus-v1/FEEDBACK.md").toString())) {
            Run run = cli("--input", "missing-body.txt", "--scratch", scratch.toString(), "--out", target);
            assertEquals(2, run.exit(), target + ": " + run.err());
            assertTrue(run.err().contains("frozen evaluation input") || run.err().contains("benchmark input"),
                    run.err());
        }
        assertArrayEquals(before, Files.readAllBytes(recall));
        assertFalse(Files.exists(scratch));
        Run own = cli("--input", "missing-body.txt", "--scratch", scratch.toString(), "--out",
                ROOT.resolve(BookFeedbackMain.FEEDBACK_REPORT).toString());
        assertEquals(2, own.exit());
        assertTrue(own.err().contains("file not found"), "its own report is allowed: " + own.err());
        assertFalse(Files.exists(scratch));
    }

    @Test
    void aForeignCorpusFailsAndLeavesNoScratch() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path body = copyFixture(inputs, "body.txt");
        Path manifest = copyFixture(inputs, "manifest.properties");
        String inputsBefore = BookStoreInventory.treeDigest(inputs);
        Path scratch = dir.resolve("scratch");
        Path report = dir.resolve("report.txt");

        Run run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", scratch.toString(),
                "--out", report.toString());

        assertEquals(1, run.exit(), run.err());
        assertTrue(run.err().contains("foreign corpus input"), run.err());
        assertFalse(Files.exists(scratch), "scratch is removed after a failed run");
        assertFalse(Files.exists(report), "no report for inputs that are not the judged corpus");
        assertEquals(inputsBefore, BookStoreInventory.treeDigest(inputs), "inputs are only read");
    }
}
