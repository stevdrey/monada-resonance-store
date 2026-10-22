package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Split;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The committed {@code book-queries-v2}: strict preservation of v1 (queries, split, every judgment), DEV-only
 * additions, post-hoc provenance and an up-to-date coverage report.
 */
class BookQueryDatasetV2Test {
    private static final String V1_MANIFEST_SHA256 = "ec4efab61f53dfdaf15e31304e2d39d9815e821042224cc806c8d8a3b7ea3bbc";
    private static final String V1_JUDGMENTS_SHA256 =
            "5f129b6e0b8cb0565d9ba13cb6a57c67238403e7cde77e45c702068d6b163968";
    private static final String REPORT = "docs/experiments/book-corpus-v1/book-unjudged-review-v2-report.txt";
    private static BookQueryDatasetV2 V2;
    private static BookQueryDataset V1;

    @BeforeAll
    static void load() {
        V1 = BookQueryDataset.load();
        V2 = BookQueryDatasetV2.load();
    }

    @Test
    void v1IsByteForByteUnchanged() {
        assertEquals(V1_MANIFEST_SHA256, BookQueryDataset.sha256(BookQueryDataset.resource("manifest.properties")));
        assertEquals(V1_JUDGMENTS_SHA256, BookQueryDataset.sha256(BookQueryDataset.resource("judgments.psv")));
        assertEquals("REVIEWED", V1.manifest().getProperty("judgment.reviewStatus"));
        assertEquals(55, V1.cases().stream().mapToInt(c -> c.judgments().size()).sum());
    }

    @Test
    void keepsEveryV1QuerySplitAndJudgment() {
        Map<String, Case> v2 = V2.cases().stream().collect(Collectors.toMap(Case::id, Function.identity()));
        assertEquals(32, v2.size());
        for (Case c : V1.cases()) {
            Case extended = v2.get(c.id());
            assertEquals(c.split(), extended.split(), c.id());
            assertEquals(c.category(), extended.category(), c.id());
            assertEquals(c.text(), extended.text(), c.id());
            assertEquals(c.judgments(), extended.judgments().subList(0, c.judgments().size()),
                    c.id() + ": v1 judgments must stay first and unchanged");
            if (c.split() == Split.HOLDOUT) {
                assertEquals(c.judgments(), extended.judgments(), c.id() + ": no HOLDOUT judgment may be added");
            }
        }
    }

    @Test
    void declaresPostHocProvenanceAndAConsistentReviewState() {
        assertEquals(BookQueryDatasetV2.PROVENANCE, V2.manifest().getProperty("provenance"));
        // The committed review used the first worksheet, which showed chunk keys and byte ranges.
        assertEquals(BookQueryDatasetV2.LEGACY_REVIEW_PROTOCOL, V2.manifest().getProperty("review.protocol"));
        // The committed decisions were drafted by Codex and verified one by one by the human reviewer.
        assertEquals("codex", V2.manifest().getProperty("judgment.draftedBy"));
        assertEquals(BookQueryDatasetV2.DRAFT_VERIFICATION, V2.manifest().getProperty("judgment.verification"));
        // What Codex could see while drafting is unknown, so the drafting is not claimed to be score-blind.
        assertEquals("UNVERIFIED", V2.manifest().getProperty("judgment.draftBlinding"));
        String status = V2.manifest().getProperty("judgment.reviewStatus");
        if (!V2.pending().isEmpty()) {
            assertEquals("PENDING_HUMAN_REVIEW", status, "a partially reviewed v2 is never REVIEWED");
        }
        assertTrue(V2.selection().size() >= BookUnjudgedSelection.TARGET);
        assertTrue(V2.addedJudgments().stream().allMatch(e -> V1.cases().stream()
                .anyMatch(c -> c.id().equals(e.getKey()) && c.split() == Split.DEV)));
    }

    @Test
    void theCommittedCoverageReportIsCurrent() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
        byte[] baseline = Files.readAllBytes(root.resolve(BookUnjudgedSelection.BASELINE_REPORT));
        String rendered = BookUnjudgedReviewReport.render(BookUnjudgedSelection.parseTrace(baseline, V1), V2);
        assertEquals(Files.readString(root.resolve(REPORT), StandardCharsets.UTF_8), rendered,
                "regenerate with runBookUnjudgedReview report --out " + REPORT);
        assertTrue(rendered.contains("ALL       answerable     270    256"), "original 256/270 U occurrences");
    }
}
