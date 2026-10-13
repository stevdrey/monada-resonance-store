package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.ForgeFixture.QueryKind;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The versioned Forge corpus meets the issue's minimums and is internally consistent. */
class ForgeFixtureTest {
    private final ForgeFixture fixture = ForgeFixture.load();

    @Test
    void declaresVersionProvenanceAndTwoOpaqueProjects() {
        assertEquals("1", fixture.version());
        assertTrue(fixture.provenance().contains("synthetic"));
        assertEquals(2, fixture.scopeIds().size());
        assertEquals(2, Set.copyOf(fixture.scopeIds().values()).size());
        assertEquals(List.of(1, 3, 5), fixture.ks());
        // iteration order feeds the report, so it must be the fixture order in every JVM (Map.copyOf is not)
        assertEquals(List.of("alpha", "beta"), List.copyOf(fixture.scopeIds().keySet()));
    }

    @Test
    void meetsCorpusAndQueryMinimums() {
        assertTrue(fixture.attempts().size() >= 24, "experiences: " + fixture.attempts().size());
        assertTrue(fixture.queries().size() >= 12, "queries: " + fixture.queries().size());
        assertEquals(fixture.attempts().size(), fixture.experienceIds().size());
        for (String scopeKey : fixture.scopeIds().keySet()) {
            assertTrue(fixture.executionsOf(scopeKey).size() >= 4, scopeKey);
        }
    }

    @Test
    void coversEveryRequiredQueryKindAndBlank() {
        Set<QueryKind> kinds = fixture.queries().stream().map(ForgeFixture.Query::kind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(QueryKind.class)));
        assertEquals(EnumSet.allOf(QueryKind.class), kinds);
    }

    @Test
    void containsTheRequiredChainsAndEdgeCases() {
        // failed -> repair -> review/qa -> accepted
        List<String> reasons = fixture.attemptsOf("a-page").stream().map(ForgeFixture.Attempt::reason).toList();
        assertEquals(List.of("INITIAL", "REPAIR", "REVIEW_FOLLOW_UP"), reasons);
        assertEquals("FAILED", fixture.attemptsOf("a-page").get(0).result());
        // same summary, different attempt
        assertEquals(1, fixture.executions().stream().filter(e -> e.id().equals("a-inv1")).count());
        assertEquals(2, fixture.attemptsOf("a-inv1").size());
        // stale revision
        assertTrue(fixture.attempts().stream().anyMatch(ForgeFixture.Attempt::revised));
        // a comparable pair (same group) and an incomparable one (other context SHA)
        assertEquals(fixture.execution("a-inv1").contextFingerprint(), fixture.execution("a-inv2").contextFingerprint());
        assertTrue(!fixture.execution("a-inv1").contextFingerprint()
                .equals(fixture.execution("a-inv3").contextFingerprint()));
        // identical summary in both projects: the isolation trap
        assertEquals(fixture.execution("a-inv1").summary(), fixture.execution("b-inv").summary());
    }

    @Test
    void relevanceIsEmptyExactlyForNoRelevantAndBlankQueries() {
        for (ForgeFixture.Query q : fixture.queries()) {
            boolean empty = q.kind() == QueryKind.NO_RELEVANT || q.kind() == QueryKind.BLANK;
            assertEquals(empty, q.relevant().isEmpty(), q.id());
        }
        assertTrue(fixture.queries().stream().filter(q -> q.kind() == QueryKind.BLANK)
                .allMatch(q -> q.text().isBlank()));
    }

    @Test
    void usesFixedSyntheticPricesAndNoLiveProvider() {
        assertEquals("USD", fixture.price().currency());
        assertEquals(java.time.LocalDate.parse("2025-12-01"), fixture.price().effective());
        assertTrue(fixture.price().provider().startsWith("fixture"));
    }

    @Test
    void rejectsACorpusBelowTheMinimums() {
        ForgeFixture tiny = new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(),
                fixture.price(), fixture.recallLimit(), fixture.ks(), fixture.executions(),
                fixture.attempts().subList(0, 10), fixture.queries(), fixture.expectations());
        assertThrows(IllegalStateException.class, tiny::validate);
    }

    @Test
    void rejectsAQueryJudgingAnExperienceOfAnotherProject() {
        ForgeFixture.Query crossing = new ForgeFixture.Query("QX", QueryKind.DIRECT, "alpha", "invoice",
                Set.of("b-inv/a1"), "judges a beta experience for an alpha query");
        List<ForgeFixture.Query> queries = new java.util.ArrayList<>(fixture.queries());
        queries.add(crossing);
        ForgeFixture bad = new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(),
                fixture.price(), fixture.recallLimit(), fixture.ks(), fixture.executions(), fixture.attempts(),
                queries, fixture.expectations());
        IllegalStateException e = assertThrows(IllegalStateException.class, bad::validate);
        assertTrue(e.getMessage().contains("another project"), e.getMessage());
    }

    @Test
    void rejectsAnUnknownRelevantExperience() {
        ForgeFixture.Query unknown = new ForgeFixture.Query("QY", QueryKind.DIRECT, "alpha", "invoice",
                Set.of("a-nothing/a1"), "unknown id");
        List<ForgeFixture.Query> queries = new java.util.ArrayList<>(fixture.queries());
        queries.add(unknown);
        ForgeFixture bad = new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(),
                fixture.price(), fixture.recallLimit(), fixture.ks(), fixture.executions(), fixture.attempts(),
                queries, fixture.expectations());
        assertThrows(IllegalStateException.class, bad::validate);
    }
}
