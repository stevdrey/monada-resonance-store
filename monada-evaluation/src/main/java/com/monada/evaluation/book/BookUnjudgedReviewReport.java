package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookQueryDatasetV2.Decision;
import com.monada.evaluation.book.BookQueryDatasetV2.Review;
import com.monada.evaluation.book.BookUnjudgedSelection.Hit;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import com.monada.evaluation.book.BookUnjudgedSelection.Stratum;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Coverage report of the {@code book-queries-v2} review (issue #177): how many unjudged occurrences the frozen
 * {@code book-recall-v1} trace had, which DEV pairs were selected and why, what the human review decided, and how
 * many occurrences stay unjudged under the v2 labels. It counts labels only: no P@K, R@K or MRR is recomputed, because
 * v1 and v2 have different judgment coverage and their scores are not comparable. No book text is written.
 */
final class BookUnjudgedReviewReport {
    static final String FORMAT = "monada-book-unjudged-review-report";
    static final int FORMAT_VERSION = 1;

    private BookUnjudgedReviewReport() {
    }

    static String render(List<Hit> hits, BookQueryDatasetV2 v2) {
        StringBuilder out = new StringBuilder();
        line(out, "Monada book unjudged-hit review report (Issue #177)");
        line(out, "format=" + FORMAT);
        line(out, "formatVersion=" + FORMAT_VERSION);
        line(out, "status=LABEL COVERAGE ONLY: no ranking was run or changed; v1 and v2 metrics use different judgment"
                + " coverage and must not be compared as a retrieval improvement");
        line(out, "");
        line(out, "== Inputs ==");
        for (String key : List.of("dataset", "extends", "provenance", "base.manifest.sha256", "base.judgments.sha256",
                "selection.baselineReport", "selection.baselineReport.sha256", "selection.rule", "selection.seed",
                "selection.target", "selection.sha256", "review.protocol", "review.sha256", "judgment.draftedBy",
                "judgment.draftedAt", "judgment.verification", "judgment.reviewStatus",
                "judgment.reviewer", "judgment.reviewedAt", "judgment.approvedBy", "reclassification.policy")) {
            line(out, key + "=" + v2.manifest().getProperty(key, ""));
        }
        line(out, "");
        line(out, "== Definitions ==");
        line(out, "occurrence = one ranked top-10 hit of one query in the frozen trace; pair = a distinct (query, canonical"
                + " chunk) of those hits; the trace ranks an atom at most once per query, so per query they coincide");
        line(out, "U = unjudged for that query: unknown, not a false positive");
        line(out, "bias = " + v2.manifest().getProperty("provenance") + ": pairs were sampled from a published run of the"
                + " evaluated system; the review is score-blind but the labels are not an independent prospective gold");
        line(out, "");

        Set<String> answerable = v2.base().cases().stream().filter(Case::answerable).map(Case::id)
                .collect(Collectors.toSet());
        Map<String, Case> v2Cases = v2.cases().stream().collect(Collectors.toMap(Case::id, Function.identity()));
        line(out, "== Unjudged occurrences, frozen v1 labels -> v2 labels ==");
        line(out, String.format(Locale.ROOT, "%-9s %-11s %6s %6s %6s %6s", "partition", "queries", "hits", "v1 U",
                "v2 U", "newly"));
        for (String part : List.of("DEV", "HOLDOUT", "ALL")) {
            Predicate<Hit> inPart = h -> part.equals("ALL") || h.split().name().equals(part);
            for (boolean ans : List.of(true, false)) {
                List<Hit> group = hits.stream().filter(inPart)
                        .filter(h -> answerable.contains(h.queryId()) == ans).toList();
                long v1 = group.stream().filter(Hit::unjudged).count();
                long now = group.stream().filter(h -> !judged(v2Cases.get(h.queryId()), h)).count();
                line(out, String.format(Locale.ROOT, "%-9s %-11s %6d %6d %6d %6d", part,
                        ans ? "answerable" : "NO_ANSWER", group.size(), v1, now, v1 - now));
            }
        }
        line(out, "");

        List<Hit> eligible = hits.stream().filter(h -> h.split() == Split.DEV && h.unjudged()).toList();
        Set<String> chosen = new HashSet<>();
        v2.selection().forEach(p -> chosen.add(p.queryId() + "|" + p.chunkKey()));
        Predicate<Hit> selected = h -> chosen.contains(h.queryId() + "|" + h.chunkKey());
        line(out, "== Selection (DEV only; HOLDOUT hits are never selected) ==");
        line(out, "eligible DEV U: occurrences " + eligible.size() + ", distinct pairs "
                + eligible.stream().map(h -> h.queryId() + "|" + h.chunkKey()).distinct().count());
        line(out, "selected pairs " + v2.selection().size() + " (= occurrences " + eligible.stream().filter(selected)
                .count() + "), target " + BookUnjudgedSelection.TARGET);
        for (Stratum s : Stratum.values()) {
            line(out, "  stratum " + s + " " + v2.selection().stream().filter(p -> p.stratum() == s).count());
        }
        for (int k : List.of(1, 3, 10)) {
            List<Hit> atK = eligible.stream().filter(h -> h.rank() <= k).toList();
            line(out, "coverage of DEV U at rank<=" + k + ": " + atK.stream().filter(selected).count() + "/"
                    + atK.size());
        }
        line(out, "by category (selected/eligible pairs, DEV queries with a selected pair):");
        for (Category c : Category.values()) {
            List<Hit> inCat = eligible.stream().filter(h -> h.category() == c).toList();
            line(out, String.format(Locale.ROOT, "  %-19s %3d/%-3d queries %d/%d", c, inCat.stream()
                            .filter(selected).count(), inCat.size(),
                    inCat.stream().filter(selected).map(Hit::queryId).distinct().count(),
                    inCat.stream().map(Hit::queryId).distinct().count()));
        }
        line(out, "");

        line(out, "== Human review outcome ==");
        List<Review> reviews = v2.reviews();
        line(out, "reviewed " + reviews.size() + "/" + v2.selection().size() + ", pending " + v2.pending().size());
        line(out, "EVIDENCE grade 2 " + count(reviews, Decision.EVIDENCE, 2) + ", EVIDENCE grade 1 "
                + count(reviews, Decision.EVIDENCE, 1) + ", CONFOUNDER grade 0 " + count(reviews, Decision.CONFOUNDER, 0)
                + ", UNRESOLVED " + count(reviews, Decision.UNRESOLVED, -1) + " (stay unjudged)");
        line(out, "NO_ANSWER_CONTRADICTED findings " + v2.findings().size()
                + " (not applied; v1 NO_ANSWER cases are never rewritten)");
        line(out, "judgments: v1 " + v2.base().cases().stream().mapToInt(c -> c.judgments().size()).sum()
                + " unchanged + added " + v2.addedJudgments().size() + " (HOLDOUT added 0)");
        line(out, "");

        line(out, "== Selected pairs (decision only; rank and score stay in selection.psv) ==");
        Map<String, Review> byPair = reviews.stream().collect(Collectors.toMap(Review::pairId, Function.identity()));
        for (Pair p : v2.selection().stream().sorted(Comparator.comparing(Pair::queryId)
                .thenComparing(Pair::chunkKey)).toList()) {
            Review r = byPair.get(p.pairId());
            String status = r == null ? "PENDING" : r.decision() + (r.grade() >= 0 ? " " + r.grade() : "");
            line(out, p.pairId() + " " + p.queryId() + " " + p.chunkKey() + " " + status);
        }
        line(out, "");
        line(out, "== Not claimed ==");
        line(out, "- no retrieval quality change: the ranking, encoder, Store and v1 report are unchanged");
        line(out, "- a re-evaluation of the unchanged ranking on v2 labels is a separate follow-up, reported as different"
                + " judgment coverage; HOLDOUT keeps v1 labels only");
        return out.toString();
    }

    private static boolean judged(Case c, Hit h) {
        return c.judgments().stream().anyMatch(j -> h.keys().contains(j.chunkKey()));
    }

    private static long count(List<Review> reviews, Decision decision, int grade) {
        return reviews.stream().filter(r -> r.decision() == decision && r.grade() == grade).count();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
