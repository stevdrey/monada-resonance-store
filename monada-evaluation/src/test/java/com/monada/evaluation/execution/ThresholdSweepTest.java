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
        assertEquals(1, base.noRelevant(), "the blank query is in neither denominator");
        assertEquals(1, base.falseReturnQueries());
        assertEquals(3, base.falseHits());
        assertEquals(6, base.kept(), "hits of the no-relevant query are not in the precision denominator");
        assertEquals(3, base.relevantKept());
        assertEquals(0.5, base.precision());
        assertEquals(1.0, base.recall());
        assertEquals(Map.of(Neighbour.JUDGED_CONFUSABLE, 1, Neighbour.LEXICAL_NEIGHBOUR, 1,
                Neighbour.ZERO_SIMILARITY, 1), base.wrongKept());

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
        assertTrue(text.contains("no declared threshold both silences every no-relevant query"), text);
        assertTrue(text.contains("Threshold: UNDECIDED (host-owned"), text);
        assertTrue(text.contains("illustrations, not recommendations"), text);
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
        assertFalse(ThresholdSweep.baselineIdentityGate(List.of(new QuerySweep(q, broken)), 5).passed());
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

    /** S90 no-relevant (best 0.21), S91 two relevant at 0.40 and 0.15, S92 relevant only as 0.0 fill, S94 blank. */
    private static RecallStressReport stress() {
        List<QueryOutcome> queries = List.of(
                outcome("S90", Category.NO_RELEVANT, "nothing", Set.of(), List.of(
                        hit(1, "n/a1", 0.21, Neighbour.JUDGED_CONFUSABLE), hit(2, "n/a2", 0.05,
                                Neighbour.LEXICAL_NEIGHBOUR), hit(3, "n/a3", 0.0, Neighbour.ZERO_SIMILARITY))),
                outcome("S91", Category.SYNONYM, "positive", Set.of("p/a1", "p/a2"), List.of(
                        hit(1, "p/a1", 0.40, Neighbour.RELEVANT), hit(2, "c/a1", 0.30, Neighbour.JUDGED_CONFUSABLE),
                        hit(3, "p/a2", 0.15, Neighbour.RELEVANT), hit(4, "l/a1", 0.05, Neighbour.LEXICAL_NEIGHBOUR))),
                outcome("S92", Category.TERMINOLOGY, "fill", Set.of("f/a1"), List.of(
                        hit(1, "f/a1", 0.0, Neighbour.RELEVANT), hit(2, "z/a1", 0.0, Neighbour.ZERO_SIMILARITY))),
                outcome("S94", Category.EMPTY_QUERY, " ", Set.of(), List.of()));
        return new RecallStressReport("1", "synthetic", "judged", "test", 3, 1, 50, List.of(1, 5), queries,
                List.of(), Map.of());
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
