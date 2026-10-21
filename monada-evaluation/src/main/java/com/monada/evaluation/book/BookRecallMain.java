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
import java.util.HashMap;
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
 * {@link ExperimentDirectoryGuard}); it is only read, and queries run on a throwaway Store copy. The judged dataset is
 * the bundled {@code book-queries-v1}. The report goes to standard output, or to {@code --out} (written atomically).
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
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (!Set.of("--work", "--out").contains(name)) {
                err.println("unknown option " + name + "\n" + USAGE);
                return 2;
            }
            if (i + 1 >= args.length || options.put(name, args[++i]) != null) {
                err.println("option " + name + " needs exactly one value\n" + USAGE);
                return 2;
            }
        }
        if (!options.containsKey("--work")) {
            err.println(USAGE);
            return 2;
        }
        try {
            Path work = ExperimentDirectoryGuard.requireSeeded(Path.of(options.get("--work")));
            Path target = options.containsKey("--out") ? Path.of(options.get("--out")) : null;
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
