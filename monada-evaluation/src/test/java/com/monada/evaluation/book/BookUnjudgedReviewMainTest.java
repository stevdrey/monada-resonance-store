package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command-line contract of {@code runBookUnjudgedReview}; the body-dependent commands run in the real-corpus test. */
class BookUnjudgedReviewMainTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    private static final String BASELINE = ROOT.resolve(BookUnjudgedSelection.BASELINE_REPORT).toString();

    @TempDir
    Path tmp;

    private record Result(int code, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = BookUnjudgedReviewMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void selectReproducesTheCommittedReceipt() {
        Result r = run("select", "--baseline", BASELINE);
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().startsWith("selection.psv reproduced: 60 pairs"), r.out());
    }

    @Test
    void selectWritesTheReceiptOnRequest() throws Exception {
        Path out = tmp.resolve("selection.psv");
        assertEquals(0, run("select", "--baseline", BASELINE, "--out", out.toString()).code());
        assertEquals(new String(BookQueryDatasetV2.resource("selection.psv"), StandardCharsets.UTF_8),
                Files.readString(out));
    }

    @Test
    void reportMatchesTheCommittedReport() throws Exception {
        Path out = tmp.resolve("report.txt");
        assertEquals(0, run("report", "--baseline", BASELINE, "--out", out.toString()).code());
        assertEquals(Files.readString(ROOT.resolve("docs/experiments/book-corpus-v1/book-unjudged-review-v2-report.txt")),
                Files.readString(out));
    }

    @Test
    void anEditedBaselineIsRejected() throws Exception {
        Path edited = tmp.resolve("report.txt");
        Files.writeString(edited, Files.readString(Path.of(BASELINE)) + "\n");
        Result r = run("select", "--baseline", edited.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().startsWith("baseline report SHA-256 is "), r.err());
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run().code());
        assertEquals(2, run("judge").code());
        assertEquals(2, run("worksheet", "--input", "body.txt").code(), "--out is required");
        assertEquals(2, run("select", "--bogus", "x").code());
        assertEquals(2, run("select", "--baseline", tmp.resolve("missing.txt").toString()).code());
    }

    @Test
    void theWorksheetIsNeverWrittenInsideAGitWorkTreeNorOverAnExistingFile() throws Exception {
        Result inside = run("worksheet", "--input", "body.txt", "--out", ROOT.resolve("build/sheet.md").toString());
        assertEquals(2, inside.code());
        assertTrue(inside.err().contains("outside any Git work tree"), inside.err());
        assertTrue(BookUnjudgedReviewMain.insideGitWorkTree(ROOT.resolve("no/such/dir/sheet.md")));
        assertFalse(BookUnjudgedReviewMain.insideGitWorkTree(tmp.resolve("sheet.md")));
        // Only a real marker counts: an empty .git directory is not a repository.
        Path empty = Files.createDirectories(tmp.resolve("empty/.git")).getParent();
        assertFalse(BookUnjudgedReviewMain.insideGitWorkTree(empty.resolve("sheet.md")));
        Path repo = Files.createDirectories(tmp.resolve("repo/.git")).getParent();
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        assertTrue(BookUnjudgedReviewMain.insideGitWorkTree(repo.resolve("sub/sheet.md")));
        Path worktree = Files.createDirectories(tmp.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/w\n");
        assertTrue(BookUnjudgedReviewMain.insideGitWorkTree(worktree.resolve("sheet.md")));

        Path existing = Files.writeString(tmp.resolve("sheet.md"), "review work");
        Result overwrite = run("worksheet", "--input", "body.txt", "--out", existing.toString());
        assertEquals(2, overwrite.code());
        assertEquals("review work", Files.readString(existing));
    }

    @Test
    void applyNeverCarriesASignOffOverToChangedDecisions() {
        Properties signed = new Properties();
        signed.setProperty("judgment.reviewStatus", "REVIEWED");
        signed.setProperty("review.sha256", "a".repeat(64));
        BookUnjudgedReviewMain.requireUnsignedChange(signed, "a".repeat(64));
        var e = assertThrows(IllegalStateException.class,
                () -> BookUnjudgedReviewMain.requireUnsignedChange(signed, "b".repeat(64)));
        assertTrue(e.getMessage().contains("set judgment.reviewStatus to PENDING_HUMAN_REVIEW"), e.getMessage());
        // A status-only reset keeps a stale approval that a later status edit would re-attach to the new digest.
        signed.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        for (String key : List.of("judgment.reviewer", "judgment.approvedBy", "judgment.reviewedAt")) {
            signed.setProperty(key, "stale");
            assertThrows(IllegalStateException.class,
                    () -> BookUnjudgedReviewMain.requireUnsignedChange(signed, "b".repeat(64)), key);
            signed.setProperty(key, "");
        }
        BookUnjudgedReviewMain.requireUnsignedChange(signed, "b".repeat(64));
    }

    @Test
    void aFailedManifestWriteRestoresThePreviousReview() throws Exception {
        Path review = Files.writeString(tmp.resolve("review.psv"), "old review\n");
        // A non-empty directory cannot be replaced by the atomic move, so the manifest write fails.
        Path manifest = Files.createDirectories(tmp.resolve("manifest.properties"));
        Files.writeString(manifest.resolve("blocker"), "x");
        assertThrows(java.io.IOException.class,
                () -> BookUnjudgedReviewMain.writeTogether(review, "new review\n", manifest, "m=1\n"));
        assertEquals("old review\n", Files.readString(review));

        Path absent = tmp.resolve("absent-review.psv");
        assertThrows(java.io.IOException.class,
                () -> BookUnjudgedReviewMain.writeTogether(absent, "new review\n", manifest, "m=1\n"));
        assertFalse(Files.exists(absent), "a review that did not exist before is removed again");
    }

    @Test
    void manifestUpdatesReplaceExactlyOneExistingKey() {
        String manifest = "# c\ncounts.pending=60\ncounts.pending.x=1\n";
        assertEquals("# c\ncounts.pending=59\ncounts.pending.x=1\n",
                BookUnjudgedReviewMain.replace(manifest, "counts.pending", "59"));
        assertThrows(IllegalStateException.class, () -> BookUnjudgedReviewMain.replace(manifest, "counts.none", "1"));
    }
}
