plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
    implementation(project(":monada-encoder"))
    implementation(project(":monada-storage"))
}

application {
    mainClass.set("com.monada.evaluation.Main")
}

tasks.register<JavaExec>("runExpanded") {
    description = "Run the expanded technology evaluation dataset (exploratory, not protected baseline)"
    group = "application"
    mainClass.set("com.monada.evaluation.ExpandedMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runProfileComparison") {
    description = "Run the A/B profile comparison harness over the expanded dataset (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.ProfileComparisonMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runFeedbackReplay") {
    description = "Compare empty, synthetic, and persisted feedback replay modes (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.FeedbackReplayMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runFeedbackQueryKeyComparison") {
    description = "Compare exact, normalized, and lexical persisted feedback query keys (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.FeedbackQueryKeyComparisonMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runProjectMemoryFeedbackKeyValidation") {
    description = "Validate exact and normalized persisted feedback keys over project memory (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.ProjectMemoryFeedbackKeyValidationMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runLatency") {
    description = "Run recall latency and scan diagnostics over the expanded dataset"
    group = "application"
    mainClass.set("com.monada.evaluation.LatencyProfileMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runProjectMemory") {
    description = "Run the project-memory evaluation dataset built from the repository's own documentation (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.ProjectMemoryMain")
    classpath = sourceSets["main"].runtimeClasspath
}
