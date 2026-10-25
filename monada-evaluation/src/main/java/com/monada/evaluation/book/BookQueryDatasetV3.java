package com.monada.evaluation.book;

import static com.monada.evaluation.book.BookQueryDataset.check;
import static com.monada.evaluation.book.BookQueryDataset.checkCount;
import static com.monada.evaluation.book.BookQueryDataset.checkDigest;
import static com.monada.evaluation.book.BookQueryDataset.lines;
import static com.monada.evaluation.book.BookQueryDataset.parseEnum;
import static com.monada.evaluation.book.BookQueryDataset.require;
import static com.monada.evaluation.book.BookQueryDataset.utf8;
import static com.monada.evaluation.book.BookQueryProtocolV3.isoDate;

import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryProtocolV3.Slot;
import com.monada.evaluation.book.BookQueryProtocolV3.Split;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validator for a {@code book-queries-v3} dataset (issue #185): prospectively authored queries that fill the
 * predeclared {@link BookQueryProtocolV3} slots, their judgments over the frozen {@code book-queries-v1} chunk map,
 * and a per-case human review receipt. Issue #185 ships no such dataset; Phase II 3/10 authors one.
 *
 * <p>Fail closed: until {@code review.status=REVIEWED}, with a signed protocol, all 40 slots approved by a named human
 * reviewer and approver who are neither a drafter nor a model, no case is available for evaluation. FINAL_ONLY cases
 * stay unavailable until a separate final gate is declared ({@code finalGate.status=OPEN}). Unlisted chunks are
 * unjudged, never implicitly non-relevant, and an {@link Decision#UNRESOLVED} case blocks sign-off. Instances exist
 * only through {@link #parse} or {@link #load}, so the gates cannot be bypassed by constructing one.
 */
final class BookQueryDatasetV3 {

    static final String MANIFEST_FILE = "book-queries-v3-manifest.properties";
    static final String QUERIES_FILE = "book-queries-v3-queries.psv";
    static final String JUDGMENTS_FILE = "book-queries-v3-judgments.psv";
    static final String REVIEW_FILE = "book-queries-v3-review.psv";
    static final String FORMAT = "monada-book-queries-prospective-dataset";
    static final String QUERIES_HEADER = "slot|split|category|answerable|text|rationale|draftedBy";
    /** Same schema and rules as v1 ({@link BookQueryDataset#judgments}). */
    static final String JUDGMENTS_HEADER = BookQueryDataset.JUDGMENTS_HEADER;
    static final String REVIEW_HEADER = "slot|decision|contentSha256|note";
    /** {@code draftedBy} value for a case the human author wrote directly, without any proposed draft. */
    static final String NO_DRAFT = "NONE";
    /** The only accepted {@code draft.verification} when any case was drafted (for example by a model). */
    static final String DRAFT_VERIFICATION = "EACH_DRAFTED_CASE_READ_AGAINST_BODY_AND_APPROVED_BY_REVIEWER";

    private static final Pattern ISSUE = Pattern.compile("#[1-9][0-9]*");
    private static final List<String> REQUIRED_KEYS = List.of("dataset", "datasetVersion", "format",
            "formatVersion", "protocol.sha256", "plan.sha256", "queries.file", "queries.sha256", "judgments.file",
            "judgments.sha256", "review.file", "review.sha256", "authoring.protocol",
            "authoring.retrievalRunsBeforeFreeze", "authoring.startedAt", "judgment.grades", "review.status",
            "finalGate.status", "counts.queries", "counts.judgments", "counts.approved");

    /** Per-case review outcome; only {@link #APPROVED} counts toward sign-off. */
    enum Decision { APPROVED, UNRESOLVED, REJECTED }

    record Review(String slot, Decision decision, String contentSha256, String note) {
    }

    record Case(Slot slot, boolean answerable, String text, String rationale, String draftedBy,
                List<Judgment> judgments) {
        Case {
            judgments = List.copyOf(judgments);
        }
    }

    private final BookQueryProtocolV3 protocol;
    private final Properties manifest;
    private final List<Case> cases;
    private final Map<String, Review> reviews;

    private BookQueryDatasetV3(BookQueryProtocolV3 protocol, Properties manifest, List<Case> cases,
                               Map<String, Review> reviews) {
        this.protocol = protocol;
        this.manifest = new Properties();
        this.manifest.putAll(manifest);
        this.cases = List.copyOf(cases);
        // Receipt-file order, so anything rendered from the receipts is deterministic.
        this.reviews = Collections.unmodifiableMap(new LinkedHashMap<>(reviews));
    }

    BookQueryProtocolV3 protocol() {
        return protocol;
    }

    /** A copy: the validated status cannot be changed through it. */
    Properties manifest() {
        Properties copy = new Properties();
        copy.putAll(manifest);
        return copy;
    }

    /**
     * Every review receipt by slot, in receipt-file order. Receipts carry reviewer notes about their cases, so they are
     * gated exactly like {@link #cases()}: only once the dataset is reviewed and the final gate is open.
     */
    Map<String, Review> reviews() {
        check(reviewed() && finalGateOpen(), "all book-queries-v3 receipts need human sign-off and an open final gate;"
                + " the dataset is " + manifest.getProperty("review.status") + " and the final gate is "
                + manifest.getProperty("finalGate.status"));
        return reviews;
    }

    /** DEV receipts, gated like {@link #devCases()}. */
    Map<String, Review> devReviews() {
        check(reviewed(), "book-queries-v3 is " + manifest.getProperty("review.status")
                + ": no receipt is available before human sign-off");
        return reviewsOf(Split.DEV);
    }

    /** FINAL_ONLY receipts, gated like {@link #finalOnlyCases()}. */
    Map<String, Review> finalOnlyReviews() {
        check(reviewed() && finalGateOpen(), "FINAL_ONLY receipts are not available: the final gate is "
                + manifest.getProperty("finalGate.status"));
        return reviewsOf(Split.FINAL_ONLY);
    }

    private Map<String, Review> reviewsOf(Split split) {
        Map<String, Slot> plan = protocol.slotsById();
        Map<String, Review> selected = new LinkedHashMap<>();
        reviews.forEach((slot, review) -> {
            if (plan.get(slot).split() == split) {
                selected.put(slot, review);
            }
        });
        return Collections.unmodifiableMap(selected);
    }

    /** Every case, FINAL_ONLY included: only once the dataset is reviewed and the final gate is open. */
    List<Case> cases() {
        check(reviewed() && finalGateOpen(), "all book-queries-v3 cases need human sign-off and an open final gate;"
                + " the dataset is " + manifest.getProperty("review.status") + " and the final gate is "
                + manifest.getProperty("finalGate.status"));
        return cases;
    }

    /** Slot ids that have a query, for progress checks; no text, answer or judgment. */
    List<String> authoredSlots() {
        return cases.stream().map(c -> c.slot().id()).toList();
    }

    boolean reviewed() {
        return BookQueryProtocolV3.REVIEWED.equals(manifest.getProperty("review.status"));
    }

    boolean finalGateOpen() {
        return "OPEN".equals(manifest.getProperty("finalGate.status"));
    }

    /** DEV cases for inspection and tuning; refused until the dataset is human-reviewed. */
    List<Case> devCases() {
        check(reviewed(), "book-queries-v3 is " + manifest.getProperty("review.status")
                + ": no case is available for evaluation before human sign-off");
        return cases.stream().filter(c -> c.slot().split() == Split.DEV).toList();
    }

    /** FINAL_ONLY cases; refused unless the dataset is reviewed and the separate final gate is declared open. */
    List<Case> finalOnlyCases() {
        check(reviewed() && finalGateOpen(),
                "FINAL_ONLY cases are not available: the final gate is " + manifest.getProperty("finalGate.status"));
        return cases.stream().filter(c -> c.slot().split() == Split.FINAL_ONLY).toList();
    }

    /** Loads the dataset next to the protocol; fails when it has not been authored yet. */
    static BookQueryDatasetV3 load(Path dir) {
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        check(Files.isRegularFile(manifestFile), "book-queries-v3 is not authored yet: " + MANIFEST_FILE
                + " is missing");
        Properties manifest = BookQueryProtocolV3.readProperties(BookQueryProtocolV3.read(manifestFile),
                MANIFEST_FILE);
        check(QUERIES_FILE.equals(manifest.getProperty("queries.file"))
                        && JUDGMENTS_FILE.equals(manifest.getProperty("judgments.file"))
                        && REVIEW_FILE.equals(manifest.getProperty("review.file")),
                "queries.file, judgments.file and review.file must be the fixed v3 names");
        return parse(BookQueryProtocolV3.read(dir.resolve(BookQueryProtocolV3.PROTOCOL_FILE)),
                BookQueryProtocolV3.read(dir.resolve(BookQueryProtocolV3.PLAN_FILE)), manifest,
                BookQueryProtocolV3.read(dir.resolve(QUERIES_FILE)),
                BookQueryProtocolV3.read(dir.resolve(JUDGMENTS_FILE)),
                BookQueryProtocolV3.read(dir.resolve(REVIEW_FILE)), BookQueryDataset.load());
    }

    /**
     * Validates a dataset. The protocol is parsed here from {@code protocolBytes}, the exact bytes the manifest pins,
     * so sign-off is always checked against the pinned protocol. The unchanged {@code book-queries-v1} supplies the
     * chunk map and the already published query texts.
     */
    static BookQueryDatasetV3 parse(byte[] protocolBytes, byte[] planBytes, Properties manifest, byte[] queries,
                                    byte[] judgments, byte[] review, BookQueryDataset v1) {
        REQUIRED_KEYS.forEach(key -> require(manifest, key));
        BookQueryProtocolV3.rejectScoreLikeKeys(manifest.stringPropertyNames(), MANIFEST_FILE);
        check(BookQueryProtocolV3.DATASET.equals(manifest.getProperty("dataset"))
                && "1".equals(manifest.getProperty("datasetVersion")), "manifest dataset is not book-queries-v3");
        check(FORMAT.equals(manifest.getProperty("format")) && "1".equals(manifest.getProperty("formatVersion")),
                "unsupported dataset format");
        checkDigest(manifest, "protocol.sha256", protocolBytes);
        BookQueryProtocolV3 protocol = BookQueryProtocolV3.parse(
                BookQueryProtocolV3.readProperties(protocolBytes, BookQueryProtocolV3.PROTOCOL_FILE), planBytes,
                v1.manifest());
        check(protocol.manifest().getProperty("plan.sha256").equals(manifest.getProperty("plan.sha256")),
                "plan.sha256 differs from the protocol plan");
        check(BookQueryDataset.PROTOCOL.equals(manifest.getProperty("authoring.protocol")),
                "authoring.protocol must be " + BookQueryDataset.PROTOCOL);
        check("0".equals(manifest.getProperty("authoring.retrievalRunsBeforeFreeze")),
                "labels must be frozen before any retrieval run");
        check(BookQueryDataset.GRADES.equals(manifest.getProperty("judgment.grades")), "unsupported judgment.grades");
        validateDates(manifest);
        checkDigest(manifest, "queries.sha256", queries);
        checkDigest(manifest, "judgments.sha256", judgments);
        checkDigest(manifest, "review.sha256", review);

        Map<String, String> queryLines = new LinkedHashMap<>();
        Map<String, Case> parsed = queries(queries, protocol.slotsById(), v1, queryLines);
        Map<String, List<Judgment>> byCase = BookQueryDataset.judgments(judgments, parsed.keySet(), v1.chunkMap());
        List<Case> cases = new ArrayList<>();
        for (Case c : parsed.values()) {
            Case full = new Case(c.slot(), c.answerable(), c.text(), c.rationale(), c.draftedBy(),
                    byCase.getOrDefault(c.slot().id(), List.of()));
            // The v1 per-category rules; they never read the split, so the v1 placeholder split is irrelevant.
            BookQueryDataset.validateCase(new BookQueryDataset.Case(full.slot().id(), BookQueryDataset.Split.DEV,
                    full.slot().category(), full.answerable(), full.text(), full.rationale(), full.judgments()),
                    v1.chunkMap());
            cases.add(full);
        }
        Map<String, Review> reviews = reviews(review, queryLines, judgmentLines(judgments));

        checkCount(manifest, "counts.queries", cases.size());
        checkCount(manifest, "counts.judgments", cases.stream().mapToInt(c -> c.judgments().size()).sum());
        checkCount(manifest, "counts.approved",
                reviews.values().stream().filter(r -> r.decision() == Decision.APPROVED).count());
        validateSignOff(protocol, manifest, cases, reviews);
        validateFinalGate(manifest);
        return new BookQueryDatasetV3(protocol, manifest, cases, reviews);
    }

    /** SHA-256 of the case's query row and its judgment rows (file order), each LF-terminated: what was reviewed. */
    static String contentDigest(String queryLine, List<String> judgmentLines) {
        StringBuilder content = new StringBuilder(queryLine).append('\n');
        judgmentLines.forEach(line -> content.append(line).append('\n'));
        return BookQueryDataset.sha256(utf8(content.toString()));
    }

    /** Case-, punctuation- and spacing-insensitive form used to detect a repeated question. */
    static String normalizedText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static Map<String, Case> queries(byte[] bytes, Map<String, Slot> plan, BookQueryDataset v1,
                                             Map<String, String> queryLines) {
        List<String> lines = lines(bytes, QUERIES_FILE);
        check(!lines.isEmpty() && QUERIES_HEADER.equals(lines.get(0)), QUERIES_FILE + " header must be "
                + QUERIES_HEADER);
        Set<String> published = new HashSet<>();
        v1.cases().forEach(c -> published.add(normalizedText(c.text())));
        Map<String, String> texts = new HashMap<>();
        Map<String, Case> cases = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 7, QUERIES_FILE + " row needs 7 columns: " + line);
            Slot slot = plan.get(f[0]);
            check(slot != null, "query for a slot that is not in the plan: " + f[0]);
            String id = slot.id();
            check(!cases.containsKey(id), "duplicate query for slot " + id);
            check(parseEnum(Split.class, f[1], id, "split") == slot.split(),
                    id + ": split " + f[1] + " differs from the plan (" + slot.split() + ")");
            check(parseEnum(Category.class, f[2], id, "category") == slot.category(),
                    id + ": category " + f[2] + " differs from the plan (" + slot.category() + ")");
            check(f[3].equals("true") || f[3].equals("false"), id + ": answerable must be true or false");
            boolean answerable = Boolean.parseBoolean(f[3]);
            check(answerable == (slot.category() != Category.NO_ANSWER),
                    id + ": contradictory label, answerable must be false exactly for NO_ANSWER");
            check(!f[4].isBlank() && !f[5].isBlank() && !f[6].isBlank(),
                    id + ": query text, rationale and draftedBy must not be blank");
            String normalized = normalizedText(f[4]);
            check(!published.contains(normalized), id + ": duplicate query, the text repeats a book-queries-v1 case");
            String other = texts.putIfAbsent(normalized, id);
            check(other == null, id + ": duplicate query, the text repeats " + other);
            cases.put(id, new Case(slot, answerable, f[4], f[5], f[6].trim(), List.of()));
            queryLines.put(id, line);
        }
        return cases;
    }

    /** Raw judgment rows by query id, in file order; called after {@link BookQueryDataset#judgments} validated them. */
    private static Map<String, List<String>> judgmentLines(byte[] bytes) {
        List<String> lines = lines(bytes, JUDGMENTS_FILE);
        Map<String, List<String>> byCase = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            byCase.computeIfAbsent(line.substring(0, line.indexOf('|')), k -> new ArrayList<>()).add(line);
        }
        return byCase;
    }

    private static Map<String, Review> reviews(byte[] bytes, Map<String, String> queryLines,
                                               Map<String, List<String>> judgmentLines) {
        List<String> lines = lines(bytes, REVIEW_FILE);
        check(!lines.isEmpty() && REVIEW_HEADER.equals(lines.get(0)), REVIEW_FILE + " header must be "
                + REVIEW_HEADER);
        Map<String, Review> reviews = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 4, REVIEW_FILE + " row needs 4 columns: " + line);
            String id = f[0];
            check(queryLines.containsKey(id), "orphan review receipt for unknown query: " + id);
            Decision decision = parseEnum(Decision.class, f[1], id, "decision");
            // The receipt names the exact rows the reviewer read; any later edit invalidates it.
            check(contentDigest(queryLines.get(id), judgmentLines.getOrDefault(id, List.of())).equals(f[2]),
                    id + ": review receipt contentSha256 does not match the reviewed query and judgments");
            check(decision == Decision.APPROVED || !f[3].isBlank(), id + ": a " + decision + " receipt needs a note");
            check(reviews.put(id, new Review(id, decision, f[2], f[3])) == null, "duplicate review receipt: " + id);
        }
        return reviews;
    }

    /** Dates that hold in every status: ISO formats, and drafting no earlier than authoring. */
    private static void validateDates(Properties manifest) {
        LocalDate started = isoDate(manifest, "authoring.startedAt");
        if (manifest.containsKey("draft.draftedAt")) {
            check(!isoDate(manifest, "draft.draftedAt").isBefore(started), "draft.draftedAt precedes authoring");
        }
    }

    private static void validateSignOff(BookQueryProtocolV3 protocol, Properties manifest, List<Case> cases,
                                        Map<String, Review> reviews) {
        Set<String> drafters = new LinkedHashSet<>();
        cases.stream().map(Case::draftedBy).filter(d -> !d.equals(NO_DRAFT)).forEach(drafters::add);
        LocalDate started = isoDate(manifest, "authoring.startedAt");
        LocalDate lastDraft = manifest.containsKey("draft.draftedAt") ? isoDate(manifest, "draft.draftedAt") : started;
        BookQueryProtocolV3.validateSignOff(manifest, "review", drafters, lastDraft);
        if (!BookQueryProtocolV3.REVIEWED.equals(manifest.getProperty("review.status"))) {
            return;
        }
        check(protocol.reviewed(), "REVIEWED, but the protocol is not signed by a human reviewer");
        check(cases.size() == protocol.slots().size(), "REVIEWED, but only " + cases.size() + " of "
                + protocol.slots().size() + " slots have a query");
        for (Case c : cases) {
            Review r = reviews.get(c.slot().id());
            check(r != null, c.slot().id() + ": REVIEWED, but the case has no human review receipt");
            check(r.decision() == Decision.APPROVED, c.slot().id() + ": REVIEWED, but the case is " + r.decision());
        }
        if (!drafters.isEmpty()) {
            check(DRAFT_VERIFICATION.equals(manifest.getProperty("draft.verification")),
                    "drafted cases (" + String.join(", ", drafters) + ") need draft.verification="
                            + DRAFT_VERIFICATION);
            require(manifest, "draft.draftedAt");
        }
        check(!started.isBefore(isoDate(protocol.manifest(), "protocol.reviewedAt")),
                "authoring started before the protocol was signed");
    }

    private static void validateFinalGate(Properties manifest) {
        String gate = manifest.getProperty("finalGate.status");
        check(gate.equals("CLOSED") || gate.equals("OPEN"), "unknown finalGate.status " + gate);
        if (gate.equals("CLOSED")) {
            return;
        }
        check(BookQueryProtocolV3.REVIEWED.equals(manifest.getProperty("review.status")),
                "the final gate cannot open over an unreviewed dataset");
        check(ISSUE.matcher(require(manifest, "finalGate.issue")).matches(),
                "finalGate.issue must name the declaring issue, like #190");
        require(manifest, "finalGate.candidate");
        check(!isoDate(manifest, "finalGate.declaredAt").isBefore(isoDate(manifest, "review.reviewedAt")),
                "the final gate cannot be declared before the review");
    }
}
