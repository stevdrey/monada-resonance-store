package com.monada.api.execution;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.ResonanceResult;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.storage.FileManifestStore;
import com.monada.storage.execution.ExperienceRefCodec;
import com.monada.storage.execution.ProjectionCheckpoint;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Derived, rebuildable projection of one scope (contract v1, sections 11 and 18): a standard {@link MonadaMemory}
 * opened with {@link MonadaMemoryOptions#defaults()} plus a {@link ProjectionCheckpoint}, published as a
 * generation directory named by an atomically replaced pointer. The ledger is always written first and stays
 * authoritative; a failed projection write leaves the projection {@code STALE} and is reconciled only by an
 * explicit {@link #rebuild}.
 *
 * <p>On open the checkpoint is verified against the ledger by recomputing the projection of the covered prefix
 * with {@link Projector}, and the memory by {@link ProjectionVerifier}. Any disagreement is {@code INCOMPATIBLE};
 * nothing is repaired automatically. A writable instance ranks through its {@code MonadaMemory}; a read-only
 * instance ranks through a {@link FrozenProjectionIndex} loaded once at open, so it is a true snapshot that later
 * writes, rebuilds, prunes or file damage never affect.
 */
final class ExperienceProjection implements AutoCloseable {
    /** Ranks atoms for a query: the writable memory, or a read-only snapshot's frozen index. */
    private interface Ranker {
        List<ResonanceResult> rank(String query, int topK);
    }

    private final Path root;
    private final ScopeId scope;
    private final boolean writable;
    private final ProjectionFaults faults;

    private ProjectionStatus.State state = ProjectionStatus.State.MISSING;
    private long covered;
    private final List<String> diagnostics = new ArrayList<>();
    private Projector projector = new Projector();
    private Set<String> uncovered = Set.of(); // atoms of an interrupted batch, outside the covered prefix
    private final Set<String> stored = new HashSet<>(); // atom IDs present in the writable projection memory
    private MonadaMemory memory; // writable only
    private Ranker ranker;
    private ProjectionCheckpoint writer;
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

    // ------------------------------------------------------------------ loading and verification

    private void load(List<HistoryEntry> ledger, boolean initializeEmpty) {
        closeResources();
        state = ProjectionStatus.State.MISSING;
        covered = 0;
        diagnostics.clear();
        projector = new Projector();
        uncovered = Set.of();
        stored.clear();
        interruptedTail = false;
        try {
            ProjectionLayout layout = ProjectionLayout.of(root, scope);
            Optional<Path> dir = layout.currentDir();
            if (dir.isEmpty()) {
                if (writable && initializeEmpty && ledger.isEmpty()) {
                    build(layout, ledger);
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
            incompatibleOnLoad(links);
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
                replayed.apply(entry.event(), Projector.Sink.NONE);
            }
        }
        if (problems.isEmpty() && (!replayed.refsByAtom().equals(snapshot.refsByAtom())
                || !replayed.retired().equals(snapshot.retiredAtoms()))) {
            problems.add("checkpoint mapping disagrees with the projection of the ledger");
        }
        if (!problems.isEmpty()) {
            incompatibleOnLoad(problems);
            return;
        }
        boolean caughtUp = snapshot.cleanTail() && snapshot.coveredSequence() == ledger.size();
        ProjectionVerifier.Result result = ProjectionVerifier.verify(layout, layout.memory(dir), snapshot, replayed,
                caughtUp);
        if (!result.problems().isEmpty()) {
            incompatibleOnLoad(result.problems());
            return;
        }
        projector = replayed;
        covered = snapshot.coveredSequence();
        uncovered = result.extra();
        if (writable) {
            memory = result.memory();
            stored.addAll(result.present());
            MonadaMemory m = memory;
            ranker = (query, topK) -> m.resonate(query).topK(topK).execute().results();
        } else {
            FrozenProjectionIndex frozen = new FrozenProjectionIndex(result.atoms(), result.vectors());
            ranker = frozen::search;
        }
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

    private void incompatibleOnLoad(List<String> problems) {
        state = ProjectionStatus.State.INCOMPATIBLE;
        diagnostics.addAll(problems);
    }

    // ------------------------------------------------------------------ writing

    /** Projects one just-appended ledger event. Failures leave the projection STALE; nothing is retried. */
    void onAppended(HistoryEntry appended, List<HistoryEntry> ledger) {
        if (state != ProjectionStatus.State.CURRENT || writer == null) {
            return; // STALE/MISSING/INCOMPATIBLE stay so until an explicit rebuild
        }
        // Entries are streamed from the projector to the checkpoint one by one: a correction touching any number
        // of attempts never materializes its whole batch. Each new atom is stored before its PROJECT line.
        try (ProjectionCheckpoint.Batch batch = writer.begin(appended.sequence())) {
            projector.apply(appended.event(), (entry, text) -> {
                if (text != null && !stored.contains(entry.atomId())) { // never rediscover a stored atom
                    remember(memory, entry.atomId(), text);
                    stored.add(entry.atomId());
                }
                batch.write(entry);
            });
            faults.beforeCommit(appended.sequence());
            batch.cover(ProjectionCheckpoint.ledgerDigest(appended.event()));
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
        closeResources();
        List<String> cleanup;
        try {
            cleanup = build(ProjectionLayout.of(root, scope), ledger);
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
     * Builds a new generation: the memory is created first and its dimension checked against the format; the
     * checkpoint streams every ledger event's entries (the same entries an incremental projection writes, so
     * verification is unchanged), and the memory stores only the atoms live at the end, in atom ID order; retired
     * atoms are compacted away. The generation gets a fresh name no reader references and is published by
     * atomically replacing the pointer; a failure before publication deletes it and leaves the pointer.
     * Afterwards, best effort, every other generation is reclaimed (read-only snapshots are frozen in memory and
     * never read generation files after opening), including generations orphaned by an interrupted build.
     */
    private List<String> build(ProjectionLayout layout, List<HistoryEntry> ledger) throws IOException {
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
                    try (ProjectionCheckpoint.Batch batch = checkpoint.begin(entry.sequence())) {
                        p.apply(entry.event(), (e, text) -> batch.write(e));
                        faults.beforeCommit(entry.sequence());
                        batch.cover(ProjectionCheckpoint.ledgerDigest(entry.event()));
                    }
                }
            }
            for (var live : p.liveTexts().entrySet()) {
                remember(target, live.getKey(), live.getValue());
            }
            faults.beforePublish();
            layout.publish(dir);
        } catch (IOException | RuntimeException e) {
            try {
                ProjectionLayout.deleteEntry(dir);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        // Published: from here on the rebuild has succeeded. Cleanup is best effort and reported, never thrown,
        // so a caller can tell a committed rebuild from one that did not happen.
        return reclaim(layout, dir, g -> true);
    }

    /**
     * Explicit reclamation: deletes every generation except the published one (all of them when nothing is
     * published), invalid generation entries and a leftover temporary pointer. Rebuilds already do this; prune
     * retries a cleanup that failed. Best effort: failures are returned as diagnostics, never thrown.
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
    private List<String> reclaim(ProjectionLayout layout, Path keep, Predicate<Path> delete) {
        List<String> problems = new ArrayList<>();
        try {
            for (Path generation : layout.generationDirs()) {
                if (!generation.equals(keep) && delete.test(generation)) {
                    try {
                        faults.beforeDeleteGeneration(generation);
                        ProjectionLayout.deleteEntry(generation);
                    } catch (IOException | RuntimeException e) {
                        problems.add("could not delete old generation " + generation.getFileName() + ": "
                                + e.getMessage() + "; the next rebuild or pruneProjection retries the cleanup");
                    }
                }
            }
            for (Path invalid : layout.invalidGenerationEntries()) {
                try {
                    ProjectionLayout.deleteEntry(invalid); // removes a link itself, never its target
                } catch (IOException | RuntimeException e) {
                    problems.add("could not remove invalid entry " + invalid.getFileName() + ": " + e.getMessage());
                }
            }
            ProjectionLayout.deleteEntry(layout.pointerTemp());
        } catch (IOException | RuntimeException e) {
            problems.add("cleanup failed: " + e.getMessage());
        }
        return problems;
    }

    // ------------------------------------------------------------------ recall

    /**
     * Bounded recall. One ranker call suffices: the top-K is padded by every atom that may rank but yields no hit
     * (retired and uncovered atoms), and every other ranked atom has at least one ref. A read-only snapshot ranks
     * its frozen index, so no file is read and nothing written later is visible.
     */
    ExperienceRecall recall(String query, int limit, long ledgerSequence,
                            Function<ExperienceRef, Optional<HistoryEntry>> resolver) {
        if (query.isBlank() || (state != ProjectionStatus.State.CURRENT && state != ProjectionStatus.State.STALE)) {
            return new ExperienceRecall(status(ledgerSequence), List.of());
        }
        int topK = (int) Math.min(Integer.MAX_VALUE, (long) limit + projector.retiredCount() + uncovered.size());
        List<ExperienceHit> hits = new ArrayList<>();
        for (ResonanceResult result : ranker.rank(query, topK)) {
            String atomId = result.atom().id();
            if (projector.isRetired(atomId) || uncovered.contains(atomId)) {
                continue;
            }
            List<ExperienceRef> refs = projector.refs(atomId);
            if (refs.isEmpty()) {
                return incompatible("recalled atom " + atomId + " has no experience refs", ledgerSequence);
            }
            for (ExperienceRef ref : refs) {
                Optional<HistoryEntry> event = resolver.apply(ref);
                if (event.isEmpty()) {
                    return incompatible("experience ref " + ExperienceRefCodec.canonical(ref)
                            + " is missing from the ledger", ledgerSequence);
                }
                hits.add(new ExperienceHit(ref, atomId, result.score(), event.get(),
                        ProjectionCheckpoint.FORMAT_VERSION, covered));
                if (hits.size() == limit) {
                    // Atom-major ranker order with canonically ordered refs: these are the first limit hits.
                    return new ExperienceRecall(status(ledgerSequence), order(hits, limit));
                }
            }
        }
        return new ExperienceRecall(status(ledgerSequence), order(hits, limit));
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
        ranker = null;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // a failed close loses nothing: every commit was forced before returning
            }
            writer = null;
        }
    }
}
