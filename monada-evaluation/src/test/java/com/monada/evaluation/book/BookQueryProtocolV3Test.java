package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookQueryDataset.Category;
import com.monada.evaluation.book.BookQueryProtocolV3.Slot;
import com.monada.evaluation.book.BookQueryProtocolV3.Split;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The committed {@code book-queries-v3} protocol and slot plan: fixed counts, a recomputable split, no text or
 * retrieval output, and a sign-off that only a named human can give. Rejection cases mutate a copy and recompute the
 * plan digest and split digest, so only the intended defect remains.
 */
class BookQueryProtocolV3Test {
    static final Path DIR = Path.of(System.getProperty("user.dir"))
            .resolve("../docs/experiments/book-corpus-v1").normalize();
    private static List<String> PLAN;
    private static Properties V1;
    private static BookQueryProtocolV3 COMMITTED;

    @BeforeAll
    static void load() {
        COMMITTED = BookQueryProtocolV3.load(DIR);
        PLAN = BookQueryDataset.lines(BookQueryProtocolV3.read(DIR.resolve(BookQueryProtocolV3.PLAN_FILE)), "plan");
        V1 = BookQueryDataset.load().manifest();
    }

    @Test
    void theCommittedPlanHasTheFixedCountsAndMinimums() {
        List<Slot> slots = COMMITTED.slots();
        assertEquals(40, slots.size());
        assertEquals(28, slots.stream().filter(s -> s.split() == Split.DEV).count());
        assertEquals(12, slots.stream().filter(s -> s.split() == Split.FINAL_ONLY).count());
        Map<Category, Long> perCategory = slots.stream()
                .collect(Collectors.groupingBy(Slot::category, () -> new EnumMap<>(Category.class),
                        Collectors.counting()));
        assertEquals(Map.of(Category.LITERAL, 4L, Category.PARAPHRASE, 8L, Category.NARRATIVE_TEMPORAL, 5L,
                Category.MULTI_EVIDENCE, 5L, Category.CONFOUNDER, 5L, Category.AMBIGUOUS, 5L,
                Category.NO_ANSWER, 8L), perCategory);
        assertTrue(perCategory.get(Category.NO_ANSWER) >= 8 && perCategory.get(Category.PARAPHRASE) >= 8);
        assertTrue(perCategory.get(Category.NARRATIVE_TEMPORAL) + perCategory.get(Category.MULTI_EVIDENCE)
                + perCategory.get(Category.CONFOUNDER) >= 8);
        for (Category category : Category.values()) {
            assertTrue(slots.stream().anyMatch(s -> s.category() == category && s.split() == Split.DEV), category
                    + " has a DEV slot");
        }
    }

    @Test
    void theSplitIsTheSeededRule() {
        Map<Category, Integer> finalOnly = new EnumMap<>(Category.class);
        COMMITTED.slots().stream().filter(s -> s.split() == Split.FINAL_ONLY)
                .forEach(s -> finalOnly.merge(s.category(), 1, Integer::sum));
        Map<String, Split> expected = BookQueryProtocolV3.expectedSplit(COMMITTED.slots(), finalOnly);
        COMMITTED.slots().forEach(s -> assertEquals(expected.get(s.id()), s.split(), s.id()));
        assertEquals(COMMITTED.manifest().getProperty("split.digest"), BookQueryProtocolV3.splitDigest(expected));
    }

    @Test
    void theProtocolCarriesNoTextNoRetrievalOutputAndAClosedFinalGate() {
        assertEquals(BookQueryProtocolV3.PLAN_HEADER, PLAN.get(0));
        PLAN.forEach(line -> assertEquals(3, line.split("\\|", -1).length, line));
        Properties m = COMMITTED.manifest();
        assertEquals("NOT_AUTHORED", m.getProperty("queries.status"));
        assertEquals("0", m.getProperty("authoring.retrievalRunsBeforeFreeze"));
        assertEquals("CLOSED", m.getProperty("finalGate.initialStatus"));
        assertEquals(BookQueryProtocolV3.PROVENANCE, m.getProperty("provenance"));
        BookQueryProtocolV3.BINDING_KEYS.forEach(k -> assertEquals(V1.getProperty(k), m.getProperty(k), k));
    }

    @Test
    void theModelDraftIsPendingUntilAHumanSigns() {
        Properties m = COMMITTED.manifest();
        assertEquals("claude", m.getProperty("protocol.draftedBy"));
        if (!COMMITTED.reviewed()) {
            assertEquals(BookQueryProtocolV3.PENDING, m.getProperty("protocol.status"));
            assertFalse(m.containsKey("protocol.reviewer"), "a pending protocol names no reviewer");
        }
        BookQueryProtocolV3 signed = parse(BookQueryProtocolV3Test::sign, plan -> { });
        assertTrue(signed.reviewed());
    }

    @Test
    void rejectsAReviewedProtocolWithoutASignature() {
        for (String key : List.of("protocol.reviewer", "protocol.approvedBy", "protocol.reviewedAt")) {
            rejects("manifest is missing " + key, m -> {
                sign(m);
                m.remove(key);
            });
        }
        rejects("not an ISO date", m -> {
            sign(m);
            m.setProperty("protocol.reviewedAt", "24/10/2026");
        });
        rejects("unknown protocol.status", m -> m.setProperty("protocol.status", "APPROVED"));
    }

    @Test
    void rejectsAModelDraftPresentedAsReviewed() {
        for (String model : List.of("claude", "Claude Opus", "codex", "GPT-5", "devin-agent",
                "chatgpt-codex-connector[bot]")) {
            rejects("is not a human reviewer", m -> {
                sign(m);
                m.setProperty("protocol.reviewer", model);
            });
            rejects("is not a human reviewer", m -> {
                sign(m);
                m.setProperty("protocol.approvedBy", model);
            });
        }
        rejects("other than the drafter", m -> {
            sign(m);
            m.setProperty("protocol.draftedBy", "Steven Rey Sequeira");
        });
    }

    @Test
    void humanNamesThatShareGenericWordsAreAccepted() {
        for (String human : List.of("Ai Tanaka", "Model Smith", "Agent Orange", "Talbot")) {
            assertFalse(BookQueryProtocolV3.looksLikeModel(human), human);
            assertTrue(parse(m -> {
                sign(m);
                m.setProperty("protocol.reviewer", human);
            }, plan -> { }).reviewed(), human);
        }
    }

    @Test
    void rejectsUndatedDraftsAndASignatureBeforeTheDraft() {
        rejects("protocol.draftedAt is not an ISO date", m -> m.setProperty("protocol.draftedAt", "tbd"));
        rejects("protocol.reviewedAt precedes what it reviews", m -> {
            sign(m);
            m.setProperty("protocol.reviewedAt", "2026-10-01");
        });
    }

    @Test
    void everyCategoryKeepsADevSlot() {
        rejects("category AMBIGUOUS needs at least one DEV slot", m -> {
            m.setProperty("counts.LITERAL.FINAL_ONLY", "0");
            m.setProperty("counts.PARAPHRASE.FINAL_ONLY", "0");
            m.setProperty("counts.NARRATIVE_TEMPORAL.FINAL_ONLY", "1");
            m.setProperty("counts.AMBIGUOUS.FINAL_ONLY", "5");
        }, plan -> {
            for (String slot : List.of("V3-LIT-03", "V3-PAR-02", "V3-PAR-06", "V3-NAR-03")) {
                setSplit(plan, slot, Split.DEV);
            }
            for (String slot : List.of("V3-AMB-01", "V3-AMB-02", "V3-AMB-03", "V3-AMB-05")) {
                setSplit(plan, slot, Split.FINAL_ONLY);
            }
        });
    }

    @Test
    void rejectsAHandEditedSplit() {
        rejects("differs from the predeclared rule", m -> { }, plan -> {
            int dev = indexOf(plan, "V3-NOA-01");
            int fin = indexOf(plan, "V3-NOA-02");
            plan.set(dev, "V3-NOA-01|NO_ANSWER|FINAL_ONLY");
            plan.set(fin, "V3-NOA-02|NO_ANSWER|DEV");
        });
        rejects("split.digest does not match", m -> m.setProperty("split.digest", "0".repeat(64)));
        rejects("unexpected split salt", m -> m.setProperty("split.salt", "book-queries-v1|"));
    }

    @Test
    void rejectsWrongCountsAndLoweredMinimums() {
        rejects("must have 40 slots", m -> { }, plan -> plan.remove(plan.size() - 1));
        rejects("counts.FINAL_ONLY", m -> m.setProperty("counts.FINAL_ONLY", "11"));
        rejects("counts.NO_ANSWER", m -> m.setProperty("counts.NO_ANSWER", "9"));
        rejects("min.NO_ANSWER must be 8", m -> m.setProperty("min.NO_ANSWER", "7"));
        rejects("min.PARAPHRASE is 8 but the plan has 7", m -> {
            m.setProperty("counts.PARAPHRASE", "7");
            m.setProperty("counts.LITERAL", "5");
        }, plan -> plan.set(indexOf(plan, "V3-PAR-08"), "V3-LIT-05|LITERAL|DEV"));
    }

    @Test
    void rejectsMalformedOrDuplicateSlots() {
        rejects("duplicate slot", m -> { }, plan -> plan.set(indexOf(plan, "V3-LIT-02"), "V3-LIT-01|LITERAL|DEV"));
        rejects("slot prefix does not match", m -> { },
                plan -> plan.set(indexOf(plan, "V3-LIT-02"), "V3-PAR-09|LITERAL|DEV"));
        rejects("row needs 3 columns", m -> { },
                plan -> plan.set(indexOf(plan, "V3-LIT-02"), "V3-LIT-02|LITERAL|DEV|0.42"));
        rejects("header must be", m -> { }, plan -> plan.set(0, "slot|category|split|score"));
    }

    @Test
    void rejectsDriftAndRetrievalLeakage() {
        rejects("plan.sha256 does not match", m -> m.setProperty("plan.sha256", "0".repeat(64)));
        rejects("source.sha256 differs", m -> m.setProperty("source.sha256", "a".repeat(64)));
        rejects("must not carry retrieval output", m -> m.setProperty("selection.baselineScore", "0.31"));
        rejects("frozen before any retrieval run", m -> m.setProperty("authoring.retrievalRunsBeforeFreeze", "1"));
        rejects("start CLOSED", m -> m.setProperty("finalGate.initialStatus", "OPEN"));
        rejects("NOT_AUTHORED", m -> m.setProperty("queries.status", "AUTHORED"));
        rejects("provenance must be", m -> m.setProperty("provenance", "POST_HOC_RETRIEVAL_SAMPLED_DEV"));
    }

    static void sign(Properties m) {
        m.setProperty("protocol.status", BookQueryProtocolV3.REVIEWED);
        m.setProperty("protocol.reviewer", "Steven Rey Sequeira");
        m.setProperty("protocol.approvedBy", "stevdrey");
        m.setProperty("protocol.reviewedAt", "2026-10-24");
    }

    /** A protocol copy after {@code edit}, with plan and split digests recomputed. */
    static BookQueryProtocolV3 parse(Consumer<Properties> edit, Consumer<List<String>> planEdit) {
        Properties m = committedManifest();
        List<String> plan = new ArrayList<>(PLAN);
        planEdit.accept(plan);
        m.setProperty("plan.sha256", BookQueryDataset.sha256(planBytes(plan)));
        Map<String, Split> declared = new java.util.TreeMap<>();
        plan.subList(1, plan.size()).forEach(line -> {
            String[] f = line.split("\\|", -1);
            if (f.length == 3 && (f[2].equals("DEV") || f[2].equals("FINAL_ONLY"))) {
                declared.put(f[0], Split.valueOf(f[2]));
            }
        });
        m.setProperty("split.digest", BookQueryProtocolV3.splitDigest(declared));
        edit.accept(m);
        return BookQueryProtocolV3.parse(m, planBytes(plan), V1);
    }

    static byte[] planBytes(List<String> plan) {
        return (String.join("\n", plan) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A fresh copy of the committed protocol properties (also used by {@link BookQueryDatasetV3RejectionTest}). */
    static Properties committedManifest() {
        return BookQueryProtocolV3.readProperties(
                BookQueryProtocolV3.read(DIR.resolve(BookQueryProtocolV3.PROTOCOL_FILE)), "protocol");
    }

    private static void rejects(String message, Consumer<Properties> edit) {
        rejects(message, edit, plan -> { });
    }

    private static void rejects(String message, Consumer<Properties> edit, Consumer<List<String>> planEdit) {
        var e = assertThrows(IllegalStateException.class, () -> parse(edit, planEdit));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static void setSplit(List<String> plan, String slot, Split split) {
        int i = indexOf(plan, slot);
        plan.set(i, plan.get(i).substring(0, plan.get(i).lastIndexOf('|') + 1) + split);
    }

    private static int indexOf(List<String> plan, String slot) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).startsWith(slot + "|")) {
                return i;
            }
        }
        throw new AssertionError("no slot " + slot);
    }
}
