package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.ResonanceResult;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.TextNormalizer;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Measures how long the book chunks take to ingest, how long a Store of them takes to open and how long the judged
 * book queries take to recall, at every scale of a declared data-size ladder (Issue #168, Book 6/8; evaluation only,
 * exploratory, informational timings, no threshold or gate on them).
 *
 * <p>Per scale, in a fixed order and each on its own Store directory under the scratch directory:
 * <ol>
 *   <li><b>parity baseline</b> (untimed): a dedicated Store is seeded and every query's top-K recorded on a new
 *   instance; every later query of the scale, in this JVM or a child JVM, must return exactly these hits;</li>
 *   <li><b>ingest</b>: per trial a new empty directory, {@code MonadaMemory.open} (timed as {@code create}) and
 *   {@code rememberAll(texts)} (encode + persist through the public API, timed as {@code rememberAll});</li>
 *   <li><b>open, in process</b>: repeated {@code MonadaMemory.open} of the last ingested Store, each followed by one
 *   timed first query on that new instance (the instance loads nothing eagerly, so that cost is not hidden);</li>
 *   <li><b>query, in process</b>: warm passes over every query on one instance, with a standalone query-encoding
 *   diagnostic (the same normalizer and encoder as the API, timed separately and never subtracted);</li>
 *   <li><b>fresh process</b>: sequential child JVMs ({@link BookLatencyChild}) open the same Store and run one pass;
 *   the first process and the later ones are reported apart. The OS page cache is never dropped, so no condition
 *   here is an OS cold-cache measurement;</li>
 *   <li><b>parity after profiling</b> (untimed): the baseline Store is reopened and queried again.</li>
 * </ol>
 * Chunking, verification, parity passes, directory setup and cleanup and report writing are outside every timing.
 * Nothing here changes ranking: queries use {@link MonadaMemoryOptions#defaults()} on feedback-free Stores.
 */
final class BookLatencyBenchmark {
    static final String API = "MonadaMemory.resonate(query).topK(" + BookLatencyChild.K + ").threshold(-Infinity)";
    /** Mismatch details kept per run; the count is always complete. */
    private static final int MAX_MISMATCH_DETAILS = 20;
    private static final long CHILD_TIMEOUT_MINUTES = 5;
    private static final long CHILD_KILL_WAIT_SECONDS = 30;

    private BookLatencyBenchmark() {
    }

    /** Repetition counts; warmup samples are taken exactly like measured ones and then discarded. */
    record Config(List<BookLatencyScale> ladder, int ingestWarmups, int ingestTrials, int openWarmups,
                  int openTrials, int queryWarmupPasses, int queryPasses, int processes) {
        Config {
            ladder = List.copyOf(ladder);
            if (ladder.isEmpty()) {
                throw new IllegalArgumentException("the ladder has no scale");
            }
            requireAtLeast("ingestWarmups", ingestWarmups, 0);
            requireAtLeast("ingestTrials", ingestTrials, 1);
            requireAtLeast("openWarmups", openWarmups, 0);
            requireAtLeast("openTrials", openTrials, 1);
            requireAtLeast("queryWarmupPasses", queryWarmupPasses, 0);
            requireAtLeast("queryPasses", queryPasses, 1);
            requireAtLeast("processes", processes, 0);
        }

        static Config defaults() {
            return new Config(BookLatencyScale.parseLadder(BookLatencyScale.DEFAULT_LADDER), 1, 5, 2, 20, 2, 10, 5);
        }

        private static void requireAtLeast(String name, int value, int min) {
            if (value < min) {
                throw new IllegalArgumentException(name + " must be >= " + min + ", got " + value);
            }
        }
    }

    /** Execution conditions, named so a fresh JVM is never mistaken for an OS cold cache. */
    enum Condition {
        /** In the benchmark JVM, on a new empty directory. */
        IN_PROCESS,
        /** In the benchmark JVM after earlier opens of the same Store: warm JIT, warm page cache. */
        IN_PROCESS_REPEATED,
        /** The first query on a Store instance that was just opened in the benchmark JVM. */
        IN_PROCESS_NEW_INSTANCE,
        /** Queries on one instance after warmup passes in the benchmark JVM. */
        IN_PROCESS_WARM,
        /** Query encoding alone, outside the API call (diagnostic; not subtracted from anything). */
        STANDALONE_DIAGNOSTIC,
        /** The first child JVM of the scale: new JVM, page cache warm from the parent's writes and reads. */
        FRESH_PROCESS_FIRST,
        /** Later child JVMs of the scale: new JVM each, page cache warm from earlier processes. */
        FRESH_PROCESS_REPEATED
    }

    /** One latency series: what was timed, under which condition, the raw nanos in measurement order. */
    record Series(String phase, Condition condition, String timed, List<Long> nanos) {
        Series {
            nanos = List.copyOf(nanos);
        }

        BookLatencyStats stats() {
            return BookLatencyStats.of(nanos);
        }

        String key() {
            return phase + "." + condition;
        }
    }

    /**
     * One measured scale.
     *
     * @param resultsDigest  {@link BookLatencyChild#digest} of the parity baseline (deterministic)
     * @param storeBytes     on-disk bytes of the last ingested Store (its atom log holds creation times)
     * @param freshProcess   {@code RUN} or why the fresh-process condition was not run
     */
    record ScaleResult(BookLatencyScale.Selected selected, String resultsDigest, int dimensions, long storeBytes,
                       List<Series> series, String freshProcess) {
        ScaleResult {
            series = List.copyOf(series);
        }
    }

    /** Result-id parity: every compared top-K list, and the ones that differed from the baseline. */
    record Parity(int checks, int mismatches, List<String> details) {
        Parity {
            details = List.copyOf(details);
        }

        boolean passed() {
            return mismatches == 0;
        }
    }

    record Result(Map<String, String> inputs, List<String> queries, Config config, String childClasspathStatus,
                  Map<String, String> host, List<ScaleResult> scales, Parity parity) {
        Result {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            queries = List.copyOf(queries);
            host = Collections.unmodifiableMap(new LinkedHashMap<>(host));
            scales = List.copyOf(scales);
        }
    }

    /**
     * Runs the benchmark over {@code chunks} (texts in ordinal order) with {@code queries} (fixed order) in the
     * existing, empty directory {@code scratch}. {@code childClasspath} null skips the fresh-process condition.
     */
    static Result run(List<String> chunks, List<String> queries, Map<String, String> inputs, Config config,
                      Path scratch, String childClasspath) throws IOException {
        if (chunks.isEmpty() || queries.isEmpty()) {
            throw new IllegalArgumentException("the benchmark needs chunks and queries");
        }
        ParityLog parity = new ParityLog();
        List<ScaleResult> scales = new ArrayList<>();
        for (BookLatencyScale scale : config.ladder()) {
            scales.add(runScale(scale.select(chunks), queries, config, scratch.resolve("scale-" + scale.id()),
                    childClasspath, parity));
        }
        String childStatus = childClasspath == null ? "NOT_RUN (no child classpath)"
                : config.processes() == 0 ? "NOT_RUN (processes=0)"
                : "RUN (" + config.processes() + " sequential child JVMs per scale)";
        return new Result(inputs, queries, config, childStatus, BookLatencyHost.describe(scratch), scales,
                parity.result());
    }

    private static ScaleResult runScale(BookLatencyScale.Selected selected, List<String> queries, Config config,
                                        Path dir, String childClasspath, ParityLog parity) throws IOException {
        Files.createDirectory(dir);
        String id = selected.scale().id();
        Set<String> atoms = selected.atomIds();

        // 1. Parity baseline, untimed, on its own Store.
        Path parityStore = dir.resolve("parity");
        MonadaMemory.open(parityStore).rememberAll(selected.texts());
        List<List<Hit>> baseline = new ArrayList<>();
        MonadaMemory baselineInstance = MonadaMemory.open(parityStore);
        for (int q = 0; q < queries.size(); q++) {
            List<Hit> hits = BookLatencyChild.hits(BookLatencyChild.query(baselineInstance, queries.get(q)));
            BookRecallEvaluator.validateHits(id + " q" + q, hits, atoms);
            baseline.add(hits);
        }
        String baselineDigest = BookLatencyChild.digest(baseline);

        // 2. Ingest: encode + persist through the public API, one new directory per trial.
        List<Long> create = new ArrayList<>();
        List<Long> remember = new ArrayList<>();
        Path store = null;
        for (int t = 0; t < config.ingestWarmups() + config.ingestTrials(); t++) {
            Path trial = dir.resolve("ingest-" + t);
            long start = System.nanoTime();
            MonadaMemory memory = MonadaMemory.open(trial);
            long opened = System.nanoTime();
            List<?> stored = memory.rememberAll(selected.texts());
            long done = System.nanoTime();
            if (stored.size() != selected.texts().size()) {
                throw new IllegalStateException(id + ": rememberAll returned " + stored.size() + " atoms for "
                        + selected.texts().size() + " texts");
            }
            if (t >= config.ingestWarmups()) {
                create.add(opened - start);
                remember.add(done - opened);
            }
            if (store != null) {
                deleteTree(store); // untimed; only the last trial's Store is kept for the open and query phases
            }
            store = trial;
        }
        long storeBytes = treeBytes(store);
        Manifest manifest = new StorageIntegrityAuditor().audit(store).manifest();
        if (manifest == null) {
            throw new IllegalStateException(id + ": the ingested Store has no readable manifest");
        }

        // 3. Open in process, each new instance followed by one timed first query (rotating through the queries).
        List<Long> open = new ArrayList<>();
        List<Long> firstQuery = new ArrayList<>();
        for (int t = 0; t < config.openWarmups() + config.openTrials(); t++) {
            int q = t % queries.size();
            long start = System.nanoTime();
            MonadaMemory memory = MonadaMemory.open(store);
            long opened = System.nanoTime();
            List<ResonanceResult> results = BookLatencyChild.query(memory, queries.get(q));
            long done = System.nanoTime();
            parity.compare(id + " open trial " + t + " q" + q, baseline.get(q), BookLatencyChild.hits(results));
            if (t >= config.openWarmups()) {
                open.add(opened - start);
                firstQuery.add(done - opened);
            }
        }

        // 4. Warm query passes on one instance, plus the standalone encoding diagnostic.
        MonadaMemoryOptions options = MonadaMemoryOptions.defaults();
        TextNormalizer normalizer = options.textNormalizer();
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(manifest.dimensions());
        MonadaMemory memory = MonadaMemory.open(store);
        List<Long> query = new ArrayList<>();
        List<Long> encode = new ArrayList<>();
        for (int pass = 0; pass < config.queryWarmupPasses() + config.queryPasses(); pass++) {
            boolean measured = pass >= config.queryWarmupPasses();
            for (int q = 0; q < queries.size(); q++) {
                long start = System.nanoTime();
                var normalized = normalizer.normalize(queries.get(q));
                if (!normalized.enrichedText().isBlank()) {
                    encoder.encode(normalized.toWeightedText(options.expansionOptions()));
                }
                long encoded = System.nanoTime();
                List<ResonanceResult> results = BookLatencyChild.query(memory, queries.get(q));
                long done = System.nanoTime();
                parity.compare(id + " pass " + pass + " q" + q, baseline.get(q), BookLatencyChild.hits(results));
                if (measured) {
                    encode.add(encoded - start);
                    query.add(done - encoded);
                }
            }
        }

        List<Series> series = new ArrayList<>(List.of(
                new Series("ingest.create", Condition.IN_PROCESS, "MonadaMemory.open(new empty dir)", create),
                new Series("ingest.rememberAll", Condition.IN_PROCESS,
                        "rememberAll(texts): encode + persist via the public API", remember),
                new Series("open", Condition.IN_PROCESS_REPEATED, "MonadaMemory.open(store)", open),
                new Series("firstQuery", Condition.IN_PROCESS_NEW_INSTANCE, "first query after open, " + API,
                        firstQuery),
                new Series("query", Condition.IN_PROCESS_WARM, API, query),
                new Series("queryEncode", Condition.STANDALONE_DIAGNOSTIC,
                        "normalize + encode only, same pipeline as the API, not subtracted", encode)));

        // 5. Fresh processes, sequential, on the same Store.
        String freshProcess;
        if (childClasspath == null || config.processes() == 0) {
            freshProcess = childClasspath == null ? "NOT_RUN (no child classpath)" : "NOT_RUN (processes=0)";
        } else {
            series.addAll(freshProcesses(id, store, dir, queries, baselineDigest, config.processes(),
                    childClasspath, parity));
            freshProcess = "RUN";
        }

        // 6. Parity after profiling, untimed, on the baseline Store.
        MonadaMemory after = MonadaMemory.open(parityStore);
        for (int q = 0; q < queries.size(); q++) {
            parity.compare(id + " after profiling q" + q, baseline.get(q),
                    BookLatencyChild.hits(BookLatencyChild.query(after, queries.get(q))));
        }
        return new ScaleResult(selected, baselineDigest, manifest.dimensions(), storeBytes, series, freshProcess);
    }

    private static List<Series> freshProcesses(String id, Path store, Path dir, List<String> queries,
                                               String baselineDigest, int processes, String classpath,
                                               ParityLog parity) throws IOException {
        Path queryFile = dir.resolve("queries.txt");
        Files.write(queryFile, queries, StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Map<Condition, List<Long>> open = new LinkedHashMap<>();
        Map<Condition, List<Long>> first = new LinkedHashMap<>();
        Map<Condition, List<Long>> rest = new LinkedHashMap<>();
        Map<Condition, List<Long>> wall = new LinkedHashMap<>();
        for (int p = 0; p < processes; p++) {
            Condition condition = p == 0 ? Condition.FRESH_PROCESS_FIRST : Condition.FRESH_PROCESS_REPEATED;
            Path outFile = dir.resolve("child-" + p + ".out");
            Path errFile = dir.resolve("child-" + p + ".err");
            long start = System.nanoTime();
            Process process = new ProcessBuilder(java, "-cp", classpath, BookLatencyChild.class.getName(),
                    "--store", store.toString(), "--queries", queryFile.toString())
                    .redirectOutput(outFile.toFile()).redirectError(errFile.toFile()).start();
            boolean exited;
            try {
                exited = process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                terminate(process);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for child JVM " + p, e);
            }
            long elapsed = System.nanoTime() - start;
            // A child that cannot run (timeout, unreadable Store, I/O error) is an environment failure, not an
            // invalid result: it surfaces as an IOException, so the command exits 2, never 1.
            if (!exited) {
                terminate(process);
                throw new IOException(id + ": child JVM " + p + " timed out after " + CHILD_TIMEOUT_MINUTES
                        + " minutes");
            }
            if (process.exitValue() != 0) {
                throw new IOException(id + ": child JVM " + p + " exited " + process.exitValue() + ": "
                        + Files.readString(errFile, StandardCharsets.UTF_8).strip());
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : Files.readAllLines(outFile, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    fields.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            List<Long> nanos = Stream.of(fields.getOrDefault("queryNanos", "").split(","))
                    .filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
            if (!fields.containsKey("openNanos") || nanos.size() != queries.size()) {
                throw new IllegalStateException(id + ": child JVM " + p + " printed an incomplete report: " + fields);
            }
            parity.compareDigest(id + " child JVM " + p, baselineDigest, fields.get("resultsDigest"));
            open.computeIfAbsent(condition, c -> new ArrayList<>()).add(Long.valueOf(fields.get("openNanos")));
            first.computeIfAbsent(condition, c -> new ArrayList<>()).add(nanos.getFirst());
            rest.computeIfAbsent(condition, c -> new ArrayList<>()).addAll(nanos.subList(1, nanos.size()));
            wall.computeIfAbsent(condition, c -> new ArrayList<>()).add(elapsed);
        }
        List<Series> series = new ArrayList<>();
        for (Condition condition : open.keySet()) {
            series.add(new Series("open", condition, "MonadaMemory.open(store) in the child, JVM startup excluded",
                    open.get(condition)));
            series.add(new Series("firstQuery", condition, "first query of the child's pass, " + API,
                    first.get(condition)));
            series.add(new Series("query", condition, "remaining queries of the child's single pass, " + API,
                    rest.get(condition)));
            series.add(new Series("processWall", condition,
                    "whole child process seen by the parent: JVM startup, open, one pass, output", wall.get(condition)));
        }
        return series;
    }

    /**
     * Kills {@code process} and waits for it to end, so it cannot race the scratch cleanup with the Store open or
     * outlive the command; forced destruction is asynchronous. A pending interrupt is kept for the caller.
     */
    static void terminate(Process process) {
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(CHILD_KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Collects parity comparisons across the whole run. */
    private static final class ParityLog {
        private int checks;
        private int mismatches;
        private final List<String> details = new ArrayList<>();

        void compare(String where, List<Hit> expected, List<Hit> actual) {
            record(where, expected.equals(actual), () -> "expected " + expected + " got " + actual);
        }

        void compareDigest(String where, String expected, String actual) {
            record(where, expected.equals(actual), () -> "expected digest " + expected + " got " + actual);
        }

        private void record(String where, boolean same, Supplier<String> detail) {
            checks++;
            if (!same) {
                mismatches++;
                if (details.size() < MAX_MISMATCH_DETAILS) {
                    details.add(where + ": " + detail.get());
                }
            }
        }

        Parity result() {
            return new Parity(checks, mismatches, details);
        }
    }

    /** Sum of regular file sizes below {@code root}. */
    static long treeBytes(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            long total = 0;
            for (Path path : walk.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(path);
                }
            }
            return total;
        }
    }

    /** Deletes {@code root} and everything below it, deepest first; symbolic links are removed, never followed. */
    static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
