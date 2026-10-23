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
 * Markdown summary to {@code --summary} (or standard output after the report), both written atomically.
 *
 * <p>Exit 0 when the report was produced and result-id parity held, 1 when the inputs are not the judged corpus or
 * parity failed (the report is still written), 2 on a usage error (a ladder scale that selects no chunk included), a
 * missing file, an unsafe scratch directory or an I/O failure (a child JVM that times out or fails included).
 */
public final class BookLatencyMain {
    static final String SCRATCH_MARKER = ".monada-book-latency-scratch";
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
                    scale.select(texts);
                } catch (IllegalArgumentException e) {
                    // Checked against the loaded corpus before scratch exists: a ladder is a usage error.
                    err.println(e.getMessage() + "\n" + USAGE);
                    return 2;
                }
            }

            Files.createDirectory(scratch);
            BookLatencyBenchmark.Result result;
            try {
                // Inside the cleanup scope: a failed marker write must not leave scratch behind to block reruns.
                Files.writeString(scratch.resolve(SCRATCH_MARKER), "monada-book-latency-scratch v1\n",
                        StandardCharsets.UTF_8);
                result = BookLatencyBenchmark.run(texts, queries, inputs, config, scratch,
                        System.getProperty("java.class.path"));
            } finally {
                if (!keep) {
                    BookLatencyBenchmark.deleteTree(scratch);
                }
            }
            String rendering = BookLatencyReport.render(result);
            String summaryText = BookLatencyReport.summary(result);
            if (report == null) {
                out.print(rendering);
            } else {
                BookRecallMain.write(report, rendering);
                out.println("wrote " + report);
            }
            if (summary == null) {
                out.print(summaryText);
            } else {
                BookRecallMain.write(summary, summaryText);
                out.println("wrote " + summary);
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
        if (report != null && summary != null
                && report.toAbsolutePath().normalize().equals(summary.toAbsolutePath().normalize())) {
            return "--out and --summary must name different files: " + report;
        }
        for (Path file : new Path[] {report, summary}) {
            if (file == null) {
                continue;
            }
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.startsWith(scratch)) {
                return "output file must not be inside --scratch, which is deleted: " + file;
            }
            if (absolute.getParent() == null || !Files.isDirectory(absolute.getParent())) {
                return "output directory does not exist: " + absolute.getParent();
            }
        }
        return null;
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
