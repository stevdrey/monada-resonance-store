package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.monada.storage.execution.audit.ExecutionLedgerAuditReport;
import com.monada.storage.execution.audit.ExecutionLedgerAuditor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the third Codex review round of PR 107. */
class LedgerRound3Test {
    private static final Duration LIMIT = Duration.ofSeconds(10);

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

    /** Fixed absolute locations only: the test never resolves an executable through PATH. */
    private static final List<Path> MKFIFO = List.of(Path.of("/usr/bin/mkfifo"), Path.of("/bin/mkfifo"));

    static Path mkfifo(Path path) throws IOException, InterruptedException {
        Files.deleteIfExists(path);
        Path tool = MKFIFO.stream().filter(Files::isExecutable).findFirst().orElse(null);
        assumeTrue(tool != null, "mkfifo is not available at a known absolute path");
        Process p = new ProcessBuilder(tool.toString(), path.toString()).redirectErrorStream(true).start();
        assumeTrue(p.waitFor() == 0, "mkfifo failed");
        return path;
    }

    @Test
    void symlinkedScopesDirectoryIsRejectedBeforeListing(@TempDir Path outside) throws IOException {
        healthy();
        Path external = outside.resolve("elsewhere");
        Files.move(root.resolve("scopes"), external);
        Files.createDirectories(external.resolve("secret-looking-name"));
        try {
            Files.createSymbolicLink(root.resolve("scopes"), external);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e);
        }
        Map<String, String> before = LedgerFiles.digests(outside);
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertEquals(LedgerDiagnosticCategory.PATH_ESCAPE, report.diagnostics().get(0).category(), report.render());
        assertFalse(report.render().contains("secret-looking-name"));
        assertEquals(0, report.scopesChecked());
        assertEquals(before, LedgerFiles.digests(outside));
    }

    @Test
    void initialManifestIsPublishedAtomicallyWithoutLeftovers() throws IOException {
        Path hidden = root.resolve(".execution-manifest.json.tmp");
        Files.writeString(hidden, "stale partial");
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            assertEquals(LedgerManifest.render(), Files.readString(root.resolve("execution-manifest.json")));
            assertFalse(Files.exists(hidden));
            assertEquals(0, ledger.replay().size());
        }
        assertTrue(auditor.audit(root).isHealthy());
    }

    @Test
    void tornTailReportsTheSequenceOnlyWhenItsFieldIsComplete() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        Files.write(segment, "MXL1\t9\t12\tabc".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertEquals(OptionalLong.of(9), ExecutionLedgerReader.open(root, Events.SCOPE).diagnostics().get(0).sequence());
        assertEquals(OptionalLong.of(9), auditor.audit(root).diagnostics().get(0).sequence());

        Files.write(segment, new byte[0]);
        healthy2(segment);
        Files.write(segment, "MXL1\t9".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        LedgerDiagnostic truncated = ExecutionLedgerReader.open(root, Events.SCOPE).diagnostics().get(0);
        assertEquals(LedgerDiagnosticCategory.TORN_TAIL, truncated.category());
        assertEquals(OptionalLong.empty(), truncated.sequence(), "'9' could be a truncated '92'");
    }

    private void healthy2(Path segment) throws IOException {
        Files.delete(segment);
        Files.delete(root.resolve("write.lock"));
        Files.delete(root.resolve("execution-manifest.json"));
        deleteTree(root.resolve("scopes"));
        healthy();
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void scopeAccessorFollowsTheLifecycle() throws IOException {
        ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE);
        assertEquals(Events.SCOPE, ledger.scope());
        ledger.close();
        assertThrows(IllegalStateException.class, ledger::scope);
    }

    @Test
    void namedPipesInsteadOfMetadataAreReportedWithoutHanging() throws Exception {
        healthy();
        Path scopeId = scopeDir().resolve("scope.id");
        Files.delete(scopeId);
        mkfifo(scopeId);
        assertTimeoutPreemptively(LIMIT, () -> {
            assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, assertThrows(LedgerException.class,
                    () -> ExecutionLedgerReader.open(root, Events.SCOPE)).diagnostics().get(0).category());
            assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
            assertFalse(auditor.audit(root).isHealthy());
        });
        Files.delete(scopeId);
        Files.writeString(scopeId, Events.SCOPE.value());

        Path manifest = root.resolve("execution-manifest.json");
        String text = Files.readString(manifest);
        Files.delete(manifest);
        mkfifo(manifest);
        assertTimeoutPreemptively(LIMIT, () -> {
            assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
            assertFalse(auditor.audit(root).isHealthy());
        });
        Files.delete(manifest);
        Files.writeString(manifest, text);

        Path segment = LedgerFiles.segment(root);
        byte[] segmentBytes = Files.readAllBytes(segment);
        Files.delete(segment);
        mkfifo(segment);
        assertTimeoutPreemptively(LIMIT, () -> {
            assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
            assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
            assertFalse(auditor.audit(root).isHealthy());
        });
        Files.delete(segment);
        Files.write(segment, segmentBytes);

        Path lock = root.resolve("write.lock");
        Files.delete(lock);
        mkfifo(lock);
        assertTimeoutPreemptively(LIMIT, () ->
                assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE)));
    }
}
