package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.core.execution.AttemptId;
import com.monada.core.execution.AttemptReason;
import com.monada.core.execution.EventEnvelope;
import com.monada.core.execution.EventId;
import com.monada.core.execution.ExecutionEvent;
import com.monada.core.execution.ExecutionEvent.AttemptStarted;
import com.monada.core.execution.ExecutionEvent.Correction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the Codex review of PR 107. */
class LedgerReviewFixesTest {
    @TempDir
    Path root;

    private final ExecutionLedgerAuditor auditor = new ExecutionLedgerAuditor();

    private static final AttemptId SECOND = AttemptId.of("attempt-2");
    private static final AttemptId THIRD = AttemptId.of("attempt-3");

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(Events.attemptStarted("e2"));
            ledger.append(Events.finished("e3", Events.ATTEMPT));
            ledger.append(Events.attemptStarted("e4", SECOND, 2, Optional.of(Events.ATTEMPT)));
            ledger.append(Events.attemptStarted("e5", THIRD, 3, Optional.of(SECOND)));
        }
    }

    private static ExecutionEvent reorder(String id, String target, AttemptId attempt, int ordinal) {
        int revision = 2;
        EventEnvelope outer = new EventEnvelope(EventId.of(id), Events.SCOPE, Events.TASK, Events.EXEC,
                Optional.of(attempt), revision, Optional.of(EventId.of(target)), Events.T0, Events.T0);
        EventEnvelope inner = new EventEnvelope(EventId.of(id), Events.SCOPE, Events.TASK, Events.EXEC,
                Optional.of(attempt), 1, Optional.empty(), Events.T0, Events.T0);
        return new Correction(outer, new AttemptStarted(inner, ordinal, Optional.of(Events.ATTEMPT),
                AttemptReason.RETRY), "renumbered");
    }

    @Test
    void correctedAttemptOrdinalsStayUniqueAndTheIndexFollowsCorrections() throws IOException {
        healthy();
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            // ordinal 2 belongs to the second attempt
            assertEquals(AppendResult.Status.CONFLICT, ledger.append(reorder("c1", "e5", THIRD, 2)).status());
            assertEquals(AppendResult.Status.APPENDED, ledger.append(reorder("c2", "e4", SECOND, 4)).status());
            // ordinal 2 was released by the correction, ordinal 4 is now taken
            AttemptId fourth = AttemptId.of("attempt-4");
            assertEquals(AppendResult.Status.CONFLICT, ledger.append(
                    Events.attemptStarted("e6", fourth, 4, Optional.of(THIRD))).status());
            assertEquals(AppendResult.Status.APPENDED, ledger.append(
                    Events.attemptStarted("e7", fourth, 2, Optional.of(THIRD))).status());
        }
        assertTrue(auditor.audit(root).isHealthy(), auditor.audit(root).render());
    }

    @Test
    void auditScansTheDiscoveredDirectoryEvenWhenItWasRenamed() throws IOException {
        healthy();
        Path scopes = root.resolve("scopes");
        Path original = scopes.resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE));
        Path renamed = scopes.resolve("s-" + "a".repeat(64));
        Files.move(original, renamed);
        // damage the moved records so only an audit of the discovered directory can notice
        Path segment = renamed.resolve("ledger").resolve("events-000001.log");
        Files.write(segment, "MXL1\t7\t1".getBytes(), java.nio.file.StandardOpenOption.APPEND);

        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertFalse(report.isHealthy(), report.render());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.category()
                == LedgerDiagnosticCategory.SCOPE_ID_MISMATCH), report.render());
    }

    @Test
    void auditRejectsSymlinkedScopeBeforeReadingAnythingOutside(@TempDir Path outside) throws IOException {
        healthy();
        Path scopeDir = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE));
        Path external = outside.resolve("external");
        Files.move(scopeDir, external);
        try {
            Files.createSymbolicLink(scopeDir, external);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        Map<String, String> before = LedgerFiles.digests(outside);
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, report.diagnostics().get(0).category(), report.render());
        assertFalse(report.render().contains("events-0000"), "no external file names leak");
        assertEquals(before, LedgerFiles.digests(outside));
    }

    @Test
    void missingOrReplacedLedgerDirectoryIsAnIntegrityError() throws IOException {
        healthy();
        Path ledgerDir = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE)).resolve("ledger");
        Path segment = ledgerDir.resolve("events-000001.log");
        Files.delete(segment);
        Files.delete(ledgerDir);
        assertFalse(auditor.audit(root).isHealthy());
        assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
        assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));

        Files.writeString(ledgerDir, "now a file");
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertFalse(report.isHealthy(), report.render());
        assertEquals(0, report.recordsValidated());
    }

    @Test
    void newScopeIsPublishedCompleteAndStaleStagingIsDiscarded() throws IOException {
        healthy();
        com.monada.core.execution.ScopeId other = com.monada.core.execution.ScopeId.of("scope-2");
        Path staging = root.resolve("scopes").resolve(".staging-" + LedgerPaths.scopeDirectoryName(other));
        Files.createDirectories(staging.resolve("junk"));
        try (ExecutionLedger ledger = ExecutionLedger.open(root, other)) {
            Path dir = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(other));
            assertTrue(Files.isRegularFile(dir.resolve("scope.id")));
            assertTrue(Files.isDirectory(dir.resolve("ledger")));
            assertFalse(Files.exists(staging));
            assertEquals(0, ledger.replay().size());
        }
        assertTrue(auditor.audit(root).isHealthy());
    }

    @Test
    void writableOpenRefusesToRecreateAMissingManifestOverExistingData() throws IOException {
        healthy();
        Path manifest = root.resolve("execution-manifest.json");
        Files.delete(manifest);
        LedgerException e = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.MANIFEST_MISSING, e.diagnostics().get(0).category());
        assertFalse(Files.exists(manifest), "no manifest was asserted for unknown data");
        // lock released: a brand new root still opens
        try (ExecutionLedger fresh = ExecutionLedger.open(root.resolve("fresh"), Events.SCOPE)) {
            assertEquals(0, fresh.replay().size());
        }
    }

    @Test
    void oversizedMetadataFilesAreRejectedWithoutBeingLoaded() throws IOException {
        healthy();
        Path manifest = root.resolve("execution-manifest.json");
        Files.write(manifest, new byte[8 * 1024 * 1024]);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        Files.writeString(manifest, LedgerManifest.render());

        Path scopeId = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE)).resolve("scope.id");
        Files.write(scopeId, new byte[8 * 1024 * 1024]);
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertFalse(auditor.audit(root).isHealthy());
    }

    @Test
    void corruptScopeNamedAuditProbeDoesNotStopTheWholeAudit() throws IOException {
        healthy();
        com.monada.core.execution.ScopeId probe = com.monada.core.execution.ScopeId.of("audit-probe");
        try (ExecutionLedger ledger = ExecutionLedger.open(root, probe)) {
            ledger.append(Events.started("p1", probe, Events.EXEC, "probe scope"));
        }
        Path segment = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(probe))
                .resolve("ledger").resolve("events-000001.log");
        Files.write(segment, "garbage\n".getBytes(), java.nio.file.StandardOpenOption.APPEND);
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertEquals(2, report.scopesChecked(), report.render());
        assertFalse(report.isHealthy());
        assertEquals(6, report.recordsValidated(), "5 healthy records plus the valid probe prefix");
    }

    @Test
    void malformedUtf8InScopeIdIsDetectedEvenForReplacementCharacterScopes() throws IOException {
        com.monada.core.execution.ScopeId odd = com.monada.core.execution.ScopeId.of("s\uFFFD");
        try (ExecutionLedger ledger = ExecutionLedger.open(root, odd)) {
            ledger.append(Events.started("e1", odd, Events.EXEC, "odd scope"));
        }
        Path scopeId = root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(odd)).resolve("scope.id");
        Files.write(scopeId, new byte[] {'s', (byte) 0xFF}); // malformed: decodes to "s\uFFFD" if lenient
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, odd)).diagnostics().get(0).category());
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                () -> ExecutionLedger.open(root, odd)).diagnostics().get(0).category());
        assertFalse(auditor.audit(root).isHealthy());
    }

    @Test
    void explicitPendingOutcomeReplaysAsPending() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(ExecutionEvent.outcomeRecorded(EventId.of("o1"), Events.SCOPE, Events.TASK, Events.EXEC,
                    com.monada.core.execution.Outcome.pending(com.monada.core.execution.OutcomeOrigin.VALIDATED),
                    Events.T1));
            assertTrue(ledger.view().execution(Events.EXEC).orElseThrow().isPending());
            ledger.append(ExecutionEvent.outcomeRecorded(EventId.of("o2"), Events.SCOPE, Events.TASK, Events.EXEC,
                    com.monada.core.execution.Outcome.rejected(com.monada.core.execution.OutcomeOrigin.VALIDATED),
                    Events.T2));
            assertFalse(ledger.view().execution(Events.EXEC).orElseThrow().isPending());
        }
    }
}
