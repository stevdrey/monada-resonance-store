package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ArtifactRef;
import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptResult;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.StageRecorded;
import com.monada.core.execution.RouteDescriptor;
import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionLedgerTest {
    @TempDir
    Path root;

    private Path segment() {
        return root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE))
                .resolve("ledger").resolve("events-000001.log");
    }

    private void appendAll(List<ExecutionEvent> events) throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (ExecutionEvent e : events) {
                assertEquals(AppendResult.Status.APPENDED, ledger.append(e).status(), e.eventId().value());
            }
        }
    }

    @Test
    void appendCloseReopenReplayPreservesEveryValueAndOrder() throws IOException {
        List<ExecutionEvent> events = Events.fullRun();
        appendAll(events);

        try (ExecutionLedger reopened = ExecutionLedger.open(root, Events.SCOPE)) {
            List<LedgerRecord> replay = reopened.replay();
            assertEquals(events.size(), replay.size());
            for (int i = 0; i < events.size(); i++) {
                assertEquals(i + 1, replay.get(i).sequence());
                assertEquals(events.get(i), replay.get(i).event());
            }
            assertEquals(events.get(2), reopened.find(EventId.of("e3")).orElseThrow().event());
            assertTrue(reopened.find(EventId.of("missing")).isEmpty());
        }
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertTrue(reader.isHealthy());
        assertEquals(events, reader.replay().stream().map(LedgerRecord::event).toList());
    }

    @Test
    void replayViewAppliesCorrectionsAndKeepsHistory() throws IOException {
        appendAll(Events.fullRun());
        ExecutionReplay view = ExecutionLedgerReader.open(root, Events.SCOPE).view();
        ExecutionReplay.ExecutionView run = view.execution(Events.EXEC).orElseThrow();
        assertFalse(run.isPending());
        assertEquals(AttemptResult.FAILED, run.attempts().get(0).result().orElseThrow());
        assertEquals(8, run.history().size());
    }

    @Test
    void incompleteButValidRunsReplayAsPendingAndInProgress() throws IOException {
        appendAll(List.of(Events.started("e1"), Events.attemptStarted("e2"), Events.stage("e3", Events.ATTEMPT)));
        ExecutionReplay.ExecutionView run = ExecutionLedgerReader.open(root, Events.SCOPE).view()
                .execution(Events.EXEC).orElseThrow();
        assertTrue(run.isPending());
        assertTrue(run.attempts().get(0).isInProgress());
    }

    @Test
    void identicalAppendIsIdempotentAndLeavesBytesUnchanged() throws IOException {
        appendAll(Events.fullRun());
        byte[] before = Files.readAllBytes(segment());
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            AppendResult again = ledger.append(Events.stage("e3", Events.ATTEMPT));
            assertEquals(AppendResult.Status.IDEMPOTENT, again.status());
            assertEquals(3, again.sequence());
        }
        assertEquals(new String(before, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(segment()), StandardCharsets.UTF_8));
    }

    @Test
    void sameIdWithDifferentPayloadIsAConflictWithoutMutation() throws IOException {
        appendAll(List.of(Events.started("e1")));
        byte[] before = Files.readAllBytes(segment());
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            AppendResult result = ledger.append(
                    Events.started("e1", Events.SCOPE, Events.EXEC, "different summary"));
            assertEquals(AppendResult.Status.CONFLICT, result.status());
            assertTrue(result.reason().isPresent());
            // same execution started again under a new event id is also a clash
            assertEquals(AppendResult.Status.CONFLICT, ledger.append(Events.started("e-other")).status());
        }
        assertEquals(List.of(before.length), List.of(Files.readAllBytes(segment()).length));
    }

    @Test
    void wrongScopeIsRejectedBeforeAnyIo() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> ledger.append(
                    Events.started("e1", ScopeId.of("other"), Events.EXEC, "x")));
            assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, e.category());
        }
        assertEquals(0, Files.size(segment()), "nothing was appended");
    }

    @Test
    void referencesAndOrderAreValidatedBeforeAppend() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            LedgerRejectedException noExecution = assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(Events.attemptStarted("a1")));
            assertEquals(LedgerDiagnosticCategory.DANGLING_REFERENCE, noExecution.category());

            ledger.append(Events.started("e1"));
            assertEquals(LedgerDiagnosticCategory.DANGLING_REFERENCE, assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(Events.stage("s0", Events.ATTEMPT))).category());
            assertEquals(LedgerDiagnosticCategory.DANGLING_REFERENCE, assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(Events.accepted("o0", AttemptId.of("ghost")))).category());

            ledger.append(Events.attemptStarted("e2"));
            ledger.append(Events.finished("e3", Events.ATTEMPT));
            assertEquals(LedgerDiagnosticCategory.INVALID_ORDER, assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(Events.stage("s1", Events.ATTEMPT))).category());
            assertEquals(LedgerDiagnosticCategory.DANGLING_REFERENCE, assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(Events.correctFinish("c0", "nope", Events.ATTEMPT))).category());
            assertEquals(3, ledger.replay().size());

            assertEquals(AppendResult.Status.APPENDED, ledger.append(Events.correctFinish("c1", "e3",
                    Events.ATTEMPT)).status());
            // the corrected revision is no longer the latest
            assertEquals(AppendResult.Status.CONFLICT, ledger.append(Events.correctFinish("c2", "e3",
                    Events.ATTEMPT)).status());
        }
    }

    @Test
    void oversizedRecordsAreRejectedBeforeAppend() throws IOException {
        List<ArtifactRef> refs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            refs.add(ArtifactRef.of("\u00fc".repeat(500), "\u00fc".repeat(500)));
        }
        ExecutionEvent oversized = StageRecorded.measured(
                EventEnvelope.original(EventId.of("o"), Events.SCOPE, Events.TASK, Events.EXEC, Events.ATTEMPT,
                        Events.T1, Events.T2),
                "s", RouteDescriptor.workerOnly("w"), Events.T0, Events.T1, List.of(), refs);
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(Events.attemptStarted("e2"));
            long size = Files.size(segment());
            LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> ledger.append(oversized));
            assertEquals(LedgerDiagnosticCategory.OVERSIZED_RECORD, e.category());
            assertEquals(2, ledger.replay().size());
            assertEquals(size, Files.size(segment()));
        }
    }

    @Test
    void secondWriterFailsImmediatelyAndLockIsReleasedOnClose() throws IOException {
        ExecutionLedger first = ExecutionLedger.open(root, Events.SCOPE);
        assertThrows(LedgerLockedException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        // a read-only reader never contends with the writer
        assertTrue(ExecutionLedgerReader.open(root, Events.SCOPE).isHealthy());
        first.close();
        first.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> first.append(Events.started("e1")));
        assertThrows(IllegalStateException.class, first::replay);
        try (ExecutionLedger second = ExecutionLedger.open(root, Events.SCOPE)) {
            assertEquals(AppendResult.Status.APPENDED, second.append(Events.started("e1")).status());
        }
    }

    @Test
    void lockIsReleasedWhenOpenFails() throws IOException {
        appendAll(List.of(Events.started("e1")));
        Files.writeString(segment(), "garbage\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        LedgerException e = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertFalse(e.diagnostics().isEmpty());
        // not left locked: a read-only look works and a different scope can still be opened for writing
        assertFalse(ExecutionLedgerReader.open(root, Events.SCOPE).isHealthy());
        try (ExecutionLedger other = ExecutionLedger.open(root, ScopeId.of("scope-2"))) {
            assertEquals(0, other.replay().size());
        }
    }

    @Test
    void writableOpenRefusesTornTailAndNeverRepairs() throws IOException {
        appendAll(Events.fullRun());
        byte[] healthy = Files.readAllBytes(segment());
        Files.write(segment(), "MXL1\t9\t12\tabc".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        long damaged = Files.size(segment());
        LedgerException e = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.TORN_TAIL, e.diagnostics().get(0).category());
        assertEquals(damaged, Files.size(segment()), "nothing was truncated");

        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertTrue(reader.hasTornTail());
        assertEquals(Events.fullRun().size(), reader.replay().size(), "valid prefix is replayed");
        LedgerDiagnostic d = reader.diagnostics().get(0);
        assertEquals(LedgerDiagnostic.Severity.WARNING, d.severity());
        assertEquals(Events.fullRun().size() + 1, d.line());
        assertTrue(healthy.length < damaged);
    }

    @Test
    void newLedgerIsLegacyAwareAndUsesItsOwnManifest() throws IOException {
        Files.writeString(root.resolve("manifest.json"), "{}");
        LedgerException e = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertTrue(e.getMessage().contains("legacy"));
        assertFalse(Files.exists(root.resolve("write.lock")));
        assertInstanceOf(LedgerException.class, assertThrows(IOException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)));
    }
}
