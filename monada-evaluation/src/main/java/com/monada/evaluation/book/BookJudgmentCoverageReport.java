package com.monada.evaluation.book;

import com.monada.evaluation.book.BookJudgmentCoverage.Metrics;
import com.monada.evaluation.book.BookJudgmentCoverage.Query;
import com.monada.evaluation.book.BookJudgmentCoverage.Result;
import com.monada.evaluation.book.BookJudgmentCoverage.Row;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Deterministic text report of {@link BookJudgmentCoverage} (issue #184): the frozen v1 ranking projected onto the
 * v1 and v2 labels, with every metric difference attributed to label coverage. No book text, no paths, no timings.
 */
final class BookJudgmentCoverageReport {
    static final String FORMAT = "monada-book-judgment-coverage-report";
    static final int FORMAT_VERSION = 1;

    private BookJudgmentCoverageReport() {
    }

    static String render(Result r) {
        StringBuilder out = new StringBuilder();
        line(out, "Monada book judgment coverage report: v1 vs v2 labels on one frozen ranking (Issue #184)");
        line(out, "format=" + FORMAT);
        line(out, "formatVersion=" + FORMAT_VERSION);
        line(out, "status=LABEL COVERAGE ONLY: zero searches; the v1 ranked hits are projected unchanged onto both"
                + " label versions; every delta below is a judgment-coverage difference, not a retrieval improvement");
        line(out, "");
        line(out, "== Inputs ==");
        line(out, "baselineReport=" + BookUnjudgedSelection.BASELINE_REPORT);
        line(out, "baselineReport.sha256=" + r.baselineSha256());
        line(out, "v1.dataset=" + r.v1Manifest().getProperty("dataset"));
        line(out, "v1.manifest.sha256=" + BookJudgmentCoverage.V1_MANIFEST_SHA256);
        for (String key : List.of("queries.sha256", "judgments.sha256", "chunkMap.sha256", "split.digest")) {
            line(out, "v1." + key + "=" + r.v1Manifest().getProperty(key));
        }
        line(out, "v2.dataset=" + r.v2Manifest().getProperty("dataset"));
        line(out, "v2.manifest.sha256=" + BookJudgmentCoverage.V2_MANIFEST_SHA256);
        for (String key : List.of("selection.sha256", "review.sha256", "provenance", "review.protocol",
                "judgment.draftedBy", "judgment.draftBlinding", "judgment.reviewStatus", "judgment.reviewedAt")) {
            line(out, "v2." + key + "=" + r.v2Manifest().getProperty(key, ""));
        }
        line(out, "");
        line(out, "== Definitions ==");
        line(out, "occurrence = one ranked top-" + BookRecallEvaluator.MAX_K + " hit of one query in the frozen trace;"
                + " pair = a distinct (query, chunk); the trace ranks an atom at most once per query, so they"
                + " coincide");
        line(out, "labels: E2/E1 judged evidence, C0 judged confounder, U unjudged (unknown, never a negative)");
        line(out, "known = U counted as not relevant (the reported value); upper = every U counted as relevant; R@K has"
                + " no upper bound because unjudged chunks outside the top-" + BookRecallEvaluator.MAX_K
                + " leave the relevant set unknown");
        line(out, "relevant = judged EVIDENCE chunks of an answerable query (every chunk is its own atom here);"
                + " denominator = answerable queries of the group");
        line(out, "delta = v2 - v1 on identical hits: attributable only to the labels v2 adds; computed before"
                + " rounding, so it can differ from the rounded v1/v2 columns by +-0.0001");
        line(out, "");

        line(out, "== Unchanged ranking (identical on both sides by construction) ==");
        List<Row> rows = r.rows();
        line(out, "queries " + r.queries().size() + ", ranked hits " + rows.size() + ", new searches 0");
        line(out, "rankingDigest=" + r.rankingDigest() + " (SHA-256 of query|rank|atom|score rows)");
        line(out, "v1 projection reproduces the frozen per-query trace metrics and partition tables: yes");
        line(out, "score = the frozen trace token (6 decimals); raw score bits are not in the baseline and are not"
                + " recomputed");
        line(out, "");

        line(out, "== Label transitions v1 -> v2 (occurrences) ==");
        line(out, String.format(Locale.ROOT, "%-9s %-11s %5s %6s %6s %6s %6s %7s %7s %7s", "partition", "queries",
                "hits", "U->U", "U->E2", "U->E1", "U->C0", "C0->C0", "E2->E2", "E1->E1"));
        for (String part : BookJudgmentCoverage.PARTITIONS) {
            List<Query> group = r.in(part);
            for (boolean ans : List.of(true, false)) {
                Predicate<Row> f = row -> row.answerable() == ans;
                line(out, String.format(Locale.ROOT, "%-9s %-11s %5d %6d %6d %6d %6d %7d %7d %7d", part,
                        ans ? "answerable" : "NO_ANSWER", group.stream().flatMap(q -> q.rows().stream()).filter(f)
                                .count(), t(group, f, "U", "U"), t(group, f, "U", "E2"), t(group, f, "U", "E1"),
                        t(group, f, "U", "C0"), t(group, f, "C0", "C0"), t(group, f, "E2", "E2"),
                        t(group, f, "E1", "E1")));
            }
        }
        for (String part : BookJudgmentCoverage.PARTITIONS) {
            List<Row> changed = r.in(part).stream().flatMap(q -> q.rows().stream()).filter(Row::changed).toList();
            line(out, part + " changed: occurrences " + changed.size() + ", distinct pairs " + changed.stream()
                    .map(x -> x.queryId() + "|" + x.chunkKey()).distinct().count() + ", distinct chunks "
                    + changed.stream().map(Row::chunkKey).distinct().count() + ", queries " + changed.stream()
                    .map(Row::queryId).distinct().count() + ", added judgments " + r.in(part).stream()
                    .mapToInt(q -> q.added().size()).sum());
        }
        line(out, "HOLDOUT: judgments identical to v1 (enforced); post-hoc DEV labels never reach HOLDOUT");
        line(out, "");

        line(out, "== Denominator provenance (answerable queries) ==");
        line(out, String.format(Locale.ROOT, "%-9s %-12s %-12s %-12s %s", "partition", "relevant v1",
                "relevant v2", "U@10 v1", "U@10 v2"));
        for (String part : BookJudgmentCoverage.PARTITIONS) {
            List<Query> group = r.in(part);
            line(out, String.format(Locale.ROOT, "%-9s %-12d %-12d %-12d %d", part,
                    group.stream().filter(Query::answerable).mapToInt(q -> q.v1().relevant()).sum(),
                    group.stream().filter(Query::answerable).mapToInt(q -> q.v2().relevant()).sum(),
                    BookJudgmentCoverage.count(group, BookRecallEvaluator.MAX_K, Row::v1, "U"),
                    BookJudgmentCoverage.count(group, BookRecallEvaluator.MAX_K, Row::v2, "U")));
        }
        line(out, "query denominators do not change (answerable queries keep their split and answerability)");
        line(out, "");

        line(out, "== Projected metrics: v1 labels vs v2 labels on the same hits ==");
        for (String part : BookJudgmentCoverage.PARTITIONS) {
            List<Query> group = r.in(part);
            long answerable = group.stream().filter(Query::answerable).count();
            line(out, "-- " + part + ": answerable " + answerable + " (denominator)");
            line(out, String.format(Locale.ROOT, "%-7s %-8s %-8s %-8s %s", "metric", "v1", "v2", "delta",
                    "v2 upper"));
            for (int k : BookRecallEvaluator.KS) {
                metric(out, "P@" + k, group, m -> m.precision().get(k), m -> m.upperPrecision().get(k));
            }
            for (int k : BookRecallEvaluator.KS) {
                metric(out, "R@" + k, group, m -> m.recall().get(k), null);
            }
            for (int k : BookRecallEvaluator.KS) {
                metric(out, "Hit@" + k, group, m -> m.hit().get(k), m -> m.upperHit().get(k));
            }
            metric(out, "MRR", group, Metrics::reciprocalRank, Metrics::upperReciprocalRank);
            line(out, "");
        }

        line(out, "== Queries whose labels changed (no book text) ==");
        for (Query q : r.queries()) {
            if (q.added().isEmpty()) {
                continue;
            }
            StringBuilder text = new StringBuilder(q.id() + " " + q.split() + " " + q.v1Case().category()
                    + " added " + q.added().size());
            for (Row hit : q.rows()) {
                if (hit.changed()) {
                    text.append(" | #").append(hit.rank()).append(' ').append(hit.v1()).append("->")
                            .append(hit.v2());
                }
            }
            if (q.answerable()) {
                text.append(" | relevant ").append(q.v1().relevant()).append("->").append(q.v2().relevant())
                        .append(" RR ").append(BookRecallReport.num(q.v1().reciprocalRank())).append("->")
                        .append(BookRecallReport.num(q.v2().reciprocalRank())).append(" R@10 ")
                        .append(BookRecallReport.num(q.v1().recall().get(10))).append("->")
                        .append(BookRecallReport.num(q.v2().recall().get(10)));
            }
            line(out, text.toString());
        }
        line(out, "");

        line(out, "== Not claimed ==");
        line(out, "- no retrieval improvement: ranking, scores, encoder, Store and the v1 report are unchanged");
        line(out, "- v2 labels are " + r.v2Manifest().getProperty("provenance") + ": sampled from this ranking's own"
                + " hits, so they are not independent test evidence");
        line(out, "- HOLDOUT was already exposed by the published v1 report and is not fresh independent evidence");
        line(out, "- U stays unknown: unresolved and unselected hits are never turned into negatives");
        return out.toString();
    }

    private static void metric(StringBuilder out, String name, List<Query> group, Function<Metrics, Double> known,
                               Function<Metrics, Double> upper) {
        double v1 = BookJudgmentCoverage.mean(group, q -> known.apply(q.v1()));
        double v2 = BookJudgmentCoverage.mean(group, q -> known.apply(q.v2()));
        String delta = Double.isNaN(v1) ? "n/a" : String.format(Locale.ROOT, "%+.4f", v2 - v1);
        String bound = upper == null ? "-" : BookRecallReport.num(
                BookJudgmentCoverage.mean(group, q -> upper.apply(q.v2())));
        line(out, String.format(Locale.ROOT, "%-7s %-8s %-8s %-8s %s", name, BookRecallReport.num(v1),
                BookRecallReport.num(v2), delta, bound));
    }

    private static long t(List<Query> group, Predicate<Row> filter, String from, String to) {
        return BookJudgmentCoverage.transitions(group, filter, from, to);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
