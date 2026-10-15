package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.RecallStressEvaluator.Observation;
import com.monada.evaluation.execution.RecallStressEvaluator.Observed;
import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.GroupSummary;
import com.monada.evaluation.execution.RecallStressReport.Neighbour;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import com.monada.evaluation.execution.RecallStressReport.Reason;
import com.monada.evaluation.execution.RecallStressReport.RelevantPosition;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end recall stress set (issue #122): the hard checks pass on the bundled fixture, two fresh evaluations
 * render the same report, zero-relevant queries stay out of every denominator, and the hit and query classifiers
 * follow their documented precedence.
 */
class RecallStressEvaluationTest {
    @TempDir
    static Path work;

    private static RecallStressFixture fixture;
    private static RecallStressReport report;

    @BeforeAll
    static void evaluate() {
        fixture = RecallStressFixture.load();
        report = RecallStressEvaluator.evaluate(fixture, work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesOnTheBundledFixture() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(RecallStressReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(RecallStressReport.Decision.STRESS_SET_CHARACTERIZED, report.decision());
    }

    @Test
    void twoFreshEvaluationsRenderTheSameReport() {
        RecallStressReport second = RecallStressEvaluator.evaluate(RecallStressFixture.load(), work.resolve("second"));
        assertEquals(report.render(), second.render());
        assertFalse(report.render().contains("Elapsed timing"));
        assertFalse(report.render().contains(" ms\n"));
    }

    @Test
    void zeroRelevantQueriesAreReportedButExcludedFromEveryDenominator() {
        GroupSummary all = group("ALL_STRESS");
        assertEquals(26, all.queries());
        assertEquals(List.of("S10", "S17", "S18", "S19", "S23", "S24", "S25", "S26"), all.excludedIds());
        assertEquals(all.queries(), all.scoredIds().size() + all.excludedIds().size());
        assertEquals(List.of("Q12", "Q14"), group("BASELINE").excludedIds());
        GroupSummary none = group("NO_RELEVANT");
        assertTrue(Double.isNaN(none.mrr()), "an empty denominator is n/a, never zero");
        assertTrue(report.render().contains("NO_RELEVANT          3      0        3    n/a"));
        report.queries().stream().filter(q -> !q.scored())
                .forEach(q -> assertTrue(q.hitAt().isEmpty() && q.recallAt().isEmpty(), q.query().id()));
    }

    @Test
    void reportsRankScoreMissesAndReasonPerQuery() {
        QueryOutcome q02 = report.outcome("Q02"); // the #102 paraphrase gap stays visible in the baseline group
        assertEquals(Reason.PARTIAL_RECALL, q02.reason());
        assertTrue(q02.relevantPositions().stream().anyMatch(p -> p.rank() > 5), "a relevant hit outside the top 5");
        String text = report.render();
        assertTrue(text.contains("Query Q02 [BASELINE/PARAPHRASE, alpha]"));
        assertTrue(text.contains("Misses outside top 5: a-page/a2 at #"), text);

        QueryOutcome s07 = report.outcome("S07");
        assertEquals(1, s07.hits().get(0).rank());
        assertEquals(Neighbour.JUDGED_CONFUSABLE, s07.hits().get(0).neighbour());
        assertEquals(Reason.OUTRANKED, s07.reason());
        assertEquals(Neighbour.JUDGED_CONFUSABLE, s07.leadNeighbour().orElseThrow());
    }

    @Test
    void blankQueriesReturnNoHitAndNoRelevantQueriesShowTheirFalseNeighbours() {
        for (String id : List.of("Q14", "S23", "S24")) {
            assertTrue(report.outcome(id).hits().isEmpty(), id);
            assertEquals(Reason.EMPTY_AS_EXPECTED, report.outcome(id).reason());
        }
        QueryOutcome s19 = report.outcome("S19");
        assertEquals(Reason.FALSE_NEIGHBOURS_RETURNED, s19.reason());
        assertTrue(s19.hits().stream().allMatch(h -> h.neighbour().falseNeighbour()));
    }

    @Test
    void baselineRankingsEqualThe102Evaluation() {
        ForgeEvaluationReport baseline = ForgeExperienceEvaluator.evaluate(fixture.corpus(), work.resolve("b102"));
        assertTrue(baseline.allGatesPassed(), baseline.render());
        for (ForgeQueryResult r : baseline.queries()) {
            assertEquals(r.rankedIds(), report.outcome(r.query().id()).rankedIds(), r.query().id());
        }
    }

    @Test
    void mainPrintsTheReportThenTimingAndExitsZero() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = RecallStressEvaluationMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        assertTrue(out.indexOf("STRESS_SET_CHARACTERIZED") < out.indexOf("== Elapsed timing"));
        assertEquals(report.render(), out.substring(0, out.indexOf("\n== Elapsed timing")));
    }

    // ---------------------------------------------------------------- classifiers on synthetic input

    @Test
    void hitCategoriesFollowTheirPrecedence() {
        ForgeFixture corpus = fixture.corpus();
        StressQuery q = query(Set.of("a-inv1/a2"), Set.of("a-inv3/a1"));
        assertEquals(Neighbour.RELEVANT, RecallStressEvaluator.neighbour(corpus, q, hit("a-inv1/a2", 0.0)));
        assertEquals(Neighbour.JUDGED_CONFUSABLE, RecallStressEvaluator.neighbour(corpus, q, hit("a-inv3/a1", 0.0)));
        assertEquals(Neighbour.SAME_EXECUTION, RecallStressEvaluator.neighbour(corpus, q, hit("a-inv1/a1", 0.5)));
        assertEquals(Neighbour.SAME_TASK, RecallStressEvaluator.neighbour(corpus, q, hit("a-inv2/a1", 0.5)));
        assertEquals(Neighbour.LEXICAL_NEIGHBOUR, RecallStressEvaluator.neighbour(corpus, q, hit("a-page/a1", 0.1)));
        assertEquals(Neighbour.ZERO_SIMILARITY, RecallStressEvaluator.neighbour(corpus, q, hit("a-page/a1", 0.0)));
    }

    @Test
    void queryReasonsFollowTheTaxonomy() {
        assertEquals(Reason.RESOLVED, reason(pos(1, 0.5), pos(5, 0.1)));
        assertEquals(Reason.PARTIAL_RECALL, reason(pos(1, 0.5), pos(6, 0.1)));
        assertEquals(Reason.PARTIAL_RECALL, reason(pos(1, 0.5), pos(0, 0.0)));
        assertEquals(Reason.OUTRANKED, reason(pos(2, 0.5)));
        assertEquals(Reason.NOT_IN_TOP_K, reason(pos(6, 0.5)));
        assertEquals(Reason.ZERO_SIMILARITY_ONLY, reason(pos(1, 0.0), pos(3, 0.0)));
        assertEquals(Reason.NOT_RETRIEVED, reason(pos(0, 0.0)));
    }

    @Test
    void ranksMarkSimilarityTiesAndUnretrievedRelevantExperiences() {
        StressQuery q = query(Set.of("a-page/a3", "a-page/a2"), Set.of());
        QueryOutcome o = RecallStressEvaluator.outcome(fixture.corpus(), q, "CURRENT",
                List.of(hit("a-page/a1", 0.4), hit("a-page/a3", 0.4), hit("a-tz/a1", 0.0)), List.of(1, 3, 5), 5);
        assertFalse(o.hits().get(0).tie());
        assertTrue(o.hits().get(1).tie());
        assertEquals(List.of(new RelevantPosition("a-page/a3", 2, 0.4), new RelevantPosition("a-page/a2", 0, 0.0)),
                o.relevantPositions());
        assertEquals(0.5, o.reciprocalRank());
        assertEquals(0.5, o.recallAt().get(5));
        assertEquals(Reason.OUTRANKED, o.reason());
    }

    // ---------------------------------------------------------------- hard checks fail on bad observations

    @Test
    void determinismCheckFailsWhenRankingsDiffer() {
        Observation a = observation("S01", List.of(hit("a-page/a1", 0.5), hit("a-page/a2", 0.4)));
        Observation b = observation("S01", List.of(hit("a-page/a2", 0.5), hit("a-page/a1", 0.4)));
        assertTrue(RecallStressEvaluator.determinismGate(a, a).passed());
        assertFalse(RecallStressEvaluator.determinismGate(a, b).passed());
    }

    @Test
    void blankQueryCheckFailsWhenABlankQueryHasHits() {
        Map<String, String> state = new LinkedHashMap<>();
        Map<String, List<Observed>> hits = new LinkedHashMap<>();
        fixture.queries().forEach(q -> {
            state.put(q.id(), "CURRENT");
            hits.put(q.id(), q.id().equals("S24") ? List.of(hit("a-page/a1", 0.0)) : List.of());
        });
        assertFalse(RecallStressEvaluator.blankQueryGate(fixture, new Observation(state, hits)).passed());
    }

    @Test
    void scopedRecallCheckFailsOnAForeignHit() {
        Map<String, String> state = new LinkedHashMap<>();
        Map<String, List<Observed>> hits = new LinkedHashMap<>();
        fixture.queries().forEach(q -> {
            state.put(q.id(), "CURRENT");
            hits.put(q.id(), List.of());
        });
        hits.put("S01", List.of(new Observed("b-page/a1", fixture.corpus().scopeId("beta"), "b-page", 1, 0.3)));
        ForgeGate gate = RecallStressEvaluator.scopedRecallGate(fixture, new Observation(state, hits));
        assertFalse(gate.passed());
        assertTrue(gate.details().stream().anyMatch(d -> d.contains("foreign-scope hit b-page/a1")), gate.details()
                .toString());
    }

    private static GroupSummary group(String name) {
        return report.groups().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private static StressQuery query(Set<String> relevant, Set<String> confusable) {
        return new StressQuery("T01", Category.NEGATION, "NEGATION", "alpha", "synthetic", relevant, confusable,
                "synthetic judgment");
    }

    private static Observed hit(String experienceId, double similarity) {
        String execution = RecallStressFixture.executionOf(experienceId);
        String scopeKey = fixture.corpus().execution(execution).scopeKey();
        return new Observed(experienceId, fixture.corpus().scopeId(scopeKey), execution, 1, similarity);
    }

    private static RelevantPosition pos(int rank, double similarity) {
        return new RelevantPosition("x/a" + rank, rank, similarity);
    }

    private static Reason reason(RelevantPosition... positions) {
        return RecallStressEvaluator.reason(List.of(positions), 5);
    }

    private static Observation observation(String id, List<Observed> hits) {
        return new Observation(Map.of(id, "CURRENT"), Map.of(id, hits));
    }
}
