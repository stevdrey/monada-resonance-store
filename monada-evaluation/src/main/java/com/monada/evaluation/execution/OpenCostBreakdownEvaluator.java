package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExecutionMemoryConfig;
import com.monada.api.execution.HistoryCursor;
import com.monada.api.execution.HistoryEntry;
import com.monada.api.execution.HistoryPage;
import com.monada.api.execution.ProjectionStatus;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.ScaleTimingStatistics;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkEvaluator.LedgerCorpus;
import com.monada.evaluation.execution.OpenCostBreakdownReport.SizeResult;
import com.monada.evaluation.execution.OpenCostBreakdownReport.SizeTiming;
import com.monada.storage.execution.ExecutionLedger;
import com.monada.storage.execution.ExecutionLedgerReader;
import com.monada.storage.execution.LedgerRecord;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Splits the cost of {@link ExecutionMemory#open} and {@link ExecutionMemory#openReadOnly} into ledger scan, history
 * index build and projection load/verification as the ledger grows (issue #156), still without changing any format.
 *
 * <p>The history index and the projection are internal to {@code monada-api}, so the split is taken by difference
 * of public opens, one fresh instance per round:
 * <ul>
 *   <li>ledger scan: {@link ExecutionLedger#open} (or {@link ExecutionLedgerReader#open}) plus {@code replay()},
 *       which is exactly what the facade does first;</li>
 *   <li>history index: the facade open of a copy of the root without any projection entry (state {@code MISSING},
 *       the projection load stops after the layout lookup) minus the ledger scan;</li>
 *   <li>projection: the facade open of the full root (state {@code CURRENT}) minus the copy.</li>
 * </ul>
 *
 * <p>As in #126, three hard checks (both roots open in the expected projection state over the same ledger bytes,
 * replay fidelity and exact loads on both roots, and no open writes anything) are deterministic and decide the exit
 * code. Every timing, share, heap proxy and growth exponent is informational only and never gates anything.
 */
public final class OpenCostBreakdownEvaluator {
    static final List<Integer> DEFAULT_SIZES = List.of(1000, 5000, 20000, 50000);
    static final int DEFAULT_WARMUP = 2;
    static final int DEFAULT_ITERATIONS = 5;

    static final String LEDGER_OPEN = "ledger open (ExecutionLedger.open)";
    static final String LEDGER_REPLAY = "ledger replay (ExecutionLedger.replay)";
    static final String LEDGER_SCAN = "ledger scan (ExecutionLedger.open + replay)";
    static final String READER_OPEN = "reader open (ExecutionLedgerReader.open)";
    static final String READER_REPLAY = "reader replay (ExecutionLedgerReader.replay)";
    static final String READER_SCAN = "reader scan (ExecutionLedgerReader.open + replay)";
    static final String WRITABLE_NO_PROJECTION = "writable open, no projection";
    static final String READ_ONLY_NO_PROJECTION = "read-only open, no projection";
    static final String WRITABLE_FULL = "writable open (ExecutionMemory.open)";
    static final String READ_ONLY_FULL = "read-only open (ExecutionMemory.openReadOnly)";

    /** What one breakdown measures; every field is printed in the deterministic report. */
    public record Config(long seed, List<Integer> sizes, int warmup, int iterations) {
        public Config {
            List<Integer> copy = List.copyOf(sizes);
            if (copy.isEmpty() || new HashSet<>(copy).size() != copy.size()) {
                throw new IllegalArgumentException("sizes must be non-empty and distinct, got: " + copy);
            }
            for (int n : copy) {
                if (n < LedgerGrowthBenchmarkEvaluator.MIN_EVENTS || n > LedgerGrowthBenchmarkEvaluator.MAX_EVENTS) {
                    throw new IllegalArgumentException("every size must be in "
                            + LedgerGrowthBenchmarkEvaluator.MIN_EVENTS + ".."
                            + LedgerGrowthBenchmarkEvaluator.MAX_EVENTS + " events, got: " + n);
                }
            }
            sizes = copy.stream().sorted().toList();
            if (warmup < 0) {
                throw new IllegalArgumentException("warmup must be >= 0, got: " + warmup);
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations must be >= 1, got: " + iterations);
            }
        }

        public static Config defaults() {
            return new Config(ExecutionScaleCorpusGenerator.DEFAULT_SEED, DEFAULT_SIZES, DEFAULT_WARMUP,
                    DEFAULT_ITERATIONS);
        }
    }

    private OpenCostBreakdownEvaluator() {
    }

    /** Runs the breakdown with stores below {@code workDir}; the caller owns {@code workDir} and removes it. */
    public static OpenCostBreakdownReport evaluate(Config config, Path workDir) {
        Objects.requireNonNull(config, "config");
        List<SizeResult> results = new ArrayList<>();
        List<SizeTiming> timings = new ArrayList<>();
        Violations v = new Violations();
        for (int n : config.sizes()) {
            SizeRun run = new SizeRun(config, LedgerGrowthBenchmarkEvaluator.corpus(config.seed(), n), workDir, v);
            run.execute();
            results.add(run.result());
            timings.add(run.timing());
        }
        List<ForgeGate> gates = List.of(
                ForgeGate.of(OpenCostBreakdownReport.PROJECTION_STATES,
                        "the full root opens CURRENT and the projection-less copy MISSING, writable and read-only, "
                                + "in every round; the copy holds byte-identical ledger segments",
                        v.states, List.of("opens checked: " + v.opens)),
                ForgeGate.of(OpenCostBreakdownReport.REPLAY_FIDELITY,
                        "ExecutionLedger, ExecutionLedgerReader and every facade open of both roots replay exactly "
                                + "the recorded events in order, healthy and without a torn tail; sampled events "
                                + "load identically on both roots; opening the copy creates no projection",
                        v.fidelity, List.of("replays compared: " + v.replays + "; exact loads: " + v.loads)),
                ForgeGate.of(OpenCostBreakdownReport.OPENS_DO_NOT_WRITE,
                        "after every round the ledger segment bytes and digest and the projection generation "
                                + "fingerprint of the full root are unchanged",
                        v.writes, List.of("sizes checked: " + config.sizes().size())));
        Map<String, String> environment = new LinkedHashMap<>(ExecutionScaleSweepReport.currentEnvironment());
        environment.put("work dir file store", LedgerGrowthBenchmarkEvaluator.fileStore(workDir));
        return new OpenCostBreakdownReport(config, results, gates, timings, environment);
    }

    /**
     * Copies {@code root} into {@code copy} without the scope's projection: every entry directly below the scope
     * directory whose name starts with {@code projection} (pointer, temporary pointer, generations) is skipped, and
     * so is the writer lock. The source is only read.
     */
    static void copyWithoutProjection(Path root, ScopeId scope, Path copy) {
        try {
            Path scopeDir = ProjectionLayout.of(root, scope).scopeDir();
            Path lock = root.resolve("write.lock");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (skipped(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(copy.resolve(root.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!skipped(file) && !file.equals(lock)) {
                        Files.copy(file, copy.resolve(root.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private boolean skipped(Path p) {
                    return scopeDir.equals(p.getParent()) && p.getFileName().toString().startsWith("projection");
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Violations collected across every size, one list per hard check. */
    private static final class Violations {
        final List<String> states = new ArrayList<>();
        final List<String> fidelity = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        int opens;
        int replays;
        int loads;
    }

    /** One size: a root recorded through the facade and its projection-less copy. */
    private static final class SizeRun {
        private final Config config;
        private final LedgerCorpus corpus;
        private final Path workDir;
        private final Violations v;
        private final String label;
        private final ScopeId scope;
        private final List<HistoryEntry> expected;
        private final Map<String, List<Long>> samples = new LinkedHashMap<>();
        private long segmentBytes;
        private String segmentDigest = "";
        private long projectionBytes;
        private String projectionGeneration = "";
        private long heapFull;
        private long heapNoProjection;

        SizeRun(Config config, LedgerCorpus corpus, Path workDir, Violations v) {
            this.config = config;
            this.corpus = corpus;
            this.workDir = workDir;
            this.v = v;
            this.label = "N=" + corpus.size();
            this.scope = corpus.scope();
            this.expected = corpus.expectedHistory();
            for (String name : List.of(LEDGER_OPEN, LEDGER_REPLAY, LEDGER_SCAN, READER_OPEN, READER_REPLAY,
                    READER_SCAN, WRITABLE_NO_PROJECTION, READ_ONLY_NO_PROJECTION, WRITABLE_FULL, READ_ONLY_FULL)) {
                samples.put(name, new ArrayList<>());
            }
        }

        void execute() {
            Path root = RecallStressEvaluator.fresh(workDir, "open-n" + corpus.size() + "-");
            record(root);
            segmentBytes = LedgerGrowthBenchmarkEvaluator.ledgerBytes(root, scope);
            segmentDigest = LedgerGrowthBenchmarkEvaluator.ledgerDigest(root, scope);
            String generation = generation(root);
            Optional<Path> current = currentDir(root);
            projectionGeneration = current.map(p -> p.getFileName().toString()).orElse("<none>");
            projectionBytes = current.map(OpenCostBreakdownEvaluator::bytes).orElse(-1L);

            Path copy = RecallStressEvaluator.fresh(workDir, "open-noproj-n" + corpus.size() + "-");
            copyWithoutProjection(root, scope, copy);
            if (!LedgerGrowthBenchmarkEvaluator.ledgerDigest(copy, scope).equals(segmentDigest)) {
                v.states.add(label + ": the projection-less copy holds a different ledger ("
                        + LedgerGrowthBenchmarkEvaluator.ledgerDigest(copy, scope) + " vs " + segmentDigest + ")");
            }
            if (currentDir(copy).isPresent() || generations(copy) != 0) {
                v.states.add(label + ": the copy still holds a projection");
            }

            int rounds = config.warmup() + config.iterations();
            for (int round = 0; round < rounds; round++) {
                boolean timed = round >= config.warmup();
                ledger(root, round, timed);
                reader(root, round, timed);
                facade(copy, round, timed, false);
                facade(root, round, timed, true);
                if (round == config.warmup()) {
                    heapFull = heapAfterReadOnlyOpen(root);
                    heapNoProjection = heapAfterReadOnlyOpen(copy);
                }
            }
            loads(root, copy);

            if (currentDir(copy).isPresent() || generations(copy) != 0) {
                v.fidelity.add(label + ": opening the projection-less copy created a projection");
            }
            if (LedgerGrowthBenchmarkEvaluator.ledgerBytes(root, scope) != segmentBytes
                    || !LedgerGrowthBenchmarkEvaluator.ledgerDigest(root, scope).equals(segmentDigest)) {
                v.writes.add(label + ": the ledger segment of the full root changed during the opens");
            }
            if (!generation(root).equals(generation)) {
                v.writes.add(label + ": the projection generation of the full root changed during the opens");
            }
        }

        private void record(Path root) {
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                for (int i = 0; i < corpus.size(); i++) {
                    RecordResult r = memory.record(corpus.events().get(i));
                    if (r.status() != RecordResult.Status.APPENDED || r.sequence() != i + 1) {
                        v.fidelity.add(label + " record: event " + i + " was " + r.status() + " at sequence "
                                + r.sequence() + " " + r.reason().orElse(""));
                    }
                }
            }
        }

        // --- timed opens: only the open (and the replay where named) is timed, never close or checks ----------

        private void ledger(Path root, int round, boolean timed) {
            long t = System.nanoTime();
            try (ExecutionLedger ledger = ExecutionLedger.open(root, scope)) {
                long open = System.nanoTime() - t;
                t = System.nanoTime();
                List<LedgerRecord> records = ledger.replay();
                long replay = System.nanoTime() - t;
                keep(timed, LEDGER_OPEN, open);
                keep(timed, LEDGER_REPLAY, replay);
                keep(timed, LEDGER_SCAN, open + replay);
                replay("ExecutionLedger round " + round, records);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void reader(Path root, int round, boolean timed) {
            try {
                long t = System.nanoTime();
                ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, scope);
                long open = System.nanoTime() - t;
                t = System.nanoTime();
                List<LedgerRecord> records = reader.replay();
                long replay = System.nanoTime() - t;
                keep(timed, READER_OPEN, open);
                keep(timed, READER_REPLAY, replay);
                keep(timed, READER_SCAN, open + replay);
                replay("ExecutionLedgerReader round " + round, records);
                if (!reader.isHealthy() || reader.hasTornTail()) {
                    v.fidelity.add(label + " ExecutionLedgerReader round " + round + ": healthy="
                            + reader.isHealthy() + " tornTail=" + reader.hasTornTail());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void facade(Path root, int round, boolean timed, boolean full) {
            ProjectionStatus.State want = full ? ProjectionStatus.State.CURRENT : ProjectionStatus.State.MISSING;
            String what = full ? "full root" : "projection-less copy";
            long t = System.nanoTime();
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                keep(timed, full ? WRITABLE_FULL : WRITABLE_NO_PROJECTION, System.nanoTime() - t);
                check("writable open of the " + what + " round " + round, memory, want);
            }
            t = System.nanoTime();
            try (ExecutionMemory memory = ExecutionMemory.openReadOnly(root, scope)) {
                keep(timed, full ? READ_ONLY_FULL : READ_ONLY_NO_PROJECTION, System.nanoTime() - t);
                check("read-only open of the " + what + " round " + round, memory, want);
            }
        }

        private void check(String where, ExecutionMemory memory, ProjectionStatus.State want) {
            v.opens++;
            ProjectionStatus.State state = memory.projectionStatus(scope).state();
            if (state != want) {
                v.states.add(label + " " + where + ": projection " + state + ", expected " + want);
            }
            if (memory.hasTornTail()) {
                v.fidelity.add(label + " " + where + ": torn tail");
            }
            List<HistoryEntry> entries = new ArrayList<>(expected.size());
            HistoryCursor cursor = null;
            do {
                HistoryPage page = memory.history(scope, cursor, ExecutionMemoryConfig.MAX_PAGE_SIZE_LIMIT);
                entries.addAll(page.entries());
                cursor = page.next().orElse(null);
            } while (cursor != null);
            v.replays++;
            v.fidelity.addAll(LedgerGrowthBenchmarkEvaluator.historyViolations(label + " " + where, expected,
                    entries));
        }

        private void replay(String where, List<LedgerRecord> records) {
            v.replays++;
            List<HistoryEntry> got = records.stream().map(r -> new HistoryEntry(r.sequence(), r.event())).toList();
            v.fidelity.addAll(LedgerGrowthBenchmarkEvaluator.historyViolations(label + " " + where, expected, got));
        }

        /** The sampled events load by exact id and revision, identically on both roots. */
        private void loads(Path root, Path copy) {
            Set<Integer> probes = LedgerGrowthBenchmarkEvaluator.idempotencyProbes(corpus.events());
            try (ExecutionMemory full = ExecutionMemory.openReadOnly(root, scope);
                 ExecutionMemory bare = ExecutionMemory.openReadOnly(copy, scope)) {
                for (int i : probes) {
                    ExecutionEvent event = corpus.events().get(i);
                    Optional<HistoryEntry> a = full.loadEvent(scope, event.eventId(), event.envelope().revision());
                    Optional<HistoryEntry> b = bare.loadEvent(scope, event.eventId(), event.envelope().revision());
                    v.loads++;
                    if (a.isEmpty() || !a.get().equals(expected.get(i)) || !a.equals(b)) {
                        v.fidelity.add(label + " loadEvent " + event.eventId().value() + ": full "
                                + a.map(e -> "sequence " + e.sequence()).orElse("missing") + ", copy "
                                + b.map(e -> "sequence " + e.sequence()).orElse("missing") + ", expected sequence "
                                + (i + 1));
                    }
                }
            }
        }

        private long heapAfterReadOnlyOpen(Path root) {
            long before = ExecutionScaleSweepEvaluator.usedHeap();
            try (ExecutionMemory memory = ExecutionMemory.openReadOnly(root, scope)) {
                long delta = ExecutionScaleSweepEvaluator.usedHeap() - before;
                memory.projectionStatus(scope); // keep the snapshot reachable until measured
                return delta;
            }
        }

        private void keep(boolean timed, String operation, long nanos) {
            LedgerGrowthBenchmarkEvaluator.keep(timed, samples.get(operation), nanos);
        }

        private String generation(Path root) {
            try {
                return ProjectionLayout.of(root, scope).generation();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Optional<Path> currentDir(Path root) {
            try {
                return ProjectionLayout.of(root, scope).currentDir();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private int generations(Path root) {
            try {
                return ProjectionLayout.of(root, scope).generationDirs().size();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        SizeResult result() {
            Map<EventKind, Integer> kinds = new EnumMap<>(EventKind.class);
            for (EventKind kind : EventKind.values()) {
                kinds.put(kind, 0);
            }
            corpus.events().forEach(e -> kinds.merge(e.kind(), 1, Integer::sum));
            return new SizeResult(corpus.size(), corpus.sourceExperiences(),
                    LedgerGrowthBenchmarkEvaluator.executions(corpus.events()), kinds, segmentBytes, segmentDigest,
                    projectionGeneration);
        }

        SizeTiming timing() {
            Map<String, ScaleTimingStatistics> operations = new LinkedHashMap<>();
            samples.forEach((name, list) -> operations.put(name, ScaleTimingStatistics.from(list, List.of(),
                    corpus.size(), config.iterations())));
            return new SizeTiming(corpus.size(), operations, projectionBytes, heapFull, heapNoProjection);
        }
    }

    private static long bytes(Path dir) {
        try {
            return ExecutionScaleSweepEvaluator.bytes(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
