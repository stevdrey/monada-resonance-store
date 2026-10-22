package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.ChunkRef;
import com.monada.evaluation.book.BookUnjudgedSelection.Pair;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Invalid v2 extensions are rejected. Each case starts from the real, unchanged v1, the bundled v2 manifest and the
 * committed 60-pair receipt, with no decision yet; {@link #decideRest} decides the remaining pairs for cases that need
 * a complete review. Digests and counts are recomputed after the mutation, so only the intended defect remains. Span
 * digests are synthetic.
 */
class BookQueryDatasetV2RejectionTest {
    private static final String KEY = "book-corpus-v1/book-chunking-v1/";
    private static final String SPAN = "b".repeat(64);
    private static BookQueryDataset BASE;
    private static byte[] BASE_MANIFEST;
    private static Properties MANIFEST;
    private static Pair ANSWERABLE;
    private static Pair NO_ANSWER;
    private static List<Pair> COMMITTED;

    private final List<String> selection = new ArrayList<>();
    private final List<String> review = new ArrayList<>(List.of(BookQueryDatasetV2.REVIEW_HEADER));
    /** Raw review bytes that replace {@link #review} when set (for byte-level defects). */
    private byte[] rawReview;

    @BeforeAll
    static void loadBase() throws Exception {
        BASE = BookQueryDataset.load();
        BASE_MANIFEST = BookQueryDataset.resource("manifest.properties");
        MANIFEST = new Properties();
        MANIFEST.load(new StringReader(new String(BookQueryDatasetV2.resource("manifest.properties"),
                StandardCharsets.UTF_8)));
        List<Pair> committed = BookUnjudgedSelection.parse(BookQueryDatasetV2.resource("selection.psv"));
        COMMITTED = committed;
        ANSWERABLE = committed.stream().filter(p -> p.category() != Category.NO_ANSWER).findFirst().orElseThrow();
        NO_ANSWER = committed.stream().filter(p -> p.category() == Category.NO_ANSWER).findFirst().orElseThrow();
    }

    BookQueryDatasetV2RejectionTest() {
        selection.add(BookUnjudgedSelection.HEADER);
        String receipt = new String(BookQueryDatasetV2.resource("selection.psv"), StandardCharsets.UTF_8);
        selection.addAll(List.of(receipt.split("\n")).subList(1, BookUnjudgedSelection.TARGET + 1));
    }

    @Test
    void aPendingSelectionLoadsWithoutLabels() {
        BookQueryDatasetV2 v2 = load(m -> { });
        assertEquals(BookUnjudgedSelection.TARGET, v2.pending().size());
        assertTrue(v2.addedJudgments().isEmpty());
    }

    @Test
    void decisionsBecomeJudgmentsOnlyAfterSignOff() {
        decideBoth();
        BookQueryDatasetV2 pending = load(m -> { });
        assertEquals(BookUnjudgedSelection.TARGET, pending.reviews().size(), "decisions are kept as review progress");
        assertTrue(pending.addedJudgments().isEmpty(), "unapproved decisions are not gold");
        assertEquals(BASE.cases(), pending.cases(), "before sign-off v2 exposes the v1 labels only");
        BookQueryDatasetV2 signed = load(BookQueryDatasetV2RejectionTest::signOff);
        assertEquals(BookUnjudgedSelection.TARGET, signed.addedJudgments().size());
        // Counts stored while unsigned stay valid after the manual sign-off edit.
        assertEquals(pending.manifest().getProperty("counts.added.judgments"),
                signed.manifest().getProperty("counts.added.judgments"));
        assertEquals("115", signed.manifest().getProperty("counts.judgments"));
    }

    @Test
    void rejectsAManifestThatIsNotValidUtf8() {
        byte[] bytes = "judgment.reviewer=Ste\n".getBytes(StandardCharsets.UTF_8);
        byte[] broken = Arrays.copyOf(bytes, bytes.length + 1);
        broken[bytes.length - 1] = (byte) 0xFF;
        broken[bytes.length] = '\n';
        var e = assertThrows(IllegalStateException.class, () -> BookQueryDatasetV2.readManifest(broken));
        assertEquals("book-queries-v2 manifest.properties is not valid UTF-8", e.getMessage());
        assertEquals("Ste", BookQueryDatasetV2.readManifest(bytes).getProperty("judgment.reviewer"));
    }

    @Test
    void rejectsAReviewThatIsNotValidUtf8() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        byte[] valid = bytes(review);
        // Corrupt one byte of the note into an invalid UTF-8 lead byte; digests are refreshed for these exact bytes.
        rawReview = valid.clone();
        rawReview[rawReview.length - 3] = (byte) 0xFF;
        reject("review.psv is not valid UTF-8", m -> { });
    }

    @Test
    void aFullyReviewedSignedOffSelectionLoadsAndAppendsJudgments() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        review.add(row(NO_ANSWER, "CONFOUNDER", "0", 0, 20));
        decideRest();
        BookQueryDatasetV2 v2 = load(BookQueryDatasetV2RejectionTest::signOff);
        assertEquals(BookUnjudgedSelection.TARGET, v2.addedJudgments().size());
        Case extended = v2.cases().stream().filter(c -> c.id().equals(ANSWERABLE.queryId())).findFirst().orElseThrow();
        assertTrue(extended.judgments().stream().anyMatch(j -> j.chunkKey().equals(ANSWERABLE.chunkKey())
                && j.role() == BookQueryDataset.Role.EVIDENCE && j.grade() == 2));
    }

    // ---- sign-off

    @Test
    void rejectsReviewedWhilePairsArePending() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        reject("REVIEWED, but 59 selected pairs have no decision", BookQueryDatasetV2RejectionTest::signOff);
    }

    @Test
    void rejectsReviewedWithoutAReviewer() {
        decideBoth();
        reject("manifest is missing judgment.reviewer", m -> {
            signOff(m);
            m.setProperty("judgment.reviewer", "");
        });
    }

    @Test
    void rejectsAnApprovalByTheDrafter() {
        decideBoth();
        reject("REVIEWED needs a human reviewer and approver other than the drafter claude", m -> {
            signOff(m);
            m.setProperty("judgment.approvedBy", "Claude");
        });
    }

    @Test
    void rejectsAnApprovalByTheLabelDrafter() {
        decideBoth();
        reject("REVIEWED needs a human reviewer and approver other than the drafter codex", m -> {
            signOff(m);
            m.setProperty("judgment.approvedBy", "Codex");
        });
    }

    @Test
    void rejectsDraftedLabelsWithoutPerDecisionVerificationOrDraftDate() {
        decideBoth();
        reject("drafted labels are REVIEWED only with judgment.verification=", m -> {
            signOff(m);
            m.setProperty("judgment.verification", "SPOT_CHECKED");
        });
        reject("judgment.draftedAt is not an ISO date: soon", m -> {
            signOff(m);
            m.setProperty("judgment.draftedAt", "soon");
        });
        reject("manifest is missing judgment.draftedBy", m -> m.setProperty("judgment.draftedBy", ""));
    }

    @Test
    void undraftedLabelsNeedNoDraftMetadata() {
        decideBoth();
        load(m -> {
            signOff(m);
            m.setProperty("judgment.draftedBy", BookQueryDatasetV2.NO_DRAFT);
            m.remove("judgment.draftedAt");
            m.remove("judgment.verification");
        });
    }

    @Test
    void rejectsAReviewDateThatIsNotIso() {
        decideBoth();
        reject("judgment.reviewedAt is not an ISO date: yesterday", m -> {
            signOff(m);
            m.setProperty("judgment.reviewedAt", "yesterday");
        });
    }

    @Test
    void rejectsAnUnknownReviewStatus() {
        reject("unknown review status APPROVED", m -> m.setProperty("judgment.reviewStatus", "APPROVED"));
    }

    // ---- digests and provenance

    @Test
    void rejectsAnAlteredSelectionDigest() {
        reject("selection.sha256 does not match the file", m -> m.setProperty("selection.sha256", SPAN));
    }

    @Test
    void rejectsAnotherBaselineReport() {
        reject("selection must be drawn from the pinned book-recall-v1 report",
                m -> m.setProperty("selection.baselineReport.sha256", SPAN));
    }

    @Test
    void rejectsAMissingPostHocProvenance() {
        reject("provenance must be POST_HOC_RETRIEVAL_SAMPLED_DEV, the labels are post-hoc",
                m -> m.setProperty("provenance", "PROSPECTIVE"));
    }

    @Test
    void acceptsOnlyTheKnownReviewProtocols() {
        load(m -> m.setProperty("review.protocol", BookQueryDatasetV2.REVIEW_PROTOCOL));
        load(m -> m.setProperty("review.protocol", BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL));
        reject("unsupported review.protocol FULLY_BLIND", m -> m.setProperty("review.protocol", "FULLY_BLIND"));
    }

    @Test
    void rejectsRenamedSelectionOrReviewFiles() {
        String expected = "selection.file and review.file must be selection.psv and review.psv";
        reject(expected, m -> m.setProperty("review.file", "review-v2.psv"));
        reject(expected, m -> m.setProperty("selection.file", "receipt.psv"));
    }

    @Test
    void rejectsAnEditedV1Manifest() {
        byte[] edited = (new String(BASE_MANIFEST, StandardCharsets.UTF_8) + "# edited\n")
                .getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(IllegalStateException.class, () -> parse(edited, BASE, m -> { }));
        assertEquals("base.manifest.sha256 does not match the file", e.getMessage());
    }

    @Test
    void rejectsChangedV1Judgments() {
        Properties v1 = new Properties();
        v1.putAll(BASE.manifest());
        v1.setProperty("judgments.sha256", SPAN);
        BookQueryDataset changed = new BookQueryDataset(v1, BASE.cases(), BASE.chunkMap());
        var e = assertThrows(IllegalStateException.class, () -> parse(BASE_MANIFEST, changed, m -> { }));
        assertEquals("book-queries-v1 files differ from the pinned base digests", e.getMessage());
    }

    @Test
    void rejectsADroppedV1Judgment() {
        List<Case> cases = new ArrayList<>(BASE.cases());
        Case first = cases.getFirst();
        cases.set(0, new Case(first.id(), first.split(), first.category(), first.answerable(), first.text(),
                first.rationale(), first.judgments().subList(1, first.judgments().size())));
        BookQueryDataset changed = new BookQueryDataset(BASE.manifest(), cases, BASE.chunkMap());
        var e = assertThrows(IllegalStateException.class, () -> parse(BASE_MANIFEST, changed, m -> { }));
        assertEquals("base.counts.judgments is 55 but the data has 54", e.getMessage());
    }

    // ---- selection

    @Test
    void rejectsAReceiptTheRuleDidNotProduce() {
        // Same 60 valid DEV pairs, other order: digests and counts refreshed, still not the predeclared receipt.
        String first = selection.remove(1);
        selection.add(first);
        reject("selection.psv is not the receipt that " + BookUnjudgedSelection.RULE, m -> { });
    }

    @Test
    void rejectsATruncatedSelection() {
        selection.removeLast();
        reject("selection.psv has 59 pairs; the predeclared rule selects exactly 60", m -> { });
    }

    @Test
    void rejectsAHoldoutPair() {
        selection.add(selectionRow("LIT-02", "HOLDOUT", "LITERAL", "s001/c0003"));
        reject("LIT-02 is not DEV; HOLDOUT hits must never be selected", m -> { });
    }

    @Test
    void rejectsAPairThatV1AlreadyJudges() {
        selection.add(selectionRow("LIT-01", "DEV", "LITERAL", "s001/c0004"));
        reject("LIT-01 already judges " + KEY + "s001/c0004 in book-queries-v1", m -> { });
    }

    @Test
    void rejectsAMissingChunk() {
        selection.add(selectionRow("LIT-01", "DEV", "LITERAL", "s099/c0000"));
        reject("unknown chunk " + KEY + "s099/c0000", m -> { });
    }

    @Test
    void rejectsAChangedCategory() {
        selection.add(selectionRow("LIT-01", "DEV", "PARAPHRASE", "s002/c0000"));
        reject("category differs from book-queries-v1", m -> { });
    }

    // ---- review rows

    @Test
    void rejectsInvalidGrades() {
        review.add(row(ANSWERABLE, "EVIDENCE", "0", 10, 40));
        reject("invalid grade 0 for EVIDENCE", m -> { });
        review.set(1, row(ANSWERABLE, "EVIDENCE", "3", 10, 40));
        reject("invalid grade 3 for EVIDENCE", m -> { });
        review.set(1, row(ANSWERABLE, "CONFOUNDER", "1", 10, 40));
        reject("invalid grade 1 for CONFOUNDER", m -> { });
    }

    @Test
    void rejectsASpanOutsideItsChunk() {
        ChunkRef chunk = BASE.chunkMap().get(ANSWERABLE.chunkKey());
        long length = chunk.byteEnd() - chunk.byteStart();
        review.add(row(ANSWERABLE, "EVIDENCE", "2", length - 5, length + 5));
        reject("is not inside chunk " + ANSWERABLE.chunkKey(), m -> { });
    }

    @Test
    void rejectsEvidenceThatSilentlyReclassifiesANoAnswerQuery() {
        review.add(row(NO_ANSWER, "EVIDENCE", "2", 0, 20));
        reject(NO_ANSWER.queryId() + " is NO_ANSWER in book-queries-v1; evidence must be NO_ANSWER_CONTRADICTED",
                m -> { });
    }

    @Test
    void rejectsANoAnswerFindingOnAnAnswerableQuery() {
        review.add(row(ANSWERABLE, "NO_ANSWER_CONTRADICTED", "2", 10, 40));
        reject("NO_ANSWER_CONTRADICTED is only for NO_ANSWER queries", m -> { });
    }

    @Test
    void aNoAnswerFindingBlocksSignOffUntilAPolicyIsAcceptedAndIsNeverALabel() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        review.add(row(NO_ANSWER, "NO_ANSWER_CONTRADICTED", "2", 0, 20));
        decideRest();
        reject("REVIEWED is blocked by 1 NO_ANSWER_CONTRADICTED findings", BookQueryDatasetV2RejectionTest::signOff);
        BookQueryDatasetV2 v2 = load(m -> {
            signOff(m);
            m.setProperty("reclassification.policy", "report-only-v1");
        });
        assertEquals(1, v2.findings().size());
        assertEquals(BookUnjudgedSelection.TARGET - 1, v2.addedJudgments().size(), "a finding never becomes a judgment");
        Case noAnswer = v2.cases().stream().filter(c -> c.id().equals(NO_ANSWER.queryId())).findFirst().orElseThrow();
        assertTrue(noAnswer.evidence().isEmpty() && !noAnswer.answerable());
    }

    @Test
    void rejectsAReclassificationPolicyThatIsNotAVersionedId() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        review.add(row(NO_ANSWER, "NO_ANSWER_CONTRADICTED", "2", 0, 20));
        decideRest();
        for (String policy : List.of("yes", "report-only-v1 ", "Report-Only-v1", "report-only")) {
            reject("reclassification.policy must be NONE or a versioned policy id", m -> {
                signOff(m);
                m.setProperty("reclassification.policy", policy);
            });
        }
    }

    @Test
    void unresolvedStaysUnjudgedAndCarriesNoGrade() {
        review.add(String.join("|", ANSWERABLE.pairId(), ANSWERABLE.queryId(), ANSWERABLE.chunkKey(), "UNRESOLVED",
                "-", "-", "-", "-", "chunk mentions the topic but not the asked fact"));
        assertTrue(load(m -> { }).addedJudgments().isEmpty());
        review.set(1, String.join("|", ANSWERABLE.pairId(), ANSWERABLE.queryId(), ANSWERABLE.chunkKey(), "UNRESOLVED",
                "0", "-", "-", "-", "forced"));
        reject("UNRESOLVED stays unjudged; it carries no grade or span, only a reason", m -> { });
    }

    @Test
    void rejectsAReviewOfAnUnselectedPair() {
        review.add("UJ-00000000|LIT-01|" + KEY + "s002/c0000|CONFOUNDER|0|1|2|" + SPAN + "|n");
        reject("UJ-00000000: review of a pair that is not in selection.psv", m -> { });
    }

    @Test
    void rejectsAPairReviewedTwice() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        review.add(row(ANSWERABLE, "CONFOUNDER", "0", 10, 40));
        reject(ANSWERABLE.pairId() + ": pair is reviewed more than once", m -> { });
    }

    @Test
    void rejectsAnUnknownDecision() {
        review.add(row(ANSWERABLE, "RELEVANT", "2", 10, 40));
        reject("unknown decision RELEVANT", m -> { });
    }

    @Test
    void rejectsStaleCounts() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        reject("counts.pending is 60 but the data has 59", m -> m.setProperty("counts.pending", "60"));
    }

    private void decideBoth() {
        review.add(row(ANSWERABLE, "EVIDENCE", "2", 10, 40));
        review.add(row(NO_ANSWER, "CONFOUNDER", "0", 0, 20));
        decideRest();
    }

    /** Decides every selected pair without a review row as a synthetic CONFOUNDER, completing the review. */
    private void decideRest() {
        var decided = review.stream().map(r -> r.split("\\|")[0]).collect(Collectors.toSet());
        COMMITTED.stream().filter(p -> !decided.contains(p.pairId()))
                .forEach(p -> review.add(row(p, "CONFOUNDER", "0", 0, 10)));
    }

    private static void signOff(Properties m) {
        m.setProperty("judgment.reviewStatus", "REVIEWED");
        m.setProperty("judgment.reviewer", "Human Reviewer");
        m.setProperty("judgment.approvedBy", "Human Reviewer");
        m.setProperty("judgment.reviewedAt", "2026-10-21");
    }

    private static String row(Pair p, String decision, String grade, long from, long to) {
        long start = BASE.chunkMap().get(p.chunkKey()).byteStart();
        return String.join("|", p.pairId(), p.queryId(), p.chunkKey(), decision, grade, String.valueOf(start + from),
                String.valueOf(start + to), SPAN, "synthetic decision");
    }

    private static String selectionRow(String query, String split, String category, String chunk) {
        return String.join("|", BookUnjudgedSelection.pairId(query, KEY + chunk), query, split, category, KEY + chunk,
                "atom", "SAMPLE", "7", "0.100000", "1");
    }

    private BookQueryDatasetV2 load(Consumer<Properties> mutate) {
        return parse(BASE_MANIFEST, BASE, mutate);
    }

    private BookQueryDatasetV2 parse(byte[] baseManifest, BookQueryDataset base, Consumer<Properties> mutate) {
        byte[] sel = bytes(selection);
        byte[] rev = rawReview != null ? rawReview : bytes(review);
        Properties m = new Properties();
        m.putAll(MANIFEST);
        // Fixtures start unsigned, whatever the bundled dataset's sign-off; tests sign off explicitly.
        m.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        List.of("judgment.reviewer", "judgment.approvedBy", "judgment.reviewedAt").forEach(k -> m.setProperty(k, ""));
        m.setProperty("selection.sha256", BookQueryDataset.sha256(sel));
        m.setProperty("review.sha256", BookQueryDataset.sha256(rev));
        // Counts are computed while unsigned, as apply does; signing off in the mutation must not make them stale.
        BookQueryDatasetV2.expectedCounts(BookQueryDatasetV2.build(m, base, baseManifest, sel, rev))
                .forEach(m::setProperty);
        mutate.accept(m);
        return BookQueryDatasetV2.parse(m, base, baseManifest, sel, rev);
    }

    private void reject(String expected, Consumer<Properties> mutate) {
        var e = assertThrows(IllegalStateException.class, () -> load(mutate));
        assertTrue(e.getMessage().contains(expected), "expected \"" + expected + "\" in: " + e.getMessage());
    }

    private static byte[] bytes(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
