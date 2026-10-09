package com.monada.storage.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.storage.execution.ExecutionLedger;
import com.monada.storage.execution.LedgerDiagnostic;
import com.monada.storage.execution.LedgerDiagnosticCategory;
import com.monada.storage.execution.LedgerFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionLedgerAuditorTest {
    @TempDir
    Path root;

    private final ExecutionLedgerAuditor auditor = new ExecutionLedgerAuditor();

    private void writeHealthy(Path dir) throws IOException {
        try (ExecutionLedger ledger = ExecutionLedger.open(dir, com.monada.storage.execution.TestScopes.SCOPE)) {
            for (var e : com.monada.storage.execution.TestScopes.fullRun()) {
                ledger.append(e);
            }
        }
    }

    @Test
    void healthyLedgerAuditsCleanAndStaysByteIdentical() throws IOException {
        writeHealthy(root);
        Map<String, String> before = LedgerFiles.digests(root);
        ExecutionLedgerAuditReport first = auditor.audit(root);
        ExecutionLedgerAuditReport second = auditor.audit(root);
        assertTrue(first.isHealthy());
        assertTrue(first.diagnostics().isEmpty(), first.render());
        assertEquals(1, first.scopesChecked());
        assertEquals(com.monada.storage.execution.TestScopes.fullRun().size(), first.recordsValidated());
        assertEquals(first.render(), second.render(), "deterministic output");
        assertEquals(before, LedgerFiles.digests(root));
    }

    @Test
    void corruptLedgerReportsDeterministicDiagnosticsAndIsNotModified() throws IOException {
        writeHealthy(root);
        Path segment = LedgerFiles.segment(root);
        List<String> lines = new ArrayList<>(LedgerFiles.lines(segment));
        lines.set(2, lines.get(2).substring(0, lines.get(2).length() - 2) + "ZZ");
        LedgerFiles.writeLines(segment, lines);
        Files.write(segment, "MXL1\t99\t5".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Map<String, String> before = LedgerFiles.digests(root);

        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertFalse(report.isHealthy());
        List<LedgerDiagnosticCategory> categories = report.diagnostics().stream().map(LedgerDiagnostic::category).toList();
        assertTrue(categories.contains(LedgerDiagnosticCategory.DIGEST_MISMATCH), report.render());
        assertTrue(categories.contains(LedgerDiagnosticCategory.TORN_TAIL), report.render());
        assertEquals(report.render(), auditor.audit(root).render());
        assertEquals(before, LedgerFiles.digests(root));
    }

    @Test
    void missingInputsAreReportedWithoutCreatingAnything(@TempDir Path parent) throws IOException {
        Path missing = parent.resolve("does-not-exist");
        ExecutionLedgerAuditReport report = auditor.audit(missing);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_MISSING, report.diagnostics().get(0).category());
        assertFalse(Files.exists(missing));

        Path empty = parent.resolve("empty");
        Files.createDirectories(empty);
        Map<String, String> before = LedgerFiles.digests(parent);
        assertEquals(LedgerDiagnosticCategory.MANIFEST_MISSING, auditor.audit(empty).diagnostics().get(0).category());
        assertEquals(before, LedgerFiles.digests(parent));

        Path legacy = parent.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("manifest.json"), "{}");
        before = LedgerFiles.digests(legacy);
        assertTrue(auditor.audit(legacy).diagnostics().get(0).message().contains("legacy"));
        assertEquals(before, LedgerFiles.digests(legacy));
    }

    @Test
    void unknownManifestVersionAndStrayFilesAreReported() throws IOException {
        writeHealthy(root);
        Path scopes = root.resolve("scopes");
        Files.writeString(scopes.resolve("stray.txt"), "x");
        Files.writeString(LedgerFiles.segment(root).getParent().resolve("events-000002.log"), "x");
        ExecutionLedgerAuditReport report = auditor.audit(root);
        assertTrue(report.isHealthy(), "stray entries are warnings only");
        assertEquals(2, report.count(LedgerDiagnostic.Severity.WARNING), report.render());

        Path manifest = root.resolve("execution-manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"version\":\"1\"", "\"version\":\"3\""));
        ExecutionLedgerAuditReport bad = auditor.audit(root);
        assertEquals(LedgerDiagnosticCategory.UNSUPPORTED_VERSION, bad.diagnostics().get(0).category());
        assertFalse(bad.isHealthy());
    }
}
