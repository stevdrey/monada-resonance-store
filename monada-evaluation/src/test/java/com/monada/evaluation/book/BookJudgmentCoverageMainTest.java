package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command-line contract of {@code runBookJudgmentCoverage} (Issue #184). */
class BookJudgmentCoverageMainTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    private static final Path BASELINE = ROOT.resolve(BookUnjudgedSelection.BASELINE_REPORT);

    @TempDir
    Path tmp;

    private record Result(int code, String out, String err) {
    }

    /** Runs with the repository root as root, as the Gradle task does. */
    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = BookJudgmentCoverageMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), ROOT);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theReportMatchesTheCommittedReport() throws Exception {
        // Any JVM must regenerate these exact bytes from the committed inputs.
        String committed = Files.readString(ROOT.resolve(BookJudgmentCoverageMain.REPORT));
        Result printed = run();
        assertEquals(0, printed.code(), printed.err());
        assertEquals(committed, printed.out(),
                "regenerate " + BookJudgmentCoverageMain.REPORT + " with runBookJudgmentCoverage if an input changed");
        Path out = tmp.resolve("report.txt");
        assertEquals(0, run("--baseline", BASELINE.toString(), "--out", out.toString()).code());
        assertEquals(committed, Files.readString(out));
    }

    @Test
    void anEditedBaselineIsRejected() throws Exception {
        Path edited = tmp.resolve("baseline.txt");
        Files.writeString(edited, Files.readString(BASELINE) + "\n");
        Result r = run("--baseline", edited.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().startsWith("baseline report SHA-256 is "), r.err());
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run("report").code());
        assertEquals(2, run("--bogus", "x").code());
        assertEquals(2, run("--out").code());
        assertEquals(2, run("--baseline", tmp.resolve("missing.txt").toString()).code());
    }

    @Test
    void frozenInputsAreNeverAnOutput() throws Exception {
        Path resources = ROOT.resolve("monada-evaluation/src/main/resources/com/monada/evaluation/book");
        for (Path target : List.of(BASELINE, resources.resolve("book-queries-v2/review.psv"),
                resources.resolve("book-queries-v1/judgments.psv"),
                ROOT.resolve(BookUnjudgedReviewMain.COVERAGE_REPORT))) {
            byte[] bytes = Files.readAllBytes(target);
            Result r = run("--out", target.toString());
            assertEquals(2, r.code(), r.err());
            assertTrue(r.err().startsWith("--out must not be"), r.err());
            Path link = Files.createSymbolicLink(tmp.resolve("alias-" + target.getFileName()), target);
            assertEquals(2, run("--out", link.toString()).code());
            assertArrayEquals(bytes, Files.readAllBytes(target));
        }
        Path fresh = ROOT.resolve("docs/experiments/book-corpus-v1/new-output.txt");
        assertEquals(2, run("--out", fresh.toString()).code());
        assertTrue(Files.notExists(fresh));
    }

    @Test
    void onlyItsOwnReportMayBeRegeneratedInPlace() throws Exception {
        Path report = ROOT.resolve(BookJudgmentCoverageMain.REPORT);
        assertNull(BookUnjudgedReviewMain.unsafeOutput(report, BASELINE, ROOT, report));
    }
}
