package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.execution.MultiAttemptEvaluationReport.AttemptLine;
import com.monada.evaluation.execution.MultiAttemptEvaluationReport.HitLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end multi-attempt evaluation (issue #121): every gate passes on the bundled fixture, the report keeps the
 * execution label, attempt result, validated acceptance and reference provenance apart, the gates fail on a wrong
 * judgment, and the version-1 evaluation of #102 still passes its seven gates.
 */
class MultiAttemptEvaluationTest {
    @TempDir
    static Path work;

    private static MultiAttemptEvaluationReport report;

    @BeforeAll
    static void evaluate() {
        report = MultiAttemptEvaluator.evaluate(MultiAttemptFixture.load(), work.resolve("bundled"));
    }

    @Test
    void everyGatePassesOnTheBundledFixture() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(MultiAttemptEvaluationReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(MultiAttemptEvaluationReport.Decision.ATTEMPT_JOINS_VERIFIED, report.decision());
    }

    @Test
    void reportsAttemptResultApartFromTheRawExecutionLabel() {
        AttemptLine failed = line(report.latest(), "m-repair/a1");
        assertEquals("FAILED", failed.attemptResult());
        assertFalse(failed.validatedAcceptedAttempt());
        assertEquals("SUCCESS", failed.label());
        assertEquals("VALIDATED_ACCEPTED", failed.acceptance());
        assertEquals("m-repair.a1.fin r1 @5", failed.finishRef());

        AttemptLine imported = line(report.latest(), "n-abandon/a2");
        assertTrue(imported.markedAccepted()); // the imported claim names it ...
        assertFalse(imported.validatedAcceptedAttempt()); // ... but it is not a validated accepted attempt

        AttemptLine unfinished = line(report.latest(), "m-unfinished/a2");
        assertEquals(MultiAttemptFixture.UNFINISHED, unfinished.attemptResult());
        assertEquals(AttemptJoin.NONE, unfinished.finishRef());
    }

    @Test
    void noFailedOrCancelledHitIsReportedAsTheAcceptedAttempt() {
        int failedInSuccess = 0;
        for (MultiAttemptEvaluationReport.QueryLine q : report.queries()) {
            for (HitLine h : q.hits()) {
                AttemptJoin.Row r = h.latest();
                if (!r.attemptResult().equals("COMPLETED")) {
                    assertFalse(r.validatedAcceptedAttempt(), q.id() + " " + r.experienceId());
                    if (r.executionLabel().equals("SUCCESS")) {
                        failedInSuccess++;
                    }
                }
            }
        }
        assertTrue(failedInSuccess > 0, "the failed-attempt-in-SUCCESS case must be exercised");
    }

    @Test
    void theCutoffShowsTheEarlierRevisionAndLeavesLaterHitsUnjoined() {
        AttemptLine latest = line(report.latest(), "m-correct/a1");
        AttemptLine atCutoff = line(report.atCutoff(), "m-correct/a1");
        assertEquals("FAILED", latest.attemptResult());
        assertEquals(2, latest.revision());
        assertEquals("COMPLETED", atCutoff.attemptResult());
        assertEquals(1, atCutoff.revision());
        assertEquals("UNLABELED", atCutoff.label());
        assertTrue(report.atCutoff().stream().noneMatch(a -> a.experienceId().startsWith("m-twin-ko/")
                || a.experienceId().equals("m-correct/a2")));
        Set<String> beyond = Set.of("m-correct/a1", "m-correct/a2", "m-twin-ko/a1");
        for (MultiAttemptEvaluationReport.QueryLine q : report.queries()) {
            for (HitLine h : q.hits()) {
                if (beyond.contains(h.latest().experienceId())) {
                    assertEquals("BEYOND_CUTOFF", h.cutoffStatus(), q.id() + " " + h.latest().experienceId());
                }
            }
        }
    }

    @Test
    void foreignProjectsNeverAppear() {
        for (MultiAttemptEvaluationReport.QueryLine q : report.queries()) {
            String prefix = q.scopeKey().equals("alpha") ? "m-" : "n-";
            q.hits().forEach(h -> assertTrue(h.latest().experienceId().startsWith(prefix),
                    q.id() + " " + h.latest().experienceId()));
        }
    }

    @Test
    void renderIsDeterministicAcrossFreshEvaluations() {
        MultiAttemptEvaluationReport again = MultiAttemptEvaluator.evaluate(MultiAttemptFixture.load(),
                work.resolve("again"));
        assertEquals(report.render(), again.render());
        assertFalse(report.render().contains("ms\n"), "timing must stay out of the deterministic report");
    }

    @Test
    void aWrongJudgmentFailsTheGates() {
        MultiAttemptFixture f = MultiAttemptFixture.load();
        // judge m-repair FAILURE (a reinterpretation of the execution label) and claim the cutoff already saw the
        // corrected m-correct a1 result
        List<MultiAttemptFixture.Execution> executions = new ArrayList<>();
        for (MultiAttemptFixture.Execution e : f.executions()) {
            executions.add(!e.id().equals("m-repair") ? e : new MultiAttemptFixture.Execution(e.id(), e.scopeKey(),
                    e.task(), e.summary(), e.sourceRevision(), e.contextFingerprint(), e.constraintsFingerprint(),
                    e.mandatory(), e.outcome(), e.outcomeAttempt(), "FAILURE", e.expectedAcceptance(),
                    e.expectedReasons()));
        }
        List<MultiAttemptFixture.CutoffAttempt> cutoff = f.cutoff().stream().map(c -> !c.experienceId()
                .equals("m-correct/a1") ? c : new MultiAttemptFixture.CutoffAttempt(c.execution(), c.ordinal(),
                "FAILED", 2, c.accepted(), c.label(), c.acceptance())).toList();
        MultiAttemptFixture wrong = new MultiAttemptFixture(f.version(), f.provenance(), f.scopeIds(), f.price(),
                f.recallLimit(), f.pageSize(), f.cutoffScope(), f.cutoffEvent(), executions, f.attempts(),
                f.queries(), cutoff);
        wrong.validate();
        MultiAttemptEvaluationReport failed = MultiAttemptEvaluator.evaluate(wrong, work.resolve("wrong"));
        assertEquals(List.of(MultiAttemptEvaluationReport.ATTEMPT_RESULT_JOIN,
                MultiAttemptEvaluationReport.EXPORT_ATTEMPTS, MultiAttemptEvaluationReport.CUTOFF),
                failed.failedGates());
        assertEquals(MultiAttemptEvaluationReport.Decision.GAPS_REQUIRE_FOLLOW_UP, failed.decision());
        assertTrue(failed.render().contains("VIOLATION: M01: m-repair/a1: execution label SUCCESS but judged FAILURE"),
                failed.render());
    }

    @Test
    void commandExitsZeroAndPrintsTheDecision() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = MultiAttemptEvaluationMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        assertTrue(out.contains("ATTEMPT_JOINS_VERIFIED"));
        assertTrue(out.indexOf("== Elapsed timing") > out.indexOf("== Decision =="));
    }

    @Test
    void theSevenForgeExperienceGatesStillPass() {
        ForgeEvaluationReport v1 = ForgeExperienceEvaluator.evaluate(ForgeFixture.load(), work.resolve("v1"));
        assertEquals(List.of(), v1.failedGates());
        assertEquals(ForgeEvaluationReport.expectedGateIds(),
                v1.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(7, v1.gates().size());
    }

    private static AttemptLine line(List<AttemptLine> lines, String experienceId) {
        return lines.stream().filter(a -> a.experienceId().equals(experienceId)).findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + experienceId));
    }
}
