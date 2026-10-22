package com.monada.evaluation.book;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import com.monada.evaluation.book.BookUnjudgedSelection.Stratum;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code book-queries-v2} (issue #177): an append-only extension of the unchanged {@code book-queries-v1}. It pins
 * the v1 files by SHA-256 and loads them through {@link BookQueryDataset#load()}, then adds the human decisions on a
 * predeclared selection of formerly unjudged DEV hits ({@link BookUnjudgedSelection}). Its provenance is
 * {@value #PROVENANCE}: the pairs were sampled from a published run of the evaluated system, so these labels are not
 * an independent prospective gold standard.
 *
 * <p>Only {@link Decision#EVIDENCE} and {@link Decision#CONFOUNDER} decisions become judgments.
 * {@link Decision#UNRESOLVED} pairs stay unjudged, and {@link Decision#NO_ANSWER_CONTRADICTED} is a review finding
 * that never rewrites a v1 NO_ANSWER case. A dataset is {@code REVIEWED} only with every selected pair decided, a
 * named human reviewer and approver distinct from the drafter, and no open finding unless an explicit reclassification
 * policy is declared.
 */
record BookQueryDatasetV2(Properties manifest, BookQueryDataset base, List<Pair> selection, List<Review> reviews,
                          List<Case> cases) {

    static final String RESOURCE_ROOT = "/com/monada/evaluation/book/book-queries-v2/";
    static final String DATASET = "book-queries-v2";
    static final String FORMAT = "monada-book-queries-extension";
    static final String PROVENANCE = "POST_HOC_RETRIEVAL_SAMPLED_DEV";
    /** The current worksheet: pair id, query, answerability and chunk text only. */
    static final String REVIEW_PROTOCOL = "SCORE_BLIND_WORKSHEET_QUERY_AND_CHUNK_TEXT_ONLY";
    /**
     * The first worksheet, which also printed each pair's chunk key and byte range (no rank, score, stratum or
     * label): a repeated key could reveal a hub chunk. The committed book-queries-v2 review used it.
     */
    static final String LEGACY_REVIEW_PROTOCOL = "SCORE_BLIND_WORKSHEET_QUERY_CHUNK_TEXT_KEY_AND_BYTE_RANGE";
    static final String REVIEW_HEADER = "pairId|queryId|chunkKey|decision|grade|byteStart|byteEnd|spanSha256|note";
    static final String NO_POLICY = "NONE";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** An accepted reclassification policy is a versioned id such as {@code report-only-v1}. */
    private static final Pattern POLICY = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*-v[1-9][0-9]*");
    private static final List<String> REQUIRED_KEYS = List.of("dataset", "datasetVersion", "format", "formatVersion",
            "extends", "base.manifest.sha256", "base.queries.sha256", "base.judgments.sha256", "base.chunkMap.sha256",
            "provenance", "selection.baselineReport", "selection.baselineReport.sha256", "selection.rule",
            "selection.seed", "selection.target", "selection.draftedBy", "selection.file", "selection.sha256",
            "review.protocol", "review.file", "review.sha256", "judgment.grades", "judgment.reviewStatus",
            "reclassification.policy");

    enum Decision { EVIDENCE, CONFOUNDER, UNRESOLVED, NO_ANSWER_CONTRADICTED }

    /** One human decision on a selected pair; span fields are absent ({@code -1}/{@code null}) for UNRESOLVED. */
    record Review(String pairId, String queryId, String chunkKey, Decision decision, int grade, long byteStart,
                  long byteEnd, String spanSha256, String note) {

        boolean labels() {
            return decision == Decision.EVIDENCE || decision == Decision.CONFOUNDER;
        }
    }

    BookQueryDatasetV2 {
        selection = List.copyOf(selection);
        reviews = List.copyOf(reviews);
        cases = List.copyOf(cases);
    }

    /** Judgments added by v2 (in review order), each tagged with its query id. */
    List<Map.Entry<String, Judgment>> addedJudgments() {
        return reviews.stream().filter(Review::labels).map(r -> Map.entry(r.queryId(), toJudgment(r))).toList();
    }

    List<Review> findings() {
        return reviews.stream().filter(r -> r.decision() == Decision.NO_ANSWER_CONTRADICTED).toList();
    }

    /** Selected pairs without a decision yet. */
    List<Pair> pending() {
        var decided = reviews.stream().map(Review::pairId).collect(Collectors.toSet());
        return selection.stream().filter(p -> !decided.contains(p.pairId())).toList();
    }

    /** Loads the bundled v2 over the bundled, unchanged v1. */
    static BookQueryDatasetV2 load() {
        Properties manifest = new Properties();
        try {
            manifest.load(new StringReader(new String(resource("manifest.properties"), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read book-queries-v2 manifest.properties", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("malformed book-queries-v2 manifest.properties: " + e.getMessage(), e);
        }
        return parse(manifest, BookQueryDataset.load(), BookQueryDataset.resource("manifest.properties"),
                resource(require(manifest, "selection.file")), resource(require(manifest, "review.file")));
    }

    static byte[] resource(String name) {
        try (InputStream in = BookQueryDatasetV2.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE_ROOT + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE_ROOT + name, e);
        }
    }

    /** Parses and validates v2; {@code baseManifest} is the raw v1 manifest file that produced {@code base}. */
    static BookQueryDatasetV2 parse(Properties manifest, BookQueryDataset base, byte[] baseManifest,
                                    byte[] selectionBytes, byte[] reviewBytes) {
        BookQueryDatasetV2 dataset = build(manifest, base, baseManifest, selectionBytes, reviewBytes);
        validateCounts(manifest, dataset);
        validateSignOff(manifest, dataset);
        return dataset;
    }

    /** Every check except the manifest counts and the sign-off, which {@link #parse} adds. */
    static BookQueryDatasetV2 build(Properties manifest, BookQueryDataset base, byte[] baseManifest,
                                    byte[] selectionBytes, byte[] reviewBytes) {
        REQUIRED_KEYS.forEach(key -> require(manifest, key));
        check(DATASET.equals(manifest.getProperty("dataset")) && "2".equals(manifest.getProperty("datasetVersion")),
                "manifest dataset is not " + DATASET + " version 2");
        check(FORMAT.equals(manifest.getProperty("format")) && "1".equals(manifest.getProperty("formatVersion")),
                "unsupported dataset format");
        check(BookQueryDataset.DATASET.equals(manifest.getProperty("extends")), "v2 must extend book-queries-v1");
        check(PROVENANCE.equals(manifest.getProperty("provenance")),
                "provenance must be " + PROVENANCE + ", the labels are post-hoc");
        check(BookQueryDataset.GRADES.equals(manifest.getProperty("judgment.grades")), "unsupported judgment.grades");
        check(List.of(REVIEW_PROTOCOL, LEGACY_REVIEW_PROTOCOL).contains(manifest.getProperty("review.protocol")),
                "unsupported review.protocol " + manifest.getProperty("review.protocol"));

        // v1 is pinned, never copied: any change to its manifest or files is drift, not a v2 edit.
        checkDigest(manifest, "base.manifest.sha256", baseManifest);
        Properties v1 = base.manifest();
        check(manifest.getProperty("base.queries.sha256").equals(v1.getProperty("queries.sha256"))
                        && manifest.getProperty("base.judgments.sha256").equals(v1.getProperty("judgments.sha256"))
                        && manifest.getProperty("base.chunkMap.sha256").equals(v1.getProperty("chunkMap.sha256")),
                "book-queries-v1 files differ from the pinned base digests");
        checkCount(manifest, "base.counts.judgments", base.cases().stream().mapToInt(c -> c.judgments().size()).sum());

        check(BookUnjudgedSelection.BASELINE_REPORT.equals(manifest.getProperty("selection.baselineReport"))
                        && BookUnjudgedSelection.BASELINE_SHA256.equals(
                        manifest.getProperty("selection.baselineReport.sha256")),
                "selection must be drawn from the pinned book-recall-v1 report");
        check(BookUnjudgedSelection.RULE.equals(manifest.getProperty("selection.rule"))
                        && BookUnjudgedSelection.SEED.equals(manifest.getProperty("selection.seed"))
                        && String.valueOf(BookUnjudgedSelection.TARGET).equals(manifest.getProperty("selection.target")),
                "unsupported selection rule, seed or target");
        checkDigest(manifest, "selection.sha256", selectionBytes);
        checkDigest(manifest, "review.sha256", reviewBytes);

        Map<String, Case> cases = base.cases().stream()
                .collect(Collectors.toMap(Case::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Pair> selection = BookUnjudgedSelection.parse(selectionBytes);
        validateSelection(selection, cases, base.chunkMap());
        Map<String, Pair> byId = selection.stream()
                .collect(Collectors.toMap(Pair::pairId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Review> reviews = reviews(reviewBytes, byId, cases, base.chunkMap());

        Map<String, List<Judgment>> added = new LinkedHashMap<>();
        reviews.stream().filter(Review::labels)
                .forEach(r -> added.computeIfAbsent(r.queryId(), k -> new ArrayList<>()).add(toJudgment(r)));
        List<Case> merged = cases.values().stream().map(c -> {
            List<Judgment> all = new ArrayList<>(c.judgments());
            all.addAll(added.getOrDefault(c.id(), List.of()));
            return new Case(c.id(), c.split(), c.category(), c.answerable(), c.text(), c.rationale(), all);
        }).toList();

        return new BookQueryDatasetV2(manifest, base, selection, reviews, merged);
    }

    private static void validateSelection(List<Pair> selection, Map<String, Case> cases, Map<String, ChunkRef> chunks) {
        check(!selection.isEmpty(), "selection.psv selects no pair");
        for (Pair p : selection) {
            Case c = cases.get(p.queryId());
            check(c != null, p.pairId() + ": selection names an unknown query " + p.queryId());
            check(p.split() == Split.DEV && c.split() == Split.DEV,
                    p.pairId() + ": " + p.queryId() + " is not DEV; HOLDOUT hits must never be selected");
            check(c.category() == p.category(), p.pairId() + ": category differs from book-queries-v1");
            check(chunks.containsKey(p.chunkKey()), p.pairId() + ": unknown chunk " + p.chunkKey());
            check(c.judgments().stream().noneMatch(j -> j.chunkKey().equals(p.chunkKey())),
                    p.pairId() + ": " + p.queryId() + " already judges " + p.chunkKey() + " in book-queries-v1");
        }
    }

    private static List<Review> reviews(byte[] bytes, Map<String, Pair> selection, Map<String, Case> cases,
                                        Map<String, ChunkRef> chunks) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        check(text.endsWith("\n") && !text.contains("\r"), "review.psv must use LF line ends and end with one");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        check(REVIEW_HEADER.equals(lines[0]), "review.psv header must be " + REVIEW_HEADER);
        Map<String, Review> reviews = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            check(f.length == 9, "review.psv row needs 9 columns: " + lines[i]);
            String id = f[0];
            Pair pair = selection.get(id);
            check(pair != null, id + ": review of a pair that is not in selection.psv");
            check(pair.queryId().equals(f[1]) && pair.chunkKey().equals(f[2]),
                    id + ": review query/chunk differs from selection.psv");
            Decision decision;
            try {
                decision = Decision.valueOf(f[3]);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(id + ": unknown decision " + f[3]);
            }
            check(!f[8].isBlank(), id + ": review note must not be blank");
            Case c = cases.get(pair.queryId());
            Review review;
            if (decision == Decision.UNRESOLVED) {
                check(f[4].equals("-") && f[5].equals("-") && f[6].equals("-") && f[7].equals("-"),
                        id + ": UNRESOLVED stays unjudged; it carries no grade or span, only a reason");
                review = new Review(id, f[1], f[2], decision, -1, -1, -1, null, f[8]);
            } else {
                long grade = number(f[4], id);
                long start = number(f[5], id);
                long end = number(f[6], id);
                check(switch (decision) {
                    case EVIDENCE, NO_ANSWER_CONTRADICTED -> grade == 1 || grade == 2;
                    default -> grade == 0;
                }, id + ": invalid grade " + grade + " for " + decision);
                ChunkRef chunk = chunks.get(f[2]);
                check(0 <= start && start < end && chunk.byteStart() <= start && end <= chunk.byteEnd(),
                        id + ": span [" + start + "," + end + ") is not inside chunk " + chunk.key());
                check(SHA256.matcher(f[7]).matches(), id + ": spanSha256 is not a lowercase SHA-256");
                review = new Review(id, f[1], f[2], decision, (int) grade, start, end, f[7], f[8]);
            }
            // A NO_ANSWER case is never reclassified by a label: evidence on it must be raised as a finding.
            check(decision != Decision.EVIDENCE || c.answerable(), id + ": " + c.id()
                    + " is NO_ANSWER in book-queries-v1; evidence must be NO_ANSWER_CONTRADICTED, not a label");
            check(decision != Decision.NO_ANSWER_CONTRADICTED || !c.answerable(),
                    id + ": NO_ANSWER_CONTRADICTED is only for NO_ANSWER queries");
            check(reviews.put(id, review) == null, id + ": pair is reviewed more than once");
        }
        return List.copyOf(reviews.values());
    }

    /** The manifest {@code counts.*} values implied by the data; {@link #parse} requires the manifest to match. */
    static Map<String, String> expectedCounts(BookQueryDatasetV2 d) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("counts.queries", String.valueOf(d.cases().size()));
        counts.put("counts.selected", String.valueOf(d.selection().size()));
        for (Stratum s : Stratum.values()) {
            counts.put("counts.selected." + s,
                    String.valueOf(d.selection().stream().filter(p -> p.stratum() == s).count()));
        }
        counts.put("counts.reviewed", String.valueOf(d.reviews().size()));
        counts.put("counts.pending", String.valueOf(d.pending().size()));
        for (Decision decision : Decision.values()) {
            counts.put("counts.decision." + decision,
                    String.valueOf(d.reviews().stream().filter(r -> r.decision() == decision).count()));
        }
        counts.put("counts.added.judgments", String.valueOf(d.addedJudgments().size()));
        counts.put("counts.judgments",
                String.valueOf(d.cases().stream().mapToInt(c -> c.judgments().size()).sum()));
        return counts;
    }

    private static void validateCounts(Properties manifest, BookQueryDatasetV2 d) {
        expectedCounts(d).forEach((key, value) -> check(value.equals(require(manifest, key)),
                key + " is " + manifest.getProperty(key) + " but the data has " + value));
    }

    private static void validateSignOff(Properties manifest, BookQueryDatasetV2 d) {
        String status = manifest.getProperty("judgment.reviewStatus");
        check(status.equals("REVIEWED") || status.equals("PENDING_HUMAN_REVIEW"), "unknown review status " + status);
        String policy = manifest.getProperty("reclassification.policy");
        check(NO_POLICY.equals(policy) || POLICY.matcher(policy).matches(), "reclassification.policy must be "
                + NO_POLICY + " or a versioned policy id like report-only-v1, not \"" + policy + "\"");
        if (!status.equals("REVIEWED")) {
            return;
        }
        check(d.pending().isEmpty(), "REVIEWED, but " + d.pending().size() + " selected pairs have no decision");
        String drafter = manifest.getProperty("selection.draftedBy").trim();
        String reviewer = require(manifest, "judgment.reviewer").trim();
        String approver = require(manifest, "judgment.approvedBy").trim();
        check(!reviewer.equalsIgnoreCase(drafter) && !approver.equalsIgnoreCase(drafter),
                "REVIEWED needs a human reviewer and approver other than the drafter " + drafter);
        try {
            LocalDate.parse(require(manifest, "judgment.reviewedAt"));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("judgment.reviewedAt is not an ISO date: "
                    + manifest.getProperty("judgment.reviewedAt"));
        }
        check(d.findings().isEmpty() || !NO_POLICY.equals(policy), "REVIEWED is blocked by " + d.findings().size()
                + " NO_ANSWER_CONTRADICTED findings until an explicit reclassification.policy is accepted");
    }

    private static Judgment toJudgment(Review r) {
        Role role = r.decision() == Decision.EVIDENCE ? Role.EVIDENCE : Role.CONFOUNDER;
        return new Judgment(r.chunkKey(), r.byteStart(), r.byteEnd(), r.spanSha256(), r.grade(), role, r.note());
    }

    private static long number(String value, String id) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(id + ": not a number: " + value);
        }
    }

    private static void checkCount(Properties manifest, String key, long actual) {
        check(String.valueOf(actual).equals(require(manifest, key)),
                key + " is " + manifest.getProperty(key) + " but the data has " + actual);
    }

    private static void checkDigest(Properties manifest, String key, byte[] bytes) {
        check(BookQueryDataset.sha256(bytes).equals(manifest.getProperty(key)), key + " does not match the file");
    }

    private static String require(Properties manifest, String key) {
        String value = manifest.getProperty(key);
        check(value != null && !value.isBlank(), "manifest is missing " + key);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
