package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ScopeId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The paged export at the fixed cutoff (MA4, issue #121) is checked as strictly as the latest export: against one
 * unpaged export at the same cutoff, sample for sample, and page by page for its snapshot metadata.
 */
class MultiAttemptCutoffPaginationTest {
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
        try (ExecutionMemory writer = ExecutionMemory.open(root, scope)) {
            MultiAttemptFixtureRecorder.recordScope(writer, FIXTURE, FIXTURE.cutoffScope());
            writer.rebuildProjection(scope);
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
    void aSinglePageNeverExercisesTheCursor() {
        ExperienceExportReport single = ExperienceExportReport.build(memory, ExportRequest.first(scope,
                ExportCutoff.atSequence(cutoff), 50, ExportView.ADAPTER_READY,
                ExportEvidencePolicy.executionPolicyOnly()), PriceBook.empty(), Optional.empty());
        assertTrue(violations(single).stream().anyMatch(v -> v.contains("used 1 pages")));
    }
}
