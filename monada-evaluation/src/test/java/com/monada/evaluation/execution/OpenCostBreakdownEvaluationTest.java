package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ProjectionStatus;
import com.monada.core.execution.EventKind;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkEvaluator.LedgerCorpus;
import com.monada.evaluation.execution.OpenCostBreakdownEvaluator.Config;
import com.monada.evaluation.execution.OpenCostBreakdownReport.SizeResult;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end open-cost breakdown (issue #156) at small sizes: every hard check passes at every size, two fresh runs
 * render the same deterministic report, timing and the split stay out of it, the projection-less copy keeps the
 * ledger bytes and opens MISSING without creating a projection, and invalid configurations are rejected.
 */
class OpenCostBreakdownEvaluationTest {
    private static final Config SMALL = new Config(42, List.of(50, 200), 0, 1);

    @TempDir
    static Path work;

    private static OpenCostBreakdownReport report;

    @BeforeAll
    static void evaluate() {
        report = OpenCostBreakdownEvaluator.evaluate(SMALL, work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesAtEverySize() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(OpenCostBreakdownReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(OpenCostBreakdownReport.Decision.OPEN_COST_CHARACTERIZED, report.decision());
        assertEquals(SMALL.sizes(), report.sizes().stream().map(SizeResult::events).toList());
    }

    @Test
    void twoFreshRunsRenderTheSameReportAndTimingIsKeptOut() {
        OpenCostBreakdownReport second = OpenCostBreakdownEvaluator.evaluate(SMALL, work.resolve("second"));
        assertEquals(report.render(), second.render());
        assertFalse(report.render().contains(" ms"));
        assertFalse(report.render().contains(" us"));
        assertFalse(report.render().contains("p95"));
        String timing = report.renderTiming();
        assertTrue(timing.startsWith("== Elapsed timing (nondeterministic; excluded from every gate) =="));
        for (String expected : List.of("java.version", "work dir file store", OpenCostBreakdownEvaluator.LEDGER_SCAN,
                OpenCostBreakdownEvaluator.READER_SCAN, OpenCostBreakdownEvaluator.WRITABLE_NO_PROJECTION,
                OpenCostBreakdownEvaluator.READ_ONLY_FULL, "Open split by difference of medians", "read-only",
                "heap proxy", "projection on disk", "Growth exponent", "N=50->200", "writable split: projection")) {
            assertTrue(timing.contains(expected), "timing lacks " + expected);
        }
    }

    @Test
    void theStructureMatchesTheCorpusAtEverySize() {
        for (SizeResult s : report.sizes()) {
            LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(SMALL.seed(), s.events());
            assertEquals(corpus.sourceExperiences(), s.sourceExperiences());
            assertEquals(LedgerGrowthBenchmarkEvaluator.executions(corpus.events()), s.executions());
            assertEquals(s.events(), s.kinds().values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(Set.of(EventKind.values()), s.kinds().keySet());
            assertEquals(16, s.segmentDigest().length());
            assertTrue(s.projectionGeneration().startsWith(ProjectionLayout.GENERATION_PREFIX));
        }
    }

    @Test
    void theCopyKeepsTheLedgerAndOpensWithoutAProjection() throws IOException {
        LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, 60);
        Path root = Files.createDirectories(work.resolve("copy-source"));
        try (ExecutionMemory memory = ExecutionMemory.open(root, corpus.scope())) {
            corpus.events().forEach(memory::record);
        }
        String generation = ProjectionLayout.of(root, corpus.scope()).generation();
        Path copy = Files.createDirectories(work.resolve("copy-target"));
        OpenCostBreakdownEvaluator.copyWithoutProjection(root, corpus.scope(), copy);

        assertEquals(LedgerGrowthBenchmarkEvaluator.ledgerDigest(root, corpus.scope()),
                LedgerGrowthBenchmarkEvaluator.ledgerDigest(copy, corpus.scope()));
        ProjectionLayout copied = ProjectionLayout.of(copy, corpus.scope());
        assertTrue(copied.currentDir().isEmpty());
        assertEquals(List.of(), copied.generationDirs());
        assertFalse(Files.exists(copy.resolve("write.lock")));
        try (ExecutionMemory memory = ExecutionMemory.open(copy, corpus.scope())) {
            assertEquals(ProjectionStatus.State.MISSING, memory.projectionStatus(corpus.scope()).state());
            assertEquals(corpus.size(), memory.history(corpus.scope()).highWatermark());
        }
        try (ExecutionMemory memory = ExecutionMemory.openReadOnly(copy, corpus.scope())) {
            assertEquals(ProjectionStatus.State.MISSING, memory.projectionStatus(corpus.scope()).state());
        }
        assertTrue(ProjectionLayout.of(copy, corpus.scope()).generationDirs().isEmpty(),
                "a writable open of a non-empty ledger never builds a projection");
        assertEquals(generation, ProjectionLayout.of(root, corpus.scope()).generation(), "the source is only read");
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Config(42, List.of(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Config(42, List.of(50, 50), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Config(42, List.of(5), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Config(42, List.of(50), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Config(42, List.of(50), 0, 0));
        assertEquals(List.of(50, 200), new Config(42, List.of(200, 50), 0, 1).sizes());
        assertThrows(IllegalArgumentException.class, () -> OpenCostBreakdownMain.parse("--bogus=1"));
        assertEquals(new Config(7, List.of(50, 200), 1, 3),
                OpenCostBreakdownMain.parse("--seed=7", "--sizes=200,50", "--warmup=1", "--iterations=3"));
        assertEquals(Config.defaults(), OpenCostBreakdownMain.parse());
    }
}
