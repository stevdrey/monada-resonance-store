package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline command line chunker for the verified {@code book-corpus-v1} normalized body (policy
 * {@link BookChunkingPolicy#V1}); JDK only, no network:
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookChunking --args="--input &lt;body.txt&gt; --out &lt;dir&gt; [--manifest &lt;manifest.properties&gt;]"
 * </pre>
 *
 * <p>The manifest defaults to {@value #DEFAULT_MANIFEST} (relative to the working directory, the repository root
 * under Gradle). Writes {@code chunks.jsonl} and {@code sidecar.jsonl} into the output directory and prints a
 * deterministic report. Exit 0 on success, 1 when the body is not the verified normalized text, 2 on a usage error,
 * a missing file, a bad manifest, an output path that would overwrite an input, or an I/O failure.
 */
public final class BookChunkingMain {
    static final String DEFAULT_MANIFEST = "docs/experiments/book-corpus-v1/manifest.properties";
    private static final String USAGE = "usage: --input <normalized body.txt> --out <output dir> "
            + "[--manifest <manifest.properties>]";

    private BookChunkingMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (!name.equals("--input") && !name.equals("--out") && !name.equals("--manifest")) {
                err.println("unknown option " + name + "\n" + USAGE);
                return 2;
            }
            if (i + 1 >= args.length || options.put(name, args[++i]) != null) {
                err.println("option " + name + " needs exactly one value\n" + USAGE);
                return 2;
            }
        }
        if (!options.containsKey("--input") || !options.containsKey("--out")) {
            err.println(USAGE);
            return 2;
        }
        try {
            Path input = Path.of(options.get("--input"));
            Path manifest = Path.of(options.getOrDefault("--manifest", DEFAULT_MANIFEST));
            Path outDir = Path.of(options.get("--out"));
            for (String file : new String[] {BookChunkOutputWriter.CHUNKS_FILE, BookChunkOutputWriter.SIDECAR_FILE}) {
                Path target = outDir.resolve(file);
                if (sameFile(target, input) || sameFile(target, manifest)) {
                    err.println("refusing to overwrite an input file: " + target);
                    return 2;
                }
            }
            VerifiedBookSource source = VerifiedBookSource.load(manifest, input);
            BookChunker.Result result = BookChunker.chunk(source, BookChunkingPolicy.V1);
            BookChunkOutputWriter.Output output = BookChunkOutputWriter.write(result, outDir);
            out.print(BookChunkingReport.of(result, output).render());
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
        } catch (IOException e) {
            err.println("I/O failure: " + e);
            return 2;
        }
    }

    private static boolean sameFile(Path a, Path b) throws IOException {
        if (Files.exists(a) && Files.exists(b)) {
            return Files.isSameFile(a, b);
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
