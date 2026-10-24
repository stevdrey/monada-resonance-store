package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline book feedback A/B and explicit-note experiment (Issue #170, Book 8/8; evaluation only, no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookFeedback -q --args="--input &lt;body.txt&gt; --scratch &lt;new dir&gt;
 *     [--out &lt;report.txt&gt;] [--baseline &lt;book-recall-v1-report.txt&gt;] [--keep no|yes]
 *     [--manifest &lt;manifest.properties&gt;]"
 * </pre>
 *
 * <p>The body is verified against the corpus manifest and chunked with {@link BookChunkingPolicy#V1}; the judged
 * dataset is the bundled {@code book-queries-v1}. {@code --scratch} follows the {@code runBookLatency} contract: it
 * must not exist yet and must be outside any Git work tree; it is created with a marker and deleted afterwards unless
 * {@code --keep yes}. The whole experiment runs twice, in {@code run-a/} and {@code run-b/}, on new Stores, and the
 * two renderings must be byte-identical. The control arm must reproduce {@code --baseline} (default: the committed
 * recall report) byte for byte. {@code --out} is written atomically and may not replace any frozen artifact except
 * {@value #FEEDBACK_REPORT}.
 *
 * <p>Exit 0 when the report was produced and every hard check passed, 1 when the inputs are not the judged corpus,
 * the feedback plan is invalid or a check failed (the report is still written), 2 on a usage error, a missing file,
 * an unsafe scratch or output path, or an I/O failure (a scratch directory that cannot be removed included).
 */
public final class BookFeedbackMain {
    static final String SCRATCH_MARKER = ".monada-book-feedback-scratch";
    static final String FEEDBACK_REPORT = "docs/experiments/book-corpus-v1/book-feedback-v1-report.txt";
    static final String BASELINE_REPORT = "docs/experiments/book-corpus-v1/book-recall-v1-report.txt";
    private static final String USAGE = "usage: --input <normalized body.txt> --scratch <new dir> [--out <report.txt>]"
            + " [--baseline <book-recall-v1-report.txt>] [--keep no|yes] [--manifest <manifest.properties>]";
    private static final Set<String> OPTIONS = Set.of("--input", "--scratch", "--out", "--baseline", "--keep",
            "--manifest");

    private BookFeedbackMain() {
    }

    /** The rendered report and whether every hard check, repeat-run identity included, passed. */
    private record Rendered(boolean passed, String text) {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Path.of(""));
    }

    /** {@code root} is the repository root: the baseline and the protected artifacts resolve against it. */
    static int run(String[] args, PrintStream out, PrintStream err, Path root) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, OPTIONS, null, err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--input") || !options.containsKey("--scratch")) {
            err.println(USAGE);
            return 2;
        }
        boolean keep;
        switch (options.getOrDefault("--keep", "no")) {
            case "yes" -> keep = true;
            case "no" -> keep = false;
            default -> {
                err.println("--keep must be yes or no\n" + USAGE);
                return 2;
            }
        }
        try {
            Path input = Path.of(options.get("--input"));
            Path manifest = Path.of(options.getOrDefault("--manifest", BookChunkingMain.DEFAULT_MANIFEST));
            Path baseline = options.containsKey("--baseline") ? Path.of(options.get("--baseline"))
                    : root.resolve(BASELINE_REPORT);
            Path scratch = Path.of(options.get("--scratch")).toAbsolutePath().normalize();
            Path report = options.containsKey("--out") ? Path.of(options.get("--out")) : null;
            String refused = BookLatencyMain.refuseScratch(scratch, report, null);
            if (refused == null) {
                refused = BookLatencyMain.refuseOverwritingInputs(List.of(input, manifest, baseline), report, null);
            }
            if (refused == null && report != null) {
                refused = BookUnjudgedReviewMain.protectedOutput("--out", report, root,
                        root.resolve(FEEDBACK_REPORT));
            }
            if (refused != null) {
                err.println(refused);
                return 2;
            }

            // Setup: verify and chunk the body and read the baseline before scratch exists.
            VerifiedBookSource source = VerifiedBookSource.load(manifest, input);
            BookChunker.Result chunked = BookChunker.chunk(source, BookChunkingPolicy.V1);
            BookQueryDataset dataset = BookQueryDataset.load();
            byte[] baselineBytes = Files.readAllBytes(baseline);

            Files.createDirectory(scratch);
            BookLatencyMain.Cleaned<Rendered> run = BookLatencyMain.runAndClean(scratch, keep, () -> {
                Files.writeString(scratch.resolve(SCRATCH_MARKER), "monada-book-feedback-scratch v1\n",
                        StandardCharsets.UTF_8);
                BookFeedbackExperiment.Result first = BookFeedbackExperiment.run(chunked, dataset, baselineBytes,
                        scratch.resolve("run-a"));
                BookFeedbackExperiment.Result second = BookFeedbackExperiment.run(chunked, dataset, baselineBytes,
                        scratch.resolve("run-b"));
                String body = BookFeedbackReport.body(first);
                boolean identical = body.equals(BookFeedbackReport.body(second));
                return new Rendered(first.passed() && second.passed() && identical,
                        BookFeedbackReport.render(first, identical));
            });
            String rendering = run.value().text();
            if (report == null) {
                out.print(rendering);
            } else {
                BookRecallMain.write(report, rendering);
                out.println("wrote " + report);
            }
            if (out.checkError()) {
                err.println("I/O failure: standard output could not be written; the report is incomplete");
                return 2;
            }
            if (run.cleanupFailure() != null) {
                err.println("I/O failure: --scratch could not be removed (delete it before rerunning): "
                        + run.cleanupFailure());
                return 2;
            }
            if (!run.value().passed()) {
                err.println("hard checks FAILED (see the report's \"Hard checks\" and \"Repeat run\" sections)");
                return 1;
            }
            return 0;
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile() + " (see docs/experiments/book-corpus-v1/README.md,"
                    + " \"Offline acquisition\" and --write-normalized)");
            return 2;
        } catch (BookInputException e) {
            err.println(e.getMessage());
            return e.reason() == BookInputException.Reason.BAD_MANIFEST ? 2 : 1;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("I/O failure: " + e);
            return 2;
        }
    }
}
