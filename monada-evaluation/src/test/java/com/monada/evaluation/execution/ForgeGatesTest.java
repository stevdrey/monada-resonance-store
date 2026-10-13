package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ProjectionStatus;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.TaskId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.execution.ForgeEvaluationReport.Decision;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        chains.put("a-inv1", new ForgeFixture.ExpectedChain(14000, 1400, Optional.of(new BigDecimal("0.041"))));
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(chains, fixture.expectations().successOrdinals(),
                fixture.expectations().comparisons());
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work);
        assertOnlyFailed(report, ForgeGate.RESOURCE_ACCOUNTING);
        assertTrue(gate(report, ForgeGate.RESOURCE_ACCOUNTING).details().stream()
                .anyMatch(d -> d.contains("a-inv1") && d.contains("0.041")));
    }

    @Test
    void aCostJudgedUnavailableButComputedFailsTheAccountingGate() {
        Map<String, ForgeFixture.ExpectedChain> chains = new HashMap<>(fixture.expectations().chains());
        chains.put("a-inv1", new ForgeFixture.ExpectedChain(14000, 1400, Optional.empty()));
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(chains, fixture.expectations().successOrdinals(),
                fixture.expectations().comparisons());
        assertOnlyFailed(ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work),
                ForgeGate.RESOURCE_ACCOUNTING);
    }

    @Test
    void wrongUsageOfAnExecutionWithoutCostIsStillCaught() {
        // a-export has no validated acceptance, so its cost is unavailable; its token totals must still be right
        Map<String, ForgeFixture.ExpectedChain> chains = new HashMap<>(fixture.expectations().chains());
        assertEquals(21000, chains.get("a-export").inputTokens());
        assertTrue(chains.get("a-export").cost().isEmpty());
        chains.put("a-export", new ForgeFixture.ExpectedChain(10000, 1200, Optional.empty())); // first attempt only
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(chains, fixture.expectations().successOrdinals(),
                fixture.expectations().comparisons());
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(with(fixture.executions(), e), work);
        assertOnlyFailed(report, ForgeGate.RESOURCE_ACCOUNTING);
        assertTrue(gate(report, ForgeGate.RESOURCE_ACCOUNTING).details().stream()
                .anyMatch(d -> d.contains("a-export") && d.contains("21000/2500") && d.contains("10000/1200")),
                gate(report, ForgeGate.RESOURCE_ACCOUNTING).details().toString());
    }

    @Test
    void everyExecutionHasHandVerifiedUsage() {
        assertEquals(fixture.executions().size(), fixture.expectations().chains().size());
        assertEquals(5, fixture.expectations().unavailableCosts().size());
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
        ForgeFixture.Expectations e = new ForgeFixture.Expectations(fixture.expectations().chains(), ordinals,
                fixture.expectations().comparisons());
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
                fixture.expectations().successOrdinals(), comparisons);
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

    // --- G3: every exported reference is verified -----------------------------------------------------

    private List<ExperienceSample> alphaSamples(Path root, ExecutionMemory[] holder) {
        ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        ExecutionMemory memory = ExecutionMemory.open(root, alpha);
        holder[0] = memory;
        ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
        memory.rebuildProjection(alpha);
        return memory.exportSamples(ExportRequest.firstPage(alpha, 50)).samples();
    }

    private static ExperienceSample sampleOf(List<ExperienceSample> samples, String execution) {
        return samples.stream().filter(x -> x.executionId().value().equals(execution)).findFirst().orElseThrow();
    }

    private static ExperienceSample withOutcome(ExperienceSample x, Optional<ExperienceSample.OutcomeFact> outcome) {
        return new ExperienceSample(x.execution(), x.taskSummary(), x.provenance(), x.policy(), x.requiredGates(),
                outcome, x.acceptance(), x.label(), x.successOrdinal(), x.group(), x.eligibility(), x.gates(),
                x.attempts(), x.revisions(), x.firstSequence(), x.lastSequence(), x.diagnostics());
    }

    private static ExperienceSample withRevisions(ExperienceSample x, List<ExperienceSample.Revision> revisions) {
        return new ExperienceSample(x.execution(), x.taskSummary(), x.provenance(), x.policy(), x.requiredGates(),
                x.outcome(), x.acceptance(), x.label(), x.successOrdinal(), x.group(), x.eligibility(), x.gates(),
                x.attempts(), revisions, x.firstSequence(), x.lastSequence(), x.diagnostics());
    }

    private static ExperienceRef ref(ExperienceRef like, String eventId, int revision) {
        return new ExperienceRef(like.scope(), like.task(), like.execution(), like.attempt(), EventId.of(eventId),
                revision);
    }

    @Test
    void exportedReferencesCoverTheOutcomeAndTheRevisionHistory(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            ExperienceSample yaml = sampleOf(alphaSamples(root, memory), "a-yaml");
            Map<String, Long> origins = new java.util.TreeMap<>();
            ForgeExperienceEvaluator.exportedReferences(yaml).forEach(r -> origins.merge(r.origin(), 1L, Long::sum));
            assertEquals(1L, origins.get("outcome"));
            assertEquals(1L, origins.get("revision.effective"));
            assertEquals(1L, origins.get("revision.superseded"));
            assertTrue(origins.containsKey("gate"));
            assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(memory[0],
                    ForgeFixtureRecorder.scope(fixture, "alpha"), yaml));
        } finally {
            memory[0].close();
        }
    }

    @Test
    void anOutcomeReferenceThatDoesNotResolveIsAViolation(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            ExperienceSample yaml = sampleOf(alphaSamples(root, memory), "a-yaml");
            ExperienceSample.OutcomeFact real = yaml.outcome().orElseThrow();
            ExperienceSample forged = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    ref(real.ref(), "no-such-event", 1), real.sequence(), real.outcome())));
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(memory[0],
                    ForgeFixtureRecorder.scope(fixture, "alpha"), forged);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("outcome ref no-such-event") && violations.get(0).contains("does not resolve"));
        } finally {
            memory[0].close();
        }
    }

    @Test
    void anOutcomeReferenceThatAddressesAnotherKindOfEventOrExecutionIsAViolation(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            List<ExperienceSample> samples = alphaSamples(root, memory);
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            ExperienceSample.OutcomeFact real = yaml.outcome().orElseThrow();
            // it resolves, belongs to the execution, but is the start of the execution, not an outcome
            ExperienceSample notAnOutcome = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    yaml.execution(), real.sequence(), real.outcome())));
            List<String> v1 = ForgeExperienceEvaluator.referenceViolations(memory[0], alpha, notAnOutcome);
            assertEquals(1, v1.size(), v1.toString());
            assertTrue(v1.get(0).contains("does not address an outcome"));
            // it resolves, but to the outcome of a different execution
            ExperienceSample.OutcomeFact other = sampleOf(samples, "a-inv2").outcome().orElseThrow();
            ExperienceSample foreign = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    other.ref(), real.sequence(), real.outcome())));
            List<String> v2 = ForgeExperienceEvaluator.referenceViolations(memory[0], alpha, foreign);
            assertEquals(1, v2.size(), v2.toString());
            assertTrue(v2.get(0).contains("resolves to another execution"));
        } finally {
            memory[0].close();
        }
    }

    @Test
    void brokenRevisionProvenanceIsAViolation(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            ExperienceSample yaml = sampleOf(alphaSamples(root, memory), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample.Revision real = yaml.revisions().get(0);
            // a superseded revision that is not older than the effective one
            ExperienceSample notOlder = withRevisions(yaml, List.of(new ExperienceSample.Revision(real.effective(),
                    real.sequence(), real.justification(), List.of(real.effective()))));
            assertTrue(ForgeExperienceEvaluator.referenceViolations(memory[0], alpha, notOlder).stream()
                    .anyMatch(v -> v.contains("is not older than the effective")));
            // a superseded revision that does not exist, and an effective revision that does not exist
            ExperienceSample dangling = withRevisions(yaml, List.of(new ExperienceSample.Revision(
                    ref(real.effective(), "ghost", 2), real.sequence(), real.justification(),
                    List.of(ref(real.effective(), "ghost-original", 1)))));
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(memory[0], alpha, dangling);
            assertTrue(violations.stream().anyMatch(v -> v.contains("revision.effective ref ghost")), violations.toString());
            assertTrue(violations.stream().anyMatch(v -> v.contains("revision.superseded ref ghost-original")),
                    violations.toString());
        } finally {
            memory[0].close();
        }
    }

    // --- G4: the second rebuild is verified -----------------------------------------------------------

    @Test
    void aProjectionThatIsNotCurrentOrNotFullyCoveredIsAViolation() {
        assertEquals(List.of(), ForgeExperienceEvaluator.projectionViolations("x",
                new ProjectionStatus(ProjectionStatus.State.CURRENT, 70, 70, 1, List.of())));
        for (ProjectionStatus.State state : List.of(ProjectionStatus.State.STALE, ProjectionStatus.State.MISSING,
                ProjectionStatus.State.INCOMPATIBLE)) {
            List<String> v = ForgeExperienceEvaluator.projectionViolations("second rebuild",
                    new ProjectionStatus(state, 70, 70, 1, List.of("why")));
            assertEquals(1, v.size(), state.toString());
            assertTrue(v.get(0).contains("second rebuild") && v.get(0).contains(state.name()));
        }
        List<String> shortCover = ForgeExperienceEvaluator.projectionViolations("second rebuild",
                new ProjectionStatus(ProjectionStatus.State.CURRENT, 60, 70, 1, List.of()));
        assertEquals(1, shortCover.size());
        assertTrue(shortCover.get(0).contains("covers 60 of 70"));
        // stale AND short is reported twice, so neither symptom hides the other
        assertEquals(2, ForgeExperienceEvaluator.projectionViolations("x",
                new ProjectionStatus(ProjectionStatus.State.STALE, 60, 70, 1, List.of())).size());
    }

    // --- report text does not depend on the platform ---------------------------------------------------

    @Test
    void reportTextNeverUsesThePlatformLineSeparator() {
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(fixture, work);
        assertFalse(report.render().contains("\r"));
        assertFalse(report.renderTiming().contains("\r"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ForgeEvaluationReport.fmt("line%n"));
        assertEquals("a 1\n", ForgeEvaluationReport.fmt("a %d\n", 1));
    }

    // --- G3: every coordinate of a reference, the declared chronology and paged exports ---------------

    private static ExperienceRef withCoordinates(ExperienceRef r, ScopeId scope, TaskId task, ExecutionId execution,
                                                 Optional<AttemptId> attempt) {
        return new ExperienceRef(scope, task, execution, attempt, r.eventId(), r.revision());
    }

    @Test
    void aReferenceWithAnyWrongCoordinateIsAViolationEvenWhenEventIdAndRevisionResolve(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            ExperienceSample yaml = sampleOf(alphaSamples(root, memory), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceRef real = yaml.attempts().get(0).start();
            var entry = memory[0].loadEvent(alpha, real.eventId(), real.revision()).orElseThrow();
            assertEquals(List.of(), ForgeExperienceEvaluator.coordinateViolations("ok", alpha, real, entry));

            ExperienceRef wrongScope = withCoordinates(real, ScopeId.of("project-beta"), real.task(), real.execution(),
                    real.attempt());
            ExperienceRef wrongTask = withCoordinates(real, real.scope(), TaskId.of("other-task"), real.execution(),
                    real.attempt());
            ExperienceRef wrongExecution = withCoordinates(real, real.scope(), real.task(), ExecutionId.of("a-inv2"),
                    real.attempt());
            ExperienceRef wrongAttempt = withCoordinates(real, real.scope(), real.task(), real.execution(),
                    Optional.of(AttemptId.of("a9")));
            ExperienceRef noAttempt = withCoordinates(real, real.scope(), real.task(), real.execution(),
                    Optional.empty());
            ExperienceRef wrongRevision = new ExperienceRef(real.scope(), real.task(), real.execution(), real.attempt(),
                    real.eventId(), 2);
            for (var forged : Map.of("scope", wrongScope, "task", wrongTask, "execution", wrongExecution,
                    "attempt", wrongAttempt, "no attempt", noAttempt, "revision", wrongRevision).entrySet()) {
                List<String> v = ForgeExperienceEvaluator.coordinateViolations("forged", alpha, forged.getValue(),
                        entry);
                assertFalse(v.isEmpty(), forged.getKey());
            }
            // the same checks reach the sample level: a sample whose stage ref names a different task
            ExperienceSample.SampleAttempt first = yaml.attempts().get(0);
            ExperienceSample.SampleStage stage = first.stages().get(0);
            ExperienceSample.SampleStage forgedStage = new ExperienceSample.SampleStage(
                    withCoordinates(stage.ref(), stage.ref().scope(), TaskId.of("other-task"), stage.ref().execution(),
                            stage.ref().attempt()), stage.sequence(), stage.stage(), stage.route(), stage.startedAt(),
                    stage.endedAt(), stage.usageDeclaration(), stage.justification(), stage.usage(), stage.artifacts());
            ExperienceSample forged = withAttempts(yaml, List.of(withStages(first, List.of(forgedStage))));
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(memory[0], alpha, forged);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("task other-task"), violations.get(0));
        } finally {
            memory[0].close();
        }
    }

    private static ExperienceSample withAttempts(ExperienceSample x, List<ExperienceSample.SampleAttempt> attempts) {
        return new ExperienceSample(x.execution(), x.taskSummary(), x.provenance(), x.policy(), x.requiredGates(),
                x.outcome(), x.acceptance(), x.label(), x.successOrdinal(), x.group(), x.eligibility(), x.gates(),
                attempts, x.revisions(), x.firstSequence(), x.lastSequence(), x.diagnostics());
    }

    private static ExperienceSample.SampleAttempt withStages(ExperienceSample.SampleAttempt a,
                                                             List<ExperienceSample.SampleStage> stages) {
        return new ExperienceSample.SampleAttempt(a.id(), a.start(), a.ordinal(), a.reason(), a.previousAttempt(),
                a.accepted(), a.result(), a.finish(), a.solutionSummary(), a.lessonSummary(), stages, a.evidence());
    }

    @Test
    void theRepairAttemptIsPersistedAtItsDeclaredStartNotAtMidnight(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            List<ExperienceSample> samples = alphaSamples(root, memory);
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample inv1 = sampleOf(samples, "a-inv1");
            assertEquals(List.of(), ForgeExperienceEvaluator.chronologyViolations(fixture, memory[0], alpha, inv1));
            ExperienceRef start = inv1.attempts().get(1).start(); // a-inv1/a2, declared to start 30 s later
            var envelope = memory[0].loadEvent(alpha, start.eventId(), start.revision()).orElseThrow().event()
                    .envelope();
            assertEquals(ForgeFixtureRecorder.T0.plusSeconds(30), envelope.occurredAt());
            assertEquals(ForgeFixtureRecorder.T0.plusSeconds(30), envelope.recordedAt());

            // a fixture that declares another start for that attempt no longer matches what was persisted
            List<ForgeFixture.Attempt> attempts = new ArrayList<>();
            for (ForgeFixture.Attempt a : fixture.attempts()) {
                attempts.add(!a.experienceId().equals("a-inv1/a2") ? a : new ForgeFixture.Attempt(a.execution(),
                        a.ordinal(), a.reason(), a.result(), a.solution(), a.lesson(), a.inputTokens(),
                        a.outputTokens(), 31, a.endSeconds(), a.evidence(), a.revisedSolution(), a.revisedLesson()));
            }
            ForgeFixture shifted = new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(),
                    fixture.price(), fixture.recallLimit(), fixture.ks(), fixture.executions(), attempts,
                    fixture.queries(), fixture.expectations());
            List<String> violations = ForgeExperienceEvaluator.chronologyViolations(shifted, memory[0], alpha, inv1);
            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.contains("a-inv1/a2") && v.contains("attempt start")),
                    violations.toString());
        } finally {
            memory[0].close();
        }
    }

    @Test
    void anExportedStageWithCorruptedUsageIsCaughtEvenWhenTheLedgerViewIsCorrect(@TempDir Path root) {
        ExecutionMemory[] memory = new ExecutionMemory[1];
        try {
            ExperienceSample inv1 = sampleOf(alphaSamples(root, memory), "a-inv1");
            ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get("a-inv1");
            assertEquals(List.of(), ForgeExperienceEvaluator.exportedUsageViolations(inv1, expected, 2));

            ExperienceSample.SampleAttempt second = inv1.attempts().get(1);
            ExperienceSample.SampleStage stage = second.stages().get(0);
            List<ExperienceSample.SampleUsage> usage = new ArrayList<>();
            for (ExperienceSample.SampleUsage u : stage.usage()) {
                usage.add(u.kind() == com.monada.core.execution.UsageKind.OUTPUT_TOKENS
                        ? new ExperienceSample.SampleUsage(u.kind(), java.util.OptionalLong.of(1), u.state(), u.source())
                        : u);
            }
            ExperienceSample.SampleStage corrupted = new ExperienceSample.SampleStage(stage.ref(), stage.sequence(),
                    stage.stage(), stage.route(), stage.startedAt(), stage.endedAt(), stage.usageDeclaration(),
                    stage.justification(), usage, stage.artifacts());
            ExperienceSample forged = withAttempts(inv1, List.of(inv1.attempts().get(0),
                    withStages(second, List.of(corrupted))));
            List<String> violations = ForgeExperienceEvaluator.exportedUsageViolations(forged, expected, 2);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("exported chain usage 14000/801 but hand-verified 14000/1400"),
                    violations.get(0));

            // a dropped stage and a counter that reports a number nobody recorded are caught too
            ExperienceSample dropped = withAttempts(inv1, List.of(inv1.attempts().get(0), withStages(second, List.of())));
            assertTrue(ForgeExperienceEvaluator.exportedUsageViolations(dropped, expected, 2).stream()
                    .anyMatch(v -> v.contains("chain stages")));
            List<ExperienceSample.SampleUsage> invented = new ArrayList<>(stage.usage());
            invented.add(new ExperienceSample.SampleUsage(com.monada.core.execution.UsageKind.REQUESTS,
                    java.util.OptionalLong.of(0), ExperienceSample.UsageState.REPORTED, Optional.of("x")));
            ExperienceSample.SampleStage zero = new ExperienceSample.SampleStage(stage.ref(), stage.sequence(),
                    stage.stage(), stage.route(), stage.startedAt(), stage.endedAt(), stage.usageDeclaration(),
                    stage.justification(), invented, stage.artifacts());
            assertTrue(ForgeExperienceEvaluator.exportedUsageViolations(withAttempts(inv1,
                    List.of(inv1.attempts().get(0), withStages(second, List.of(zero)))), expected, 2).stream()
                    .anyMatch(v -> v.contains("REQUESTS") && v.contains("never recorded")));
        } finally {
            memory[0].close();
        }
    }

    @Test
    void theExportMustTraverseContinuationPagesAndEqualTheUnpagedExport(@TempDir Path root) {
        var alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(alpha);
            int executions = fixture.executionsOf("alpha").size();
            assertEquals(9, executions);
            PriceBook prices = PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture)));
            ExperienceExportReport paged = ExperienceExportReport.build(memory, ExportRequest.first(alpha,
                    com.monada.api.execution.ExportCutoff.latest(), 4, com.monada.api.execution.ExportView.ADAPTER_READY,
                    com.monada.api.execution.ExportEvidencePolicy.executionPolicyOnly()), prices, Optional.empty());
            assertEquals(3, paged.pages().size());
            assertEquals(List.of(), ForgeExperienceEvaluator.paginationViolations(memory, alpha, paged, 4, executions));

            // a single page (page size above the corpus) never exercises the resume cursor
            ExperienceExportReport single = ExperienceExportReport.build(memory, ExportRequest.firstPage(alpha, 50),
                    prices, Optional.empty());
            assertEquals(1, single.pages().size());
            List<String> v = ForgeExperienceEvaluator.paginationViolations(memory, alpha, single, 50, executions);
            assertTrue(v.stream().anyMatch(x -> x.contains("never traversed a continuation")), v.toString());

            // pages that lose or reorder samples are caught by the comparison with the unpaged export
            List<AnnotatedSample> reordered = new ArrayList<>(paged.samples());
            java.util.Collections.reverse(reordered);
            ExperienceExportReport forged = new ExperienceExportReport(alpha, paged.pages(), reordered,
                    paged.comparison(), paged.exportDigest());
            assertTrue(ForgeExperienceEvaluator.paginationViolations(memory, alpha, forged, 4, executions).stream()
                    .anyMatch(x -> x.contains("differs from the unpaged export")));
            ExperienceExportReport lost = new ExperienceExportReport(alpha, paged.pages(),
                    paged.samples().subList(0, 8), paged.comparison(), paged.exportDigest());
            assertFalse(ForgeExperienceEvaluator.paginationViolations(memory, alpha, lost, 4, executions).isEmpty());
        }
    }

    @Test
    void theEvaluationExportsEveryProjectInSeveralPages() {
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(fixture, work);
        assertTrue(gate(report, ForgeGate.EXACT_REFERENCES).details().stream()
                .anyMatch(d -> d.contains("alpha=3") && d.contains("beta=3")), report.render());
    }
}
