package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command-line contract of {@link BookHubDiagnosticsMain}: usage errors, unsafe paths, missing and foreign inputs. */
class BookHubDiagnosticsMainTest {
    @TempDir
    Path dir;

    private record Run(int exit, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookHubDiagnosticsMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run().exit());
        assertEquals(2, run("--work", "a").exit(), "--baseline is required");
        assertEquals(2, run("--baseline", "b").exit(), "--work is required");
        assertEquals(2, run("--bogus", "x").exit());
        assertEquals(2, run("--work", "a", "--work", "b", "--baseline", "c").exit());
    }

    @Test
    void unseededWorkDirectoryExitsTwo() throws Exception {
        Path plain = Files.createDirectories(dir.resolve("plain"));
        assertEquals(2, run("--work", plain.toString(), "--baseline", "x").exit());
    }

    @Test
    void reportInsideTheWorkDirectoryIsRefused() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        String digest = BookStoreInventory.treeDigest(work);
        Run result = run("--work", work.toString(), "--baseline", "x", "--out", work.resolve("r.txt").toString());
        assertEquals(2, result.exit());
        assertTrue(result.err().startsWith("--out must not be inside the work directory"), result.err());
        assertEquals(digest, BookStoreInventory.treeDigest(work));
    }

    @Test
    void outThatIsTheBaselineIsRefusedAndTheBaselineIsUntouched() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        String digest = BookStoreInventory.treeDigest(work);
        Path baseline = Files.writeString(dir.resolve("baseline.txt"), "pinned baseline bytes\n");
        byte[] bytes = Files.readAllBytes(baseline);
        FileTime modified = Files.getLastModifiedTime(baseline);
        Files.createDirectories(dir.resolve("sub"));
        List<Path> aliases = List.of(
                baseline,
                dir.resolve("sub/../baseline.txt"),
                Files.createSymbolicLink(dir.resolve("baseline-link.txt"), baseline),
                Files.createLink(dir.resolve("baseline-hard.txt"), baseline));
        for (Path out : aliases) {
            Run result = run("--work", work.toString(), "--baseline", baseline.toString(), "--out", out.toString());
            assertEquals(2, result.exit(), out.toString());
            assertTrue(result.err().startsWith("--out must not be the --baseline file"), result.err());
            assertArrayEquals(bytes, Files.readAllBytes(baseline), "baseline bytes unchanged: " + out);
            assertEquals(modified, Files.getLastModifiedTime(baseline), "baseline not replaced: " + out);
        }
        assertEquals(digest, BookStoreInventory.treeDigest(work), "the work directory is unchanged");
    }

    @Test
    void missingBaselineExitsTwo() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        Run result = run("--work", work.toString(), "--baseline", dir.resolve("absent.txt").toString());
        assertEquals(2, result.exit());
        assertTrue(result.err().startsWith("file not found"), result.err());
    }

    @Test
    void foreignCorpusExitsOneAndWritesNothing() throws Exception {
        Path work = BookRecallFixture.seededWork(dir, "work", BookRecallFixture.POLICY);
        Path baseline = Files.writeString(dir.resolve("baseline.txt"), "not the baseline\n");
        Path out = dir.resolve("hub.txt");
        Run result = run("--work", work.toString(), "--baseline", baseline.toString(), "--out", out.toString());
        assertEquals(1, result.exit());
        assertTrue(result.err().startsWith("foreign corpus input"), result.err());
        assertFalse(Files.exists(out));
    }
}
