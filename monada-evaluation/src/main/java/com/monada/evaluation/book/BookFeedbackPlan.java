package com.monada.evaluation.book;

import com.monada.core.KnowledgeAtom;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.storage.feedback.FeedbackSignal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The predeclared, explicit feedback set of the book feedback A/B (Issue #170, Book 8/8; evaluation only). It is
 * derived by rule {@value #RULE} from the frozen {@code book-queries-v1} DEV judgments alone: no retrieval output,
 * no HOLDOUT case and no {@code book-queries-v2} label is read, so the set is fixed before any treatment runs.
 *
 * <p>Feedback here is host-supplied ranking feedback, keyed by the exact query text: it says "for this query, rank
 * this atom up (or down)". It is not a factual correction, not a text mutation and not an execution outcome, and the
 * Store does not produce it: an oracle (the human judgments) does.
 */
final class BookFeedbackPlan {
    static final String RULE = "book-feedback-v1-dev-oracle";
    static final String RULE_TEXT = "per DEV case of book-queries-v1: POSITIVE for every judged EVIDENCE chunk (grade"
            + " 2 or 1), NEGATIVE for every judged CONFOUNDER chunk (grade 0) whose atom is not also evidence of the"
            + " case; one event per (query, atom); the exact v1 query text; MonadaMemory default deltas (+-0.05);"
            + " default ExactQueryKeyStrategy";

    private BookFeedbackPlan() {
    }

    /**
     * One planned feedback call: {@code MonadaMemory.feedback(query, atomId, signal)}.
     *
     * @param source the judgment it comes from: {@code E2}, {@code E1} or {@code C0}
     */
    record Event(String caseId, String query, String atomId, String chunkKey, FeedbackSignal signal, String source) {
        String canonical() {
            return caseId + "|" + signal + "|" + source + "|" + chunkKey + "|" + atomId + "|" + query;
        }
    }

    /**
     * The derived plan.
     *
     * @param sharedWithHoldout distinct feedback atoms that some HOLDOUT case also judges (disclosed, not rejected:
     *                          exact query keys cannot carry DEV feedback into a HOLDOUT query, which the experiment
     *                          checks)
     */
    record Plan(List<Event> events, String digest, int casesWithFeedback, int sharedWithHoldout) {
        Plan {
            events = List.copyOf(events);
        }

        long count(FeedbackSignal signal) {
            return events.stream().filter(e -> e.signal() == signal).count();
        }

        Set<String> caseIds() {
            Set<String> ids = new TreeSet<>();
            events.forEach(e -> ids.add(e.caseId()));
            return ids;
        }

        Set<String> queries() {
            Set<String> queries = new TreeSet<>();
            events.forEach(e -> queries.add(e.query()));
            return queries;
        }
    }

    /** Derives the plan from the DEV cases of {@code dataset}; chunk keys resolve to atoms through {@code chunks}. */
    static Plan derive(BookQueryDataset dataset, BookChunkFiles.Loaded chunks) throws BookInputException {
        Map<String, String> atomByKey = atomByKey(chunks);
        List<Event> events = new ArrayList<>();
        List<Case> dev = dataset.cases().stream().filter(c -> c.split() == Split.DEV)
                .sorted(Comparator.comparing(Case::id)).toList();
        for (Case c : dev) {
            Map<String, Event> byAtom = new LinkedHashMap<>();
            List<Judgment> ordered = new ArrayList<>(c.evidence());
            ordered.sort(Comparator.comparing(Judgment::chunkKey));
            for (Judgment j : ordered) {
                String atom = resolve(atomByKey, c, j);
                byAtom.putIfAbsent(atom, new Event(c.id(), c.text(), atom, j.chunkKey(), FeedbackSignal.POSITIVE,
                        "E" + j.grade()));
            }
            List<Judgment> confounders = new ArrayList<>(c.confounders());
            confounders.sort(Comparator.comparing(Judgment::chunkKey));
            for (Judgment j : confounders) {
                String atom = resolve(atomByKey, c, j);
                // An atom that is evidence of the case (repeated text) stays positive, as the evaluator scores it.
                byAtom.putIfAbsent(atom, new Event(c.id(), c.text(), atom, j.chunkKey(), FeedbackSignal.NEGATIVE,
                        "C0"));
            }
            events.addAll(byAtom.values());
        }
        Set<String> holdoutAtoms = new HashSet<>();
        for (Case c : dataset.cases()) {
            if (c.split() == Split.HOLDOUT) {
                for (Judgment j : c.judgments()) {
                    holdoutAtoms.add(resolve(atomByKey, c, j));
                }
            }
        }
        Set<String> feedbackAtoms = new TreeSet<>();
        events.forEach(e -> feedbackAtoms.add(e.atomId()));
        feedbackAtoms.retainAll(holdoutAtoms);
        StringBuilder canonical = new StringBuilder();
        events.forEach(e -> canonical.append(e.canonical()).append('\n'));
        return new Plan(events, VerifiedBookSource.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)),
                (int) events.stream().map(Event::caseId).distinct().count(), feedbackAtoms.size());
    }

    /**
     * Rejects a plan before anything is written: every event must come from a DEV case with its exact text, name an
     * atom of the chunk set and be unique per (query, atom); no event query may equal a HOLDOUT query or a probe
     * query (either would leak feedback into what is measured as untouched).
     */
    static void validate(Plan plan, BookQueryDataset dataset, Set<String> chunkAtoms, Collection<String> probes)
            throws BookInputException {
        Map<String, Case> byId = new LinkedHashMap<>();
        dataset.cases().forEach(c -> byId.put(c.id(), c));
        Set<String> holdoutTexts = new HashSet<>();
        dataset.cases().stream().filter(c -> c.split() == Split.HOLDOUT).forEach(c -> holdoutTexts.add(c.text()));
        Set<String> seen = new HashSet<>();
        for (Event e : plan.events()) {
            Case source = byId.get(e.caseId());
            if (source == null || source.split() != Split.DEV) {
                throw leak(e.caseId() + ": feedback may only come from a DEV case of " + BookQueryDataset.DATASET);
            }
            if (!source.text().equals(e.query())) {
                throw leak(e.caseId() + ": feedback query is not the case's exact text");
            }
            if (holdoutTexts.contains(e.query())) {
                throw leak(e.caseId() + ": feedback query equals a HOLDOUT query");
            }
            if (probes.contains(e.query())) {
                throw leak(e.caseId() + ": feedback query equals a contamination probe");
            }
            if (!chunkAtoms.contains(e.atomId())) {
                throw leak(e.caseId() + ": feedback names atom " + e.atomId() + ", which is not a chunk of this"
                        + " corpus; no feedback is written for an invalid atom id");
            }
            if (!seen.add(e.query() + "\0" + e.atomId())) {
                throw leak(e.caseId() + ": duplicate feedback for atom " + e.atomId());
            }
            boolean judged = source.judgments().stream().anyMatch(j -> j.chunkKey().equals(e.chunkKey())
                    && (e.signal() == FeedbackSignal.POSITIVE) == (j.role() == Role.EVIDENCE));
            if (!judged) {
                throw leak(e.caseId() + ": feedback on " + e.chunkKey() + " has no matching judgment");
            }
        }
    }

    private static Map<String, String> atomByKey(BookChunkFiles.Loaded chunks) {
        Map<String, String> atoms = new LinkedHashMap<>();
        for (BookChunkFiles.Entry entry : chunks.entries()) {
            atoms.put(entry.key(), KnowledgeAtom.text(entry.text()).id());
        }
        return atoms;
    }

    private static String resolve(Map<String, String> atomByKey, Case c, Judgment j) throws BookInputException {
        String atom = atomByKey.get(j.chunkKey());
        if (atom == null) {
            throw leak(c.id() + ": judged chunk " + j.chunkKey() + " is not in the chunk set");
        }
        return atom;
    }

    private static BookInputException leak(String message) {
        return new BookInputException(BookInputException.Reason.INVALID_INPUT, "invalid feedback plan: " + message);
    }
}
