package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceSample;
import com.monada.api.execution.ExperienceSamplePage;
import com.monada.api.execution.ExportCutoff;
import com.monada.api.execution.ExportEvidencePolicy;
import com.monada.api.execution.ExportRequest;
import com.monada.api.execution.ExportView;
import com.monada.api.execution.HistoryEntry;
import com.monada.core.execution.EventKind;
import com.monada.core.execution.ExecutionEvent;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkEvaluator.Config;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkEvaluator.LedgerCorpus;
import com.monada.evaluation.execution.LedgerGrowthBenchmarkReport.SizeResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end ledger growth benchmark (issue #126) at small sizes: every hard check passes at every size, two fresh
 * runs render the same deterministic report, timing stays out of it, the corpus is cut at exact event counts, the
 * structure matches it, the checks detect reordered, missing and duplicated entries, samples and pages, and invalid
 * configurations are rejected.
 */
class LedgerGrowthBenchmarkEvaluationTest {
    private static final Config SMALL = new Config(42, List.of(20, 60, 150), List.of(1, 7, 50), List.of(1, 100, 500),
            0, 1);

    @TempDir
    static Path work;

    private static LedgerGrowthBenchmarkReport report;

    @BeforeAll
    static void evaluate() {
        report = LedgerGrowthBenchmarkEvaluator.evaluate(SMALL, work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesAtEverySize() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(LedgerGrowthBenchmarkReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(LedgerGrowthBenchmarkReport.Decision.LEDGER_GROWTH_CHARACTERIZED, report.decision());
        assertEquals(SMALL.sizes(), report.sizes().stream().map(SizeResult::events).toList());
    }

    @Test
    void twoFreshRunsRenderTheSameReportAndTimingIsKeptOut() {
        LedgerGrowthBenchmarkReport second = LedgerGrowthBenchmarkEvaluator.evaluate(SMALL, work.resolve("second"));
        assertEquals(report.render(), second.render());
        assertFalse(report.render().contains(" ms"));
        assertFalse(report.render().contains(" us"));
        assertFalse(report.render().contains("p95"));
        String timing = report.renderTiming();
        assertTrue(timing.startsWith("== Elapsed timing (nondeterministic; excluded from every gate) =="));
        for (String expected : List.of("java.version", "work dir file store", "append (ExecutionMemory.record)",
                "writer reopen (ExecutionMemory.open)", "reader open (ExecutionLedgerReader.open)",
                "loadEvent exact (per lookup)", "history full replay ps=500", "export page ps=7", "Growth exponent",
                "N=60->150")) {
            assertTrue(timing.contains(expected), "timing lacks " + expected);
        }
    }

    @Test
    void theCorpusIsCutAtExactEventCountsAndTheTailContinuesIt() {
        for (int n : List.of(10, 20, 150, 1000)) {
            LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, n);
            assertEquals(n, corpus.size());
            assertEquals(LedgerGrowthBenchmarkEvaluator.tailSize(n), corpus.tail().size());
            assertEquals(corpus, LedgerGrowthBenchmarkEvaluator.corpus(42, n), "same seed and size, same corpus");
            List<ExecutionEvent> source = ExecutionScaleCorpusGenerator.generate(42, corpus.sourceExperiences())
                    .scopes().get(0).events();
            List<ExecutionEvent> both = new ArrayList<>(corpus.events());
            both.addAll(corpus.tail());
            assertEquals(source.subList(0, n + corpus.tail().size()), both);
            assertTrue(both.stream().allMatch(e -> e.scopeId().equals(corpus.scope())));
            assertEquals(both.size(), both.stream().map(ExecutionEvent::eventId).distinct().count());
            List<HistoryEntry> history = corpus.expectedHistory();
            assertEquals(1, history.get(0).sequence());
            assertEquals(n, history.get(n - 1).sequence());
        }
        assertNotEquals(LedgerGrowthBenchmarkEvaluator.corpus(42, 60).events(),
                LedgerGrowthBenchmarkEvaluator.corpus(43, 60).events());
        assertEquals(1, LedgerGrowthBenchmarkEvaluator.tailSize(10));
        assertEquals(500, LedgerGrowthBenchmarkEvaluator.tailSize(5000));
    }

    @Test
    void theStructureMatchesTheCorpusAtEverySize() {
        for (SizeResult s : report.sizes()) {
            LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(SMALL.seed(), s.events());
            List<ExecutionEvent> all = new ArrayList<>(corpus.events());
            all.addAll(corpus.tail());
            assertEquals(corpus.tail().size(), s.tailEvents());
            assertEquals(corpus.sourceExperiences(), s.sourceExperiences());
            assertEquals(LedgerGrowthBenchmarkEvaluator.executions(corpus.events()), s.executions());
            assertEquals(LedgerGrowthBenchmarkEvaluator.executions(all), s.executionsAfterTail());
            assertEquals(s.events(), s.kinds().values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(Set.of(EventKind.values()), s.kinds().keySet());
            assertEquals(LedgerGrowthBenchmarkEvaluator.idempotencyProbes(corpus.events()).size(),
                    s.idempotencyProbes());
            assertEquals(s.executions(), s.samples(), "one sample per execution");
            assertTrue(s.segmentBytesAfterTail() > s.segmentBytes());
            assertEquals(16, s.segmentDigest().length());
            for (int pageSize : SMALL.exportPageSizes()) {
                assertEquals(Math.max(1, (s.samples() + pageSize - 1) / pageSize), s.exportPages().get(pageSize));
            }
            for (int pageSize : SMALL.historyPageSizes()) {
                assertEquals((s.events() + pageSize - 1) / pageSize, s.historyPages().get(pageSize));
            }
        }
        assertTrue(report.sizes().stream().anyMatch(s -> s.kinds().get(EventKind.CORRECTION) > 0),
                "corrected revisions are part of the measured shapes");
        assertTrue(report.sizes().stream().anyMatch(s -> s.kinds().get(EventKind.ATTEMPT_STARTED)
                > s.executions()), "multi-attempt executions are part of the measured shapes");
        assertTrue(report.gates().stream().filter(g -> g.id().equals(LedgerGrowthBenchmarkReport.EXACT_REFERENCES))
                .findFirst().orElseThrow().details().get(0).matches("exact loads: \\d+; sample ref resolutions "
                        + "checked: \\d+; distinct sample refs per size: N=20:\\d+ N=60:\\d+ N=150:\\d+"));
    }

    @Test
    void theEvidenceCountsEveryReopenedFacadeAndEveryResumeOnTheNewSnapshot() {
        int rounds = SMALL.warmup() + SMALL.iterations();
        // per size and round: writable reopen, read-only open, ExecutionLedger and ExecutionLedgerReader replays;
        // per size: the read-only snapshot opened after the tail
        assertEquals("replays compared: " + (4 * rounds + 1) * SMALL.sizes().size() + "; history traversals compared: "
                        + rounds * SMALL.sizes().size() * SMALL.historyPageSizes().size(),
                gate(LedgerGrowthBenchmarkReport.REPLAY_FIDELITY).details().get(0));
        // every pre-tail export cursor and the pre-tail history cursor, on the writer and on the new snapshot
        int resumed = 0;
        for (SizeResult s : report.sizes()) {
            resumed += 2 * (s.exportPages().values().stream().mapToInt(p -> p - 1).sum() + 1);
        }
        assertTrue(gate(LedgerGrowthBenchmarkReport.CUTOFF_STABILITY).details().get(0)
                .endsWith("; resumed cursors: " + resumed));
    }

    @Test
    void idempotencyProbesAreCappedEvenlySpacedAndIncludeEveryCorrection() {
        for (int n : List.of(20, 60, 99, 1000)) {
            List<ExecutionEvent> events = LedgerGrowthBenchmarkEvaluator.corpus(42, n).events();
            Set<Integer> probes = LedgerGrowthBenchmarkEvaluator.idempotencyProbes(events);
            Set<Integer> corrections = new HashSet<>();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).kind() == EventKind.CORRECTION) {
                    corrections.add(i);
                }
            }
            int spaced = Math.min(LedgerGrowthBenchmarkEvaluator.IDEMPOTENCY_PROBES, n);
            Set<Integer> regular = new HashSet<>();
            for (int k = 0; k < spaced; k++) {
                regular.add((k + 1) * n / spaced - 1);
            }
            assertEquals(spaced, regular.size(), "N=" + n + ": the spaced probes are distinct");
            Set<Integer> expected = new HashSet<>(regular);
            expected.addAll(corrections);
            assertEquals(expected, probes, "N=" + n);
            assertTrue(probes.contains(n - 1), "the last event is always probed at N=" + n);
        }
    }

    private static ForgeGate gate(String id) {
        return report.gates().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void idempotencyProbesAreEvenlySpacedAndIncludeEveryCorrection() {
        List<ExecutionEvent> events = LedgerGrowthBenchmarkEvaluator.corpus(42, 1000).events();
        Set<Integer> probes = LedgerGrowthBenchmarkEvaluator.idempotencyProbes(events);
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).kind() == EventKind.CORRECTION) {
                assertTrue(probes.contains(i), "correction " + i + " is re-recorded");
            }
        }
        assertTrue(probes.contains(19) && probes.contains(999));
        assertEquals(probes.stream().sorted().toList(), new ArrayList<>(probes));
    }

    @Test
    void theChecksDetectBrokenHistoriesSamplesAndPages() {
        LedgerCorpus corpus = LedgerGrowthBenchmarkEvaluator.corpus(42, 60);
        Path root = work.resolve("mutations");
        try (ExecutionMemory memory = ExecutionMemory.open(root, corpus.scope())) {
            ForgeFixtureRecorder.record(memory, corpus.events());
            List<HistoryEntry> expected = corpus.expectedHistory();
            assertEquals(List.of(), LedgerGrowthBenchmarkEvaluator.historyViolations("ok", expected,
                    memory.history(corpus.scope(), null, 500).entries()));
            List<HistoryEntry> swapped = new ArrayList<>(expected);
            Collections.swap(swapped, 3, 4);
            assertFalse(LedgerGrowthBenchmarkEvaluator.historyViolations("swapped", expected, swapped).isEmpty());
            assertFalse(LedgerGrowthBenchmarkEvaluator.historyViolations("short", expected,
                    expected.subList(0, 59)).isEmpty());

            List<ExperienceSamplePage> pages = LedgerGrowthBenchmarkEvaluator.exportAll(memory, pinned(corpus, 3),
                    new ArrayList<>());
            List<ExperienceSamplePage> onePage = LedgerGrowthBenchmarkEvaluator.exportAll(memory, pinned(corpus, 500),
                    new ArrayList<>());
            List<ExperienceSample> samples = LedgerGrowthBenchmarkEvaluator.flatten(pages);
            assertTrue(samples.size() >= 3);
            assertEquals(List.of(), LedgerGrowthBenchmarkEvaluator.sampleEqualityViolations("ok", samples,
                    LedgerGrowthBenchmarkEvaluator.flatten(onePage)));
            assertEquals(LedgerGrowthBenchmarkEvaluator.samplesDigest(pages),
                    LedgerGrowthBenchmarkEvaluator.samplesDigest(onePage), "the samples digest ignores paging");
            assertNotEquals(LedgerGrowthBenchmarkEvaluator.exportDigest(pages),
                    LedgerGrowthBenchmarkEvaluator.exportDigest(onePage), "the export digest covers the pages");
            assertEquals(List.of(), LedgerGrowthBenchmarkEvaluator.sampleOrderViolations("ok", samples));

            List<ExperienceSample> reordered = new ArrayList<>(samples);
            Collections.swap(reordered, 0, 1);
            assertFalse(LedgerGrowthBenchmarkEvaluator.sampleEqualityViolations("reordered", samples, reordered)
                    .isEmpty());
            assertFalse(LedgerGrowthBenchmarkEvaluator.sampleOrderViolations("reordered", reordered).isEmpty());
            List<ExperienceSample> missing = samples.subList(1, samples.size());
            assertFalse(LedgerGrowthBenchmarkEvaluator.sampleEqualityViolations("missing", samples, missing)
                    .isEmpty());
            List<ExperienceSample> duplicated = new ArrayList<>(samples);
            duplicated.add(1, samples.get(0));
            assertFalse(LedgerGrowthBenchmarkEvaluator.sampleOrderViolations("duplicated", duplicated).isEmpty());

            assertEquals(List.of(), LedgerGrowthBenchmarkEvaluator.pageDigestViolations("ok", pages,
                    LedgerGrowthBenchmarkEvaluator.exportAll(memory, pinned(corpus, 3), new ArrayList<>())));
            assertFalse(LedgerGrowthBenchmarkEvaluator.pageDigestViolations("other paging", pages, onePage)
                    .isEmpty());
            List<ExperienceSamplePage> dropped = pages.subList(0, pages.size() - 1);
            assertFalse(LedgerGrowthBenchmarkEvaluator.pageDigestViolations("dropped", pages, dropped).isEmpty());

            Set<String> refs = new HashSet<>();
            samples.forEach(s -> LedgerGrowthBenchmarkEvaluator.refsOf(s).forEach(r -> refs.add(
                    r.ref().eventId().value())));
            assertTrue(refs.size() > samples.size(), "attempt, stage and outcome refs are collected, not only "
                    + "execution refs");
        }
    }

    @Test
    void aHistoryPageLargerThanTheLedgerStillResumesThePreTailHistoryCursor() {
        Config wide = new Config(42, List.of(10, 15), List.of(1), List.of(500), 0, 1);
        LedgerGrowthBenchmarkReport r = LedgerGrowthBenchmarkEvaluator.evaluate(wide, work.resolve("wide"));
        assertEquals(List.of(), r.failedGates(), r.render());
        int resumed = 0;
        for (SizeResult s : r.sizes()) {
            assertEquals(1, s.historyPages().get(500), "the whole history fits in one page");
            // export cursors (pages - 1) plus the history cursor, on the writer and on the snapshot
            resumed += 2 * (s.exportPages().get(1) - 1 + 1);
        }
        assertTrue(r.gates().stream().filter(g -> g.id().equals(LedgerGrowthBenchmarkReport.CUTOFF_STABILITY))
                .findFirst().orElseThrow().details().get(0).endsWith("; resumed cursors: " + resumed),
                "the pre-tail history cursor is resumed on the writer and the snapshot at every size");
    }

    @Test
    void theSingleScopeGeneratorMatchesTheFullCorpus() {
        for (int experiences : List.of(7, 41, 275)) {
            var full = ExecutionScaleCorpusGenerator.generate(42, experiences).scopes();
            for (int i = 0; i < full.size(); i++) {
                assertEquals(full.get(i), ExecutionScaleCorpusGenerator.generateScope(42, experiences, i));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleCorpusGenerator.generateScope(42, 6, 0));
    }

    @Test
    void theRunnerPrintsTheReportThenTimingAndExitsZero() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = LedgerGrowthBenchmarkMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new Config(1, List.of(10, 12), List.of(1), List.of(5), 0, 1));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        int decision = out.indexOf("LEDGER_GROWTH_CHARACTERIZED");
        assertTrue(decision > 0 && decision < out.indexOf("== Elapsed timing"), out);
    }

    @Test
    void parsingAppliesDefaultsSortsAndRejectsInvalidOptions() {
        assertEquals(Config.defaults(), LedgerGrowthBenchmarkMain.parse());
        assertEquals(List.of(100, 1000, 5000), Config.defaults().sizes());
        assertEquals(List.of(1, 50, 100), Config.defaults().exportPageSizes());
        Config parsed = LedgerGrowthBenchmarkMain.parse("--seed=7", "--sizes=500, 50", "--exportPageSizes=100,1",
                "--historyPageSizes=500,1", "--warmup=0", "--iterations=3");
        assertEquals(new Config(7, List.of(50, 500), List.of(1, 100), List.of(1, 500), 0, 3), parsed);
        for (String bad : List.of("--sizes=", "--sizes=20,20", "--sizes=9", "--sizes=1000001", "--exportPageSizes=0",
                "--exportPageSizes=501", "--historyPageSizes=", "--warmup=-1", "--iterations=0", "--other=1",
                "sizes")) {
            assertThrows(IllegalArgumentException.class, () -> LedgerGrowthBenchmarkMain.parse(bad), bad);
        }
    }

    private static ExportRequest pinned(LedgerCorpus corpus, int pageSize) {
        return ExportRequest.first(corpus.scope(), ExportCutoff.atSequence(corpus.size()), pageSize,
                ExportView.ADAPTER_READY, ExportEvidencePolicy.executionPolicyOnly());
    }
}
