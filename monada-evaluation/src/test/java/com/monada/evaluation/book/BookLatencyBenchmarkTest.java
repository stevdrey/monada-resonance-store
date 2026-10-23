package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.MonadaMemory;
import com.monada.evaluation.book.BookLatencyBenchmark.Condition;
import com.monada.evaluation.book.BookLatencyBenchmark.Config;
import com.monada.evaluation.book.BookLatencyBenchmark.Result;
import com.monada.evaluation.book.BookLatencyBenchmark.ScaleResult;
import com.monada.evaluation.book.BookLatencyBenchmark.Series;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.storage.audit.StorageIntegrityAuditor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The book latency benchmark on the original 10-chunk fixture (Issue #168): scenario isolation, result-id parity,
 * sample denominators, deterministic metadata, the fresh-process condition and the command-line contract. Timings
 * are never asserted.
 */
class BookLatencyBenchmarkTest {
    private static final List<String> QUERIES = BookRecallFixture.QUERIES.stream().map(r -> r.split("\\|")[2])
            .toList();
    private static final Map<String, String> INPUTS = Map.of("corpus", "book-chunking-fixture");

    @TempDir
    Path dir;

    private static String childClasspath() {
        String classpath = System.getProperty("forge.runtime.classpath");
        return classpath == null || classpath.isBlank() ? null : classpath;
    }

    private Path copyFixture(Path target, String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(BookRecallFixture.FIXTURE + name)) {
            Path file = target.resolve(name);
            Files.write(file, in.readAllBytes());
            return file;
        }
    }

    private List<String> fixtureChunks() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        BookChunker.Result result = BookChunker.chunk(VerifiedBookSource.load(copyFixture(inputs,
                "manifest.properties"), copyFixture(inputs, "body.txt")), BookRecallFixture.POLICY);
        return result.chunks().stream().map(BookChunk::text).toList();
    }

    private static Config smallConfig(int processes) {
        return new Config(BookLatencyScale.parseLadder("50%,100%,x2"), 1, 2, 1, 3, 1, 2, processes);
    }

    private Result run(String name, Config config, String classpath) throws Exception {
        Path scratch = Files.createDirectory(dir.resolve(name));
        return BookLatencyBenchmark.run(fixtureChunks(), QUERIES, INPUTS, config, scratch, classpath);
    }

    private static Series series(ScaleResult scale, String phase, Condition condition) {
        return scale.series().stream().filter(s -> s.phase().equals(phase) && s.condition() == condition)
                .findFirst().orElseThrow(() -> new AssertionError(phase + " " + condition));
    }

    @Test
    void everyScaleIsMeasuredOnItsOwnStoresWithParityAndHonestDenominators() throws Exception {
        String classpath = childClasspath();
        int processes = classpath == null ? 0 : 2;
        Config config = smallConfig(processes);
        Result result = run("scratch", config, classpath);
        int q = QUERIES.size();

        assertTrue(result.parity().passed(), result.parity().details().toString());
        int directPerScale = config.openWarmups() + config.openTrials()
                + (config.queryWarmupPasses() + config.queryPasses()) * q + q;
        assertEquals(3 * directPerScale, result.parity().direct(), "every timed and untimed pass is compared");
        assertEquals(3 * processes, result.parity().digests());
        assertEquals(3 * processes * q, result.parity().digestQueries(), "a child digest covers its whole pass");
        assertEquals(3 * (directPerScale + processes * q), result.parity().checks(),
                "the denominator counts query result lists, digests included");

        assertEquals(List.of("p50", "p100", "x2"),
                result.scales().stream().map(s -> s.selected().scale().id()).toList());
        for (ScaleResult scale : result.scales()) {
            assertEquals(config.ingestTrials(), series(scale, "ingest.rememberAll", Condition.IN_PROCESS).nanos().size(),
                    "warmup trials are discarded");
            assertEquals(config.openTrials(), series(scale, "open", Condition.IN_PROCESS_REPEATED).nanos().size());
            assertEquals(config.openTrials(),
                    series(scale, "firstQuery", Condition.IN_PROCESS_NEW_INSTANCE).nanos().size());
            assertEquals(config.queryPasses() * q, series(scale, "query", Condition.IN_PROCESS_WARM).nanos().size());
            assertEquals(config.queryPasses() * q,
                    series(scale, "queryEncode", Condition.STANDALONE_DIAGNOSTIC).nanos().size());
            if (processes > 0) {
                assertEquals("RUN", scale.freshProcess());
                assertEquals(1, series(scale, "open", Condition.FRESH_PROCESS_FIRST).nanos().size());
                assertEquals(q - 1, series(scale, "query", Condition.FRESH_PROCESS_FIRST).nanos().size(),
                        "the first query of a child is its own series");
                assertEquals(processes - 1, series(scale, "open", Condition.FRESH_PROCESS_REPEATED).nanos().size());
                assertEquals((processes - 1) * (q - 1),
                        series(scale, "query", Condition.FRESH_PROCESS_REPEATED).nanos().size());
            } else {
                assertTrue(scale.freshProcess().startsWith("NOT_RUN"));
            }
            assertTrue(scale.storeBytes() > 0);
        }

        // Scenario isolation: one directory per scale, the warmup Store removed, each Store holds only its scale.
        for (ScaleResult scale : result.scales()) {
            Path scaleDir = dir.resolve("scratch").resolve("scale-" + scale.selected().scale().id());
            Path last = scaleDir.resolve("ingest-" + (config.ingestWarmups() + config.ingestTrials() - 1));
            assertFalse(Files.exists(scaleDir.resolve("ingest-0")), "earlier trial Stores are deleted");
            for (Path store : List.of(last, scaleDir.resolve("parity"))) {
                var manifest = new StorageIntegrityAuditor().audit(store).manifest();
                assertEquals(scale.selected().uniqueAtoms(), BookStoreInventory.read(store, manifest).uniqueAtoms(),
                        store.toString());
            }
        }
        ScaleResult half = result.scales().get(0);
        ScaleResult full = result.scales().get(1);
        ScaleResult doubled = result.scales().get(2);
        assertEquals(5, half.selected().texts().size());
        assertEquals(10, full.selected().texts().size());
        assertEquals(9, full.selected().uniqueAtoms(), "the fixture repeats one chunk text");
        assertEquals(18, doubled.selected().uniqueAtoms());
        assertNotEquals(full.resultsDigest(), doubled.resultsDigest(), "replicas change what is recalled");

        String report = BookLatencyReport.render(result);
        assertEquals(BookLatencyReport.renderDeterministic(result), BookLatencyReport.deterministicPart(report));
        assertTrue(report.contains("parity.status=PASS"));
        assertTrue(report.contains("scale.x2.kind=DERIVED (replica-tagged, not real-book shape)"));
        assertTrue(report.contains("scale.p100.query.IN_PROCESS_WARM.p99Micros=n/a (n=12 < 100)"), report);
        assertTrue(report.contains("host.page_cache=WARM_OR_UNKNOWN"), report);
        assertTrue(report.contains(processes > 0 ? "host.child_jvm=max heap " : "host.child_jvm=NOT_RUN"),
                "fresh-process timings carry the child's own JVM configuration");
        String summary = BookLatencyReport.summary(result);
        assertTrue(summary.contains("| x2 | DERIVED | 20 |"), summary);
        assertTrue(summary.contains("Parity: PASS"), summary);
    }

    @Test
    void deterministicMetadataIsIdenticalAcrossRunsAndHoldsNoTimings() throws Exception {
        Result first = run("first", smallConfig(0), null);
        Result second = run("second", smallConfig(0), null);
        String deterministic = BookLatencyReport.renderDeterministic(first);
        assertEquals(deterministic, BookLatencyReport.renderDeterministic(second));
        assertFalse(deterministic.contains("Micros"), "timings never enter the deterministic part");
        assertFalse(deterministic.contains("storeBytes"), "the atom log holds creation times");
        assertTrue(deterministic.contains("freshProcess=NOT_RUN (no child classpath)"));
        assertEquals(BookLatencyReport.keys(BookLatencyReport.render(first)),
                BookLatencyReport.keys(BookLatencyReport.render(second)), "the same keys in the same order");
    }

    @Test
    void aChildJvmThatCannotRunIsAnIoFailureNotAnInvalidResult() throws Exception {
        Config config = new Config(BookLatencyScale.parseLadder("100%"), 0, 1, 0, 1, 0, 1, 1);
        Path scratch = Files.createDirectory(dir.resolve("broken-child"));
        IOException e = assertThrows(IOException.class,
                () -> BookLatencyBenchmark.run(fixtureChunks(), QUERIES, INPUTS, config, scratch,
                        dir.resolve("no-such-classpath").toString()));
        assertTrue(e.getMessage().contains("child JVM 0 exited"), e.getMessage());
    }

    @Test
    void aChildThatCannotWriteItsReportExitsTwo() throws Exception {
        Path store = dir.resolve("child-store");
        MonadaMemory.open(store).rememberAll(List.of("The tide turns twice a day."));
        Path queries = Files.write(dir.resolve("queries.txt"), List.of("tide"), StandardCharsets.UTF_8);
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("no space left on device");
            }
        }, true, StandardCharsets.UTF_8);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookLatencyChild.run(new String[] {"--store", store.toString(), "--queries", queries.toString()},
                broken, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(2, exit, "a lost report is an I/O failure, never success");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("could not write its report"));

        ByteArrayOutputStream ok = new ByteArrayOutputStream();
        assertEquals(0, BookLatencyChild.run(new String[] {"--store", store.toString(), "--queries",
                queries.toString()}, new PrintStream(ok, true, StandardCharsets.UTF_8), new PrintStream(err)));
        List<String> keys = ok.toString(StandardCharsets.UTF_8).lines().map(l -> l.substring(0, l.indexOf('=')))
                .toList();
        assertTrue(keys.containsAll(BookLatencyBenchmark.CHILD_FIELDS), "the parent requires every field: " + keys);
    }

    @Test
    void terminateWaitsForTheKilledProcess() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path sleeper = Files.writeString(dir.resolve("Sleep.java"),
                "class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }");
        Process process = new ProcessBuilder(java, sleeper.toString()).start();
        BookLatencyBenchmark.terminate(process);
        assertFalse(process.isAlive(), "the child has ended before terminate returns");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void resultDigestSeesIdsOrderAndScores() {
        List<List<Hit>> base = List.of(List.of(new Hit("a", 0.5), new Hit("b", 0.25)), List.of());
        String digest = BookLatencyChild.digest(base);
        assertEquals(digest, BookLatencyChild.digest(List.of(List.of(new Hit("a", 0.5), new Hit("b", 0.25)),
                List.of())));
        assertNotEquals(digest, BookLatencyChild.digest(List.of(List.of(new Hit("b", 0.25), new Hit("a", 0.5)),
                List.of())));
        assertNotEquals(digest, BookLatencyChild.digest(List.of(List.of(new Hit("a", 0.5), new Hit("b", 0.2500001)),
                List.of())));
        assertNotEquals(digest, BookLatencyChild.digest(List.of(List.of(new Hit("a", 0.5)),
                List.of(new Hit("b", 0.25)))), "hits cannot move between queries");
    }

    // ---- command line ----

    private record Run(int exit, String out, String err) {
    }

    private static Run cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BookLatencyMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, cli().exit());
        assertEquals(2, cli("--input", "body.txt").exit());
        assertEquals(2, cli("--bogus", "x").exit());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--ladder", "x1").exit());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--queryPasses", "0").exit());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--processes", "many").exit());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--keep", "maybe").exit());
        Run overflow = cli("--input", "a", "--scratch", "b", "--ingestWarmups", "2147483647", "--ingestTrials", "1");
        assertEquals(2, overflow.exit(), "warmup + trial totals must never wrap");
        assertTrue(overflow.err().contains("ingestWarmups must be in 0..100000"), overflow.err());
        assertEquals(2, cli("--input", "a", "--scratch", "b", "--queryPasses", "100001").exit());
    }

    @Test
    void unsafeScratchIsRefusedBeforeAnything() throws Exception {
        Path existing = Files.createDirectory(dir.resolve("existing"));
        Run run = cli("--input", "missing.txt", "--scratch", existing.toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("must not exist"), run.err());

        Path repo = Files.createDirectories(dir.resolve("repo").resolve(".git")).getParent();
        run = cli("--input", "missing.txt", "--scratch", repo.resolve("scratch").toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("outside a Git work tree"), run.err());
        assertFalse(Files.exists(repo.resolve("scratch")));

        Path same = dir.resolve("same.txt");
        run = cli("--input", "missing.txt", "--scratch", dir.resolve("s").toString(), "--out", same.toString(),
                "--summary", dir.resolve(".").resolve("same.txt").toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("must name different files"), run.err());

        Path real = Files.createDirectory(dir.resolve("real"));
        Path alias = Files.createSymbolicLink(dir.resolve("alias"), real);
        run = cli("--input", "missing.txt", "--scratch", dir.resolve("s").toString(), "--out",
                real.resolve("report").toString(), "--summary", alias.resolve("report").toString());
        assertEquals(2, run.exit(), "a symlinked parent must not hide that both outputs are one directory entry");
        assertTrue(run.err().contains("must name different files"), run.err());

        Path scratch = dir.resolve("scratch");
        run = cli("--input", "missing.txt", "--scratch", scratch.toString(), "--out",
                scratch.resolve("report.txt").toString());
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("inside --scratch"), run.err());
    }

    @Test
    void outputsMayNotOverwriteTheInputs() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path manifest = copyFixture(inputs, "manifest.properties");
        Path body = copyFixture(inputs, "body.txt");
        byte[] bodyBytes = Files.readAllBytes(body);
        Path scratch = dir.resolve("scratch");
        for (String[] args : List.of(
                new String[] {"--out", body.toString()},
                new String[] {"--summary", inputs.resolve(".").resolve("body.txt").toString()},
                new String[] {"--out", manifest.toString()})) {
            Run run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch",
                    scratch.toString(), args[0], args[1]);
            assertEquals(2, run.exit(), run.err());
            assertTrue(run.err().contains("would overwrite the benchmark input"), run.err());
        }
        Path link = Files.createSymbolicLink(dir.resolve("alias.txt"), body);
        Run run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", scratch.toString(),
                "--out", link.toString());
        assertEquals(2, run.exit(), run.err());
        assertFalse(Files.exists(scratch));
        assertTrue(Arrays.equals(bodyBytes, Files.readAllBytes(body)), "the body is untouched");
    }

    @Test
    void aForeignCorpusExitsOneWithoutCreatingScratch() throws Exception {
        Path inputs = Files.createDirectories(dir.resolve("inputs"));
        Path manifest = copyFixture(inputs, "manifest.properties");
        Path body = copyFixture(inputs, "body.txt");
        Path scratch = dir.resolve("scratch");
        Run run = cli("--input", body.toString(), "--manifest", manifest.toString(), "--scratch", scratch.toString());
        assertEquals(1, run.exit(), run.err());
        assertTrue(run.err().contains("foreign corpus input"), run.err());
        assertFalse(Files.exists(scratch), "nothing is created before the inputs are bound");
        try (Stream<Path> children = Files.list(dir)) {
            assertEquals(List.of("inputs"), children.map(p -> p.getFileName().toString()).toList());
        }
    }
}
