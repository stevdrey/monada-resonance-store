package com.monada.storage.execution.audit;

import com.monada.core.execution.ScopeId;
import com.monada.storage.execution.ExecutionLedgerReader;
import com.monada.storage.execution.LedgerDiagnostic;
import com.monada.storage.execution.LedgerDiagnosticCategory;
import com.monada.storage.execution.LedgerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only integrity audit of an execution ledger root. It validates the manifest, every scope directory
 * (name hash, {@code scope.id}, containment) and every record of every segment, and never creates, locks,
 * truncates or repairs anything: a missing root yields diagnostics, not directories.
 */
public final class ExecutionLedgerAuditor {
    private static final Pattern SCOPE_DIR = Pattern.compile("s-[0-9a-f]{64}");

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
        if (!Files.isRegularFile(manifest)) {
            boolean legacy = Files.exists(root.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS);
            findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.MANIFEST_MISSING, "execution-manifest.json",
                    0, OptionalLong.empty(), legacy
                    ? "execution-manifest.json is missing (the directory holds a legacy store manifest.json)"
                    : "execution-manifest.json is missing"));
            return;
        }
        // Reuse the reader's validation so manifest rules live in one place.
        try {
            ExecutionLedgerReader.open(root, ScopeId.of("audit-probe"));
        } catch (LedgerException e) {
            boolean manifestProblem = e.diagnostics().stream().anyMatch(d -> d.category()
                    != LedgerDiagnosticCategory.SCOPE_ID_MISMATCH);
            if (manifestProblem && !e.diagnostics().isEmpty()) {
                findings.addAll(e.diagnostics());
                return;
            }
            if (e.diagnostics().isEmpty()) {
                findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.MANIFEST_INVALID, ".", 0,
                        OptionalLong.empty(), e.getMessage()));
                return;
            }
        }
        Path scopesDir = root.resolve("scopes");
        if (!Files.exists(scopesDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> children;
        try (Stream<Path> list = Files.list(scopesDir)) {
            children = list.sorted().toList();
        }
        for (Path child : children) {
            auditScope(root, child, findings, scopes, records);
        }
    }

    private void auditScope(Path root, Path scopeDir, List<LedgerDiagnostic> findings, int[] scopes, long[] records)
            throws IOException {
        String name = scopeDir.getFileName().toString();
        String shown = "scopes/" + name;
        if (!SCOPE_DIR.matcher(name).matches() || !Files.isDirectory(scopeDir)) {
            findings.add(LedgerDiagnostic.warning(LedgerDiagnosticCategory.MANIFEST_INVALID, shown, 0,
                    OptionalLong.empty(), "unexpected entry in scopes/ (ignored)"));
            return;
        }
        scopes[0]++;
        Path idFile = scopeDir.resolve("scope.id");
        ScopeId scope;
        try {
            scope = ScopeId.of(Files.readString(idFile));
        } catch (IOException | RuntimeException e) {
            findings.add(LedgerDiagnostic.error(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown + "/scope.id", 0,
                    OptionalLong.empty(), "scope.id is missing, unreadable or not a valid scope id"));
            return;
        }
        // The reader enforces the directory-name hash, scope.id, containment and every record rule.
        try {
            ExecutionLedgerReader reader = ExecutionLedgerReader.open(root, scope);
            findings.addAll(reader.diagnostics());
            records[0] += reader.replay().size();
        } catch (LedgerException e) {
            findings.addAll(e.diagnostics().isEmpty()
                    ? List.of(LedgerDiagnostic.error(LedgerDiagnosticCategory.SCOPE_ID_MISMATCH, shown, 0,
                    OptionalLong.empty(), e.getMessage()))
                    : e.diagnostics());
        }
        Path ledgerDir = scopeDir.resolve("ledger");
        if (Files.isDirectory(ledgerDir)) {
            try (Stream<Path> list = Files.list(ledgerDir)) {
                for (Path entry : list.sorted().toList()) {
                    if (!entry.getFileName().toString().equals("events-000001.log")) {
                        findings.add(LedgerDiagnostic.warning(LedgerDiagnosticCategory.UNSUPPORTED_VERSION,
                                shown + "/ledger/" + entry.getFileName(), 0, OptionalLong.empty(),
                                "unexpected file in ledger directory (v1 reads a single segment; ignored)"));
                    }
                }
            }
        }
    }
}
