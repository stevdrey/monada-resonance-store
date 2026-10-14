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
import com.monada.evaluation.execution.ForgeTestSupport.SampleCopy;
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
 * exactly the matching gate reports it and that the decision turns to GAPS_REQUIRE_FOLLOW_UP. The checks of the
 * references of an export (G3) are in {@link ForgeReferenceChecksTest}.
 */
class ForgeGatesTest {
    @TempDir Path work;
    private final ForgeFixture fixture = ForgeFixture.load();


    private ForgeFixture with(List<ForgeFixture.Execution> executions, ForgeFixture.Expectations expectations) {
        return new ForgeFixture(fixture.version(), fixture.provenance(), fixture.scopeIds(), fixture.price(),
                fixture.recallLimit(), fixture.ks(), executions, fixture.attempts(), fixture.queries(), expectations);
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
                    x.mandatory(), x.outcome(), x.outcomeAttempt(), "SUCCESS", "VALIDATED_ACCEPTED", java.util.Set.of()));
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


    // --- G5: one entry per usage kind -----------------------------------------------------------------

    @Test
    void everyStageMustExportExactlyOneEntryPerUsageKind(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample inv1 = sampleOf(store.samples(), "a-inv1");
            ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get("a-inv1");
            ExperienceSample.SampleStage stage = inv1.attempts().get(1).stages().get(0);
            assertEquals(com.monada.core.execution.UsageKind.values().length, stage.usage().size());

            List<ExperienceSample.SampleUsage> withoutRequests = stage.usage().stream()
                    .filter(u -> u.kind() != com.monada.core.execution.UsageKind.REQUESTS).toList();
            ExperienceSample dropped = withStageUsage(inv1, withoutRequests);
            List<String> v = ForgeExperienceEvaluator.exportedUsageViolations(dropped, expected, 2);
            assertEquals(1, v.size(), v.toString());
            assertTrue(v.get(0).contains("omits the REQUESTS counter"), v.get(0));

            List<ExperienceSample.SampleUsage> doubled = new ArrayList<>(stage.usage());
            doubled.add(stage.usage().get(0));
            List<String> v2 = ForgeExperienceEvaluator.exportedUsageViolations(withStageUsage(inv1, doubled), expected, 2);
            assertTrue(v2.stream().anyMatch(x -> x.contains("exports the INPUT_TOKENS counter 2 times")), v2.toString());

            List<ExperienceSample.SampleUsage> onlyTokens = stage.usage().stream()
                    .filter(u -> u.kind() == com.monada.core.execution.UsageKind.INPUT_TOKENS
                            || u.kind() == com.monada.core.execution.UsageKind.OUTPUT_TOKENS).toList();
            assertEquals(4, ForgeExperienceEvaluator.exportedUsageViolations(withStageUsage(inv1, onlyTokens),
                    expected, 2).size());
        }
    }


    // --- G2: mandatory gates, group and ordinal of every sample ----------------------------------------

    @Test
    void aSuccessMustCarryOnePassingGatePerMandatoryDimension(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample page = sampleOf(store.samples(), "a-page");
            assertEquals(List.of(), ForgeSampleChecks.successViolations(fixture, page));
            assertEquals(2, page.gates().stream().filter(ExperienceSample.Gate::required).count());

            ExperienceSample noArchitecture = withGates(page, page.gates().stream()
                    .filter(g -> g.dimension() != QualityDimension.ARCHITECTURE).toList());
            assertTrue(ForgeSampleChecks.successViolations(fixture, noArchitecture).stream()
                    .anyMatch(v -> v.contains("0 required gates for the mandatory dimension ARCHITECTURE")));

            List<ExperienceSample.Gate> doubled = new ArrayList<>(page.gates());
            doubled.add(page.gates().get(0));
            assertTrue(ForgeSampleChecks.successViolations(fixture, withGates(page, doubled)).stream()
                    .anyMatch(v -> v.contains("2 required gates")));

            List<ExperienceSample.Gate> unbacked = new ArrayList<>();
            for (ExperienceSample.Gate g : page.gates()) {
                unbacked.add(new ExperienceSample.Gate(g.dimension(), g.required(), g.state(), Optional.empty(),
                        Optional.empty(), java.util.OptionalLong.empty()));
            }
            assertTrue(ForgeSampleChecks.successViolations(fixture, withGates(page, unbacked)).stream()
                    .anyMatch(v -> v.contains("without the observation that backs it")));

            // the policy itself must match: a sample claiming fewer required gates than the policy mandates
            ExperienceSample fewerRequired = SampleCopy.of(page).requiredGates(List.of(QualityDimension.TESTS)).build();
            assertTrue(ForgeSampleChecks.successViolations(fixture, fewerRequired).stream()
                    .anyMatch(v -> v.contains("required gates") && v.contains("mandates")));
        }
    }


    @Test
    void groupAndSuccessOrdinalAreDerivedFromTheFixtureForEverySample(@TempDir Path root) {
        // the hand-listed ordinals agree with the derived ones
        assertEquals(java.util.Map.of("a-inv1", 1, "a-inv2", 2), java.util.Map.of("a-inv1",
                ForgeSampleChecks.expectedOrdinals(fixture, "alpha").get("a-inv1"), "a-inv2",
                ForgeSampleChecks.expectedOrdinals(fixture, "alpha").get("a-inv2")));
        for (String scopeKey : fixture.scopeIds().keySet()) {
            fixture.expectations().successOrdinals().forEach((exec, ordinal) -> {
                if (fixture.execution(exec).scopeKey().equals(scopeKey)) {
                    assertEquals(ordinal, ForgeSampleChecks.expectedOrdinals(fixture, scopeKey).get(exec), exec);
                }
            });
        }
        assertEquals(1, ForgeSampleChecks.expectedOrdinals(fixture, "alpha").get("a-inv3")); // other context SHA

        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            for (ExperienceSample sample : samples) {
                assertEquals(List.of(), ForgeSampleChecks.successViolations(fixture, sample),
                        sample.executionId().value());
            }
            ExperienceSample inv1 = sampleOf(samples, "a-inv1");
            ExperienceSample inv3 = sampleOf(samples, "a-inv3");
            // grouping that ignores the context SHA would put a-inv3 after a-inv1 and a-inv2 with ordinal 3
            List<String> v = ForgeSampleChecks.successViolations(fixture,
                    withGroup(inv3, inv1.group(), java.util.OptionalInt.of(3)));
            assertTrue(v.stream().anyMatch(x -> x.contains("group")), v.toString());
            assertTrue(v.stream().anyMatch(x -> x.contains("success ordinal OptionalInt[3] but derived 1")), v.toString());
            // a repeat that restarts at 1, and a field of the group that differs
            ExperienceSample inv2 = sampleOf(samples, "a-inv2");
            assertFalse(ForgeSampleChecks.successViolations(fixture,
                    withGroup(inv2, inv2.group(), java.util.OptionalInt.of(1))).isEmpty());
            ExperienceSample.Group g = inv2.group();
            assertFalse(ForgeSampleChecks.successViolations(fixture, withGroup(inv2,
                    new ExperienceSample.Group(g.task(), g.sourceRevision(), g.contextFingerprint(),
                            "other-constraints", g.policyId(), g.policyVersion()), inv2.successOrdinal())).isEmpty());
            // a non-success sample must not carry an ordinal (the sample constructor already refuses to build one)
            ExperienceSample tokens = sampleOf(samples, "a-tokens");
            assertTrue(tokens.successOrdinal().isEmpty());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> withGroup(tokens, tokens.group(), java.util.OptionalInt.of(1)));
        }
    }


    // --- G1: one sample per execution -------------------------------------------------------------------

    @Test
    void anExportMustHoldExactlyOneSamplePerExecution(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            assertEquals(List.of(), ForgeSampleChecks.exportMembershipViolations(fixture, "alpha", samples));

            List<ExperienceSample> duplicated = new ArrayList<>(samples);
            duplicated.add(sampleOf(samples, "a-export")); // a non-success duplicate leaves the id set unchanged
            List<String> v = ForgeSampleChecks.exportMembershipViolations(fixture, "alpha", duplicated);
            assertTrue(v.stream().anyMatch(x -> x.contains("a-export is exported 2 times")), v.toString());
            assertTrue(v.stream().anyMatch(x -> x.contains("10 samples for 9 executions")), v.toString());

            List<ExperienceSample> missing = new ArrayList<>(samples);
            missing.remove(sampleOf(samples, "a-tz"));
            assertTrue(ForgeSampleChecks.exportMembershipViolations(fixture, "alpha", missing).stream()
                    .anyMatch(x -> x.contains("a-tz is missing")));

            List<ExperienceSample> swapped = new ArrayList<>(samples);
            swapped.remove(sampleOf(samples, "a-tz"));
            swapped.add(sampleOf(samples, "a-tz")); // same members, other order: still valid
            assertEquals(List.of(), ForgeSampleChecks.exportMembershipViolations(fixture, "alpha", swapped));
            // one to three extra copies never change the three-page count at page size 4, only the membership check sees them
            for (int extra = 1; extra <= 3; extra++) {
                List<ExperienceSample> more = new ArrayList<>(samples);
                for (int i = 0; i < extra; i++) {
                    more.add(sampleOf(samples, "a-export"));
                }
                assertEquals(3, (more.size() + 3) / 4);
                assertFalse(ForgeSampleChecks.exportMembershipViolations(fixture, "alpha", more).isEmpty());
            }
        }
    }


    // --- #6 fresh stores on every evaluate call ---------------------------------------------------------

    @Test
    void evaluatingTwiceInTheSameWorkDirectoryNeverSeesTheEarlierLedgers() throws Exception {
        ForgeEvaluationReport first = ForgeExperienceEvaluator.evaluate(fixture, work);
        ForgeEvaluationReport second = ForgeExperienceEvaluator.evaluate(fixture, work);
        assertEquals(Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT, first.decision());
        assertEquals(Decision.READY_FOR_HOST_ADAPTER_EXPERIMENT, second.decision(), second.render());
        assertEquals(first.render(), second.render());
        try (var runs = java.nio.file.Files.list(work)) {
            assertEquals(4, runs.filter(p -> p.getFileName().toString().startsWith("run-")).count());
        }
        // a missing work directory is created
        ForgeEvaluationReport created = ForgeExperienceEvaluator.evaluate(fixture, work.resolve("not/yet/there"));
        assertEquals(first.render(), created.render());
    }


    // --- #5 the corpus section shows what the export reports ---------------------------------------------

    @Test
    void theCorpusSectionShowsObservedLabelsAndMarksDifferencesAndMissingSamples(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            Map<String, AnnotatedSample> exported = new java.util.LinkedHashMap<>();
            PriceBook prices = PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture)));
            for (ExperienceSample sample : samples) {
                exported.put(sample.executionId().value(), new AnnotatedSample(sample,
                        ExecutionSummaryCalculator.summarize(store.memory().loadExecution(
                                ForgeFixtureRecorder.scope(fixture, "alpha"), sample.executionId()).orElseThrow(),
                                prices, List.of()).cost(), AnnotatedSample.Placement.notRequested()));
            }
            Map<String, String> results = new HashMap<>();
            fixture.attempts().forEach(a -> results.put(a.experienceId(), a.result()));

            // real data: observed == judged, so no suffix anywhere for alpha
            List<ForgeEvaluationReport.ForgeCorpusLine> real = ForgeExperienceEvaluator.corpusLines(fixture,
                    exported, results);
            real.stream().filter(l -> l.scopeKey().equals("alpha")).forEach(l -> {
                assertFalse(l.label().contains("judged"), l.toString());
                assertFalse(l.acceptance().contains("judged"), l.toString());
            });

            // the export regressed: a judged SUCCESS comes back UNLABELED
            ExperienceSample inv2 = sampleOf(samples, "a-inv2");
            ExperienceSample regressed = SampleCopy.of(inv2).acceptance(DerivedAcceptance.Status.ACCEPTED_UNVALIDATED)
                    .label(ExperienceSample.Label.UNLABELED).successOrdinal(java.util.OptionalInt.empty()).build();
            Map<String, AnnotatedSample> broken = new java.util.LinkedHashMap<>(exported);
            broken.put("a-inv2", new AnnotatedSample(regressed, exported.get("a-inv2").cost(),
                    AnnotatedSample.Placement.notRequested()));
            broken.remove("a-tz"); // and one execution is gone from the export
            List<ForgeEvaluationReport.ForgeCorpusLine> lines = ForgeExperienceEvaluator.corpusLines(fixture, broken,
                    results);
            var inv2Line = lines.stream().filter(l -> l.experienceId().equals("a-inv2/a1")).findFirst().orElseThrow();
            assertEquals("UNLABELED (judged SUCCESS)", inv2Line.label());
            assertEquals("ACCEPTED_UNVALIDATED (judged VALIDATED_ACCEPTED)", inv2Line.acceptance());
            var tzLine = lines.stream().filter(l -> l.experienceId().equals("a-tz/a1")).findFirst().orElseThrow();
            assertEquals("MISSING (judged SUCCESS)", tzLine.label());
            assertEquals("MISSING", tzLine.cost());
        }
    }


    @Test
    void eligibilityMustBeTrueExactlyForSuccessAndFailureLabels(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ExperienceSample.Reason reason = new ExperienceSample.Reason(
                    ExperienceSample.EligibilityCode.OUTCOME_PENDING, Optional.empty(), "forged");
            ExperienceSample failure = sampleOf(samples, "a-export");
            ExperienceSample unlabeled = sampleOf(samples, "a-tokens");
            ExperienceSample success = sampleOf(samples, "a-inv2");
            assertEquals(ExperienceSample.Label.FAILURE, failure.label());
            assertEquals(ExperienceSample.Label.UNLABELED, unlabeled.label());
            for (ExperienceSample real : List.of(failure, unlabeled, success)) {
                assertEquals(List.of(), ForgeSampleChecks.successViolations(fixture, real), real.executionId().value());
            }
            // a valid negative example excluded by an adapter, a non-example offered as one, a success excluded
            assertTrue(ForgeSampleChecks.successViolations(fixture, withEligibility(failure,
                    new ExperienceSample.Eligibility(false, List.of(reason)))).stream()
                    .anyMatch(v -> v.contains("FAILURE sample is marked ineligible") && v.contains("must be true")));
            assertTrue(ForgeSampleChecks.successViolations(fixture, withEligibility(unlabeled,
                    new ExperienceSample.Eligibility(true, List.of()))).stream()
                    .anyMatch(v -> v.contains("UNLABELED sample is marked eligible") && v.contains("must be false")));
            assertFalse(ForgeSampleChecks.successViolations(fixture, withEligibility(success,
                    new ExperienceSample.Eligibility(false, List.of(reason)))).isEmpty());
        }
    }


    // --- #1 only the outcome's attempt is marked accepted ------------------------------------------------------

    @Test
    void theAcceptedMarkerFollowsTheJudgedOutcomeOfEveryExecution(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            for (ExperienceSample sample : samples) {
                assertEquals(List.of(), ForgeSampleChecks.acceptedMarkerViolations(
                        fixture.execution(sample.executionId().value()), sample), sample.executionId().value());
            }
            assertTrue(sampleOf(samples, "a-import").attempts().get(0).accepted()); // an imported claim names one too

            ExperienceSample export = sampleOf(samples, "a-export"); // rejected: no attempt may be marked
            ExperienceSample markedFailed = withAttempt(export, 0, a -> copyAttempt(a, true, a.previousAttempt(),
                    a.solutionSummary(), a.lessonSummary(), a.evidence()));
            assertTrue(ForgeSampleChecks.acceptedMarkerViolations(fixture.execution("a-export"), markedFailed).stream()
                    .anyMatch(v -> v.contains("a-export/a1") && v.contains("marked accepted")));

            ExperienceSample yaml = sampleOf(samples, "a-yaml"); // the accepted attempt lost its marker
            assertFalse(ForgeSampleChecks.acceptedMarkerViolations(fixture.execution("a-yaml"), withAttempt(yaml, 0,
                    a -> copyAttempt(a, false, a.previousAttempt(), a.solutionSummary(), a.lessonSummary(),
                            a.evidence()))).isEmpty());

            ExperienceSample page = sampleOf(samples, "a-page"); // accepted is attempt 3: marking attempt 1 as well
            assertFalse(ForgeSampleChecks.acceptedMarkerViolations(fixture.execution("a-page"), withAttempt(page, 0,
                    a -> copyAttempt(a, true, a.previousAttempt(), a.solutionSummary(), a.lessonSummary(),
                            a.evidence()))).isEmpty());
        }
    }


    // --- #4 the currency of every cost --------------------------------------------------------------------------

    @Test
    void aCostInAnotherCurrencyIsAViolationAndTheReportShowsTheRealCurrency(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            PriceBook prices = PriceBook.of(List.of(ForgeFixtureRecorder.priceSnapshot(fixture)));
            CostToAcceptedOutcome real = ExecutionSummaryCalculator.summarize(
                    store.memory().loadExecution(alpha, com.monada.core.execution.ExecutionId.of("a-yaml")).orElseThrow(),
                    prices, List.of()).cost();
            ForgeFixture.ExpectedChain expected = fixture.expectations().chains().get("a-yaml");
            assertEquals(List.of(), ForgeExperienceEvaluator.costViolations("a-yaml", expected, real, "USD"));

            CurrencyCost usd = real.amounts().get(0);
            CostToAcceptedOutcome eur = new CostToAcceptedOutcome(real.status(), real.basis(),
                    List.of(new CurrencyCost(java.util.Currency.getInstance("EUR"), usd.reportedBasis(),
                            usd.estimatedBasis())), real.reasons());
            List<String> violations = ForgeExperienceEvaluator.costViolations("a-yaml", expected, eur, "USD");
            assertEquals(1, violations.size(), violations.toString()); // the number is right, the currency is not
            assertTrue(violations.get(0).contains("cost is in EUR but the fixture prices in USD"));

            Map<String, AnnotatedSample> exported = new java.util.LinkedHashMap<>();
            for (ExperienceSample sample : samples) {
                exported.put(sample.executionId().value(), new AnnotatedSample(sample, sample.executionId().value()
                        .equals("a-yaml") ? eur : real, AnnotatedSample.Placement.notRequested()));
            }
            Map<String, String> results = new HashMap<>();
            fixture.attempts().forEach(a -> results.put(a.experienceId(), a.result()));
            var line = ForgeExperienceEvaluator.corpusLines(fixture, exported, results).stream()
                    .filter(l -> l.experienceId().equals("a-yaml/a1")).findFirst().orElseThrow();
            assertTrue(line.cost().endsWith("EUR"), line.cost());
        }
    }


    // --- #9 the reasons of an ineligible sample -------------------------------------------------------------------------

    @Test
    void anIneligibleSampleMustGiveTheJudgedReasons(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            List<ExperienceSample> samples = store.samples();
            for (ExperienceSample real : samples) {
                assertEquals(List.of(), ForgeSampleChecks.eligibilityReasonViolations(real.executionId().value(),
                        fixture.execution(real.executionId().value()), real), real.executionId().value());
            }
            ExperienceSample tokens = sampleOf(samples, "a-tokens"); // judged GATE_MISSING(TESTS)
            ForgeFixture.Execution judged = fixture.execution("a-tokens");
            assertEquals(java.util.Set.of("GATE_MISSING(TESTS)"), judged.expectedReasons());
            ExperienceSample.Reason pending = new ExperienceSample.Reason(
                    ExperienceSample.EligibilityCode.OUTCOME_PENDING, Optional.empty(), "x");
            ExperienceSample.Reason missingTests = new ExperienceSample.Reason(
                    ExperienceSample.EligibilityCode.GATE_MISSING, Optional.of(QualityDimension.TESTS), "x");
            ExperienceSample.Reason missingSecurity = new ExperienceSample.Reason(
                    ExperienceSample.EligibilityCode.GATE_MISSING, Optional.of(QualityDimension.SECURITY), "x");
            for (List<ExperienceSample.Reason> wrong : List.of(List.of(pending), List.of(missingTests, pending),
                    List.of(missingSecurity), List.of(missingTests, missingTests))) {
                List<String> v = ForgeSampleChecks.eligibilityReasonViolations("a-tokens", judged, withEligibility(
                        tokens, new ExperienceSample.Eligibility(false, wrong)));
                assertEquals(1, v.size(), wrong + " " + v);
                assertTrue(v.get(0).contains("but judged [GATE_MISSING(TESTS)]"), v.get(0));
            }
            // an eligible sample gives none; an ineligible FAILURE contradicts both the label and the reasons
            ExperienceSample failure = sampleOf(samples, "a-export");
            assertTrue(ForgeSampleChecks.successViolations(fixture, withEligibility(failure,
                    new ExperienceSample.Eligibility(false, List.of(pending)))).stream()
                    .anyMatch(v -> v.contains("eligibility reasons [OUTCOME_PENDING] but judged []")));
        }
    }

    // ======================= code-review findings ===============================================================

    // --- 1 an attempt-less hit must not abort the fingerprint or any gate ---------------------------------------

    @Test
    void anAttemptLessHitNeverAbortsTheFingerprintOrAnyGate(@TempDir Path root) {
        ForgeExperienceEvaluator.Snapshot s = openSnapshot(root, fixture);
        try {
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceHit real = s.hits.get("Q06").get(0);
            var started = s.memories.get("alpha").loadEvent(alpha, EventId.of("a-yaml.start"), 1).orElseThrow();
            ExperienceRef noAttempt = new ExperienceRef(alpha, TaskId.of(fixture.execution("a-yaml").task()),
                    ExecutionId.of("a-yaml"), Optional.empty(), EventId.of("a-yaml.start"), 1);
            s.hits.put("Q06", List.of(new ExperienceHit(noAttempt, real.atomId(), real.similarity(), started,
                    real.projectionVersion(), real.coveredSequence())));

            assertTrue(s.fingerprint().contains("a-yaml/<no-attempt>")); // used to throw NoSuchElementException
            List<ForgeGate> gates = ForgeExperienceEvaluator.allGates(fixture, s, s,
                    new ForgeExperienceEvaluator.ReplayFindings(0, List.of(), List.of(), List.of(), List.of()));
            assertTrue(gateOf(gates, ForgeGate.EXACT_REFERENCES).details().stream()
                    .anyMatch(d -> d.contains("hit without an attempt")), gates.toString());
        } finally {
            s.close();
        }
    }

    private static ForgeGate gateOf(List<ForgeGate> gates, String id) {
        return gates.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    // --- 2 an execution the fixture does not know is reported, never thrown --------------------------------------

    @Test
    void anExecutionUnknownToTheFixtureIsReportedByTheGatesInsteadOfAbortingThem(@TempDir Path root) {
        ForgeExperienceEvaluator.Snapshot s = openSnapshot(root, fixture);
        try {
            ScopeId alpha = ForgeFixtureRecorder.scope(fixture, "alpha");
            ExperienceExportReport real = s.exports.get("alpha");
            AnnotatedSample first = real.samples().get(0);
            ExperienceRef r = first.sample().execution();
            ExperienceSample ghost = SampleCopy.of(first.sample()).execution(new ExperienceRef(r.scope(), r.task(),
                    ExecutionId.of("ghost"), Optional.empty(), r.eventId(), r.revision())).build();
            List<AnnotatedSample> samples = new ArrayList<>(real.samples());
            samples.add(new AnnotatedSample(ghost, first.cost(), AnnotatedSample.Placement.notRequested()));
            s.exports.put("alpha", new ExperienceExportReport(alpha, real.pages(), samples, real.comparison(),
                    real.exportDigest()));

            ForgeGate isolation = ForgeExperienceEvaluator.isolationGate(fixture, s);
            ForgeGate success = ForgeExperienceEvaluator.successGate(fixture, s);
            ForgeGate accounting = ForgeExperienceEvaluator.accountingGate(fixture, s);
            ForgeGate references = ForgeExperienceEvaluator.referenceGate(fixture, s);
            assertFalse(isolation.passed());
            assertTrue(isolation.details().stream().anyMatch(d -> d.contains("ghost")), isolation.details().toString());
            assertFalse(success.passed());
            assertTrue(success.details().stream().anyMatch(d -> d.contains("ghost") && d.contains("unknown")),
                    success.details().toString());
            assertFalse(references.passed());
            assertTrue(accounting.details().stream().noneMatch(d -> d.contains("ghost"))); // reported elsewhere
        } finally {
            s.close();
        }
    }

    @Test
    void aHitAndASampleOfAnUnknownExecutionAreViolationsNotExceptions(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceHit real = store.memory().recall(store.scope(), "Fix null pointer in invoice totals", 3)
                    .hits().get(0);
            ExperienceRef r = real.ref();
            ExperienceHit ghostHit = new ExperienceHit(new ExperienceRef(r.scope(), r.task(),
                    ExecutionId.of("ghost"), r.attempt(), r.eventId(), r.revision()), real.atomId(), real.similarity(),
                    real.event(), real.projectionVersion(), real.coveredSequence());
            ForgeFixture.Query q = fixture.queries().stream().filter(x -> x.id().equals("Q03")).findFirst().orElseThrow();
            List<String> leakage = ForgeExperienceEvaluator.leakageViolations(fixture, q, List.of(ghostHit));
            assertTrue(leakage.stream().anyMatch(v -> v.contains("unknown to the fixture") && v.contains("ghost")),
                    leakage.toString());

            ExperienceSample sample = sampleOf(store.samples(), "a-inv2");
            ExperienceRef sr = sample.execution();
            ExperienceSample ghost = SampleCopy.of(sample).execution(new ExperienceRef(sr.scope(), sr.task(),
                    ExecutionId.of("ghost"), sr.attempt(), sr.eventId(), sr.revision())).build();
            assertEquals(List.of("ghost: execution unknown to the fixture"),
                    ForgeSampleChecks.successViolations(fixture, ghost));
        }
    }

    // --- 3 an empty or repeated requiredGates list is a violation, not an exception ---------------------------------

    @Test
    void anEmptyOrRepeatedRequiredGateListIsReportedWithoutThrowing(@TempDir Path root) {
        try (ProjectStore store = ProjectStore.open(root, fixture)) {
            ExperienceSample inv2 = sampleOf(store.samples(), "a-inv2"); // mandatory: TESTS
            ExperienceSample page = sampleOf(store.samples(), "a-page"); // mandatory: TESTS and ARCHITECTURE
            assertTrue(ForgeSampleChecks.successViolations(fixture, SampleCopy.of(inv2).requiredGates(List.of()).build())
                    .stream().anyMatch(v -> v.contains("required gates []") && v.contains("mandates")));
            assertTrue(ForgeSampleChecks.successViolations(fixture, SampleCopy.of(inv2)
                    .requiredGates(List.of(QualityDimension.TESTS, QualityDimension.TESTS)).build()).stream()
                    .anyMatch(v -> v.contains("required gates")));
            assertTrue(ForgeSampleChecks.successViolations(fixture, SampleCopy.of(page)
                    .requiredGates(List.of(QualityDimension.TESTS)).build()).stream()
                    .anyMatch(v -> v.contains("required gates")));
            assertEquals(List.of(), ForgeSampleChecks.successViolations(fixture, inv2));
        }
    }

    // --- 4 snapshots are closed when a gate (or anything after them) fails --------------------------------------------

    private record Opened(ScopeId scope, ExecutionMemory memory) {
    }

    private List<Opened> capture(ForgeExperienceEvaluator.Snapshot a, ForgeExperienceEvaluator.Snapshot b) {
        List<Opened> opened = new ArrayList<>();
        for (var snapshot : List.of(a, b)) {
            snapshot.memories.forEach((key, memory) -> opened.add(new Opened(
                    ForgeFixtureRecorder.scope(fixture, key), memory)));
        }
        return opened;
    }

    private static void assertClosed(List<Opened> opened) {
        assertFalse(opened.isEmpty());
        for (Opened o : opened) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> o.memory().projectionStatus(o.scope()), "a snapshot memory was left open");
        }
    }

    @Test
    void aFailureWhileComputingTheGatesClosesBothSnapshots() {
        List<Opened> opened = new ArrayList<>();
        ForgeExperienceEvaluator.GateRunner failing = (f, a, b, replay) -> {
            opened.addAll(capture(a, b));
            throw new IllegalStateException("boom");
        };
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ForgeExperienceEvaluator.evaluate(fixture, work, failing));
        assertEquals("boom", e.getMessage());
        assertEquals(4, opened.size()); // two projects in each of the two snapshots
        assertClosed(opened);
    }

    @Test
    void aSuccessfulEvaluationLeavesNoSnapshotOpen() {
        List<Opened> opened = new ArrayList<>();
        ForgeExperienceEvaluator.GateRunner recording = (f, a, b, replay) -> {
            opened.addAll(capture(a, b));
            return ForgeExperienceEvaluator.allGates(f, a, b, replay);
        };
        ForgeEvaluationReport report = ForgeExperienceEvaluator.evaluate(fixture, work, recording);
        assertTrue(report.allGatesPassed(), report.render());
        assertEquals(8, opened.size()); // two runs of two snapshots of two projects
        assertClosed(opened);
    }
}
