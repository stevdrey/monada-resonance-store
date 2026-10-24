package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline book footprint experiment (Issue #169, Book 7/8; evaluation only, no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookFootprint -q --args="--input &lt;body.txt&gt; --scratch &lt;new dir&gt;
 *     [--out &lt;report.txt&gt;] [--summary &lt;summary.md&gt;] [--ladder 25%,50%,100%,x4,x16] [--keep no|yes]
 *     [--manifest &lt;manifest.properties&gt;]"
 * </pre>
 *
 * <p>The body is verified against the corpus manifest and chunked with {@link BookChunkingPolicy#V1}. Every Store is
 * created under {@code --scratch}, with the same contract as {@code runBookLatency} (#168): it must not exist yet, it
 * must be outside any Git work tree, it gets a marker, and it is deleted afterwards unless {@code --keep yes}. The
 * body and manifest are only read; their digests before and after the run are a check. Outputs are written atomically
 * and never replace a frozen experiment artifact, except this experiment's own report ({@code --out}) and summary
 * ({@code --summary}).
 *
 * <p>Exit 0 when the report was produced and every structural check passed, 1 when the input is not the verified
 * corpus or a check failed (the report is still written), 2 on a usage error, a missing file, an unsafe scratch or
 * output path, or an I/O failure (a scratch directory that cannot be removed included).
 */
public final class BookFootprintMain {
    static final String SCRATCH_MARKER = ".monada-book-footprint-scratch";
    static final String FOOTPRINT_REPORT = "docs/experiments/book-corpus-v1/book-footprint-v1-report.txt";
    static final String FOOTPRINT_SUMMARY = "docs/experiments/book-corpus-v1/book-footprint-v1-summary.md";
    private static final String USAGE = "usage: --input <normalized body.txt> --scratch <new dir> [--out <report.txt>]"
            + " [--summary <summary.md>] [--ladder " + BookLatencyScale.DEFAULT_LADDER + "] [--keep no|yes]"
            + " [--manifest <manifest.properties>]";
    private static final Set<String> OPTIONS = Set.of("--input", "--scratch", "--out", "--summary", "--ladder",
            "--keep", "--manifest");

    private BookFootprintMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Path.of(""), BookMemorySampler.platform());
    }

    /** {@code root} is the repository root: the protected, frozen experiment artifacts resolve against it. */
    static int run(String[] args, PrintStream out, PrintStream err, Path root, BookMemorySampler sampler) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, OPTIONS, null, err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--input") || !options.containsKey("--scratch")) {
            err.println(USAGE);
            return 2;
        }
        List<BookLatencyScale> ladder;
        boolean keep;
        try {
            ladder = BookLatencyScale.parseLadder(options.getOrDefault("--ladder", BookLatencyScale.DEFAULT_LADDER));
            keep = switch (options.getOrDefault("--keep", "no")) {
                case "yes" -> true;
                case "no" -> false;
                default -> throw new IllegalArgumentException("--keep must be yes or no");
            };
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage() + "\n" + USAGE);
            return 2;
        }
        try {
            Path input = Path.of(options.get("--input"));
            Path manifest = Path.of(options.getOrDefault("--manifest", BookChunkingMain.DEFAULT_MANIFEST));
            Path scratch = Path.of(options.get("--scratch")).toAbsolutePath().normalize();
            Path report = options.containsKey("--out") ? Path.of(options.get("--out")) : null;
            Path summary = options.containsKey("--summary") ? Path.of(options.get("--summary")) : null;
            String refused = BookLatencyMain.refuseScratch(scratch, report, summary);
            if (refused == null) {
                refused = BookLatencyMain.refuseOverwritingInputs(List.of(input, manifest), report, summary);
            }
            if (refused == null) {
                refused = refuseProtectedOutputs(root, report, summary);
            }
            if (refused != null) {
                err.println(refused);
                return 2;
            }

            // Setup: verify and chunk the body before scratch exists.
            VerifiedBookSource source = VerifiedBookSource.load(manifest, input);
            BookChunker.Result chunked = BookChunker.chunk(source, BookChunkingPolicy.V1);
            BookChunkOutputWriter.Output rendered = BookChunkOutputWriter.render(chunked);
            BookChunkFiles.Loaded chunks = BookChunkFiles.parse(rendered.chunks(), rendered.sidecar());
            try {
                // Every ladder scale and the OVERLAP seed must select a chunk: checked before scratch exists.
                BookFootprintBenchmark.requireRunnable(chunks.entries().size(), ladder);
            } catch (IllegalArgumentException e) {
                err.println(e.getMessage() + "\n" + USAGE);
                return 2;
            }
            Map<String, String> inputs = inputs(chunks, rendered);

            Files.createDirectory(scratch);
            BookLatencyMain.Cleaned<BookFootprintBenchmark.Result> run = BookLatencyMain.runAndClean(scratch, keep,
                    () -> {
                        Files.writeString(scratch.resolve(SCRATCH_MARKER), "monada-book-footprint-scratch v1\n",
                                StandardCharsets.UTF_8);
                        return BookFootprintBenchmark.run(chunks, inputs, ladder, scratch, sampler,
                                List.of(input, manifest));
                    });
            BookFootprintBenchmark.Result result = run.value();
            if (!BookLatencyMain.emit(out, err, report, BookFootprintReport.render(result), summary,
                    BookFootprintReport.summary(result))) {
                err.println("I/O failure: standard output could not be written; the report or summary is incomplete");
                return 2;
            }
            if (run.cleanupFailure() != null) {
                err.println("I/O failure: --scratch could not be removed (delete it before rerunning): "
                        + run.cleanupFailure());
                return 2;
            }
            if (!result.passed()) {
                err.println("structural checks FAILED: " + result.failures().size() + " of " + result.checks().size());
                for (BookFootprintBenchmark.Check failed : result.failures()) {
                    err.println("  " + failed.name() + ": expected " + failed.expected() + ", got "
                            + failed.actual());
                }
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

    /**
     * Why an output would replace a committed, frozen experiment artifact, or null: the shared guard of
     * {@link BookUnjudgedReviewMain#protectedOutput}, with this experiment's own report and summary allowed, each for
     * its own option only.
     */
    static String refuseProtectedOutputs(Path root, Path report, Path summary) throws IOException {
        String refused = report == null ? null
                : BookUnjudgedReviewMain.protectedOutput("--out", report, root, root.resolve(FOOTPRINT_REPORT));
        if (refused == null && summary != null) {
            refused = BookUnjudgedReviewMain.protectedOutput("--summary", summary, root,
                    root.resolve(FOOTPRINT_SUMMARY));
        }
        return refused;
    }

    private static Map<String, String> inputs(BookChunkFiles.Loaded chunks, BookChunkOutputWriter.Output rendered) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("corpus", chunks.corpus());
        inputs.put("corpusVersion", chunks.corpusVersion());
        inputs.put("source.sha256", chunks.sourceSha256());
        inputs.put("chunking.policy", chunks.policyId());
        inputs.put("chunks.count", String.valueOf(chunks.entries().size()));
        inputs.put("chunks.duplicateContent",
                String.valueOf(chunks.entries().stream().filter(e -> e.duplicateOf() != null).count()));
        inputs.put("chunksFile.sha256", chunks.chunksFileSha256());
        inputs.put("chunkMap.sha256", VerifiedBookSource.sha256(rendered.sidecar()));
        return inputs;
    }
}
