package com.monada.evaluation.book;

import static com.monada.evaluation.book.BookQueryProtocolV3.check;
import static com.monada.evaluation.book.BookQueryProtocolV3.checkCount;
import static com.monada.evaluation.book.BookQueryProtocolV3.checkDigest;
import static com.monada.evaluation.book.BookQueryProtocolV3.isoDate;
import static com.monada.evaluation.book.BookQueryProtocolV3.lines;
import static com.monada.evaluation.book.BookQueryProtocolV3.parseEnum;
import static com.monada.evaluation.book.BookQueryProtocolV3.require;
import static com.monada.evaluation.book.BookQueryProtocolV3.utf8;

import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookQueryDataset.Judgment;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryProtocolV3.Slot;
import com.monada.evaluation.book.BookQueryProtocolV3.Split;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
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
 * unjudged, never implicitly non-relevant, and an {@link Decision#UNRESOLVED} case blocks sign-off.
 */
record BookQueryDatasetV3(BookQueryProtocolV3 protocol, Properties manifest, List<Case> cases,
                          Map<String, Review> reviews) {

    static final String MANIFEST_FILE = "book-queries-v3-manifest.properties";
    static final String QUERIES_FILE = "book-queries-v3-queries.psv";
    static final String JUDGMENTS_FILE = "book-queries-v3-judgments.psv";
    static final String REVIEW_FILE = "book-queries-v3-review.psv";
    static final String FORMAT = "monada-book-queries-prospective-dataset";
    static final String QUERIES_HEADER = "slot|split|category|answerable|text|rationale|draftedBy";
    static final String JUDGMENTS_HEADER = BookQueryDataset.JUDGMENTS_HEADER;
    static final String REVIEW_HEADER = "slot|decision|contentSha256|note";
    /** {@code draftedBy} value for a case the human author wrote directly, without any proposed draft. */
    static final String NO_DRAFT = "NONE";
    /** The only accepted {@code draft.verification} when any case was drafted (for example by a model). */
    static final String DRAFT_VERIFICATION = "EACH_DRAFTED_CASE_READ_AGAINST_BODY_AND_APPROVED_BY_REVIEWER";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
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

        List<Judgment> evidence() {
            return judgments.stream().filter(j -> j.role() == Role.EVIDENCE).toList();
        }

        List<Judgment> confounders() {
            return judgments.stream().filter(j -> j.role() == Role.CONFOUNDER).toList();
        }
    }

    BookQueryDatasetV3 {
        Properties copy = new Properties();
        copy.putAll(manifest);
        manifest = copy;
        cases = List.copyOf(cases);
        reviews = Map.copyOf(reviews);
    }

    @Override
    public Properties manifest() {
        Properties copy = new Properties();
        copy.putAll(manifest);
        return copy;
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
        BookQueryProtocolV3 protocol = BookQueryProtocolV3.load(dir);
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        check(Files.isRegularFile(manifestFile), "book-queries-v3 is not authored yet: " + MANIFEST_FILE
                + " is missing");
        Properties manifest = BookQueryProtocolV3.readProperties(BookQueryProtocolV3.read(manifestFile),
                MANIFEST_FILE);
        check(QUERIES_FILE.equals(manifest.getProperty("queries.file"))
                        && JUDGMENTS_FILE.equals(manifest.getProperty("judgments.file"))
                        && REVIEW_FILE.equals(manifest.getProperty("review.file")),
                "queries.file, judgments.file and review.file must be the fixed v3 names");
        return parse(protocol, BookQueryProtocolV3.read(dir.resolve(BookQueryProtocolV3.PROTOCOL_FILE)), manifest,
                BookQueryProtocolV3.read(dir.resolve(QUERIES_FILE)),
                BookQueryProtocolV3.read(dir.resolve(JUDGMENTS_FILE)),
                BookQueryProtocolV3.read(dir.resolve(REVIEW_FILE)), BookQueryDataset.load());
    }

    /**
     * Validates a dataset against its protocol ({@code protocolBytes} are the exact protocol file the manifest pins)
     * and the unchanged {@code book-queries-v1}, which supplies the chunk map and the already published query texts.
     */
    static BookQueryDatasetV3 parse(BookQueryProtocolV3 protocol, byte[] protocolBytes, Properties manifest,
                                    byte[] queries, byte[] judgments, byte[] review, BookQueryDataset v1) {
        REQUIRED_KEYS.forEach(key -> require(manifest, key));
        BookQueryProtocolV3.rejectScoreLikeKeys(manifest.stringPropertyNames(), MANIFEST_FILE);
        check(BookQueryProtocolV3.DATASET.equals(manifest.getProperty("dataset"))
                && "1".equals(manifest.getProperty("datasetVersion")), "manifest dataset is not book-queries-v3");
        check(FORMAT.equals(manifest.getProperty("format")) && "1".equals(manifest.getProperty("formatVersion")),
                "unsupported dataset format");
        checkDigest(manifest, "protocol.sha256", protocolBytes);
        check(protocol.manifest().getProperty("plan.sha256").equals(manifest.getProperty("plan.sha256")),
                "plan.sha256 differs from the protocol plan");
        check(BookQueryDataset.PROTOCOL.equals(manifest.getProperty("authoring.protocol")),
                "authoring.protocol must be " + BookQueryDataset.PROTOCOL);
        check("0".equals(manifest.getProperty("authoring.retrievalRunsBeforeFreeze")),
                "labels must be frozen before any retrieval run");
        check(BookQueryDataset.GRADES.equals(manifest.getProperty("judgment.grades")), "unsupported judgment.grades");
        checkDigest(manifest, "queries.sha256", queries);
        checkDigest(manifest, "judgments.sha256", judgments);
        checkDigest(manifest, "review.sha256", review);

        Map<String, Slot> plan = protocol.slotsById();
        Map<String, String> queryLines = new LinkedHashMap<>();
        Map<String, Case> parsed = queries(queries, plan, v1, queryLines);
        Map<String, List<String>> judgmentLines = new HashMap<>();
        Map<String, List<Judgment>> byCase = judgments(judgments, parsed.keySet(), v1.chunkMap(), judgmentLines);
        List<Case> cases = new ArrayList<>();
        for (Case c : parsed.values()) {
            Case full = new Case(c.slot(), c.answerable(), c.text(), c.rationale(), c.draftedBy(),
                    byCase.getOrDefault(c.slot().id(), List.of()));
            validateCase(full, v1.chunkMap());
            cases.add(full);
        }
        Map<String, Review> reviews = reviews(review, queryLines, judgmentLines);

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

    private static Map<String, List<Judgment>> judgments(byte[] bytes, Set<String> ids, Map<String, ChunkRef> chunks,
                                                         Map<String, List<String>> judgmentLines) {
        List<String> lines = lines(bytes, JUDGMENTS_FILE);
        check(!lines.isEmpty() && JUDGMENTS_HEADER.equals(lines.get(0)), JUDGMENTS_FILE + " header must be "
                + JUDGMENTS_HEADER);
        Map<String, List<Judgment>> byCase = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\\|", -1);
            check(f.length == 8, JUDGMENTS_FILE + " row needs 8 columns: " + line);
            String id = f[0];
            check(ids.contains(id), "orphan judgment for unknown query: " + id);
            ChunkRef chunk = chunks.get(f[1]);
            check(chunk != null, id + ": orphan judgment for unknown chunk key " + f[1]);
            long start = number(f[2], id);
            long end = number(f[3], id);
            check(0 <= start && start < end, id + ": orphan span [" + start + "," + end + ") is empty or negative");
            // v1 bounds every chunk by the source, so containment also keeps the span inside the source.
            check(chunk.byteStart() <= start && end <= chunk.byteEnd(), id + ": orphan span [" + start + "," + end
                    + ") is not inside chunk " + chunk.key());
            check(SHA256.matcher(f[4]).matches(), id + ": spanSha256 is not a lowercase SHA-256");
            long grade = number(f[5], id);
            Role role = parseEnum(Role.class, f[6], id, "role");
            check(role == Role.EVIDENCE ? grade == 1 || grade == 2 : grade == 0,
                    id + ": contradictory label, " + role + " with grade " + grade + " on " + chunk.key());
            check(!f[7].isBlank(), id + ": judgment note must not be blank");
            check(seen.add(id + "\u0000" + chunk.key()),
                    id + ": contradictory label, chunk " + chunk.key() + " is judged more than once");
            byCase.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new Judgment(chunk.key(), start, end, f[4], (int) grade, role, f[7]));
            judgmentLines.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        return byCase;
    }

    private static void validateCase(Case c, Map<String, ChunkRef> chunks) {
        String id = c.slot().id();
        int evidence = c.evidence().size();
        if (!c.answerable()) {
            check(evidence == 0, id + ": contradictory label, NO_ANSWER case must not have evidence");
            return;
        }
        check(evidence > 0, id + ": answerable case has no evidence");
        check(c.evidence().stream().anyMatch(j -> j.grade() == 2), id + ": no evidence directly answers it");
        switch (c.slot().category()) {
            case MULTI_EVIDENCE -> {
                IntSummaryStatistics ordinals = c.evidence().stream()
                        .mapToInt(j -> chunks.get(j.chunkKey()).ordinal()).summaryStatistics();
                check(ordinals.getMax() - ordinals.getMin() >= 2,
                        id + ": MULTI_EVIDENCE needs evidence in at least two non-adjacent chunks");
            }
            case AMBIGUOUS -> check(evidence >= 2, id + ": AMBIGUOUS needs evidence in at least two chunks");
            case CONFOUNDER -> check(!c.confounders().isEmpty(), id + ": CONFOUNDER case has no confounder");
            default -> { }
        }
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

    private static void validateSignOff(BookQueryProtocolV3 protocol, Properties manifest, List<Case> cases,
                                        Map<String, Review> reviews) {
        Set<String> drafters = new LinkedHashSet<>();
        cases.stream().map(Case::draftedBy).filter(d -> !d.equals(NO_DRAFT)).forEach(drafters::add);
        BookQueryProtocolV3.validateSignOff(manifest, "review", drafters);
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
            isoDate(manifest, "draft.draftedAt");
        }
        LocalDate protocolSigned = isoDate(protocol.manifest(), "protocol.reviewedAt");
        LocalDate started = isoDate(manifest, "authoring.startedAt");
        check(!started.isBefore(protocolSigned), "authoring started before the protocol was signed");
        check(!isoDate(manifest, "review.reviewedAt").isBefore(started), "review.reviewedAt precedes authoring");
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

    private static long number(String value, String id) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(id + ": not a number: " + value);
        }
    }
}
