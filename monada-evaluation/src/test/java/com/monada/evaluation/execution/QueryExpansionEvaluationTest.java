package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.QueryExpansionReport.CandidateResult;
import com.monada.evaluation.execution.QueryExpansionReport.QueryComparison;
import com.monada.evaluation.execution.QueryExpansionReport.Verdict;
import com.monada.evaluation.execution.QueryExpansionReport.Veto;
import com.monada.evaluation.execution.RecallStressReport.QueryOutcome;
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
 * End-to-end comparison (issue #124) over the bundled #122 stress set: the hard checks pass, the exact side is the
 * #122 recall, unexpanded queries are untouched, every query is printed side by side with its judgments, the risky
 * rule is vetoed, nothing is adopted and a fresh run renders the same report.
 */
class QueryExpansionEvaluationTest {
    @TempDir
    static Path work;

    private static QueryExpansionReport report;

    @BeforeAll
    static void evaluate() {
        report = QueryExpansionEvaluator.evaluate(RecallStressFixture.load(), QueryExpansion.load(),
                work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesOnTheBundledFixture() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(QueryExpansionReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(QueryExpansionReport.Decision.COMPARISON_CHARACTERIZED, report.decision());
    }

    @Test
    void theExactSideIsTheStressRecallAndUnexpandedQueriesAreUntouched() {
        for (CandidateResult c : report.candidates()) {
            assertEquals(report.stress().queries().size(), c.rows().size());
            for (QueryComparison r : c.rows()) {
                QueryOutcome stress = report.stress().outcome(r.id());
                assertEquals(stress.hits(), r.exact().hits(), r.id());
                if (!r.expansion().changed()) {
                    assertEquals(r.exact().query().text(), r.expansion().expanded(), r.id());
                    assertEquals(r.exact().hits(), r.candidate().hits(), c.candidate().name() + " " + r.id());
                }
                if (r.exact().query().text().isBlank()) {
                    assertFalse(r.expansion().changed(), r.id());
                    assertEquals(List.of(), r.candidate().hits(), r.id());
                }
            }
        }
    }

    @Test
    void everyQueryIsPrintedSideBySideWithItsJudgments() {
        String text = report.render();
        for (QueryOutcome q : report.stress().queries()) {
            int start = text.indexOf("\nQuery " + q.query().id() + " [");
            assertTrue(start >= 0, q.query().id());
            int end = text.indexOf("\nQuery ", start + 1);
            String block = text.substring(start, end < 0 ? text.indexOf("\n== Hard checks") : end);
            assertTrue(block.contains("  Relevant: "), block);
            assertTrue(block.contains("  Confusable: "), block);
            assertTrue(block.contains("  EXACT reason "), block);
            q.query().relevant().forEach(id -> assertTrue(block.contains(id), block));
            for (CandidateResult c : report.candidates()) {
                assertTrue(block.contains("  " + c.candidate().name() + ": "), block);
            }
        }
        QueryComparison s06 = report.candidate("FULL").row("S06");
        assertTrue(text.contains("  FULL: \"" + s06.expansion().expanded() + "\""), text);
    }

    @Test
    void theCollisionStressCaseVetoesTheRiskyRuleAndTheFullCandidate() {
        CandidateResult r23 = report.rule("R23");
        assertEquals(Verdict.REJECTED, r23.verdict());
        assertTrue(r23.vetoes().stream().anyMatch(v -> v.code().equals("V4") && v.queryId().equals("S18")),
                r23.vetoes().toString());
        CandidateResult full = report.candidate("FULL");
        assertEquals(Verdict.REJECTED, full.verdict());
        assertTrue(full.vetoes().stream().map(Veto::queryId).toList().contains("S18"));
        assertFalse(full.idsWith(QueryExpansionReport.Effect.IMPROVED).isEmpty(),
                "FULL improves aggregate recall and is still rejected");
    }

    @Test
    void theLowRiskCandidateIsVetoedByTheCursorPagingConfusableAndNothingIsAdopted() {
        CandidateResult low = report.candidate("LOW_RISK");
        assertTrue(low.candidate().ruleIds().stream()
                .allMatch(id -> report.table().rule(id).risk() == QueryExpansion.Risk.LOW));
        assertEquals(Verdict.REJECTED, low.verdict(), low.vetoes().toString());
        assertTrue(low.vetoes().stream().anyMatch(v -> v.code().equals("V3") && v.queryId().equals("S13")),
                low.vetoes().toString());
        assertFalse(low.idsWith(QueryExpansionReport.Effect.IMPROVED).isEmpty());
        String text = report.render();
        assertTrue(text.contains("No candidate is adopted"), text);
        assertTrue(text.contains("in-sample"), text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("adopted as default"), text);
    }

    @Test
    void isolatedRulesReuseTheExactRankingsOfTheQueriesTheyDoNotChange() {
        for (CandidateResult c : report.rules()) {
            for (QueryComparison r : c.rows()) {
                if (!r.expansion().changed()) {
                    assertEquals(r.exact().hits(), r.candidate().hits(), c.candidate().name() + " " + r.id());
                }
                for (String id : r.expansion().fired()) {
                    assertTrue(QueryExpansion.tokens(r.exact().query().text())
                            .contains(report.table().rule(id).source()), "no chaining: " + r.id());
                }
            }
        }
    }

    @Test
    void theNegationStressCaseVetoesTheRuleThatStrengthensTheExcludedApproach() {
        // S07 excludes offset paging; paginate -> pagination makes the offset attempt score higher at #1.
        CandidateResult r10 = report.rule("R10");
        assertEquals(Verdict.REJECTED, r10.verdict());
        assertTrue(r10.vetoes().stream().anyMatch(v -> v.code().equals("V3") && v.queryId().equals("S07")),
                r10.vetoes().toString());
        for (String name : List.of("FULL", "LOW_RISK")) {
            assertTrue(report.candidate(name).row("S07").confusableRises().stream()
                    .anyMatch(x -> x.startsWith("a-page/a1 ")), name);
        }
    }

    /** The command runs a second, fresh evaluation, so it also checks that two runs render the same report. */
    @Test
    void theCommandExitsZeroAndAFreshRunRendersTheSameReport() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = QueryExpansionMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        String rendered = report.render();
        assertTrue(out.startsWith(rendered + "\n== Elapsed timing"), out);
        assertFalse(rendered.contains("Elapsed timing"));
        assertFalse(rendered.contains(" ms\n"));
    }
}
