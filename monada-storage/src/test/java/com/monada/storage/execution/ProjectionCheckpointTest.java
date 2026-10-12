package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionId;
import com.monada.core.execution.ExperienceRef;
import com.monada.core.execution.ScopeId;
import com.monada.core.execution.TaskId;
import com.monada.storage.execution.ProjectionCheckpoint.Entry;
import com.monada.storage.execution.ProjectionCheckpoint.EntryKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectionCheckpointTest {
    private static final ScopeId SCOPE = ScopeId.of("scope|1\\x");
    private static final String ATOM_A = "00000000-0000-3000-8000-00000000000a";
    private static final String ATOM_B = "00000000-0000-3000-8000-00000000000b";
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path root;

    private static ExperienceRef ref(String event, int revision) {
        return new ExperienceRef(SCOPE, TaskId.of("task"), ExecutionId.of("exec"), Optional.of(AttemptId.of("a1")),
                EventId.of(event), revision);
    }

    private Path file() {
        return root.resolve(ProjectionCheckpoint.FILE_NAME);
    }

    private ProjectionCheckpoint.Snapshot read() throws IOException {
        return ProjectionCheckpoint.read(file(), SCOPE);
    }

    @Test
    void roundTripFoldsMappingRetirementsAndCovers() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of());
            c.commit(2, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("z", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_A, ref("b", 1))));
            c.commit(3, DIGEST, List.of(new Entry(EntryKind.RETIRE, ATOM_A, ref("z", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_B, ref("z2", 2))));
            c.commit(4, DIGEST, List.of(new Entry(EntryKind.RETIRE, ATOM_A, ref("b", 1)),
                    new Entry(EntryKind.PROJECT, ATOM_B, ref("b2", 2))));
            assertEquals(4, c.coveredSequence());
        }
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertTrue(s.cleanTail());
        assertEquals(4, s.coveredSequence());
        assertEquals(List.of(ATOM_B), List.copyOf(s.refsByAtom().keySet()));
        assertEquals(List.of(ref("b2", 2), ref("z2", 2)), s.refsByAtom().get(ATOM_B), "canonical ref order");
        assertEquals(Set.of(ATOM_A), s.retiredAtoms());
    }

    @Test
    void commitRequiresTheNextSequenceAndScope() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            assertThrows(IllegalArgumentException.class, () -> c.commit(2, DIGEST, List.of()));
            ExperienceRef foreign = new ExperienceRef(ScopeId.of("other"), TaskId.of("t"), ExecutionId.of("e"),
                    Optional.empty(), EventId.of("x"), 1);
            assertThrows(IllegalArgumentException.class,
                    () -> c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, foreign))));
            assertThrows(IllegalArgumentException.class, () -> c.commit(1, "XYZ", List.of()));
        }
        assertThrows(IOException.class, () -> ProjectionCheckpoint.create(file(), SCOPE, 128), "never overwrites");
    }

    @Test
    void tornTailAndIncompleteBatchAreUncleanNotProblems() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
            c.commit(2, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_B, ref("e2", 1))));
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file()));
        // Drop the last COVER: the PROJECT of sequence 2 becomes an incomplete batch.
        Files.write(file(), lines.subList(0, lines.size() - 1));
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertFalse(s.cleanTail());
        assertEquals(1, s.coveredSequence());
        assertEquals(Set.of(ATOM_A), s.refsByAtom().keySet());
        assertThrows(IOException.class, () -> ProjectionCheckpoint.openForAppend(file(), SCOPE, s));

        Files.writeString(file(), "MXP1\t4\t9", StandardOpenOption.APPEND); // torn line
        ProjectionCheckpoint.Snapshot torn = read();
        assertTrue(torn.isSound());
        assertFalse(torn.cleanTail());
        assertEquals(1, torn.coveredSequence());
    }

    @Test
    void corruptionIsAProblemAndStopsReading() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
            c.commit(2, DIGEST, List.of());
        }
        byte[] original = Files.readAllBytes(file());
        String text = new String(original, StandardCharsets.UTF_8).replace("|e1|", "|e9|");
        Files.writeString(file(), text);
        ProjectionCheckpoint.Snapshot s = read();
        assertFalse(s.isSound());
        assertTrue(s.problems().get(0).contains("line 2"), s.problems().toString());
        assertTrue(s.problems().get(0).contains("digest"), s.problems().toString());
        assertEquals(0, s.coveredSequence());
        assertThrows(IOException.class, () -> ProjectionCheckpoint.openForAppend(file(), SCOPE, s));
    }

    @Test
    void unknownVersionOrForeignScopeIsAProblem() throws IOException {
        ProjectionCheckpoint.create(file(), SCOPE, 128).close();
        assertFalse(ProjectionCheckpoint.read(file(), ScopeId.of("other")).isSound());

        Path v2 = root.resolve("v2.log");
        String payload = "HEADER|2|summary-text-v1|x";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        Files.writeString(v2, "MXP1\t1\t" + body.length + "\t" + RecordLine.sha256Hex(body) + "\t" + payload + "\n");
        ProjectionCheckpoint.Snapshot s = ProjectionCheckpoint.read(v2, ScopeId.of("x"));
        assertTrue(s.problems().get(0).contains("unsupported projection version"), s.problems().toString());

        Path empty = root.resolve("empty.log");
        Files.createFile(empty);
        assertFalse(ProjectionCheckpoint.read(empty, SCOPE).isSound());
        assertThrows(IOException.class, () -> ProjectionCheckpoint.read(root.resolve("missing.log"), SCOPE));
    }

    @Test
    void duplicateProjectionAndUnknownRetirementAreProblems() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
            c.commit(2, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
        }
        assertTrue(read().problems().get(0).contains("duplicate projection"));

        Path other = root.resolve("other.log");
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(other, SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.RETIRE, ATOM_A, ref("e1", 1))));
        }
        assertTrue(ProjectionCheckpoint.read(other, SCOPE).problems().get(0).contains("retirement"));
    }

    @Test
    void appendContinuesAfterReopen() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
        }
        try (ProjectionCheckpoint c = ProjectionCheckpoint.openForAppend(file(), SCOPE, read())) {
            c.commit(2, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e2", 1))));
        }
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertEquals(2, s.coveredSequence());
        assertEquals(2, s.refsByAtom().get(ATOM_A).size());
    }

    @Test
    void refCodecIsCanonicalAndEscapesSeparators() {
        ExperienceRef withAttempt = ref("ev|t\\1", 3);
        String canonical = ExperienceRefCodec.canonical(withAttempt);
        assertEquals("scope\\p1\\\\x|task|exec|~a1|ev\\pt\\\\1|3", canonical);
        assertEquals(withAttempt, ExperienceRefCodec.parse(canonical));
        ExperienceRef noAttempt = new ExperienceRef(SCOPE, TaskId.of("~t"), ExecutionId.of("e"), Optional.empty(),
                EventId.of("x"), 1);
        assertEquals(noAttempt, ExperienceRefCodec.parse(ExperienceRefCodec.canonical(noAttempt)));
        assertThrows(IllegalArgumentException.class, () -> ExperienceRefCodec.parse("s|t|e|a1|x|1"));
        assertThrows(IllegalArgumentException.class, () -> ExperienceRefCodec.parse("s|t|e||x|01"));
        assertThrows(IllegalArgumentException.class, () -> ExperienceRefCodec.parse("s|t|e||x"));
    }

    @Test
    void layoutUsesHashedScopeDirectoryAndCreatesNothing() throws IOException {
        ScopeId scope = ScopeId.of("../../etc");
        ProjectionLayout layout = ProjectionLayout.of(root, scope);
        Path scopeDir = root.toAbsolutePath().normalize().resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(scope));
        assertEquals(scopeDir, layout.scopeDir());
        assertEquals(scopeDir.resolve("projection.current"), layout.pointer());
        assertEquals(Optional.empty(), layout.currentDir());
        Path dir = layout.nextGenerationDir();
        assertEquals(scopeDir.resolve("projection-000001"), dir);
        assertEquals(dir.resolve(ProjectionCheckpoint.FILE_NAME), layout.checkpoint(dir));
        assertEquals(dir.resolve("memory"), layout.memory(dir));
        assertFalse(Files.exists(root.resolve("scopes")));
        assertThrows(IOException.class, () -> ProjectionLayout.of(root.resolve("missing"), scope));
    }

    @Test
    void pointerPublishesGenerationsAtomicallyAndStrictly() throws IOException {
        ScopeId scope = ScopeId.of("scope-1");
        ProjectionLayout layout = ProjectionLayout.of(root, scope);
        Path first = Files.createDirectories(layout.nextGenerationDir());
        layout.publish(first);
        assertEquals(Optional.of(first), layout.currentDir());
        Path second = Files.createDirectories(layout.nextGenerationDir());
        assertEquals("projection-000002", second.getFileName().toString());
        layout.publish(second);
        assertEquals(Optional.of(second), layout.currentDir());
        assertFalse(Files.exists(layout.pointerTemp()));
        Files.createDirectories(layout.scopeDir().resolve("projection-000010"));
        assertEquals(List.of("projection-000001", "projection-000002", "projection-000010"),
                layout.generationDirs().stream().map(p -> p.getFileName().toString()).toList(), "numeric order");
        assertEquals("projection-000011", layout.nextGenerationDir().getFileName().toString());
        assertThrows(IllegalArgumentException.class, () -> layout.publish(root.resolve("elsewhere")));

        for (String bad : List.of("projection-000002", "projection-1\n", "../projection-000002\n", "x".repeat(80))) {
            Files.writeString(layout.pointer(), bad);
            assertThrows(LedgerException.class, layout::currentDir, bad);
        }
        Files.delete(layout.pointer());
        Path target = Files.writeString(root.resolve("target"), "projection-000002\n");
        Files.createSymbolicLink(layout.pointer(), target);
        assertThrows(LedgerException.class, layout::currentDir, "a linked pointer is refused");
    }

    @Test
    void layoutChecksLinksMemoryArtifactsAndGeneration() throws IOException {
        ScopeId scope = ScopeId.of("scope-1");
        ProjectionLayout layout = ProjectionLayout.of(root, scope);
        Path dir = layout.nextGenerationDir();
        Path memory = layout.memory(dir);
        assertEquals(List.of(), layout.linkProblems(dir), "a missing generation has no link problems");
        assertEquals(1, layout.memoryLayoutProblems(memory).size());

        for (String d : ProjectionLayout.MEMORY_DIRS) {
            Files.createDirectories(memory.resolve(d));
        }
        for (String f : ProjectionLayout.MEMORY_FILES) {
            Files.createFile(memory.resolve(f));
        }
        layout.publish(dir);
        assertEquals(List.of(), layout.memoryLayoutProblems(memory));
        String generation = layout.generation();
        assertEquals(generation, layout.generation(), "stable while nothing changes");
        Files.writeString(memory.resolve("feedback/feedback-000001.log"), "x");
        assertFalse(generation.equals(layout.generation()), "a growing file changes the generation");

        Files.delete(memory.resolve("feedback/feedback-000001.log"));
        assertTrue(layout.memoryLayoutProblems(memory).get(0).contains("feedback-000001.log"));

        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.createSymbolicLink(memory.resolve("feedback/feedback-000001.log"), outside.resolve("log"));
        List<String> links = layout.linkProblems(dir);
        assertEquals(1, links.size());
        assertTrue(links.get(0).contains("symbolic link"), links.toString());
        assertFalse(Files.exists(outside.resolve("log")), "checks never create anything");
    }

    @Test
    void generationEnumerationNeverResolvesLinks() throws IOException {
        ProjectionLayout layout = ProjectionLayout.of(root, ScopeId.of("scope-1"));
        Path real = Files.createDirectories(layout.nextGenerationDir());
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path escaping = Files.createSymbolicLink(layout.scopeDir().resolve("projection-000002"), outside);
        Path dangling = Files.createSymbolicLink(layout.scopeDir().resolve("projection-000003"),
                layout.scopeDir().resolve("nowhere"));
        assertEquals(List.of(real), layout.generationDirs(), "only real directories are generations");
        assertEquals(List.of(escaping, dangling), layout.invalidGenerationEntries());
        assertEquals("projection-000004", layout.nextGenerationDir().getFileName().toString(),
                "names held by links are never reused");
    }

    @Test
    void headerPinsTheFormatDimension() throws IOException {
        ProjectionCheckpoint.create(file(), SCOPE, ProjectionCheckpoint.PROJECTION_DIMENSIONS).close();
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertEquals(128, s.dimensions());
        assertThrows(IllegalArgumentException.class, () -> ProjectionCheckpoint.create(root.resolve("x.log"), SCOPE, 64));
        assertFalse(Files.exists(root.resolve("x.log")), "an invalid dimension creates nothing");

        for (String dims : List.of("064", "64")) {
            Path bad = root.resolve("bad-" + dims + ".log");
            String payload = "HEADER|1|summary-text-v1|" + dims + "|" + EventPayloadCodec.escape(SCOPE.value());
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            Files.writeString(bad, "MXP1\t1\t" + body.length + "\t" + RecordLine.sha256Hex(body) + "\t" + payload + "\n");
            ProjectionCheckpoint.Snapshot rejected = ProjectionCheckpoint.read(bad, SCOPE);
            assertFalse(rejected.isSound(), dims);
            assertTrue(rejected.problems().get(0).contains(dims.equals("64") ? "unsupported projection dimension"
                    : "malformed projection header"), rejected.problems().toString());
        }
    }

    @Test
    void publishReplacesALinkedOrDirectoryPointerWithoutFollowingIt() throws IOException {
        ProjectionLayout layout = ProjectionLayout.of(root, ScopeId.of("scope-1"));
        Path generation = Files.createDirectories(layout.nextGenerationDir());
        Path target = Files.writeString(root.resolve("target"), "unchanged");
        Files.createSymbolicLink(layout.pointer(), target);
        Files.createDirectories(layout.pointerTemp()); // a leftover temp entry that is a directory
        layout.publish(generation);
        assertTrue(Files.isRegularFile(layout.pointer(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(Optional.of(generation), layout.currentDir());
        assertEquals("unchanged", Files.readString(target), "the old link's target is never written");
        assertFalse(Files.exists(layout.pointerTemp(), java.nio.file.LinkOption.NOFOLLOW_LINKS));

        Files.delete(layout.pointer());
        Files.writeString(Files.createDirectories(layout.pointer()).resolve("inner"), "x");
        layout.publish(generation);
        assertEquals(Optional.of(generation), layout.currentDir());
    }

    @Test
    void generationNamesAreAsciiUnderAnyLocale() throws IOException {
        java.util.Locale saved = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
        try {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.of("fa", "IR"));
            ProjectionLayout layout = ProjectionLayout.of(root, ScopeId.of("scope-1"));
            Path dir = Files.createDirectories(layout.nextGenerationDir());
            assertEquals("projection-000001", dir.getFileName().toString());
            layout.publish(dir);
            assertEquals(Optional.of(dir), layout.currentDir());
        } finally {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, saved);
        }
    }

    @Test
    void exhaustedGenerationNumbersFallBackToTheSmallestFreeName() throws IOException {
        ProjectionLayout layout = ProjectionLayout.of(root, ScopeId.of("scope-1"));
        Files.createDirectories(layout.scopeDir().resolve("projection-000001"));
        Files.createDirectories(layout.scopeDir().resolve("projection-999999999999999999"));
        Path next = layout.nextGenerationDir();
        assertEquals("projection-000002", next.getFileName().toString());
        Files.createDirectories(next);
        layout.publish(next); // the fallback name is a valid generation name
        assertEquals(Optional.of(next), layout.currentDir());
    }

    @Test
    void largeBatchesAreStreamedAndRoundTrip() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            entries.add(new Entry(EntryKind.PROJECT, ATOM_A, ref("e" + i, 1)));
        }
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, entries);
        }
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertEquals(1, s.coveredSequence());
        assertEquals(5_000, s.refsByAtom().get(ATOM_A).size());
        assertEquals(5_002, Files.readAllLines(file()).size(), "header + entries + COVER");
    }

    @Test
    void largeUncoveredBatchIsIgnoredWithBoundedReads() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("first", 1))));
            List<Entry> batch = new ArrayList<>();
            for (int i = 0; i < 5_000; i++) {
                batch.add(new Entry(EntryKind.PROJECT, ATOM_B, ref("e" + i, 1)));
            }
            c.commit(2, DIGEST, batch);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file()));
        Files.write(file(), lines.subList(0, lines.size() - 1)); // drop the last COVER: 5,000 uncovered entries
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertFalse(s.cleanTail());
        assertEquals(1, s.coveredSequence());
        assertEquals(java.util.Set.of(ATOM_A), s.refsByAtom().keySet(), "the uncovered batch is never applied");
    }

    @Test
    void malformedEntryAfterTheLastCoverIsStillReported() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of());
        }
        String payload = "PROJECT|7|not-an-atom|x";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        Files.writeString(file(), "MXP1\t3\t" + body.length + "\t" + RecordLine.sha256Hex(body) + "\t" + payload + "\n",
                StandardOpenOption.APPEND);
        ProjectionCheckpoint.Snapshot s = read();
        assertFalse(s.isSound());
        assertTrue(s.problems().get(0).contains("malformed PROJECT"), s.problems().toString());
    }

    @Test
    void anAbandonedBatchFailsTheCheckpoint() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            try (ProjectionCheckpoint.Batch batch = c.begin(1)) {
                batch.write(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1)));
                // abandoned: no cover
            }
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.begin(1));
            assertTrue(e.getMessage().contains("failed earlier"), e.getMessage());
            assertEquals(0, c.coveredSequence());
        }
        ProjectionCheckpoint.Snapshot s = read();
        assertTrue(s.isSound(), s.problems().toString());
        assertEquals(0, s.coveredSequence(), "the abandoned entries are never applied");
    }

    @Test
    void snapshotCountsTheLinesItRead() throws IOException {
        try (ProjectionCheckpoint c = ProjectionCheckpoint.create(file(), SCOPE, 128)) {
            c.commit(1, DIGEST, List.of(new Entry(EntryKind.PROJECT, ATOM_A, ref("e1", 1))));
            c.commit(2, DIGEST, List.of());
        }
        ProjectionCheckpoint.Snapshot s = read();
        assertEquals(Files.readAllLines(file()).size(), s.lines(), "header + entry + 2 covers");
        assertEquals(4, s.lines());
        try (ProjectionCheckpoint c = ProjectionCheckpoint.openForAppend(file(), SCOPE, s)) {
            c.commit(3, DIGEST, List.of());
        }
        ProjectionCheckpoint.Snapshot after = read();
        assertTrue(after.isSound(), "line numbering continued from the snapshot's count: " + after.problems());
        assertEquals(5, after.lines());
    }
}
