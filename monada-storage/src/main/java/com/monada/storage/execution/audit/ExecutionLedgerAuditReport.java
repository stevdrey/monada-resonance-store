package com.monada.storage.execution.audit;

import com.monada.storage.execution.LedgerDiagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Result of a read-only execution ledger audit: deterministic diagnostics plus simple counts. */
public record ExecutionLedgerAuditReport(Path root, List<LedgerDiagnostic> diagnostics, int scopesChecked,
                                         long recordsValidated) {
    public ExecutionLedgerAuditReport {
        Objects.requireNonNull(root, "root");
        List<LedgerDiagnostic> sorted = new ArrayList<>(Objects.requireNonNull(diagnostics, "diagnostics"));
        Collections.sort(sorted);
        diagnostics = List.copyOf(sorted);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == LedgerDiagnostic.Severity.ERROR);
    }

    public boolean isHealthy() {
        return !hasErrors();
    }

    public long count(LedgerDiagnostic.Severity severity) {
        return diagnostics.stream().filter(d -> d.severity() == severity).count();
    }

    public String render() {
        String nl = System.lineSeparator();
        String banner = "=".repeat(80);
        StringBuilder sb = new StringBuilder();
        sb.append(banner).append(nl);
        sb.append("Monada Execution Ledger Audit Report").append(nl);
        sb.append(banner).append(nl);
        sb.append("Ledger Root: ").append(root.toAbsolutePath().normalize()).append(nl);
        sb.append("Status: ").append(hasErrors() ? "CORRUPTED (ERRORS)"
                : count(LedgerDiagnostic.Severity.WARNING) > 0 ? "WARNINGS" : "HEALTHY").append(nl);
        sb.append("Scopes Checked: ").append(scopesChecked).append(nl);
        sb.append("Records Validated: ").append(recordsValidated).append(nl);
        sb.append(nl).append("Diagnostics (").append(diagnostics.size()).append(" total: ")
                .append(count(LedgerDiagnostic.Severity.ERROR)).append(" error, ")
                .append(count(LedgerDiagnostic.Severity.WARNING)).append(" warning, ")
                .append(count(LedgerDiagnostic.Severity.INFO)).append(" info):").append(nl);
        if (diagnostics.isEmpty()) {
            sb.append("  (no diagnostics)").append(nl);
        }
        diagnostics.forEach(d -> sb.append("  ").append(d).append(nl));
        sb.append(banner).append(nl);
        return sb.toString();
    }
}
