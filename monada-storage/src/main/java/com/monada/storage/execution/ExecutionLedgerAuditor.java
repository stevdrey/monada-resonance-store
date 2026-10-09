package com.monada.storage.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Read-only integrity audit of an execution ledger root. It validates the manifest, every scope directory
 * (name hash, {@code scope.id}, containment) and every record of every segment, and never creates, locks,
 * truncates or repairs anything: a missing root yields diagnostics, not directories.
 */
public final class ExecutionLedgerAuditor {

    public ExecutionLedgerAuditReport audit(Path root) {
        List<LedgerDiagnostic> findings = new ArrayList<>();
        int[] scopes = {0};
        long[] records = {0};
        try {
            auditRoot(root, findings, scopes, records);
        } catch (IOException e) {
            findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.MALFORMED_RECORD, ".", 0,
                    OptionalLong.empty(), "audit could not read the ledger: " + e.getMessage()));
        }
        return new ExecutionLedgerAuditReport(root, findings, scopes[0], records[0]);
    }

    private void auditRoot(Path root, List<LedgerDiagnostic> findings, int[] scopes, long[] records)
            throws IOException {
        if (!Files.isDirectory(root)) {
            findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.MANIFEST_MISSING, ".", 0,
                    OptionalLong.empty(), "ledger root does not exist or is not a directory"));
            return;
        }
        Path manifest = root.resolve("execution-manifest.json");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            boolean legacy = Files.exists(root.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS);
            findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.MANIFEST_MISSING, "execution-manifest.json",
                    0, OptionalLong.empty(), legacy
                    ? "execution-manifest.json is missing (the directory holds a legacy store manifest.json)"
                    : "execution-manifest.json is missing"));
            return;
        }
        LedgerPaths paths;
        try {
            paths = ExecutionLedgerReader.openRoot(root); // resolves the root and validates the manifest once
        } catch (LedgerException e) {
            findings.addAll(e.diagnostics().isEmpty()
                    ? List.of(LedgerDiagnostic.error(LedgerDiagnosticCategory.MANIFEST_INVALID, ".", 0,
                    OptionalLong.empty(), e.getMessage()))
                    : e.diagnostics());
            return;
        }
        try {
            ExecutionLedgerReader.verifyWriterLockPath(paths);
        } catch (LedgerException e) {
            findings.addAll(e.diagnostics());
        }
        List<String> names;
        try {
            names = ExecutionLedgerReader.listScopeDirectories(paths);
        } catch (LedgerException e) {
            findings.addAll(e.diagnostics());
            return;
        }
        for (String name : names) {
            auditScope(paths, name, findings, scopes, records);
        }
    }

    private void auditScope(LedgerPaths paths, String name, List<LedgerDiagnostic> findings, int[] scopes,
                            long[] records) {
        if (!LedgerPaths.SCOPE_DIR_NAME.matcher(name).matches()) {
            findings.add(LedgerDiagnostic.warning(LedgerDiagnosticCategory.MANIFEST_INVALID, "scopes/" + name, 0,
                    OptionalLong.empty(), "unexpected entry in scopes/ (ignored)"));
            return;
        }
        scopes[0]++;
        // Containment is checked before any child is read; scope.id is tied to the directory name and this
        // very directory is scanned even when scope.id is damaged.
        try {
            ExecutionLedgerReader.ScopeAudit audit = ExecutionLedgerReader.auditScopeDirectory(paths, name);
            findings.addAll(audit.diagnostics());
            records[0] += audit.recordsValidated();
        } catch (IOException e) {
            findings.addAll(e instanceof LedgerException le && !le.diagnostics().isEmpty()
                    ? le.diagnostics()
                    : List.of(LedgerDiagnostic.error(LedgerDiagnosticCategory.MALFORMED_RECORD, "scopes/" + name, 0,
                    OptionalLong.empty(), "scope could not be read: " + e.getMessage())));
        }
    }
}
