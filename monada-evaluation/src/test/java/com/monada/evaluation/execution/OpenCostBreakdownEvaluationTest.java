package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ProjectionStatus;
import com.monada.core.execution.EventKind;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkEvaluator.LedgerCorpus;
import com.monada.evaluation.execution.OpenCostBreakdownEvaluator.Config;
import com.monada.evaluation.execution.OpenCostBreakdownEvaluator.RootFingerprint;
import com.monada.evaluation.execution.OpenCostBreakdownReport.SizeResult;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
            assertEquals(Map.of("full root writable", Set.of(ProjectionStatus.State.CURRENT),
                    "full root read-only", Set.of(ProjectionStatus.State.CURRENT),
                    "copy writable", Set.of(ProjectionStatus.State.MISSING),
                    "copy read-only", Set.of(ProjectionStatus.State.MISSING)), s.projectionStates());
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
        assertTrue(Files.isRegularFile(copy.resolve("write.lock")), "the lock file is copied, never created by a "
                + "timed open");
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
    void theContentDigestSeesASameSizeRewrite() throws IOException {
        LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, 30);
        Path root = Files.createDirectories(work.resolve("digest-source"));
        try (ExecutionMemory memory = ExecutionMemory.open(root, corpus.scope())) {
            corpus.events().forEach(memory::record);
        }
        ProjectionLayout layout = ProjectionLayout.of(root, corpus.scope());
        Path dir = layout.currentDir().orElseThrow();
        String generation = layout.generation();
        String before = LedgerGrowthBenchmarkEvaluator.treeDigest(dir);
        assertEquals(before, LedgerGrowthBenchmarkEvaluator.treeDigest(dir));

        Path checkpoint = layout.checkpoint(dir);
        byte[] bytes = Files.readAllBytes(checkpoint);
        bytes[bytes.length / 2] ^= 1;
        Files.write(checkpoint, bytes);

        assertEquals(generation, layout.generation(), "the size fingerprint alone misses a same-size rewrite");
        assertNotEquals(before, LedgerGrowthBenchmarkEvaluator.treeDigest(dir));
    }

    @Test
    void relativeAndUnnormalizedRootsAreCopiedWithoutTheProjection() throws IOException {
        LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, 30);
        Path root = Files.createDirectories(work.resolve("relative-source"));
        try (ExecutionMemory memory = ExecutionMemory.open(root, corpus.scope())) {
            corpus.events().forEach(memory::record);
        }
        Path relative = Path.of("").toAbsolutePath().relativize(root.toAbsolutePath());
        Path unnormalized = root.resolve("..").resolve(root.getFileName());
        int i = 0;
        for (Path source : List.of(relative, unnormalized)) {
            Path copy = Files.createDirectories(work.resolve("relative-target-" + i++));
            OpenCostBreakdownEvaluator.copyWithoutProjection(source, corpus.scope(), copy);
            assertTrue(ProjectionLayout.of(copy, corpus.scope()).currentDir().isEmpty(), source.toString());
            assertEquals(List.of(), ProjectionLayout.of(copy, corpus.scope()).generationDirs(), source.toString());
            assertEquals(LedgerGrowthBenchmarkEvaluator.ledgerDigest(root, corpus.scope()),
                    LedgerGrowthBenchmarkEvaluator.ledgerDigest(copy, corpus.scope()));
        }
    }

    @Test
    void theHardChecksFailOnALeftoverProjectionAChangedLedgerOrARewrittenProjection() throws IOException {
        LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, 30);
        Path root = Files.createDirectories(work.resolve("violations-source"));
        try (ExecutionMemory memory = ExecutionMemory.open(root, corpus.scope())) {
            corpus.events().forEach(memory::record);
        }
        Path copy = Files.createDirectories(work.resolve("violations-copy"));
        OpenCostBreakdownEvaluator.copyWithoutProjection(root, corpus.scope(), copy);
        RootFingerprint full = RootFingerprint.of(root, corpus.scope());
        RootFingerprint bare = RootFingerprint.of(copy, corpus.scope());
        assertEquals(List.of(), OpenCostBreakdownEvaluator.copyViolations("N=30", full, bare));
        assertEquals(List.of(), OpenCostBreakdownEvaluator.writeViolations("N=30", full, full, true));

        // B1: a "copy" that still holds the projection
        assertEquals(List.of("N=30: the copy still holds a projection"),
                OpenCostBreakdownEvaluator.copyViolations("N=30", full, full));

        // B3: a same-size rewrite of the current projection generation
        ProjectionLayout layout = ProjectionLayout.of(root, corpus.scope());
        Path checkpoint = layout.checkpoint(layout.currentDir().orElseThrow());
        flipMiddleByte(checkpoint);
        RootFingerprint rewritten = RootFingerprint.of(root, corpus.scope());
        assertEquals(List.of("N=30: the content of the current projection generation changed during the opens"),
                OpenCostBreakdownEvaluator.writeViolations("N=30", full, rewritten, true));
        assertEquals(List.of(), OpenCostBreakdownEvaluator.writeViolations("N=30", full, rewritten, false),
                "the copy's check covers the ledger only");

        // B1 and B3: a same-size rewrite of the copy's ledger
        Path segment;
        try (var files = Files.walk(LedgerGrowthBenchmarkEvaluator.ledgerDir(copy, corpus.scope()))) {
            segment = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        flipMiddleByte(segment);
        RootFingerprint changed = RootFingerprint.of(copy, corpus.scope());
        assertEquals(1, OpenCostBreakdownEvaluator.copyViolations("N=30", full, changed).size());
        assertEquals(List.of("copy: the ledger segment changed during the opens"),
                OpenCostBreakdownEvaluator.writeViolations("copy", bare, changed, false));
    }

    private static void flipMiddleByte(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);
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
