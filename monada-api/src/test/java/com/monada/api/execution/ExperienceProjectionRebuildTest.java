package com.monada.api.execution;

import static com.monada.api.execution.Experiences.appended;
import static com.monada.api.execution.Experiences.attempt;
import static com.monada.api.execution.Experiences.checkpointBytes;
import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.finish;
import static com.monada.api.execution.Experiences.render;
import static com.monada.api.execution.Experiences.start;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ProjectionCheckpoint;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperienceProjectionRebuildTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");
    static final List<String> QUERIES = List.of("flaky test", "gradle cache", "release notes", "session cookie");

    @TempDir
    Path root;

    private static void corpus(ExecutionMemory m) {
        experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
        experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache",
                "Measure before optimizing");
        experience(m, SCOPE, "x-3", "Write release notes", null, null);
    }

    private static Map<String, List<String>> recallAll(ExecutionMemory m) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        QUERIES.forEach(q -> out.put(q, render(m.recall(SCOPE, q, 10))));
        return out;
    }

    @Test
    void identicalSummariesFromTwoAttemptsKeepTwoExactRefs() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            appended(m, start(SCOPE, "x-1", "Repair the flaky login test"));
            appended(m, attempt(SCOPE, "x-1", "a1", 1, null));
            appended(m, finish(SCOPE, "x-1", "a1", "Waited for the session cookie", null));
            appended(m, attempt(SCOPE, "x-1", "a2", 2, "a1"));
            appended(m, finish(SCOPE, "x-1", "a2", "Waited for the session cookie", null));
            // An identical retry of a recorded event is idempotent and projects nothing.
            assertEquals(RecordResult.Status.IDEMPOTENT,
                    m.record(finish(SCOPE, "x-1", "a2", "Waited for the session cookie", null)).status());

            List<ExperienceHit> hits = m.recall(SCOPE, "session cookie", 10).hits();
            assertEquals(2, hits.size());
            assertEquals(hits.get(0).atomId(), hits.get(1).atomId(), "identical text, one atom");
            assertNotEquals(hits.get(0).ref(), hits.get(1).ref());
            assertEquals(List.of("a1", "a2"), hits.stream().map(h -> h.ref().attempt().orElseThrow().value()).toList());
            assertEquals(hits.get(0).similarity(), hits.get(1).similarity());
            assertEquals(List.of(hits.get(0)), m.recall(SCOPE, "session cookie", 1).hits(), "case (b), limit 1");
            List<String> before = render(m.recall(SCOPE, "session cookie", 10));

            m.rebuildProjection(SCOPE);
            assertEquals(before, render(m.recall(SCOPE, "session cookie", 10)), "rebuild does not duplicate");
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(2, m.recall(SCOPE, "session cookie", 10).hits().size(), "reopen does not duplicate");
        }
        ProjectionCheckpoint.Snapshot snapshot =
                ProjectionCheckpoint.read(Experiences.checkpoint(root, SCOPE), SCOPE);
        assertEquals(1, snapshot.refsByAtom().size());
        assertEquals(2, snapshot.refsByAtom().values().iterator().next().size());
    }

    @Test
    void reopenAndRebuildReproduceRankedRefsScoresAndCheckpointBytes() {
        Map<String, List<String>> incremental;
        byte[] incrementalCheckpoint;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            incremental = recallAll(m);
            incrementalCheckpoint = checkpointBytes(root, SCOPE);
        }
        incremental.values().forEach(v -> assertTrue(!v.isEmpty()));
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(SCOPE).state());
            assertEquals(incremental, recallAll(m), "reopen");
            ProjectionStatus rebuilt = m.rebuildProjection(SCOPE);
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertEquals(9, rebuilt.coveredSequence());
            assertEquals(incremental, recallAll(m), "explicit rebuild");
            assertArrayEquals(incrementalCheckpoint, checkpointBytes(root, SCOPE), "rebuild is byte-identical");
            m.rebuildProjection(SCOPE);
            assertEquals(incremental, recallAll(m), "rebuild is idempotent");
            assertArrayEquals(incrementalCheckpoint, checkpointBytes(root, SCOPE));
        }
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            assertEquals(incremental, recallAll(m), "read-only snapshot");
        }
        // A fresh root with the same inputs yields the same results.
        Path other = root.resolve("other");
        try (ExecutionMemory m = ExecutionMemory.open(other, SCOPE)) {
            corpus(m);
            assertEquals(incremental, recallAll(m));
            assertArrayEquals(incrementalCheckpoint, checkpointBytes(other, SCOPE));
        }
    }

    @Test
    void correctedFinishReplacesTheRefAndRetiredAtomsDoNotConsumeSlots() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            appended(m, Experiences.correctFinish(SCOPE, "x-3", "a1", "x-3-fix", "x-3-a1-fin", 2,
                    "Generated the changelog from merged pull requests", null));
            List<ExperienceHit> hits = m.recall(SCOPE, "release notes changelog", 10).hits();
            assertEquals(3, hits.size(), "one experience per finished attempt");
            ExperienceRef corrected = hits.get(0).ref();
            assertEquals("x-3-fix", corrected.eventId().value());
            assertEquals(2, corrected.revision());
            assertEquals(m.loadEvent(SCOPE, corrected.eventId(), 2).orElseThrow(), hits.get(0).event());
            assertTrue(hits.stream().noneMatch(h -> h.ref().eventId().value().equals("x-3-a1-fin")));

            // The old text's atom is retired: the old wording still matches it best but never yields a hit.
            List<ExperienceHit> old = m.recall(SCOPE, "Write release notes", 1).hits();
            assertEquals(1, old.size(), "the retired atom does not consume the only slot");
            assertNotEquals("x-3-a1-fin", old.get(0).ref().eventId().value());

            Map<String, List<String>> before = recallAll(m);
            byte[] checkpoint = checkpointBytes(root, SCOPE);
            m.rebuildProjection(SCOPE);
            assertEquals(before, recallAll(m));
            assertArrayEquals(checkpoint, checkpointBytes(root, SCOPE));
        }
    }

    @Test
    void correctedTaskSummaryMovesExistingRefsToTheNewText() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            ExperienceHit before = m.recall(SCOPE, "release notes", 1).hits().get(0);
            appended(m, Experiences.correctStart(SCOPE, "x-3", "x-3-retitle", 2, "Publish the quarterly changelog"));
            ExperienceHit after = m.recall(SCOPE, "quarterly changelog", 1).hits().get(0);
            assertEquals(before.ref(), after.ref(), "the finish fact itself did not change");
            assertNotEquals(before.atomId(), after.atomId());
            assertTrue(m.recall(SCOPE, "release notes", 10).hits().stream()
                    .noneMatch(h -> h.atomId().equals(before.atomId())));
            Map<String, List<String>> results = recallAll(m);
            m.rebuildProjection(SCOPE);
            assertEquals(results, recallAll(m));
        }
    }
}
