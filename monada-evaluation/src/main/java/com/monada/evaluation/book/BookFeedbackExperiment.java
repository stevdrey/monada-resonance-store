package com.monada.evaluation.book;

import com.monada.api.MonadaMemory;
import com.monada.core.KnowledgeAtom;
import com.monada.core.ResonanceResult;
import com.monada.evaluation.book.BookFeedbackPlan.Event;
import com.monada.evaluation.book.BookFeedbackPlan.Plan;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.storage.JsonStrings;
import com.monada.storage.Manifest;
import com.monada.storage.feedback.FeedbackSignal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit-feedback A/B and explicit new note over the frozen book corpus (Issue #170, Book 8/8; evaluation only).
 *
 * <p>One run seeds three isolated Stores from the same verified chunk set, each in its own guarded work directory:
 * <ul>
 *   <li><b>control</b>: no feedback, no note; measured with {@link BookRecallEvaluator} and, when a baseline is
 *   given, required to reproduce it byte for byte;</li>
 *   <li><b>treatment</b>: the predeclared {@link BookFeedbackPlan} applied through the public
 *   {@link MonadaMemory#feedback(String, String, FeedbackSignal)} API, then ranked on the same instance and measured
 *   again on fresh reopened instances (persist/reopen parity);</li>
 *   <li><b>note</b>: the {@link BookFeedbackNote} added with {@link MonadaMemory#remember(String)}, then read back
 *   from disk on fresh instances next to every original chunk.</li>
 * </ul>
 * Contamination is checked, not assumed: HOLDOUT cases, DEV cases without feedback and predeclared confusable
 * variants of feedback queries must rank exactly as in control under the default exact query keys. Every check is
 * recorded; nothing here requires the metrics to improve.
 */
final class BookFeedbackExperiment {
    /** An id that is no content-derived atom of any chunk: the invalid-atom guard must reject it. */
    static final String UNKNOWN_ATOM = "00000000-0000-0000-0000-000000000000";
    static final String GUARD_QUERY = "invalid-atom guard probe";
    static final double POSITIVE_DELTA = 0.05;
    static final double NEGATIVE_DELTA = -0.05;

    private BookFeedbackExperiment() {
    }

    /** Records one planned event on the treatment instance. */
    @FunctionalInterface
    interface FeedbackSink {
        void apply(MonadaMemory memory, Event event);
    }

    /** The public API, exact query text, default delta: the only sink the command uses. */
    static final FeedbackSink PUBLIC_API = (memory, e) -> memory.feedback(e.query(), e.atomId(), e.signal());

    /** One hard check: the run fails when any check fails. */
    record Check(String name, boolean passed, String detail) {
    }

    /** How a confusable probe was derived from its feedback query (rule fixed before any run). */
    enum ProbeKind {
        /** The feedback query lower-cased: same words, a different exact key. */
        CASE_FOLDED,
        /** The feedback query behind a neutral lead-in: a light rewording of the same question. */
        REWORDED
    }

    /** A confusable variant of a DEV feedback query, ranked on control and on treatment. */
    record Probe(String id, ProbeKind kind, Case source, String text, Set<String> evidenceAtoms, List<Hit> control,
                 List<Hit> treatment) {
        Probe {
            evidenceAtoms = Set.copyOf(evidenceAtoms);
            control = List.copyOf(control);
            treatment = List.copyOf(treatment);
        }

        boolean identical() {
            return control.equals(treatment);
        }

        /** The rank of the first evidence atom of the source case in {@code hits}, 0 when none. */
        int evidenceRank(List<Hit> hits) {
            for (int i = 0; i < hits.size(); i++) {
                if (evidenceAtoms.contains(hits.get(i).atomId())) {
                    return i + 1;
                }
            }
            return 0;
        }
    }

    /** Where the note ranks for one query on the reopened note Store (0: not in the top-K). */
    record NoteRank(String query, String caseId, Split split, BookQueryDataset.Category category, int rank,
                    double score) {
    }

    /** Phase C: the explicit new note. */
    record NoteResult(String atomId, String sidecar, List<NoteRank> probes, List<NoteRank> intrusions,
                      int inventoryAtoms, int chunkAtoms, int chunkAtomsExact, boolean noteExact,
                      BookStoreReadback.Audit audit, int readbackUnexpectedAtoms, boolean readbackPassed) {
        NoteResult {
            probes = List.copyOf(probes);
            intrusions = List.copyOf(intrusions);
        }
    }

    /**
     * The run's outcome; {@code keyByAtom} maps each chunk atom to its first chunk key, so probe hits (raw atom ids)
     * can be reported by key.
     */
    record Result(Plan plan, BookRecallEvaluator.Result control, BookRecallEvaluator.Result treatment,
                  List<Probe> probes, NoteResult note, List<Check> checks, String baselineSha256,
                  Map<String, String> keyByAtom) {
        Result {
            probes = List.copyOf(probes);
            checks = List.copyOf(checks);
            keyByAtom = Map.copyOf(keyByAtom);
        }

        boolean passed() {
            return checks.stream().allMatch(Check::passed);
        }

        List<Check> failures() {
            return checks.stream().filter(c -> !c.passed()).toList();
        }
    }

    /** The predeclared confusable probes of every DEV case that receives feedback, in case id order. */
    static List<Probe> probes(BookQueryDataset dataset, Plan plan, BookChunkFiles.Loaded chunks) {
        Set<String> withFeedback = new LinkedHashSet<>();
        plan.events().forEach(e -> withFeedback.add(e.caseId()));
        Map<String, String> atomByKey = new LinkedHashMap<>();
        chunks.entries().forEach(e -> atomByKey.put(e.key(), KnowledgeAtom.text(e.text()).id()));
        List<Probe> probes = new ArrayList<>();
        dataset.cases().stream().filter(c -> withFeedback.contains(c.id())).sorted(Comparator.comparing(Case::id))
                .forEach(c -> {
                    Set<String> evidence = new LinkedHashSet<>();
                    c.evidence().forEach(j -> evidence.add(atomByKey.get(j.chunkKey())));
                    probes.add(new Probe(c.id() + "/" + ProbeKind.CASE_FOLDED, ProbeKind.CASE_FOLDED, c,
                            c.text().toLowerCase(Locale.ROOT), evidence, List.of(), List.of()));
                    probes.add(new Probe(c.id() + "/" + ProbeKind.REWORDED, ProbeKind.REWORDED, c,
                            "In the story, " + Character.toLowerCase(c.text().charAt(0)) + c.text().substring(1),
                            evidence, List.of(), List.of()));
                });
        return probes;
    }

    /**
     * Runs one complete experiment under {@code runDir}, which must not exist yet (its parent must). {@code baseline}
     * is the committed recall report control must reproduce, or null to skip that check.
     */
    static Result run(BookChunker.Result chunked, BookQueryDataset dataset, byte[] baseline, Path runDir)
            throws IOException, BookInputException {
        return run(chunked, dataset, baseline, runDir, PUBLIC_API);
    }

    /** {@link #run(BookChunker.Result, BookQueryDataset, byte[], Path)} recording the plan through {@code sink}. */
    static Result run(BookChunker.Result chunked, BookQueryDataset dataset, byte[] baseline, Path runDir,
                      FeedbackSink sink) throws IOException, BookInputException {
        Files.createDirectory(runDir);
        Path tmp = Files.createDirectory(runDir.resolve("tmp"));
        List<Check> checks = new ArrayList<>();
        Path control = seed(chunked, runDir.resolve("control"), "control", checks);
        Path treatment = seed(chunked, runDir.resolve("treatment"), "treatment", checks);
        Path noteWork = seed(chunked, runDir.resolve("note"), "note", checks);

        // Control first: the evaluator binds the chunk set to the judged dataset before any plan is derived from it.
        // Control is only read (on throwaway copies) from here on.
        Path controlStore = control.resolve(ExperimentDirectoryGuard.STORE_DIR);
        BookRecallEvaluator.Result controlResult = BookRecallEvaluator.evaluate(control, dataset,
                BookRecallEvaluator.DEFAULT_RETRIEVER, tmp);
        String controlDigest = BookStoreInventory.treeDigest(controlStore);

        BookChunkFiles.Loaded chunks = BookChunkFiles.load(control.resolve(ExperimentDirectoryGuard.CHUNKS_DIR));
        Map<String, String> expected = BookStoreReadback.expectedContents(
                chunks.entries().stream().map(BookChunkFiles.Entry::text).toList());
        Plan plan = BookFeedbackPlan.derive(dataset, chunks);
        List<Probe> probes = probes(dataset, plan, chunks);
        Set<String> probeTexts = new LinkedHashSet<>();
        probes.forEach(p -> probeTexts.add(p.text()));
        probeTexts.addAll(BookFeedbackNote.PROBES);
        BookFeedbackPlan.validate(plan, dataset, expected.keySet(), probeTexts);
        BookFeedbackNote.preflight(chunks, expected.keySet());
        List<Case> cases = dataset.cases().stream().sorted(Comparator.comparing(Case::id)).toList();

        String baselineSha = null;
        if (baseline != null) {
            baselineSha = VerifiedBookSource.sha256(baseline);
            byte[] rendered = BookRecallReport.render(controlResult).getBytes(StandardCharsets.UTF_8);
            checks.add(new Check("control.reproducesBaseline", Arrays.equals(rendered, baseline),
                    "control recall report vs committed baseline sha256 " + baselineSha));
        }
        List<List<Hit>> controlProbes = runOnCopy(controlStore, tmp,
                probes.stream().map(Probe::text).toList());

        // Treatment: explicit feedback through the public API, exact keys, default deltas.
        Path treatmentStore = treatment.resolve(ExperimentDirectoryGuard.STORE_DIR);
        MonadaMemory memory = MonadaMemory.open(treatmentStore);
        Path log = feedbackLog(treatmentStore);
        byte[] logBefore = Files.exists(log, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(log) : new byte[0];
        boolean rejected;
        try {
            memory.feedback(GUARD_QUERY, UNKNOWN_ATOM, FeedbackSignal.POSITIVE);
            rejected = false;
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        byte[] logAfter = Files.exists(log, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(log) : new byte[0];
        checks.add(new Check("guard.unknownAtomRejected", rejected && Arrays.equals(logBefore, logAfter),
                "feedback on an atom id outside the Store throws and writes nothing"));
        for (Event e : plan.events()) {
            sink.apply(memory, e);
        }
        List<List<Hit>> live = new ArrayList<>();
        for (Case c : cases) {
            live.add(search(memory, c.text()));
        }
        checks.add(new Check("treatment.feedbackLogMatchesPlan", logMatches(log, plan),
                plan.events().size() + " events (query, queryKey, atomId, signal, delta; createdAt ignored)"));

        // Bound to the events actually recorded; whether they are exactly the plan is the check above.
        BookRecallEvaluator.Result treatmentResult = BookRecallEvaluator.evaluate(treatment, dataset,
                BookRecallEvaluator.DEFAULT_RETRIEVER, tmp, recordedEvents(log));
        int sameAfterReopen = 0;
        for (int i = 0; i < cases.size(); i++) {
            if (live.get(i).equals(hits(treatmentResult.cases().get(i)))) {
                sameAfterReopen++;
            }
        }
        checks.add(new Check("treatment.reopenParity", sameAfterReopen == cases.size(), sameAfterReopen + "/"
                + cases.size() + " queries rank identically on the feedback instance and after reopen"));
        List<List<Hit>> treatmentProbes = runOnCopy(treatmentStore, tmp, probes.stream().map(Probe::text).toList());
        List<Probe> measured = new ArrayList<>();
        for (int i = 0; i < probes.size(); i++) {
            Probe p = probes.get(i);
            measured.add(new Probe(p.id(), p.kind(), p.source(), p.text(), p.evidenceAtoms(), controlProbes.get(i),
                    treatmentProbes.get(i)));
        }

        // Contamination: exact keys must keep every query without its own feedback exactly as in control.
        Set<String> feedbackQueries = plan.queries();
        int holdout = 0;
        int holdoutSame = 0;
        int devUntouched = 0;
        int devUntouchedSame = 0;
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            boolean same = hits(controlResult.cases().get(i)).equals(hits(treatmentResult.cases().get(i)));
            if (c.split() == Split.HOLDOUT) {
                holdout++;
                holdoutSame += same ? 1 : 0;
            } else if (!feedbackQueries.contains(c.text())) {
                devUntouched++;
                devUntouchedSame += same ? 1 : 0;
            }
        }
        checks.add(new Check("contamination.holdoutUnchanged", holdoutSame == holdout,
                holdoutSame + "/" + holdout + " HOLDOUT queries rank exactly as in control"));
        checks.add(new Check("contamination.devWithoutFeedbackUnchanged", devUntouchedSame == devUntouched,
                devUntouchedSame + "/" + devUntouched + " DEV queries without feedback rank exactly as in control"));
        long isolated = measured.stream().filter(Probe::identical).count();
        checks.add(new Check("contamination.confusableProbesUnchanged", isolated == measured.size(),
                isolated + "/" + measured.size() + " confusable variants rank exactly as in control (no transfer)"));
        checks.add(new Check("isolation.controlStoreUnchanged",
                controlDigest.equals(BookStoreInventory.treeDigest(controlStore)),
                "the control Store's bytes are identical before and after the treatment and note phases"));

        NoteResult note = note(noteWork, runDir, tmp, chunks, expected, cases, controlResult, checks);
        checks.add(new Check("isolation.controlStoreUnchangedAfterNote",
                controlDigest.equals(BookStoreInventory.treeDigest(controlStore)),
                "the note phase never touches the control Store"));
        Map<String, String> keyByAtom = new LinkedHashMap<>();
        chunks.entries().forEach(e -> keyByAtom.putIfAbsent(KnowledgeAtom.text(e.text()).id(), e.key()));
        return new Result(plan, controlResult, treatmentResult, measured, note, checks, baselineSha, keyByAtom);
    }

    private static NoteResult note(Path work, Path runDir, Path tmp, BookChunkFiles.Loaded chunks,
                                   Map<String, String> expected, List<Case> cases,
                                   BookRecallEvaluator.Result control, List<Check> checks)
            throws IOException, BookInputException {
        Path store = work.resolve(ExperimentDirectoryGuard.STORE_DIR);
        KnowledgeAtom atom = MonadaMemory.open(store).remember(BookFeedbackNote.TEXT);
        String atomId = atom.id();
        checks.add(new Check("note.newAtomId", atomId.equals(BookFeedbackNote.atomId())
                && !expected.containsKey(atomId), "remember returned the content-derived id of the note, no chunk's"));
        // Provenance outside the guarded work directory: the Store persists no atom metadata.
        Path sidecar = runDir.resolve("note-sidecar.jsonl");
        Files.writeString(sidecar, BookFeedbackNote.sidecarLine(atomId), StandardCharsets.UTF_8);
        String sidecarText = Files.readString(sidecar, StandardCharsets.UTF_8);
        checks.add(new Check("note.sidecarReadsBack", BookFeedbackNote.readsBack(sidecarText, atomId),
                "the provenance sidecar line parses back to the written fields"));

        // Fresh read-only instances: the audited manifest and the atom log, never the remembering instance.
        BookStoreReadback.Audited audited = BookStoreReadback.audit(store);
        BookStoreInventory inventory = BookStoreInventory.read(store, audited.manifest());
        int exact = 0;
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (entry.getValue().equals(inventory.contents().get(entry.getKey()))) {
                exact++;
            }
        }
        boolean noteExact = BookFeedbackNote.TEXT.equals(inventory.contents().get(atomId));
        checks.add(new Check("note.persistedNextToOriginals", noteExact && exact == expected.size()
                && inventory.uniqueAtoms() == expected.size() + 1 && audited.audit().healthy(),
                "after reopen: note exact, " + exact + "/" + expected.size() + " original chunks exact, "
                        + inventory.uniqueAtoms() + " atoms, audit " + audited.audit().status()));
        BookStoreReadback.Verification readback = BookStoreReadback.verify(work, tmp, false);
        checks.add(new Check("note.chunkReadbackSeesOneExtraAtom", !readback.passed()
                && readback.unexpectedAtoms() == 1 && readback.exactReadback() == readback.expectedUniqueAtoms(),
                "the chunk-only readback contract reports the note as its one unexpected atom"));

        List<String> queries = new ArrayList<>(BookFeedbackNote.PROBES);
        cases.forEach(c -> queries.add(c.text()));
        List<List<Hit>> ranked = runOnCopy(store, tmp, queries);
        List<NoteRank> probes = new ArrayList<>();
        for (int i = 0; i < BookFeedbackNote.PROBES.size(); i++) {
            probes.add(rank(BookFeedbackNote.PROBES.get(i), null, ranked.get(i), atomId));
        }
        List<NoteRank> intrusions = new ArrayList<>();
        int unchanged = 0;
        int withoutNote = 0;
        for (int i = 0; i < cases.size(); i++) {
            List<Hit> hits = ranked.get(BookFeedbackNote.PROBES.size() + i);
            NoteRank r = rank(cases.get(i).text(), cases.get(i), hits, atomId);
            if (r.rank() > 0) {
                intrusions.add(r);
            } else {
                withoutNote++;
                unchanged += hits.equals(hits(control.cases().get(i))) ? 1 : 0;
            }
        }
        checks.add(new Check("note.rankingsWithoutNoteUnchanged", unchanged == withoutNote, unchanged + "/"
                + withoutNote + " judged queries whose top-" + BookRecallEvaluator.MAX_K
                + " lacks the note rank exactly as in control"));
        return new NoteResult(atomId, sidecarText, probes, intrusions, inventory.uniqueAtoms(), expected.size(),
                exact, noteExact, audited.audit(), readback.unexpectedAtoms(), readback.passed());
    }

    private static NoteRank rank(String query, Case c, List<Hit> hits, String atomId) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).atomId().equals(atomId)) {
                return new NoteRank(query, c == null ? null : c.id(), c == null ? null : c.split(),
                        c == null ? null : c.category(), i + 1, hits.get(i).score());
            }
        }
        return new NoteRank(query, c == null ? null : c.id(), c == null ? null : c.split(),
                c == null ? null : c.category(), 0, Double.NaN);
    }

    private static Path seed(BookChunker.Result chunked, Path work, String arm, List<Check> checks)
            throws IOException, BookInputException {
        Path prepared;
        try {
            prepared = ExperimentDirectoryGuard.prepareForSeed(work, List.of());
        } catch (ExperimentDirectoryGuard.UnsafeDirectoryException e) {
            throw new IllegalStateException(arm + " work directory refused: " + e.getMessage(), e);
        }
        BookStoreReadback.Seed seed = BookStoreReadback.seed(chunked, prepared);
        boolean clean = seed.passed() && seed.importKind() == BookStoreReadback.ImportKind.CLEAN;
        checks.add(new Check(arm + ".cleanSeed", clean, "own Store, " + seed.importKind() + ", " + seed.after().uniqueAtoms() + " atoms, readback "
                        + (seed.verification().passed() ? "passed" : "FAILED")));
        return prepared;
    }

    /** Queries a fresh instance on a throwaway copy of {@code store}, so measuring never writes to the arm. */
    private static List<List<Hit>> runOnCopy(Path store, Path tmp, List<String> queries) throws IOException {
        return BookStoreReadback.onCopy(store, tmp, copy -> {
            MonadaMemory memory = MonadaMemory.open(copy);
            List<List<Hit>> all = new ArrayList<>();
            for (String query : queries) {
                all.add(search(memory, query));
            }
            return all;
        });
    }

    private static List<Hit> search(MonadaMemory memory, String query) {
        return memory.resonate(query).topK(BookRecallEvaluator.MAX_K).threshold(Double.NEGATIVE_INFINITY).execute()
                .results().stream().map((ResonanceResult r) -> new Hit(r.atom().id(), r.score())).toList();
    }

    static List<Hit> hits(CaseResult r) {
        return r.hits().stream().map(h -> new Hit(h.atomId(), h.score())).toList();
    }

    private static Path feedbackLog(Path store) throws BookInputException {
        Manifest manifest = BookStoreReadback.audit(store).manifest();
        if (manifest == null) {
            throw new BookInputException(BookInputException.Reason.INVALID_INPUT,
                    "the treatment Store's manifest cannot be read");
        }
        return store.toAbsolutePath().normalize().resolve(manifest.feedbackSegment()).normalize();
    }

    private static int recordedEvents(Path log) throws IOException {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        return (int) Files.readAllLines(log, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
    }

    /** The persisted log, parsed read-only, is exactly the plan in order; wall-clock {@code createdAt} is ignored. */
    private static boolean logMatches(Path log, Plan plan) throws IOException {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank())
                .toList();
        if (lines.size() != plan.events().size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            Map<String, String> f = new LinkedHashMap<>(JsonStrings.parseFlat(lines.get(i), "feedback log"));
            Event e = plan.events().get(i);
            double delta = e.signal() == FeedbackSignal.POSITIVE ? POSITIVE_DELTA : NEGATIVE_DELTA;
            if (!e.query().equals(f.get("query")) || !e.query().equals(f.get("queryKey"))
                    || !e.atomId().equals(f.get("atomId")) || !e.signal().name().equals(f.get("signal"))
                    || f.get("delta") == null || Double.parseDouble(f.get("delta")) != delta) {
                return false;
            }
        }
        return true;
    }
}
