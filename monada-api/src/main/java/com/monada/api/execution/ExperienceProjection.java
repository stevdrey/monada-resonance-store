package com.monada.api.execution;

import com.monada.api.MonadaMemory;
import com.monada.api.MonadaMemoryOptions;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.storage.FileAtomStore;
import com.monada.storage.Manifest;
import com.monada.storage.audit.StorageIntegrityAuditor;
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
    private final Path root;
    private final ScopeId scope;
    private final boolean writable;
    private final ProjectionFaults faults;

    private ProjectionStatus.State state = ProjectionStatus.State.MISSING;
    private long covered;
    private final List<String> diagnostics = new ArrayList<>();
    private Projector projector = new Projector();
    private Set<String> uncovered = Set.of(); // atoms of an interrupted batch, outside the covered prefix
    private MonadaMemory memory;
    private ProjectionCheckpoint writer;
    private ProjectionLayout layout;
    private String openGeneration = ""; // read-only: generation of the scope files when the snapshot was taken

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
        return new ProjectionStatus(state, covered, ledgerSequence, ProjectionCheckpoint.FORMAT_VERSION, diagnostics);
    }

    // ------------------------------------------------------------------ loading and verification

    private void load(List<HistoryEntry> ledger, boolean initializeEmpty) {
        closeResources();
        state = ProjectionStatus.State.MISSING;
        covered = 0;
        diagnostics.clear();
        projector = new Projector();
        uncovered = Set.of();
        try {
            layout = ProjectionLayout.of(root, scope);
            openGeneration = layout.generation();
            Path dir = layout.projectionDir();
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                if (writable && initializeEmpty && ledger.isEmpty()) {
                    build(layout, ledger);
                    load(ledger, false);
                    return;
                }
                diagnostics.add("scope has no projection; call rebuildProjection to create it");
                return;
            }
            verify(layout, dir, ledger);
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
        if (problems.isEmpty()) {
            problems.addAll(memoryProblems(layout, memoryDir));
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
        if (problems.isEmpty()) {
            Set<String> stored = new HashSet<>();
            for (KnowledgeAtom atom : new FileAtomStore(memoryDir).findAll()) {
                stored.add(atom.id());
            }
            Set<String> known = new HashSet<>(snapshot.refsByAtom().keySet());
            known.addAll(snapshot.retiredAtoms());
            for (String atom : known) {
                if (!stored.contains(atom)) {
                    problems.add("mapped atom " + atom + " is missing from the projection memory");
                    break;
                }
            }
            stored.removeAll(known);
            extra.addAll(stored);
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
        if (!caughtUp) {
            state = ProjectionStatus.State.STALE;
            diagnostics.add("projection covers ledger sequence " + covered + " of " + ledger.size()
                    + (snapshot.cleanTail() ? "" : " and ends in an interrupted write")
                    + "; call rebuildProjection to reconcile");
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
     * {@link StorageIntegrityAuditor} must report no error (for example an atom without a vector), and the
     * manifest must name the standard segments.
     */
    private static List<String> memoryProblems(ProjectionLayout layout, Path memoryDir) throws IOException {
        List<String> problems = new ArrayList<>(layout.memoryLayoutProblems(memoryDir));
        if (!problems.isEmpty()) {
            return problems;
        }
        StorageIntegrityReport report = new StorageIntegrityAuditor().audit(memoryDir);
        report.findings().stream()
                .filter(f -> f.severity() == StorageIntegritySeverity.ERROR
                        || f.severity() == StorageIntegritySeverity.FATAL)
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
        return problems;
    }

    // ------------------------------------------------------------------ writing

    /** Projects one just-appended ledger event. Failures leave the projection STALE; nothing is retried. */
    void onAppended(HistoryEntry appended, List<HistoryEntry> ledger) {
        if (state != ProjectionStatus.State.CURRENT || writer == null) {
            return; // STALE/MISSING/INCOMPATIBLE stay so until an explicit rebuild
        }
        try {
            project(memory, writer, projector, appended);
            covered = appended.sequence();
        } catch (IOException | RuntimeException e) {
            load(ledger, false);
            if (state == ProjectionStatus.State.CURRENT) {
                return; // the write had completed before the failure surfaced
            }
            diagnostics.add(0, "projection of ledger sequence " + appended.sequence() + " failed: " + e);
        }
    }

    private void project(MonadaMemory target, ProjectionCheckpoint checkpoint, Projector p, HistoryEntry entry)
            throws IOException {
        Projector.Step step = p.apply(entry.event());
        for (var text : step.texts().entrySet()) {
            String stored = target.remember(text.getValue()).id();
            if (!stored.equals(text.getKey())) {
                throw new IllegalStateException("projection memory assigned atom " + stored + ", expected "
                        + text.getKey());
            }
        }
        faults.beforeCommit(entry.sequence());
        checkpoint.commit(entry.sequence(), ProjectionCheckpoint.ledgerDigest(entry.event()), step.entries());
    }

    /**
     * Deterministic full rebuild from the ledger, built privately and published with atomic renames; the
     * previous projection (whatever its state) is replaced only after the new one is complete.
     */
    ProjectionStatus rebuild(List<HistoryEntry> ledger) {
        if (!writable) {
            throw new UnsupportedOperationException("this execution memory is read-only");
        }
        closeResources();
        try {
            ProjectionLayout layout = ProjectionLayout.of(root, scope);
            build(layout, ledger);
        } catch (IOException e) {
            load(ledger, false);
            throw new UncheckedIOException("cannot rebuild the projection of scope " + scope, e);
        } catch (RuntimeException e) {
            load(ledger, false);
            throw e;
        }
        load(ledger, false);
        return status(ledger.size());
    }

    private void build(ProjectionLayout layout, List<HistoryEntry> ledger) throws IOException {
        Path staging = layout.stagingDir();
        Path retired = layout.retiredDir();
        Path published = layout.projectionDir();
        deleteTree(staging);
        deleteTree(retired);
        Files.createDirectory(staging);
        try {
            MonadaMemory target = MonadaMemory.open(layout.memory(staging), MonadaMemoryOptions.defaults());
            Projector p = new Projector();
            try (ProjectionCheckpoint checkpoint = ProjectionCheckpoint.create(layout.checkpoint(staging), scope)) {
                for (HistoryEntry entry : ledger) {
                    project(target, checkpoint, p, entry);
                }
            }
            if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(published, retired, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(staging);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        deleteTree(retired);
    }

    // ------------------------------------------------------------------ recall

    ExperienceRecall recall(String query, int limit, long ledgerSequence,
                            Function<ExperienceRef, Optional<HistoryEntry>> resolver) {
        if (query.isBlank() || (state != ProjectionStatus.State.CURRENT && state != ProjectionStatus.State.STALE)) {
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
            if (before.equals(layout.generation()) || attempt == ExecutionMemory.READ_ONLY_ATTEMPTS) {
                return incompatible(problem, ledgerSequence);
            }
        }
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
        int topK = limit + excluded.size();
        while (true) {
            List<ResonanceResult> results = memory.resonate(query).topK(topK).execute().results();
            List<ExperienceHit> hits = new ArrayList<>();
            int unknown = 0;
            int atoms = 0;
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
                atoms++;
                for (ExperienceRef ref : refs) {
                    Optional<HistoryEntry> event = resolver.apply(ref);
                    if (event.isEmpty()) {
                        return new Collected(List.of(), "experience ref " + ExperienceRefCodec.canonical(ref)
                                + " is missing from the ledger");
                    }
                    hits.add(new ExperienceHit(ref, atomId, result.score(), event.get(),
                            ProjectionCheckpoint.FORMAT_VERSION, covered));
                }
            }
            if (unknown == 0 || atoms >= limit || results.size() < topK) {
                return new Collected(hits, null);
            }
            topK = limit + excluded.size() + unknown; // strictly larger: fewer than limit known atoms were seen
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
