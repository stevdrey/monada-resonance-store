package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.storage.audit.StorageIntegrityStatistics;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Profiles on-disk growth, process memory and repeat-import behaviour of book Stores (Issue #169, Book 7/8;
 * evaluation only). Every Store lives in its own directory below an isolated scratch root; nothing else is written.
 *
 * <p>Per ladder scale ({@link BookLatencyScale}), one Store goes through four phases, each snapshotted from disk with
 * {@link BookFootprintSnapshot}:
 * <ol>
 *   <li>{@code EMPTY}: the Store does not exist yet;</li>
 *   <li>{@code FRESH_IMPORT}: {@code MonadaMemory.open(store).rememberAll(texts)};</li>
 *   <li>{@code REOPEN}: a new instance answers one recall (so the deferred vector load really happens); the Store's
 *   bytes must not change;</li>
 *   <li>{@code REPEAT_IMPORT}: a new instance remembers the same texts again; nothing may be appended, and no space is
 *   reclaimed (none is expected).</li>
 * </ol>
 * Two content experiments run once over the whole chunk set, each on its own Stores: {@code OVERLAP} (half the
 * chunks, then all of them: only the missing ones may be appended) and {@code INTRA_BATCH_DUPLICATES} (every chunk
 * twice in one call, against a clean import: repeats must merge into the same atoms).
 *
 * <p>Every structural expectation is a {@link Check}; a failed check is reported, never hidden, and fails the run.
 * Memory samples ({@link BookMemorySampler}) are informational only.
 */
final class BookFootprintBenchmark {
    /** The scale the OVERLAP experiment seeds its Store with before importing every chunk. */
    static final String OVERLAP_SEED = "50%";
    static final String API = "MonadaMemory.open(store).rememberAll(texts); reopen probe: resonate(first text).topK(1)";

    private BookFootprintBenchmark() {
    }

    /** One structural expectation and what was observed. */
    record Check(String name, String expected, String actual) {
        boolean passed() {
            return expected.equals(actual);
        }
    }

    /**
     * One phase of one Store.
     *
     * @param inputTexts      texts passed to the API in this phase (repeats included); 0 for EMPTY and REOPEN
     * @param inputTextBytes  UTF-8 bytes of those texts
     * @param newAtoms        distinct content-derived ids the phase's input adds to what the Store held before
     * @param newTextBytes    UTF-8 bytes of the texts of those new atoms (the content the phase had to persist)
     * @param uniqueTextBytes UTF-8 bytes of the distinct texts the Store should hold after the phase
     * @param uniqueTextCodePoints Unicode code points of those distinct texts
     * @param unchanged       the Store's tree digest equals the previous phase's; null for a Store's first phase
     * @param memory          memory sample around the phase's API work, or null where no API call ran
     */
    record PhaseResult(String phase, int inputTexts, long inputTextBytes, int newAtoms, long newTextBytes,
                       int expectedUniqueAtoms, long uniqueTextBytes, long uniqueTextCodePoints,
                       BookFootprintSnapshot snapshot, Boolean unchanged, BookMemorySampler.Sample memory) {
    }

    /**
     * The phases of one ladder scale plus an approximate retained-heap estimate of one reopened, queried instance:
     * heap used after an advisory GC while the instance is held, minus the same after it is dropped.
     */
    record ScaleResult(BookLatencyScale.Selected selected, List<PhaseResult> phases, long retainedHeapEstimate) {
        ScaleResult {
            phases = List.copyOf(phases);
        }
    }

    /** One content experiment: its phases, in order, possibly over several Stores (named in the phase). */
    record Experiment(String id, String description, List<PhaseResult> phases) {
        Experiment {
            phases = List.copyOf(phases);
        }
    }

    record Result(Map<String, String> inputs, List<ScaleResult> scales, List<Experiment> experiments,
                  List<Check> checks, Map<String, String> host) {
        Result {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            scales = List.copyOf(scales);
            experiments = List.copyOf(experiments);
            checks = List.copyOf(checks);
            host = Collections.unmodifiableMap(new LinkedHashMap<>(host));
        }

        boolean passed() {
            return checks.stream().allMatch(Check::passed);
        }

        List<Check> failures() {
            return checks.stream().filter(c -> !c.passed()).toList();
        }
    }

    /**
     * Runs every scale of {@code ladder} and both content experiments over {@code chunks} in the existing, empty
     * directory {@code scratch}. {@code guardedInputs} are files the run only reads (the body, the manifest): their
     * digests before and after the run are a check.
     */
    static Result run(BookChunkFiles.Loaded chunks, Map<String, String> inputs, List<BookLatencyScale> ladder,
                      Path scratch, BookMemorySampler sampler, List<Path> guardedInputs) throws IOException {
        List<String> texts = chunks.entries().stream().map(BookChunkFiles.Entry::text).toList();
        requireRunnable(texts.size(), ladder);
        Map<Path, String> inputDigests = new LinkedHashMap<>();
        for (Path input : guardedInputs) {
            inputDigests.put(input, digest(input));
        }
        List<Check> checks = new ArrayList<>();
        List<ScaleResult> scales = new ArrayList<>();
        for (BookLatencyScale scale : ladder) {
            Path dir = Files.createDirectory(scratch.resolve("scale-" + scale.id()));
            scales.add(runScale(scale.select(texts), chunks, dir.resolve("store"), sampler, checks));
        }
        Path experiments = Files.createDirectory(scratch.resolve("experiments"));
        List<Experiment> done = List.of(
                overlap(texts, Files.createDirectory(experiments.resolve("overlap")), sampler, checks),
                intraBatchDuplicates(texts, Files.createDirectory(experiments.resolve("duplicates")), sampler,
                        checks));
        int i = 0;
        for (Map.Entry<Path, String> input : inputDigests.entrySet()) {
            checks.add(new Check("inputs.guarded[" + i++ + "].unchanged", "yes",
                    yesNo(input.getValue().equals(digest(input.getKey())))));
        }
        Map<String, String> host = BookLatencyHost.describe(scratch);
        host.remove("clock");
        host.put("memory heap", BookMemorySampler.HEAP_METHOD);
        host.put("memory allocation", BookMemorySampler.ALLOCATION_METHOD);
        host.put("memory rss", BookMemorySampler.RSS_METHOD);
        host.put("memory gc", BookMemorySampler.GC_NOTE);
        return new Result(inputs, scales, done, checks, host);
    }

    /**
     * Fails, before anything is created, when {@code chunkCount} chunks cannot run the experiment: every ladder scale
     * and the {@value #OVERLAP_SEED} seed of the OVERLAP experiment must select at least one chunk. Count arithmetic
     * only, so callers can check a corpus before any scratch directory exists.
     */
    static void requireRunnable(int chunkCount, List<BookLatencyScale> ladder) {
        if (chunkCount < 1) {
            throw new IllegalArgumentException("the experiment needs chunks");
        }
        for (BookLatencyScale scale : ladder) {
            scale.requireSelects(chunkCount);
        }
        try {
            BookLatencyScale.parse(OVERLAP_SEED).requireSelects(chunkCount);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the OVERLAP experiment needs its " + OVERLAP_SEED + " seed to select a"
                    + " chunk: " + e.getMessage(), e);
        }
    }

    private static ScaleResult runScale(BookLatencyScale.Selected selected, BookChunkFiles.Loaded chunks, Path store,
                                        BookMemorySampler sampler, List<Check> checks) throws IOException {
        String id = "scale." + selected.scale().id();
        List<String> texts = selected.texts();
        Map<String, String> expected = BookStoreReadback.expectedContents(texts);
        List<PhaseResult> phases = new ArrayList<>();
        Phases tracker = new Phases(store, phases);

        tracker.record("EMPTY", List.of(), Map.of(), null);
        BookMemorySampler.Measured<Void> fresh = sampler.measure(id + ".FRESH_IMPORT", () -> {
            MonadaMemory.open(store).rememberAll(texts);
            return null;
        });
        PhaseResult freshPhase = tracker.record("FRESH_IMPORT", texts, expected, fresh.sample());
        structural(id + ".FRESH_IMPORT", freshPhase, expected.size(), checks);
        // REAL ordinals are the sidecar's own: every selected chunk key must reach its exact text in the Store.
        resolution(id + ".FRESH_IMPORT", store, freshPhase, texts,
                selected.scale().kind() == BookLatencyScale.Kind.REAL ? chunks : null, selected.ordinals(), checks);

        String probe = texts.getFirst();
        BookMemorySampler.Measured<Void> reopen = sampler.measure(id + ".REOPEN", () -> {
            MonadaMemory.open(store).resonate(probe).topK(1).execute();
            return null;
        });
        // The retained-heap probe opens and queries the Store too: it runs before the REOPEN snapshot, so anything
        // either reopen wrote is charged to REOPEN, never to the repeat import that follows.
        long retained = retainedHeap(store, probe);
        PhaseResult reopenPhase = tracker.record("REOPEN", List.of(), expected, reopen.sample());
        checks.add(new Check(id + ".REOPEN.storeUnchanged", "yes",
                yesNo(Boolean.TRUE.equals(reopenPhase.unchanged()))));

        BookMemorySampler.Measured<Void> repeat = sampler.measure(id + ".REPEAT_IMPORT", () -> {
            MonadaMemory.open(store).rememberAll(texts);
            return null;
        });
        PhaseResult repeatPhase = tracker.record("REPEAT_IMPORT", texts, expected, repeat.sample());
        checks.add(new Check(id + ".REPEAT_IMPORT.newAtoms", "0", Integer.toString(repeatPhase.newAtoms())));
        checks.add(new Check(id + ".REPEAT_IMPORT.storeUnchanged", "yes",
                yesNo(Boolean.TRUE.equals(repeatPhase.unchanged()))));
        structural(id + ".REPEAT_IMPORT", repeatPhase, expected.size(), checks);
        return new ScaleResult(selected, phases, retained);
    }

    /** Half the chunks first, then all of them: the second import may append exactly the missing atoms. */
    private static Experiment overlap(List<String> texts, Path dir, BookMemorySampler sampler, List<Check> checks)
            throws IOException {
        String id = "experiment.OVERLAP";
        List<String> half = BookLatencyScale.parse(OVERLAP_SEED).select(texts).texts();
        Map<String, String> halfExpected = BookStoreReadback.expectedContents(half);
        Map<String, String> allExpected = BookStoreReadback.expectedContents(texts);
        Path store = dir.resolve("store");
        List<PhaseResult> phases = new ArrayList<>();
        Phases tracker = new Phases(store, phases);
        BookMemorySampler.Measured<Void> first = sampler.measure(id + ".IMPORT_50%", () -> {
            MonadaMemory.open(store).rememberAll(half);
            return null;
        });
        PhaseResult halfPhase = tracker.record("IMPORT_50%", half, halfExpected, first.sample());
        structural(id + ".IMPORT_50%", halfPhase, halfExpected.size(), checks);
        BookMemorySampler.Measured<Void> second = sampler.measure(id + ".IMPORT_100%", () -> {
            MonadaMemory.open(store).rememberAll(texts);
            return null;
        });
        PhaseResult allPhase = tracker.record("IMPORT_100%", texts, allExpected, second.sample());
        structural(id + ".IMPORT_100%", allPhase, allExpected.size(), checks);
        checks.add(new Check(id + ".IMPORT_100%.newAtoms", Integer.toString(allExpected.size() - halfExpected.size()),
                Integer.toString(allPhase.newAtoms())));
        StorageIntegrityStatistics before = halfPhase.snapshot().statistics();
        StorageIntegrityStatistics after = allPhase.snapshot().statistics();
        checks.add(new Check(id + ".IMPORT_100%.appendedAtomRecords",
                Integer.toString(allPhase.newAtoms()),
                Long.toString(after.atomLogPhysicalRecords() - before.atomLogPhysicalRecords())));
        checks.add(new Check(id + ".IMPORT_100%.appendedVectorBytes",
                Long.toString(allPhase.newAtoms() * allPhase.snapshot().frameBytes()),
                Long.toString(allPhase.snapshot().bytes(BookFootprintSnapshot.Role.VECTORS)
                        - halfPhase.snapshot().bytes(BookFootprintSnapshot.Role.VECTORS))));
        resolution(id + ".IMPORT_100%", store, allPhase, texts, null, List.of(), checks);
        return new Experiment("OVERLAP", "import the 50% scale, then every chunk into the same Store", phases);
    }

    /** Every chunk twice in one call, against a clean single import: repeats must merge into the same atoms. */
    private static Experiment intraBatchDuplicates(List<String> texts, Path dir, BookMemorySampler sampler,
                                                   List<Check> checks) throws IOException {
        String id = "experiment.INTRA_BATCH_DUPLICATES";
        Map<String, String> expected = BookStoreReadback.expectedContents(texts);
        List<String> twice = new ArrayList<>(texts);
        twice.addAll(texts);
        List<PhaseResult> phases = new ArrayList<>();
        Path cleanStore = dir.resolve("clean");
        Path doubledStore = dir.resolve("doubled");
        BookMemorySampler.Measured<Void> clean = sampler.measure(id + ".CLEAN_ONCE", () -> {
            MonadaMemory.open(cleanStore).rememberAll(texts);
            return null;
        });
        PhaseResult cleanPhase = new Phases(cleanStore, phases).record("CLEAN_ONCE", texts, expected, clean.sample());
        structural(id + ".CLEAN_ONCE", cleanPhase, expected.size(), checks);
        BookMemorySampler.Measured<Void> doubled = sampler.measure(id + ".EVERY_CHUNK_TWICE", () -> {
            MonadaMemory.open(doubledStore).rememberAll(twice);
            return null;
        });
        PhaseResult doubledPhase = new Phases(doubledStore, phases).record("EVERY_CHUNK_TWICE", twice, expected,
                doubled.sample());
        structural(id + ".EVERY_CHUNK_TWICE", doubledPhase, expected.size(), checks);
        BookFootprintSnapshot a = cleanPhase.snapshot();
        BookFootprintSnapshot b = doubledPhase.snapshot();
        checks.add(new Check(id + ".sameStatistics", a.statistics().toString(), b.statistics().toString()));
        for (BookFootprintSnapshot.Role role : List.of(BookFootprintSnapshot.Role.MANIFEST,
                BookFootprintSnapshot.Role.VECTORS, BookFootprintSnapshot.Role.VECTOR_INDEX,
                BookFootprintSnapshot.Role.FEEDBACK)) {
            checks.add(new Check(id + ".sameBytes." + role.key, Long.toString(a.files().get(role)),
                    Long.toString(b.files().get(role))));
        }
        checks.add(new Check(id + ".sameOtherFiles", a.other().toString(), b.other().toString()));
        resolution(id + ".EVERY_CHUNK_TWICE", doubledStore, doubledPhase, twice, null, List.of(), checks);
        return new Experiment("INTRA_BATCH_DUPLICATES", "every chunk twice in one rememberAll call, against a clean"
                + " single import on its own Store", phases);
    }

    /** Snapshots one Store phase after phase, tracking which atoms it already held. */
    private static final class Phases {
        private final Path store;
        private final List<PhaseResult> out;
        private final Set<String> held = new LinkedHashSet<>();
        /** Null until the first phase: that phase has nothing to be unchanged from. */
        private String lastDigest;

        Phases(Path store, List<PhaseResult> out) {
            this.store = store;
            this.out = out;
        }

        PhaseResult record(String phase, List<String> input, Map<String, String> expected,
                           BookMemorySampler.Sample memory) throws IOException {
            int newAtoms = 0;
            long newBytes = 0;
            for (Map.Entry<String, String> atom : BookStoreReadback.expectedContents(input).entrySet()) {
                if (!held.contains(atom.getKey())) {
                    newAtoms++;
                    newBytes += utf8(atom.getValue());
                }
            }
            held.addAll(expected.keySet());
            long uniqueBytes = 0;
            long codePoints = 0;
            for (String text : expected.values()) {
                uniqueBytes += utf8(text);
                codePoints += text.codePointCount(0, text.length());
            }
            BookFootprintSnapshot snapshot = BookFootprintSnapshot.take(store);
            Boolean unchanged = lastDigest == null ? null : snapshot.treeDigest().equals(lastDigest);
            lastDigest = snapshot.treeDigest();
            long inputBytes = input.stream().mapToLong(BookFootprintBenchmark::utf8).sum();
            PhaseResult result = new PhaseResult(phase, input.size(), inputBytes, newAtoms, newBytes, expected.size(),
                    uniqueBytes, codePoints, snapshot, unchanged, memory);
            out.add(result);
            return result;
        }
    }

    /**
     * The structural checks every populated phase must pass: a HEALTHY audit and logical counts that agree with
     * each other and with the expected unique atoms (no history duplicate, no orphan, every atom with one vector, a
     * vector segment exactly one frame per entry, no feedback written).
     */
    static void structural(String id, PhaseResult phase, int expectedAtoms, List<Check> checks) {
        BookFootprintSnapshot s = phase.snapshot();
        StorageIntegrityStatistics st = s.statistics();
        String n = Integer.toString(expectedAtoms);
        checks.add(new Check(id + ".audit", "HEALTHY", s.audit().status()));
        checks.add(new Check(id + ".uniqueAtoms", n, Long.toString(st.activeUniqueAtoms())));
        checks.add(new Check(id + ".atomLogPhysicalRecords", n, Long.toString(st.atomLogPhysicalRecords())));
        checks.add(new Check(id + ".atomHistoryDuplicates", "0", Long.toString(st.atomHistoryDuplicates())));
        checks.add(new Check(id + ".vectorIndexEntries", n, Long.toString(st.vectorIndexEntries())));
        checks.add(new Check(id + ".uniqueVectorIndexIds", n, Long.toString(st.uniqueVectorIndexIds())));
        checks.add(new Check(id + ".duplicateVectorOffsets", "0", Long.toString(st.duplicateVectorOffsets())));
        checks.add(new Check(id + ".activeAtomsWithVector", n, Long.toString(st.activeAtomsWithVector())));
        checks.add(new Check(id + ".vectorEntriesWithoutActiveAtom", "0",
                Long.toString(st.vectorEntriesWithoutActiveAtom())));
        checks.add(new Check(id + ".feedbackLogRecords", "0", Long.toString(st.feedbackLogRecords())));
        checks.add(new Check(id + ".vectorSegmentBytes", Long.toString(st.vectorIndexEntries() * s.frameBytes()),
                Long.toString(s.bytes(BookFootprintSnapshot.Role.VECTORS))));
        checks.add(new Check(id + ".unattributedFiles", "{}", s.other().toString()));
    }

    /**
     * Exact-readback checks of one phase, from a single inventory read through the manifest the phase's snapshot
     * already parsed (no second audit): every text of {@code texts} (repeats included) must read back exactly from its
     * content-derived id, and, when {@code chunks} is given, so must every sidecar entry at {@code ordinals}.
     */
    private static void resolution(String id, Path store, PhaseResult phase, List<String> texts,
                                   BookChunkFiles.Loaded chunks, List<Integer> ordinals, List<Check> checks) {
        Map<String, String> contents;
        try {
            contents = BookStoreInventory.read(store, phase.snapshot().manifest()).contents();
        } catch (IOException e) {
            // An unreadable inventory resolves nothing: the checks report it instead of aborting the run.
            contents = Map.of();
        }
        int resolved = 0;
        for (String text : texts) {
            if (text.equals(contents.get(KnowledgeAtom.text(text).id()))) {
                resolved++;
            }
        }
        checks.add(new Check(id + ".chunksResolvedExactly", Integer.toString(texts.size()),
                Integer.toString(resolved)));
        if (chunks == null) {
            return;
        }
        int keys = 0;
        for (int ordinal : ordinals) {
            BookChunkFiles.Entry entry = chunks.entries().get(ordinal);
            if (entry.ordinal() == ordinal
                    && entry.text().equals(contents.get(KnowledgeAtom.text(entry.text()).id()))) {
                keys++;
            }
        }
        checks.add(new Check(id + ".sidecarKeysResolvedExactly", Integer.toString(ordinals.size()),
                Integer.toString(keys)));
    }

    /**
     * Approximate heap retained by one reopened, queried instance: heap used after an advisory GC while it is held,
     * minus the same once it is unreachable. Host- and GC-dependent; may be negative when the GC declines.
     */
    private static long retainedHeap(Path store, String probe) {
        MonadaMemory instance = MonadaMemory.open(store);
        instance.resonate(probe).topK(1).execute();
        long held = BookMemorySampler.heapUsedAfterGc();
        Reference.reachabilityFence(instance);
        instance = null;
        long dropped = BookMemorySampler.heapUsedAfterGc();
        return held - dropped;
    }

    private static long utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String digest(Path file) throws IOException {
        return VerifiedBookSource.sha256(Files.readAllBytes(file));
    }

    static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
