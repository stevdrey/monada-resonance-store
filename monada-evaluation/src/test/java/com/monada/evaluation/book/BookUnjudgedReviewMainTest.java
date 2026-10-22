package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        // The negative cases need a temp dir outside any repository; some machines put it inside one.
        assumeFalse(BookUnjudgedReviewMain.insideGitWorkTree(tmp), "the temp dir is inside a Git work tree");
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
        BookUnjudgedReviewMain.requireUnsignedChange(signed, false);
        var e = assertThrows(IllegalStateException.class,
                () -> BookUnjudgedReviewMain.requireUnsignedChange(signed, true));
        assertTrue(e.getMessage().contains("set judgment.reviewStatus to PENDING_HUMAN_REVIEW"), e.getMessage());
        // A status-only reset keeps a stale approval that a later status edit would re-attach to the new digest.
        signed.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        for (String key : List.of("judgment.reviewer", "judgment.approvedBy", "judgment.reviewedAt",
                "judgment.draftedAt", "judgment.verification", "judgment.draftBlinding")) {
            signed.setProperty(key, "stale");
            assertThrows(IllegalStateException.class,
                    () -> BookUnjudgedReviewMain.requireUnsignedChange(signed, true), key);
            signed.setProperty(key, "");
        }
        BookUnjudgedReviewMain.requireUnsignedChange(signed, true);
    }

    @Test
    void aFailedManifestWriteRestoresThePreviousReview() throws Exception {
        Path review = Files.writeString(tmp.resolve("review.psv"), "old review\n");
        // A non-empty directory cannot be replaced by the atomic move, so the manifest write fails.
        Path manifest = Files.createDirectories(tmp.resolve("manifest.properties"));
        Files.writeString(manifest.resolve("blocker"), "x");
        Path backup = tmp.resolve("sheet.md.review-backup.psv");
        assertThrows(java.io.IOException.class,
                () -> BookUnjudgedReviewMain.writeTogether(review, "new review\n", manifest, "m=1\n", backup));
        assertEquals("old review\n", Files.readString(review));
        assertFalse(Files.exists(backup), "the backup is removed once the review is restored");

        Path absent = tmp.resolve("absent-review.psv");
        assertThrows(java.io.IOException.class,
                () -> BookUnjudgedReviewMain.writeTogether(absent, "new review\n", manifest, "m=1\n", backup));
        assertFalse(Files.exists(absent), "a review that did not exist before is removed again");
    }

    @Test
    void aSuccessfulWriteReplacesBothFilesAndDropsTheBackup() throws Exception {
        Path review = Files.writeString(tmp.resolve("review.psv"), "old review\n");
        Path manifest = Files.writeString(tmp.resolve("manifest.properties"), "m=0\n");
        Path backup = tmp.resolve("sheet.md.review-backup.psv");
        BookUnjudgedReviewMain.writeTogether(review, "new review\n", manifest, "m=1\n", backup);
        assertEquals("new review\n", Files.readString(review));
        assertEquals("m=1\n", Files.readString(manifest));
        assertFalse(Files.exists(backup));
    }

    @Test
    void anIncrementalReviewKeepsTheWeakerProtocol() {
        String legacy = BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL;
        String current = BookQueryDatasetV2.REVIEW_PROTOCOL;
        // Decisions made on the legacy sheet stay legacy even when the next sheet is the current one.
        assertEquals(legacy, BookUnjudgedReviewMain.weakerProtocol(legacy, current));
        assertEquals(legacy, BookUnjudgedReviewMain.weakerProtocol(current, legacy));
        assertEquals(current, BookUnjudgedReviewMain.weakerProtocol(current, current));
        assertThrows(IllegalStateException.class, () -> BookUnjudgedReviewMain.weakerProtocol("OTHER", current));
        // The weaker record applies only when an earlier decision row is carried over unchanged.
        String header = BookQueryDatasetV2.REVIEW_HEADER + "\n";
        byte[] earlier = (header + "UJ-1|a\nUJ-2|b\n").getBytes(StandardCharsets.UTF_8);
        assertTrue(BookUnjudgedReviewMain.carriesDecisions(earlier,
                (header + "UJ-1|a\nUJ-3|c\n").getBytes(StandardCharsets.UTF_8)));
        assertFalse(BookUnjudgedReviewMain.carriesDecisions(earlier,
                (header + "UJ-1|changed\n").getBytes(StandardCharsets.UTF_8)));
        assertFalse(BookUnjudgedReviewMain.carriesDecisions(earlier, header.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMalformedManifestEscapeIsAValidationFailure() {
        // A Windows-style account name puts a backslash before "user": an invalid Unicode escape for Properties.
        String manifest = "judgment.reviewer=DOMAIN" + '\\' + "user\n";
        var e = assertThrows(IllegalStateException.class,
                () -> BookQueryDatasetV2.readManifest(manifest.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().startsWith("malformed book-queries-v2 manifest.properties: "), e.getMessage());
    }

    @Test
    void manifestUpdatesReplaceExactlyOneExistingKey() {
        String manifest = "# c\ncounts.pending=60\ncounts.pending.x=1\n";
        assertEquals("# c\ncounts.pending=59\ncounts.pending.x=1\n",
                BookUnjudgedReviewMain.replace(manifest, "counts.pending", "59"));
        assertThrows(IllegalStateException.class, () -> BookUnjudgedReviewMain.replace(manifest, "counts.none", "1"));
    }
}
