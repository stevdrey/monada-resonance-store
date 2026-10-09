package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the findings of the code review of PR 107. */
class LedgerHardeningTest {
    @TempDir
    Path root;

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(Events.attemptStarted("e2"));
        }
    }

    @Test
    void newRootGetsTheSamePermissionsAsAnOrdinaryDirectory() throws IOException {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path reference = Files.createDirectory(root.resolve("reference"));
        Path created = root.resolve("created-by-open");
        try (ExecutionLedger ledger = ExecutionLedger.open(created, Events.SCOPE)) {
            assertEquals(0, ledger.replay().size());
        }
        Set<PosixFilePermission> expected = Files.getPosixFilePermissions(reference);
        assertEquals(expected, Files.getPosixFilePermissions(created));
    }

    @Test
    void segmentThatVanishesAfterOpenIsNeverRecreatedOrWrittenElsewhere() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            Files.delete(segment);
            assertThrows(LedgerException.class, () -> ledger.append(Events.stage("s1", Events.ATTEMPT)));
            assertFalse(Files.exists(segment), "the writer must not recreate the history file");
            assertThrows(IllegalStateException.class, ledger::replay);
        }
    }

    @Test
    void segmentReplacedAfterOpenIsRefused() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            byte[] original = Files.readAllBytes(segment);
            Files.delete(segment);
            Files.write(segment, original); // same bytes, different file
            assertThrows(LedgerException.class, () -> ledger.append(Events.stage("s1", Events.ATTEMPT)));
            assertEquals(original.length, Files.size(segment), "nothing was appended to the replacement either");
        }
    }

    @Test
    void manifestOfAFutureVersionIsUnsupportedNotCorrupt() throws IOException {
        healthy();
        Path manifest = root.resolve("execution-manifest.json");
        Files.writeString(manifest, "{\"format\":\"monada-execution-ledger\",\"version\":\"2\","
                + "\"recordCodec\":\"MXL2\",\"maxRecordBytes\":1048576,\"compression\":\"zstd\"}\n");
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, assertThrows(LedgerException.class,
                () -> ExecutionLedger.open(root, Events.SCOPE)).diagnostics().get(0).category());
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION,
                new ExecutionLedgerAuditor().audit(root).diagnostics().get(0).category());

        Files.writeString(manifest, "{\"format\":\"something-else\",\"version\":\"2\"}");
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
        Files.writeString(manifest, "{\"format\":\"monada-execution-ledger\"}");
        assertEquals(LedgerDiagnosticCategory.MANIFEST_INVALID, assertThrows(LedgerException.class,
                () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
    }

    @Test
    void auditScopeDirectoryOnlyAcceptsScopeDirectoryNames() throws IOException {
        healthy();
        LedgerPaths paths = ExecutionLedgerReader.openRoot(root);
        for (String bad : new String[] {"..", "../x", "scopes", "s-xyz", "", "s-" + "A".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> ExecutionLedgerReader.auditScopeDirectory(paths, bad), bad);
        }
        String good = LedgerPaths.scopeDirectoryName(Events.SCOPE);
        assertEquals(2, ExecutionLedgerReader.auditScopeDirectory(paths, good).recordsValidated());
    }

    @Test
    void largeLedgersSpanningReadChunksReplayCompletely() throws IOException {
        int stages = 1200;
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            ledger.append(Events.started("e1"));
            ledger.append(Events.attemptStarted("e2"));
            for (int i = 0; i < stages; i++) {
                assertEquals(AppendResult.Status.APPENDED,
                        ledger.append(Events.stage("stage-" + i, Events.ATTEMPT)).status());
            }
        }
        assertTrue(Files.size(LedgerFiles.segment(root)) > 4 * (1 << 16), "the file spans several read chunks");
        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        assertTrue(reader.isHealthy(), reader.diagnostics().toString());
        assertEquals(stages + 2, reader.replay().size());
        assertEquals(stages + 2, new ExecutionLedgerAuditor().audit(root).recordsValidated());
        try (ExecutionLedger reopened = ExecutionLedger.open(root, Events.SCOPE)) {
            assertEquals(stages + 2, reopened.replay().size());
        }
    }
}
