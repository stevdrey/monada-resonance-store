package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookQueryProtocolV3.Slot;
import com.monada.evaluation.book.BookQueryProtocolV3.Split;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Invalid {@code book-queries-v3} datasets are rejected. Each case starts from a complete synthetic fixture that
 * fills all 40 committed slots over the real, unchanged v1 chunk map (span digests are synthetic, no book text), is
 * approved per case and signed. File digests, counts and, unless a test keeps them stale, review receipts are
 * recomputed after the mutation, so only the intended defect remains.
 */
class BookQueryDatasetV3RejectionTest {
    private static final String SPAN = "b".repeat(64);
    private static final String REVIEWER = "Steven Rey Sequeira";
    private static BookQueryDataset V1;
    private static BookQueryProtocolV3 PROTOCOL;
    private static byte[] PROTOCOL_BYTES;
    private static byte[] PLAN_BYTES;
    private static List<ChunkRef> CHUNKS;

    /** Query rows by slot, judgment rows in file order, review rows by slot (recomputed unless {@link #staleReview}). */
    private final Map<String, String> queries = new LinkedHashMap<>();
    private final List<String> judgments = new ArrayList<>();
    private final Map<String, String> decisions = new LinkedHashMap<>();
    private final List<String> extraReview = new ArrayList<>();
    private final Properties manifest = new Properties();
    private Map<String, String> staleReview;

    @BeforeAll
    static void loadBase() {
        V1 = BookQueryDataset.load();
        Properties signed = BookQueryProtocolV3Test.committedManifest();
        BookQueryProtocolV3Test.sign(signed);
        PROTOCOL_BYTES = properties(signed);
        PLAN_BYTES = BookQueryProtocolV3.read(BookQueryProtocolV3Test.DIR.resolve(BookQueryProtocolV3.PLAN_FILE));
        PROTOCOL = BookQueryProtocolV3.parse(signed, PLAN_BYTES, V1.manifest());
        CHUNKS = V1.chunkMap().values().stream().sorted(Comparator.comparingInt(ChunkRef::ordinal)).toList();
    }

    BookQueryDatasetV3RejectionTest() {
        int i = 0;
        for (Slot slot : PROTOCOL.slots()) {
            boolean answerable = slot.category() != Category.NO_ANSWER;
            queries.put(slot.id(), String.join("|", slot.id(), slot.split().name(), slot.category().name(),
                    String.valueOf(answerable), "Synthetic prospective question number " + i + "?",
                    "synthetic fixture rationale", BookQueryDatasetV3.NO_DRAFT));
            ChunkRef chunk = CHUNKS.get(i % (CHUNKS.size() - 2));
            switch (slot.category()) {
                case NO_ANSWER -> judgment(slot.id(), chunk, 0, "CONFOUNDER");
                case MULTI_EVIDENCE -> {
                    judgment(slot.id(), chunk, 2, "EVIDENCE");
                    judgment(slot.id(), CHUNKS.get(chunk.ordinal() + 2), 1, "EVIDENCE");
                }
                case AMBIGUOUS -> {
                    judgment(slot.id(), chunk, 2, "EVIDENCE");
                    judgment(slot.id(), CHUNKS.get(chunk.ordinal() + 1), 2, "EVIDENCE");
                }
                case CONFOUNDER -> {
                    judgment(slot.id(), chunk, 2, "EVIDENCE");
                    judgment(slot.id(), CHUNKS.get(chunk.ordinal() + 1), 0, "CONFOUNDER");
                }
                default -> judgment(slot.id(), chunk, 2, "EVIDENCE");
            }
            decisions.put(slot.id(), "APPROVED|");
            i++;
        }
        manifest.setProperty("dataset", "book-queries-v3");
        manifest.setProperty("datasetVersion", "1");
        manifest.setProperty("format", BookQueryDatasetV3.FORMAT);
        manifest.setProperty("formatVersion", "1");
        manifest.setProperty("plan.sha256", PROTOCOL.manifest().getProperty("plan.sha256"));
        manifest.setProperty("queries.file", BookQueryDatasetV3.QUERIES_FILE);
        manifest.setProperty("judgments.file", BookQueryDatasetV3.JUDGMENTS_FILE);
        manifest.setProperty("review.file", BookQueryDatasetV3.REVIEW_FILE);
        manifest.setProperty("authoring.protocol", BookQueryDataset.PROTOCOL);
        manifest.setProperty("authoring.retrievalRunsBeforeFreeze", "0");
        manifest.setProperty("authoring.startedAt", "2026-10-25");
        manifest.setProperty("judgment.grades", BookQueryDataset.GRADES);
        manifest.setProperty("review.status", BookQueryProtocolV3.REVIEWED);
        manifest.setProperty("review.reviewer", REVIEWER);
        manifest.setProperty("review.approvedBy", "stevdrey");
        manifest.setProperty("review.reviewedAt", "2026-10-30");
        manifest.setProperty("finalGate.status", "CLOSED");
    }

    @Test
    void theSignedFixtureLoadsAndExposesOnlyDevCases() {
        BookQueryDatasetV3 v3 = load();
        assertEquals(28, v3.devCases().size());
        assertTrue(v3.devCases().stream().allMatch(c -> c.slot().split() == Split.DEV));
        var e = assertThrows(IllegalStateException.class, v3::finalOnlyCases);
        assertTrue(e.getMessage().contains("final gate is CLOSED"), e.getMessage());
        e = assertThrows(IllegalStateException.class, v3::cases);
        assertTrue(e.getMessage().contains("final gate is CLOSED"), "the accessor must not leak FINAL_ONLY cases");
        assertEquals(40, v3.authoredSlots().size());
        assertEquals(PROTOCOL.slots().stream().filter(s -> s.split() == Split.DEV).map(Slot::id).toList(),
                List.copyOf(v3.devReviews().keySet()), "DEV receipts keep their file order");
    }

    @Test
    void finalOnlyReceiptNotesStayBehindTheGate() {
        String finalSlot = PROTOCOL.slots().stream().filter(s -> s.split() == Split.FINAL_ONLY).findFirst()
                .orElseThrow().id();
        String note = "reviewer note that hints at the FINAL_ONLY answer";
        decisions.put(finalSlot, "APPROVED|" + note);
        BookQueryDatasetV3 closed = load();
        var e = assertThrows(IllegalStateException.class, closed::reviews);
        assertTrue(e.getMessage().contains("final gate is CLOSED"), e.getMessage());
        e = assertThrows(IllegalStateException.class, closed::finalOnlyReviews);
        assertTrue(e.getMessage().contains("final gate is CLOSED"), e.getMessage());
        assertEquals(28, closed.devReviews().size());
        assertTrue(closed.devReviews().values().stream().noneMatch(r -> r.note().contains(note)),
                "no FINAL_ONLY note is reachable through the DEV receipts");

        manifest.setProperty("finalGate.status", "OPEN");
        manifest.setProperty("finalGate.issue", "#190");
        manifest.setProperty("finalGate.candidate", "frozen candidate named by #190");
        manifest.setProperty("finalGate.declaredAt", "2026-11-05");
        BookQueryDatasetV3 open = load();
        assertEquals(note, open.finalOnlyReviews().get(finalSlot).note());
        assertEquals(12, open.finalOnlyReviews().size());
        assertEquals(40, open.reviews().size());
    }

    @Test
    void finalOnlyCasesNeedASeparatelyDeclaredGate() {
        manifest.setProperty("finalGate.status", "OPEN");
        rejects("finalGate.issue");
        manifest.setProperty("finalGate.issue", "#190");
        rejects("finalGate.candidate");
        manifest.setProperty("finalGate.candidate", "frozen candidate named by #190");
        manifest.setProperty("finalGate.declaredAt", "2026-10-29");
        rejects("cannot be declared before the review");
        manifest.setProperty("finalGate.declaredAt", "2026-11-05");
        assertEquals(12, load().finalOnlyCases().size());
        assertEquals(40, load().cases().size());
        manifest.setProperty("finalGate.status", "AJAR");
        rejects("unknown finalGate.status");
    }

    @Test
    void aPendingDatasetFailsClosed() {
        manifest.setProperty("review.status", BookQueryProtocolV3.PENDING);
        manifest.remove("review.reviewer");
        manifest.remove("review.approvedBy");
        manifest.remove("review.reviewedAt");
        List.copyOf(queries.keySet()).subList(10, queries.size()).forEach(this::dropCase);
        decisions.put(queries.keySet().iterator().next(), "UNRESOLVED|reviewer cannot confirm the answer span");
        BookQueryDatasetV3 v3 = load();
        assertEquals(10, v3.authoredSlots().size());
        var e = assertThrows(IllegalStateException.class, v3::devCases);
        assertTrue(e.getMessage().contains("before human sign-off"), e.getMessage());
        assertThrows(IllegalStateException.class, v3::finalOnlyCases);
        assertThrows(IllegalStateException.class, v3::cases, "the accessor must not bypass the review gate");
        for (var accessor : List.<Runnable>of(v3::reviews, v3::devReviews, v3::finalOnlyReviews)) {
            assertThrows(IllegalStateException.class, accessor::run, "no receipt note before human sign-off");
        }
        manifest.setProperty("finalGate.status", "OPEN");
        rejects("cannot open over an unreviewed dataset");
    }

    @Test
    void rejectsAMissingSignature() {
        for (String key : List.of("review.reviewer", "review.approvedBy", "review.reviewedAt")) {
            String value = (String) manifest.remove(key);
            rejects("manifest is missing " + key);
            manifest.setProperty(key, value);
        }
        manifest.setProperty("review.reviewedAt", "yesterday");
        rejects("not an ISO date");
    }

    @Test
    void rejectsAModelOnlyDraftPresentedAsReviewed() {
        queries.replaceAll((slot, row) -> row.substring(0, row.lastIndexOf('|') + 1) + "claude");
        manifest.setProperty("review.reviewer", "claude");
        rejects("is not a human reviewer");
        manifest.setProperty("review.reviewer", "Codex review agent");
        rejects("is not a human reviewer");
        manifest.setProperty("review.reviewer", REVIEWER);
        manifest.setProperty("review.approvedBy", "GPT-5");
        rejects("is not a human reviewer");
        manifest.setProperty("review.approvedBy", "stevdrey");
        rejects("draft.verification=" + BookQueryDatasetV3.DRAFT_VERIFICATION);
        manifest.setProperty("draft.verification", BookQueryDatasetV3.DRAFT_VERIFICATION);
        rejects("draft.draftedAt");
        manifest.setProperty("draft.draftedAt", "2026-10-26");
        load();
    }

    @Test
    void rejectsAReviewerWhoIsAlsoADrafter() {
        String slot = queries.keySet().iterator().next();
        queries.computeIfPresent(slot, (k, row) -> row.substring(0, row.lastIndexOf('|') + 1) + "stevdrey");
        manifest.setProperty("draft.verification", BookQueryDatasetV3.DRAFT_VERIFICATION);
        manifest.setProperty("draft.draftedAt", "2026-10-26");
        rejects("other than the drafter stevdrey");
    }

    @Test
    void rejectsSignOffWithoutAnApprovedReceiptForEveryCase() {
        String slot = queries.keySet().iterator().next();
        decisions.remove(slot);
        rejects(slot + ": REVIEWED, but the case has no human review receipt");
        decisions.put(slot, "UNRESOLVED|answer span is disputed");
        rejects(slot + ": REVIEWED, but the case is UNRESOLVED");
        decisions.put(slot, "REJECTED|reads as a paraphrase of a published case");
        rejects(slot + ": REVIEWED, but the case is REJECTED");
        decisions.put(slot, "UNRESOLVED|");
        rejects("receipt needs a note");
        decisions.put(slot, "APPROVED|");
        dropCase(slot);
        rejects("REVIEWED, but only 39 of 40 slots have a query");
    }

    @Test
    void rejectsAnEditAfterReview() {
        snapshotReview();
        String slot = queries.keySet().iterator().next();
        queries.computeIfPresent(slot, (k, row) -> row.replace("number 0?", "number 0, rewritten?"));
        rejects(slot + ": review receipt contentSha256 does not match");
    }

    @Test
    void rejectsSignOffOverAnUnsignedProtocolOrBeforeIt() {
        Properties draft = BookQueryProtocolV3Test.committedManifest();
        BookQueryProtocolV3Test.unsign(draft);
        byte[] unsigned = properties(draft);
        var e = assertThrows(IllegalStateException.class, () -> build(unsigned, m -> { }));
        assertTrue(e.getMessage().contains("the protocol is not signed"), e.getMessage());
        manifest.setProperty("authoring.startedAt", "2026-10-23");
        rejects("authoring started before the protocol was signed");
        manifest.setProperty("authoring.startedAt", "2026-10-31");
        rejects("review.reviewedAt precedes what it reviews");
        manifest.setProperty("authoring.startedAt", "2026-10-25");
        rejects("protocol.sha256 does not match", m -> m.setProperty("protocol.sha256", "0".repeat(64)));
        manifest.setProperty("plan.sha256", "0".repeat(64));
        rejects("plan.sha256 differs from the protocol plan");
    }

    @Test
    void rejectsMalformedOrInconsistentDatesInAnyStatus() {
        manifest.setProperty("review.status", BookQueryProtocolV3.PENDING);
        manifest.setProperty("authoring.startedAt", "soon");
        rejects("authoring.startedAt is not an ISO date");
        manifest.setProperty("authoring.startedAt", "2026-10-25");
        manifest.setProperty("draft.draftedAt", "2026-10-24");
        rejects("draft.draftedAt precedes authoring");
        manifest.setProperty("review.status", BookQueryProtocolV3.REVIEWED);
        manifest.setProperty("draft.draftedAt", "2026-10-31");
        rejects("review.reviewedAt precedes what it reviews (2026-10-31)");
    }

    @Test
    void rejectsDuplicateQueries() {
        List<String> slots = List.copyOf(queries.keySet());
        String first = slots.get(0);
        String second = slots.get(1);
        String original = queries.get(second);
        setText(second, "  synthetic PROSPECTIVE question -- number 0 ");
        rejects(second + ": duplicate query, the text repeats " + first);
        setText(second, V1.cases().get(0).text().toUpperCase());
        rejects(second + ": duplicate query, the text repeats a book-queries-v1 case");
        queries.put(second, original);
        queries.put(first + "#dup", queries.get(first).replace("number 0?", "number 99?"));
        rejects("duplicate query for slot " + first);
    }

    @Test
    void rejectsQueriesThatLeaveThePlan() {
        String slot = queries.keySet().iterator().next();
        String row = queries.get(slot);
        queries.put(slot, row.replaceFirst("\\|DEV\\|", "|FINAL_ONLY|"));
        rejects(slot + ": split FINAL_ONLY differs from the plan");
        queries.put(slot, row.replaceFirst("\\|LITERAL\\|", "|PARAPHRASE|"));
        rejects(slot + ": category PARAPHRASE differs from the plan");
        queries.put(slot, row.replaceFirst("^V3-LIT-01", "V3-LIT-09"));
        rejects("slot that is not in the plan: V3-LIT-09");
        queries.put(slot, row + "|0.73");
        rejects("row needs 7 columns");
        queries.put(slot, row);
        manifest.setProperty("baseline.top1Rank", "3");
        rejects("must not carry retrieval output: baseline.top1Rank");
    }

    @Test
    void rejectsOrphanSpans() {
        String slot = queries.keySet().iterator().next();
        ChunkRef chunk = CHUNKS.get(0);
        judgments.add(row("V3-LIT-99", chunk.key(), chunk.byteStart(), chunk.byteStart() + 1, 1, "EVIDENCE"));
        rejects("orphan judgment for unknown query: V3-LIT-99");
        judgments.set(judgments.size() - 1, row(slot, chunk.key() + "x", 0, 1, 1, "EVIDENCE"));
        rejects("unknown chunk key");
        ChunkRef other = CHUNKS.get(5);
        judgments.set(judgments.size() - 1, row(slot, other.key(), other.byteEnd() - 1, other.byteEnd() + 4, 1,
                "EVIDENCE"));
        rejects("is not inside chunk " + other.key());
        judgments.set(judgments.size() - 1, row(slot, other.key(), other.byteStart() + 3, other.byteStart() + 3, 1,
                "EVIDENCE"));
        rejects("is empty or negative");
        judgments.remove(judgments.size() - 1);
        extraReview.add("V3-LIT-99|APPROVED|" + SPAN + "|");
        rejects("orphan review receipt for unknown query: V3-LIT-99");
    }

    @Test
    void rejectsLabelContradictions() {
        String literal = "V3-LIT-01";
        String first = judgments.stream().filter(j -> j.startsWith(literal + "|")).findFirst().orElseThrow();
        judgments.add(first.replace("|2|EVIDENCE|", "|0|CONFOUNDER|"));
        rejects("chunk " + first.split("\\|")[1] + " is judged more than once");
        judgments.remove(judgments.size() - 1);
        replaceJudgment(literal, "|2|EVIDENCE|", "|0|EVIDENCE|");
        rejects("contradictory label, EVIDENCE with grade 0");
        replaceJudgment(literal, "|0|EVIDENCE|", "|2|EVIDENCE|");
        String noAnswer = "V3-NOA-01";
        replaceJudgment(noAnswer, "|0|CONFOUNDER|", "|1|CONFOUNDER|");
        rejects("contradictory label, CONFOUNDER with grade 1");
        replaceJudgment(noAnswer, "|1|CONFOUNDER|", "|1|EVIDENCE|");
        rejects(noAnswer + ": NO_ANSWER case must not have evidence");
        replaceJudgment(noAnswer, "|1|EVIDENCE|", "|0|CONFOUNDER|");
        queries.computeIfPresent(noAnswer, (k, row) -> row.replace("|NO_ANSWER|false|", "|NO_ANSWER|true|"));
        rejects(noAnswer + ": contradictory label, answerable must be false exactly for NO_ANSWER");
    }

    @Test
    void rejectsIncompleteCategoryEvidence() {
        judgments.removeIf(j -> j.startsWith("V3-MUL-01|") && j.contains("|1|EVIDENCE|"));
        rejects("V3-MUL-01: MULTI_EVIDENCE needs evidence in at least two non-adjacent chunks");
    }

    // ----- fixture -----

    private void judgment(String slot, ChunkRef chunk, int grade, String role) {
        judgments.add(row(slot, chunk.key(), chunk.byteStart(), chunk.byteStart() + 8, grade, role));
    }

    private static String row(String slot, String key, long start, long end, int grade, String role) {
        return String.join("|", slot, key, String.valueOf(start), String.valueOf(end), SPAN, String.valueOf(grade),
                role, "synthetic judgment note");
    }

    private void setText(String slot, String text) {
        String[] f = queries.get(slot).split("\\|", -1);
        f[4] = text;
        queries.put(slot, String.join("|", f));
    }

    private void replaceJudgment(String slot, String from, String to) {
        judgments.replaceAll(j -> j.startsWith(slot + "|") ? j.replace(from, to) : j);
    }

    private void dropCase(String slot) {
        queries.remove(slot);
        decisions.remove(slot);
        judgments.removeIf(j -> j.startsWith(slot + "|"));
    }

    /** Freezes the current receipts so a later edit leaves them stale. */
    private void snapshotReview() {
        staleReview = new LinkedHashMap<>();
        decisions.forEach((slot, decision) -> staleReview.put(slot, receipt(slot, decision)));
    }

    private String receipt(String slot, String decision) {
        String[] d = decision.split("\\|", -1);
        List<String> own = judgments.stream().filter(j -> j.startsWith(slot + "|")).toList();
        return String.join("|", slot, d[0], BookQueryDatasetV3.contentDigest(queries.get(slot), own), d[1]);
    }

    private BookQueryDatasetV3 load() {
        return build(PROTOCOL_BYTES, m -> { });
    }

    private BookQueryDatasetV3 build(byte[] protocolBytes, Consumer<Properties> edit) {
        List<String> review = new ArrayList<>(List.of(BookQueryDatasetV3.REVIEW_HEADER));
        decisions.forEach((slot, decision) -> review.add(staleReview != null && staleReview.containsKey(slot)
                ? staleReview.get(slot) : receipt(slot, decision)));
        review.addAll(extraReview);
        List<String> queryRows = new ArrayList<>(List.of(BookQueryDatasetV3.QUERIES_HEADER));
        queryRows.addAll(queries.values());
        List<String> judgmentRows = new ArrayList<>(List.of(BookQueryDatasetV3.JUDGMENTS_HEADER));
        judgmentRows.addAll(judgments);
        byte[] q = bytes(queryRows);
        byte[] j = bytes(judgmentRows);
        byte[] r = bytes(review);
        Properties m = new Properties();
        m.putAll(manifest);
        m.setProperty("protocol.sha256", BookQueryDataset.sha256(protocolBytes));
        m.setProperty("queries.sha256", BookQueryDataset.sha256(q));
        m.setProperty("judgments.sha256", BookQueryDataset.sha256(j));
        m.setProperty("review.sha256", BookQueryDataset.sha256(r));
        m.setProperty("counts.queries", String.valueOf(queries.size()));
        m.setProperty("counts.judgments", String.valueOf(judgments.size()));
        m.setProperty("counts.approved",
                String.valueOf(decisions.values().stream().filter(d -> d.startsWith("APPROVED|")).count()));
        edit.accept(m);
        return BookQueryDatasetV3.parse(protocolBytes, PLAN_BYTES, m, q, j, r, V1);
    }

    private void rejects(String message) {
        rejects(message, m -> { });
    }

    private void rejects(String message, Consumer<Properties> edit) {
        var e = assertThrows(IllegalStateException.class, () -> build(PROTOCOL_BYTES, edit));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static byte[] bytes(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] properties(Properties p) {
        StringBuilder text = new StringBuilder();
        new java.util.TreeMap<>(p).forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
