package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookRecallEvaluator.Aggregate;
import com.monada.evaluation.book.BookRecallEvaluator.CaseResult;
import com.monada.evaluation.book.BookRecallEvaluator.RankedHit;
import com.monada.evaluation.book.BookRecallEvaluator.Result;
import com.monada.evaluation.book.BookRecallEvaluator.ThresholdPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link BookRecallEvaluator.Result} as a versioned, deterministic plain-text report: no paths, timings or
 * host details, fixed number formats, queries in id order. The report quotes query text and reviewer judgment notes,
 * never book text, and never states an answer.
 */
final class BookRecallReport {
    static final String FORMAT = "monada-book-recall-report";
    static final int FORMAT_VERSION = 1;
    /** Failure examples rendered in full; at least three when that many queries failed. */
    static final int MAX_EXAMPLES = 6;
    private static final int EXAMPLE_HITS = 5;

    private BookRecallReport() {
    }

    static String render(Result result) {
        StringBuilder out = new StringBuilder();
        line(out, "Monada book recall report (Issue #167, Book 5/8)");
        line(out, "format=" + FORMAT);
        line(out, "formatVersion=" + FORMAT_VERSION);
        line(out, "status=EXPLORATORY: similarity scores are uncalibrated; no threshold, gate or configuration is"
                + " promoted; HOLDOUT is reported only, never used to choose anything");
        line(out, "");
        line(out, "== Inputs ==");
        result.identity().fields().forEach((k, v) -> line(out, k + "=" + v));
        line(out, "");
        definitions(out);
        line(out, "== Metrics by partition ==");
        result.partitions().forEach(a -> partition(out, a));
        categories(out, result.categories());
        negatives(out, result.cases());
        thresholds(out, result.thresholds());
        examples(out, result.cases());
        trace(out, result.cases());
        return out.toString();
    }

    private static void definitions(StringBuilder out) {
        line(out, "== Definitions ==");
        line(out, "relevant = judged EVIDENCE (grade 1 partial, grade 2 direct) of the query; chunks with identical"
                + " text share one atom and count once, under their first key");
        line(out, "denominator = answerable queries of the group; NO_ANSWER queries have no relevant set, are not in"
                + " P/R/Hit/MRR and are reported as false hits instead");
        line(out, "P@K = relevant hits in top-K / K (a short result list still divides by K); R@K = relevant atoms"
                + " in top-K / relevant atoms; Hit@K = any relevant in top-K; Hit@K(direct) = any grade-2 evidence"
                + " in top-K; MRR = mean 1/rank of the first relevant hit (0 when none in top-"
                + BookRecallEvaluator.MAX_K + ")");
        line(out, "labels: E2/E1 judged evidence, C0 judged confounder, U unjudged for this query (not assumed"
                + " non-relevant; counted separately)");
        line(out, "false hit = a NO_ANSWER query that returns any chunk at the evaluated threshold (-Infinity, so"
                + " every non-empty top-K is one)");
        line(out, "failure classes (first match wins): NO_RELEVANT_RESULT no evidence in top-"
                + BookRecallEvaluator.MAX_K + "; DISTRACTOR a judged confounder above the first evidence;"
                + " CHUNK_BOUNDARY_GAP an unjudged chunk adjacent (ordinal +-1) to evidence above the first"
                + " evidence; WRONG_RANK evidence below rank 1 behind other chunks; MULTI_EVIDENCE_GAP rank 1 is"
                + " evidence but some evidence is missing from the top-" + BookRecallEvaluator.MAX_K
                + "; NONE; FALSE_POSITIVE / ABSTAINED for NO_ANSWER");
        line(out, "");
    }

    private static void partition(StringBuilder out, Aggregate a) {
        line(out, "-- " + a.name() + ": queries " + a.queries() + ", answerable " + a.answerable() + " (denominator),"
                + " NO_ANSWER " + a.negatives());
        line(out, String.format(Locale.ROOT, "%-4s %-8s %-8s %-8s %-14s %-15s %s", "K", "P@K", "R@K", "Hit@K",
                "Hit@K(direct)", "confounder hits", "unjudged hits"));
        for (int k : BookRecallEvaluator.KS) {
            line(out, String.format(Locale.ROOT, "%-4d %-8s %-8s %-8s %-14s %-15d %d", k, num(a.precision().get(k)),
                    num(a.recall().get(k)), num(a.hit().get(k)), num(a.directHit().get(k)),
                    a.confounderHits().get(k), a.unjudgedHits().get(k)));
        }
        line(out, "MRR " + num(a.mrr()) + " (n=" + a.answerable() + ")");
        line(out, "NO_ANSWER false hits: " + a.negativesWithHits() + "/" + a.negatives());
        line(out, "failures: " + failures(a.failures()));
        line(out, "");
    }

    private static void categories(StringBuilder out, List<Aggregate> categories) {
        line(out, "== Metrics by category (DEV + HOLDOUT) ==");
        line(out, String.format(Locale.ROOT, "%-19s %-3s %-4s %-7s %-7s %-7s %-7s %-7s %s", "category", "n", "ans",
                "Hit@1", "Hit@10", "R@5", "R@10", "MRR", "false hits"));
        for (Aggregate a : categories) {
            if (a.queries() == 0) {
                continue;
            }
            line(out, String.format(Locale.ROOT, "%-19s %-3d %-4d %-7s %-7s %-7s %-7s %-7s %s", a.name(), a.queries(),
                    a.answerable(), num(a.hit().get(1)), num(a.hit().get(10)), num(a.recall().get(5)),
                    num(a.recall().get(10)), num(a.mrr()),
                    a.negatives() == 0 ? "-" : a.negativesWithHits() + "/" + a.negatives()));
        }
        line(out, "");
    }

    private static void negatives(StringBuilder out, List<CaseResult> cases) {
        line(out, "== NO_ANSWER queries (false-hit handling) ==");
        line(out, "Every hit of a NO_ANSWER query is a false hit; its top-1 score is shown for the threshold"
                + " exploration below.");
        for (CaseResult r : cases) {
            if (r.answerable()) {
                continue;
            }
            String top = r.hits().isEmpty() ? "no hit" : hit(r.hits().getFirst());
            line(out, String.format(Locale.ROOT, "%s %-7s %s hits=%d top1: %s", r.query().id(), r.query().split(),
                    r.failure(), r.hits().size(), top));
        }
        line(out, "");
    }

    private static void thresholds(StringBuilder out, List<ThresholdPoint> points) {
        line(out, "== Threshold sensitivity (EXPLORATION ONLY, scores are not calibrated truth) ==");
        line(out, "At score >= t: NO_ANSWER queries that would still return a hit, and answerable queries whose first"
                + " retrieved evidence would survive. Fixed grid; nothing is selected from it.");
        line(out, String.format(Locale.ROOT, "%-6s %-18s %-18s %-18s %s", "t", "DEV false hits", "DEV evidence kept",
                "HOLDOUT false hits", "HOLDOUT evidence kept"));
        for (double t : BookRecallEvaluator.THRESHOLD_GRID) {
            ThresholdPoint dev = point(points, BookQueryDataset.Split.DEV, t);
            ThresholdPoint holdout = point(points, BookQueryDataset.Split.HOLDOUT, t);
            line(out, String.format(Locale.ROOT, "%-6.2f %-18s %-18s %-18s %s", t,
                    dev.negativesWithHit() + "/" + dev.negatives(), dev.answerableKept() + "/" + dev.answerable(),
                    holdout.negativesWithHit() + "/" + holdout.negatives(),
                    holdout.answerableKept() + "/" + holdout.answerable()));
        }
        line(out, "");
    }

    private static ThresholdPoint point(List<ThresholdPoint> points, BookQueryDataset.Split split, double t) {
        return points.stream().filter(p -> p.split() == split && p.threshold() == t).findFirst().orElseThrow();
    }

    /**
     * Failure examples: the first query (by id) of each failure class, then further failing queries by id, up to
     * {@value #MAX_EXAMPLES}.
     */
    static List<CaseResult> examples(List<CaseResult> cases) {
        Set<BookRecallFailure> ok = EnumSet.of(BookRecallFailure.NONE, BookRecallFailure.ABSTAINED);
        List<CaseResult> picked = new ArrayList<>();
        for (BookRecallFailure failure : BookRecallFailure.values()) {
            cases.stream().filter(r -> r.failure() == failure && !ok.contains(failure)).findFirst()
                    .ifPresent(picked::add);
        }
        for (CaseResult r : cases) {
            if (picked.size() >= MAX_EXAMPLES) {
                break;
            }
            if (!ok.contains(r.failure()) && !picked.contains(r)) {
                picked.add(r);
            }
        }
        return picked.subList(0, Math.min(picked.size(), MAX_EXAMPLES)).stream()
                .sorted(Comparator.comparing((CaseResult r) -> r.failure().ordinal())
                        .thenComparing(r -> r.query().id()))
                .toList();
    }

    private static void examples(StringBuilder out, List<CaseResult> cases) {
        List<CaseResult> examples = examples(cases);
        line(out, "== Failure examples (expected vs actual chunks; no book text, no answer text) ==");
        if (examples.isEmpty()) {
            line(out, "no failing query");
        }
        int n = 1;
        for (CaseResult r : examples) {
            line(out, "-- Example " + n++ + ": " + r.query().id() + " (" + r.query().split() + ", "
                    + r.query().category() + ") -> " + r.failure());
            line(out, "query: " + r.query().text());
            gold(out, r.query().judgments(), "  ");
            line(out, "actual top-" + Math.min(EXAMPLE_HITS, r.hits().size()) + ":");
            r.hits().stream().limit(EXAMPLE_HITS).forEach(h -> line(out, "  " + hit(h)));
            line(out, r.answerable() ? "first evidence rank: " + (r.firstRelevantRank() == 0
                    ? "none in top-" + BookRecallEvaluator.MAX_K
                    : r.firstRelevantRank() + " " + hit(r.hits().get(r.firstRelevantRank() - 1)))
                    : "expected: no chunk (NO_ANSWER); returned " + r.hits().size());
            line(out, "");
        }
    }

    private static void trace(StringBuilder out, List<CaseResult> cases) {
        line(out, "== Per-query trace (all hits, raw atom ids) ==");
        for (CaseResult r : cases) {
            StringBuilder head = new StringBuilder().append(r.query().id()).append(' ').append(r.query().split())
                    .append(' ').append(r.query().category()).append(" failure=").append(r.failure());
            if (r.answerable()) {
                head.append(" firstEvidenceRank=").append(r.firstRelevantRank()).append(" RR=")
                        .append(num(r.reciprocalRank()));
                for (int k : BookRecallEvaluator.KS) {
                    head.append(" P@").append(k).append('=').append(num(r.precision().get(k)));
                    head.append(" R@").append(k).append('=').append(num(r.recall().get(k)));
                }
            }
            line(out, head.toString());
            line(out, "  query: " + r.query().text());
            gold(out, r.query().judgments(), "  ");
            // Compact: ordinal and section are in the sidecar under the key; the examples above show them inline.
            for (RankedHit h : r.hits()) {
                line(out, String.format(Locale.ROOT, "  #%d %.6f %-2s %s atom=%s%s", h.rank(), h.score(), h.label(),
                        h.key(), h.atomId(), h.keys().size() > 1 ? " sameText=" + h.keys() : ""));
            }
        }
    }

    private static void gold(StringBuilder out, List<Judgment> judgments, String indent) {
        if (judgments.isEmpty()) {
            line(out, indent + "gold: none (no evidence, no judged confounder)");
        }
        for (Judgment j : judgments) {
            String label = j.role() == BookQueryDataset.Role.EVIDENCE ? "E" + j.grade() : "C0";
            line(out, indent + "gold " + label + " " + j.chunkKey() + " span [" + j.byteStart() + "," + j.byteEnd()
                    + ") note: " + j.note());
        }
    }

    private static String hit(RankedHit h) {
        return String.format(Locale.ROOT, "#%d %.6f %-2s %s (ordinal %d, %s)", h.rank(), h.score(), h.label(),
                h.key(), h.ordinals().getFirst(), h.section());
    }

    private static String failures(Map<BookRecallFailure, Integer> failures) {
        StringBuilder text = new StringBuilder();
        failures.forEach((failure, count) -> text.append(text.isEmpty() ? "" : ", ").append(failure).append(' ')
                .append(count));
        return text.isEmpty() ? "none" : text.toString();
    }

    private static String num(Double value) {
        return value == null || value.isNaN() ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
