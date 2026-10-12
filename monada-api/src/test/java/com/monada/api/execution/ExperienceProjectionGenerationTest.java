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
    void rebuildRecoversFromLinkedOrDanglingGenerationEntries() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        Path scopeDir = layout().scopeDir();
        Path outside = Files.createDirectories(root.getParent().resolve(root.getFileName() + "-outside"));
        Files.writeString(outside.resolve("keep.txt"), "outside");
        Files.createSymbolicLink(scopeDir.resolve("projection-000002"), outside);
        Files.createSymbolicLink(scopeDir.resolve("projection-000003"), scopeDir.resolve("missing-target"));
        Files.writeString(layout().pointer(), "projection-000002\n");
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
            ProjectionStatus rebuilt = m.rebuildProjection(SCOPE); // the explicit recovery path must work
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state(), rebuilt.diagnostics().toString());
            assertEquals("projection-000004", projectionDir(root, SCOPE).getFileName().toString(),
                    "never reuses a name held by a link");
        }
        assertFalse(Files.exists(scopeDir.resolve("projection-000002"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(scopeDir.resolve("projection-000003"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("outside", Files.readString(outside.resolve("keep.txt")), "link targets are untouched");
        assertEquals(List.of(), layout().invalidGenerationEntries());
    }

    private List<String> generationNames() throws IOException {
        return layout().generationDirs().stream().map(p -> p.getFileName().toString()).toList();
    }

    @Test
    void rebuildsKeepSoundGenerationsUntilPruned() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            List<String> expected = render(m.recall(SCOPE, QUERY, 10));
            for (int i = 0; i < 3; i++) {
                m.rebuildProjection(SCOPE);
            }
            assertEquals(List.of("projection-000001", "projection-000002", "projection-000003", "projection-000004"),
                    generationNames(), "rebuilds never reclaim sound generations");
            ProjectionStatus pruned = m.pruneProjection(SCOPE);
            assertEquals(ProjectionStatus.State.CURRENT, pruned.state());
            assertEquals(List.of(), pruned.diagnostics());
            assertEquals(List.of("projection-000004"), generationNames());
            assertEquals(expected, render(m.recall(SCOPE, QUERY, 10)));
        }
    }

    @Test
    void snapshotSurvivesSeveralRebuilds() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            List<String> expected = render(snapshot.recall(SCOPE, QUERY, 10));
            try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
                writer.rebuildProjection(SCOPE);
                writer.rebuildProjection(SCOPE);
            }
            ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, 10);
            assertEquals(ProjectionStatus.State.CURRENT, recall.status().state(), recall.status().diagnostics().toString());
            assertEquals(expected, render(recall));
            assertEquals(List.of("projection-000001", "projection-000002", "projection-000003"), generationNames());
        }
    }

    @Test
    void snapshotOfAPrunedGenerationReportsMissing() throws IOException {
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            expected = render(m.recall(SCOPE, QUERY, 10));
        }
        try (ExecutionMemory snapshot = ExecutionMemory.openReadOnly(root, SCOPE)) {
            try (ExecutionMemory writer = ExecutionMemory.open(root, SCOPE)) {
                writer.rebuildProjection(SCOPE);
                writer.pruneProjection(SCOPE); // the host prunes although this snapshot is still open
            }
            ProjectionStatus status = snapshot.projectionStatus(SCOPE); // before any recall
            assertEquals(ProjectionStatus.State.MISSING, status.state());
            assertTrue(status.diagnostics().get(0).contains("projection-000001 was reclaimed"),
                    status.diagnostics().toString());
            ExperienceRecall recall = snapshot.recall(SCOPE, QUERY, 10);
            assertEquals(ProjectionStatus.State.MISSING, recall.status().state());
            assertEquals(List.of(), recall.hits());
            assertTrue(recall.status().diagnostics().get(0).contains("projection-000001 was reclaimed"),
                    recall.status().diagnostics().toString());
        }
        try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(root, SCOPE)) {
            assertEquals(expected, render(fresh.recall(SCOPE, QUERY, 10)), "a reopened snapshot works");
        }
    }

    @Test
    void failedCleanupIsReportedNotThrownAndRetried() throws IOException {
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
            corpus(m);
            m.rebuildProjection(SCOPE);
            ProjectionStatus pruned = m.pruneProjection(SCOPE); // must not throw
            assertEquals(ProjectionStatus.State.CURRENT, pruned.state());
            assertTrue(pruned.diagnostics().toString().contains("could not delete old generation projection-000001"),
                    pruned.diagnostics().toString());
            assertEquals(List.of("projection-000001", "projection-000002"), generationNames());
            fail[0] = false;
            assertEquals(List.of(), m.pruneProjection(SCOPE).diagnostics());
            assertEquals(List.of("projection-000002"), generationNames(), "the next prune retries the cleanup");

            // An unsound previous generation is reclaimed by the rebuild itself; a failure there is reported too.
            Files.writeString(projectionDir(root, SCOPE).resolve("memory/feedback/feedback-000001.log"), "x");
            m.close();
        }
        fail[0] = true;
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE, ExecutionMemoryConfig.defaults(), faults)) {
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
            ProjectionStatus rebuilt = m.rebuildProjection(SCOPE); // committed: must not throw
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertTrue(rebuilt.diagnostics().toString().contains("projection-000002"), rebuilt.diagnostics().toString());
            assertTrue(rebuilt.diagnostics().toString().contains("pruneProjection retries"),
                    rebuilt.diagnostics().toString());
            assertEquals(List.of("projection-000002", "projection-000003"), generationNames());
            fail[0] = false;
            assertEquals(List.of(), m.pruneProjection(SCOPE).diagnostics());
            assertEquals(List.of("projection-000003"), generationNames(), "prune retries the failed cleanup");
        }
    }

    @Test
    void pruneWithAnUnreadablePointerDeletesNothing() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
            m.rebuildProjection(SCOPE);
            Files.writeString(layout().pointer(), "garbage\n");
            ProjectionStatus status = m.pruneProjection(SCOPE);
            assertTrue(status.diagnostics().toString().contains("rebuild before pruning"), status.diagnostics().toString());
            assertEquals(List.of("projection-000001", "projection-000002"), generationNames());
        }
    }

    @Test
    void manifestDimensionIsPinnedByTheGeneration() throws IOException {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        // Rewrite the memory as an internally consistent 0.4 store at another dimension.
        Path memory = projectionDir(root, SCOPE).resolve("memory");
        Path scratch = root.resolve("scratch-64");
        com.monada.api.MonadaMemory other = com.monada.api.MonadaMemory.open(scratch,
                com.monada.api.MonadaMemoryOptions.defaults());
        String manifest = Files.readString(scratch.resolve("manifest.json"))
                .replace("\"dimensions\": 128", "\"dimensions\": 64")
                .replace("\"vectorDimensions\": 128", "\"vectorDimensions\": 64");
        assertFalse(manifest.contains("128"), manifest);
        Files.writeString(memory.resolve("manifest.json"), manifest);
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("differs from the generation's dimension 128"),
                    status.diagnostics().toString());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
        assertTrue(other != null);
    }

    @Test
    void consistentlyRewrittenHeaderAndManifestDimensionIsIncompatible() throws Exception {
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            corpus(m);
        }
        Path generation = projectionDir(root, SCOPE);
        // Rewrite the checkpoint header (with a recomputed line hash) and the manifest to 64 dimensions.
        Path checkpoint = generation.resolve("projection-checkpoint.log");
        List<String> lines = new ArrayList<>(Files.readAllLines(checkpoint, StandardCharsets.UTF_8));
        String payload = lines.get(0).split("\t", 5)[4].replace("|128|", "|64|");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
        lines.set(0, "MXP1\t1\t" + body.length + "\t" + digest + "\t" + payload);
        Files.write(checkpoint, lines, StandardCharsets.UTF_8);
        Path manifest = generation.resolve("memory/manifest.json");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("\"dimensions\": 128", "\"dimensions\": 64")
                .replace("\"vectorDimensions\": 128", "\"vectorDimensions\": 64"));
        try (ExecutionMemory m = ExecutionMemory.open(root, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("unsupported projection dimension 64"),
                    status.diagnostics().toString());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
    }

    @Test
    void rebuildReplacesAnInvalidPointerEntry() throws IOException {
        Path outsideFile = Files.writeString(root.getParent().resolve(root.getFileName() + "-pointer-target"),
                "projection-000001\n");
        for (String kind : List.of("dangling link", "outside link", "directory")) {
            Path store = root.resolve(kind.replace(' ', '-'));
            try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
                corpus(m);
            }
            ProjectionLayout layout = ProjectionLayout.of(store, SCOPE);
            Path pointer = layout.pointer();
            Files.delete(pointer);
            switch (kind) {
                case "dangling link" -> Files.createSymbolicLink(pointer, layout.scopeDir().resolve("nowhere"));
                case "outside link" -> Files.createSymbolicLink(pointer, outsideFile);
                default -> Files.writeString(Files.createDirectories(pointer).resolve("inner.txt"), "x");
            }
            try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
                assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state(), kind);
                ProjectionStatus rebuilt = m.rebuildProjection(SCOPE);
                assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state(), kind + " " + rebuilt.diagnostics());
            }
            assertTrue(Files.isRegularFile(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS), kind);
            assertEquals("projection-000002\n", Files.readString(pointer), kind);
        }
        assertEquals("projection-000001\n", Files.readString(outsideFile), "a link target is never written");
    }
}
