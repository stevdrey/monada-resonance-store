package com.monada.evaluation.book;

import com.monada.evaluation.HitAtK;
import com.monada.evaluation.PrecisionAtK;
import com.monada.evaluation.RecallAtK;
import com.monada.evaluation.ReciprocalRank;
import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookUnjudgedSelection.Hit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Projects the <em>same</em> frozen {@code book-recall-v1} ranked query/hit pairs onto the {@code book-queries-v1} and
 * {@code book-queries-v2} judgments (issue #184, evaluation only). Nothing is searched, re-scored or re-encoded: the
 * only ranking input is the per-query trace of the pinned baseline report, and every atom id, rank and score token is
 * carried through untouched. Any difference between the v1 and v2 projections is therefore a difference in label
 * coverage, never in retrieval.
 *
 * <p>Fails closed ({@link IllegalStateException}) on drifted inputs: a baseline, v1 or v2 file other than the pinned
 * bytes; a v2 that is not {@code REVIEWED} or has undecided pairs; a v1 judgment that v2 drops or overwrites; any
 * HOLDOUT judgment change; an added judgment outside the selection or the traced hits; a ranking that is not
 * contiguous and score-descending; and a v1 projection that does not reproduce the frozen per-query and per-partition
 * metrics byte for byte.
 */
final class BookJudgmentCoverage {
    /** Pinned bytes of the committed dataset files; the manifests' own digests could be refreshed by an edit. */
    static final String V1_MANIFEST_SHA256 = "ec4efab61f53dfdaf15e31304e2d39d9815e821042224cc806c8d8a3b7ea3bbc";
    static final String V2_MANIFEST_SHA256 = "35d9af0213c0a3e20277c1aa8045cb83b6ee9992637ffc0aa284c7870c5823cb";
    static final String V2_SELECTION_SHA256 = BookUnjudgedSelection.RECEIPT_SHA256;
    static final String V2_REVIEW_SHA256 = "37dc4627c26edd9a0ff49d1f766672cb2c91293025ab8d9ef64bd415d13b40e6";
    static final List<String> PARTITIONS = List.of("DEV", "HOLDOUT", "ALL");
    static final List<String> LABELS = List.of("E2", "E1", "C0", "U");

    private static final String TRACE_SECTION = "\n== Per-query trace (all hits, raw atom ids) ==\n";
    /** A query head line of the trace; the same shape {@code BookUnjudgedSelection} parses. */
    private static final Pattern TRACE_HEAD = Pattern.compile("([A-Z]{3}-[0-9]{2}) (DEV|HOLDOUT) .*");

    /** One ranked hit of the frozen trace with its label under each judgment version. */
    record Row(String queryId, Split split, Category category, boolean answerable, int rank, String atomId,
               String score, String chunkKey, String v1, String v2) {

        boolean changed() {
            return !v1.equals(v2);
        }
    }

    /**
     * One query's projection under one label version. Metric maps are empty for NO_ANSWER queries. The bounds treat
     * every unjudged hit as relevant ({@code upper*}); the known values treat it as not relevant. R@K has no bound:
     * unjudged chunks outside the top-K make the true relevant set unknown.
     */
    record Metrics(int relevant, int firstRank, double reciprocalRank, Map<Integer, Double> precision,
                   Map<Integer, Double> recall, Map<Integer, Double> hit, Map<Integer, Double> directHit,
                   Map<Integer, Double> upperPrecision, Map<Integer, Double> upperHit, double upperReciprocalRank) {
        Metrics {
            precision = Map.copyOf(precision);
            recall = Map.copyOf(recall);
            hit = Map.copyOf(hit);
            directHit = Map.copyOf(directHit);
            upperPrecision = Map.copyOf(upperPrecision);
            upperHit = Map.copyOf(upperHit);
        }
    }

    /** A query, its unchanged hits, and its v1 and v2 projections; {@code added} are the v2-only judgments. */
    record Query(Case v1Case, Case v2Case, List<Row> rows, List<Judgment> added, Metrics v1, Metrics v2) {
        Query {
            rows = List.copyOf(rows);
            added = List.copyOf(added);
        }

        String id() {
            return v1Case.id();
        }

        Split split() {
            return v1Case.split();
        }

        boolean answerable() {
            return v1Case.answerable();
        }
    }

    record Result(String baselineSha256, String rankingDigest, Properties v1Manifest, Properties v2Manifest,
                  List<Query> queries) {
        Result {
            queries = List.copyOf(queries);
        }

        List<Row> rows() {
            return queries.stream().flatMap(q -> q.rows().stream()).toList();
        }

        List<Query> in(String partition) {
            return queries.stream().filter(q -> partition.equals("ALL") || q.split().name().equals(partition))
                    .toList();
        }
    }

    private BookJudgmentCoverage() {
    }

    /** Checks the bundled dataset files against their pins, then compares on the pinned {@code baseline}. */
    static Result load(byte[] baseline) {
        // Each file is read once: the bytes that are pinned are the bytes that are parsed.
        byte[] v1Manifest = pin("book-queries-v1 manifest.properties", BookQueryDataset.resource("manifest.properties"),
                V1_MANIFEST_SHA256);
        byte[] v2Manifest = pin("book-queries-v2 manifest.properties",
                BookQueryDatasetV2.resource("manifest.properties"), V2_MANIFEST_SHA256);
        byte[] selection = pin("book-queries-v2 selection.psv",
                BookQueryDatasetV2.resource(BookQueryDatasetV2.SELECTION_FILE), V2_SELECTION_SHA256);
        byte[] review = pin("book-queries-v2 review.psv", BookQueryDatasetV2.resource(BookQueryDatasetV2.REVIEW_FILE),
                V2_REVIEW_SHA256);
        // The pinned v1 manifest pins its other files by digest, which BookQueryDataset.parse checks.
        Properties v1Properties = BookQueryDatasetV2.readManifest(v1Manifest);
        BookQueryDataset v1 = BookQueryDataset.parse(v1Properties, resource(v1Properties, "queries.file"),
                resource(v1Properties, "judgments.file"), resource(v1Properties, "chunkMap.file"));
        BookQueryDatasetV2 v2 = BookQueryDatasetV2.parse(BookQueryDatasetV2.readManifest(v2Manifest), v1, v1Manifest,
                selection, review);
        return compare(baseline, BookUnjudgedSelection.BASELINE_SHA256, v2);
    }

    private static byte[] resource(Properties manifest, String key) {
        String name = manifest.getProperty(key);
        check(name != null && !name.isBlank(), "book-queries-v1 manifest is missing " + key);
        return BookQueryDataset.resource(name);
    }

    /** As {@link #load} on already loaded data; tests pass edited traces (with their pin) and edited v2 datasets. */
    static Result compare(byte[] baseline, String pinnedSha256, BookQueryDatasetV2 v2) {
        BookQueryDataset v1 = v2.base();
        List<Hit> hits = BookUnjudgedSelection.parseTrace(baseline, v1, pinnedSha256);
        String text = new String(baseline, StandardCharsets.UTF_8);
        requireOneAtomPerChunk(text, hits);
        check("REVIEWED".equals(v2.manifest().getProperty("judgment.reviewStatus")),
                "book-queries-v2 is not REVIEWED; unapproved labels are never projected");
        check(v2.pending().isEmpty(), "book-queries-v2 has " + v2.pending().size() + " undecided pairs");

        check(v2.cases().stream().map(Case::id).toList().equals(v1.cases().stream().map(Case::id).toList()),
                "book-queries-v2 cases differ from book-queries-v1 in ids or order");
        // The baseline renders queries in id order; the projection keeps that order.
        Map<String, Case> v1Cases = new TreeMap<>();
        v1.cases().forEach(c -> v1Cases.put(c.id(), c));
        Map<String, Case> v2Cases = new TreeMap<>();
        v2.cases().forEach(c -> v2Cases.put(c.id(), c));
        Map<String, String> heads = traceHeads(text);
        check(List.copyOf(heads.keySet()).equals(List.copyOf(v1Cases.keySet())),
                "trace query order is not the id order of book-queries-v1");
        Map<String, List<Hit>> byQuery = new LinkedHashMap<>();
        v1Cases.keySet().forEach(id -> byQuery.put(id, new ArrayList<>()));
        hits.forEach(h -> byQuery.get(h.queryId()).add(h));
        Set<String> selected = v2.selection().stream().map(p -> p.queryId() + "|" + p.chunkKey())
                .collect(Collectors.toSet());
        Map<String, Judgment> reviewed = new LinkedHashMap<>();
        v2.reviews().stream().filter(BookQueryDatasetV2.Review::labels)
                .forEach(r -> reviewed.put(r.queryId() + "|" + r.chunkKey(), expectedJudgment(r)));

        List<Query> queries = new ArrayList<>();
        int addedTotal = 0;
        for (String id : byQuery.keySet()) {
            Case before = v1Cases.get(id);
            Case after = v2Cases.get(id);
            List<Hit> ranked = byQuery.get(before.id());
            requireRanking(before.id(), ranked);
            List<Judgment> added = added(before, after);
            Set<String> traced = ranked.stream().flatMap(h -> h.keys().stream()).collect(Collectors.toSet());
            for (Judgment j : added) {
                check(before.split() == Split.DEV, before.id() + ": HOLDOUT judgments must stay the v1 judgments");
                check(selected.contains(before.id() + "|" + j.chunkKey()),
                        before.id() + ": added judgment on " + j.chunkKey() + " is not a selected pair");
                check(traced.contains(j.chunkKey()),
                        before.id() + ": added judgment on " + j.chunkKey() + " is not a traced hit");
                check(j.equals(reviewed.get(before.id() + "|" + j.chunkKey())), before.id() + ": added judgment on "
                        + j.chunkKey() + " differs from its review decision (role, grade, span or note)");
            }
            addedTotal += added.size();
            Map<String, Judgment> judged = new LinkedHashMap<>();
            after.judgments().forEach(j -> judged.put(j.chunkKey(), j));
            List<Row> rows = new ArrayList<>();
            for (Hit h : ranked) {
                String v2Label = BookRecallEvaluator.label(h.keys(), judged);
                check(h.label().equals(v2Label) || h.unjudged(), before.id() + ": " + h.chunkKey()
                        + " is " + h.label() + " in v1 but " + v2Label + " in v2; v2 may only label unjudged hits");
                rows.add(new Row(before.id(), before.split(), before.category(), before.answerable(), h.rank(),
                        h.atomId(), h.score(), h.chunkKey(), h.label(), v2Label));
            }
            queries.add(new Query(before, after, rows, added, metrics(before, rows, Row::v1),
                    metrics(after, rows, Row::v2)));
        }
        check(addedTotal == reviewed.size(),
                "book-queries-v2 adds " + addedTotal + " judgments, but its reviews label " + reviewed.size());
        Result result = new Result(BookQueryDataset.sha256(baseline), rankingDigest(queries), v1.manifest(),
                v2.manifest(), queries);
        requireBaselineMetrics(text, heads, result);
        return result;
    }

    /** SHA-256 over {@code query|rank|atom|score} rows: identifies the unchanged ranking independent of labels. */
    static String rankingDigest(List<Query> queries) {
        StringBuilder rows = new StringBuilder();
        queries.forEach(q -> q.rows().forEach(r -> rows.append(r.queryId()).append('|').append(r.rank()).append('|')
                .append(r.atomId()).append('|').append(r.score()).append('\n')));
        return BookQueryDataset.sha256(rows.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Mean of {@code metric} over the answerable queries of {@code group}, summed then divided like the baseline. */
    static double mean(List<Query> group, Function<Query, Double> metric) {
        List<Query> answerable = group.stream().filter(Query::answerable).toList();
        return answerable.isEmpty() ? Double.NaN
                : answerable.stream().mapToDouble(metric::apply).sum() / answerable.size();
    }

    /** Hits with {@code label} in the top-{@code k} of the answerable queries of {@code group}. */
    static int count(List<Query> group, int k, Function<Row, String> label, String value) {
        return (int) group.stream().filter(Query::answerable).flatMap(q -> q.rows().stream().limit(k))
                .filter(r -> label.apply(r).equals(value)).count();
    }

    private static Metrics metrics(Case c, List<Row> rows, Function<Row, String> label) {
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> direct = new LinkedHashSet<>();
        for (Judgment j : c.evidence()) {
            evidence.add(j.chunkKey());
            if (j.grade() == 2) {
                direct.add(j.chunkKey());
            }
        }
        if (!c.answerable()) {
            return new Metrics(0, 0, 0.0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0.0);
        }
        List<String> keys = rows.stream().map(Row::chunkKey).toList();
        // Upper bound: every unjudged hit is assumed relevant, under its own key so it never merges with evidence.
        Set<String> optimistic = new LinkedHashSet<>(evidence);
        rows.stream().filter(r -> label.apply(r).equals("U")).forEach(r -> optimistic.add(r.chunkKey()));
        Map<Integer, Double> precision = new TreeMap<>();
        Map<Integer, Double> recall = new TreeMap<>();
        Map<Integer, Double> hit = new TreeMap<>();
        Map<Integer, Double> directHit = new TreeMap<>();
        Map<Integer, Double> upperPrecision = new TreeMap<>();
        Map<Integer, Double> upperHit = new TreeMap<>();
        for (int k : BookRecallEvaluator.KS) {
            precision.put(k, PrecisionAtK.compute(evidence, keys, k));
            recall.put(k, RecallAtK.compute(evidence, keys, k));
            hit.put(k, HitAtK.compute(evidence, keys, k));
            directHit.put(k, HitAtK.compute(direct, keys, k));
            upperPrecision.put(k, PrecisionAtK.compute(optimistic, keys, k));
            upperHit.put(k, HitAtK.compute(optimistic, keys, k));
        }
        int first = 0;
        for (int i = 0; i < keys.size() && first == 0; i++) {
            if (evidence.contains(keys.get(i))) {
                first = i + 1;
            }
        }
        return new Metrics(evidence.size(), first, ReciprocalRank.compute(evidence, keys), precision, recall, hit,
                directHit, upperPrecision, upperHit, ReciprocalRank.compute(optimistic, keys));
    }

    /** The judgment a labelling review must become (as {@code BookQueryDatasetV2} merges it). */
    private static Judgment expectedJudgment(BookQueryDatasetV2.Review r) {
        Role role = r.decision() == BookQueryDatasetV2.Decision.EVIDENCE ? Role.EVIDENCE : Role.CONFOUNDER;
        return new Judgment(r.chunkKey(), r.byteStart(), r.byteEnd(), r.spanSha256(), r.grade(), role, r.note());
    }

    /** The v2-only judgments of a case; the v1 judgments must come first, unchanged and in order. */
    private static List<Judgment> added(Case before, Case after) {
        check(after.split() == before.split() && after.category() == before.category()
                        && after.answerable() == before.answerable(),
                before.id() + ": split, category or answerability differs between v1 and v2");
        List<Judgment> v1 = before.judgments();
        List<Judgment> v2 = after.judgments();
        check(v2.size() >= v1.size() && v2.subList(0, v1.size()).equals(v1),
                before.id() + ": book-queries-v2 drops or overwrites a book-queries-v1 judgment");
        List<Judgment> added = v2.subList(v1.size(), v2.size());
        Set<String> keys = new HashSet<>();
        v1.forEach(j -> keys.add(j.chunkKey()));
        for (Judgment j : added) {
            check(keys.add(j.chunkKey()), before.id() + ": " + j.chunkKey() + " is judged twice");
            check(j.role() == Role.CONFOUNDER ? j.grade() == 0 : j.grade() == 1 || j.grade() == 2,
                    before.id() + ": invalid grade " + j.grade() + " for " + j.role());
        }
        return List.copyOf(added);
    }

    private static void requireRanking(String id, List<Hit> ranked) {
        check(ranked.size() <= BookRecallEvaluator.MAX_K, id + ": more than " + BookRecallEvaluator.MAX_K + " hits");
        for (int i = 0; i < ranked.size(); i++) {
            check(ranked.get(i).rank() == i + 1, id + ": ranks are not contiguous from 1 at " + ranked.get(i).rank());
            double score = score(id, ranked.get(i));
            check(!Double.isNaN(score), id + ": score is not a number at rank " + (i + 1));
            check(i == 0 || score(id, ranked.get(i - 1)) >= score,
                    id + ": scores are not descending at rank " + (i + 1));
        }
    }

    /** The trace accepts any {@code [0-9.Ee+-]} token, so an unparsable one must still fail closed. */
    private static double score(String id, Hit hit) {
        try {
            return Double.parseDouble(hit.score());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(id + ": malformed score token " + hit.score() + " at rank " + hit.rank());
        }
    }

    /**
     * Relevance keys are chunk keys only when every chunk is its own atom: the baseline must report as many stored
     * atoms as chunks, and no hit may carry repeated-text keys.
     */
    private static void requireOneAtomPerChunk(String text, List<Hit> hits) {
        String atoms = header(text, "store.atoms");
        check(atoms != null && atoms.equals(header(text, "chunks.count")),
                "baseline store.atoms differs from chunks.count; repeated-text atoms are not supported here");
        check(hits.stream().allMatch(h -> h.keys().size() == 1), "baseline trace has repeated-text atoms");
    }

    private static String header(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
            if (line.startsWith("== Definitions ==")) {
                break;
            }
        }
        return null;
    }

    /** The query head line of each traced query, in trace order, from one pass over the trace section. */
    private static Map<String, String> traceHeads(String text) {
        int at = text.indexOf(TRACE_SECTION);
        Map<String, String> heads = new LinkedHashMap<>();
        for (String line : text.substring(at + TRACE_SECTION.length()).split("\n")) {
            Matcher head = TRACE_HEAD.matcher(line);
            if (head.matches()) {
                check(heads.put(head.group(1), line) == null, head.group(1) + ": traced twice");
            }
        }
        return heads;
    }

    /**
     * The v1 projection must reproduce the frozen report: each answerable query's trace head metrics and each
     * partition's P/R/Hit/direct, confounder/unjudged hit and MRR lines, as the baseline formats them.
     */
    private static void requireBaselineMetrics(String text, Map<String, String> heads, Result result) {
        for (Query q : result.queries()) {
            String head = heads.get(q.id());
            if (!q.answerable()) {
                check(!head.contains(" firstEvidenceRank="), q.id() + ": NO_ANSWER trace head carries metrics");
                continue;
            }
            check(head.endsWith(headMetrics(q.v1())), q.id() + ": v1 projection does not reproduce the frozen trace"
                    + " metrics; expected suffix" + headMetrics(q.v1()) + " in: " + head);
        }
        List<String> table = List.of(text.substring(0, text.indexOf(TRACE_SECTION)).split("\n", -1));
        for (String partition : PARTITIONS) {
            List<String> expected = partitionLines(partition, result.in(partition));
            // Anchored to this partition's own block: header, column titles, the K rows, then MRR.
            int start = -1;
            for (int i = 0; i < table.size() && start < 0; i++) {
                if (table.get(i).startsWith("-- " + partition + ": queries ")) {
                    start = i;
                }
            }
            check(start >= 0 && start + expected.size() < table.size(),
                    partition + ": the frozen report has no partition block");
            List<String> actual = new ArrayList<>(table.subList(start, start + expected.size() + 1));
            actual.remove(1);
            for (int i = 0; i < expected.size(); i++) {
                check(expected.get(i).equals(actual.get(i)), partition + ": v1 projection does not reproduce the"
                        + " frozen line: expected " + expected.get(i) + ", found " + actual.get(i));
            }
        }
    }

    private static String headMetrics(Metrics m) {
        StringBuilder head = new StringBuilder(" firstEvidenceRank=").append(m.firstRank()).append(" RR=")
                .append(BookRecallReport.num(m.reciprocalRank()));
        for (int k : BookRecallEvaluator.KS) {
            head.append(" P@").append(k).append('=').append(BookRecallReport.num(m.precision().get(k)));
            head.append(" R@").append(k).append('=').append(BookRecallReport.num(m.recall().get(k)));
        }
        return head.toString();
    }

    /** The baseline's partition header, metric rows and MRR line, computed from the v1 projection. */
    static List<String> partitionLines(String partition, List<Query> group) {
        List<String> lines = new ArrayList<>();
        long answerable = group.stream().filter(Query::answerable).count();
        lines.add("-- " + partition + ": queries " + group.size() + ", answerable " + answerable + " (denominator),"
                + " NO_ANSWER " + (group.size() - answerable));
        for (int k : BookRecallEvaluator.KS) {
            lines.add(BookRecallReport.partitionRow(k, mean(group, q -> q.v1().precision().get(k)),
                    mean(group, q -> q.v1().recall().get(k)), mean(group, q -> q.v1().hit().get(k)),
                    mean(group, q -> q.v1().directHit().get(k)), count(group, k, Row::v1, "C0"),
                    count(group, k, Row::v1, "U")));
        }
        lines.add("MRR " + BookRecallReport.num(mean(group, q -> q.v1().reciprocalRank())) + " (n=" + answerable
                + ")");
        return lines;
    }

    /** Hits of {@code group} whose label goes from {@code from} to {@code to}, optionally answerable only. */
    static long transitions(List<Query> group, Predicate<Row> filter, String from, String to) {
        return group.stream().flatMap(q -> q.rows().stream()).filter(filter)
                .filter(r -> r.v1().equals(from) && r.v2().equals(to)).count();
    }

    private static byte[] pin(String name, byte[] bytes, String pinned) {
        String actual = BookQueryDataset.sha256(bytes);
        check(pinned.equals(actual), name + " SHA-256 is " + actual + ", pinned " + pinned);
        return bytes;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
