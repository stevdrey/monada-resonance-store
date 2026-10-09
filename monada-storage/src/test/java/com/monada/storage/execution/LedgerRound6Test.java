package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.core.execution.ScopeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the sixth Codex review round of PR 107. */
class LedgerRound6Test {
    @TempDir
    Path root;

    private final ExecutionLedgerAuditor auditor = new ExecutionLedgerAuditor();

    private void healthy() throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(root, Events.SCOPE)) {
            for (var e : Events.fullRun()) {
                ledger.append(e);
            }
        }
    }

    private Path scopeDir() {
        return root.resolve("scopes").resolve(LedgerPaths.scopeDirectoryName(Events.SCOPE));
    }

    private static List<LedgerDiagnosticCategory> categories(ExecutionLedgerAuditReport report) {
        return report.diagnostics().stream().map(LedgerDiagnostic::category).toList();
    }

    private static String line(String sequenceField, String payload, boolean goodDigest) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String digest = goodDigest ? RecordLine.sha256Hex(bytes) : "0".repeat(64);
        return "MXL1\t" + sequenceField + "\t" + bytes.length + "\t" + digest + "\t" + payload;
    }

    @Test
    void malformedSequenceFieldDoesNotHideIndependentDefects() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        int valid = lines.size();
        lines.add(line("02", "9|FUTURE", true));          // non-canonical sequence + unknown schema
        lines.add(line("abc", "1|X", false));             // unparseable sequence + wrong digest
        LedgerFiles.writeLines(segment, lines);

        ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, Events.SCOPE);
        List<LedgerDiagnosticCategory> found = reader.diagnostics().stream().map(LedgerDiagnostic::category).toList();
        // two invalid sequence fields, plus the truncated schema-1 payload of the second line
        assertEquals(3, found.stream().filter(c -> c == LedgerDiagnosticCategory.MALFORMED_RECORD).count(), found.toString());
        assertTrue(found.contains(LedgerDiagnosticCategory.UNSUPPORTED_SCHEMA), found.toString());
        assertTrue(found.contains(LedgerDiagnosticCategory.DIGEST_MISMATCH), found.toString());
        assertEquals(valid, reader.replay().size());
    }

    @Test
    void damagedScopeIdDoesNotStopTheStructuralScanOfThatScope() throws IOException {
        healthy();
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        String victim = lines.get(lines.size() - 1);
        lines.set(lines.size() - 1, victim.substring(0, victim.length() - 2) + "ZZ"); // digest mismatch at the end
        LedgerFiles.writeLines(segment, lines);
        Files.write(segment, "MXL1\t99".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Path scopeId = scopeDir().resolve("scope.id");
        byte[] original = Files.readAllBytes(scopeId);

        byte[][] damages = {null, new byte[8 * 1024 * 1024], {'s', (byte) 0xFF}, "other-scope".getBytes()};
        for (byte[] damage : damages) {
            if (damage == null) {
                Files.delete(scopeId);
            } else {
                Files.write(scopeId, damage);
            }
            ExecutionLedgerAuditReport report = auditor.audit(root);
            List<LedgerDiagnosticCategory> found = categories(report);
            assertTrue(found.contains(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH), report.render());
            assertTrue(found.contains(LedgerDiagnosticCategory.DIGEST_MISMATCH), report.render());
            assertTrue(found.contains(LedgerDiagnosticCategory.TORN_TAIL), report.render());
            // a scope.id that names another valid scope makes every record foreign; the others are unknown ids
            boolean foreign = damage != null && damage.length == "other-scope".length();
            assertEquals(foreign ? 0 : Events.fullRun().size() - 1, report.recordsValidated(),
                    "valid prefix still counted when the scope id is unknown");
            assertFalse(report.isHealthy());
        }
        Files.write(scopeId, original);
        assertEquals(LedgerDiagnosticCategory.DIGEST_MISMATCH, auditor.audit(root).diagnostics().stream()
                .filter(d -> d.severity() == LedgerDiagnostic.Severity.ERROR).findFirst().orElseThrow().category());
    }

    @Test
    void displacedScopeDirectoryIsRejectedInsteadOfBeingRecreatedEmpty() throws IOException {
        healthy();
        Path scopes = root.resolve("scopes");
        Path displaced = scopes.resolve("s-" + "b".repeat(64));
        Files.move(scopeDir(), displaced);

        LedgerException read = assertThrows(LedgerException.class, () -> ExecutionLedgerReader.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, read.diagnostics().get(0).category());
        LedgerException write = assertThrows(LedgerException.class, () -> ExecutionLedger.open(root, Events.SCOPE));
        assertEquals(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, write.diagnostics().get(0).category());
        assertFalse(Files.exists(scopeDir()), "no empty scope was published over the displaced history");
        try (var entries = Files.list(scopes)) {
            assertEquals(List.of(displaced.getFileName().toString()),
                    entries.map(p -> p.getFileName().toString()).toList(), "no staging leftovers either");
        }
    }

    @Test
    void displacedDirectoryOfAnotherScopeDoesNotBlockNewScopes() throws IOException {
        healthy();
        Files.move(scopeDir(), root.resolve("scopes").resolve("s-" + "c".repeat(64)));
        ScopeId other = ScopeId.of("some-other-scope");
        assertTrue(ExecutionLedgerReader.open(root, other).isHealthy());
        try (ExecutionLedger ledger = ExecutionLedger.open(root, other)) {
            assertEquals(0, ledger.replay().size());
        }
    }
}
