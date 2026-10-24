package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Renders the v1/v2 judgment-coverage report (Issue #184, evaluation only; offline; zero searches):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookJudgmentCoverage --args="[--baseline &lt;report&gt;] [--out &lt;file&gt;]"
 * </pre>
 *
 * <p>The only ranking input is the frozen baseline (default {@value BookUnjudgedSelection#BASELINE_REPORT}), pinned
 * by SHA-256. {@code --out} never writes over a frozen input (see {@link BookUnjudgedReviewMain#unsafeOutput}); the one
 * protected file it may regenerate is {@value #REPORT}.
 *
 * <p>Exit 0 on success, 1 when the inputs fail validation, 2 on a usage error, a missing file, an unsafe output path
 * or an I/O failure.
 */
public final class BookJudgmentCoverageMain {
    static final String REPORT = "docs/experiments/book-corpus-v1/book-judgment-coverage-v2-report.txt";
    private static final String USAGE = "usage: [--baseline <report>] [--out <file>]";

    private BookJudgmentCoverageMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Path.of(""));
    }

    /** {@code root} is the repository root: the default baseline and the protected inputs resolve against it. */
    static int run(String[] args, PrintStream out, PrintStream err, Path root) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, Set.of("--baseline", "--out"), null,
                err, USAGE);
        if (options == null) {
            return 2;
        }
        try {
            Path baseline = options.containsKey("--baseline") ? Path.of(options.get("--baseline"))
                    : root.resolve(BookUnjudgedSelection.BASELINE_REPORT);
            if (options.containsKey("--out")) {
                String unsafe = BookUnjudgedReviewMain.unsafeOutput(Path.of(options.get("--out")), baseline, root,
                        root.resolve(REPORT));
                if (unsafe != null) {
                    err.println(unsafe);
                    return 2;
                }
            }
            String report = BookJudgmentCoverageReport.render(BookJudgmentCoverage.load(Files.readAllBytes(baseline)));
            if (options.containsKey("--out")) {
                BookRecallMain.write(Path.of(options.get("--out")), report);
                out.println("wrote " + options.get("--out"));
            } else {
                out.print(report);
            }
            return 0;
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile());
            return 2;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("I/O failure: " + e);
            return 2;
        }
    }
}
