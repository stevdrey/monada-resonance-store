package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
import com.monada.evaluation.execution.ThresholdSweepReport.Kind;
import com.monada.evaluation.execution.ThresholdSweepReport.PointSummary;
import com.monada.evaluation.execution.ThresholdSweepReport.QuerySweep;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end threshold sweep (issue #123) over the bundled #122 stress set: the hard checks pass, two fresh
 * evaluations render the same report, 0.00 is the current recall, both abstention and lost relevant matches are
 * visible, no-relevant queries stay out of the positive denominators and no threshold is recommended.
 */
class ThresholdSweepEvaluationTest {
    @TempDir
    static Path work;

    private static ThresholdSweepReport report;

    @BeforeAll
    static void evaluate() {
        report = ThresholdSweep.evaluate(RecallStressFixture.load(), work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesOnTheBundledFixture() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(ThresholdSweepReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(ThresholdSweepReport.Decision.SWEEP_CHARACTERIZED, report.decision());
    }

    @Test
    void theDeclaredGridHasTheBaselineAndAtLeastFiveCandidates() {
        assertEquals(ThresholdSweep.GRID, report.grid());
        assertEquals(0.0, report.grid().get(0));
        assertTrue(report.grid().size() >= 6);
        assertTrue(report.render().contains("Declared grid: 0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, "
                + "0.45"));
    }

    @Test
    void thresholdZeroIsTheUnfilteredTopKOfTheStressSet() {
        int k = report.stress().deepestK();
        for (QuerySweep r : report.rows()) {
            QueryOutcome q = report.stress().outcome(r.id());
            assertEquals(q.hits().stream().limit(k).toList(), r.kept().get(0), r.id());
        }
    }

    @Test
    void aNoRelevantQueryAbstainsAtSomeCandidate() {
        QuerySweep q12 = report.row("Q12"); // the #102 no-relevant query, best false neighbour about 0.21
        assertEquals(Kind.NO_RELEVANT, q12.kind());
        assertFalse(q12.abstains(0));
        assertTrue(q12.abstains(report.point(0.25)));
        assertTrue(report.firstFullNoRelevantAbstention().isPresent());
    }

    @Test
    void aPositiveQueryLosesRelevantResultsAtSomeCandidate() {
        QuerySweep s01 = report.row("S01"); // the paraphrase whose relevant hits top out at about 0.18
        assertEquals(Kind.SCORED, s01.kind());
        assertEquals(3, s01.relevantKept(0));
        assertTrue(s01.abstains(report.point(0.20)));
        assertEquals(3, s01.lostRelevant(report.point(0.20)).size());
        assertTrue(report.render().contains("scored queries abstaining: S01"));
    }

    @Test
    void noRelevantAndBlankQueriesStayOutOfThePositiveDenominators() {
        PointSummary all = report.summary("ALL", 0);
        List<QuerySweep> scored = report.rows().stream().filter(r -> r.kind() == Kind.SCORED).toList();
        assertEquals(scored.size(), all.scored());
        assertEquals(scored.stream().mapToInt(r -> r.outcome().query().relevant().size()).sum(),
                all.judgedPositives());
        assertEquals(List.of("Q12", "S17", "S18", "S19"), report.rows().stream()
                .filter(r -> r.kind() == Kind.NO_RELEVANT).map(QuerySweep::id).toList());
        assertEquals(List.of("Q14", "S23", "S24", "S25", "S26"), report.rows().stream()
                .filter(r -> r.kind() == Kind.EMPTY).map(QuerySweep::id).toList());
        assertEquals(report.rows().size(), all.scored() + all.noRelevant() + 5);
        assertEquals(0.0, all.noRelevantAbstention(), "every no-relevant content query returns hits at 0.00");
    }

    @Test
    void theReportLabelsEveryOperatingPointExploratoryAndHostOwned() {
        String text = report.render();
        assertTrue(text.contains("EXPLORATORY"), text);
        assertTrue(text.contains("host-owned"), text);
        assertTrue(text.contains("illustrations, not recommendations"), text);
        assertTrue(text.contains("Threshold: " + ThresholdSweepReport.THRESHOLD_CONCLUSION), text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("recommended threshold"), text);
    }

    /** The command runs a second, fresh evaluation, so it also checks that two runs render the same report. */
    @Test
    void theCommandExitsZeroAndAFreshRunRendersTheSameReport() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = ThresholdSweepMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        String rendered = report.render();
        assertTrue(out.startsWith(rendered + "\n== Elapsed timing"), out);
        assertFalse(rendered.contains("Elapsed timing"));
        assertFalse(rendered.contains(" ms\n"));
    }
}
