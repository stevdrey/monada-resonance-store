package com.monada.api.execution;

import static com.monada.api.execution.Experiences.appended;
import static com.monada.api.execution.Experiences.attempt;
import static com.monada.api.execution.Experiences.checkpointBytes;
import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.finish;
import static com.monada.api.execution.Experiences.projectionDir;
import static com.monada.api.execution.Experiences.render;
import static com.monada.api.execution.Experiences.start;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.KnowledgeAtom;
import com.monada.core.execution.ScopeId;
import com.monada.storage.FileAtomStore;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generation publication, compaction, duplicate vectors and bounded ref expansion (Codex review round 4). */
class ExperienceProjectionGenerationTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");
    static final String QUERY = "flaky test";

    @TempDir
    Path root;

    private void corpus(ExecutionMemory m) {
        experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
        experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache", null);
    }

    private ProjectionLayout layout() throws IOException {
        return ProjectionLayout.of(root, SCOPE);
    }

    /** Faults that only act before publication. */
    private static ProjectionFaults beforePublish(Runnable action) {
        return new ProjectionFaults() {
            @Override
            public void beforeCommit(long ledgerSequence) {
            }

            @Override
            public void beforePublish() {
                action.run();
            }
        };
    }

    @Test
    void rebuildNeverLeavesTheScopeWithoutAPublishedProjection() throws IOException {
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            expected = render(m.recall(SCOPE, QUERY, 10));
        }
        List<ProjectionStatus.State> seen = new ArrayList<>();
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE, ExecutionMemoryConfig.defaults(),
                beforePublish(() -> {
                    // A reader that opens while the new generation is complete but not yet published.
                    try (ExecutionMemory reader = ExecutionMemory.openReadOnly(root, SCOPE)) {
                        ExperienceRecall recall = reader.recall(SCOPE, QUERY, 10);
                        seen.add(recall.status().state());
                        assertEquals(expected, render(recall));
                    }
                }))) {
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, QUERY, 10)));
        }
        assertEquals(List.of(ProjectionStatus.State.CURRENT), seen);
        assertEquals(2, layout().generationDirs().size(), "the previous generation is kept for readers");
    }

    @Test
    void failedPublicationKeepsThePublishedGeneration() throws IOException {
        List<String> expected;
        Path published;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            expected = render(m.recall(SCOPE, QUERY, 10));
            published = projectionDir(root, SCOPE);
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE, ExecutionMemoryConfig.defaults(),
                beforePublish(() -> {
                    throw new UncheckedIOException(new IOException("injected publication failure"));
                }))) {
            assertThrows(UncheckedIOException.class, () -> m.rebuildProjection(SCOPE));
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, QUERY, 10)));
        }
        assertEquals(Optional.of(published), layout().currentDir());
        assertEquals(List.of(published), layout().generationDirs(), "the unpublished generation is removed");
    }

    @Test
    void rebuildsKeepOnlyTheCurrentAndPreviousGenerations() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            for (int i = 0; i < 3; i++) {
                m.rebuildProjection(SCOPE);
            }
            assertEquals(List.of("projection-000003", "projection-000004"),
                    layout().generationDirs().stream().map(p -> p.getFileName().toString()).toList());
            assertEquals("projection-000004", projectionDir(root, SCOPE).getFileName().toString());
        }
    }

    @Test
    void malformedOrDanglingPointerIsIncompatibleUntilRebuilt() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        for (String pointer : List.of("projection-000099\n", "not a generation\n")) {
            Files.writeString(layout().pointer(), pointer);
            try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
                ProjectionStatus status = m.projectionStatus(SCOPE);
                assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state(), pointer);
                assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            }
        }
    }

    @Test
    void rebuildCompactsRetiredAtomsAndKeepsResults() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            appended(m, Experiences.correctFinish(SCOPE, "x-1", "a1", "x-1-fix", "x-1-a1-fin", 2,
                    "Mocked the session", null));
            appended(m, Experiences.correctStart(SCOPE, "x-2", "x-2-retitle", 2, "Speed up the CI build"));
            int beforeRebuild = atoms().size();
            assertEquals(4, beforeRebuild, "incremental projection keeps the two retired atoms");
            List<String> expected = render(m.recall(SCOPE, QUERY, 10));
            byte[] checkpoint = checkpointBytes(root, SCOPE);

            m.rebuildProjection(SCOPE);
            assertEquals(2, atoms().size(), "only live atoms are stored after a rebuild");
            assertEquals(expected, render(m.recall(SCOPE, QUERY, 10)));
            assertArrayEquals(checkpoint, checkpointBytes(root, SCOPE), "checkpoint unchanged");

            // Correcting back to a compacted text stores it again instead of trusting it was stored before.
            appended(m, Experiences.correctFinish(SCOPE, "x-1", "a1", "x-1-fix2", "x-1-fix", 3,
                    "Waited for the session cookie", null));
            ExperienceRecall recall = m.recall(SCOPE, "session cookie", 1);
            assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(), recall.status().diagnostics().toString());
            assertEquals("x-1-fix2", recall.hits().get(0).ref().eventId().value());
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(SCOPE).state(), "survives reopen");
        }
    }

    private List<KnowledgeAtom> atoms() throws IOException {
        return new FileAtomStore(projectionDir(root, SCOPE).resolve("memory")).findAll();
    }

    @Test
    void duplicateVectorOfAMappedAtomIsIncompatible() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        Path memory = projectionDir(root, SCOPE).resolve("memory");
        Path index = memory.resolve("indexes/vector-map.idx");
        Path segment = memory.resolve("vectors/segment-000001.f32");
        List<String> entries = Files.readAllLines(index, StandardCharsets.UTF_8);
        long frame = Files.size(segment) / entries.size();
        byte[] firstFrame = java.util.Arrays.copyOf(Files.readAllBytes(segment), (int) frame);
        long offset = Files.size(segment);
        Files.write(segment, firstFrame, StandardOpenOption.APPEND);
        String atom = entries.get(0).split("\t")[0];
        Files.writeString(index, atom + "\t" + offset + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("DUPLICATE_VECTOR_INDEX_ID"),
                    status.diagnostics().toString());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
    }

    @Test
    void refExpansionStopsAtTheLimit() {
        List<HistoryEntry> ledger;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            appended(m, start(SCOPE, "x-1", "Repair the flaky login test"));
            String previous = null;
            for (int i = 1; i <= 5; i++) {
                appended(m, attempt(SCOPE, "x-1", "a" + i, i, previous));
                appended(m, finish(SCOPE, "x-1", "a" + i, "Waited for the cookie", null));
                previous = "a" + i;
            }
            ledger = m.history(SCOPE, null, 500).entries();
        }
        List<String> resolved = new ArrayList<>();
        try (ExperienceProjection projection = ExperienceProjection.open(root, SCOPE, ledger, false,
                ProjectionFaults.NONE)) {
            ExperienceRecall recall = projection.recall(QUERY, 2, ledger.size(), ref -> {
                resolved.add(ref.attempt().orElseThrow().value());
                return ledger.stream().filter(e -> e.event().eventId().equals(ref.eventId())).findFirst();
            });
            assertEquals(List.of("a1", "a2"),
                    recall.hits().stream().map(h -> h.ref().attempt().orElseThrow().value()).toList());
            assertEquals(List.of("a1", "a2"), resolved, "only the refs that are returned are resolved");
        }
        assertFalse(resolved.contains("a3"));
    }

    @Test
    void legacyManifestInAProjectionIsIncompatible() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        // A structurally valid 0.1 manifest with the same segments: legacy query weights would apply.
        Files.writeString(projectionDir(root, SCOPE).resolve("memory/manifest.json"), """
                {
                  "version": "0.1",
                  "dimensions": 128,
                  "vectorSegment": "vectors/segment-000001.f32",
                  "atomSegment": "atoms/segment-000001.log",
                  "feedbackSegment": "feedback/feedback-000001.log"
                }
                """);
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("default encoding"), status.diagnostics().toString());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
    }

    @Test
    void alteredVectorOfALiveAtomIsIncompatible() throws IOException {
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            expected = render(m.recall(SCOPE, QUERY, 10));
        }
        // Swap the two frames: the store stays structurally valid, but each atom now has the other's vector.
        Path segment = projectionDir(root, SCOPE).resolve("memory/vectors/segment-000001.f32");
        byte[] bytes = Files.readAllBytes(segment);
        int frame = bytes.length / 2;
        byte[] swapped = new byte[bytes.length];
        System.arraycopy(bytes, frame, swapped, 0, frame);
        System.arraycopy(bytes, 0, swapped, frame, frame);
        Files.write(segment, swapped);
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(root, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("does not match its projected text"),
                    status.diagnostics().toString());
        }
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, QUERY, 10)));
        }
    }

    @Test
    void failedCleanupAfterPublicationIsReportedNotThrown() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            m.rebuildProjection(SCOPE); // generations 1 (previous) and 2 (current)
        }
        boolean[] fail = {true};
        ProjectionFaults faults = new ProjectionFaults() {
            @Override
            public void beforeCommit(long ledgerSequence) {
            }

            @Override
            public void beforeDeleteGeneration(Path generation) throws IOException {
                if (fail[0]) {
                    throw new IOException("file in use");
                }
            }
        };
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE, ExecutionMemoryConfig.defaults(), faults)) {
            ProjectionStatus status = m.rebuildProjection(SCOPE); // committed: must not throw
            assertEquals(ProjectionStatus.State.CURRENT, status.state());
            assertTrue(status.diagnostics().toString().contains("could not delete old generation projection-000001"),
                    status.diagnostics().toString());
            assertEquals("projection-000003", projectionDir(root, SCOPE).getFileName().toString());
            assertEquals(3, layout().generationDirs().size(), "the undeleted generation is still there");
            fail[0] = false;
            assertEquals(List.of(), m.rebuildProjection(SCOPE).diagnostics());
            assertEquals(List.of("projection-000003", "projection-000004"),
                    layout().generationDirs().stream().map(p -> p.getFileName().toString()).toList(),
                    "the next rebuild retries the cleanup");
        }
    }
}
