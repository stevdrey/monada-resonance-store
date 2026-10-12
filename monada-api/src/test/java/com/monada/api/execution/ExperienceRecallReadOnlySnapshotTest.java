package com.monada.api.execution;

import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.projectionDir;
import static com.monada.api.execution.Experiences.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recall on a read-only snapshot: the projection is verified and loaded into memory at open, so nothing written,
 * rebuilt, pruned or damaged afterwards changes its answers, and its ranking equals the writable instance's.
 */
class ExperienceRecallReadOnlySnapshotTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");
    static final String QUERY = "flaky test";
    static final List<String> QUERIES = List.of(QUERY, "gradle cache", "session cookie", "release notes",
            "unrelated astronomy question", "");

    @TempDir
    Path root;

    private void seed() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
            experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache", null);
        }
    }

    private void writerAddsBetterMatches() {
        try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
            experience(writer, SCOPE, "x-3", "Fix the flaky test", "Retried the flaky test", null);
            experience(writer, SCOPE, "x-4", "Fix another flaky test", "Quarantined the flaky test", null);
        }
    }

    @Test
    void experiencesProjectedAfterTheSnapshotAreInvisibleAndConsumeNoSlots() {
        seed();
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            List<String> before1 = render(snapshot.recall(SCOPE, QUERY, 1));
            List<String> before2 = render(snapshot.recall(SCOPE, QUERY, 2));
            writerAddsBetterMatches();
            for (int limit : new int[] {1, 2, 10}) {
                ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, limit);
                assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(),
                        recall.status().diagnostics().toString());
                assertEquals(6, recall.status().ledgerSequence(), "still the snapshot's ledger");
                recall.hits().forEach(h -> assertTrue(h.event().sequence() <= 6));
            }
            assertEquals(before1, render(snapshot.recall(SCOPE, QUERY, 1)), "new atoms rank first but are invisible");
            assertEquals(before2, render(snapshot.recall(SCOPE, QUERY, 2)));
        }
        try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
            List<ExperienceHit> hits = fresh.recall(SCOPE, QUERY, 2).hits();
            assertTrue(hits.stream().allMatch(h -> h.ref().execution().value().compareTo("x-3") >= 0),
                    "a new snapshot sees the new experiences");
        }
    }

    @Test
    void filesChangedAfterOpenNeverAffectTheSnapshot() throws IOException {
        seed();
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            List<String> expected = render(snapshot.recall(SCOPE, QUERY, 2));
            Path memory = projectionDir(root, SCOPE).resolve("memory");
            // A half-written index line, a truncated vector segment and a deleted atom log after open.
            Files.writeString(memory.resolve("indexes/vector-map.idx"), "half-written", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            Files.write(memory.resolve("vectors/segment-000001.f32"), new byte[0]);
            Files.delete(memory.resolve("atoms/segment-000001.log"));
            ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, 2);
            assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(), recall.status().diagnostics().toString());
            assertEquals(expected, render(recall), "the snapshot answers from its frozen copy");
            assertEquals(ProjectionStatus.State.CURRENT, snapshot.projectionStatus(SCOPE).state());
        }
        try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, fresh.projectionStatus(SCOPE).state(),
                    "the damage is reported to snapshots opened afterwards");
        }
    }

    @Test
    void readOnlyRecallEqualsWritableRecall() {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
            experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache",
                    "Measure before optimizing");
            experience(m, SCOPE, "x-3", "Write release notes", null, null);
            // A retired atom and identical texts must be handled the same way by both rankers.
            Experiences.appended(m, Experiences.correctFinish(SCOPE, "x-3", "a1", "x-3-fix", "x-3-a1-fin", 2,
                    "Generated the changelog from merged pull requests", null));
            experience(m, SCOPE, "x-4", "Repair the flaky login test", "Waited for the session cookie", null);
        }
        try (ExecutionMemory writable = ExecutionMemory.open(root, SCOPE);
             ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            for (String query : QUERIES) {
                for (int limit : new int[] {1, 2, 3, 10}) {
                    assertEquals(render(writable.recall(SCOPE, query, limit)),
                            render(snapshot.recall(SCOPE, query, limit)), query + " limit " + limit);
                }
            }
        }
    }
}
