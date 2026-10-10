package com.monada.api.execution;

import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.projectionDir;
import static com.monada.api.execution.Experiences.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recall on a read-only snapshot while a writer keeps appending to the projection files. */
class ExperienceRecallReadOnlySnapshotTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");
    static final String QUERY = "flaky test";

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
            assertEquals(before1, render(snapshot.recall(SCOPE, QUERY, 1)), "new atoms rank first but are skipped");
            assertEquals(before2, render(snapshot.recall(SCOPE, QUERY, 2)));
        }
        try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
            List<ExperienceHit> hits = fresh.recall(SCOPE, QUERY, 2).hits();
            assertTrue(hits.stream().allMatch(h -> h.ref().execution().value().compareTo("x-3") >= 0),
                    "a new snapshot sees the new experiences");
        }
    }

    @Test
    void aQueryOverlappingAnAppendIsRetried() throws IOException {
        seed();
        Path index = projectionDir(root, SCOPE).resolve("memory/indexes/vector-map.idx");
        byte[] complete = Files.readAllBytes(index);
        List<String> expected;
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            expected = render(snapshot.recall(SCOPE, QUERY, 2));
        }
        int[] attempts = {0};
        ExecutionMemory.ReadOnlyOpenHook hook = new ExecutionMemory.ReadOnlyOpenHook() {
            @Override
            public void afterLedgerSnapshot(int attempt) {
            }

            @Override
            public void beforeRecallQuery(int attempt) {
                attempts[0] = attempt;
                try { // attempt 1 sees a half-written index line; the writer then finishes (restores) it
                    if (attempt == 1) {
                        Files.writeString(index, "half-written", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    } else {
                        Files.write(index, complete);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE, ExecutionMemoryConfig.defaults(),
                hook)) {
            ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, 2);
            assertEquals(2, attempts[0]);
            assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(),
                    recall.status().diagnostics().toString());
            assertEquals(expected, render(recall));
        }
    }

    @Test
    void stableDamageIsIncompatibleWithoutRetrying() throws IOException {
        seed();
        Path index = projectionDir(root, SCOPE).resolve("memory/indexes/vector-map.idx");
        int[] attempts = {0};
        ExecutionMemory.ReadOnlyOpenHook hook = new ExecutionMemory.ReadOnlyOpenHook() {
            @Override
            public void afterLedgerSnapshot(int attempt) {
            }

            @Override
            public void beforeRecallQuery(int attempt) {
                attempts[0] = attempt;
            }
        };
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE, ExecutionMemoryConfig.defaults(),
                hook)) {
            Files.writeString(index, "garbage", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            // Damage made after open but stable during the query: one attempt, then INCOMPATIBLE.
            ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, 2);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, recall.status().state());
            assertEquals(List.of(), recall.hits());
            assertTrue(recall.status().diagnostics().toString().contains("cannot be read"),
                    recall.status().diagnostics().toString());
            assertEquals(1, attempts[0]);
        }
    }
}
