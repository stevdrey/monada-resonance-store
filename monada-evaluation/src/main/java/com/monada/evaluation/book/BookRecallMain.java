package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/**
 * Offline book recall-quality experiment (Issue #167, evaluation only; no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookRecall --args="--work &lt;seeded dir&gt; [--out &lt;report.txt&gt;]"
 * </pre>
 *
 * <p>{@code --work} is a work directory seeded by {@code runBookStoreReadback seed} (see
 * {@link ExperimentDirectoryGuard}); it is only read, and queries run on a throwaway Store copy, so {@code --out}
 * must point outside it. The judged dataset is the bundled {@code book-queries-v1}. The report goes to standard
 * output, or to {@code --out} (written atomically).
 *
 * <p>Exit 0 when the report was produced, 1 when the inputs are not the judged corpus, the Store fails readback, a
 * gold reference is invalid or result ordering is unstable, 2 on a usage error, a missing file, an unsafe work
 * directory or an I/O failure.
 */
public final class BookRecallMain {
    private static final String USAGE = "usage: --work <seeded experiment dir> [--out <report.txt>]";

    private BookRecallMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, Set.of("--work", "--out"), null,
                err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--work")) {
            err.println(USAGE);
            return 2;
        }
        try {
            Path work = ExperimentDirectoryGuard.requireSeeded(Path.of(options.get("--work")));
            Path target = options.containsKey("--out") ? Path.of(options.get("--out")) : null;
            if (target != null && insideWork(target, work)) {
                err.println("--out must not be inside the work directory, which is only read: " + target);
                return 2;
            }
            Path outDir = target == null ? null : target.toAbsolutePath().normalize().getParent();
            if (outDir != null && !Files.isDirectory(outDir)) {
                // Checked before the evaluation, so a typo in --out costs nothing and is not reported as a missing
                // work-directory file.
                err.println("--out directory does not exist: " + outDir);
                return 2;
            }
            String report = BookRecallReport.render(BookRecallEvaluator.evaluate(work, BookQueryDataset.load()));
            if (target == null) {
                out.print(report);
            } else {
                write(target, report);
                out.println("wrote " + target);
            }
            return 0;
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile() + " (seed the work directory first, see"
                    + " docs/experiments/book-corpus-v1/STORE-READBACK.md)");
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
        }
    }

    /**
     * Whether {@code target} would land in {@code work}: compared on real paths of the nearest existing ancestor, so
     * a symbolic link cannot route the report into the guarded tree.
     */
    static boolean insideWork(Path target, Path work) throws IOException {
        Path realWork = work.toRealPath();
        Path absolute = target.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && Files.notExists(existing)) {
            existing = existing.getParent();
        }
        Path resolved = existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute));
        return resolved.normalize().startsWith(realWork);
    }

    private static void write(Path target, String report) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, ".book-recall-", ".tmp");
        try {
            Files.writeString(tmp, report, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
