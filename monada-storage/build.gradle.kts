description = "Monada Resonance Store manifest, append-only logs, vector files and feedback logs."

dependencies {
    // Public storage signatures expose core atoms and vectors.
    api(project(":monada-core"))
}

tasks.register<JavaExec>("runIntegrityAudit") {
    description = "Run cross-file atom/vector/index integrity audit on a Monada store"
    group = "application"
    mainClass.set("com.monada.storage.audit.StorageIntegrityAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.store.") }
        .associateWith { System.getProperty(it) }
}

tasks.register<JavaExec>("runExecutionLedgerAudit") {
    description = "Run a read-only integrity audit on a Monada execution ledger directory"
    group = "application"
    mainClass.set("com.monada.storage.execution.ExecutionLedgerAuditMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.execution.") }
        .associateWith { System.getProperty(it) }
}
