package com.monada.evaluation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.api.execution.ExecutionMemory;
import com.monada.api.execution.ExperienceHit;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.Query;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.QueryKind;
import com.monada.evaluation.execution.ExecutionScaleCorpusGenerator.ScopeCorpus;
import com.monada.evaluation.execution.ExecutionScaleSweepEvaluator.Config;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.ScopeStructure;
import com.monada.evaluation.execution.ExecutionScaleSweepReport.SizeResult;
import com.monada.storage.execution.ProjectionCheckpoint;
import com.monada.storage.execution.ProjectionLayout;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end scale sweep (issue #125) at small sizes: every hard check passes at every size, two fresh runs render
 * the same deterministic report, timing stays out of it, the structure matches the generated corpus, the checks
 * detect broken tie order and split atoms, and invalid configurations are rejected.
 */
class ExecutionScaleSweepEvaluationTest {
    private static final Config SMALL = new Config(42, List.of(10, 25, 50), List.of(1, 5, 20), 1, 2);

    @TempDir
    static Path work;

    private static ExecutionScaleSweepReport report;

    @BeforeAll
    static void evaluate() {
        report = ExecutionScaleSweepEvaluator.evaluate(SMALL, work.resolve("first"));
    }

    @Test
    void everyHardCheckPassesAtEverySize() {
        assertEquals(List.of(), report.failedGates(), report.render());
        assertEquals(ExecutionScaleSweepReport.expectedGateIds(),
                report.gates().stream().map(ForgeGate::id).collect(Collectors.toSet()));
        assertEquals(ExecutionScaleSweepReport.Decision.SCALE_SWEEP_CHARACTERIZED, report.decision());
        assertEquals(SMALL.sizes(), report.sizes().stream().map(SizeResult::experiencesPerScope).toList());
    }

    @Test
    void twoFreshRunsRenderTheSameReportAndTimingIsKeptOut() {
        ExecutionScaleSweepReport second = ExecutionScaleSweepEvaluator.evaluate(SMALL, work.resolve("second"));
        assertEquals(report.render(), second.render());
        assertFalse(report.render().contains(" ms"));
        assertFalse(report.render().contains("p95"));
        assertTrue(report.renderTiming().startsWith("== Elapsed timing (nondeterministic; excluded from every "
                + "gate) =="));
        assertTrue(report.renderTiming().contains("java.version"));
        for (int k : SMALL.ks()) {
            assertTrue(report.renderTiming().contains("recall K=" + k));
        }
    }

    @Test
    void theStructureMatchesTheGeneratedCorpusAtEverySize() {
        for (SizeResult size : report.sizes()) {
            List<ScopeCorpus> corpus = ExecutionScaleCorpusGenerator.generate(SMALL.seed(),
                    size.experiencesPerScope()).scopes();
            assertEquals(corpus.size(), size.scopes().size());
            for (int i = 0; i < corpus.size(); i++) {
                ScopeCorpus expected = corpus.get(i);
                ScopeStructure s = size.scopes().get(i);
                assertEquals(expected.scope().value(), s.scope());
                assertEquals(expected.events().size(), s.ledgerEvents());
                assertEquals(size.experiencesPerScope(), s.activeAttempts());
                assertEquals(size.experiencesPerScope(), s.projectedRefs());
                assertEquals(expected.liveTexts().size(), s.liveAtoms());
                assertEquals(List.of(expected.retiredTexts().size(), expected.retiredTexts().size(),
                        expected.retiredTexts().size(), expected.retiredTexts().size()), s.retiredAtomsPerPhase());
                assertEquals(expected.retiredTexts().size(), s.retiredAtomsPublished());
                assertEquals(ProjectionCheckpoint.PROJECTION_DIMENSIONS, s.dimensions());
                assertEquals(s.liveAtoms() + s.retiredAtomsPublished(), s.structuralCandidates());
                assertEquals(List.of("append=projection-000001", "rebuild=projection-000002",
                        "second rebuild=projection-000003", "reopen=projection-000003"), s.generations());
            }
        }
    }

    @Test
    void theGateEvidenceSeparatesHitsFromResolvedRefsAndNamesTheLimit() {
        ForgeGate references = report.gates().stream()
                .filter(g -> g.id().equals(ExecutionScaleSweepReport.EXACT_REFERENCES)).findFirst().orElseThrow();
        assertTrue(references.name().contains("never more than K hits"), references.name());
        String evidence = references.details().get(0);
        assertTrue(evidence.matches("hits checked: \\d+; distinct refs resolved against the ledger: \\d+"), evidence);
        String[] counts = evidence.replaceAll("[^0-9 ]", "").trim().split(" +");
        assertTrue(Integer.parseInt(counts[1]) < Integer.parseInt(counts[0]), "refs are resolved once, not per hit");
        assertTrue(report.renderTiming().contains("opening 3 read-only snapshots"));
    }

    @Test
    void everySizeReportsTheSameQuerySet() {
        assertEquals(1, report.sizes().stream().map(SizeResult::queryDigest).distinct().count());
        assertTrue(report.render().contains("query set sha256=" + report.sizes().get(0).queryDigest()));
        assertTrue(report.render().contains("the K order rotates by one every round"));
    }

    @Test
    void latencySamplesCoverEveryQueryAndIterationForEveryK() {
        for (ExecutionScaleSweepReport.SizeTiming t : report.timings()) {
            assertEquals(SMALL.ks(), List.copyOf(t.recallLatency().keySet()));
            t.recallLatency().values().forEach(s -> assertEquals(30 * SMALL.iterations(), s.sampleCount()));
        }
    }

    @Test
    void theRunnerPrintsTheReportThenTimingAndExitsZero() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = ExecutionScaleSweepMain.run(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new Config(1, List.of(7, 8, 9), List.of(1), 0, 1));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, out);
        assertTrue(out.indexOf("SCALE_SWEEP_CHARACTERIZED") < out.indexOf("== Elapsed timing"));
    }

    @Test
    void optionsAreParsedAndInvalidConfigurationsRejected() {
        assertEquals(Config.defaults(), ExecutionScaleSweepMain.parse());
        assertEquals(List.of(25, 250, 1000), Config.defaults().sizes());
        assertEquals(List.of(1, 5, 20), Config.defaults().ks());
        Config c = ExecutionScaleSweepMain.parse("--seed=7", "--sizes=100,10,50", "--ks=5,1", "--warmup=0",
                "--iterations=3");
        assertEquals(new Config(7, List.of(10, 50, 100), List.of(1, 5), 0, 3), c);
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--sizes="));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--sizes=25,25"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--sizes=6"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--ks=51"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--ks=0"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--warmup=-1"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--iterations=0"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("--limit=5"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionScaleSweepMain.parse("sizes"));
    }

    @Test
    void theTieAndAtomChecksDetectReorderedAndSplitRefs() throws Exception {
        ScopeCorpus scope = ExecutionScaleCorpusGenerator.generate(42, 25).scopes().get(0);
        Path root = work.resolve("ties");
        try (ExecutionMemory memory = ExecutionMemory.open(root, scope.scope())) {
            ForgeFixtureRecorder.record(memory, scope.events());
            Query dup = scope.queries().stream().filter(q -> q.kind() == QueryKind.DUPLICATE).findFirst()
                    .orElseThrow();
            List<ExperienceHit> hits = memory.recall(scope.scope(), dup.text(), 5).hits();
            ProjectionLayout layout = ProjectionLayout.of(root, scope.scope());
            ProjectionCheckpoint.Snapshot snapshot = ProjectionCheckpoint.read(
                    layout.checkpoint(layout.currentDir().orElseThrow()), scope.scope());
            assertTrue(hits.size() >= 3 && hits.get(0).atomId().equals(hits.get(1).atomId()),
                    "the duplicate group shares the top atom");
            assertEquals(List.of(), ExecutionScaleSweepEvaluator.orderViolations("ok", hits));
            String fingerprint = new ExecutionScaleSweepEvaluator.Rankings(java.util.Map.of("dup@5", hits))
                    .fingerprint();
            assertTrue(fingerprint.contains("@c" + scope.events().size() + "v"), "freshness is fingerprinted: "
                    + fingerprint);
            assertEquals(List.of(), ExecutionScaleSweepEvaluator.atomViolations("ok", hits, 5, snapshot));

            List<ExperienceHit> swapped = new ArrayList<>(hits);
            Collections.swap(swapped, 0, 1);
            assertFalse(ExecutionScaleSweepEvaluator.orderViolations("swapped", swapped).isEmpty());

            List<ExperienceHit> split = new ArrayList<>(hits);
            split.remove(1);
            assertFalse(ExecutionScaleSweepEvaluator.atomViolations("split", split, 5, snapshot).isEmpty());
        }
    }
}
