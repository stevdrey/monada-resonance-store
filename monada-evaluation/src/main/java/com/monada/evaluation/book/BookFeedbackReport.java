package com.monada.evaluation.book;

import com.monada.evaluation.book.BookFeedbackExperiment.Check;
import com.monada.evaluation.book.BookFeedbackExperiment.NoteRank;
import com.monada.evaluation.book.BookFeedbackExperiment.Probe;
import com.monada.evaluation.book.BookFeedbackExperiment.Result;
import com.monada.evaluation.book.BookFeedbackPlan.Event;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookRecallEvaluator.Aggregate;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.Hit;
import com.monada.evaluation.book.BookRecallEvaluator.RankedHit;
import com.monada.storage.feedback.FeedbackSignal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders a {@link BookFeedbackExperiment.Result} as a versioned, deterministic plain-text report: no paths, timings,
 * timestamps or host details, fixed number formats, queries in id order. It quotes query texts, chunk keys and the
 * experiment's own note, never book text; reviewer judgment notes are left out.
 */
final class BookFeedbackReport {
    static final String FORMAT = "monada-book-feedback-report";
    static final int FORMAT_VERSION = 1;
    /** Metric differences at or below this are treated as no change (float noise only; the data is 4-decimal). */
    static final double EPSILON = 1e-12;

    private BookFeedbackReport() {
    }

    /** How the treatment compares with control for one group. */
    enum Outcome { BETTER, SAME, WORSE }

    /** The report; {@code repeatIdentical} (null: not repeated) is the second run's byte-for-byte verdict. */
    static String render(Result result, Boolean repeatIdentical) {
        StringBuilder out = new StringBuilder(body(result));
        line(out, "== Repeat run ==");
        if (repeatIdentical == null) {
            line(out, "repeat.identical=NOT_RUN");
        } else {
            line(out, "repeat.identical=" + repeatIdentical + " (a second complete run on new Stores rendered "
                    + (repeatIdentical ? "the same bytes" : "DIFFERENT bytes") + " above this section)");
        }
        return out.toString();
    }

    /** Everything above the repeat verdict: what two runs must render identically. */
    static String body(Result result) {
        StringBuilder out = new StringBuilder();
        line(out, "Monada book feedback report (Issue #170, Book 8/8)");
        line(out, "format=" + FORMAT);
        line(out, "formatVersion=" + FORMAT_VERSION);
        line(out, "status=EXPLORATORY: explicit host/oracle feedback replay and one explicit note; no autonomous"
                + " learning, mistake detection or fact correction is shipped; nothing is promoted; HOLDOUT is"
                + " reported only");
        line(out, "checks=" + (result.passed() ? "PASS" : "FAIL") + " (" + (result.checks().size()
                - result.failures().size()) + "/" + result.checks().size() + ")");
        line(out, "");
        inputs(out, result);
        scope(out);
        plan(out, result);
        line(out, "== Metrics by partition: control -> treatment (treatment measured after reopen) ==");
        for (int i = 0; i < result.control().partitions().size(); i++) {
            partition(out, result.control().partitions().get(i), result.treatment().partitions().get(i));
        }
        categories(out, result);
        Set<String> withFeedback = result.plan().caseIds();
        changes(out, result, withFeedback);
        negatives(out, result, withFeedback);
        probes(out, result);
        note(out, result);
        checks(out, result);
        trace(out, result);
        return out.toString();
    }

    private static void inputs(StringBuilder out, Result result) {
        line(out, "== Inputs ==");
        result.control().identity().fields().forEach((k, v) -> {
            if (!k.equals("store.feedbackEvents") && !k.equals("runs")) {
                line(out, k + "=" + v);
            }
        });
        Map<String, String> treatment = result.treatment().identity().fields();
        line(out, "baseline.report.sha256=" + (result.baselineSha256() == null ? "NOT_CHECKED"
                : result.baselineSha256()));
        line(out, "control.store.feedbackEvents=" + result.control().identity().fields().get("store.feedbackEvents"));
        line(out, "treatment.store.vectorsSha256=" + treatment.get("store.vectorsSha256"));
        line(out, "treatment.store.fingerprint=" + treatment.get("store.fingerprint"));
        line(out, "treatment.store.feedbackEvents=" + treatment.get("store.feedbackEvents"));
        line(out, "feedback.api=MonadaMemory.feedback(query, atomId, signal) with default deltas +"
                + num(BookFeedbackExperiment.POSITIVE_DELTA) + " / " + num(BookFeedbackExperiment.NEGATIVE_DELTA));
        line(out, "feedback.queryKeys=ExactQueryKeyStrategy (MonadaMemoryOptions.defaults(); not changed)");
        line(out, "arms=control, treatment and note: three Stores seeded separately from the same chunk set, each in"
                + " its own work directory");
        line(out, "runs=control and treatment are measured twice per query on fresh instances of a throwaway copy;"
                + " treatment is also ranked on the instance that recorded the feedback");
        line(out, "note.id=" + BookFeedbackNote.NOTE_ID);
        line(out, "note.text=" + BookFeedbackNote.TEXT);
        line(out, "note.source=" + BookFeedbackNote.SOURCE);
        line(out, "note.wording=" + BookFeedbackNote.WORDING);
        line(out, "note.verificationStatus=" + BookFeedbackNote.VERIFICATION_STATUS);
        line(out, "note.verifiedBy=" + BookFeedbackNote.VERIFIED_BY);
        line(out, "note.verifiedAt=" + BookFeedbackNote.VERIFIED_AT);
        line(out, "");
    }

    private static void scope(StringBuilder out) {
        line(out, "== What is measured, and what is not ==");
        line(out, "ranking feedback: an explicit statement by the host that, for one exact query text, an atom should"
                + " rank higher (POSITIVE) or lower (NEGATIVE); it changes scores, never stored text");
        line(out, "explicit new note: content the host adds with remember(); it is a new atom next to the old ones and"
                + " replaces, deletes or versions nothing");
        line(out, "execution outcomes: not used or measured here");
        line(out, "not shipped: the Store does not detect wrong answers, does not choose feedback, does not add or"
                + " correct facts by itself and does not generalize feedback beyond the exact query key");
        line(out, "metrics: as in the book recall report (P@K, R@K, Hit@K over judged evidence; MRR; NO_ANSWER false"
                + " hits); labels E2/E1 evidence, C0 confounder, U unjudged");
        line(out, "outcome per group: BETTER/WORSE by MRR, ties broken by Hit@" + BookRecallEvaluator.MAX_K
                + " then R@" + BookRecallEvaluator.MAX_K + "; SAME when all three are equal; no improvement is"
                + " required");
        line(out, "regression: an answerable query whose RR, any Hit@K or R@" + BookRecallEvaluator.MAX_K
                + " is lower in treatment than in control");
        line(out, "contamination: a query without its own feedback (HOLDOUT, DEV without feedback, confusable"
                + " variant) whose top-" + BookRecallEvaluator.MAX_K + " differs from control in any atom or score");
        line(out, "chunk keys below are relative to <corpus>/<policy>/ of the inputs");
        line(out, "");
    }

    private static void plan(StringBuilder out, Result result) {
        BookFeedbackPlan.Plan plan = result.plan();
        line(out, "== Feedback plan (predeclared, DEV oracle labels only) ==");
        line(out, "rule=" + BookFeedbackPlan.RULE);
        line(out, "ruleText=" + BookFeedbackPlan.RULE_TEXT);
        line(out, "events=" + plan.events().size() + " (POSITIVE " + plan.count(FeedbackSignal.POSITIVE)
                + ", NEGATIVE " + plan.count(FeedbackSignal.NEGATIVE) + ")");
        line(out, "casesWithFeedback=" + plan.casesWithFeedback());
        line(out, "plan.sha256=" + plan.digest());
        line(out, "disclosure.atomsAlsoJudgedByHoldout=" + plan.sharedWithHoldout()
                + " (feedback stays keyed by the DEV query text; HOLDOUT queries are checked for transfer)");
        for (Event e : plan.events()) {
            line(out, String.format(Locale.ROOT, "  %s %-8s %s %s atom=%s", e.caseId(), e.signal(), e.source(),
                    shortKey(e.chunkKey()), e.atomId()));
        }
        line(out, "");
    }

    private static void partition(StringBuilder out, Aggregate c, Aggregate t) {
        line(out, "-- " + c.name() + ": queries " + c.queries() + ", answerable " + c.answerable()
                + " (denominator), NO_ANSWER " + c.negatives());
        line(out, String.format(Locale.ROOT, "%-4s %-26s %-26s %s", "K", "P@K c -> t (delta)",
                "R@K c -> t (delta)", "Hit@K c -> t (delta)"));
        for (int k : BookRecallEvaluator.KS) {
            line(out, String.format(Locale.ROOT, "%-4d %-26s %-26s %s", k, delta(c.precision().get(k),
                    t.precision().get(k)), delta(c.recall().get(k), t.recall().get(k)),
                    delta(c.hit().get(k), t.hit().get(k))));
        }
        line(out, "MRR " + delta(c.mrr(), t.mrr()) + " (n=" + c.answerable() + ")");
        line(out, "NO_ANSWER false hits: " + c.negativesWithHits() + "/" + c.negatives() + " -> "
                + t.negativesWithHits() + "/" + t.negatives());
        line(out, "outcome=" + outcome(c, t));
        line(out, "");
    }

    private static void categories(StringBuilder out, Result result) {
        line(out, "== Metrics by category (DEV + HOLDOUT): control -> treatment ==");
        int high = BookRecallEvaluator.MAX_K;
        line(out, String.format(Locale.ROOT, "%-19s %-4s %-24s %-24s %s", "category", "ans", "Hit@1", "Hit@" + high,
                "MRR"));
        for (int i = 0; i < result.control().categories().size(); i++) {
            Aggregate c = result.control().categories().get(i);
            Aggregate t = result.treatment().categories().get(i);
            if (c.answerable() == 0) {
                continue;
            }
            line(out, String.format(Locale.ROOT, "%-19s %-4d %-24s %-24s %s", c.name(), c.answerable(),
                    delta(c.hit().get(1), t.hit().get(1)), delta(c.hit().get(high), t.hit().get(high)),
                    delta(c.mrr(), t.mrr())));
        }
        line(out, "");
    }

    private static void changes(StringBuilder out, Result result, Set<String> withFeedback) {
        line(out, "== Per-query changes (answerable) ==");
        List<String> regressions = new ArrayList<>();
        for (int i = 0; i < result.control().cases().size(); i++) {
            CaseResult c = result.control().cases().get(i);
            CaseResult t = result.treatment().cases().get(i);
            if (!c.answerable()) {
                continue;
            }
            String change = change(c, t);
            if (change.equals("REGRESSED")) {
                regressions.add(c.query().id());
            }
            line(out, String.format(Locale.ROOT, "%s %-7s %-18s feedback=%-3s firstEvidenceRank %s -> %s RR %s -> %s"
                            + " R@%d %s -> %s %s", c.query().id(), c.query().split(), c.query().category(),
                    withFeedback.contains(c.query().id()) ? "yes" : "no", rank(c.firstRelevantRank()),
                    rank(t.firstRelevantRank()), num(c.reciprocalRank()), num(t.reciprocalRank()),
                    BookRecallEvaluator.MAX_K, num(c.recall().get(BookRecallEvaluator.MAX_K)),
                    num(t.recall().get(BookRecallEvaluator.MAX_K)), change));
        }
        line(out, "regressions=" + (regressions.isEmpty() ? "none" : String.join(", ", regressions)));
        line(out, "");
    }

    private static void negatives(StringBuilder out, Result result, Set<String> withFeedback) {
        line(out, "== NO_ANSWER queries: control -> treatment ==");
        line(out, "Feedback can move which chunk ranks first; it cannot make the Store abstain at threshold"
                + " -Infinity.");
        for (int i = 0; i < result.control().cases().size(); i++) {
            CaseResult c = result.control().cases().get(i);
            CaseResult t = result.treatment().cases().get(i);
            if (c.answerable()) {
                continue;
            }
            line(out, String.format(Locale.ROOT, "%s %-7s feedback=%-3s %s -> %s top1 %s -> %s", c.query().id(),
                    c.query().split(), withFeedback.contains(c.query().id()) ? "yes" : "no", c.failure(),
                    t.failure(), top1(c), top1(t)));
        }
        line(out, "");
    }

    private static void probes(StringBuilder out, Result result) {
        line(out, "== Contamination probes ==");
        line(out, "HOLDOUT and DEV queries without feedback: compared in full in the per-query trace and in the hard"
                + " checks below.");
        line(out, "Confusable variants of DEV feedback queries (rule: CASE_FOLDED = lower-cased text; REWORDED ="
                + " \"In the story, \" + the question):");
        for (Probe p : result.probes()) {
            line(out, String.format(Locale.ROOT, "  %-19s %s firstEvidenceRank %s -> %s top1 %s -> %s query: %s",
                    p.id(), p.identical() ? "ISOLATED" : "TRANSFER", rank(p.evidenceRank(p.control())),
                    rank(p.evidenceRank(p.treatment())), top1(result, p, p.control()), top1(result, p, p.treatment()),
                    p.text()));
        }
        long transfer = result.probes().stream().filter(p -> !p.identical()).count();
        line(out, "confusable transfer=" + transfer + "/" + result.probes().size()
                + " (exact query keys: feedback does not generalize to a reworded or re-cased question)");
        line(out, "");
    }

    private static void note(StringBuilder out, Result result) {
        BookFeedbackExperiment.NoteResult note = result.note();
        line(out, "== Phase C: explicit new note ==");
        line(out, "note.atomId=" + note.atomId());
        line(out, "note.sidecar=" + note.sidecar().strip());
        line(out, "after reopen: atoms " + note.inventoryAtoms() + " (chunks " + note.chunkAtoms() + " + note 1),"
                + " original chunks exact " + note.chunkAtomsExact() + "/" + note.chunkAtoms() + ", note exact "
                + note.noteExact() + ", audit " + note.audit().status());
        line(out, "chunk readback contract: passed=" + note.readbackPassed() + ", unexpected atoms "
                + note.readbackUnexpectedAtoms() + " (the chunk-only readback is expected to flag the note)");
        line(out, "lifecycle=" + BookFeedbackNote.LIFECYCLE);
        line(out, "note probes (predeclared; the note is not required to rank first):");
        for (NoteRank r : note.probes()) {
            line(out, "  " + noteRank(r) + " query: " + r.query());
        }
        line(out, "judged queries whose top-" + BookRecallEvaluator.MAX_K + " now includes the note: "
                + note.intrusions().size() + (note.intrusions().isEmpty() ? "" : " (false transfer for NO_ANSWER"
                + " queries; for answerable ones the note displaces a chunk)"));
        for (NoteRank r : note.intrusions()) {
            line(out, String.format(Locale.ROOT, "  %s %-7s %-18s %s", r.caseId(), r.split(), r.category(),
                    noteRank(r)));
        }
        line(out, "");
    }

    private static void checks(StringBuilder out, Result result) {
        line(out, "== Hard checks ==");
        for (Check c : result.checks()) {
            line(out, (c.passed() ? "PASS " : "FAIL ") + c.name() + ": " + c.detail());
        }
        line(out, "");
    }

    private static void trace(StringBuilder out, Result result) {
        line(out, "== Per-query trace: control vs treatment top-" + BookRecallEvaluator.MAX_K + " ==");
        for (int i = 0; i < result.control().cases().size(); i++) {
            CaseResult c = result.control().cases().get(i);
            CaseResult t = result.treatment().cases().get(i);
            boolean same = BookFeedbackExperiment.hits(c).equals(BookFeedbackExperiment.hits(t));
            line(out, c.query().id() + " " + c.query().split() + " " + c.query().category() + " "
                    + (same ? "IDENTICAL" : "CHANGED"));
            line(out, "  query: " + c.query().text());
            int n = Math.max(c.hits().size(), t.hits().size());
            for (int r = 0; r < n; r++) {
                line(out, String.format(Locale.ROOT, "  #%-2d c %-32s t %s", r + 1,
                        r < c.hits().size() ? hit(c.hits().get(r)) : "-",
                        r < t.hits().size() ? hit(t.hits().get(r)) : "-"));
            }
        }
    }

    static Outcome outcome(Aggregate c, Aggregate t) {
        int k = BookRecallEvaluator.MAX_K;
        for (double[] pair : new double[][] {{c.mrr(), t.mrr()}, {c.hit().get(k), t.hit().get(k)},
                {c.recall().get(k), t.recall().get(k)}}) {
            if (Double.isNaN(pair[0]) || Double.isNaN(pair[1])) {
                return Outcome.SAME;
            }
            if (pair[1] - pair[0] > EPSILON) {
                return Outcome.BETTER;
            }
            if (pair[0] - pair[1] > EPSILON) {
                return Outcome.WORSE;
            }
        }
        return Outcome.SAME;
    }

    /** {@code REGRESSED} when RR, any Hit@K or R@K-max dropped; {@code IMPROVED} when any rose; else unchanged. */
    static String change(CaseResult c, CaseResult t) {
        boolean worse = t.reciprocalRank() < c.reciprocalRank() - EPSILON;
        boolean better = t.reciprocalRank() > c.reciprocalRank() + EPSILON;
        for (int k : BookRecallEvaluator.KS) {
            worse |= t.hit().get(k) < c.hit().get(k) - EPSILON;
            better |= t.hit().get(k) > c.hit().get(k) + EPSILON;
        }
        int k = BookRecallEvaluator.MAX_K;
        worse |= t.recall().get(k) < c.recall().get(k) - EPSILON;
        better |= t.recall().get(k) > c.recall().get(k) + EPSILON;
        return worse ? "REGRESSED" : better ? "IMPROVED" : "UNCHANGED";
    }

    private static String top1(CaseResult r) {
        return r.hits().isEmpty() ? "none" : String.format(Locale.ROOT, "%s %s %.6f", r.hits().getFirst().label(),
                shortKey(r.hits().getFirst().key()), r.hits().getFirst().score());
    }

    /** The probe's top hit by chunk key, labelled as the evaluator labels hits, from the source case's judgments. */
    private static String top1(Result result, Probe p, List<Hit> hits) {
        if (hits.isEmpty()) {
            return "none";
        }
        List<String> keys = result.keysByAtom().get(hits.getFirst().atomId());
        if (keys == null) {
            return String.format(Locale.ROOT, "%s %.6f", hits.getFirst().atomId(), hits.getFirst().score());
        }
        Map<String, Judgment> judged = new LinkedHashMap<>();
        p.source().judgments().forEach(j -> judged.put(j.chunkKey(), j));
        return String.format(Locale.ROOT, "%s %s %.6f", BookRecallEvaluator.label(keys, judged),
                shortKey(keys.getFirst()), hits.getFirst().score());
    }

    private static String noteRank(NoteRank r) {
        return r.rank() == 0 ? "note not in top-" + BookRecallEvaluator.MAX_K
                : String.format(Locale.ROOT, "note at #%d %.6f", r.rank(), r.score());
    }

    private static String hit(RankedHit h) {
        return String.format(Locale.ROOT, "%.6f %-2s %s", h.score(), h.label(), shortKey(h.key()));
    }

    /** {@code s001/c0004} for {@code <corpus>/<policy>/s001/c0004}. */
    static String shortKey(String key) {
        int last = key.lastIndexOf('/');
        int section = last < 0 ? -1 : key.lastIndexOf('/', last - 1);
        return section < 0 ? key : key.substring(section + 1);
    }

    private static String rank(int rank) {
        return rank == 0 ? "none" : String.valueOf(rank);
    }

    private static String delta(Double control, Double treatment) {
        if (control == null || treatment == null || control.isNaN() || treatment.isNaN()) {
            return "n/a";
        }
        double d = treatment - control;
        return num(control) + " -> " + num(treatment) + " (" + (Math.abs(d) <= EPSILON ? "+0.0000"
                : String.format(Locale.ROOT, "%+.4f", d)) + ")";
    }

    private static String num(Double value) {
        return value == null || value.isNaN() ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static void line(StringBuilder out, String text) {
        out.append(Objects.requireNonNull(text)).append('\n');
    }
}
