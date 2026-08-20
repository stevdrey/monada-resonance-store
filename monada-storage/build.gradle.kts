dependencies {
    implementation(project(":monada-core"))
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