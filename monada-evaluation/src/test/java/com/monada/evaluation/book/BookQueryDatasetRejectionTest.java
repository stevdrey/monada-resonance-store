package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Split;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Invalid gold labels are rejected. Each case starts from a small, valid, synthetic dataset (no book text) whose
 * manifest is recomputed after the mutation, so only the intended defect remains.
 */
class BookQueryDatasetRejectionTest {
    private static final String KEY = "book-corpus-v1/book-chunking-v1/s001/c000";
    private static final String SPAN = "b".repeat(64);

    private final List<String> queries = new ArrayList<>(List.of(
            BookQueryDataset.QUERIES_HEADER,
            "LIT-01|DEV|LITERAL|true|Literal question?|Stated once.",
            "MUL-01|DEV|MULTI_EVIDENCE|true|Spread question?|Two chunks.",
            "CON-01|DEV|CONFOUNDER|true|Confusable question?|One wrong chunk.",
            "NOA-01|DEV|NO_ANSWER|false|Absent question?|Not in the text."));
    private final List<String> judgments = new ArrayList<>(List.of(
            BookQueryDataset.JUDGMENTS_HEADER,
            "LIT-01|" + KEY + "0|10|20|" + SPAN + "|2|EVIDENCE|direct",
            "MUL-01|" + KEY + "0|30|40|" + SPAN + "|2|EVIDENCE|first part",
            "MUL-01|" + KEY + "1|100|120|" + SPAN + "|1|EVIDENCE|second part",
            "CON-01|" + KEY + "1|130|140|" + SPAN + "|2|EVIDENCE|answer",
            "CON-01|" + KEY + "2|200|210|" + SPAN + "|0|CONFOUNDER|same word, unrelated",
            "NOA-01|" + KEY + "2|220|230|" + SPAN + "|0|CONFOUNDER|near miss"));
    private final List<String> chunkMap = new ArrayList<>(List.of(
            "{\"format\":\"monada-book-chunk-sidecar\",\"formatVersion\":1,\"corpus\":\"book-corpus-v1\","
                    + "\"corpusVersion\":\"1\",\"sourceBytes\":300,\"sourceSha256\":\"" + "a".repeat(64) + "\","
                    + "\"manifestSha256\":\"" + "c".repeat(64) + "\",\"policy\":{\"id\":\"book-chunking-v1\","
                    + "\"chapterHeadingPattern\":\"CHAPTER [IVXLC]+\\\\.\",\"maxChunkBytes\":2048,\"overlapBytes\":0},"
                    + "\"frontMatter\":false,\"chapterCount\":1,\"chunkCount\":3,\"chunksFile\":\"chunks.jsonl\","
                    + "\"chunksFileSha256\":\"" + "d".repeat(64) + "\"}",
            chunk(0, 0, 90), chunk(1, 92, 190), chunk(2, 192, 300)));

    @Test
    void validFixtureLoads() {
        BookQueryDataset dataset = load(m -> { });
        assertEquals(4, dataset.cases().size());
        assertEquals(3, dataset.chunkMap().size());
    }

    // ---- contradictory labels

    @Test
    void rejectsTheSameChunkAsEvidenceAndConfounder() {
        judgments.set(5, "CON-01|" + KEY + "1|150|160|" + SPAN + "|0|CONFOUNDER|contradicts");
        reject("CON-01: contradictory label, chunk " + KEY + "1 is judged more than once");
    }

    @Test
    void rejectsTheSameChunkJudgedTwiceWithDifferentGrades() {
        judgments.add("LIT-01|" + KEY + "0|50|60|" + SPAN + "|1|EVIDENCE|again");
        reject("LIT-01: contradictory label, chunk " + KEY + "0 is judged more than once");
    }

    @Test
    void rejectsEvidenceWithGradeZeroAndConfounderWithPositiveGrade() {
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|0|EVIDENCE|direct");
        reject("LIT-01: contradictory label, EVIDENCE with grade 0");
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|2|EVIDENCE|direct");
        judgments.set(5, "CON-01|" + KEY + "2|200|210|" + SPAN + "|1|CONFOUNDER|x");
        reject("CON-01: contradictory label, CONFOUNDER with grade 1");
    }

    @Test
    void rejectsEvidenceOnANoAnswerCase() {
        judgments.set(6, "NOA-01|" + KEY + "2|220|230|" + SPAN + "|2|EVIDENCE|claims an answer");
        reject("NOA-01: NO_ANSWER case must not have evidence");
    }

    @Test
    void rejectsAnswerableFlagThatContradictsTheCategory() {
        queries.set(4, "NOA-01|DEV|NO_ANSWER|true|Absent question?|Not in the text.");
        reject("NOA-01: answerable must be false exactly for NO_ANSWER");
    }

    // ---- empty labels

    @Test
    void rejectsAnAnswerableCaseWithoutEvidence() {
        judgments.remove(1);
        reject("LIT-01: empty label, answerable case has no evidence");
    }

    @Test
    void rejectsAnAnswerableCaseWithOnlyPartialEvidence() {
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|1|EVIDENCE|partial");
        reject("LIT-01: no evidence directly answers it");
    }

    @Test
    void rejectsAMultiEvidenceCaseWithASingleChunk() {
        judgments.remove(3);
        reject("MUL-01: MULTI_EVIDENCE needs evidence in at least two chunks");
    }

    @Test
    void rejectsAConfounderCaseWithoutAConfounder() {
        judgments.remove(5);
        reject("CON-01: CONFOUNDER case has no confounder");
    }

    @Test
    void rejectsBlankTextRationaleOrNote() {
        queries.set(1, "LIT-01|DEV|LITERAL|true| |Stated once.");
        reject("LIT-01: query text and rationale must not be blank");
        queries.set(1, "LIT-01|DEV|LITERAL|true|Literal question?|Stated once.");
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|2|EVIDENCE| ");
        reject("LIT-01: judgment note must not be blank");
    }

    // ---- orphan labels

    @Test
    void rejectsAJudgmentForAnUnknownQuery() {
        judgments.add("ZZZ-99|" + KEY + "0|10|20|" + SPAN + "|2|EVIDENCE|orphan");
        reject("orphan judgment for unknown query: ZZZ-99");
    }

    @Test
    void rejectsAnUnknownChunkKey() {
        judgments.set(1, "LIT-01|" + KEY + "9|10|20|" + SPAN + "|2|EVIDENCE|direct");
        reject("LIT-01: orphan judgment for unknown chunk key " + KEY + "9");
    }

    @Test
    void rejectsASpanOutsideItsChunkOrTheSource() {
        judgments.set(1, "LIT-01|" + KEY + "0|80|95|" + SPAN + "|2|EVIDENCE|direct");
        reject("LIT-01: span [80,95) is not inside chunk " + KEY + "0");
        judgments.set(1, "LIT-01|" + KEY + "0|20|20|" + SPAN + "|2|EVIDENCE|direct");
        reject("LIT-01: span [20,20) is empty or outside the source");
        judgments.set(1, "LIT-01|" + KEY + "2|290|301|" + SPAN + "|2|EVIDENCE|direct");
        reject("LIT-01: span [290,301) is empty or outside the source");
    }

    @Test
    void rejectsAMalformedSpanDigest() {
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + "B".repeat(64) + "|2|EVIDENCE|direct");
        reject("LIT-01: spanSha256 is not a lowercase SHA-256");
    }

    // ---- schema

    @Test
    void rejectsDuplicateIdsAndUnknownEnumValues() {
        queries.add("LIT-01|DEV|LITERAL|true|Again?|Duplicate.");
        reject("duplicate query id: LIT-01");
        queries.remove(queries.size() - 1);
        queries.set(1, "LIT-01|DEV|TRIVIA|true|Literal question?|Stated once.");
        reject("LIT-01: unknown category TRIVIA");
        queries.set(1, "LIT-01|TRAIN|LITERAL|true|Literal question?|Stated once.");
        reject("LIT-01: unknown split TRAIN");
        queries.set(1, "LIT-01|DEV|LITERAL|true|Literal question?|Stated once.");
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|3|EVIDENCE|direct");
        reject("LIT-01: contradictory label, EVIDENCE with grade 3");
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|2|RELEVANT|direct");
        reject("LIT-01: unknown role RELEVANT");
        judgments.set(1, "LIT-01|" + KEY + "0|10|20|" + SPAN + "|4294967298|EVIDENCE|wraps to 2 as an int");
        reject("LIT-01: contradictory label, EVIDENCE with grade 4294967298");
    }

    @Test
    void rejectsExtraColumnsSuchAsRetrievalScores() {
        queries.set(0, BookQueryDataset.QUERIES_HEADER + "|score");
        reject("queries.psv header must be");
        queries.set(0, BookQueryDataset.QUERIES_HEADER);
        judgments.set(1, judgments.get(1) + "|0.93");
        reject("judgments.psv row needs 8 columns");
    }

    @Test
    void rejectsAHandEditedSplit() {
        queries.set(1, "LIT-01|HOLDOUT|LITERAL|true|Literal question?|Stated once.");
        reject("LIT-01: split HOLDOUT differs from the frozen rule (DEV)");
    }

    @Test
    void rejectsCountsOrDigestsThatDoNotMatchTheData() {
        reject(m -> m.setProperty("counts.queries", "5"), "counts.queries is 5 but the data has 4");
        reject(m -> m.setProperty("split.digest", "e".repeat(64)), "split.digest does not match");
        reject(m -> m.setProperty("split.method", "random-v2"), "unsupported split method random-v2");
        reject(m -> m.setProperty("judgments.sha256", "e".repeat(64)), "judgments.sha256 does not match the file");
        reject(m -> m.setProperty("chunkMap.sha256", "e".repeat(64)), "chunkMap.sha256 does not match the file");
    }

    @Test
    void rejectsAChunkMapForAnotherSourceOrPolicy() {
        reject(m -> m.setProperty("source.sha256", "f".repeat(64)), "chunk map sourceSha256 is");
        reject(m -> m.setProperty("chunking.policy", "book-chunking-v2"), "chunk map policy.id is");
        reject(m -> m.setProperty("corpusManifest.sha256", "f".repeat(64)), "chunk map manifestSha256 is");
    }

    @Test
    void rejectsLabelsMarkedAsEditedAfterRetrievalOrWithAnIncompleteReview() {
        reject(m -> m.setProperty("judgment.retrievalRunsBeforeFreeze", "1"),
                "labels must be frozen before any retrieval run");
        reject(m -> m.setProperty("judgment.reviewStatus", "REVIEWED"), "manifest is missing judgment.reviewer");
        reject(m -> m.setProperty("judgment.reviewStatus", "MAYBE"), "unknown review status MAYBE");
    }

    @Test
    void ruleSplitIsStratifiedAndStable() {
        Map<String, BookQueryDataset.Category> categories = new LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) {
            categories.put("PAR-0" + i, BookQueryDataset.Category.PARAPHRASE);
        }
        categories.put("LIT-01", BookQueryDataset.Category.LITERAL);
        Map<String, Split> split = BookQueryDataset.expectedSplit(categories);
        assertEquals(2, split.values().stream().filter(s -> s == Split.HOLDOUT).count());
        assertEquals(Split.DEV, split.get("LIT-01"));
        assertEquals(split, BookQueryDataset.expectedSplit(new TreeMap<>(categories)));
    }

    private static String chunk(int index, int start, int end) {
        return "{\"key\":\"" + KEY + index + "\",\"ordinal\":" + index + ",\"byteStart\":" + start + ",\"byteEnd\":"
                + end + "}";
    }

    private void reject(String message) {
        reject(m -> { }, message);
    }

    private void reject(Consumer<Properties> tamper, String message) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(tamper));
        assertTrue(e.getMessage().contains(message), () -> "expected '" + message + "' in: " + e.getMessage());
    }

    /** Builds a manifest consistent with the current rows, applies {@code tamper}, then parses. */
    private BookQueryDataset load(Consumer<Properties> tamper) {
        byte[] q = bytes(queries);
        byte[] j = bytes(judgments);
        byte[] c = bytes(chunkMap);
        Properties m = new Properties();
        m.setProperty("dataset", "book-queries-v1");
        m.setProperty("datasetVersion", "1");
        m.setProperty("format", "monada-book-queries");
        m.setProperty("formatVersion", "1");
        m.setProperty("corpus", "book-corpus-v1");
        m.setProperty("corpusVersion", "1");
        m.setProperty("corpusManifest.sha256", "c".repeat(64));
        m.setProperty("source.sizeBytes", "300");
        m.setProperty("source.sha256", "a".repeat(64));
        m.setProperty("chunking.policy", "book-chunking-v1");
        m.setProperty("chunkMap.file", "chunk-map.jsonl");
        m.setProperty("chunkMap.sha256", BookQueryDataset.sha256(c));
        m.setProperty("chunksFile.sha256", "d".repeat(64));
        m.setProperty("queries.file", "queries.psv");
        m.setProperty("queries.sha256", BookQueryDataset.sha256(q));
        m.setProperty("judgments.file", "judgments.psv");
        m.setProperty("judgments.sha256", BookQueryDataset.sha256(j));
        m.setProperty("judgment.protocol", "MANUAL_READING_OF_NORMALIZED_BODY_NO_RETRIEVAL");
        m.setProperty("judgment.draftedBy", "test");
        m.setProperty("judgment.reviewStatus", "PENDING_HUMAN_REVIEW");
        m.setProperty("judgment.retrievalRunsBeforeFreeze", "0");
        m.setProperty("split.method", BookQueryDataset.SPLIT_METHOD);
        m.setProperty("split.salt", "book-queries-v1|");

        Map<String, Split> declared = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (BookQueryDataset.Category category : BookQueryDataset.Category.values()) {
            counts.put(category.name(), 0);
        }
        counts.put("DEV", 0);
        counts.put("HOLDOUT", 0);
        for (String row : queries.subList(1, queries.size())) {
            String[] f = row.split("\\|", -1);
            if (f.length > 2) {
                counts.merge(f[1], 1, Integer::sum);
                counts.merge(f[2], 1, Integer::sum);
                try {
                    declared.put(f[0], Split.valueOf(f[1]));
                } catch (IllegalArgumentException ignored) {
                    // unknown split: parse reports it before the digest is checked
                }
            }
        }
        counts.forEach((name, n) -> m.setProperty("counts." + name, String.valueOf(n)));
        m.setProperty("counts.queries", String.valueOf(queries.size() - 1));
        m.setProperty("counts.judgments", String.valueOf(judgments.size() - 1));
        m.setProperty("split.digest", BookQueryDataset.splitDigest(declared));
        tamper.accept(m);
        return BookQueryDataset.parse(m, q, j, c);
    }

    private static byte[] bytes(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
