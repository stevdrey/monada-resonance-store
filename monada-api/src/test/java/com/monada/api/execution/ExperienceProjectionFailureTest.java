package com.monada.api.execution;

import static com.monada.api.execution.Experiences.appended;
import static com.monada.api.execution.Experiences.attempt;
import static com.monada.api.execution.Experiences.checkpoint;
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

import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ExecutionLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperienceProjectionFailureTest {
    static final ScopeId SCOPE = ScopeId.of("project-alpha");

    @TempDir
    Path root;

    private static void two(ExecutionMemory m) {
        experience(m, SCOPE, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
        experience(m, SCOPE, "x-2", "Speed up the gradle build", "Enabled the configuration cache", null);
    }

    private static void third(ExecutionMemory m) {
        experience(m, SCOPE, "x-3", "Fix the flaky upload test", "Retried the upload on timeouts", null);
    }

    /** Reference result: the same ledger projected without any failure. */
    private List<String> reference(String query) {
        Path clean = root.resolve("reference");
        try (ExecutionMemory m = ExecutionMemory.open(clean, SCOPE)) {
            two(m);
            third(m);
            return render(m.recall(SCOPE, query, 10));
        }
    }

    private static Map<String, String> digests(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(f).toString(), java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Test
    void failedProjectionWriteKeepsTheLedgerAppendAndMarksStale() {
        List<String> expected = reference("flaky test");
        Path store = root.resolve("store");
        boolean[] fail = {false};
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE, ExecutionMemoryConfig.defaults(), seq -> {
            if (fail[0]) {
                throw new IOException("injected projection failure at " + seq);
            }
        })) {
            two(m);
            fail[0] = true;
            RecordResult r = m.record(start(SCOPE, "x-3", "Fix the flaky upload test"));
            assertEquals(RecordResult.Status.APPENDED, r.status(), "ledger success is reported as is");
            assertEquals(7, r.sequence());
            ProjectionStatus stale = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.STALE, stale.state());
            assertEquals(6, stale.coveredSequence());
            assertEquals(7, stale.ledgerSequence());
            assertTrue(stale.diagnostics().get(0).contains("sequence 7 failed"), stale.diagnostics().toString());

            fail[0] = false;
            appended(m, attempt(SCOPE, "x-3", "a1", 1, null));
            appended(m, finish(SCOPE, "x-3", "a1", "Retried the upload on timeouts", null));
            assertEquals(9, m.loadEvent(SCOPE, com.monada.core.execution.EventId.of("x-3-a1-fin")).orElseThrow()
                    .sequence(), "the failed event was not re-appended");
            assertEquals(ProjectionStatus.State.STALE, m.projectionStatus(SCOPE).state(), "no silent catch-up");
            assertEquals(6, m.projectionStatus(SCOPE).coveredSequence());
            ExperienceRecall partial = m.recall(SCOPE, "flaky test", 10);
            assertEquals(ProjectionStatus.State.STALE, partial.status().state());
            assertEquals(2, partial.hits().size(), "answers from the covered prefix only");
            partial.hits().forEach(h -> assertEquals(6, h.coveredSequence()));
        }
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            assertEquals(ProjectionStatus.State.STALE, m.projectionStatus(SCOPE).state(), "survives reopen");
            ProjectionStatus rebuilt = m.rebuildProjection(SCOPE);
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertEquals(9, rebuilt.coveredSequence());
            assertEquals(expected, render(m.recall(SCOPE, "flaky test", 10)));
            byte[] once = checkpointBytes(store, SCOPE);
            m.rebuildProjection(SCOPE);
            assertEquals(expected, render(m.recall(SCOPE, "flaky test", 10)), "reconciliation is idempotent");
            assertArrayEquals(once, checkpointBytes(store, SCOPE));
            assertArrayEquals(checkpointBytes(root.resolve("reference"), SCOPE), once);
        }
    }

    @Test
    void interruptedCheckpointWriteIsStaleUntilRebuilt() throws IOException {
        List<String> expected = reference("flaky test");
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
            third(m);
        }
        // Simulate a crash in the middle of the last batch: drop the last COVER and leave a torn line.
        Path file = checkpoint(store, SCOPE);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.write(file, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);
        Files.writeString(file, "MXP1\t99\t3", StandardOpenOption.APPEND);
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.STALE, status.state());
            assertEquals(8, status.coveredSequence());
            assertTrue(status.diagnostics().get(0).contains("interrupted"), status.diagnostics().toString());
            // The atom of the uncovered batch exists in the memory but is never reported nor counted.
            List<ExperienceHit> one = m.recall(SCOPE, "flaky upload test", 1).hits();
            assertEquals(1, one.size(), "the uncovered atom does not consume the slot");
            assertFalse(one.get(0).ref().execution().value().equals("x-3"));
            m.recall(SCOPE, "flaky test", 10).hits()
                    .forEach(h -> assertFalse(h.ref().execution().value().equals("x-3")));
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, "flaky test", 10)));
        }
    }

    @Test
    void ledgerAppendedOutsideTheFacadeIsStale() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        try (ExecutionLedger ledger = ExecutionLedger.open(store, SCOPE)) {
            ledger.append(start(SCOPE, "x-3", "Fix the flaky upload test"));
        }
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            assertEquals(ProjectionStatus.State.STALE, m.projectionStatus(SCOPE).state());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
    }

    @Test
    void scopeWithoutProjectionIsMissingUntilRebuilt() throws IOException {
        Path store = root.resolve("store");
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
            expected = render(m.recall(SCOPE, "gradle cache", 10));
        }
        // A scope recorded before projections existed (#97) has no projection directory.
        deleteTree(projectionDir(store, SCOPE));
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            assertFalse(Files.exists(projectionDir(store, SCOPE)), "open never builds a projection for history");
            ExperienceRecall recall = m.recall(SCOPE, "gradle cache", 10);
            assertEquals(ProjectionStatus.State.MISSING, recall.status().state());
            assertEquals(List.of(), recall.hits());
            appended(m, start(SCOPE, "x-3", "Fix the flaky upload test"));
            assertEquals(ProjectionStatus.State.MISSING, m.projectionStatus(SCOPE).state());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, "gradle cache", 10)));
        }
    }

    @Test
    void corruptProjectionIsIncompatibleAndNeverRewrittenAutomatically() throws IOException {
        Path store = root.resolve("store");
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
            expected = render(m.recall(SCOPE, "gradle cache", 10));
        }
        Path file = checkpoint(store, SCOPE);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("|x-2-a1-fin|", "|x-2-a1-fiN|"), StandardCharsets.UTF_8);
        Map<String, String> before = digests(projectionDir(store, SCOPE));
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().get(0).contains("digest"), status.diagnostics().toString());
            assertEquals(List.of(), m.recall(SCOPE, "gradle cache", 10).hits());
            appended(m, start(SCOPE, "x-3", "Fix the flaky upload test"));
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
            assertEquals(before, digests(projectionDir(store, SCOPE)), "nothing repaired in place");
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(expected, render(m.recall(SCOPE, "gradle cache", 10)));
        }
    }

    @Test
    void checkpointThatDisagreesWithTheLedgerIsIncompatible() throws IOException {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        try (ExecutionMemory m = ExecutionMemory.open(a, SCOPE)) {
            two(m);
        }
        try (ExecutionMemory m = ExecutionMemory.open(b, SCOPE)) {
            experience(m, SCOPE, "x-1", "A different task", "Another solution", null);
            experience(m, SCOPE, "x-2", "Yet another task", "More", null);
        }
        // A well-formed projection of another ledger is detected by its cover digests.
        Files.copy(checkpoint(b, SCOPE), checkpoint(a, SCOPE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (ExecutionMemory m = ExecutionMemory.open(a, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().get(0).contains("different ledger"), status.diagnostics().toString());
        }
    }

    @Test
    void missingProjectionMemoryIsIncompatible() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        Files.delete(projectionDir(store, SCOPE).resolve("memory").resolve("manifest.json"));
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
    }

    @Test
    void readOnlyOpenCreatesNothingAndCannotRebuild() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        deleteTree(projectionDir(store, SCOPE));
        Map<String, String> before = digests(store);
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE)) {
            assertEquals(ProjectionStatus.State.MISSING, m.projectionStatus(SCOPE).state());
            assertEquals(List.of(), m.recall(SCOPE, "gradle", 5).hits());
            assertThrows(UnsupportedOperationException.class, () -> m.rebuildProjection(SCOPE));
        }
        ScopeId unknown = ScopeId.of("never-recorded");
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, unknown)) {
            assertEquals(ProjectionStatus.State.MISSING, m.projectionStatus(unknown).state());
            assertEquals(List.of(), m.recall(unknown, "gradle", 5).hits());
        }
        assertEquals(before, digests(store));
        assertFalse(Files.exists(projectionDir(store, unknown).getParent()));
    }

    @Test
    void symbolicLinkInsideTheProjectionIsIncompatibleAndNeverFollowed() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        Path feedback = projectionDir(store, SCOPE).resolve("memory").resolve("feedback");
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Files.move(feedback.resolve("feedback-000001.log"), outside.resolve("feedback-000001.log"));
        Files.delete(feedback);
        Files.createSymbolicLink(feedback, outside);
        Map<String, String> outsideBefore = digests(outside);
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().get(0).contains("symbolic link"), status.diagnostics().toString());
        }
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
            appended(m, start(SCOPE, "x-3", "Fix the flaky upload test"));
            assertEquals(outsideBefore, digests(outside), "nothing written through the link");
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
        }
        assertEquals(outsideBefore, digests(outside), "rebuild removes the link, never its target");
        assertFalse(Files.isSymbolicLink(feedback));
    }

    @Test
    void diagnosingADamagedProjectionRecreatesNothing() throws IOException {
        for (String damaged : List.of("feedback/feedback-000001.log", "atoms/segment-000001.log")) {
            Path store = root.resolve("store-" + damaged.substring(0, damaged.indexOf('/')));
            try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
                two(m);
            }
            Path file = projectionDir(store, SCOPE).resolve("memory").resolve(damaged);
            Files.delete(file);
            Map<String, String> before = digests(projectionDir(store, SCOPE));
            try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE)) {
                ProjectionStatus status = m.projectionStatus(SCOPE);
                assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state(), damaged);
                assertTrue(status.diagnostics().get(0).contains(damaged), status.diagnostics().toString());
            }
            try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
                assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state(), damaged);
            }
            assertFalse(Files.exists(file), "verification never recreates " + damaged);
            assertEquals(before, digests(projectionDir(store, SCOPE)));
        }
    }

    @Test
    void mappedAtomWithoutAVectorIsIncompatible() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        // Drop the last vector frame and its index entry: the store stays structurally valid.
        Path memory = projectionDir(store, SCOPE).resolve("memory");
        Path index = memory.resolve("indexes/vector-map.idx");
        Path segment = memory.resolve("vectors/segment-000001.f32");
        List<String> entries = Files.readAllLines(index, StandardCharsets.UTF_8);
        assertEquals(2, entries.size());
        long frame = Files.size(segment) / entries.size();
        Files.write(index, entries.subList(0, 1), StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.truncate(frame);
        }
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().toString().contains("ATOM_WITHOUT_VECTOR"), status.diagnostics().toString());
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(SCOPE).state());
            assertEquals(2, m.recall(SCOPE, "gradle flaky test", 10).hits().size());
        }
    }

    @Test
    void readOnlyOpenRetriesWhenAWriterCompletesAnEventMidOpen() {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        int[] attempts = {0};
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE, ExecutionMemoryConfig.defaults(),
                attempt -> {
                    attempts[0] = attempt;
                    if (attempt == 1) {
                        // After the reader's ledger snapshot, a writer appends and projects one more event, so
                        // the checkpoint now covers a sequence beyond this snapshot.
                        try (ExecutionMemory writer = ExecutionMemory.open(store, SCOPE)) {
                            appended(writer, start(SCOPE, "x-3", "Fix the flaky upload test"));
                        }
                    }
                })) {
            assertEquals(2, attempts[0], "one retry with a fresh snapshot");
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.CURRENT, status.state(), status.diagnostics().toString());
            assertEquals(7, status.ledgerSequence());
        }
    }

    @Test
    void readOnlyOpenGivesUpAfterBoundedAttemptsWhileFilesKeepChanging() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        Path segment = projectionDir(store, SCOPE).resolve("memory/vectors/segment-000001.f32");
        int[] attempts = {0};
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE, ExecutionMemoryConfig.defaults(),
                attempt -> {
                    attempts[0] = attempt;
                    try { // a writer that is always midway through a vector frame
                        Files.write(segment, new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })) {
            assertEquals(ExecutionMemory.READ_ONLY_ATTEMPTS, attempts[0]);
            ProjectionStatus status = m.projectionStatus(SCOPE);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, status.state());
            assertTrue(status.diagnostics().get(0).contains("live writer"), status.diagnostics().toString());
        }
    }

    @Test
    void stableDamageIsReportedOnTheFirstReadOnlyAttempt() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, SCOPE)) {
            two(m);
        }
        Path file = checkpoint(store, SCOPE);
        Files.writeString(file, Files.readString(file).replace("|x-2-a1-fin|", "|x-2-a1-fiN|"));
        int[] attempts = {0};
        try (ExecutionMemory m = ExecutionMemory.openReadOnly(store, SCOPE, ExecutionMemoryConfig.defaults(),
                attempt -> attempts[0] = attempt)) {
            assertEquals(1, attempts[0]);
            assertEquals(ProjectionStatus.State.INCOMPATIBLE, m.projectionStatus(SCOPE).state());
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
