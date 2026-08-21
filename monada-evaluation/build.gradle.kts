plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
    implementation(project(":monada-encoder"))
    implementation(project(":monada-storage"))
    implementation(project(":monada-speech"))
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

tasks.register<JavaExec>("runNormalizedQueryKeyStress") {
    description = "Stress normalized feedback keys with adversarial semantic collision pairs (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.NormalizedQueryKeyStressMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runLatency") {
    description = "Run recall latency and scan diagnostics over the expanded dataset"
    group = "application"
    mainClass.set("com.monada.evaluation.LatencyProfileMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runLatencyScaleSweep") {
    description = "Run the scaled linear-scan latency benchmark and decision gate across multiple corpus sizes"
    group = "application"
    mainClass.set("com.monada.evaluation.LatencyScaleSweepMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runProjectMemory") {
    description = "Run the project-memory evaluation dataset built from the repository's own documentation (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.ProjectMemoryMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runSpeechModalityComparison") {
    description = "Compare transcript-only and acoustic-only speech retrieval using deterministic generated fixtures"
    group = "application"
    mainClass.set("com.monada.evaluation.speech.SpeechModalityComparisonMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runSpeechHybridSweep") {
    description = "Evaluate deterministic transcript/acoustic score-fusion weights using generated fixtures"
    group = "application"
    mainClass.set("com.monada.evaluation.speech.SpeechHybridSweepMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runSpeechHybridRobustness") {
    description = "Stress the fixed transcript/acoustic hybrid candidate using generated conflict fixtures"
    group = "application"
    mainClass.set("com.monada.evaluation.speech.SpeechHybridRobustnessMain")
    classpath = sourceSets["main"].runtimeClasspath
}
