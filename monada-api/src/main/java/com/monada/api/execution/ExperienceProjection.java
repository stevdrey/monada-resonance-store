package com.monada.api.execution;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.encoder.SimpleFrequencyEncoder;
import com.monada.encoder.WeightedAtomEncoder;
import com.monada.storage.FileAtomStore;
import com.monada.storage.FileFrequencyStore;
import com.monada.storage.FileManifestStore;
import com.monada.storage.StoredVector;
import com.monada.storage.VectorFormatProfile;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
import com.monada.storage.audit.StorageIntegrityCategory;
import com.monada.storage.audit.StorageIntegrityReport;
import com.monada.storage.audit.StorageIntegritySeverity;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.ProjectionCheckpoint;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Derived, rebuildable projection of one scope (contract v1, section 11): a standard {@link MonadaMemory}
 * opened with {@link MonadaMemoryOptions#defaults()} plus a {@link ProjectionCheckpoint}. The ledger is
 * always written first and stays authoritative; a failed projection write leaves the projection
 * {@code STALE} and is reconciled only by an explicit {@link #rebuild}.
 *
 * <p>On open the checkpoint is verified against the ledger by recomputing the projection of the covered
 * prefix with {@link Projector}: digests, mapping and retired atoms must match, and every mapped atom must
 * exist in the memory. Any disagreement is {@code INCOMPATIBLE}; nothing is repaired automatically.
 */
final class ExperienceProjection implements AutoCloseable {
    /** Manifest version of every projection memory (the current {@code MonadaMemory} format). */
    private static final String PROJECTION_MANIFEST_VERSION = "0.4";
    private final Path root;
    private final ScopeId scope;
    private final boolean writable;
    private final ProjectionFaults faults;

    private ProjectionStatus.State state = ProjectionStatus.State.MISSING;
    private long covered;
    private final List<String> diagnostics = new ArrayList<>();
    private Projector projector = new Projector();
    private Set<String> uncovered = Set.of(); // atoms of an interrupted batch, outside the covered prefix
    private final Set<String> stored = new HashSet<>(); // atom IDs present in the projection memory
    private MonadaMemory memory;
    private ProjectionCheckpoint writer;
    private ProjectionLayout layout;
    private String openGeneration = ""; // read-only: generation of the scope files when the snapshot was taken
    private Path loadedDir; // generation directory this instance loaded
    private boolean interruptedTail; // STALE: the checkpoint ends in an interrupted write

    private ExperienceProjection(Path root, ScopeId scope, boolean writable, ProjectionFaults faults) {
        this.root = root;
        this.scope = scope;
        this.writable = writable;
        this.faults = faults;
    }

    /**
     * Loads the projection of {@code scope}. A writable open initializes the projection only for an empty
     * ledger without one; a non-empty ledger without a projection is {@code MISSING} until rebuilt. A
     * read-only open never creates a projection.
     */
    static ExperienceProjection open(Path root, ScopeId scope, List<HistoryEntry> ledger, boolean writable,
                                     ProjectionFaults faults) {
        ExperienceProjection p = new ExperienceProjection(root, scope, writable, faults);
        p.load(ledger, true);
        return p;
    }

    ProjectionStatus.State state() {
        return state;
    }

    /** Adds a leading diagnostic explaining the current state. */
    void note(String diagnostic) {
        diagnostics.add(0, diagnostic);
    }

    ProjectionStatus status(long ledgerSequence) {
        List<String> all = new ArrayList<>(diagnostics);
        if (state == ProjectionStatus.State.STALE) {
            // Computed now, so it always names the current ledger end, however many events were appended since.
            all.add("projection covers ledger sequence " + covered + " of " + ledgerSequence
                    + (interruptedTail ? " and ends in an interrupted write" : "")
                    + "; call rebuildProjection to reconcile");
        }
        return new ProjectionStatus(state, covered, ledgerSequence, ProjectionCheckpoint.FORMAT_VERSION, all);
    }

    /**
     * Status for {@code projectionStatus}: a read-only snapshot first checks, without touching any file, whether
     * its generation was reclaimed by {@code pruneProjection}, and then reports {@code MISSING}.
     */
    ProjectionStatus currentStatus(long ledgerSequence) {
        if (!writable && (state == ProjectionStatus.State.CURRENT || state == ProjectionStatus.State.STALE)
                && reclaimed(false)) {
            markReclaimed();
        }
        return status(ledgerSequence);
    }

    // ------------------------------------------------------------------ loading and verification

    private void load(List<HistoryEntry> ledger, boolean initializeEmpty) {
        closeResources();
        state = ProjectionStatus.State.MISSING;
        covered = 0;
        diagnostics.clear();
        projector = new Projector();
        uncovered = Set.of();
        stored.clear();
        loadedDir = null;
        interruptedTail = false;
        try {
            layout = ProjectionLayout.of(root, scope);
            openGeneration = layout.generation();
            Optional<Path> dir = layout.currentDir();
            if (dir.isEmpty()) {
                if (writable && initializeEmpty && ledger.isEmpty()) {
                    build(layout, ledger, false);
                    load(ledger, false);
                    return;
                }
                diagnostics.add("scope has no projection; call rebuildProjection to create it");
                return;
            }
            if (!Files.isDirectory(dir.get(), LinkOption.NOFOLLOW_LINKS)) {
                state = ProjectionStatus.State.INCOMPATIBLE;
                diagnostics.add(ProjectionLayout.POINTER_FILE + " names a missing generation "
                        + dir.get().getFileName());
                return;
            }
            loadedDir = dir.get();
            verify(layout, dir.get(), ledger);
        } catch (IOException | RuntimeException e) {
            closeResources();
            state = ProjectionStatus.State.INCOMPATIBLE;
            diagnostics.add("projection cannot be loaded: " + e.getMessage());
        }
    }

    private void verify(ProjectionLayout layout, Path dir, List<HistoryEntry> ledger) throws IOException {
        // Fail closed before reading anything through a link that could leave the ledger root.
        List<String> links = layout.linkProblems(dir);
        if (!links.isEmpty()) {
            state = ProjectionStatus.State.INCOMPATIBLE;
            diagnostics.addAll(links);
            return;
        }
        ProjectionCheckpoint.Snapshot snapshot = ProjectionCheckpoint.read(layout.checkpoint(dir), scope);
        List<String> problems = new ArrayList<>(snapshot.problems());
        if (problems.isEmpty() && snapshot.coveredSequence() > ledger.size()) {
            problems.add("checkpoint covers ledger sequence " + snapshot.coveredSequence()
                    + " but the ledger ends at " + ledger.size());
        }
        Projector replayed = new Projector();
        // coverDigests holds exactly one digest per covered sequence, so its int size bounds the loop.
        List<String> digests = snapshot.coverDigests();
        for (int i = 0; problems.isEmpty() && i < digests.size(); i++) {
            HistoryEntry entry = ledger.get(i);
            if (!ProjectionCheckpoint.ledgerDigest(entry.event()).equals(digests.get(i))) {
                problems.add("checkpoint was built from a different ledger at sequence " + entry.sequence());
            } else {
                replayed.apply(entry.event());
            }
        }
        if (problems.isEmpty() && (!replayed.refsByAtom().equals(snapshot.refsByAtom())
                || !replayed.retired().equals(snapshot.retiredAtoms()))) {
            problems.add("checkpoint mapping disagrees with the projection of the ledger");
        }
        Path memoryDir = layout.memory(dir);
        Manifest manifest = null;
        if (problems.isEmpty()) {
            MemoryCheck check = memoryProblems(layout, memoryDir, snapshot.dimensions());
            problems.addAll(check.problems());
            manifest = check.manifest();
        }
        MonadaMemory opened = null;
        if (problems.isEmpty()) {
            try {
                opened = MonadaMemory.open(memoryDir, MonadaMemoryOptions.defaults());
            } catch (RuntimeException e) {
                problems.add("projection memory is incompatible: " + e.getMessage());
            }
        }
        Set<String> extra = new TreeSet<>();
        Set<String> present = new HashSet<>();
        if (problems.isEmpty()) {
            for (KnowledgeAtom atom : new FileAtomStore(memoryDir).findAll()) {
                present.add(atom.id());
            }
            // Live atoms must be stored; retired atoms may be absent (a rebuild compacts them away).
            for (String atom : snapshot.refsByAtom().keySet()) {
                if (!present.contains(atom)) {
                    problems.add("mapped atom " + atom + " is missing from the projection memory");
                    break;
                }
            }
            extra.addAll(present);
            extra.removeAll(snapshot.refsByAtom().keySet());
            extra.removeAll(snapshot.retiredAtoms());
        }
        if (problems.isEmpty()) {
            vectorProblem(memoryDir, manifest, replayed).ifPresent(problems::add);
        }
        boolean caughtUp = snapshot.cleanTail() && snapshot.coveredSequence() == ledger.size();
        if (problems.isEmpty() && caughtUp && !extra.isEmpty()) {
            problems.add("projection memory holds atoms without experience refs: " + extra.iterator().next());
        }
        if (!problems.isEmpty()) {
            state = ProjectionStatus.State.INCOMPATIBLE;
            diagnostics.addAll(problems);
            return;
        }
        memory = opened;
        projector = replayed;
        covered = snapshot.coveredSequence();
        uncovered = Set.copyOf(extra);
        stored.addAll(present);
        if (!caughtUp) {
            state = ProjectionStatus.State.STALE; // the coverage diagnostic is computed by status()
            interruptedTail = !snapshot.cleanTail();
            return;
        }
        state = ProjectionStatus.State.CURRENT;
        if (writable) {
            writer = ProjectionCheckpoint.openForAppend(layout.checkpoint(dir), scope, snapshot);
        }
    }

    /**
     * Read-only checks of the projection memory, run before {@link MonadaMemory#open} because opening would
     * recreate missing directories and logs: the fixed layout must be complete, the existing side-effect-free
     * {@link StorageIntegrityAuditor} must report no error (for example an atom without a vector) and no
     * duplicate vector, and the
     * manifest must name the standard segments. The feedback log must be empty, so the default feedback-aware
     * ranking is a no-op and scores depend on the ledger alone.
     */
    private record MemoryCheck(List<String> problems, Manifest manifest) {
    }

    private static MemoryCheck memoryProblems(ProjectionLayout layout, Path memoryDir, int dimensions)
            throws IOException {
        List<String> problems = new ArrayList<>(layout.memoryLayoutProblems(memoryDir));
        if (!problems.isEmpty()) {
            return new MemoryCheck(problems, null);
        }
        if (Files.size(memoryDir.resolve(ProjectionLayout.FEEDBACK_SEGMENT)) != 0) {
            // Feedback would change ranking while the checkpoint still matches the ledger.
            problems.add("projection memory holds retrieval feedback; projections are derived from the ledger only");
            return new MemoryCheck(problems, null);
        }
        StorageIntegrityReport report = new StorageIntegrityAuditor().audit(memoryDir);
        report.findings().stream()
                .filter(f -> f.severity() == StorageIntegritySeverity.ERROR
                        || f.severity() == StorageIntegritySeverity.FATAL
                        // Projection writes never index an atom or a frame twice; a duplicate would let an
                        // extra frame change the atom's score.
                        || f.category() == StorageIntegrityCategory.DUPLICATE_VECTOR_INDEX_ID
                        || f.category() == StorageIntegrityCategory.DUPLICATE_VECTOR_OFFSET)
                .limit(3)
                .forEach(f -> problems.add("projection memory " + f.category() + " in " + f.target() + ": "
                        + f.message()));
        Manifest manifest = report.manifest();
        if (problems.isEmpty() && (manifest == null
                || !ProjectionLayout.ATOM_SEGMENT.equals(manifest.atomSegment())
                || !ProjectionLayout.VECTOR_SEGMENT.equals(manifest.vectorSegment())
                || !ProjectionLayout.FEEDBACK_SEGMENT.equals(manifest.feedbackSegment()))) {
            problems.add("projection memory manifest does not name the standard projection segments");
        }
        // Projections are always written as current-format stores with the production defaults; a legacy or
        // different profile would encode queries with semantics the stored vectors were not built with.
        // The dimension is pinned by the checkpoint header written when the generation was built, never taken
        // from the (possibly rewritten) manifest itself.
        if (problems.isEmpty() && manifest.dimensions() != dimensions) {
            problems.add("projection memory dimension " + manifest.dimensions()
                    + " differs from the generation's dimension " + dimensions);
        }
        if (problems.isEmpty() && (!PROJECTION_MANIFEST_VERSION.equals(manifest.version())
                || !MonadaMemory.getExpectedProfile(dimensions, MonadaMemoryOptions.defaults())
                        .equals(manifest.encodingProfile())
                || !VectorFormatProfile.currentFixedRaw(dimensions).equals(manifest.vectorFormatProfile()))) {
            problems.add("projection memory manifest is not a " + PROJECTION_MANIFEST_VERSION
                    + " store with the default encoding and vector profile");
        }
        return new MemoryCheck(problems, manifest);
    }

    /**
     * Re-encodes the projection text of every live atom with the production pipeline ({@link WeightedAtomEncoder},
     * the side-effect-free helper {@code MonadaMemory.remember} itself relies on, and the manifest's encoder
     * dimensions) and compares it with the stored vector, so a structurally valid but different frame cannot
     * silently change scores. Reads only; the store layout and manifest were verified before.
     */
    private static Optional<String> vectorProblem(Path memoryDir, Manifest manifest, Projector replayed)
            throws IOException {
        Map<String, float[]> vectors = new HashMap<>();
        for (StoredVector v : FileFrequencyStore.openExisting(memoryDir, ProjectionLayout.VECTOR_SEGMENT,
                manifest.vectorFormatProfile()).findAll()) {
            vectors.put(v.atomId(), v.vector().values());
        }
        MonadaMemoryOptions options = MonadaMemoryOptions.defaults();
        SimpleFrequencyEncoder encoder = new SimpleFrequencyEncoder(manifest.dimensions());
        for (var live : replayed.liveTexts().entrySet()) {
            float[] expected = encoder.encode(WeightedAtomEncoder.toWeightedText(KnowledgeAtom.text(live.getValue()),
                    options.textNormalizer(), options.expansionOptions())).values();
            if (!Arrays.equals(expected, vectors.get(live.getKey()))) {
                return Optional.of("stored vector of atom " + live.getKey()
                        + " does not match its projected text");
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ writing

    /** Projects one just-appended ledger event. Failures leave the projection STALE; nothing is retried. */
    void onAppended(HistoryEntry appended, List<HistoryEntry> ledger) {
        if (state != ProjectionStatus.State.CURRENT || writer == null) {
            return; // STALE/MISSING/INCOMPATIBLE stay so until an explicit rebuild
        }
        try {
            Projector.Step step = projector.apply(appended.event());
            for (var text : step.texts().entrySet()) {
                if (!stored.contains(text.getKey())) { // never rediscover a stored atom by scanning the log
                    remember(memory, text.getKey(), text.getValue());
                    stored.add(text.getKey());
                }
            }
            faults.beforeCommit(appended.sequence());
            writer.commit(appended.sequence(), ProjectionCheckpoint.ledgerDigest(appended.event()), step.entries());
            covered = appended.sequence();
        } catch (IOException | RuntimeException e) {
            load(ledger, false);
            if (state == ProjectionStatus.State.CURRENT) {
                return; // the write had completed before the failure surfaced
            }
            diagnostics.add(0, "projection of ledger sequence " + appended.sequence() + " failed: " + e);
        }
    }

    private static void remember(MonadaMemory target, String atomId, String text) {
        String assigned = target.remember(text).id();
        if (!assigned.equals(atomId)) {
            throw new IllegalStateException("projection memory assigned atom " + assigned + ", expected " + atomId);
        }
    }

    /**
     * Deterministic full rebuild from the ledger into a new generation, published by replacing the pointer only
     * once complete; the previous projection (whatever its state) stays published until then.
     */
    ProjectionStatus rebuild(List<HistoryEntry> ledger) {
        if (!writable) {
            throw new UnsupportedOperationException("this execution memory is read-only");
        }
        // A sound previous generation may back open read-only snapshots and is kept until pruneProjection; an
        // unsound one backs no snapshot memory and is reclaimed by this rebuild.
        boolean previousSound = state == ProjectionStatus.State.CURRENT || state == ProjectionStatus.State.STALE;
        closeResources();
        List<String> cleanup;
        try {
            ProjectionLayout layout = ProjectionLayout.of(root, scope);
            cleanup = build(layout, ledger, previousSound);
        } catch (IOException e) {
            load(ledger, false);
            throw new UncheckedIOException("cannot rebuild the projection of scope " + scope, e);
        } catch (RuntimeException e) {
            load(ledger, false);
            throw e;
        }
        load(ledger, false);
        return withNotes(status(ledger.size()), cleanup);
    }

    /** {@code status} plus transient notes of the operation that produced it (not kept in later statuses). */
    private static ProjectionStatus withNotes(ProjectionStatus status, List<String> notes) {
        List<String> all = new ArrayList<>(status.diagnostics());
        all.addAll(notes);
        return new ProjectionStatus(status.state(), status.coveredSequence(), status.ledgerSequence(),
                status.projectionVersion(), all);
    }

    /**
     * Builds a new generation: the memory is created first and its dimension is pinned in the checkpoint header;
     * the checkpoint replays every ledger event (the same entries an incremental projection writes, so
     * verification is unchanged), and the memory stores only the atoms live at the end, in atom ID order; retired
     * atoms are compacted away. The generation gets a fresh name no reader references and is published by
     * atomically replacing the pointer. A failure before publication deletes the new generation and leaves the
     * pointer. Afterwards, best effort, invalid generation entries are removed and the previous generation is
     * reclaimed only when it was unsound; sound older generations stay until {@link #prune}.
     */
    private List<String> build(ProjectionLayout layout, List<HistoryEntry> ledger, boolean previousSound)
            throws IOException {
        Optional<Path> previous;
        try {
            previous = layout.currentDir();
        } catch (IOException e) {
            previous = Optional.empty(); // an unreadable pointer is replaced by this rebuild
        }
        Path dir = layout.nextGenerationDir();
        Files.createDirectory(dir);
        try {
            Path memoryDir = layout.memory(dir);
            MonadaMemory target = MonadaMemory.open(memoryDir, MonadaMemoryOptions.defaults());
            int dimensions = new FileManifestStore(memoryDir).load()
                    .orElseThrow(() -> new IOException("new projection memory has no manifest")).dimensions();
            if (dimensions != ProjectionCheckpoint.PROJECTION_DIMENSIONS) {
                throw new IllegalStateException("MonadaMemory created a " + dimensions + "-dimensional store, but "
                        + "projection format " + ProjectionCheckpoint.FORMAT_VERSION + " is "
                        + ProjectionCheckpoint.PROJECTION_DIMENSIONS + "-dimensional; a new format version is required");
            }
            Projector p = new Projector();
            try (ProjectionCheckpoint checkpoint = ProjectionCheckpoint.create(layout.checkpoint(dir), scope,
                    dimensions)) {
                for (HistoryEntry entry : ledger) {
                    Projector.Step step = p.apply(entry.event());
                    faults.beforeCommit(entry.sequence());
                    checkpoint.commit(entry.sequence(), ProjectionCheckpoint.ledgerDigest(entry.event()),
                            step.entries());
                }
            }
            for (var live : p.liveTexts().entrySet()) {
                remember(target, live.getKey(), live.getValue());
            }
            faults.beforePublish();
            layout.publish(dir);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(dir);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        // Published: from here on the rebuild has succeeded. Cleanup is best effort and reported, never thrown,
        // so a caller can tell a committed rebuild from one that did not happen.
        Optional<Path> unsound = previousSound ? Optional.empty() : previous;
        return reclaim(layout, dir, g -> unsound.map(g::equals).orElse(false));
    }

    /**
     * Explicit reclamation: deletes every generation except the published one (all of them when nothing is
     * published), invalid generation entries and a leftover temporary pointer. The host calls it when no
     * read-only snapshot is open; a snapshot whose generation is deleted reports {@code MISSING}. Best effort:
     * failures are returned as diagnostics, never thrown.
     */
    ProjectionStatus prune(long ledgerSequence) {
        if (!writable) {
            throw new UnsupportedOperationException("this execution memory is read-only");
        }
        List<String> notes = new ArrayList<>();
        try {
            ProjectionLayout layout = ProjectionLayout.of(root, scope);
            Optional<Path> current;
            try {
                current = layout.currentDir();
            } catch (IOException e) {
                notes.add("the projection pointer is unreadable (" + e.getMessage()
                        + "); rebuild before pruning, nothing was deleted");
                return withNotes(status(ledgerSequence), notes);
            }
            notes.addAll(reclaim(layout, current.orElse(null), g -> true));
        } catch (IOException | RuntimeException e) {
            notes.add("pruning failed: " + e.getMessage());
        }
        return withNotes(status(ledgerSequence), notes);
    }

    /** Deletes generations other than {@code keep} that {@code delete} selects, invalid entries and the temp pointer. */
    private List<String> reclaim(ProjectionLayout layout, Path keep, java.util.function.Predicate<Path> delete) {
        List<String> problems = new ArrayList<>();
        try {
            for (Path generation : layout.generationDirs()) {
                if (!generation.equals(keep) && delete.test(generation)) {
                    try {
                        faults.beforeDeleteGeneration(generation);
                        deleteTree(generation);
                    } catch (IOException | RuntimeException e) {
                        problems.add("could not delete old generation " + generation.getFileName() + ": "
                                + e.getMessage() + "; pruneProjection retries the cleanup");
                    }
                }
            }
            for (Path invalid : layout.invalidGenerationEntries()) {
                try {
                    Files.delete(invalid); // removes a link itself, never its target
                } catch (IOException | RuntimeException e) {
                    problems.add("could not remove invalid entry " + invalid.getFileName() + ": " + e.getMessage());
                }
            }
            Files.deleteIfExists(layout.pointerTemp());
        } catch (IOException | RuntimeException e) {
            problems.add("cleanup failed: " + e.getMessage());
        }
        return problems;
    }

    // ------------------------------------------------------------------ recall

    ExperienceRecall recall(String query, int limit, long ledgerSequence,
                            Function<ExperienceRef, Optional<HistoryEntry>> resolver) {
        boolean usable = state == ProjectionStatus.State.CURRENT || state == ProjectionStatus.State.STALE;
        if (!writable && usable && reclaimed(false)) {
            return reclaimedRecall(ledgerSequence); // even for a blank query: the status must say MISSING
        }
        if (query.isBlank() || !usable) {
            return new ExperienceRecall(status(ledgerSequence), List.of());
        }
        if (writable) { // the single writer: nothing changes the files behind this instance
            Collected c = collect(query, limit, resolver, false);
            return c.problem() == null ? new ExperienceRecall(status(ledgerSequence), order(c.hits(), limit))
                    : incompatible(c.problem(), ledgerSequence);
        }
        // A read-only snapshot may run beside a live writer that keeps appending to the projection files. Atoms
        // projected after the snapshot are skipped (they never consume a slot), and a read that fails or looks
        // inconsistent while the files are changing is retried; stable damage is reported at once.
        for (int attempt = 1; ; attempt++) {
            String before = layout.generation();
            faults.beforeReadOnlyQuery(attempt);
            boolean grown = !before.equals(openGeneration);
            String problem;
            try {
                Collected c = collect(query, limit, resolver, grown);
                if (c.problem() == null) {
                    return new ExperienceRecall(status(ledgerSequence), order(c.hits(), limit));
                }
                problem = c.problem();
            } catch (RuntimeException e) {
                problem = "projection memory cannot be read: " + e.getMessage();
            }
            if (reclaimed(true)) {
                return reclaimedRecall(ledgerSequence);
            }
            if (before.equals(layout.generation()) || attempt == ExecutionMemory.READ_ONLY_ATTEMPTS) {
                return incompatible(problem, ledgerSequence);
            }
        }
    }

    /**
     * True when this snapshot's generation was deleted, or (after a failed read, {@code afterFailure}) is no
     * longer the published one and is therefore being reclaimed by {@code pruneProjection}.
     */
    private boolean reclaimed(boolean afterFailure) {
        if (loadedDir == null) {
            return false;
        }
        if (!Files.isDirectory(loadedDir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (!afterFailure) {
            return false;
        }
        try {
            return !layout.currentDir().map(loadedDir::equals).orElse(false);
        } catch (IOException e) {
            return false;
        }
    }

    private ExperienceRecall reclaimedRecall(long ledgerSequence) {
        markReclaimed();
        return new ExperienceRecall(status(ledgerSequence), List.of());
    }

    private void markReclaimed() {
        closeResources();
        state = ProjectionStatus.State.MISSING;
        diagnostics.add(0, "this snapshot's projection generation " + loadedDir.getFileName()
                + " was reclaimed (pruneProjection); reopen the snapshot");
    }

    private record Collected(List<ExperienceHit> hits, String problem) {
    }

    /**
     * Runs the ranker and expands atoms to refs. With {@code skipUnknown}, atoms the frozen projector does not
     * know (projected after a read-only snapshot) are skipped and the ranker is asked again with a larger
     * top-K until {@code limit} known atoms are found or the store is exhausted; otherwise such an atom is a
     * problem. The order of known atoms is unaffected: each atom is scored independently.
     */
    private Collected collect(String query, int limit, Function<ExperienceRef, Optional<HistoryEntry>> resolver,
                              boolean skipUnknown) {
        Set<String> excluded = new HashSet<>(projector.retired());
        excluded.addAll(uncovered);
        // Only excluded atoms that are actually stored can occupy ranker slots (a rebuild compacts retired ones).
        int padding = (int) excluded.stream().filter(stored::contains).count();
        int topK = limit + padding;
        while (true) {
            List<ResonanceResult> results = memory.resonate(query).topK(topK).execute().results();
            List<ExperienceHit> hits = new ArrayList<>();
            faults.onRecallRound(topK);
            int unknown = 0;
            for (ResonanceResult result : results) {
                String atomId = result.atom().id();
                if (excluded.contains(atomId)) {
                    continue;
                }
                List<ExperienceRef> refs = projector.refs(atomId);
                if (refs.isEmpty()) {
                    if (!skipUnknown) {
                        return new Collected(List.of(), "recalled atom " + atomId + " has no experience refs");
                    }
                    unknown++;
                    continue;
                }
                for (ExperienceRef ref : refs) {
                    Optional<HistoryEntry> event = resolver.apply(ref);
                    if (event.isEmpty()) {
                        return new Collected(List.of(), "experience ref " + ExperienceRefCodec.canonical(ref)
                                + " is missing from the ledger");
                    }
                    hits.add(new ExperienceHit(ref, atomId, result.score(), event.get(),
                            ProjectionCheckpoint.FORMAT_VERSION, covered));
                    if (hits.size() == limit) {
                        // Atom-major order with canonically ordered refs: these are the first limit hits.
                        return new Collected(hits, null);
                    }
                }
            }
            // Ranker order is the atom-major prefix of the total order, so once limit refs are collected no unseen
            // atom can displace them.
            if (unknown == 0 || hits.size() >= limit || results.size() < topK) {
                return new Collected(hits, null);
            }
            topK = limit + padding + unknown; // strictly larger: fewer than limit refs were collected
        }
    }

    /**
     * Total experience order: similarity descending, atom ID ascending (the ranker's tie order), canonical
     * ref ascending; then the first {@code limit}. Atoms arrive already truncated to the ranker's top-K, which
     * never changes the result because the order is atom-major and every atom has at least one ref.
     */
    static List<ExperienceHit> order(List<ExperienceHit> hits, int limit) {
        List<ExperienceHit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble(ExperienceHit::similarity).reversed()
                .thenComparing(ExperienceHit::atomId)
                .thenComparing(h -> ExperienceRefCodec.canonical(h.ref())));
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    private ExperienceRecall incompatible(String why, long ledgerSequence) {
        closeResources();
        state = ProjectionStatus.State.INCOMPATIBLE;
        diagnostics.add(why);
        return new ExperienceRecall(status(ledgerSequence), List.of());
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() {
        closeResources();
    }

    private void closeResources() {
        memory = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // a failed close loses nothing: every commit was forced before returning
            }
            writer = null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
