package com.monada.storage.audit;

import java.nio.file.Path;

public class StorageIntegrityAuditMain {

    public static void main(String[] args) {
        String storeDir = System.getProperty("monada.store.dir");
        if (storeDir == null || storeDir.isBlank()) {
            if (args.length > 0 && !args[0].isBlank()) {
                storeDir = args[0];
            }
        }

        if (storeDir == null || storeDir.isBlank()) {
            System.err.println("Usage: StorageIntegrityAuditMain <store-directory>");
            System.err.println("   or: ./gradlew :monada-storage:runIntegrityAudit -Dmonada.store.dir=/path/to/store");
            System.exit(1);
            return;
        }

        Path root = Path.of(storeDir);
        StorageIntegrityAuditor auditor = new StorageIntegrityAuditor();
        StorageIntegrityReport report = auditor.audit(root);

        System.out.println(report.render());

        if (report.hasErrorsOrFatal()) {
            System.exit(1);
        }
    }
}
