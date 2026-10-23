package com.monada.evaluation.book;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Offline book latency benchmark (Issue #168, Book 6/8; evaluation only, no network):
 *
 * <pre>
 * ./gradlew :monada-evaluation:runBookLatency -q --args="--input &lt;body.txt&gt; --scratch &lt;new dir&gt;
 *     [--out &lt;report.txt&gt;] [--summary &lt;summary.md&gt;] [--ladder 25%,50%,100%,x4,x16] [--ingestTrials 5]
 *     [--ingestWarmups 1] [--openTrials 20] [--openWarmups 2] [--queryPasses 10] [--queryWarmupPasses 2]
 *     [--processes 5] [--keep no|yes] [--manifest &lt;manifest.properties&gt;]"
 * </pre>
 *
 * <p>The body is verified against the corpus manifest and chunked with {@link BookChunkingPolicy#V1}; the chunk set
 * must be the one {@code book-queries-v1} was judged against, whose 32 query texts are the workload. Every Store is
 * created under {@code --scratch}, which must not exist yet (it is created with a marker, outside any Git work tree,
 * and deleted afterwards unless {@code --keep yes}). The report goes to {@code --out} (or standard output) and the
 * Markdown summary to {@code --summary}, both written atomically. Without {@code --summary} the summary goes to
 * standard output only when the report went to {@code --out}; standard output never mixes the two. Neither output
 * may replace a committed, frozen experiment artifact ({@link #refuseProtectedOutputs}) except this benchmark's own
 * report and summary.
 *
 * <p>Exit 0 when the report was produced and result-id parity held, 1 when the inputs are not the judged corpus or
 * parity failed (the report is still written), 2 on a usage error (a ladder scale that selects no chunk included), a
 * missing file, an unsafe scratch directory or an I/O failure (a child JVM that times out or fails, and standard
 * output that cannot be written, and a scratch directory that cannot be removed, included).
 */
public final class BookLatencyMain {
    static final String SCRATCH_MARKER = ".monada-book-latency-scratch";
    /** The one frozen-directory file {@code --out} may regenerate: this benchmark's own committed report. */
    static final String LATENCY_REPORT = "docs/experiments/book-corpus-v1/book-latency-v1-report.txt";
    /** The one frozen-directory file {@code --summary} may regenerate: this benchmark's own committed summary. */
    static final String LATENCY_SUMMARY = "docs/experiments/book-corpus-v1/book-latency-v1-summary.md";
    private static final String USAGE = "usage: --input <normalized body.txt> --scratch <new dir> [--out <report.txt>]"
            + " [--summary <summary.md>] [--ladder " + BookLatencyScale.DEFAULT_LADDER + "] [--ingestTrials n]"
            + " [--ingestWarmups n] [--openTrials n] [--openWarmups n] [--queryPasses n] [--queryWarmupPasses n]"
            + " [--processes n] [--keep no|yes] [--manifest <manifest.properties>]";
    private static final Set<String> OPTIONS = Set.of("--input", "--scratch", "--out", "--summary", "--ladder",
            "--ingestTrials", "--ingestWarmups", "--openTrials", "--openWarmups", "--queryPasses",
            "--queryWarmupPasses", "--processes", "--keep", "--manifest");

    private BookLatencyMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Path.of(""));
    }

    /** {@code root} is the repository root: the protected, frozen experiment artifacts resolve against it. */
    static int run(String[] args, PrintStream out, PrintStream err, Path root) {
        Map<String, String> options = BookStoreReadbackMain.parseOptions(args, 0, OPTIONS, null, err, USAGE);
        if (options == null) {
            return 2;
        }
        if (!options.containsKey("--input") || !options.containsKey("--scratch")) {
            err.println(USAGE);
            return 2;
        }
        BookLatencyBenchmark.Config config;
        boolean keep;
        try {
            config = config(options);
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
            String refused = refuseScratch(scratch, report, summary);
            if (refused == null) {
                refused = refuseOverwritingInputs(List.of(input, manifest), report, summary);
            }
            if (refused == null) {
                refused = refuseProtectedOutputs(root, report, summary);
            }
            if (refused != null) {
                err.println(refused);
                return 2;
            }

            // Setup, untimed: verify and chunk the body, bind it to the judged dataset, before scratch exists.
            VerifiedBookSource source = VerifiedBookSource.load(manifest, input);
            BookChunker.Result chunked = BookChunker.chunk(source, BookChunkingPolicy.V1);
            BookChunkOutputWriter.Output rendered = BookChunkOutputWriter.render(chunked);
            BookChunkFiles.Loaded chunks = BookChunkFiles.parse(rendered.chunks(), rendered.sidecar());
            BookQueryDataset dataset = BookQueryDataset.load();
            Map<String, String> inputs = bind(dataset.manifest(), chunks, rendered);
            List<String> queries = dataset.cases().stream().sorted(Comparator.comparing(BookQueryDataset.Case::id))
                    .map(BookQueryDataset.Case::text).toList();
            List<String> texts = chunks.entries().stream().map(BookChunkFiles.Entry::text).toList();

            for (BookLatencyScale scale : config.ladder()) {
                try {
                    scale.requireSelects(texts.size());
                } catch (IllegalArgumentException e) {
                    // Checked against the loaded corpus before scratch exists: a ladder is a usage error.
                    err.println(e.getMessage() + "\n" + USAGE);
                    return 2;
                }
            }

            Files.createDirectory(scratch);
            Cleaned<BookLatencyBenchmark.Result> run = runAndClean(scratch, keep, () -> {
                // Inside the cleanup scope: a failed marker write must not leave scratch behind to block reruns.
                Files.writeString(scratch.resolve(SCRATCH_MARKER), "monada-book-latency-scratch v1\n",
                        StandardCharsets.UTF_8);
                return BookLatencyBenchmark.run(texts, queries, inputs, config, scratch,
                        System.getProperty("java.class.path"));
            });
            BookLatencyBenchmark.Result result = run.value();
            String rendering = BookLatencyReport.render(result);
            String summaryText = BookLatencyReport.summary(result);
            if (!emit(out, err, report, rendering, summary, summaryText)) {
                err.println("I/O failure: standard output could not be written; the report or summary is incomplete");
                return 2;
            }
            if (run.cleanupFailure() != null) {
                // The measured result was emitted first; only then is the leftover scratch reported.
                err.println("I/O failure: --scratch could not be removed (delete it before rerunning): "
                        + run.cleanupFailure());
                return 2;
            }
            if (!result.parity().passed()) {
                err.println("result-id parity FAILED: " + result.parity().mismatches() + " of "
                        + result.parity().checks() + " comparisons differ");
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

    /** Benchmark work that may throw any checked failure the command classifies. */
    @FunctionalInterface
    interface Task<T> {
        T run() throws IOException, BookInputException;
    }

    /** A task's value plus the failure to remove scratch afterwards, if any (null when it was removed or kept). */
    record Cleaned<T>(T value, IOException cleanupFailure) {
    }

    /**
     * Runs {@code task}, then removes {@code scratch} unless {@code keep}. When the task fails, a cleanup failure is
     * attached to the task's exception as suppressed, so the real cause is never replaced; when it succeeds, the
     * cleanup failure is returned instead of thrown, so the measured result is still reported.
     */
    static <T> Cleaned<T> runAndClean(Path scratch, boolean keep, Task<T> task)
            throws IOException, BookInputException {
        T value;
        try {
            value = task.run();
        } catch (IOException | BookInputException | RuntimeException | Error e) {
            if (!keep) {
                try {
                    BookLatencyBenchmark.deleteTree(scratch);
                } catch (IOException | RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        IOException cleanupFailure = null;
        if (!keep) {
            try {
                BookLatencyBenchmark.deleteTree(scratch);
            } catch (IOException e) {
                cleanupFailure = e;
            }
        }
        return new Cleaned<>(value, cleanupFailure);
    }

    /**
     * Writes the report and the summary to their files, or to {@code out} when no file was named. Standard output
     * carries the machine-readable report alone: when the report goes there and no summary file was named, the
     * Markdown summary is skipped (noted on {@code err}) instead of being appended to it. Returns false when
     * {@code out} reported a write failure ({@link PrintStream} swallows it), so a truncated or lost artifact is never
     * reported as success.
     */
    static boolean emit(PrintStream out, PrintStream err, Path report, String rendering, Path summary,
                        String summaryText) throws IOException {
        if (report == null) {
            out.print(rendering);
        } else {
            BookRecallMain.write(report, rendering);
            out.println("wrote " + report);
        }
        if (summary != null) {
            BookRecallMain.write(summary, summaryText);
            out.println("wrote " + summary);
        } else if (report != null) {
            out.print(summaryText);
        } else {
            err.println("summary not written: standard output holds the key=value report; pass --summary <file>");
        }
        return !out.checkError();
    }

    private static BookLatencyBenchmark.Config config(Map<String, String> options) {
        BookLatencyBenchmark.Config d = BookLatencyBenchmark.Config.defaults();
        return new BookLatencyBenchmark.Config(
                options.containsKey("--ladder") ? BookLatencyScale.parseLadder(options.get("--ladder")) : d.ladder(),
                count(options, "--ingestWarmups", d.ingestWarmups()), count(options, "--ingestTrials", d.ingestTrials()),
                count(options, "--openWarmups", d.openWarmups()), count(options, "--openTrials", d.openTrials()),
                count(options, "--queryWarmupPasses", d.queryWarmupPasses()),
                count(options, "--queryPasses", d.queryPasses()), count(options, "--processes", d.processes()));
    }

    private static int count(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got " + value);
        }
    }

    /**
     * Why {@code scratch} cannot hold the benchmark Stores, or null: it must not exist yet, its parent must, it must
     * not lie inside a Git work tree (Stores never land in a repository), and no output file may live inside it.
     */
    static String refuseScratch(Path scratch, Path report, Path summary) throws IOException {
        if (Files.exists(scratch, LinkOption.NOFOLLOW_LINKS)) {
            return "--scratch must not exist yet (it is created and deleted by the run): " + scratch;
        }
        Path parent = scratch.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return "--scratch parent does not exist: " + parent;
        }
        for (Path dir = parent.toRealPath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return "--scratch must be outside a Git work tree (" + dir + "): " + scratch;
            }
        }
        if (report != null && summary != null && directoryEntry(report).equals(directoryEntry(summary))) {
            return "--out and --summary must name different files: " + report;
        }
        Path scratchEntry = directoryEntry(scratch);
        for (Path file : new Path[] {report, summary}) {
            if (file == null) {
                continue;
            }
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.startsWith(scratch) || directoryEntry(file).startsWith(scratchEntry)) {
                return "output file must not be inside --scratch, which is deleted: " + file;
            }
            if (absolute.getParent() == null || !Files.isDirectory(absolute.getParent())) {
                return "output directory does not exist: " + absolute.getParent();
            }
        }
        return null;
    }

    /**
     * Why an output would replace a benchmark input, or null: {@code --out} and {@code --summary} must not name the
     * body or the manifest, compared on normalized absolute paths and, where both exist, on the files themselves (so
     * a symbolic or relative alias is caught too). Checked before anything runs.
     */
    static String refuseOverwritingInputs(List<Path> inputs, Path report, Path summary) throws IOException {
        for (Path output : new Path[] {report, summary}) {
            if (output == null) {
                continue;
            }
            for (Path input : inputs) {
                boolean same = directoryEntry(output).equals(directoryEntry(input))
                        || (Files.exists(output) && Files.exists(input) && Files.isSameFile(output, input));
                if (same) {
                    return "output file " + output + " would overwrite the benchmark input " + input;
                }
            }
        }
        return null;
    }

    /**
     * Why an output would replace a committed, frozen experiment artifact (the recall, hub and review reports, the
     * book-queries v1/v2 resources, the corpus contract), or null: the guard of {@link
     * BookUnjudgedReviewMain#protectedOutput}, with normalized paths, symbolic-linked parents and symbolic or hard
     * link aliases all refused. The only exceptions are this benchmark's own artifacts, each for its own option:
     * {@code --out} may regenerate {@value #LATENCY_REPORT} and {@code --summary} {@value #LATENCY_SUMMARY}. Checked
     * before anything is read or created.
     */
    static String refuseProtectedOutputs(Path root, Path report, Path summary) throws IOException {
        String refused = report == null ? null
                : BookUnjudgedReviewMain.protectedOutput("--out", report, root, root.resolve(LATENCY_REPORT));
        if (refused == null && summary != null) {
            refused = BookUnjudgedReviewMain.protectedOutput("--summary", summary, root,
                    root.resolve(LATENCY_SUMMARY));
        }
        return refused;
    }

    /**
     * The directory entry an atomic write to {@code file} replaces: the file name under its parent's real path (when
     * the parent exists), so two spellings through a symbolic-linked or relative parent compare equal even when the
     * file itself does not exist yet. The file name itself is not resolved: the move replaces the entry, not a link
     * target.
     */
    static Path directoryEntry(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null || !Files.isDirectory(parent)) {
            return absolute;
        }
        return parent.toRealPath().resolve(absolute.getFileName().toString());
    }

    /** The chunk set must be the one {@code book-queries-v1} was judged against; returns the report's inputs. */
    private static Map<String, String> bind(Properties dataset, BookChunkFiles.Loaded chunks,
                                            BookChunkOutputWriter.Output rendered) throws BookInputException {
        String chunkMap = VerifiedBookSource.sha256(rendered.sidecar());
        expect("corpus", chunks.corpus(), dataset.getProperty("corpus"));
        expect("source.sha256", chunks.sourceSha256(), dataset.getProperty("source.sha256"));
        expect("chunking.policy", chunks.policyId(), dataset.getProperty("chunking.policy"));
        expect("chunksFile.sha256", chunks.chunksFileSha256(), dataset.getProperty("chunksFile.sha256"));
        expect("chunkMap.sha256", chunkMap, dataset.getProperty("chunkMap.sha256"));
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("corpus", chunks.corpus());
        inputs.put("corpusVersion", chunks.corpusVersion());
        inputs.put("source.sha256", chunks.sourceSha256());
        inputs.put("chunking.policy", chunks.policyId());
        inputs.put("chunks.count", String.valueOf(chunks.entries().size()));
        inputs.put("chunksFile.sha256", chunks.chunksFileSha256());
        inputs.put("chunkMap.sha256", chunkMap);
        inputs.put("dataset", dataset.getProperty("dataset") + " v" + dataset.getProperty("datasetVersion")
                + " (query texts only; judgments unused)");
        inputs.put("queries.sha256", dataset.getProperty("queries.sha256"));
        return inputs;
    }

    private static void expect(String field, String actual, String expected) throws BookInputException {
        if (!Objects.equals(actual, expected)) {
            throw new BookInputException(BookInputException.Reason.INVALID_INPUT, "foreign corpus input: the chunk"
                    + " set's " + field + " is " + actual + ", book-queries-v1 expects " + expected);
        }
    }
}
