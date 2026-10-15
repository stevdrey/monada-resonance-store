package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The judged stress set of issue #122 is versioned, covers every required category, keeps explicit empty relevant
 * sets, reuses the #102 queries unchanged as its baseline and rejects structurally inconsistent judgments.
 */
class RecallStressFixtureTest {
    private static final RecallStressFixture FIXTURE = RecallStressFixture.load();

    @Test
    void isAVersionedSetOverTheUnchangedCorpusOf102() {
        assertEquals("1", FIXTURE.version());
        assertEquals("1", FIXTURE.corpus().version());
        assertEquals(FIXTURE.corpus().recallLimit(), FIXTURE.recallLimit());
        assertEquals(FIXTURE.corpus().ks(), FIXTURE.ks());
        assertTrue(FIXTURE.provenance().contains("manually authored and judged"));
        assertTrue(FIXTURE.judgmentProtocol().contains("BEFORE any recall was run"));
    }

    @Test
    void baselineGroupIsExactlyThe102Queries() {
        List<StressQuery> baseline = FIXTURE.queriesOf(Category.BASELINE);
        List<ForgeFixture.Query> original = FIXTURE.corpus().queries();
        assertEquals(original.size(), baseline.size());
        for (int i = 0; i < original.size(); i++) {
            ForgeFixture.Query o = original.get(i);
            StressQuery b = baseline.get(i);
            assertEquals(o.id(), b.id());
            assertEquals(o.kind().name(), b.kind());
            assertEquals(o.text(), b.text());
            assertEquals(o.relevant(), b.relevant());
            assertTrue(b.confusable().isEmpty());
        }
    }

    @Test
    void coversEveryRequiredCategory() {
        Set<Category> covered = FIXTURE.queries().stream().map(StressQuery::category)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Category.class)));
        assertEquals(EnumSet.allOf(Category.class), covered);
        assertEquals(26, FIXTURE.queries().size() - FIXTURE.queriesOf(Category.BASELINE).size());
    }

    @Test
    void emptyRelevantSetsAreExplicit() {
        for (Category c : List.of(Category.NO_RELEVANT, Category.EMPTY_QUERY)) {
            assertFalse(FIXTURE.queriesOf(c).isEmpty());
            FIXTURE.queriesOf(c).forEach(q -> assertTrue(q.relevant().isEmpty(), q.id()));
        }
        // A category other than NO_RELEVANT may also judge that nothing answers a query (reverse migration).
        assertTrue(FIXTURE.query("S10").orElseThrow().relevant().isEmpty());
        assertEquals(Set.of("a-yaml/a1"), FIXTURE.query("S10").orElseThrow().confusable());
    }

    @Test
    void blankQueriesAreWrittenAsTokens() {
        assertEquals("", FIXTURE.query("S23").orElseThrow().text());
        assertEquals(RecallStressFixture.WHITESPACE_TEXT, FIXTURE.query("S24").orElseThrow().text());
        assertTrue(FIXTURE.query("S24").orElseThrow().text().isBlank());
        assertFalse(FIXTURE.query("S25").orElseThrow().text().isBlank()); // punctuation only is not blank
        assertThrows(IllegalStateException.class, () -> RecallStressFixture.text(""));
    }

    @Test
    void negationQueriesJudgeTheExcludedApproachConfusable() {
        StressQuery s07 = FIXTURE.query("S07").orElseThrow();
        assertEquals(Set.of("a-page/a1"), s07.confusable());
        assertFalse(s07.relevant().contains("a-page/a1"));
    }

    @Test
    void rejectsAnExperienceOfAnotherProject() {
        assertRejected(q -> q.id().equals("S01") ? with(q, Set.of("b-page/a1"), q.confusable()) : q,
                "another project");
    }

    @Test
    void rejectsAnUnknownExperience() {
        assertRejected(q -> q.id().equals("S01") ? with(q, Set.of("a-page/a9"), q.confusable()) : q,
                "not in the corpus");
    }

    @Test
    void rejectsAnExperienceJudgedBothRelevantAndConfusable() {
        assertRejected(q -> q.id().equals("S07") ? with(q, q.relevant(), Set.of("a-page/a2")) : q,
                "both relevant and confusable");
    }

    @Test
    void rejectsRelevantExperiencesForANoRelevantQuery() {
        assertRejected(q -> q.id().equals("S17") ? with(q, Set.of("a-tz/a1"), Set.of()) : q, "no relevant");
    }

    @Test
    void rejectsMetricKValuesThatAreNotStrictlyAscending() {
        for (List<Integer> ks : List.of(List.of(5, 3, 1), List.of(1, 3, 3))) {
            RecallStressFixture copy = new RecallStressFixture(FIXTURE.version(), FIXTURE.provenance(),
                    FIXTURE.judgmentProtocol(), FIXTURE.reviewer(), FIXTURE.corpus(), FIXTURE.recallLimit(), ks,
                    FIXTURE.queries());
            IllegalStateException e = assertThrows(IllegalStateException.class, copy::validate);
            assertTrue(e.getMessage().contains("strictly ascending"), e.getMessage());
        }
    }

    @Test
    void cursorAndNegatedQueriesJudgeTheOffsetAttemptAlike() {
        assertEquals(FIXTURE.query("S07").orElseThrow().confusable(), FIXTURE.query("S13").orElseThrow().confusable());
    }

    @Test
    void rejectsADuplicateQueryId() {
        List<StressQuery> queries = new ArrayList<>(FIXTURE.queries());
        queries.add(queries.get(queries.size() - 1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> copy(queries).validate());
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void rejectsAMissingCategory() {
        List<StressQuery> queries = FIXTURE.queries().stream().filter(q -> q.category() != Category.NEGATION)
                .toList();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> copy(queries).validate());
        assertTrue(e.getMessage().contains("NEGATION"), e.getMessage());
    }

    private static void assertRejected(UnaryOperator<StressQuery> change, String message) {
        List<StressQuery> queries = FIXTURE.queries().stream().map(change).toList();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> copy(queries).validate());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static StressQuery with(StressQuery q, Set<String> relevant, Set<String> confusable) {
        return new StressQuery(q.id(), q.category(), q.kind(), q.scopeKey(), q.text(), relevant, confusable,
                q.judgment());
    }

    private static RecallStressFixture copy(List<StressQuery> queries) {
        return new RecallStressFixture(FIXTURE.version(), FIXTURE.provenance(), FIXTURE.judgmentProtocol(),
                FIXTURE.reviewer(), FIXTURE.corpus(), FIXTURE.recallLimit(), FIXTURE.ks(), queries);
    }
}
