package com.monada.storage.execution;

import java.nio.file.Path;

/** Command-line entry point: audits an execution ledger directory without modifying it. */
public final class ExecutionLedgerAuditMain {
    private ExecutionLedgerAuditMain() {
    }

    public static void main(String[] args) {
        String dir = System.getProperty("monada.execution.dir");
        if ((dir == null || dir.isBlank()) && args.length > 0 && !args[0].isBlank()) {
            dir = args[0];
        }
        if (dir == null || dir.isBlank()) {
            System.err.println("Usage: ExecutionLedgerAuditMain <ledger-directory>");
            System.err.println("   or: ./gradlew :monada-storage:runExecutionLedgerAudit "
                    + "-Dmonada.execution.dir=/path/to/ledger");
            System.exit(1);
            return;
        }
        ExecutionLedgerAuditReport report = new ExecutionLedgerAuditor().audit(Path.of(dir));
        System.out.println(report.render());
        if (report.hasErrors()) {
            System.exit(1);
        }
    }
}
