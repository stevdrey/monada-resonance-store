package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the eighth Codex review round of PR 107. */
class LedgerRound8Test {
    @TempDir
    Path root;

    private final ExecutionLedgerAuditor auditor = new ExecutionLedgerAuditor();

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
        }
    }

    private Path scopeDir() {
        return root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE));
    }

    private void assertEscapeBlocksNewScope() {
        LedgerException read = assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, read.diagnostics().get(0).category());
        LedgerException write = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, write.diagnostics().get(0).category());
        assertFalse(Files.exists(scopeDir()), "no empty scope was published while history may be hidden");
    }

    @Test
    void escapingEntryWhileCheckingForDisplacedScopeFailsClosed(@TempDir Path outside) throws IOException {
        healthy();
        Files.move(scopeDir(), outside.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(root.resolve("scopes").resolve("s-" + "d".repeat(64)),
                    outside.resolve("elsewhere"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        assertEscapeBlocksNewScope();
    }

    @Test
    void escapingScopeIdSymlinkInAnotherDirectoryFailsClosed(@TempDir Path outside) throws IOException {
        healthy();
        Files.delete(scopeDir().resolve("ledger").resolve("events-000001.log"));
        Files.delete(scopeDir().resolve("ledger"));
        Files.move(scopeDir().resolve("scope.id"), outside.resolve("scope.id"));
        Files.delete(scopeDir());
        Path other = root.resolve("scopes").resolve("s-" + "e".repeat(64));
        Files.createDirectory(other);
        try {
            Files.createSymbolicLink(other.resolve("scope.id"), outside.resolve("scope.id"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        assertEscapeBlocksNewScope();
    }

    @Test
    void newRootIsPublishedTogetherWithItsManifest() throws Exception {
        Path parent = root.resolve("parent");
        Path ledgerRoot = parent.resolve("ledger-root");
        try (ExecutionLedger ledger = ExecutionLedger.open(ledgerRoot, Events.SCOPE)) {
            assertEquals(0, ledger.replay().size());
        }
        assertEquals(LedgerManifest.render(), Files.readString(ledgerRoot.resolve("execution-manifest.json")));
        try (var entries = Files.list(parent)) {
            assertEquals(List.of("ledger-root"), entries.map(p -> p.getFileName().toString()).toList(),
                    "no initialisation leftovers next to the root");
        }
        assertTrue(auditor.audit(ledgerRoot).isHealthy());
    }

    @Test
    void concurrentReaderNeverSeesARootWithoutManifest() throws Exception {
        for (int round = 0; round < 40; round++) {
            Path ledgerRoot = root.resolve("race-" + round);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<String> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                while (!done.get() && failure.get() == null) {
                    try {
                        ExecutionLedgerReader.open(ledgerRoot, Events.SCOPE);
                    } catch (LedgerException e) {
                        boolean absent = e.diagnostics().isEmpty();
                        if (!absent) {
                            failure.set(e.diagnostics().toString());
                        }
                    } catch (IOException e) {
                        failure.set(e.toString());
                    }
                }
            });
            reader.start();
            try (ExecutionLedger ledger = ExecutionLedger.open(ledgerRoot, Events.SCOPE)) {
                ledger.append(Events.started("e1"));
            } finally {
                done.set(true);
                reader.join();
            }
            assertEquals(null, failure.get(), "round " + round);
        }
    }

    @Test
    void presentNonRegularManifestIsInvalidNotMissing() throws Exception {
        healthy();
        Path manifest = root.resolve("execution-manifest.json");
        Files.delete(manifest);
        Files.createDirectory(manifest);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, report.diagnostics().get(0).category(), report.render());
        assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));

        Files.delete(manifest);
        LedgerRound3Test.mkfifo(manifest);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                    () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
            assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID,
                    auditor.audit(root).diagnostics().get(0).category());
        });

        Files.delete(manifest);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_MISSING, auditor.audit(root).diagnostics().get(0).category(),
                "a genuinely absent manifest is still MISSING");
    }
}
