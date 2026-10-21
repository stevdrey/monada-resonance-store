package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline book Store readback experiment (Issue #165, evaluation only; no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookStoreReadback --args="seed --input &lt;body.txt&gt; --work &lt;dir&gt; [--manifest &lt;manifest.properties&gt;]"
 * ./gradlew :monada-evaluation:runBookStoreReadback --args="verify --work &lt;dir&gt;"
 * </pre>
 *
 * <p>{@code seed} chunks the verified body with {@link BookChunkingPolicy#V1}, writes the chunk files to
 * {@code <work>/chunks}, remembers every chunk into the Store at {@code <work>/store} and verifies it on fresh
 * instances. Running it again on the same work directory is reported as a {@code REPEAT} import. {@code verify} only
 * reads: run it in a separate process to prove the Store is recoverable from disk alone. The work directory must
 * be absent (it is created with a marker) or a marked experiment directory; anything else is refused before any
 * write (see {@link ExperimentDirectoryGuard}).
 *
 * <p>Exit 0 when the readback passes, 1 when it fails (or the body is not the verified text), 2 on a usage error, a
 * missing file, a bad manifest, an unsafe work directory or an I/O failure.
 */
public final class BookStoreReadbackMain {
    private static final String USAGE = "usage: seed --input <normalized body.txt> --work <experiment dir>"
            + " [--manifest <manifest.properties>]\n       verify --work <experiment dir>";

    private BookStoreReadbackMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Parses {@code --name value} pairs from {@code args[from..]}, shared by the book experiment commands. Prints the
     * error plus {@code usage} and returns null on an unknown option ({@code context}, when not null, names the
     * subcommand) or an option without exactly one value.
     */
    static Map<String, String> parseOptions(String[] args, int from, Set<String> allowed, String context,
                                            PrintStream err, String usage) {
        Map<String, String> options = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            String name = args[i];
            if (!allowed.contains(name)) {
                err.println("unknown option " + name + (context == null ? "" : " for " + context) + "\n" + usage);
                return null;
            }
            if (i + 1 >= args.length || options.put(name, args[++i]) != null) {
                err.println("option " + name + " needs exactly one value\n" + usage);
                return null;
            }
        }
        return options;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !Set.of("seed", "verify").contains(args[0])) {
            err.println(USAGE);
            return 2;
        }
        boolean seed = args[0].equals("seed");
        Set<String> allowed = seed ? Set.of("--input", "--work", "--manifest") : Set.of("--work");
        Map<String, String> options = parseOptions(args, 1, allowed, args[0], err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--work") || (seed && !options.containsKey("--input"))) {
            err.println(USAGE);
            return 2;
        }
        try {
            Path work = Path.of(options.get("--work"));
            if (seed) {
                Path input = Path.of(options.get("--input"));
                Path manifest = Path.of(options.getOrDefault("--manifest", BookChunkingMain.DEFAULT_MANIFEST));
                // Verify the inputs before touching the work directory, so a bad input never creates it.
                VerifiedBookSource source = VerifiedBookSource.load(manifest, input);
                BookChunker.Result result = BookChunker.chunk(source, BookChunkingPolicy.V1);
                Path dir = ExperimentDirectoryGuard.prepareForSeed(work, List.of(input, manifest));
                BookStoreReadback.Seed outcome = BookStoreReadback.seed(result, dir);
                out.print(BookStoreReadbackReport.render(outcome));
                return outcome.passed() ? 0 : 1;
            }
            Path dir = ExperimentDirectoryGuard.requireSeeded(work);
            BookStoreReadback.Verification verification = BookStoreReadback.verify(dir);
            out.print(BookStoreReadbackReport.render(verification));
            return verification.passed() ? 0 : 1;
        } catch (InvalidPathException e) {
            err.println("invalid path: " + e.getMessage() + "\n" + USAGE);
            return 2;
        } catch (NoSuchFileException e) {
            err.println("file not found: " + e.getFile() + " (see docs/experiments/book-corpus-v1/README.md,"
                    + " \"Offline acquisition\" and --write-normalized)");
            return 2;
        } catch (ExperimentDirectoryGuard.UnsafeDirectoryException e) {
            err.println(e.getMessage());
            return 2;
        } catch (BookInputException e) {
            err.println(e.getMessage());
            return e.reason() == BookInputException.Reason.BAD_MANIFEST ? 2 : 1;
        } catch (IOException | UncheckedIOException e) {
            err.println("I/O failure: " + e);
            return 2;
        }
    }
}
