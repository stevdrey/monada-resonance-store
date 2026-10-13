package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.evaluation.execution.ForgeEvaluationReport.Decision;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gates must be able to fail: each test breaks one judged expectation or forges one violation and checks that
 * exactly the matching gate reports it and that the decision turns to GAPS_REQUIRE_FOLLOW_UP.
 */
class ForgeGatesTest {
    @TempDir Path work;
    private final ForgeFixture fixture = ForgeFixture.load();

    private ForgeFixture with(List<ForgeFixture.Execution> executions, ForgeFixture.Expectations expectations) {
        return new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(), fixture.price(),
                fixture.recallLimit(), fixture.ks(), executions, fixture.attempts(), fixture.queries(), expectations);
    }

    private static ForgeGate gate(ForgeEvaluationReport report, String id) {
        return report.gates().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertOnlyFailed(ForgeEvaluationReport report, String id) {
        assertEquals(List.of(id), report.failedGates(), report.render());
        assertEquals(Decision.GAPS_REQUIRE_FOLLOW_UP, report.decision());
        assertFalse(report.allGatesPassed());
    }

    @Test
    void aWrongHandVerifiedCostFailsTheAccountingGate() {
        Map<String, ForgeFixture.ExpectedChain> chains = new HashMap<>(fixture.expectations().chains());
        chains.put("a-inv1", new ForgeFixture.ExpectedChain(14000, 1400, new BigDecimal("0.041")));
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(chains, fixture.expectations().unavailableCosts(),
                fixture.expectations().successOrdinals(), fixture.expectations().comparisons());
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work);
        assertOnlyFailed(report, ForgeGate.RESOURCE_ACCOUNTING);
        assertTrue(gate(report, ForgeGate.RESOURCE_ACCOUNTING).details().stream()
                .anyMatch(d -> d.contains("a-inv1") && d.contains("0.041")));
    }

    @Test
    void aCostJudgedUnavailableButComputedFailsTheAccountingGate() {
        Set<String> unavailable = new java.util.HashSet<>(fixture.expectations().unavailableCosts());
        unavailable.add("a-inv1");
        Map<String, ForgeFixture.ExpectedChain> chains = new HashMap<>(fixture.expectations().chains());
        chains.remove("a-inv1");
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(chains, unavailable,
                fixture.expectations().successOrdinals(), fixture.expectations().comparisons());
        assertOnlyFailed(ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work),
                ForgeGate.RESOURCE_ACCOUNTING);
    }

    @Test
    void aSuccessLabelJudgedDifferentlyFailsTheSuccessGate() {
        List<ForgeFixture.Execution> executions = new ArrayList<>();
        for (ForgeFixture.Execution x : fixture.executions()) {
            executions.add(!x.id().equals("a-tokens") ? x : new ForgeFixture.Execution(x.id(), x.scopeKey(),
                    x.task(), x.summary(), x.sourceRevision(), x.contextFingerprint(), x.constraintsFingerprint(),
                    x.mandatory(), x.outcome(), x.outcomeAttempt(), "SUCCESS", "VALIDATED_ACCEPTED"));
        }
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(with(executions, fixture.expectations()),
                work);
        // a-tokens has no TESTS evidence: claiming SUCCESS for it is exactly the false success the gate prevents
        assertTrue(report.failedGates().contains(ForgeGate.NO_INVALID_SUCCESS), report.render());
        assertEquals(Decision.GAPS_REQUIRE_FOLLOW_UP, report.decision());
        assertTrue(gate(report, ForgeGate.NO_INVALID_SUCCESS).details().stream()
                .anyMatch(d -> d.contains("a-tokens") && d.contains("UNLABELED")));
    }

    @Test
    void aWrongSuccessOrdinalFailsTheSuccessGate() {
        Map<String, Integer> ordinals = new HashMap<>(fixture.expectations().successOrdinals());
        ordinals.put("a-inv2", 1);
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(fixture.expectations().chains(),
                fixture.expectations().unavailableCosts(), ordinals, fixture.expectations().comparisons());
        assertOnlyFailed(ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work),
                ForgeGate.NO_INVALID_SUCCESS);
    }

    @Test
    void aWrongComparisonConclusionOrMismatchFailsTheComparisonGate() {
        List<ForgeFixture.ExpectedComparison> comparisons = new ArrayList<>(fixture.expectations().comparisons());
        ForgeFixture.ExpectedComparison first = comparisons.get(0);
        comparisons.set(0, new ForgeFixture.ExpectedComparison(first.leftScope(), first.left(), first.rightScope(),
                first.right(), "RIGHT_DOMINATES", first.mismatchFields()));
        ForgeFixture.ExpectedComparison second = comparisons.get(1);
        comparisons.set(1, new ForgeFixture.ExpectedComparison(second.leftScope(), second.left(),
                second.rightScope(), second.right(), second.conclusion(), Set.of("TASK")));
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(fixture.expectations().chains(),
                fixture.expectations().unavailableCosts(), fixture.expectations().successOrdinals(), comparisons);
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work);
        assertOnlyFailed(report, ForgeGate.COMPARISON);
        assertEquals(2, gate(report, ForgeGate.COMPARISON).details().stream()
                .filter(d -> d.startsWith("VIOLATION")).count());
    }

    @Test
    void aHitOfAnotherProjectIsDetectedAsLeakage(@TempDir Path root) {
        // Take genuine beta hits and present them as the answer to an alpha query: the isolation check must flag them.
        List<ExperienceHit> betaHits;
        var betaScope = ForgeFixtureRecorder.scope(fixture, "beta");
        try (ExecutionMemory memory = ExecutionMemory.open(root, betaScope)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "beta");
            memory.rebuildProjection(betaScope);
            betaHits = memory.recall(betaScope, "Fix null pointer in invoice totals calculation", 5).hits();
        }
        assertFalse(betaHits.isEmpty());
        ForgeFixture.Query alphaQuery = fixture.queries().stream().filter(q -> q.id().equals("Q03")).findFirst()
                .orElseThrow();
        List<String> violations = ForgeExperienceEvaluator.leakageViolations(fixture, alphaQuery, betaHits);
        assertEquals(2 * betaHits.size(), violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("foreign-scope hit project-beta"));
        // the same hits answer a beta query without any violation
        ForgeFixture.Query betaQuery = fixture.queries().stream().filter(q -> q.id().equals("Q09")).findFirst()
                .orElseThrow();
        assertEquals(List.of(), ForgeExperienceEvaluator.leakageViolations(fixture, betaQuery, betaHits));
    }

    @Test
    void aBlankQueryThatReturnsHitsIsAViolation(@TempDir Path root) {
        var scope = ForgeFixtureRecorder.scope(fixture, "alpha");
        List<ExperienceHit> hits;
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(scope);
            hits = memory.recall(scope, "invoice totals", 3).hits();
        }
        ForgeFixture.Query blank = fixture.queries().stream().filter(q -> q.id().equals("Q14")).findFirst()
                .orElseThrow();
        assertFalse(ForgeExperienceEvaluator.leakageViolations(fixture, blank, hits).isEmpty());
    }

    @Test
    void recordingTheSameFixtureTwiceIsIdempotentAndForeignEventsAreRejected(@TempDir Path root) {
        var alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            long before = memory.history(alpha).highWatermark();
            assertTrue(ForgeFixtureRecorder.recordScope(memory, fixture, "alpha").stream()
                    .allMatch(r -> r.status() == com.monada.api.execution.RecordResult.Status.IDEMPOTENT));
            assertEquals(before, memory.history(alpha).highWatermark());
            // a beta event must not be accepted into the alpha scope
            var foreign = ForgeFixtureRecorder.events(fixture, "beta").get(0);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> memory.record(foreign));
        }
    }

    @Test
    void emptyRelevanceSemanticsAreExplicit() {
        ForgeFixture.Query none = fixture.queries().stream().filter(q -> q.id().equals("Q12")).findFirst()
                .orElseThrow();
        ForgeQueryResult result = ForgeQueryResult.of(none, "CURRENT", List.of(), fixture.ks());
        assertTrue(result.precision().isEmpty() && result.recall().isEmpty() && result.hit().isEmpty());
        assertEquals(0.0, result.reciprocalRank());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void missingRelevantExperiencesAreDiagnosed() {
        ForgeFixture.Query q = fixture.queries().stream().filter(x -> x.id().equals("Q01")).findFirst().orElseThrow();
        List<ForgeEvaluationReport.RankedHit> ranked = new ArrayList<>();
        String[] ids = {"a-tz/a1", "a-export/a1", "a-inv1/a1", "a-inv2/a1", "a-inv3/a1", "a-page/a2"};
        for (int i = 0; i < ids.length; i++) {
            ranked.add(new ForgeEvaluationReport.RankedHit(i + 1, ids[i], 0.5 - i * 0.1, "COMPLETED", "SUCCESS", 1,
                    q.relevant().contains(ids[i])));
        }
        ForgeQueryResult result = ForgeQueryResult.of(q, "CURRENT", ranked, fixture.ks());
        assertEquals(0.0, result.hit().get(5));
        assertEquals(1.0 / 6.0, result.reciprocalRank(), 1e-12);
        assertEquals("ranked #6, outside the top 5", result.missing().get("a-page/a2"));
        assertEquals("not retrieved within the recall limit of 6 hits", result.missing().get("a-page/a1"));
    }
}
