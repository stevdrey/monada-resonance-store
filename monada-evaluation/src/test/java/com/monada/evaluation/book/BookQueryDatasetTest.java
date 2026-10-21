package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Case;
import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryDataset.Role;
import com.monada.evaluation.book.BookQueryDataset.Split;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The committed {@code book-queries-v1} set: acceptance thresholds, frozen bindings and no score leakage. */
class BookQueryDatasetTest {
    // Loaded in @BeforeAll, not a static initializer, so a validation failure reports its own message.
    private static BookQueryDataset DATASET;

    @BeforeAll
    static void loadDataset() {
        DATASET = BookQueryDataset.load();
    }

    @Test
    void meetsTheIssueThresholds() {
        assertTrue(DATASET.cases().size() >= 30, "at least 30 judged queries");
        assertTrue(DATASET.casesOf(Category.NO_ANSWER).size() >= 5, "at least 5 no-answer negatives");
        assertTrue(DATASET.casesOf(Category.PARAPHRASE).size() >= 5, "at least 5 paraphrases");
        long multiOrDistractor = DATASET.cases().stream()
                .filter(c -> c.category() == Category.MULTI_EVIDENCE || c.category() == Category.CONFOUNDER)
                .count();
        assertTrue(multiOrDistractor >= 5, "at least 5 multi-evidence or distractor cases");
        for (Category category : Category.values()) {
            assertFalse(DATASET.casesOf(category).isEmpty(), "no cases for " + category);
        }
    }

    @Test
    void everyPositiveJudgmentResolvesToAFrozenSpanAndChunk() {
        long sourceBytes = Long.parseLong(DATASET.manifest().getProperty("source.sizeBytes"));
        for (Case c : DATASET.cases()) {
            for (var j : c.evidence()) {
                var chunk = DATASET.chunkMap().get(j.chunkKey());
                assertTrue(chunk != null, c.id() + " cites unknown chunk " + j.chunkKey());
                assertTrue(chunk.byteStart() <= j.byteStart() && j.byteEnd() <= chunk.byteEnd(), c.id());
                assertTrue(j.byteEnd() <= sourceBytes, c.id());
                assertTrue(j.spanSha256().matches("[0-9a-f]{64}"), c.id());
            }
            if (c.answerable()) {
                assertFalse(c.evidence().isEmpty(), c.id());
            } else {
                assertTrue(c.evidence().isEmpty(), c.id());
            }
        }
    }

    @Test
    void chunkMapCoversTheWholeFrozenChunkingRun() {
        assertEquals(87, DATASET.chunkMap().size());
        assertTrue(DATASET.chunkMap().keySet().stream().allMatch(k -> k.startsWith("book-corpus-v1/book-chunking-v1/")));
    }

    @Test
    void corpusBindingMatchesTheCheckedInCorpusManifest() throws Exception {
        // Gradle runs tests with the module directory as working directory.
        Path corpusManifest = Path.of(System.getProperty("user.dir")).resolve("../" + BookChunkingMain.DEFAULT_MANIFEST)
                .normalize();
        assertEquals(BookQueryDataset.sha256(Files.readAllBytes(corpusManifest)),
                DATASET.manifest().getProperty("corpusManifest.sha256"));
        var corpus = new java.util.Properties();
        try (var in = Files.newInputStream(corpusManifest)) {
            corpus.load(in);
        }
        assertEquals(corpus.getProperty("normalized.sha256"), DATASET.manifest().getProperty("source.sha256"));
        assertEquals(corpus.getProperty("normalized.sizeBytes"), DATASET.manifest().getProperty("source.sizeBytes"));
        assertEquals(BookChunkingPolicy.V1.id(), DATASET.manifest().getProperty("chunking.policy"));
    }

    @Test
    void partitionIsFixedByTheFrozenRuleAndCoversEveryCategory() {
        assertEquals(DATASET.cases().size(), DATASET.casesOf(Split.DEV).size() + DATASET.casesOf(Split.HOLDOUT).size());
        for (Category category : Category.values()) {
            if (DATASET.casesOf(category).size() >= 3) {
                assertTrue(DATASET.casesOf(category).stream().anyMatch(c -> c.split() == Split.HOLDOUT),
                        category + " has no held-out case");
            }
            assertTrue(DATASET.casesOf(category).stream().anyMatch(c -> c.split() == Split.DEV),
                    category + " has no dev case");
        }
    }

    @Test
    void labelsCarryNoRetrievalScoresOrRanks() {
        Pattern leak = Pattern.compile("\\b(score|rank|recall|precision|mrr|ndcg|hits?|similarity|cosine|top-?k)\\b");
        for (String key : DATASET.manifest().stringPropertyNames()) {
            assertFalse(leak.matcher(key.toLowerCase(Locale.ROOT)).find(), "retrieval output in manifest: " + key);
        }
        for (Case c : DATASET.cases()) {
            assertFalse(leak.matcher(c.rationale().toLowerCase(Locale.ROOT)).find(), c.id() + " rationale");
            c.judgments().forEach(j -> assertFalse(leak.matcher(j.note().toLowerCase(Locale.ROOT)).find(),
                    c.id() + " note"));
        }
        assertEquals("0", DATASET.manifest().getProperty("judgment.retrievalRunsBeforeFreeze"));
    }

    @Test
    void distractorCasesJudgeTheirConfounderAsNonRelevant() {
        for (Case c : DATASET.casesOf(Category.CONFOUNDER)) {
            assertFalse(c.confounders().isEmpty(), c.id());
            assertTrue(c.confounders().stream().allMatch(j -> j.grade() == 0 && j.role() == Role.CONFOUNDER), c.id());
        }
    }

    @Test
    void labelsAreHumanReviewed() {
        assertEquals("REVIEWED", DATASET.manifest().getProperty("judgment.reviewStatus"),
                "book-queries-v1 must be signed off by a human reviewer before it is used");
        assertFalse(DATASET.manifest().getProperty("judgment.reviewer").isBlank());
        assertTrue(DATASET.manifest().getProperty("judgment.reviewedAt").matches("\\d{4}-\\d{2}-\\d{2}"));
    }
}
