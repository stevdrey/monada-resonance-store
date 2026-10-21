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
 * Offline book hub-concentration diagnostic (Issue #176, evaluation only; no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookHubDiagnostics --args="--work &lt;seeded dir&gt;
 *     --baseline docs/experiments/book-corpus-v1/book-recall-v1-report.txt [--out &lt;report.txt&gt;]"
 * </pre>
 *
 * <p>{@code --work} is the seeded work directory measured by {@code runBookRecall}; it is only read. The recall
 * evaluation is re-run on a throwaway Store copy and must reproduce {@code --baseline} byte for byte before anything
 * is diagnosed, so the report always explains that committed baseline. The report goes to standard output, or to
 * {@code --out} (written atomically, outside the work directory).
 *
 * <p>Exit 0 when the report was produced, 1 when the inputs are not the judged corpus, the baseline is not
 * reproduced, score parity fails, the inputs change during the run or reading them fails unexpectedly, 2 on a usage
 * error, a missing file, an unsafe work directory or an I/O failure.
 */
public final class BookHubDiagnosticsMain {
    private static final String USAGE = "usage: --work <seeded experiment dir> --baseline <book-recall report>"
            + " [--out <report.txt>]";

    private BookHubDiagnosticsMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0,
                Set.of("--work", "--baseline", "--out"), null, err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--work") || !options.containsKey("--baseline")) {
            err.println(USAGE);
            return 2;
        }
        try {
            Path work = ExperimentDirectoryGuard.requireSeeded(Path.of(options.get("--work")));
            Path target = options.containsKey("--out") ? Path.of(options.get("--out")) : null;
            if (target != null && BookRecallMain.insideWork(target, work)) {
                err.println("--out must not be inside the work directory, which is only read: " + target);
                return 2;
            }
            Path outDir = target == null ? null : target.toAbsolutePath().normalize().getParent();
            if (outDir != null && !Files.isDirectory(outDir)) {
                err.println("--out directory does not exist: " + outDir);
                return 2;
            }
            byte[] baseline = Files.readAllBytes(Path.of(options.get("--baseline")));
            String report = BookHubReport.render(BookHubProbe.diagnose(work, BookQueryDataset.load(), baseline));
            if (target == null) {
                out.print(report);
            } else {
                BookRecallMain.write(target, report);
                out.println("wrote " + target);
            }
            return 0;
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile());
            return 2;
        } catch (ExperimentDirectoryGuard.UnsafeDirectoryException e) {
            err.println(e.getMessage());
            return 2;
        } catch (BookInputException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("I/O failure: " + e);
            return 2;
        } catch (RuntimeException e) {
            // Unexpected failures while reading the copied Store still end with a message and exit 1.
            err.println("diagnostic failed: " + e);
            return 1;
        }
    }
}
