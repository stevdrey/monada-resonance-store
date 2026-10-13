package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.ForgeEvaluationReport.Decision;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end: record, reopen, reconcile, recall, summarize, compare and export the Forge corpus through public
 * APIs only. Correctness gates are asserted exactly; retrieval quality is only checked structurally and is never
 * frozen as a protected baseline.
 */
class ForgeExperienceEvaluationTest {
    @TempDir static Path work;
    static ForgeFixture fixture;
    static ForgeEvaluationReport report;

    @BeforeAll
    static void evaluate() {
        fixture = ForgeFixture.load();
        report = ForgeExperienceEvaluator.evaluate(fixture, work);
    }

    @Test
    void everyHardGateIsReportedAndPasses() {
        assertEquals(ForgeEvaluationReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        for (ForgeGate gate : report.gates()) {
            assertTrue(gate.passed(), gate.id() + " " + gate.details());
        }
        assertEquals(List.of(), report.failedGates());
        assertEquals(Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT, report.decision());
    }

    @Test
    void zeroForeignScopeLeakageOnEveryQueryIncludingTheIdenticalSummaryTrap() {
        var q09 = report.queries().stream().filter(q -> q.query().id().equals("Q09")).findFirst().orElseThrow();
        assertFalse(q09.ranked().isEmpty());
        // alpha holds the identical task summary; every hit of the beta query is a beta experience
        for (var hit : q09.ranked()) {
            assertTrue(hit.experienceId().startsWith("b-"), hit.experienceId());
        }
        for (var q : report.queries()) {
            String prefix = q.query().scopeKey().equals("alpha") ? "a-" : "b-";
            for (var hit : q.ranked()) {
                assertTrue(hit.experienceId().startsWith(prefix), q.query().id() + " leaked " + hit.experienceId());
            }
        }
    }

    @Test
    void successLabelsAreNeverGivenToFailedOrUnvalidatedExecutions() {
        for (var line : report.corpus()) {
            String exec = line.experienceId().substring(0, line.experienceId().indexOf('/'));
            var expected = fixture.execution(exec);
            assertEquals(expected.expectedLabel(), line.label(), line.experienceId());
            if (!line.label().equals("SUCCESS")) {
                assertEquals("unavailable", line.cost(), line.experienceId());
            }
        }
        Set<String> notSuccess = report.corpus().stream().filter(l -> !l.label().equals("SUCCESS"))
                .map(l -> l.experienceId().substring(0, l.experienceId().indexOf('/'))).collect(Collectors.toSet());
        assertEquals(Set.of("a-tokens", "a-export", "a-import", "b-webhook", "b-backup"), notSuccess);
    }

    @Test
    void failedExperiencesCanBeRelevantButStayLabelledFailure() {
        var q11 = report.queries().stream().filter(q -> q.query().id().equals("Q11")).findFirst().orElseThrow();
        var top = q11.ranked().get(0);
        assertTrue(top.relevant());
        assertEquals("FAILURE", top.label());
        assertEquals("FAILED", top.attemptResult());
    }

    @Test
    void staleRevisionIsRecalledOnlyAtItsLatestRevision() {
        var q06 = report.queries().stream().filter(q -> q.query().id().equals("Q06")).findFirst().orElseThrow();
        var hit = q06.ranked().stream().filter(h -> h.experienceId().equals("a-yaml/a1")).findFirst().orElseThrow();
        assertEquals(2, hit.revision());
        for (var q : report.queries()) {
            q.ranked().stream().filter(h -> !h.experienceId().equals("a-yaml/a1"))
                    .forEach(h -> assertEquals(1, h.revision(), h.experienceId()));
        }
    }

    @Test
    void chainUsageAndCostsAreIndependentlyRecomputedFromTheFixture() {
        // recompute every chain from the raw attempt rows, without the library's calculators
        for (var exec : fixture.executions()) {
            var expected = fixture.expectations().chains().get(exec.id());
            boolean validated = exec.outcome() == ForgeFixture.OutcomeKind.ACCEPTED_VALIDATED
                    && exec.expectedAcceptance().equals("VALIDATED_ACCEPTED");
            long in = 0;
            long out = 0;
            for (var a : fixture.attemptsOf(exec.id())) {
                // a validated acceptance ends the chain at the accepted attempt; otherwise every attempt counts
                if (!validated || a.ordinal() <= exec.outcomeAttempt()) {
                    in += a.inputTokens();
                    out += a.outputTokens();
                }
            }
            assertEquals(expected.inputTokens(), in, exec.id());
            assertEquals(expected.outputTokens(), out, exec.id());
            if (validated) {
                BigDecimal cost = BigDecimal.valueOf(in).multiply(fixture.price().input())
                        .add(BigDecimal.valueOf(out).multiply(fixture.price().output()))
                        .divide(BigDecimal.valueOf(fixture.price().unitSize()));
                assertEquals(0, expected.cost().orElseThrow().compareTo(cost), exec.id() + " " + cost);
            } else {
                assertTrue(expected.cost().isEmpty(), exec.id() + " has no validated acceptance, so no cost");
            }
        }
    }

    @Test
    void emptyRelevanceAndBlankQueriesAreOutsideEveryDenominator() {
        assertEquals(14, report.queries().size());
        assertEquals(12, report.scoredQueries().size());
        var blank = report.queries().stream().filter(q -> q.query().id().equals("Q14")).findFirst().orElseThrow();
        assertTrue(blank.ranked().isEmpty());
        assertTrue(blank.precision().isEmpty());
        var none = report.queries().stream().filter(q -> q.query().id().equals("Q12")).findFirst().orElseThrow();
        assertTrue(none.recall().isEmpty());
        assertTrue(report.render().contains("Aggregates over 12 scored queries (2 excluded"));
    }

    @Test
    void qualityValuesAreStructurallySoundButNotFrozen() {
        for (var q : report.scoredQueries()) {
            for (int k : List.of(1, 3, 5)) {
                assertTrue(q.precision().get(k) >= 0 && q.precision().get(k) <= 1);
                assertTrue(q.recall().get(k) >= 0 && q.recall().get(k) <= 1);
                assertTrue(q.hit().get(k) == 0.0 || q.hit().get(k) == 1.0);
            }
            assertTrue(q.reciprocalRank() >= 0 && q.reciprocalRank() <= 1);
        }
        // the report names itself exploratory and states that quality does not decide
        assertTrue(report.render().contains("EXPLORATORY"));
        assertTrue(report.render().contains("exploratory quality did not influence the decision"));
    }

    @Test
    void reportShowsPerQueryRankingsMissingRelevantAndWorstCases() {
        String text = report.render();
        for (var q : fixture.queries()) {
            assertTrue(text.contains("Query " + q.id()), q.id());
        }
        assertTrue(text.contains("Missing relevant (outside top 5)"));
        assertTrue(text.contains("Worst cases"));
        assertTrue(text.contains("Hard correctness gates"));
        assertTrue(text.contains("Exploratory retrieval quality"));
    }

    @Test
    void repeatedFixedInputRunsProduceIdenticalContentAndTimingIsSeparate(@TempDir Path other) {
        ForgeEvaluationReport again = ForgeExperienceEvaluator.evaluate(ForgeFixture.load(), other);
        assertEquals(report.render(), again.render());
        assertEquals(report.gates(), again.gates());
        assertFalse(report.render().contains("Elapsed timing"));
        assertTrue(report.renderTiming().contains("nondeterministic"));
        assertFalse(report.elapsedNanos().isEmpty());
    }

    @Test
    void commandPrintsTheReportThenTimingAndExitsZero() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = ForgeExperienceEvaluationMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit);
        assertTrue(text.contains("READY_FOR_HOST_ADAPTER_EXPERIMENT"));
        assertTrue(text.indexOf("== Decision ==") < text.indexOf("== Elapsed timing"));
    }
}
