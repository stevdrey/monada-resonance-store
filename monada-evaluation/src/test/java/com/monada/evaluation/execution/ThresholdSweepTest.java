package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.RecallStressFixture.Category;
import com.monada.evaluation.execution.RecallStressFixture.StressQuery;
import com.monada.evaluation.execution.RecallStressReport.Hit;
import com.monada.evaluation.execution.RecallStressReport.Neighbour;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import com.monada.evaluation.execution.RecallStressReport.Reason;
import com.monada.evaluation.execution.ThresholdSweepReport.Kind;
import com.monada.evaluation.execution.ThresholdSweepReport.PointSummary;
import com.monada.evaluation.execution.ThresholdSweepReport.QuerySweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The threshold post-filter on fixed, synthetic rankings (issue #123): inclusive boundaries, ties kept or dropped
 * together, abstention of no-relevant queries, lost relevant hits of positive queries, separate denominators, and the
 * hard checks.
 */
class ThresholdSweepTest {
    private static final List<Double> GRID = List.of(0.0, 0.1, 0.2, 0.25, 0.3, 0.5);
    private static final String NOT_EVALUABLE = "not evaluable: no content-bearing no-relevant queries";
    private static final String NONE_SILENCES = "No declared threshold makes every no-relevant content query abstain.";

    @Test
    void theBoundaryIsInclusiveAndTiesAtTheThresholdMoveTogether() {
        List<Hit> ranked = List.of(hit(1, "x/a1", 0.30, Neighbour.RELEVANT), hit(2, "x/a2", 0.20, Neighbour.RELEVANT),
                hit(3, "x/a3", 0.20, Neighbour.LEXICAL_NEIGHBOUR), hit(4, "x/a4", 0.10, Neighbour.LEXICAL_NEIGHBOUR));
        assertEquals(ranked.subList(0, 3), ThresholdSweep.retained(ranked, 0.20, 5), "similarity == threshold is kept");
        assertEquals(ranked.subList(0, 1), ThresholdSweep.retained(ranked, Math.nextUp(0.20), 5),
                "both hits tied at 0.20 are dropped together just above it");
        assertEquals(ranked.subList(0, 2), ThresholdSweep.retained(ranked, 0.0, 2), "top K is read after filtering");
    }

    @Test
    void thresholdZeroKeepsZeroSimilarityFill() {
        List<Hit> ranked = List.of(hit(1, "x/a1", 0.4, Neighbour.RELEVANT), hit(2, "x/a2", 0.0, Neighbour.RELEVANT));
        assertEquals(ranked, ThresholdSweep.retained(ranked, 0.0, 5));
        assertEquals(ranked.subList(0, 1), ThresholdSweep.retained(ranked, 0.1, 5));
    }

    @Test
    void aNoRelevantQueryAbstainsAndAPositiveQueryLosesResults() {
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(), GRID);
        QuerySweep none = report.row("S90");
        assertEquals(Kind.NO_RELEVANT, none.kind());
        assertFalse(none.abstains(report.point(0.2)), "best false neighbour 0.21 is still returned at 0.20");
        assertTrue(none.abstains(report.point(0.25)));

        QuerySweep positive = report.row("S91");
        assertEquals(2, positive.relevantKept(0));
        assertEquals(List.of("p/a2"), positive.lostRelevant(report.point(0.2)).stream().map(Hit::experienceId)
                .toList());
        assertTrue(positive.abstains(report.point(0.5)));
        assertEquals(2, positive.lostRelevant(report.point(0.5)).size());

        String text = report.render();
        assertTrue(text.contains("S91 p/a2 sim=0.1500"), text);
        assertTrue(text.contains("no-relevant queries abstaining: S90"), text);
    }

    @Test
    void noRelevantAndBlankQueriesNeverEnterTheScoredDenominators() {
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(), GRID);
        PointSummary base = report.summary("ALL", 0);
        assertEquals(2, base.scored());
        assertEquals(3, base.judgedPositives(), "only the relevant sets of scored queries");
        assertEquals(1, base.noRelevant(), "blank and content-free EMPTY_QUERY probes are in neither denominator");
        assertEquals(Kind.EMPTY, report.row("S94").kind());
        assertEquals(Kind.EMPTY, report.row("S95").kind(), "a non-blank punctuation probe is EMPTY, not NO_RELEVANT");
        assertEquals(1, base.falseReturnQueries());
        assertEquals(3, base.falseHits());
        assertEquals(6, base.kept(), "hits of the no-relevant query are not in the precision denominator");
        assertEquals(3, base.relevantKept());
        assertEquals(0.5, base.precision());
        assertEquals(1.0, base.recall());
        assertEquals(Map.of(Neighbour.JUDGED_CONFUSABLE, 1, Neighbour.LEXICAL_NEIGHBOUR, 1,
                Neighbour.ZERO_SIMILARITY, 1), base.wrongKept());
        assertThrows(UnsupportedOperationException.class, () -> base.wrongKept().put(Neighbour.SAME_TASK, 1));

        PointSummary high = report.summary("ALL", report.point(0.5));
        assertTrue(Double.isNaN(high.precision()), "nothing kept: precision is n/a, never zero");
        assertEquals(0.0, high.recall());
        assertEquals(1.0, high.abstention());
        assertEquals(1.0, high.noRelevantAbstention());

        PointSummary empty = report.summary(Category.PARAPHRASE.name(), 0);
        assertTrue(Double.isNaN(empty.recall()) && Double.isNaN(empty.noRelevantAbstention()));
        assertTrue(report.render().contains("n/a"));
    }

    @Test
    void operatingPointsAreComputedAndLeftUndecided() {
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(), GRID);
        assertEquals(Optional.of(report.point(0.25)), report.firstFullNoRelevantAbstention());
        // The 0.0 fill relevant hit of S92 is lost at 0.1 but does not count as a positive-similarity loss.
        assertEquals(report.point(0.1), report.lastWithoutPositiveRelevantLoss());
        String text = report.render();
        assertTrue(text.contains("Lowest threshold at which every no-relevant content query abstains: 0.25"), text);
        assertFalse(text.contains(NOT_EVALUABLE), text);
        assertTrue(text.contains("no declared threshold both silences every no-relevant query"), text);
        assertTrue(text.contains("Threshold: UNDECIDED (host-owned"), text);
        assertTrue(text.contains("illustrations, not recommendations"), text);
    }

    @Test
    void onlyScoredQueriesLeaveFullNoRelevantAbstentionNotEvaluable() {
        assertNotEvaluable(ThresholdSweep.sweep(stress(s91(), s92()), GRID));
    }

    @Test
    void onlyEmptyProbesNeverManufactureAFullAbstention() {
        QueryOutcome stopwords = outcome("S96", Category.EMPTY_QUERY, "the and of", Set.of(), List.of());
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(s94(), s95(), stopwords), GRID);
        assertEquals(Kind.EMPTY, report.row("S96").kind(), "a stopwords-only probe is EMPTY, not NO_RELEVANT");
        assertNotEvaluable(report);
    }

    @Test
    void scoredAndEmptyQueriesWithoutNoRelevantOnesAreNotEvaluable() {
        assertNotEvaluable(ThresholdSweep.sweep(stress(s91(), s92(), s94(), s95()), GRID));
    }

    @Test
    void aNoRelevantQueryThatNeverAbstainsIsExplainedDifferentlyFromAnAbsentSet() {
        QueryOutcome loud = outcome("S97", Category.NO_RELEVANT, "loud", Set.of(),
                List.of(hit(1, "n/a9", 0.90, Neighbour.JUDGED_CONFUSABLE)));
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(s90(), loud, s91(), s94()), GRID);
        assertEquals(Optional.empty(), report.firstFullNoRelevantAbstention());
        String text = report.render();
        assertTrue(text.contains(NONE_SILENCES), text);
        assertFalse(text.contains(NOT_EVALUABLE), text);
        assertTrue(text.contains("no declared threshold both silences every no-relevant query"), text);
    }

    private static void assertNotEvaluable(ThresholdSweepReport report) {
        assertEquals(0, report.summary("ALL", 0).noRelevant());
        assertEquals(Optional.empty(), report.firstFullNoRelevantAbstention(), "no vacuous operating point at 0.00");
        String text = report.render();
        assertTrue(text.contains("Full no-relevant abstention: n/a — " + NOT_EVALUABLE), text);
        assertTrue(text.contains("Tradeoff: n/a on this set"), text);
        assertFalse(text.contains("Lowest threshold at which every no-relevant"), text);
        assertFalse(text.contains(NONE_SILENCES), text);
        assertFalse(text.contains("silences every no-relevant query"), text);
        assertTrue(text.contains("Threshold: UNDECIDED (host-owned"), text);
    }

    @Test
    void hardChecksPassOnAConsistentSweepAndDetectANonPrefix() {
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(), GRID);
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(ThresholdSweepReport.expectedGateIds(),
                Set.copyOf(report.gates().stream().map(ForgeGate::id).toList()));

        QueryOutcome q = outcome("S93", Category.PARAPHRASE, "x", Set.of("x/a1"),
                List.of(hit(1, "x/a1", 0.4, Neighbour.RELEVANT), hit(2, "x/a2", 0.3, Neighbour.LEXICAL_NEIGHBOUR)));
        List<List<Hit>> broken = new ArrayList<>(List.of(q.hits().subList(0, 1), q.hits()));
        ForgeGate gate = ThresholdSweep.monotonicGate(List.of(new QuerySweep(q, broken)), List.of(0.0, 0.1));
        assertFalse(gate.passed());
        assertFalse(ThresholdSweep.filterSemanticsGate(List.of(new QuerySweep(q, broken)), List.of(0.0, 0.1), 5)
                .passed(), "0.0 must keep the unfiltered top K");
    }

    @Test
    void theFilterSemanticsCheckCatchesBoundaryOrderAndCutErrors() {
        List<Hit> ranked = List.of(hit(1, "x/a1", 0.4, Neighbour.RELEVANT), hit(2, "x/a2", 0.3,
                Neighbour.LEXICAL_NEIGHBOUR), hit(3, "x/a3", 0.3, Neighbour.LEXICAL_NEIGHBOUR));
        QueryOutcome q = outcome("S93", Category.PARAPHRASE, "x", Set.of("x/a1"), ranked);
        List<Double> grid = List.of(0.0, 0.3);
        assertTrue(semantics(q, List.of(ranked, ranked), grid, 5).passed());
        assertFalse(semantics(q, List.of(ranked, ranked.subList(0, 1)), grid, 5).passed(),
                "an exclusive boundary drops the hits tied at 0.3");
        assertFalse(semantics(q, List.of(ranked, List.of(ranked.get(0), ranked.get(2))), grid, 5).passed(),
                "an eligible hit ranked above a kept one was skipped");
        assertFalse(semantics(q, List.of(ranked, List.of(ranked.get(1), ranked.get(0), ranked.get(2))), grid, 5)
                .passed(), "rank order changed");
        assertFalse(semantics(q, List.of(ranked.subList(0, 2), ranked.subList(0, 2)), grid, 3).passed(),
                "cut at the wrong K");
        List<Hit> below = List.of(ranked.get(0), hit(2, "x/a4", 0.2, Neighbour.LEXICAL_NEIGHBOUR));
        QueryOutcome low = outcome("S96", Category.PARAPHRASE, "x", Set.of("x/a1"), below);
        assertFalse(semantics(low, List.of(below, below), List.of(0.0, 0.3), 5).passed(), "kept below threshold");
    }

    @Test
    void aReportRejectsRowsThatDoNotMatchTheGrid() {
        QueryOutcome q = outcome("S93", Category.PARAPHRASE, "x", Set.of("x/a1"),
                List.of(hit(1, "x/a1", 0.4, Neighbour.RELEVANT)));
        QuerySweep row = new QuerySweep(q, List.of(q.hits()));
        assertThrows(IllegalArgumentException.class,
                () -> new ThresholdSweepReport(stress(), GRID, List.of(row), List.of(), Map.of()));
    }

    private static ForgeGate semantics(QueryOutcome q, List<List<Hit>> kept, List<Double> grid, int k) {
        return ThresholdSweep.filterSemanticsGate(List.of(new QuerySweep(q, kept)), grid, k);
    }

    @Test
    void aFailedStressCheckFailsTheSweep() {
        RecallStressReport failing = new RecallStressReport("1", "p", "j", "r", 3, 1, 50, List.of(1, 5),
                stress().queries(), List.of(new ForgeGate(RecallStressReport.DETERMINISM, "d", false, List.of())),
                Map.of());
        ThresholdSweepReport report = ThresholdSweep.sweep(failing, GRID);
        assertEquals(List.of(ThresholdSweepReport.STRESS_CHECKS), report.failedGates());
        assertEquals(ThresholdSweepReport.Decision.CHECKS_FAILED, report.decision());
    }

    @Test
    void anUndeclaredOrMalformedGridIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ThresholdSweep.validateGrid(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ThresholdSweep.validateGrid(List.of(0.05, 0.1, 0.2, 0.3, 0.4, 0.5)), "no 0.0 baseline");
        assertThrows(IllegalArgumentException.class,
                () -> ThresholdSweep.validateGrid(List.of(0.0, 0.1, 0.2, 0.3, 0.4)), "only four candidates");
        assertThrows(IllegalArgumentException.class,
                () -> ThresholdSweep.validateGrid(List.of(0.0, 0.1, 0.3, 0.2, 0.4, 0.5)), "not ascending");
        assertThrows(IllegalArgumentException.class,
                () -> ThresholdSweep.validateGrid(List.of(0.0, 0.1, 0.1, 0.2, 0.4, 0.5)), "duplicate");
        assertThrows(IllegalArgumentException.class,
                () -> ThresholdSweep.validateGrid(List.of(0.0, 0.1, 0.2, 0.3, 0.4, Double.NaN)), "NaN");
        ThresholdSweep.validateGrid(ThresholdSweep.GRID);
        assertTrue(ThresholdSweep.GRID.size() - 1 >= ThresholdSweep.MIN_CANDIDATES);
    }

    @Test
    void anUnknownThresholdIsNotSilentlyMapped() {
        ThresholdSweepReport report = ThresholdSweep.sweep(stress(), GRID);
        assertThrows(IllegalArgumentException.class, () -> report.point(0.15));
    }

    /** S90 no-relevant (best 0.21), S91 two relevant at 0.40 and 0.15, S92 relevant only as 0.0 fill, S94 blank, S95
     * punctuation-only. */
    private static RecallStressReport stress() {
        return stress(s90(), s91(), s92(), s94(), s95());
    }

    private static RecallStressReport stress(QueryOutcome... queries) {
        return new RecallStressReport("1", "synthetic", "judged", "test", 3, 1, 50, List.of(1, 5), List.of(queries),
                List.of(), Map.of());
    }

    private static QueryOutcome s90() {
        return outcome("S90", Category.NO_RELEVANT, "nothing", Set.of(), List.of(
                hit(1, "n/a1", 0.21, Neighbour.JUDGED_CONFUSABLE), hit(2, "n/a2", 0.05, Neighbour.LEXICAL_NEIGHBOUR),
                hit(3, "n/a3", 0.0, Neighbour.ZERO_SIMILARITY)));
    }

    private static QueryOutcome s91() {
        return outcome("S91", Category.SYNONYM, "positive", Set.of("p/a1", "p/a2"), List.of(
                hit(1, "p/a1", 0.40, Neighbour.RELEVANT), hit(2, "c/a1", 0.30, Neighbour.JUDGED_CONFUSABLE),
                hit(3, "p/a2", 0.15, Neighbour.RELEVANT), hit(4, "l/a1", 0.05, Neighbour.LEXICAL_NEIGHBOUR)));
    }

    private static QueryOutcome s92() {
        return outcome("S92", Category.TERMINOLOGY, "fill", Set.of("f/a1"), List.of(
                hit(1, "f/a1", 0.0, Neighbour.RELEVANT), hit(2, "z/a1", 0.0, Neighbour.ZERO_SIMILARITY)));
    }

    private static QueryOutcome s94() {
        return outcome("S94", Category.EMPTY_QUERY, " ", Set.of(), List.of());
    }

    private static QueryOutcome s95() {
        return outcome("S95", Category.EMPTY_QUERY, "?!", Set.of(), List.of());
    }

    private static QueryOutcome outcome(String id, Category category, String text, Set<String> relevant,
                                        List<Hit> hits) {
        StressQuery q = new StressQuery(id, category, category.name(), "alpha", text, relevant, Set.of(), "test");
        return new QueryOutcome(q, "CURRENT", hits, List.of(), Map.of(), Map.of(), 0.0,
                relevant.isEmpty() ? Reason.FALSE_NEIGHBOURS_RETURNED : Reason.RESOLVED, Optional.empty());
    }

    private static Hit hit(int rank, String id, double similarity, Neighbour neighbour) {
        return new Hit(rank, id, similarity, false, neighbour);
    }
}
