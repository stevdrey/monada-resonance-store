package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ScopeId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate checks of the multi-attempt evaluation (issue #121) driven on one recorded store, with forged inputs.
 *
 * <p>MA4: the paged export at the fixed cutoff is checked as strictly as the latest export: against one unpaged
 * export at the same cutoff, sample for sample, and page by page for its snapshot metadata. Its executions are counted,
 * never collapsed into a set, its references must be the exact historical events, and the raw outcome marker of every
 * attempt is compared with its judgment separately from the validated accepted verdict.
 *
 * <p>MA5: every hit must join the sample of its own execution; identical-summary twins never borrow each other's.
 */
class MultiAttemptGateChecksTest {
    @TempDir
    static Path root;

    private static final MultiAttemptFixture FIXTURE = MultiAttemptFixture.load();
    private static ScopeId scope;
    private static ExecutionMemory memory;
    private static long cutoff;
    private static ExperienceExportReport report;

    @BeforeAll
    static void exportAtTheCutoff() {
        scope = MultiAttemptFixtureRecorder.scope(FIXTURE, FIXTURE.cutoffScope());
        for (String scopeKey : FIXTURE.scopeIds().keySet()) { // every project, so a full snapshot can be taken
            ScopeId each = MultiAttemptFixtureRecorder.scope(FIXTURE, scopeKey);
            try (ExecutionMemory writer = ExecutionMemory.open(root, each)) {
                MultiAttemptFixtureRecorder.recordScope(writer, FIXTURE, scopeKey);
                writer.rebuildProjection(each);
            }
        }
        memory = ExecutionMemory.openReadOnly(root, scope);
        cutoff = memory.loadEvent(scope, EventId.of(FIXTURE.cutoffEvent()), 1).orElseThrow().sequence();
        report = ExperienceExportReport.build(memory, ExportRequest.first(scope, ExportCutoff.atSequence(cutoff),
                FIXTURE.pageSize(), ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly()),
                PriceBook.of(List.of(MultiAttemptFixtureRecorder.priceSnapshot(FIXTURE))), Optional.empty());
    }

    @AfterAll
    static void close() {
        memory.close();
    }

    private static List<String> violations(ExperienceExportReport r) {
        return MultiAttemptEvaluator.cutoffPaginationViolations(memory, scope, r, cutoff, FIXTURE.pageSize());
    }

    private static ExperienceExportReport withSamples(List<AnnotatedSample> samples) {
        return new ExperienceExportReport(scope, report.pages(), samples, report.comparison(), report.exportDigest());
    }

    private static ExperienceExportReport withSecondPage(UnaryOperator<ExperienceSamplePage> change) {
        List<ExperienceSamplePage> pages = new ArrayList<>(report.pages());
        pages.set(1, change.apply(pages.get(1)));
        return new ExperienceExportReport(scope, pages, report.samples(), report.comparison(), report.exportDigest());
    }

    @Test
    void theBundledCutoffExportIsConsistent() {
        assertEquals(2, report.pages().size());
        assertEquals(List.of(), violations(report));
    }

    @Test
    void aDuplicatedSampleIsCaughtEvenThoughEveryJudgedAttemptIsPresent() {
        List<AnnotatedSample> duplicated = new ArrayList<>(report.samples());
        duplicated.add(duplicated.get(0));
        assertTrue(violations(withSamples(duplicated)).stream()
                .anyMatch(v -> v.contains("differs from the unpaged cutoff export")));
    }

    @Test
    void aReorderedOrDroppedSampleIsCaught() {
        List<AnnotatedSample> reordered = new ArrayList<>(report.samples());
        java.util.Collections.reverse(reordered);
        assertTrue(violations(withSamples(reordered)).stream()
                .anyMatch(v -> v.contains("differs from the unpaged cutoff export")));
        assertTrue(!violations(withSamples(report.samples().subList(1, report.samples().size()))).isEmpty());
    }

    @Test
    void aPageWithAnotherCheckpointProjectionOrContinuationIsCaught() {
        List<UnaryOperator<ExperienceSamplePage>> variants = List.of(
                p -> new ExperienceSamplePage(p.schema(), p.scope(), p.view(), p.additionalGates(),
                        new ExperienceSamplePage.LedgerCheckpoint(cutoff + 1, p.ledger().prefixDigest()),
                        p.projection(), p.samples(), p.hasMore(), p.next()),
                p -> new ExperienceSamplePage(p.schema(), p.scope(), ExportView.DIAGNOSTIC, p.additionalGates(),
                        p.ledger(), p.projection(), p.samples(), p.hasMore(), p.next()),
                p -> new ExperienceSamplePage(p.schema(), p.scope(), p.view(), p.additionalGates(), p.ledger(),
                        new ExperienceSamplePage.ProjectionStamp(p.projection().formatVersion() + 1,
                                p.projection().textScheme(), p.projection().state(), p.projection().coversCutoff()),
                        p.samples(), p.hasMore(), p.next()),
                p -> new ExperienceSamplePage(p.schema(), p.scope(), p.view(), p.additionalGates(), p.ledger(),
                        p.projection(), p.samples(), true, report.pages().get(0).next())); // last page claims more
        for (int i = 0; i < variants.size(); i++) {
            List<String> v = violations(withSecondPage(variants.get(i)));
            assertTrue(v.stream().anyMatch(x -> x.contains("cutoff page 2/2")), "variant " + i + ": " + v);
        }
    }

    @Test
    void executionsAtTheCutoffAreCountedNotCollapsed() {
        List<ExperienceSample> real = report.samples().stream().map(AnnotatedSample::sample).toList();
        assertEquals(List.of(), MultiAttemptEvaluator.cutoffMembershipViolations(FIXTURE, real));

        // as if the exporter emitted m-repair twice in the paged and the unpaged export alike: every judged attempt
        // is still present, so only the count catches it
        List<ExperienceSample> duplicated = new ArrayList<>(real);
        duplicated.add(real.get(0));
        List<String> v = MultiAttemptEvaluator.cutoffMembershipViolations(FIXTURE, duplicated);
        assertTrue(v.contains("m-repair: exported 2 times at the cutoff, expected once"), v.toString());
        assertTrue(v.contains("m-repair/a1: exported 2 times at the cutoff, expected once"), v.toString());

        List<String> dropped = MultiAttemptEvaluator.cutoffMembershipViolations(FIXTURE, real.subList(1, real.size()));
        assertTrue(dropped.stream().anyMatch(x -> x.contains("but the fixture judges")), dropped.toString());
    }

    @Test
    void theGateComparesTheRawOutcomeMarkerApartFromTheValidatedVerdict() {
        MultiAttemptEvaluator.Snapshot s = MultiAttemptEvaluator.snapshot(FIXTURE, root, new LinkedHashMap<>());
        try {
            assertTrue(MultiAttemptEvaluator.cutoffGate(FIXTURE, s).passed());
            // m-correct is PENDING at the cutoff; marking its a1 accepted cannot change the derived verdict (still
            // false), so only the comparison of the raw marker detects it
            List<AnnotatedSample> forged = new ArrayList<>();
            for (AnnotatedSample a : s.cutoffExport.samples()) {
                forged.add(!a.sample().executionId().value().equals("m-correct") ? a
                        : new AnnotatedSample(ForgeTestSupport.withAttempt(a.sample(), 0, at ->
                        ForgeTestSupport.copyAttempt(at, true, at.previousAttempt(), at.solutionSummary(),
                                at.lessonSummary(), at.evidence())), a.cost(), a.comparison()));
            }
            ExperienceExportReport real = s.cutoffExport;
            s.cutoffExport = new ExperienceExportReport(real.scope(), real.pages(), forged, real.comparison(),
                    real.exportDigest());
            ForgeGate gate = MultiAttemptEvaluator.cutoffGate(FIXTURE, s);
            assertFalse(gate.passed());
            assertTrue(gate.details().contains(
                    "VIOLATION: m-correct/a1: at the cutoff outcome marker true but judged false"), gate.details()
                    .toString());
            assertTrue(gate.details().stream().noneMatch(d -> d.contains(
                    "m-correct/a1: at the cutoff validated accepted attempt")), gate.details().toString());
        } finally {
            s.close();
        }
    }

    @Test
    void aProjectionStampWrongOnEveryPageIsCaughtAgainstTheUnpagedExport() {
        // consistent across pages, so only the comparison with the unpaged export can catch it
        List<ExperienceSamplePage> pages = report.pages().stream().map(p -> new ExperienceSamplePage(p.schema(),
                p.scope(), p.view(), p.additionalGates(), p.ledger(), new ExperienceSamplePage.ProjectionStamp(
                p.projection().formatVersion() + 1, p.projection().textScheme(), p.projection().state(),
                p.projection().coversCutoff()), p.samples(), p.hasMore(), p.next())).toList();
        List<String> v = violations(new ExperienceExportReport(scope, pages, report.samples(), report.comparison(),
                report.exportDigest()));
        assertTrue(v.stream().anyMatch(x -> x.contains("cutoff page 1/2: projection stamp")
                && x.contains("the unpaged cutoff export")), v.toString());
        assertTrue(v.stream().anyMatch(x -> x.contains("cutoff page 2/2")), v.toString());
    }

    @Test
    void cutoffSamplesMustReferenceTheExactHistoricalEvents() {
        MultiAttemptEvaluator.Snapshot s = MultiAttemptEvaluator.snapshot(FIXTURE, root, new LinkedHashMap<>());
        try {
            ExperienceExportReport real = s.cutoffExport;
            assertTrue(MultiAttemptEvaluator.cutoffGate(FIXTURE, s).passed());

            // a1's finish pointing at its own stage event: COMPLETED r1 still matches the judgment, the reference not
            s.cutoffExport = withFinishOfCorrectA1(real, "m-correct.a1.stage", 1);
            ForgeGate stage = MultiAttemptEvaluator.cutoffGate(FIXTURE, s);
            assertFalse(stage.passed());
            assertTrue(stage.details().stream().anyMatch(d -> d.contains("attempt.finish ref m-correct.a1.stage r1")
                    && d.contains("STAGE_RECORDED")), stage.details().toString());

            // a1's finish pointing at the later correction: a fact the historical sample cannot reference
            s.cutoffExport = withFinishOfCorrectA1(real, "m-correct.a1.fin.r2", 2);
            ForgeGate later = MultiAttemptEvaluator.cutoffGate(FIXTURE, s);
            assertFalse(later.passed());
            assertTrue(later.details().stream().anyMatch(d -> d.contains("attempt.finish ref m-correct.a1.fin.r2 r2 "
                    + "lies at sequence") && d.contains("beyond the cutoff " + cutoff)), later.details().toString());
        } finally {
            s.close();
        }
    }

    /** The cutoff export with the finish reference of m-correct a1 replaced. */
    private static ExperienceExportReport withFinishOfCorrectA1(ExperienceExportReport real, String eventId,
                                                                int revision) {
        List<AnnotatedSample> forged = new ArrayList<>();
        for (AnnotatedSample a : real.samples()) {
            forged.add(!a.sample().executionId().value().equals("m-correct") ? a
                    : new AnnotatedSample(ForgeTestSupport.withAttempt(a.sample(), 0, at -> ForgeTestSupport
                    .withFinish(at, ForgeTestSupport.ref(at.finish().orElseThrow(), eventId, revision))), a.cost(),
                    a.comparison()));
        }
        return new ExperienceExportReport(real.scope(), real.pages(), forged, real.comparison(),
                real.exportDigest());
    }

    @Test
    void aHitJoinedToItsTwinsSampleFailsIsolation() {
        MultiAttemptEvaluator.Snapshot s = MultiAttemptEvaluator.snapshot(FIXTURE, root, new LinkedHashMap<>());
        try {
            assertTrue(MultiAttemptEvaluator.isolationGate(FIXTURE, s).passed());
            // M05 recalls both twins through one shared atom; let m-twin-ko/a1 borrow m-twin-ok's sample and verdict
            List<AttemptJoin.Row> rows = new ArrayList<>(s.latestRows.get("M05"));
            int index = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).experienceId().equals("m-twin-ko/a1")) {
                    index = i;
                }
            }
            assertTrue(index >= 0, "M05 must recall m-twin-ko/a1");
            AttemptJoin.Row r = rows.get(index);
            rows.set(index, new AttemptJoin.Row(r.experienceId(), r.eventId(), r.revision(), r.sequence(), r.status(),
                    "m-twin-ok", "SUCCESS", "VALIDATED_ACCEPTED", "COMPLETED", true, true));
            s.latestRows.put("M05", rows);
            ForgeGate gate = MultiAttemptEvaluator.isolationGate(FIXTURE, s);
            assertFalse(gate.passed());
            assertTrue(gate.details().contains("VIOLATION: M05: hit on m-twin-ko joined the sample of m-twin-ok"),
                    gate.details().toString());
            assertTrue(gate.details().stream().anyMatch(d -> d.contains("m-twin-ko/a1 of m-twin-ok/m-twin-ko joined")
                    && d.contains("expected its own sample labelled FAILURE")), gate.details().toString());
        } finally {
            s.close();
        }
    }

    @Test
    void aSinglePageNeverExercisesTheCursor() {
        ExperienceExportReport single = ExperienceExportReport.build(memory, ExportRequest.first(scope,
                ExportCutoff.atSequence(cutoff), 50, ExportView.ADAPTER_READY,
                ExportEvidencePolicy.executionPolicyOnly()), PriceBook.empty(), Optional.empty());
        assertTrue(violations(single).stream().anyMatch(v -> v.contains("used 1 pages")));
    }
}
