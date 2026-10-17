package com.monada.evaluation.execution;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExecutionMemoryConfig;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCursor;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.api.execution.HistoryCursor;
import com.monada.api.execution.HistoryEntry;
import com.monada.api.execution.HistoryPage;
import com.monada.api.execution.RecordResult;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.ScaleTimingStatistics;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScopeCorpus;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkReport.Phase;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkReport.SizeResult;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkReport.SizeTiming;
import com.monada.storage.execution.AppendResult;
import com.monada.storage.execution.ExecutionLedger;
import com.monada.storage.execution.ExecutionLedgerReader;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.LedgerRecord;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Measures the authoritative execution ledger and the read-only sample export as the ledger grows (issue #126),
 * before any change to the ledger layout is considered. For every size N a fresh root records exactly N synthetic
 * events of one scope, then the benchmark times append, writable reopen, lock-free read-only open, full history
 * replay, exact {@code loadEvent} and sample-export paging with a pinned cutoff, and finally appends a tail and
 * proves that the pinned pages and their cursors did not move.
 *
 * <p>Both layers are measured: the {@link ExecutionMemory} facade (ledger, history index and projection) and the
 * storage-level {@link ExecutionLedger} / {@link ExecutionLedgerReader}, so that ledger cost can be told apart from
 * projection and index cost. Nothing is configured or tuned: no ledger, codec, export, limit or default changes.
 *
 * <p>As in the #125 sweep, two kinds of output are kept apart. Five hard checks (append idempotency, reopen/replay
 * fidelity, exact refs, export page equality across page sizes and instances, cutoff and cursor stability after
 * later appends) are deterministic and decide the exit code. Every timing, throughput, percentile, heap proxy and
 * growth exponent is informational only and never gates anything.
 */
public final class LedgerGrowthBenchmarkEvaluator {
    static final List<Integer> DEFAULT_SIZES = List.of(100, 1000, 5000);
    static final List<Integer> DEFAULT_EXPORT_PAGE_SIZES = List.of(1, 50, 100);
    static final List<Integer> DEFAULT_HISTORY_PAGE_SIZES = List.of(1, 100, 500);
    static final int DEFAULT_WARMUP = 2;
    static final int DEFAULT_ITERATIONS = 5;
    /**
     * Smallest ledger accepted. With the default seed it already holds two executions with a finished attempt and an
     * outcome, so exports at page size 1 resume a cursor; corrected revisions appear only in longer prefixes (the first
     * before event 60), so the smallest sizes do not exercise revision-2 refs.
     */
    static final int MIN_EVENTS = 10;
    /** Largest ledger accepted: keeps N + tail and the generator's doubling far from int overflow. */
    static final int MAX_EVENTS = 1_000_000;
    /** Evenly spaced events re-recorded to prove idempotency, besides every correction. */
    static final int IDEMPOTENCY_PROBES = 50;
    /** The one scope the corpus is cut from: the first of {@link ExecutionScaleCorpusGenerator#SCOPES}. */
    static final String SCOPE = ExecutionScaleCorpusGenerator.SCOPES.get(0);

    /** What one benchmark measures; every field is printed in the deterministic report. */
    public record Config(long seed, List<Integer> sizes, List<Integer> exportPageSizes,
                         List<Integer> historyPageSizes, int warmup, int iterations) {
        public Config {
            sizes = eventSizes(sizes);
            exportPageSizes = sortedDistinct("export page sizes", exportPageSizes);
            historyPageSizes = sortedDistinct("history page sizes", historyPageSizes);
            pageSizes("export page sizes", exportPageSizes);
            pageSizes("history page sizes", historyPageSizes);
            if (warmup < 0) {
                throw new IllegalArgumentException("warmup must be >= 0, got: " + warmup);
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations must be >= 1, got: " + iterations);
            }
        }

        public static Config defaults() {
            return new Config(ExecutionScaleCorpusGenerator.DEFAULT_SEED, DEFAULT_SIZES, DEFAULT_EXPORT_PAGE_SIZES,
                    DEFAULT_HISTORY_PAGE_SIZES, DEFAULT_WARMUP, DEFAULT_ITERATIONS);
        }

        /** Ledger sizes in events: non-empty, distinct, each in {@code MIN_EVENTS..MAX_EVENTS}, sorted. */
        static List<Integer> eventSizes(List<Integer> values) {
            List<Integer> sizes = sortedDistinct("sizes", values);
            for (int n : sizes) {
                if (n < MIN_EVENTS || n > MAX_EVENTS) {
                    throw new IllegalArgumentException("every size must be in " + MIN_EVENTS + ".." + MAX_EVENTS
                            + " events, got: " + n);
                }
            }
            return sizes;
        }

        static List<Integer> sortedDistinct(String what, List<Integer> values) {
            List<Integer> copy = List.copyOf(values);
            if (copy.isEmpty() || new HashSet<>(copy).size() != copy.size()) {
                throw new IllegalArgumentException(what + " must be non-empty and distinct, got: " + copy);
            }
            return copy.stream().sorted().toList();
        }

        private static void pageSizes(String what, List<Integer> values) {
            for (int size : values) {
                if (size < 1 || size > ExecutionMemoryConfig.MAX_PAGE_SIZE_LIMIT) {
                    throw new IllegalArgumentException("every " + what.substring(0, what.length() - 1) + " must be "
                            + "in 1.." + ExecutionMemoryConfig.MAX_PAGE_SIZE_LIMIT + ", got: " + size);
                }
            }
        }
    }

    /**
     * Exactly {@code events.size()} events of one scope, then the {@code tail} appended after the pinned snapshot.
     * Both are consecutive slices of one generated scope, so every prefix is a valid ledger: references only ever
     * point backwards. {@code sourceExperiences} is the generator size the slices were cut from.
     */
    record LedgerCorpus(long seed, ScopeId scope, int sourceExperiences, List<ExecutionEvent> events,
                        List<ExecutionEvent> tail) {
        LedgerCorpus {
            events = List.copyOf(events);
            tail = List.copyOf(tail);
        }

        int size() {
            return events.size();
        }

        /** The history every replay must return: event i at sequence i + 1. */
        List<HistoryEntry> expectedHistory() {
            List<HistoryEntry> out = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                out.add(new HistoryEntry(i + 1, events.get(i)));
            }
            return out;
        }
    }

    private LedgerGrowthBenchmarkEvaluator() {
    }

    /** Tail appended after the pinned snapshot: a tenth of the ledger, at least one event. */
    static int tailSize(int size) {
        return Math.max(1, size / 10);
    }

    /**
     * Cuts N + tail consecutive events from the first scope of the #125 generator, doubling its size until the
     * scope holds enough events. The same seed and size always give the same slices.
     */
    static LedgerCorpus corpus(long seed, int size) {
        int needed = size + tailSize(size);
        int experiences = Math.max(ExecutionScaleCorpusGenerator.MIN_EXPERIENCES_PER_SCOPE, needed / 4);
        while (true) {
            ScopeCorpus scope = ExecutionScaleCorpusGenerator.generateScope(seed, experiences, 0);
            if (scope.events().size() >= needed) {
                return new LedgerCorpus(seed, scope.scope(), experiences, scope.events().subList(0, size),
                        scope.events().subList(size, needed));
            }
            experiences *= 2;
        }
    }

    /** Runs the benchmark with stores below {@code workDir}; the caller owns {@code workDir} and removes it. */
    public static LedgerGrowthBenchmarkReport evaluate(Config config, Path workDir) {
        Objects.requireNonNull(config, "config");
        List<SizeResult> results = new ArrayList<>();
        List<SizeTiming> timings = new ArrayList<>();
        Violations v = new Violations();
        for (int n : config.sizes()) {
            LedgerCorpus corpus = corpus(config.seed(), n);
            SizeRun run = new SizeRun(config, corpus, workDir, v);
            run.execute();
            results.add(run.result());
            timings.add(run.timing());
        }
        List<ForgeGate> gates = List.of(
                ForgeGate.of(LedgerGrowthBenchmarkReport.APPEND_IDEMPOTENCY,
                        "every event appended once at the next sequence through ExecutionMemory and ExecutionLedger, "
                                + "byte-identical segments; re-records are IDEMPOTENT at the original sequence and "
                                + "change neither the watermark nor the segment",
                        v.append, List.of("events appended: " + v.appended + "; idempotent re-records: "
                                + v.idempotent)),
                ForgeGate.of(LedgerGrowthBenchmarkReport.REPLAY_FIDELITY,
                        "every writable reopen and read-only open (paged at the maximum page size, also after "
                                + "the tail), ExecutionLedger and ExecutionLedgerReader replay exactly the recorded "
                                + "events in order, as does a read-only snapshot at every history page size; healthy, "
                                + "no torn tail",
                        v.fidelity, List.of("replays compared: " + v.replays + "; history traversals compared: "
                                + v.traversals)),
                ForgeGate.of(LedgerGrowthBenchmarkReport.EXACT_REFERENCES,
                        "every event loads exactly by id and revision; every ref of every exported sample resolves "
                                + "to its ledger event at or before the cutoff with matching coordinates",
                        v.references, List.of("exact loads: " + v.loads + "; sample ref resolutions checked: "
                                + v.refChecks + "; distinct sample refs per size: " + v.distinctRefsPerSize.entrySet()
                                .stream().map(e -> "N=" + e.getKey() + ":" + e.getValue())
                                .collect(Collectors.joining(" ")))),
                ForgeGate.of(LedgerGrowthBenchmarkReport.PAGE_EQUALITY,
                        "the same samples in the same order at every export page size, on writable and read-only "
                                + "instances and in every round; one sample per execution, expected page counts",
                        v.pages, List.of("exports compared: " + v.exports + "; pages read: " + v.pagesRead)),
                ForgeGate.of(LedgerGrowthBenchmarkReport.CUTOFF_STABILITY,
                        "after a later append, pinned pages, resumed pre-append export and history cursors return "
                                + "byte-identical pages on the writer and on a new read-only snapshot",
                        v.cutoff, List.of("pinned pages compared: " + v.pinnedPages + "; resumed cursors: "
                                + v.resumedCursors)));
        Map<String, String> environment = new LinkedHashMap<>(ExecutionScaleSweepReport.currentEnvironment());
        environment.put("work dir file store", fileStore(workDir));
        return new LedgerGrowthBenchmarkReport(config, results, gates, timings, environment);
    }

    /** The file system the ledgers were written to: every append forces the segment, so its kind matters. */
    static String fileStore(Path workDir) {
        try {
            var store = Files.getFileStore(workDir);
            return store.type() + " (" + store.name() + ")";
        } catch (IOException e) {
            return "<unknown: " + e.getMessage() + ">";
        }
    }

    /** Violations collected across every size, one list per hard check. */
    private static final class Violations {
        final List<String> append = new ArrayList<>();
        final List<String> fidelity = new ArrayList<>();
        final List<String> references = new ArrayList<>();
        final List<String> pages = new ArrayList<>();
        final List<String> cutoff = new ArrayList<>();
        long appended;
        int idempotent;
        int replays;
        int traversals;
        long loads;
        /** Every resolution of a sample ref: once per distinct canonical ref of each export page size. */
        int refChecks;
        /** Distinct canonical sample refs resolved per ledger size, across every export page size. */
        final Map<Integer, Integer> distinctRefsPerSize = new LinkedHashMap<>();
        int exports;
        long pagesRead;
        int pinnedPages;
        int resumedCursors;
    }

    /** One size of the benchmark: its own facade root and its own storage-only root. */
    private static final class SizeRun {
        private final Config config;
        private final LedgerCorpus corpus;
        private final Path workDir;
        private final Violations v;
        private final String label;
        private final ScopeId scope;
        private final List<HistoryEntry> expected;
        private final int executions;
        private final Map<String, Phase> phases = new LinkedHashMap<>();
        private final Map<String, List<Long>> samples = new LinkedHashMap<>();
        /** Reference export per page size, from the first read-only traversal. */
        private final Map<Integer, List<ExperienceSamplePage>> reference = new TreeMap<>();
        private final Map<Integer, Integer> historyPages = new TreeMap<>();
        private final Set<String> distinctRefs = new TreeSet<>();
        private long heapDelta;
        private long segmentBytes;
        private long segmentBytesAfterTail;
        private String segmentDigest = "";
        private int idempotencyProbes;
        private int executionsAfterTail;
        private String samplesDigest = "";
        private String prefixDigest = "";

        SizeRun(Config config, LedgerCorpus corpus, Path workDir, Violations v) {
            this.config = config;
            this.corpus = corpus;
            this.workDir = workDir;
            this.v = v;
            this.label = "N=" + corpus.size();
            this.scope = corpus.scope();
            this.expected = corpus.expectedHistory();
            this.executions = executions(corpus.events());
        }

        void execute() {
            Path root = RecallStressEvaluator.fresh(workDir, "ledger-n" + corpus.size() + "-");
            Path storageRoot = RecallStressEvaluator.fresh(workDir, "ledger-storage-n" + corpus.size() + "-");
            append(root);
            appendStorage(storageRoot);
            String storageDigest = ledgerDigest(storageRoot, scope);
            if (!segmentDigest.equals(storageDigest)) {
                v.append.add(label + ": the ledger written through ExecutionMemory (" + segmentDigest + ") differs "
                        + "from the one written through ExecutionLedger (" + storageDigest + ")");
            }
            opens(root);
            reads(root);
            afterTail(root);
        }

        // --- append and idempotency ---------------------------------------------------------------------------

        private void append(Path root) {
            List<Long> perEvent = new ArrayList<>(corpus.size());
            try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                long start = System.nanoTime();
                for (int i = 0; i < corpus.size(); i++) {
                    long t = System.nanoTime();
                    RecordResult r = memory.record(corpus.events().get(i));
                    perEvent.add(System.nanoTime() - t);
                    v.appended++;
                    if (r.status() != RecordResult.Status.APPENDED || r.sequence() != i + 1) {
                        v.append.add(label + " record: event " + i + " was " + r.status() + " at sequence "
                                + r.sequence() + " " + r.reason().orElse(""));
                    }
                }
                phases.put("append (ExecutionMemory.record)", new Phase(System.nanoTime() - start, corpus.size(),
                        "events"));
                segmentBytes = ledgerBytes(root, scope);
                segmentDigest = ledgerDigest(root, scope);

                Set<Integer> probes = idempotencyProbes(corpus.events());
                long t = System.nanoTime();
                for (int i : probes) {
                    RecordResult r = memory.record(corpus.events().get(i));
                    if (r.status() != RecordResult.Status.IDEMPOTENT || r.sequence() != i + 1) {
                        v.append.add(label + " re-record: event " + i + " was " + r.status() + " at sequence "
                                + r.sequence() + ", expected IDEMPOTENT at " + (i + 1));
                    }
                }
                phases.put("idempotent re-record", new Phase(System.nanoTime() - t, probes.size(), "events"));
                idempotencyProbes = probes.size();
                v.idempotent += probes.size();
                long watermark = memory.history(scope).highWatermark();
                if (watermark != corpus.size()) {
                    v.append.add(label + ": high watermark " + watermark + " after re-records, expected "
                            + corpus.size());
                }
                if (ledgerBytes(root, scope) != segmentBytes || !ledgerDigest(root, scope).equals(segmentDigest)) {
                    v.append.add(label + ": idempotent re-records changed the ledger segment");
                }
            }
            samples.put("record (ExecutionMemory, per event)", perEvent);
        }

        private void appendStorage(Path root) {
            List<Long> perEvent = new ArrayList<>(corpus.size());
            try (ExecutionLedger ledger = ExecutionLedger.open(root, scope)) {
                long start = System.nanoTime();
                for (int i = 0; i < corpus.size(); i++) {
                    long t = System.nanoTime();
                    AppendResult r = ledger.append(corpus.events().get(i));
                    perEvent.add(System.nanoTime() - t);
                    if (r.status() != AppendResult.Status.APPENDED || r.sequence() != i + 1) {
                        v.append.add(label + " ledger append: event " + i + " was " + r.status() + " at sequence "
                                + r.sequence() + " " + r.reason().orElse(""));
                    }
                }
                phases.put("append (ExecutionLedger.append)", new Phase(System.nanoTime() - start, corpus.size(),
                        "events"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            samples.put("append (ExecutionLedger, per event)", perEvent);
        }

        // --- opens --------------------------------------------------------------------------------------------

        /**
         * Times every way of opening the grown ledger, one fresh instance per round; only the open itself is timed
         * (close is not). Every instance is checked against the recorded history outside the timed region.
         */
        private void opens(Path root) {
            List<Long> writer = new ArrayList<>();
            List<Long> ledgerOpen = new ArrayList<>();
            List<Long> readOnly = new ArrayList<>();
            List<Long> coldExport = new ArrayList<>();
            List<Long> readerOpen = new ArrayList<>();
            List<Long> readerView = new ArrayList<>();
            int coldPageSize = config.exportPageSizes().get(config.exportPageSizes().size() - 1);
            int rounds = config.warmup() + config.iterations();
            for (int round = 0; round < rounds; round++) {
                boolean timed = round >= config.warmup();
                long t = System.nanoTime();
                try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
                    long elapsed = System.nanoTime() - t;
                    keep(timed, writer, elapsed);
                    facadeSnapshot("writable reopen round " + round, memory);
                }

                t = System.nanoTime();
                try (ExecutionLedger ledger = ExecutionLedger.open(root, scope)) {
                    keep(timed, ledgerOpen, System.nanoTime() - t);
                    replay("ExecutionLedger round " + round, ledger.replay());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }

                boolean measureHeap = round == config.warmup();
                long before = measureHeap ? ExecutionScaleSweepEvaluator.usedHeap() : 0;
                t = System.nanoTime();
                try (ExecutionMemory memory = ExecutionMemory.openReadOnly(root, scope)) {
                    long elapsed = System.nanoTime() - t;
                    keep(timed, readOnly, elapsed);
                    if (measureHeap) {
                        heapDelta = ExecutionScaleSweepEvaluator.usedHeap() - before;
                    }
                    // the first page of a fresh instance pays for whatever the exporter builds once per cutoff
                    t = System.nanoTime();
                    ExperienceSamplePage first = memory.exportSamples(pinned(coldPageSize));
                    keep(timed, coldExport, System.nanoTime() - t);
                    if (first.ledger().cutoffSequence() != corpus.size()) {
                        v.cutoff.add(label + " cold export round " + round + ": cutoff "
                                + first.ledger().cutoffSequence());
                    }
                    facadeSnapshot("read-only open round " + round, memory);
                }

                try {
                    t = System.nanoTime();
                    ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, scope);
                    keep(timed, readerOpen, System.nanoTime() - t);
                    t = System.nanoTime();
                    var view = reader.view();
                    keep(timed, readerView, System.nanoTime() - t);
                    replay("ExecutionLedgerReader round " + round, reader.replay());
                    if (!reader.isHealthy() || reader.hasTornTail()) {
                        v.fidelity.add(label + " ExecutionLedgerReader round " + round + ": healthy="
                                + reader.isHealthy() + " tornTail=" + reader.hasTornTail());
                    }
                    if (view.executions().size() != executions) {
                        v.fidelity.add(label + " ExecutionLedgerReader round " + round + ": replay view holds "
                                + view.executions().size() + " executions, expected " + executions);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            samples.put("writer reopen (ExecutionMemory.open)", writer);
            samples.put("writer open (ExecutionLedger.open)", ledgerOpen);
            samples.put("read-only open (ExecutionMemory.openReadOnly)", readOnly);
            samples.put("reader open (ExecutionLedgerReader.open)", readerOpen);
            samples.put("replay view (ExecutionLedgerReader.view)", readerView);
            samples.put("cold first export page ps=" + coldPageSize + " (fresh read-only)", coldExport);
        }

        private void facadeSnapshot(String where, ExecutionMemory memory) {
            facadeSnapshot(where, memory, expected);
        }

        /** Pages the whole facade history at the maximum page size and compares it entry by entry. */
        private void facadeSnapshot(String where, ExecutionMemory memory, List<HistoryEntry> expected) {
            if (memory.hasTornTail()) {
                v.fidelity.add(label + " " + where + ": torn tail");
            }
            FacadeHistory history = facadeHistory(memory, scope);
            for (long watermark : history.watermarks()) {
                if (watermark != expected.size()) {
                    v.fidelity.add(label + " " + where + ": high watermark " + watermark + ", recorded "
                            + expected.size());
                    return;
                }
            }
            v.replays++;
            v.fidelity.addAll(historyViolations(label + " " + where, expected, history.entries()));
        }

        private void replay(String where, List<LedgerRecord> records) {
            v.replays++;
            v.fidelity.addAll(historyViolations(label + " " + where, expected, entries(records)));
        }

        // --- reads on a read-only snapshot and on the writer ---------------------------------------------------

        private void reads(Path root) {
            try (ExecutionMemory readOnly = ExecutionMemory.openReadOnly(root, scope)) {
                history(readOnly);
                loads(readOnly);
                exports(readOnly);
            }
        }

        private void history(ExecutionMemory memory) {
            int rounds = config.warmup() + config.iterations();
            for (int pageSize : config.historyPageSizes()) {
                List<Long> full = new ArrayList<>();
                for (int round = 0; round < rounds; round++) {
                    List<HistoryEntry> entries = new ArrayList<>(corpus.size());
                    int pages = 0;
                    long t = System.nanoTime();
                    HistoryCursor cursor = null;
                    do {
                        HistoryPage page = memory.history(scope, cursor, pageSize);
                        entries.addAll(page.entries());
                        pages++;
                        cursor = page.next().orElse(null);
                    } while (cursor != null);
                    keep(round >= config.warmup(), full, System.nanoTime() - t);
                    v.traversals++;
                    v.fidelity.addAll(historyViolations(label + " history ps=" + pageSize + " round " + round,
                            expected, entries));
                    int expectedPages = ceilDiv(corpus.size(), pageSize);
                    if (pages != expectedPages) {
                        v.fidelity.add(label + " history ps=" + pageSize + ": " + pages + " pages, expected "
                                + expectedPages);
                    }
                    historyPages.put(pageSize, pages);
                }
                samples.put("history full replay ps=" + pageSize, full);
            }
        }

        /** Every event by exact id and revision, each round starting at a rotated offset. */
        private void loads(ExecutionMemory memory) {
            List<Long> perLoad = new ArrayList<>();
            int n = corpus.size();
            int rounds = config.warmup() + config.iterations();
            for (int round = 0; round < rounds; round++) {
                boolean timed = round >= config.warmup();
                int offset = (int) ((long) round * n / Math.max(1, rounds));
                for (int j = 0; j < n; j++) {
                    int i = (j + offset) % n;
                    ExecutionEvent event = corpus.events().get(i);
                    long t = System.nanoTime();
                    Optional<HistoryEntry> entry = memory.loadEvent(scope, event.eventId(),
                            event.envelope().revision());
                    keep(timed, perLoad, System.nanoTime() - t);
                    v.loads++;
                    if (entry.isEmpty() || !entry.get().equals(expected.get(i))) {
                        v.references.add(label + " loadEvent " + event.eventId().value() + " r"
                                + event.envelope().revision() + ": " + entry.map(e -> "sequence " + e.sequence())
                                .orElse("missing") + ", expected sequence " + (i + 1));
                    }
                }
            }
            samples.put("loadEvent exact (per lookup)", perLoad);
        }

        private void exports(ExecutionMemory memory) {
            int rounds = config.warmup() + config.iterations();
            List<ExperienceSample> first = null;
            for (int pageSize : config.exportPageSizes()) {
                List<Long> full = new ArrayList<>();
                List<Long> perPage = new ArrayList<>();
                for (int round = 0; round < rounds; round++) {
                    boolean timed = round >= config.warmup();
                    List<Long> pageNanos = new ArrayList<>();
                    long t = System.nanoTime();
                    List<ExperienceSamplePage> pages = exportAll(memory, pinned(pageSize), pageNanos);
                    keep(timed, full, System.nanoTime() - t);
                    if (timed) {
                        perPage.addAll(pageNanos);
                    }
                    v.exports++;
                    v.pagesRead += pages.size();
                    String where = label + " export ps=" + pageSize + " round " + round;
                    if (round == 0) {
                        reference.put(pageSize, pages);
                        exportStructure(where, pageSize, pages);
                        refs(where, memory, pages);
                        List<ExperienceSample> flat = flatten(pages);
                        if (first == null) {
                            first = flat;
                            samplesDigest = samplesDigest(pages);
                            prefixDigest = pages.get(0).ledger().prefixDigest();
                        } else {
                            v.pages.addAll(sampleEqualityViolations(where + " vs ps="
                                    + config.exportPageSizes().get(0), first, flat));
                        }
                    } else {
                        v.pages.addAll(pageDigestViolations(where + " vs round 0", reference.get(pageSize), pages));
                    }
                }
                samples.put("export full ps=" + pageSize, full);
                samples.put("export page ps=" + pageSize, perPage);
            }
            v.distinctRefsPerSize.put(corpus.size(), distinctRefs.size());
        }

        private void exportStructure(String where, int pageSize, List<ExperienceSamplePage> pages) {
            List<ExperienceSample> flat = flatten(pages);
            if (flat.size() != executions) {
                v.pages.add(where + ": " + flat.size() + " samples, expected one per execution: " + executions);
            }
            int expectedPages = Math.max(1, ceilDiv(flat.size(), pageSize));
            if (pages.size() != expectedPages) {
                v.pages.add(where + ": " + pages.size() + " pages, expected " + expectedPages);
            }
            v.pages.addAll(sampleOrderViolations(where, flat));
            for (ExperienceSamplePage page : pages) {
                if (page.ledger().cutoffSequence() != corpus.size()
                        || !page.ledger().prefixDigest().equals(pages.get(0).ledger().prefixDigest())) {
                    v.cutoff.add(where + ": a page reports cutoff " + page.ledger().cutoffSequence()
                            + " with another prefix digest");
                }
            }
        }

        /** Every distinct ref of every sample loads exactly, at or before the cutoff, with matching coordinates. */
        private void refs(String where, ExecutionMemory memory, List<ExperienceSamplePage> pages) {
            // one resolution per canonical ref; every sequence a sample carries for it is checked against that entry
            Map<String, List<RefAt>> distinct = new LinkedHashMap<>();
            for (ExperienceSample s : flatten(pages)) {
                for (RefAt r : refsOf(s)) {
                    distinct.computeIfAbsent(ExperienceRefCodec.canonical(r.ref()), k -> new ArrayList<>()).add(r);
                }
            }
            for (List<RefAt> occurrences : distinct.values()) {
                ExperienceRef ref = occurrences.get(0).ref();
                Optional<HistoryEntry> entry = memory.loadEvent(scope, ref.eventId(), ref.revision());
                if (entry.isEmpty()) {
                    v.references.add(where + ": sample ref " + ExperienceRefCodec.canonical(ref) + " does not load");
                    continue;
                }
                v.refChecks++;
                distinctRefs.add(ExperienceRefCodec.canonical(ref));
                HistoryEntry e = entry.get();
                if (e.sequence() > corpus.size()) {
                    v.references.add(where + ": sample ref " + ref.eventId().value() + " is at sequence "
                            + e.sequence() + ", after the cutoff " + corpus.size());
                }
                if (e.event().envelope().revision() != ref.revision()) {
                    v.references.add(where + ": sample ref " + ref.eventId().value() + " r" + ref.revision()
                            + " loads revision " + e.event().envelope().revision());
                }
                for (RefAt r : occurrences) {
                    if (r.sequence().isPresent() && r.sequence().getAsLong() != e.sequence()) {
                        v.references.add(where + ": sample ref " + ref.eventId().value() + " carries sequence "
                                + r.sequence().getAsLong() + ", the ledger holds it at " + e.sequence());
                    }
                }
                v.references.addAll(ForgeExperienceEvaluator.coordinateViolations(where + ": sample ref", scope, ref,
                        e));
            }
        }

        // --- later appends: pinned cutoff and cursor stability -------------------------------------------------

        private void afterTail(Path root) {
            int historyPageSize = config.historyPageSizes().get(0);
            // a first page of one entry always leaves a cursor to resume (N >= MIN_EVENTS), whatever the config
            HistoryPage historyBefore;
            try (ExecutionMemory writer = ExecutionMemory.open(root, scope)) {
                // the writer exports exactly what the read-only snapshot exported
                for (int pageSize : config.exportPageSizes()) {
                    List<ExperienceSamplePage> pages = exportAll(writer, pinned(pageSize), new ArrayList<>());
                    v.exports++;
                    v.pagesRead += pages.size();
                    v.pages.addAll(pageDigestViolations(label + " writable export ps=" + pageSize + " vs read-only",
                            reference.get(pageSize), pages));
                }
                historyBefore = writer.history(scope, null, 1);

                for (int i = 0; i < corpus.tail().size(); i++) {
                    RecordResult r = writer.record(corpus.tail().get(i));
                    v.appended++;
                    long sequence = corpus.size() + i + 1L;
                    if (r.status() != RecordResult.Status.APPENDED || r.sequence() != sequence) {
                        v.append.add(label + " tail: event " + i + " was " + r.status() + " at sequence "
                                + r.sequence() + ", expected APPENDED at " + sequence);
                    }
                }
                List<ExecutionEvent> all = new ArrayList<>(corpus.events());
                all.addAll(corpus.tail());
                executionsAfterTail = executions(all);

                pinnedPages("writer after tail", writer);
                resumedCursors("writer", writer);
                resumedHistory("writer", writer, historyBefore, historyPageSize);

                int largest = config.exportPageSizes().get(config.exportPageSizes().size() - 1);
                List<ExperienceSamplePage> latest = exportAll(writer, ExportRequest.first(scope, ExportCutoff.latest(),
                        largest, ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()),
                        new ArrayList<>());
                long latestCutoff = latest.get(0).ledger().cutoffSequence();
                int latestSamples = flatten(latest).size();
                if (latestCutoff != all.size() || latestSamples != executionsAfterTail) {
                    v.cutoff.add(label + ": latest export after the tail has cutoff " + latestCutoff + " and "
                            + latestSamples + " samples, expected " + all.size() + " and " + executionsAfterTail);
                }
            }
            segmentBytesAfterTail = ledgerBytes(root, scope);
            List<HistoryEntry> expectedAfterTail = new ArrayList<>(expected);
            for (int i = 0; i < corpus.tail().size(); i++) {
                expectedAfterTail.add(new HistoryEntry(corpus.size() + i + 1L, corpus.tail().get(i)));
            }
            try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, scope)) {
                facadeSnapshot("read-only open after tail", snapshot, expectedAfterTail);
                pinnedPages("new read-only snapshot after tail", snapshot);
                resumedCursors("new read-only snapshot", snapshot);
                resumedHistory("new read-only snapshot", snapshot, historyBefore, historyPageSize);
            }
        }

        private void pinnedPages(String where, ExecutionMemory memory) {
            for (int pageSize : config.exportPageSizes()) {
                List<ExperienceSamplePage> pages = exportAll(memory, pinned(pageSize), new ArrayList<>());
                v.pinnedPages += pages.size();
                v.cutoff.addAll(pageDigestViolations(label + " " + where + " pinned export ps=" + pageSize,
                        reference.get(pageSize), pages));
            }
        }

        /** Each pre-append cursor, resumed after the tail, returns exactly the page that followed it before. */
        private void resumedCursors(String where, ExecutionMemory memory) {
            for (int pageSize : config.exportPageSizes()) {
                List<ExperienceSamplePage> before = reference.get(pageSize);
                for (int i = 0; i + 1 < before.size(); i++) {
                    ExportCursor cursor = ExportCursor.parse(before.get(i).next().orElseThrow().token());
                    ExperienceSamplePage page = memory.exportSamples(ExportRequest.resume(cursor, pageSize));
                    v.resumedCursors++;
                    if (!page.digest().equals(before.get(i + 1).digest())) {
                        v.cutoff.add(label + " " + where + " resumed cursor ps=" + pageSize + " after page " + (i + 1)
                                + " returns another page after the tail");
                    }
                }
            }
        }

        /**
         * A history cursor taken before the tail, resumed at {@code pageSize}, still ends at its own high watermark
         * and returns exactly the pre-tail history.
         */
        private void resumedHistory(String where, ExecutionMemory memory, HistoryPage first, int pageSize) {
            List<HistoryEntry> entries = new ArrayList<>(first.entries());
            HistoryCursor cursor = first.next().map(c -> HistoryCursor.parse(c.token())).orElse(null);
            if (cursor == null) {
                v.cutoff.add(label + " " + where + ": the pre-tail history page left no cursor to resume");
                return;
            }
            while (cursor != null) {
                HistoryPage page = memory.history(scope, cursor, pageSize);
                entries.addAll(page.entries());
                if (page.highWatermark() != corpus.size()) {
                    v.cutoff.add(label + " " + where + " resumed history ps=" + pageSize + ": high watermark "
                            + page.highWatermark() + " after the tail, the cursor was taken at " + corpus.size());
                    return;
                }
                cursor = page.next().orElse(null);
            }
            v.resumedCursors++;
            v.cutoff.addAll(historyViolations(label + " " + where + " resumed history ps=" + pageSize
                    + " after the tail", expected, entries));
        }

        // --- results ------------------------------------------------------------------------------------------

        private ExportRequest pinned(int pageSize) {
            return ExportRequest.first(scope, ExportCutoff.atSequence(corpus.size()), pageSize,
                    ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly());
        }

        SizeResult result() {
            Map<EventKind, Integer> kinds = new EnumMap<>(EventKind.class);
            for (EventKind kind : EventKind.values()) {
                kinds.put(kind, 0);
            }
            corpus.events().forEach(e -> kinds.merge(e.kind(), 1, Integer::sum));
            Map<Integer, Integer> exportPages = new TreeMap<>();
            Map<Integer, String> exportDigests = new TreeMap<>();
            reference.forEach((pageSize, pages) -> {
                exportPages.put(pageSize, pages.size());
                exportDigests.put(pageSize, exportDigest(pages));
            });
            int sampleCount = reference.isEmpty() ? 0 : flatten(reference.values().iterator().next()).size();
            return new SizeResult(corpus.size(), corpus.tail().size(), corpus.sourceExperiences(), executions,
                    executionsAfterTail, kinds, segmentBytes, segmentBytesAfterTail, segmentDigest,
                    idempotencyProbes, historyPages, sampleCount, samplesDigest, prefixDigest, exportPages,
                    exportDigests);
        }

        SizeTiming timing() {
            Map<String, ScaleTimingStatistics> operations = new LinkedHashMap<>();
            samples.forEach((name, list) -> operations.put(name, ScaleTimingStatistics.from(list, List.of(),
                    corpus.size(), config.iterations())));
            return new SizeTiming(corpus.size(), phases, operations, heapDelta);
        }
    }

    // --- shared helpers and checks ---------------------------------------------------------------------------

    static void keep(boolean timed, List<Long> samples, long nanos) {
        if (timed) {
            samples.add(nanos);
        }
    }

    /** Pages through a whole export from {@code first}, adding each page's elapsed nanos to {@code pageNanos}. */
    static List<ExperienceSamplePage> exportAll(ExecutionMemory memory, ExportRequest first, List<Long> pageNanos) {
        List<ExperienceSamplePage> pages = new ArrayList<>();
        long t = System.nanoTime();
        ExperienceSamplePage page = memory.exportSamples(first);
        pageNanos.add(System.nanoTime() - t);
        pages.add(page);
        while (page.hasMore()) {
            t = System.nanoTime();
            page = memory.exportSamples(ExportRequest.resume(page.next().orElseThrow(), first.pageSize()));
            pageNanos.add(System.nanoTime() - t);
            pages.add(page);
        }
        return pages;
    }

    static List<ExperienceSample> flatten(List<ExperienceSamplePage> pages) {
        return pages.stream().flatMap(p -> p.samples().stream()).toList();
    }

    /**
     * Re-recorded events, in ledger order: {@code min(}{@value #IDEMPOTENCY_PROBES}{@code , N)} evenly spaced ones
     * (index {@code floor((k + 1) * N / 50) - 1}, so the last event is always one) plus every correction.
     */
    static Set<Integer> idempotencyProbes(List<ExecutionEvent> events) {
        Set<Integer> probes = new TreeSet<>();
        int n = events.size();
        int spaced = Math.min(IDEMPOTENCY_PROBES, n);
        for (int k = 0; k < spaced; k++) {
            probes.add((int) ((long) (k + 1) * n / spaced) - 1);
        }
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).kind() == EventKind.CORRECTION) {
                probes.add(i);
            }
        }
        return probes;
    }

    static int executions(List<ExecutionEvent> events) {
        Set<ExecutionId> ids = new LinkedHashSet<>();
        events.forEach(e -> ids.add(e.executionId()));
        return ids.size();
    }

    /** A whole facade history traversal: its entries and the high watermark every page reported, in page order. */
    record FacadeHistory(List<HistoryEntry> entries, List<Long> watermarks) {
    }

    /** The whole facade history of {@code scope}, paged at the maximum page size, with each page's watermark. */
    static FacadeHistory facadeHistory(ExecutionMemory memory, ScopeId scope) {
        List<HistoryEntry> entries = new ArrayList<>();
        List<Long> watermarks = new ArrayList<>();
        HistoryCursor cursor = null;
        do {
            HistoryPage page = memory.history(scope, cursor, ExecutionMemoryConfig.MAX_PAGE_SIZE_LIMIT);
            watermarks.add(page.highWatermark());
            entries.addAll(page.entries());
            cursor = page.next().orElse(null);
        } while (cursor != null);
        return new FacadeHistory(entries, watermarks);
    }

    /** Storage-level records as the history entries a facade replay must return. */
    static List<HistoryEntry> entries(List<LedgerRecord> records) {
        return records.stream().map(r -> new HistoryEntry(r.sequence(), r.event())).toList();
    }

    /** A replay must hold exactly the expected entries in order; reports the size and the first mismatch. */
    static List<String> historyViolations(String where, List<HistoryEntry> expected, List<HistoryEntry> got) {
        List<String> violations = new ArrayList<>();
        if (got.size() != expected.size()) {
            violations.add(where + ": " + got.size() + " entries, expected " + expected.size());
        }
        for (int i = 0; i < Math.min(got.size(), expected.size()); i++) {
            if (!got.get(i).equals(expected.get(i))) {
                violations.add(where + ": entry " + (i + 1) + " is " + got.get(i).event().eventId().value()
                        + " at sequence " + got.get(i).sequence() + ", expected "
                        + expected.get(i).event().eventId().value() + " at " + expected.get(i).sequence());
                break;
            }
        }
        return violations;
    }

    /** The flattened samples of two exports must be equal and in the same order. */
    static List<String> sampleEqualityViolations(String where, List<ExperienceSample> reference,
                                                 List<ExperienceSample> got) {
        List<String> violations = new ArrayList<>();
        if (got.size() != reference.size()) {
            violations.add(where + ": " + got.size() + " samples, expected " + reference.size());
        }
        for (int i = 0; i < Math.min(got.size(), reference.size()); i++) {
            if (!got.get(i).equals(reference.get(i))) {
                violations.add(where + ": sample " + (i + 1) + " (" + got.get(i).executionId().value() + ") differs "
                        + "from " + reference.get(i).executionId().value());
                break;
            }
        }
        return violations;
    }

    /** One sample per execution, in strictly ascending order of the sequence at which each execution started. */
    static List<String> sampleOrderViolations(String where, List<ExperienceSample> samples) {
        List<String> violations = new ArrayList<>();
        Set<ExecutionId> seen = new HashSet<>();
        long previous = 0;
        for (ExperienceSample s : samples) {
            if (!seen.add(s.executionId())) {
                violations.add(where + ": execution " + s.executionId().value() + " is exported twice");
            }
            if (s.firstSequence() <= previous) {
                violations.add(where + ": sample " + s.executionId().value() + " starts at " + s.firstSequence()
                        + ", not after " + previous);
            }
            previous = s.firstSequence();
        }
        return violations;
    }

    /** Two page lists must have the same canonical page digests, page by page. */
    static List<String> pageDigestViolations(String where, List<ExperienceSamplePage> reference,
                                             List<ExperienceSamplePage> got) {
        List<String> violations = new ArrayList<>();
        if (got.size() != reference.size()) {
            violations.add(where + ": " + got.size() + " pages, expected " + reference.size());
        }
        for (int i = 0; i < Math.min(got.size(), reference.size()); i++) {
            if (!got.get(i).digest().equals(reference.get(i).digest())) {
                violations.add(where + ": page " + (i + 1) + " differs");
                break;
            }
        }
        return violations;
    }

    /** A ref of a sample with the sequence the sample says it is at, when it carries one. */
    record RefAt(ExperienceRef ref, OptionalLong sequence) {
    }

    /** Every ledger ref a sample carries: execution, outcome, gates, attempts, stages, evidence and revisions. */
    static List<RefAt> refsOf(ExperienceSample s) {
        List<RefAt> refs = new ArrayList<>();
        refs.add(new RefAt(s.execution(), OptionalLong.empty()));
        s.outcome().ifPresent(o -> refs.add(new RefAt(o.ref(), OptionalLong.of(o.sequence()))));
        s.gates().forEach(g -> g.ref().ifPresent(r -> refs.add(new RefAt(r, g.sequence()))));
        for (ExperienceSample.SampleAttempt a : s.attempts()) {
            refs.add(new RefAt(a.start(), OptionalLong.empty()));
            a.finish().ifPresent(r -> refs.add(new RefAt(r, OptionalLong.empty())));
            a.stages().forEach(st -> refs.add(new RefAt(st.ref(), OptionalLong.of(st.sequence()))));
            a.evidence().forEach(ev -> refs.add(new RefAt(ev.ref(), OptionalLong.of(ev.sequence()))));
        }
        for (ExperienceSample.Revision r : s.revisions()) {
            refs.add(new RefAt(r.effective(), OptionalLong.of(r.sequence())));
            r.superseded().forEach(old -> refs.add(new RefAt(old, OptionalLong.empty())));
        }
        s.diagnostics().ifPresent(d -> {
            d.outcomeHistory().forEach(o -> refs.add(new RefAt(o.ref(), OptionalLong.of(o.sequence()))));
            d.ignoredObservations().forEach(o -> refs.add(new RefAt(o.ref(), OptionalLong.empty())));
        });
        return refs;
    }

    /** SHA-256 (first 16 hex digits) over every page's canonical digest, in page order. */
    static String exportDigest(List<ExperienceSamplePage> pages) {
        StringBuilder text = new StringBuilder();
        pages.forEach(p -> text.append(p.digest()).append('\n'));
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /**
     * Canonical digest (first 16 hex digits) of the samples alone, independent of how they were paged: all samples
     * rendered as one page of the first page's scope, view, gates, ledger checkpoint and projection stamp.
     */
    static String samplesDigest(List<ExperienceSamplePage> pages) {
        ExperienceSamplePage first = pages.get(0);
        return new ExperienceSamplePage(first.schema(), first.scope(), first.view(), first.additionalGates(),
                first.ledger(), first.projection(), flatten(pages), false, Optional.empty()).digest().substring(0, 16);
    }

    static Path ledgerDir(Path root, ScopeId scope) throws IOException {
        return ProjectionLayout.of(root, scope).scopeDir().resolve("ledger");
    }

    /** Bytes of the scope's ledger directory (the event segment), excluding projections. */
    static long ledgerBytes(Path root, ScopeId scope) {
        try {
            return ExecutionScaleSweepEvaluator.bytes(ledgerDir(root, scope));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA-256 (first 16 hex digits) over the names and contents of the scope's ledger files, in name order. */
    static String ledgerDigest(Path root, ScopeId scope) {
        try {
            return treeDigest(ledgerDir(root, scope));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * SHA-256 (first 16 hex digits) over the relative names and contents of every entry below {@code dir} except
     * directories, in name order. Links are never followed and never skipped: a link contributes its name and its
     * target path, so a symlinked file can't drop out of the digest.
     */
    static String treeDigest(Path dir) {
        try {
            List<Path> files;
            try (var walk = Files.walk(dir)) {
                files = walk.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                digest.update(dir.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isSymbolicLink(file)) {
                    digest.update(("-> " + Files.readSymbolicLink(file)).getBytes(StandardCharsets.UTF_8));
                } else {
                    digest.update(Files.readAllBytes(file));
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
