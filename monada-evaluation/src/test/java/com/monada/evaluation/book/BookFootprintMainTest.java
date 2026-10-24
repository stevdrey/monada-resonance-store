package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The footprint command-line contract (Issue #169): usage errors, scratch and output safety before anything is
 * created, frozen-artifact protection, and an end-to-end run that cleans up and leaves its inputs untouched.
 */
class BookFootprintMainTest {
    /** The repository root (Gradle runs tests in the module directory). */
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private static Run cli(String... args) {
        return cliAt(Path.of(""), args);
    }

    private static Run cliAt(Path root, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookFootprintMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), root,
                new BookMemorySampler(BookMemorySampler.unsupported("test"), Optional::empty));
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
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--ladder", "x1").exit());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--keep", "maybe").exit());
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
        Path manifest = copyFixture(inputs, "manifest.properties");
        run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", scratch.toString(),
                "--out", body.toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("would overwrite the benchmark input"), run.err());
        assertFalse(Files.exists(scratch));
    }

    @Test
    void outputsMayNotReplaceFrozenArtifactsExceptTheirOwn() throws Exception {
        Path docs = ROOT.resolve("docs/experiments/book-corpus-v1");
        Path recall = docs.resolve("book-recall-v1-report.txt");
        byte[] before = Files.readAllBytes(recall);
        Path scratch = dir.resolve("scratch");
        for (String[] output : List.of(new String[] {"--out", recall.toString()},
                new String[] {"--summary", docs.resolve("LATENCY.md").toString()},
                new String[] {"--out", ROOT.resolve(BookLatencyMain.LATENCY_REPORT).toString()},
                new String[] {"--out", ROOT.resolve(BookFootprintMain.FOOTPRINT_SUMMARY).toString()},
                new String[] {"--summary", ROOT.resolve(BookFootprintMain.FOOTPRINT_REPORT).toString()})) {
            Run run = cliAt(ROOT, "--input", "missing-body.txt", "--scratch", scratch.toString(), output[0],
                    output[1]);
            assertEquals(2, run.exit(), output[1] + ": " + run.err());
            assertTrue(run.err().contains("frozen evaluation input"), run.err());
        }
        assertTrue(Arrays.equals(before, Files.readAllBytes(recall)));
        assertFalse(Files.exists(scratch));
        assertNull(BookFootprintMain.refuseProtectedOutputs(ROOT, ROOT.resolve(BookFootprintMain.FOOTPRINT_REPORT),
                ROOT.resolve(BookFootprintMain.FOOTPRINT_SUMMARY)));
    }

    @Test
    void anEndToEndRunWritesBothOutputsCleansUpAndLeavesInputsUntouched() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path body = copyFixture(inputs, "body.txt");
        Path manifest = copyFixture(inputs, "manifest.properties");
        String inputsBefore = BookStoreInventory.treeDigest(inputs);
        Path scratch = dir.resolve("scratch");
        Path report = dir.resolve("report.txt");
        Path summary = dir.resolve("summary.md");

        Run run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", scratch.toString(),
                "--ladder", "100%,x2", "--out", report.toString(), "--summary", summary.toString());

        assertEquals(0, run.exit(), run.err());
        String text = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(text.contains("checks.status=PASS"), text);
        assertTrue(text.contains("check.inputs.guarded[0].unchanged=PASS"), text);
        assertTrue(text.contains("scale.p100.REPEAT_IMPORT.unchangedFromPreviousPhase=yes"), text);
        String summaryText = Files.readString(summary, StandardCharsets.UTF_8);
        assertTrue(summaryText.contains("Checks: **PASS**"), summaryText);
        assertTrue(summaryText.contains("| Input text bytes | Unique text bytes |"), summaryText);
        assertFalse(Files.exists(scratch), "scratch is removed after the run");
        assertEquals(inputsBefore, BookStoreInventory.treeDigest(inputs), "inputs are only read");

        Path kept = dir.resolve("kept");
        run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", kept.toString(),
                "--ladder", "100%", "--keep", "yes");
        assertEquals(0, run.exit(), run.err());
        assertTrue(Files.isRegularFile(kept.resolve(BookFootprintMain.SCRATCH_MARKER)));
        assertTrue(run.err().contains("summary not written"), "standard output carries the report alone");
    }
}
