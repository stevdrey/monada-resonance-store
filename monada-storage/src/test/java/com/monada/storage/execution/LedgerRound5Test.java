package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the fifth Codex review round of PR 107. */
class LedgerRound5Test {
    @TempDir
    Path root;

    private final ExecutionLedgerAuditor auditor = new ExecutionLedgerAuditor();

    private static final AttemptId A2 = AttemptId.of("a2");
    private static final AttemptId A3 = AttemptId.of("a3");

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (var e : Events.fullRun()) {
                ledger.append(e);
            }
        }
    }

    private static ExecutionEvent relink(String id, String target, AttemptId attempt, int ordinal, AttemptId previous) {
        return relink(id, target, 2, attempt, ordinal, previous);
    }

    private static ExecutionEvent relink(String id, String target, int revision, AttemptId attempt, int ordinal,
                                         AttemptId previous) {
        EventEnvelope outer = new EventEnvelope(EventId.of(id), Events.SCOPE, Events.TASK, Events.EXEC,
                Optional.of(attempt), revision, Optional.of(EventId.of(target)), Events.T0, Events.T0);
        EventEnvelope inner = new EventEnvelope(EventId.of(id), Events.SCOPE, Events.TASK, Events.EXEC,
                Optional.of(attempt), 1, Optional.empty(), Events.T0, Events.T0);
        return new Correction(outer, new AttemptStarted(inner, ordinal, Optional.of(previous),
                AttemptReason.RETRY), "relinked");
    }

    @Test
    void newScopesPublishAnEmptySegmentAndItsLossIsDetected() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            assertEquals(0, ledger.replay().size());
        }
        assertEquals(0, Files.size(LedgerFiles.segment(root)));
        assertTrue(auditor.audit(root).isHealthy());

        healthy2();
        Files.delete(LedgerFiles.segment(root));
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertFalse(Files.exists(LedgerFiles.segment(root)), "history is not silently restarted");
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertFalse(report.isHealthy(), report.render());
    }

    private void healthy2() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
        }
    }

    @Test
    void renamedScopeDirectoryIsReportedAndStillScanned() throws IOException {
        healthy();
        Path scopes = root.resolve("scopes");
        Path renamed = scopes.resolve("s-" + "b".repeat(64));
        Files.move(scopes.resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE)), renamed);
        Files.write(renamed.resolve("ledger").resolve("events-000001.log"), "MXL1\t99".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        ExecutionLedgerAuditReport report = auditor.audit(root);
        List<LedgerDiagnosticCategory> categories = report.diagnostics().stream().map(LedgerDiagnostic::category).toList();
        assertTrue(categories.contains(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH), report.render());
        assertTrue(categories.contains(LedgerDiagnosticCategory.TORN_TAIL), report.render());
        assertEquals(Events.fullRun().size(), report.recordsValidated());
        assertFalse(report.isHealthy());
    }

    @Test
    void correctionsCannotCreateAPreviousAttemptCycle() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(Events.attemptStarted("e2"));
            ledger.append(Events.attemptStarted("e3", A2, 2, Optional.of(Events.ATTEMPT)));
            ledger.append(Events.attemptStarted("e4", A3, 3, Optional.of(A2)));

            // a2 -> a3 would close the loop a2 <-> a3
            LedgerRejectedException cycle = assertThrows(LedgerRejectedException.class,
                    () -> ledger.append(relink("c1", "e3", A2, 2, A3)));
            assertEquals(LedgerDiagnosticCategory.INVALID_ORDER, cycle.category());
            assertEquals(4, ledger.replay().size());

            // re-pointing a3 at the first attempt is a legal, acyclic change
            assertEquals(AppendResult.Status.APPENDED, ledger.append(
                    relink("c2", "e4", A3, 3, Events.ATTEMPT)).status());
            // and now a2 -> a3 is fine (a3 no longer leads back to a2)
            assertEquals(AppendResult.Status.APPENDED, ledger.append(relink("c3", "e3", A2, 2, A3)).status());
            // the reverse link would loop again
            assertThrows(LedgerRejectedException.class, () -> ledger.append(relink("c4", "c2", 3, A3, 3, A2)));
        }
        assertTrue(auditor.audit(root).isHealthy(), auditor.audit(root).render());
    }

    @Test
    void auditChecksTheWriterLockPathWithoutAcquiringIt() throws Exception {
        healthy();
        Path lock = root.resolve("write.lock");
        Files.delete(lock);
        Files.createDirectory(lock);
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertFalse(report.isHealthy(), report.render());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.file().equals("write.lock")), report.render());
        assertEquals(Events.fullRun().size(), report.recordsValidated(), "scopes are still audited");
        assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));

        Files.delete(lock);
        LedgerRound3Test.mkfifo(lock);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertFalse(auditor.audit(root).isHealthy()));

        Files.delete(lock);
        assertTrue(auditor.audit(root).isHealthy(), "an absent lock is fine for a read-only audit");
        assertFalse(Files.exists(lock), "the audit never creates the lock");
    }
}
