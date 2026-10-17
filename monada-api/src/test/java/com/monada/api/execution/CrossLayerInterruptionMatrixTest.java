package com.monada.api.execution;

import static com.monada.api.execution.Experiences.appended;
import static com.monada.api.execution.Experiences.attempt;
import static com.monada.api.execution.Experiences.checkpointBytes;
import static com.monada.api.execution.Experiences.experience;
import static com.monada.api.execution.Experiences.finish;
import static com.monada.api.execution.Experiences.projectionDir;
import static com.monada.api.execution.Experiences.render;
import static com.monada.api.execution.Experiences.scopeDir;
import static com.monada.api.execution.Experiences.start;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.EventId;
import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ExecutionLedgerAuditReport;
import com.monada.storage.execution.ExecutionLedgerAuditor;
import com.monada.storage.execution.LedgerDiagnostic;
import com.monada.storage.execution.LedgerDiagnosticCategory;
import com.monada.storage.execution.ProjectionLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-layer interruption matrix (issue #127): what an interruption between the authoritative ledger and the
 * derived projection leaves behind, which status reports it and which explicit call reconciles it. Only the
 * cells no other test covers live here; the full matrix, with the tests of the covered cells, is in
 * {@code docs/specs/execution-memory-contract-v1.md} §18.6.
 *
 * <ul>
 *   <li>W1 before the ledger append: {@link #refusedLedgerAppendLeavesNoFactAndTheProjectionCurrent},
 *       {@link #tornLedgerTailIsRefusedByTheWriterAndReportedButNeverRepaired}.</li>
 *   <li>W2 after the durable append, before COVER: {@link #retryAfterAFailedProjectionWriteIsIdempotentAndStaysStale},
 *       {@link #readOnlySnapshotOfAStaleProjectionAnswersFromTheCoveredPrefixOnly}.</li>
 *   <li>W3 during generation construction: {@link #rebuildInterruptedBeforeACoverKeepsThePublishedGeneration}.</li>
 *   <li>W4 publication: {@link #crashBetweenTemporaryPointerAndMoveKeepsTheOldGeneration}.</li>
 *   <li>W5 reader against writer: {@link #snapshotOpenedBetweenLedgerAppendAndCoverIsStaleAndFrozen}.</li>
 * </ul>
 * Scope isolation and the absence of read-only mutations are checked inside these cells.
 */
class CrossLayerInterruptionMatrixTest {
    static final ScopeId ALPHA = ScopeId.of("project-alpha");
    static final ScopeId BETA = ScopeId.of("project-beta");

    @TempDir
    Path root;

    private static void two(ExecutionMemory m, ScopeId scope) {
        experience(m, scope, "x-1", "Repair the flaky login test", "Waited for the session cookie", null);
        experience(m, scope, "x-2", "Speed up the gradle build", "Enabled the configuration cache", null);
    }

    private static Path segment(Path store, ScopeId scope) {
        return scopeDir(store, scope).resolve("ledger/events-000001.log");
    }

    private static Map<String, String> digests(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.toList()) {
                String name = dir.relativize(f).toString();
                out.put(name, Files.isDirectory(f) ? "dir" : java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static List<String> generations(Path store, ScopeId scope) throws IOException {
        return ProjectionLayout.of(store, scope).generationDirs().stream()
                .map(p -> p.getFileName().toString()).toList();
    }

    private static String pointer(Path store, ScopeId scope) throws IOException {
        return Files.readString(ProjectionLayout.of(store, scope).pointer(), StandardCharsets.UTF_8);
    }

    private static boolean mentions(ExperienceRecall recall, String execution) {
        return recall.hits().stream().anyMatch(h -> h.ref().execution().value().equals(execution));
    }

    /** Faults that fail the incremental write of one sequence and, when armed, every rebuild COVER. */
    private static final class Faults implements ProjectionFaults {
        long failIncrementalAt = -1;
        boolean failRebuild;
        boolean rebuilding;

        @Override
        public void beforeCommit(long ledgerSequence) throws IOException {
            if (rebuilding ? failRebuild : ledgerSequence == failIncrementalAt) {
                throw new IOException("injected interruption before COVER " + ledgerSequence);
            }
        }
    }

    private static void third(ExecutionMemory m) {
        appended(m, attempt(ALPHA, "x-3", "a1", 1, null));
        appended(m, finish(ALPHA, "x-3", "a1", "Retried the upload on timeouts", null));
    }

    /**
     * STALE alpha store: x-1, x-2 projected (sequence 6); the projection of x-3's start (7) failed, so its
     * attempt and finish (8, 9) are appended but never projected.
     */
    private Path staleStore(Faults faults) {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA, ExecutionMemoryConfig.defaults(), faults)) {
            two(m, ALPHA);
            faults.failIncrementalAt = 7;
            assertEquals(RecordResult.Status.APPENDED,
                    m.record(start(ALPHA, "x-3", "Fix the flaky upload test")).status());
            faults.failIncrementalAt = -1;
            third(m);
            assertEquals(ProjectionStatus.State.STALE, m.projectionStatus(ALPHA).state());
        }
        return store;
    }

    // ---- W1: interrupted before the ledger append ------------------------------------------------------

    @Test
    void refusedLedgerAppendLeavesNoFactAndTheProjectionCurrent() throws IOException {
        Path store = root.resolve("store");
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            two(m, ALPHA);
            byte[] checkpoint = checkpointBytes(store, ALPHA);
            // Same bytes, new file: the held channel no longer writes to the file readers see.
            Path segment = segment(store, ALPHA);
            Path copy = segment.resolveSibling("copy.tmp");
            Files.copy(segment, copy);
            Files.move(copy, segment, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            byte[] ledger = Files.readAllBytes(segment);

            assertThrows(UncheckedIOException.class, () -> m.record(start(ALPHA, "x-3", "Fix the flaky upload test")));
            ProjectionStatus status = m.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, status.state(), "nothing was appended, nothing is stale");
            assertEquals(6, status.coveredSequence());
            assertEquals(6, status.ledgerSequence());
            assertArrayEquals(checkpoint, checkpointBytes(store, ALPHA), "the projection never saw the event");
            assertArrayEquals(ledger, Files.readAllBytes(segment));
            assertThrows(IllegalStateException.class, () -> m.record(start(ALPHA, "x-4", "Another task")),
                    "the ledger stays unusable until reopened, no automatic retry");
        }
        assertTrue(new ExecutionLedgerAuditor().audit(store).isHealthy());
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            assertEquals(6, m.history(ALPHA).entries().size());
            assertTrue(m.loadEvent(ALPHA, EventId.of("x-3-start")).isEmpty(), "no ghost fact");
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(ALPHA).state());
            RecordResult retried = m.record(start(ALPHA, "x-3", "Fix the flaky upload test"));
            assertEquals(RecordResult.Status.APPENDED, retried.status(), "the caller's retry is a new append");
            assertEquals(7, retried.sequence());
            assertEquals(7, m.projectionStatus(ALPHA).coveredSequence());
        }
    }

    @Test
    void tornLedgerTailIsRefusedByTheWriterAndReportedButNeverRepaired() throws IOException {
        Path store = root.resolve("store");
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            two(m, ALPHA);
            expected = render(m.recall(ALPHA, "flaky test", 10));
        }
        // A crash in the middle of writing record 7: the ledger ends in a partial line.
        Files.writeString(segment(store, ALPHA), "MXL1\t7\t99\tpartial", StandardOpenOption.APPEND);
        Map<String, String> before = digests(store);

        UncheckedIOException refused = assertThrows(UncheckedIOException.class, () -> ExecutionMemory.open(store, ALPHA));
        String reason = refused.getCause().getMessage();
        assertTrue(reason.contains("no automatic repair") && reason.contains("incomplete final record"), reason);
        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(store, ALPHA)) {
            assertTrue(reader.hasTornTail(), "the torn tail is reported, not hidden");
            assertEquals(6, reader.history(ALPHA).entries().size());
            ProjectionStatus status = reader.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, status.state(), "the projection covers the valid prefix");
            assertEquals(6, status.ledgerSequence());
            assertEquals(expected, render(reader.recall(ALPHA, "flaky test", 10)));
        }
        ExecutionLedgerAuditReport audit = new ExecutionLedgerAuditor().audit(store);
        assertTrue(audit.diagnostics().stream().anyMatch(d -> d.category() == LedgerDiagnosticCategory.TORN_TAIL
                && d.severity() == LedgerDiagnostic.Severity.WARNING), audit.render());
        assertEquals(before, digests(store), "neither layer repairs or truncates anything");
    }

    // ---- W2: after the durable append, before COVER ----------------------------------------------------

    @Test
    void retryAfterAFailedProjectionWriteIsIdempotentAndStaysStale() throws IOException {
        Path store = root.resolve("store");
        List<String> betaRecall;
        try (ExecutionMemory beta = ExecutionMemory.open(store, BETA)) {
            experience(beta, BETA, "y-1", "Repair the flaky upload test", "Raised the socket timeout", null);
            betaRecall = render(beta.recall(BETA, "flaky upload test", 10));
        }
        Map<String, String> betaFiles = digests(scopeDir(store, BETA));

        Faults faults = new Faults();
        staleStore(faults);
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA, ExecutionMemoryConfig.defaults(), faults)) {
            byte[] ledger = Files.readAllBytes(segment(store, ALPHA));
            byte[] checkpoint = checkpointBytes(store, ALPHA);
            RecordResult same = m.record(start(ALPHA, "x-3", "Fix the flaky upload test"));
            assertEquals(RecordResult.Status.IDEMPOTENT, same.status());
            assertEquals(7, same.sequence(), "the accepted fact keeps its sequence");
            RecordResult changed = m.record(start(ALPHA, "x-3", "Fix the upload test differently"));
            assertEquals(RecordResult.Status.CONFLICT, changed.status());
            assertArrayEquals(ledger, Files.readAllBytes(segment(store, ALPHA)), "no duplicate fact");
            assertArrayEquals(checkpoint, checkpointBytes(store, ALPHA), "a retry never repairs the projection");
            ProjectionStatus status = m.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.STALE, status.state());
            assertEquals(6, status.coveredSequence());
            assertEquals(9, status.ledgerSequence());

            ProjectionStatus rebuilt = m.rebuildProjection(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertEquals(9, rebuilt.coveredSequence());
            ExperienceRecall recall = m.recall(ALPHA, "flaky upload test", 10);
            assertEquals(1, recall.hits().stream().filter(h -> h.ref().execution().value().equals("x-3")).count(),
                    "the retried fact is projected once");
            recall.hits().forEach(h -> assertEquals(ALPHA, h.ref().scope()));
        }
        Path reference = root.resolve("reference");
        try (ExecutionMemory m = ExecutionMemory.open(reference, ALPHA)) {
            two(m, ALPHA);
            appended(m, start(ALPHA, "x-3", "Fix the flaky upload test"));
            third(m);
        }
        assertArrayEquals(checkpointBytes(reference, ALPHA), checkpointBytes(store, ALPHA),
                "reconciliation equals an uninterrupted run");
        assertTrue(new ExecutionLedgerAuditor().audit(store).isHealthy());

        assertEquals(betaFiles, digests(scopeDir(store, BETA)), "the other scope is untouched");
        try (ExecutionMemory beta = ExecutionMemory.openReadOnly(store, BETA)) {
            assertEquals(ProjectionStatus.State.CURRENT, beta.projectionStatus(BETA).state());
            assertEquals(betaRecall, render(beta.recall(BETA, "flaky upload test", 10)));
        }
    }

    @Test
    void readOnlySnapshotOfAStaleProjectionAnswersFromTheCoveredPrefixOnly() throws IOException {
        Path store = staleStore(new Faults());
        Map<String, String> before = digests(store);
        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(store, ALPHA)) {
            ProjectionStatus status = reader.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.STALE, status.state());
            assertEquals(6, status.coveredSequence());
            assertEquals(9, status.ledgerSequence());
            ExperienceRecall recall = reader.recall(ALPHA, "flaky upload test", 10);
            assertEquals(ProjectionStatus.State.STALE, recall.status().state());
            assertFalse(recall.hits().isEmpty());
            assertFalse(mentions(recall, "x-3"), "the uncovered fact is never served");
            recall.hits().forEach(h -> assertEquals(6, h.coveredSequence()));
            assertThrows(UnsupportedOperationException.class, () -> reader.rebuildProjection(ALPHA));
        }
        assertEquals(before, digests(store), "a read-only open neither repairs nor creates anything");
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.rebuildProjection(ALPHA).state());
        }
        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(store, ALPHA)) {
            assertEquals(ProjectionStatus.State.CURRENT, reader.projectionStatus(ALPHA).state());
            assertTrue(mentions(reader.recall(ALPHA, "flaky upload test", 10), "x-3"));
        }
    }

    // ---- W3: interrupted while a generation is built -------------------------------------------------

    @Test
    void rebuildInterruptedBeforeACoverKeepsThePublishedGeneration() throws IOException {
        Faults faults = new Faults();
        Path store = staleStore(faults);
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA, ExecutionMemoryConfig.defaults(), faults)) {
            String published = pointer(store, ALPHA);
            List<String> generations = generations(store, ALPHA);
            byte[] ledger = Files.readAllBytes(segment(store, ALPHA));
            byte[] checkpoint = checkpointBytes(store, ALPHA);
            List<String> stale = render(m.recall(ALPHA, "flaky test", 10));

            faults.rebuilding = true;
            faults.failRebuild = true;
            assertThrows(UncheckedIOException.class, () -> m.rebuildProjection(ALPHA));
            ProjectionStatus status = m.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.STALE, status.state(), "the published generation still answers");
            assertEquals(6, status.coveredSequence());
            assertEquals(9, status.ledgerSequence());
            assertEquals(published, pointer(store, ALPHA));
            assertEquals(generations, generations(store, ALPHA), "the half-built generation is deleted");
            assertArrayEquals(checkpoint, checkpointBytes(store, ALPHA));
            assertArrayEquals(ledger, Files.readAllBytes(segment(store, ALPHA)), "the ledger is never rolled back");
            assertEquals(stale, render(m.recall(ALPHA, "flaky test", 10)));

            faults.failRebuild = false;
            ProjectionStatus rebuilt = m.rebuildProjection(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertEquals(9, rebuilt.coveredSequence());
        }
    }

    // ---- W4: interrupted while the pointer is published ----------------------------------------------

    @Test
    void crashBetweenTemporaryPointerAndMoveKeepsTheOldGeneration() throws IOException {
        Path store = root.resolve("store");
        List<String> expected;
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            two(m, ALPHA);
            expected = render(m.recall(ALPHA, "flaky test", 10));
        }
        // The crash left a complete but unpublished generation and the temporary pointer naming it.
        ProjectionLayout layout = ProjectionLayout.of(store, ALPHA);
        Path old = projectionDir(store, ALPHA);
        Path orphan = layout.scopeDir().resolve("projection-000002");
        try (Stream<Path> walk = Files.walk(old)) {
            for (Path p : walk.toList()) {
                Files.copy(p, orphan.resolve(old.relativize(p).toString()));
            }
        }
        Files.writeString(layout.pointerTemp(), "projection-000002\n", StandardCharsets.UTF_8);
        Map<String, String> before = digests(store);

        try (ExecutionMemory reader = ExecutionMemory.openReadOnly(store, ALPHA)) {
            assertEquals(ProjectionStatus.State.CURRENT, reader.projectionStatus(ALPHA).state());
            assertEquals(expected, render(reader.recall(ALPHA, "flaky test", 10)));
        }
        assertEquals(before, digests(store), "a reader never completes or reclaims an interrupted publication");
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA)) {
            assertEquals(ProjectionStatus.State.CURRENT, m.projectionStatus(ALPHA).state());
            assertEquals(old, projectionDir(store, ALPHA), "the temporary pointer is never promoted");
            ProjectionStatus rebuilt = m.rebuildProjection(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, rebuilt.state());
            assertEquals("projection-000003\n", pointer(store, ALPHA));
            assertEquals(List.of("projection-000003"), generations(store, ALPHA), "the orphan is reclaimed");
            assertFalse(Files.exists(layout.pointerTemp(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals(expected, render(m.recall(ALPHA, "flaky test", 10)));
        }
    }

    // ---- W5: a reader opens while the writer is mid-record ------------------------------------------

    @Test
    void snapshotOpenedBetweenLedgerAppendAndCoverIsStaleAndFrozen() throws IOException {
        Path store = root.resolve("store");
        ExecutionMemory[] snapshot = new ExecutionMemory[1];
        Map<String, Map<String, String>> around = new TreeMap<>();
        ProjectionFaults openReaderBeforeCover = seq -> {
            if (seq == 9) {
                around.put("before", digests(store));
                snapshot[0] = ExecutionMemory.openReadOnly(store, ALPHA);
                around.put("after", digests(store));
            }
        };
        try (ExecutionMemory m = ExecutionMemory.open(store, ALPHA, ExecutionMemoryConfig.defaults(),
                openReaderBeforeCover)) {
            two(m, ALPHA);
            appended(m, start(ALPHA, "x-3", "Fix the flaky upload test"));
            third(m);
            ProjectionStatus written = m.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, written.state(), "the writer finishes its COVER");
            assertEquals(9, written.coveredSequence());
        }
        try (ExecutionMemory reader = snapshot[0]) {
            assertEquals(around.get("before"), around.get("after"), "the reader mutated nothing");
            ProjectionStatus status = reader.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.STALE, status.state(), status.diagnostics().toString());
            assertEquals(8, status.coveredSequence());
            assertEquals(9, status.ledgerSequence(), "the durable fact is in the snapshot's ledger");
            assertFalse(mentions(reader.recall(ALPHA, "flaky upload test", 10), "x-3"),
                    "no hit from an uncovered fact, even after the writer covered it");
        }
        try (ExecutionMemory fresh = ExecutionMemory.openReadOnly(store, ALPHA)) {
            ProjectionStatus status = fresh.projectionStatus(ALPHA);
            assertEquals(ProjectionStatus.State.CURRENT, status.state());
            assertEquals(9, status.coveredSequence());
            assertTrue(mentions(fresh.recall(ALPHA, "flaky upload test", 10), "x-3"));
        }
    }
}
