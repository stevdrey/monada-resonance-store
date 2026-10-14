package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.monada.evaluation.execution.ForgeTestSupport.*;

import com.monada.api.execution.DerivedAcceptance;
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
import com.monada.core.execution.QualityDimension;
import com.monada.core.execution.ScopeId;
import com.monada.evaluation.execution.ForgeEvaluationReport.Decision;
import com.monada.evaluation.execution.ForgeTestSupport.ProjectStore;
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
 * G3 and the export checks around it: every reference, revision, payload, page, sequence bound and instant of an
 * exported sample is compared with the ledger and the fixture. Each test forges one violation from a real sample
 * and checks that the matching pure check reports it (and that the real sample passes).
 */
class ForgeReferenceChecksTest {
    @TempDir Path work;
    private final ForgeFixture fixture = ForgeFixture.load();


    @Test
    void exportedReferencesCoverTheOutcomeAndTheRevisionHistory(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            Map<String, Long> origins = new java.util.TreeMap<>();
            ForgeExperienceEvaluator.exportedReferences(yaml).forEach(r -> origins.merge(r.origin(), 1L, Long::sum));
            assertEquals(1L, origins.get("outcome"));
            assertEquals(1L, origins.get("revision.effective"));
            assertEquals(1L, origins.get("revision.superseded"));
            assertTrue(origins.containsKey("gate"));
            assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(store.memory(),
                    ForgeFixtureRecorder.scope(fixture, "alpha"), yaml));
        }
    }


    @Test
    void anOutcomeReferenceThatDoesNotResolveIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            ExperienceSample.OutcomeFact real = yaml.outcome().orElseThrow();
            ExperienceSample forged = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    ref(real.ref(), "no-such-event", 1), real.sequence(), real.outcome())));
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(store.memory(),
                    ForgeFixtureRecorder.scope(fixture, "alpha"), forged);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("outcome ref no-such-event") && violations.get(0).contains("does not resolve"));
        }
    }


    @Test
    void anOutcomeReferenceThatAddressesAnotherKindOfEventOrExecutionIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            ExperienceSample.OutcomeFact real = yaml.outcome().orElseThrow();
            // it resolves, belongs to the execution, but is the start of the execution, not an outcome
            ExperienceSample notAnOutcome = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    yaml.execution(), real.sequence(), real.outcome())));
            List<String> v1 = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, notAnOutcome);
            assertEquals(1, v1.size(), v1.toString());
            assertTrue(v1.get(0).contains("addresses a EXECUTION_STARTED event but must address OUTCOME_RECORDED"), v1.get(0));
            // it resolves, but to the outcome of a different execution
            ExperienceSample.OutcomeFact other = sampleOf(samples, "a-inv2").outcome().orElseThrow();
            ExperienceSample foreign = withOutcome(yaml, Optional.of(new ExperienceSample.OutcomeFact(
                    other.ref(), real.sequence(), real.outcome())));
            List<String> v2 = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, foreign);
            assertEquals(1, v2.size(), v2.toString());
            assertTrue(v2.get(0).contains("resolves to another execution"));
        }
    }


    @Test
    void brokenRevisionProvenanceIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample.Revision real = yaml.revisions().get(0);
            // a superseded revision that is not older than the effective one
            ExperienceSample notOlder = withRevisions(yaml, List.of(new ExperienceSample.Revision(real.effective(),
                    real.sequence(), real.justification(), List.of(real.effective()))));
            assertTrue(ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, notOlder).stream()
                    .anyMatch(v -> v.contains("is not older than the effective")));
            // a superseded revision that does not exist, and an effective revision that does not exist
            ExperienceSample dangling = withRevisions(yaml, List.of(new ExperienceSample.Revision(
                    ref(real.effective(), "ghost", 2), real.sequence(), real.justification(),
                    List.of(ref(real.effective(), "ghost-original", 1)))));
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, dangling);
            assertTrue(violations.stream().anyMatch(v -> v.contains("revision.effective ref ghost")), violations.toString());
            assertTrue(violations.stream().anyMatch(v -> v.contains("revision.superseded ref ghost-original")),
                    violations.toString());
        }
    }


    @Test
    void aReferenceWithAnyWrongCoordinateIsAViolationEvenWhenEventIdAndRevisionResolve(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceRef real = yaml.attempts().get(0).start();
            var entry = store.memory().loadEvent(alpha, real.eventId(), real.revision()).orElseThrow();
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
            List<String> violations = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, forged);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("task other-task"), violations.get(0));
        }
    }


    @Test
    void theRepairAttemptIsPersistedAtItsDeclaredStartNotAtMidnight(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample inv1 = sampleOf(samples, "a-inv1");
            assertEquals(List.of(), ForgeExperienceEvaluator.chronologyViolations(fixture, store.memory(), alpha, inv1));
            ExperienceRef start = inv1.attempts().get(1).start(); // a-inv1/a2, declared to start 30 s later
            var envelope = store.memory().loadEvent(alpha, start.eventId(), start.revision()).orElseThrow().event()
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
            List<String> violations = ForgeExperienceEvaluator.chronologyViolations(shifted, store.memory(), alpha, inv1);
            assertFalse(violations.isEmpty());
            assertTrue(violations.stream().anyMatch(v -> v.contains("a-inv1.a2.start") && v.contains("attempt.start") && v.contains("declared 2026-01-01T00:00:31Z")),
                    violations.toString());
        }
    }


    @Test
    void anExportedStageWithCorruptedUsageIsCaughtEvenWhenTheLedgerViewIsCorrect(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample inv1 = sampleOf(store.samples(), "a-inv1");
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


    @Test
    void aGenuineReferenceOfTheWrongKindOrAttemptIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample page = sampleOf(samples, "a-page"); // 3 attempts, gates TESTS and ARCHITECTURE
            assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, page));

            ExperienceSample.SampleAttempt a1 = page.attempts().get(0);
            ExperienceSample.SampleAttempt a2 = page.attempts().get(1);
            ExperienceSample.SampleAttempt a3 = page.attempts().get(2);
            ExperienceRef stage1 = a1.stages().get(0).ref();
            ExperienceRef finish1 = a1.finish().orElseThrow();

            // a stage that points at the same attempt's AttemptFinished
            expectViolation(store.memory(), alpha, withStageRef(page, 0, 0, finish1), "addresses a ATTEMPT_FINISHED event but must address STAGE_RECORDED");
            // an attempt start that points at another attempt's start
            expectViolation(store.memory(), alpha, withAttempt(page, 1, a -> withStart(a, a1.start())),
                    "belongs to attempt a1 but must belong to a2");
            // a finish that points at a start, and a start that points at a finish
            expectViolation(store.memory(), alpha, withAttempt(page, 0, a -> withFinish(a, a1.start())),
                    "addresses a ATTEMPT_STARTED event but must address ATTEMPT_FINISHED");
            expectViolation(store.memory(), alpha, withAttempt(page, 0, a -> withStart(a, finish1)),
                    "addresses a ATTEMPT_FINISHED event but must address ATTEMPT_STARTED");
            // a finish of another attempt, with another result
            expectViolation(store.memory(), alpha, withAttempt(page, 1, a -> withFinish(a, finish1)),
                    "belongs to attempt a1 but must belong to a2");
            // evidence that points at a stage
            expectViolation(store.memory(), alpha, withAttempt(page, 2, a -> withEvidenceRef(a, a3.stages().get(0).ref())),
                    "addresses a STAGE_RECORDED event but must address EVIDENCE_RECORDED");
            // a stage of another attempt, and the ordinal/result/name claimed by the sample must match the event
            expectViolation(store.memory(), alpha, withStageRef(page, 1, 0, stage1), "belongs to attempt a1 but must belong to a2");
            expectViolation(store.memory(), alpha, withAttempt(page, 0, a -> new ExperienceSample.SampleAttempt(a.id(),
                    a.start(), 2, a.reason(), a.previousAttempt(), a.accepted(), a.result(), a.finish(),
                    a.solutionSummary(), a.lessonSummary(), a.stages(), a.evidence())), "does not carry ordinal 2");
            // gates: pointing at a stage, at evidence of a non-accepted attempt, or at evidence lacking the dimension
            expectViolation(store.memory(), alpha, withGateRef(page, QualityDimension.ARCHITECTURE, a3.stages().get(0).ref()),
                    "addresses a STAGE_RECORDED event but must address EVIDENCE_RECORDED");
            expectViolation(store.memory(), alpha, withGateRef(page, QualityDimension.ARCHITECTURE,
                    a2.evidence().get(0).ref()), "belongs to attempt a2 but must belong to a3");
            // a gate of a dimension that the evidence it points at never observed (the fixture's a3 evidence holds
            // TESTS and ARCHITECTURE only)
            ExperienceSample.Gate invented = new ExperienceSample.Gate(QualityDimension.SECURITY, false,
                    ExperienceSample.GateState.PASS, Optional.empty(), Optional.of(a3.evidence().get(0).ref()),
                    java.util.OptionalLong.empty());
            List<ExperienceSample.Gate> gates = new ArrayList<>(page.gates());
            gates.add(invented);
            expectViolation(store.memory(), alpha, withGates(page, gates), "does not carry an observation of SECURITY");
        }
    }


    @Test
    void aRevisionMustCorrectAFactTheSampleExportsAndReplaceTheSameKind(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            ExperienceSample.Revision real = yaml.revisions().get(0);
            // a revision whose effective ref is not any fact the sample exports (here: no attempt coordinate)
            ExperienceRef start = yaml.attempts().get(0).start();
            ExperienceSample notExported = withRevisions(yaml, List.of(new ExperienceSample.Revision(
                    new ExperienceRef(start.scope(), start.task(), start.execution(), Optional.empty(),
                            real.effective().eventId(), 2), real.sequence(), real.justification(),
                    real.superseded())));
            assertTrue(ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, notExported).stream()
                    .anyMatch(v -> v.contains("corrects a fact the sample does not export")));
            // the first superseded revision must be the one the correction names
            ExperienceSample wrongChain = withRevisions(yaml, List.of(new ExperienceSample.Revision(real.effective(),
                    real.sequence(), real.justification(), List.of(ref(real.effective(), "a-yaml.a1.start", 1)))));
            assertFalse(ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, wrongChain).isEmpty());
        }
    }


    // --- #1 a recalled hit must be the ATTEMPT_FINISHED fact ----------------------------------------------

    @Test
    void aRecalledHitThatAddressesAnotherEventOfTheSameAttemptIsAViolation(@TempDir Path root) {
        ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(alpha);
            List<ExperienceHit> hits = memory.recall(alpha, "Fix null pointer in invoice totals", 10).hits();
            ExperienceHit real = hits.stream().filter(h -> h.ref().execution().value().equals("a-inv2")).findFirst()
                    .orElseThrow();
            assertEquals(List.of(), ForgeExperienceEvaluator.hitViolations(fixture, memory, alpha, "Q", real));

            // genuine events of the same attempt: its stage and its start, with a matching ref and event
            for (String suffix : List.of("a1.stage", "a1.start")) {
                var entry = memory.loadEvent(alpha, EventId.of("a-inv2." + suffix), 1).orElseThrow();
                ExperienceRef ref = new ExperienceRef(real.ref().scope(), real.ref().task(), real.ref().execution(),
                        real.ref().attempt(), entry.event().eventId(), 1);
                ExperienceHit forged = new ExperienceHit(ref, real.atomId(), real.similarity(), entry,
                        real.projectionVersion(), real.coveredSequence());
                List<String> v = ForgeExperienceEvaluator.hitViolations(fixture, memory, alpha, "Q", forged);
                assertTrue(v.stream().anyMatch(x -> x.contains("but must address ATTEMPT_FINISHED")), suffix + v);
            }

            // the finish of another attempt of the same execution is rejected by the attempt rule
            ScopeId probe = alpha;
            var other = memory.loadEvent(probe, EventId.of("a-inv1.a1.fin"), 1).orElseThrow();
            ExperienceRef foreignRef = new ExperienceRef(real.ref().scope(), real.ref().task(), real.ref().execution(),
                    real.ref().attempt(), other.event().eventId(), 1);
            ExperienceHit foreign = new ExperienceHit(foreignRef, real.atomId(), real.similarity(), other,
                    real.projectionVersion(), real.coveredSequence());
            assertFalse(ForgeExperienceEvaluator.hitViolations(fixture, memory, alpha, "Q", foreign).isEmpty());
        }
    }


    // --- #2 the exported outcome must be the one the ledger holds ------------------------------------------

    @Test
    void anExportedOutcomeThatDisagreesWithItsLedgerEventIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample.OutcomeFact real = yaml.outcome().orElseThrow();
            // accepted -> rejected, accepted-by-claim, and a displaced ledger sequence
            for (var forgedOutcome : List.of(
                    com.monada.core.execution.Outcome.rejected(com.monada.core.execution.OutcomeOrigin.VALIDATED),
                    com.monada.core.execution.Outcome.accepted(com.monada.core.execution.OutcomeOrigin.IMPORTED_CLAIM,
                            real.outcome().acceptedAttempt().orElseThrow()))) {
                List<String> v = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, withOutcome(yaml,
                        Optional.of(new ExperienceSample.OutcomeFact(real.ref(), real.sequence(), forgedOutcome))));
                assertTrue(v.stream().anyMatch(x -> x.contains("does not carry the exported outcome")), v.toString());
            }
            List<String> shifted = ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, withOutcome(yaml,
                    Optional.of(new ExperienceSample.OutcomeFact(real.ref(), real.sequence() + 1, real.outcome()))));
            assertTrue(shifted.stream().anyMatch(x -> x.contains("states ledger sequence")), shifted.toString());

            // the same holds for a stage whose stated sequence is off
            ExperienceSample.SampleStage st = yaml.attempts().get(0).stages().get(0);
            ExperienceSample off = withAttempt(yaml, 0, a -> withStages(a, List.of(new ExperienceSample.SampleStage(
                    st.ref(), st.sequence() + 5, st.stage(), st.route(), st.startedAt(), st.endedAt(),
                    st.usageDeclaration(), st.justification(), st.usage(), st.artifacts()))));
            assertTrue(ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, off).stream()
                    .anyMatch(x -> x.contains("states ledger sequence")));
        }
    }


    @Test
    void theDiagnosticViewPassesTheSameReferenceChecks(@TempDir Path root) {
        ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(alpha);
            List<ExperienceSample> samples = memory.exportSamples(ExportRequest.first(alpha,
                    com.monada.api.execution.ExportCutoff.latest(), 50, com.monada.api.execution.ExportView.DIAGNOSTIC,
                    com.monada.api.execution.ExportEvidencePolicy.executionPolicyOnly())).samples();
            assertEquals(9, samples.size());
            long diagnosticRefs = 0;
            for (ExperienceSample sample : samples) {
                assertTrue(sample.diagnostics().isPresent());
                diagnosticRefs += ForgeExperienceEvaluator.exportedReferences(sample).stream()
                        .filter(r -> r.origin().startsWith("diagnostics.")).count();
                assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(memory, alpha, sample),
                        sample.executionId().value());
            }
            assertTrue(diagnosticRefs > 0, "the diagnostic references are exercised");
        }
    }


    // --- #3 a revision keeps its whole history --------------------------------------------------------------

    @Test
    void aRevisionThatDropsOrReplacesItsPredecessorsIsAViolation(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample yaml = sampleOf(store.samples(), "a-yaml");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceSample.Revision real = yaml.revisions().get(0);
            assertEquals(List.of(), ForgeExperienceEvaluator.revisionViolations(store.memory(), alpha, yaml));
            assertEquals(List.of(), ForgeSampleChecks.revisionCountViolations(fixture, yaml));

            // the whole superseded list is dropped: nothing left to validate used to mean nothing was reported
            ExperienceSample dropped = withRevisions(yaml, List.of(new ExperienceSample.Revision(real.effective(),
                    real.sequence(), real.justification(), List.of())));
            List<String> v = ForgeExperienceEvaluator.revisionViolations(store.memory(), alpha, dropped);
            assertTrue(v.stream().anyMatch(x -> x.contains("retains 0 of its 1 predecessors")), v.toString());

            // a predecessor that is a genuine event, but not the one the ledger chain names
            ExperienceRef wrong = ref(real.effective(), "a-yaml.a1.stage", 1);
            ExperienceSample replaced = withRevisions(yaml, List.of(new ExperienceSample.Revision(real.effective(),
                    real.sequence(), real.justification(), List.of(wrong))));
            assertTrue(ForgeExperienceEvaluator.revisionViolations(store.memory(), alpha, replaced).stream()
                    .anyMatch(x -> x.contains("predecessor 1 of")), "replaced predecessor");

            // the revisions of the execution are missing altogether (the fixture corrects one attempt)
            ExperienceSample none = withRevisions(yaml, List.of());
            assertTrue(ForgeSampleChecks.revisionCountViolations(fixture, none).stream()
                    .anyMatch(x -> x.contains("0 revisions but the fixture corrects 1")));
            // and an execution that the fixture never corrects must not invent one
            ExperienceSample inv2 = sampleOf(store.samples(), "a-inv2");
            assertEquals(List.of(), ForgeSampleChecks.revisionCountViolations(fixture, inv2));
            assertFalse(ForgeSampleChecks.revisionCountViolations(fixture, withRevisions(inv2,
                    List.of(real))).isEmpty());
        }
    }


    // --- #7 a hit without an attempt must not abort the report -------------------------------------------------

    @Test
    void aHitWithoutAnAttemptIsReportedByTheGateInsteadOfAbortingTheReport(@TempDir Path root) {
        ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(alpha);
            ExperienceHit real = memory.recall(alpha, "yaml loader that preserves comments", 5).hits().get(0);
            var started = memory.loadEvent(alpha, EventId.of("a-yaml.start"), 1).orElseThrow();
            ExperienceRef noAttempt = new ExperienceRef(alpha, TaskId.of(fixture.execution("a-yaml").task()),
                    com.monada.core.execution.ExecutionId.of("a-yaml"), Optional.empty(), EventId.of("a-yaml.start"), 1);
            ExperienceHit forged = new ExperienceHit(noAttempt, real.atomId(), real.similarity(), started,
                    real.projectionVersion(), real.coveredSequence());
            ForgeFixture.Query q06 = fixture.queries().stream().filter(q -> q.id().equals("Q06")).findFirst().orElseThrow();

            List<ForgeEvaluationReport.RankedHit> ranked = ForgeExperienceEvaluator.rankedHits(q06, List.of(forged),
                    Map.of(), Map.of()); // must not throw
            assertEquals("a-yaml/<no-attempt>", ranked.get(0).experienceId());
            ForgeQueryResult result = ForgeQueryResult.of(q06, "CURRENT", ranked, fixture.ks());
            assertTrue(result.missing().containsKey("a-yaml/a1")); // and the query result can be built and rendered
            List<String> violations = ForgeExperienceEvaluator.hitViolations(fixture, memory, alpha, "Q06", forged);
            assertTrue(violations.stream().anyMatch(v -> v.contains("hit without an attempt")), violations.toString());
        }
    }


    // --- #2 #3 exported payloads equal the ledger's ---------------------------------------------------------------

    @Test
    void exportedFinishSummariesEvidenceStagesAndStartsMustEqualTheirLedgerEvents(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            for (ExperienceSample real : samples) {
                assertEquals(List.of(), ForgeExperienceEvaluator.referenceViolations(store.memory(), alpha, real),
                        real.executionId().value());
                assertEquals(List.of(), ForgeSampleChecks.evidenceStateViolations(fixture, real));
            }
            // finish: corrupted or dropped solution and lesson text
            ExperienceSample inv1 = sampleOf(samples, "a-inv1");
            expectViolation(store.memory(), alpha, withAttempt(inv1, 1, a -> copyAttempt(a, a.accepted(), a.previousAttempt(),
                    Optional.of("tampered solution"), a.lessonSummary(), a.evidence())),
                    "does not carry result COMPLETED and the exported solution and lesson summaries");
            expectViolation(store.memory(), alpha, withAttempt(inv1, 1, a -> copyAttempt(a, a.accepted(), a.previousAttempt(),
                    a.solutionSummary(), Optional.empty(), a.evidence())), "lesson summaries");
            expectViolation(store.memory(), alpha, withAttempt(inv1, 1, a -> copyAttempt(a, a.accepted(), a.previousAttempt(),
                    Optional.empty(), Optional.empty(), a.evidence())), "solution and lesson summaries");

            // start: a wrong predecessor
            expectViolation(store.memory(), alpha, withAttempt(inv1, 1, a -> copyAttempt(a, a.accepted(), Optional.empty(),
                    a.solutionSummary(), a.lessonSummary(), a.evidence())),
                    "does not carry ordinal 2, reason REPAIR and previous attempt <none>");

            // evidence of a rejected execution (it has no gates, so only this comparison protects it)
            ExperienceSample rejected = sampleOf(samples, "a-export");
            ExperienceSample.SampleEvidence ev = rejected.attempts().get(0).evidence().get(0);
            ExperienceSample.SampleObservation ob = ev.observations().get(0);
            ExperienceSample.SampleEvidence flipped = new ExperienceSample.SampleEvidence(ev.ref(), ev.sequence(),
                    List.of(new ExperienceSample.SampleObservation(withState(ob.observation(),
                            com.monada.core.execution.ObservationState.PASS), ob.policyMatches())), ev.artifacts());
            ExperienceSample.SampleEvidence dropped = new ExperienceSample.SampleEvidence(ev.ref(), ev.sequence(),
                    List.of(), ev.artifacts());
            ExperienceSample.SampleEvidence artifact = new ExperienceSample.SampleEvidence(ev.ref(), ev.sequence(),
                    ev.observations(), List.of(com.monada.core.execution.ArtifactRef.of("report", "extra")));
            for (ExperienceSample.SampleEvidence tampered : List.of(flipped, dropped, artifact)) {
                ExperienceSample forged = withAttempt(rejected, 0, a -> copyAttempt(a, a.accepted(),
                        a.previousAttempt(), a.solutionSummary(), a.lessonSummary(), List.of(tampered)));
                expectViolation(store.memory(), alpha, forged, "does not carry the exported observations and artifacts");
            }
            // and the judged states of the fixture catch the same corruption without the ledger
            assertTrue(ForgeSampleChecks.evidenceStateViolations(fixture, withAttempt(rejected, 0, a -> copyAttempt(a,
                    a.accepted(), a.previousAttempt(), a.solutionSummary(), a.lessonSummary(), List.of(flipped))))
                    .stream().anyMatch(v -> v.contains("a-export/a1") && v.contains("TESTS=PASS")));
            assertFalse(ForgeSampleChecks.evidenceStateViolations(fixture, withAttempt(rejected, 0, a -> copyAttempt(a,
                    a.accepted(), a.previousAttempt(), a.solutionSummary(), a.lessonSummary(), List.of()))).isEmpty());

            // stage: a different route
            ExperienceSample.SampleStage st = inv1.attempts().get(0).stages().get(0);
            ExperienceSample.SampleStage rerouted = new ExperienceSample.SampleStage(st.ref(), st.sequence(),
                    st.stage(), new com.monada.core.execution.RouteDescriptor("other-worker", st.route().provider(),
                    st.route().model(), st.route().effort(), st.route().billingMode()), st.startedAt(), st.endedAt(),
                    st.usageDeclaration(), st.justification(), st.usage(), st.artifacts());
            expectViolation(store.memory(), alpha, withAttempt(inv1, 0, a -> withStages(a, List.of(rerouted))),
                    "with the exported route, interval, usage declaration and artifacts");
        }
    }


    @Test
    void aGateMustHoldTheObservationOfItsLedgerEventAndTheStateItImplies(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample page = sampleOf(store.samples(), "a-page");
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            assertEquals(List.of(), ForgeSampleChecks.gateStateViolations("a-page", page));
            ExperienceSample.Gate tests = page.gates().stream()
                    .filter(g -> g.dimension() == QualityDimension.TESTS).findFirst().orElseThrow();
            // an observation the ledger event does not hold (state flipped), with the state left as it was
            ExperienceSample.Gate other = new ExperienceSample.Gate(tests.dimension(), tests.required(), tests.state(),
                    Optional.of(withState(tests.observation().orElseThrow(),
                            com.monada.core.execution.ObservationState.FAIL)), tests.ref(), tests.sequence());
            List<ExperienceSample.Gate> gates = new ArrayList<>(page.gates());
            gates.set(gates.indexOf(tests), other);
            ExperienceSample forged = withGates(page, gates);
            expectViolation(store.memory(), alpha, forged, "an observation of TESTS equal to the exported one");
            assertTrue(ForgeSampleChecks.gateStateViolations("a-page", forged).stream()
                    .anyMatch(v -> v.contains("is PASS but its observation is FAIL")));
            // no observation but a PASS state
            ExperienceSample.Gate hollow = new ExperienceSample.Gate(tests.dimension(), tests.required(),
                    ExperienceSample.GateState.PASS, Optional.empty(), Optional.empty(), java.util.OptionalLong.empty());
            List<ExperienceSample.Gate> hollowGates = new ArrayList<>(page.gates());
            hollowGates.set(hollowGates.indexOf(tests), hollow);
            assertFalse(ForgeSampleChecks.gateStateViolations("a-page", withGates(page, hollowGates)).isEmpty());
        }
    }


    // --- #5 every page describes the same snapshot --------------------------------------------------------------

    @Test
    void everyPageMustCarryTheMetadataOfTheSameSnapshot(@TempDir Path root) {
        ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
        try (ExecutionMemory memory = ExecutionMemory.open(root, alpha)) {
            ForgeFixtureRecorder.recordScope(memory, fixture, "alpha");
            memory.rebuildProjection(alpha);
            PriceBook prices = PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture)));
            ExperienceExportReport report = ExperienceExportReport.build(memory, ExportRequest.first(alpha,
                    com.monada.api.execution.ExportCutoff.latest(), 4, com.monada.api.execution.ExportView.ADAPTER_READY,
                    com.monada.api.execution.ExportEvidencePolicy.executionPolicyOnly()), prices, Optional.empty());
            assertEquals(3, report.pages().size());
            assertEquals(List.of(), ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, report));

            var second = report.pages().get(1);
            var ledger = second.ledger();
            var projection = second.projection();
            List<com.monada.api.execution.ExperienceSamplePage> variants = List.of(
                    page(second, second.schema(), second.view(), second.additionalGates(),
                            new com.monada.api.execution.ExperienceSamplePage.LedgerCheckpoint(
                                    ledger.cutoffSequence() - 1, ledger.prefixDigest()), projection, second.hasMore(),
                            second.next()),
                    page(second, second.schema(), second.view(), second.additionalGates(),
                            new com.monada.api.execution.ExperienceSamplePage.LedgerCheckpoint(
                                    ledger.cutoffSequence(), "0".repeat(64)), projection, second.hasMore(), second.next()),
                    page(second, "execution-sample/2", second.view(), second.additionalGates(), ledger, projection,
                            second.hasMore(), second.next()),
                    page(second, second.schema(), com.monada.api.execution.ExportView.DIAGNOSTIC,
                            second.additionalGates(), ledger, projection, second.hasMore(), second.next()),
                    page(second, second.schema(), second.view(), List.of(QualityDimension.TESTS), ledger, projection,
                            second.hasMore(), second.next()),
                    page(second, second.schema(), second.view(), second.additionalGates(), ledger,
                            new com.monada.api.execution.ExperienceSamplePage.ProjectionStamp(
                                    projection.formatVersion() + 1, projection.textScheme(), projection.state(),
                                    projection.coversCutoff()), second.hasMore(), second.next()),
                    page(second, second.schema(), second.view(), second.additionalGates(), ledger,
                            new com.monada.api.execution.ExperienceSamplePage.ProjectionStamp(projection.formatVersion(),
                                    projection.textScheme(), com.monada.api.execution.ProjectionStatus.State.STALE,
                                    false), second.hasMore(), second.next()),
                    page(second, second.schema(), second.view(), second.additionalGates(), ledger, projection, false,
                            Optional.empty()));
            for (int i = 0; i < variants.size(); i++) {
                List<com.monada.api.execution.ExperienceSamplePage> pages = new ArrayList<>(report.pages());
                pages.set(1, variants.get(i));
                ExperienceExportReport forged = new ExperienceExportReport(alpha, pages, report.samples(),
                        report.comparison(), report.exportDigest());
                assertFalse(ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, forged).isEmpty(),
                        "variant " + i);
                // and the pagination check the gate runs includes the metadata comparison
                assertTrue(ForgeExperienceEvaluator.paginationViolations(memory, alpha, forged, 4, 9).stream()
                        .anyMatch(v -> v.contains("page 2/3")), "variant " + i + " through paginationViolations");
            }
            assertEquals(List.of(), ForgeExperienceEvaluator.paginationViolations(memory, alpha, report, 4, 9));
        }
    }


    // --- #6 sequence bounds ---------------------------------------------------------------------------------------

    @Test
    void theDeclaredSequenceBoundsMustBeTheFirstAndLastOfTheExecutionHistory(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            for (ExperienceSample real : samples) {
                assertEquals(List.of(), ForgeSampleChecks.sequenceBoundsViolations(store.memory(), alpha, real),
                        real.executionId().value());
            }
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            assertFalse(ForgeSampleChecks.sequenceBoundsViolations(store.memory(), alpha,
                    withBounds(yaml, yaml.firstSequence() + 1, yaml.lastSequence())).isEmpty());
            assertFalse(ForgeSampleChecks.sequenceBoundsViolations(store.memory(), alpha,
                    withBounds(yaml, yaml.firstSequence(), yaml.lastSequence() - 1)).isEmpty());
        }
    }


    // --- #8 the whole persisted chronology ----------------------------------------------------------------------------

    @Test
    void everyReferencedEventMustBePersistedAtTheInstantTheFixtureDeclares(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            for (ExperienceSample real : samples) {
                assertEquals(List.of(), ForgeExperienceEvaluator.chronologyViolations(fixture, store.memory(), alpha, real),
                        real.executionId().value());
            }
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            // a fixture that declares a later end for a-yaml/a1 than the one the events were persisted with
            ForgeFixture later = shifted(a -> a.experienceId().equals("a-yaml/a1"), 0, 1);
            List<String> violations = ForgeExperienceEvaluator.chronologyViolations(later, store.memory(), alpha, yaml);
            for (String origin : List.of("stage", "evidence", "attempt.finish", "revision.effective", "revision.superseded",
                    "gate", "outcome")) {
                assertTrue(violations.stream().anyMatch(v -> v.contains(": " + origin + " ")), origin + " in " + violations);
            }
            assertFalse(violations.stream().anyMatch(v -> v.contains(": execution ")));
            // the execution start is declared at T0 and the attempt start at its own start
            ExperienceSample inv1 = sampleOf(samples, "a-inv1");
            ForgeFixture otherStart = shifted(a -> a.experienceId().equals("a-inv1/a1"), 1, 0);
            assertTrue(ForgeExperienceEvaluator.chronologyViolations(otherStart, store.memory(), alpha, inv1).stream()
                    .anyMatch(v -> v.contains("attempt.start")));
        }
    }


    private ForgeFixture shifted(java.util.function.Predicate<ForgeFixture.Attempt> which, long startDelta,
                                 long endDelta) {
        List<ForgeFixture.Attempt> attempts = new ArrayList<>();
        for (ForgeFixture.Attempt a : fixture.attempts()) {
            attempts.add(!which.test(a) ? a : new ForgeFixture.Attempt(a.execution(), a.ordinal(), a.reason(),
                    a.result(), a.solution(), a.lesson(), a.inputTokens(), a.outputTokens(),
                    a.startSeconds() + startDelta, a.endSeconds() + endDelta, a.evidence(), a.revisedSolution(),
                    a.revisedLesson()));
        }
        return new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(), fixture.price(),
                fixture.recallLimit(), fixture.ks(), fixture.executions(), attempts, fixture.queries(),
                fixture.expectations());
    }

    // --- the compute-once overloads return what the originals return ------------------------------------------------

    @Test
    void theComputeOnceOverloadsReturnExactlyWhatTheOriginalsReturn(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            var memory = store.memory();
            ScopeId alpha = store.scope();
            Map<String, Integer> ordinals = ForgeSampleChecks.expectedOrdinals(fixture, "alpha");
            List<ExperienceSample> samples = new ArrayList<>(store.samples());
            ExperienceSample yaml = sampleOf(samples, "a-yaml");
            samples.add(withRevisions(yaml, List.of())); // forged samples must agree too, violations included
            samples.add(withAttempt(sampleOf(samples, "a-inv1"), 1, a -> copyAttempt(a, true, a.previousAttempt(),
                    Optional.of("tampered"), a.lessonSummary(), a.evidence())));
            for (ExperienceSample sample : samples) {
                var refs = ForgeExperienceEvaluator.exportedReferences(sample);
                String id = sample.executionId().value();
                assertEquals(ForgeExperienceEvaluator.referenceViolations(memory, alpha, sample),
                        ForgeExperienceEvaluator.referenceViolations(memory, alpha, sample, refs), id);
                assertEquals(ForgeExperienceEvaluator.revisionViolations(memory, alpha, sample),
                        ForgeExperienceEvaluator.revisionViolations(memory, alpha, sample, refs), id);
                assertEquals(ForgeExperienceEvaluator.chronologyViolations(fixture, memory, alpha, sample),
                        ForgeExperienceEvaluator.chronologyViolations(fixture, memory, alpha, sample, refs), id);
                assertEquals(ForgeSampleChecks.successViolations(fixture, sample),
                        ForgeSampleChecks.successViolations(fixture, sample, ordinals), id);
            }
            assertFalse(ForgeExperienceEvaluator.referenceViolations(memory, alpha, samples.get(samples.size() - 1))
                    .isEmpty()); // and the forged one really is a violation, so the parity above is not vacuous

            PriceBook prices = PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture)));
            ExperienceExportReport report = ExperienceExportReport.build(memory, ExportRequest.first(alpha,
                    com.monada.api.execution.ExportCutoff.latest(), 4, com.monada.api.execution.ExportView.ADAPTER_READY,
                    com.monada.api.execution.ExportEvidencePolicy.executionPolicyOnly()), prices, Optional.empty());
            var reference = memory.exportSamples(ExportRequest.firstPage(alpha, 50));
            assertEquals(ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, report),
                    ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, report, reference));
            var second = report.pages().get(1);
            List<com.monada.api.execution.ExperienceSamplePage> pages = new ArrayList<>(report.pages());
            pages.set(1, page(second, "execution-sample/2", second.view(), second.additionalGates(), second.ledger(),
                    second.projection(), second.hasMore(), second.next()));
            ExperienceExportReport forged = new ExperienceExportReport(alpha, pages, report.samples(),
                    report.comparison(), report.exportDigest());
            assertEquals(ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, forged),
                    ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, forged, reference));
            assertFalse(ForgeExperienceEvaluator.pageMetadataViolations(memory, alpha, forged, reference).isEmpty());
        }
    }
}
