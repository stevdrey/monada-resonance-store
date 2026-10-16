plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
    implementation(project(":monada-encoder"))
    implementation(project(":monada-storage"))
    implementation(project(":monada-index"))
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

tasks.register<JavaExec>("runScanPhaseBreakdown") {
    description = "Isolate linear-scan atom load, vector load, scoring, dedup, and full-sort costs before top-K optimization (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.ScanPhaseBreakdownMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runVectorLoadBreakdown") {
    description = "Split linear-scan vector loading into index parse, seek, per-float read, bulk read, decode, and materialization costs (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.VectorLoadBreakdownMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runVectorCopyShare") {
    description = "Measure the share of FrequencyVector's defensive copy in FileFrequencyStore and FileSpeechFeatureStore findAll() (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.VectorCopyShareMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runSpeechFeatureLoadBreakdown") {
    description = "Split FileSpeechFeatureStore.findAll() into index parse, seek, header read, value read and materialization (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.SpeechFeatureLoadBreakdownMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runBoundedTopKExperiment") {
    description = "Compare the experimental exact bounded top-K selector against the linear-scan full-sort oracle (exploratory)"
    group = "application"
    mainClass.set("com.monada.evaluation.BoundedTopKExperimentMain")
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

tasks.register<JavaExec>("runExecutionSampleExport") {
    description = "Read-only export of bounded, provenance-preserving execution samples from a ledger (use -Dmonada.execution.dir and -Dmonada.execution.scope)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.ExperienceExportMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.execution.") }
        .associateWith { System.getProperty(it) }
}

tasks.register<JavaExec>("runForgeExperienceEvaluation") {
    description = "Run the reproducible Forge experience-memory evaluation: hard correctness gates plus exploratory retrieval quality (no Forge, network or provider required)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.ForgeExperienceEvaluationMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runMultiAttemptExecutionEvaluation") {
    description = "Run the multi-attempt evaluation: exact attempt-result joins of recall hits to exported execution samples over fixture forge-multi-attempt-v1 (no Forge, network or provider required)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.MultiAttemptEvaluationMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runForgeRecallStress") {
    description = "Run the judged Forge recall stress set (paraphrase, synonym, negation, relation, terminology, no-relevant, duplicate-summary, empty-query) over fixture forge-experience-v1 (exploratory metrics plus hard checks; no Forge, network or provider required)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.RecallStressEvaluationMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runForgeRecallThresholdSweep") {
    description = "Sweep host-owned relevance thresholds as an evaluation-only post-filter over the judged Forge recall stress set (exploratory precision/recall/abstention tradeoffs plus hard checks; no threshold is recommended; no Forge, network or provider required)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.ThresholdSweepMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runForgeQueryExpansionComparison") {
    description = "Compare exact queries with a bounded, documented query-side lexical expansion over the judged Forge recall stress set on the same persisted projection (offline A/B with vetoes; evaluation-only, nothing adopted; no Forge, network or provider required)"
    group = "application"
    mainClass.set("com.monada.evaluation.execution.QueryExpansionMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runExecutionScaleSweep") {
    description = "Measure scoped execution recall at N=25/250/1000 experiences per scope over several isolated scopes: append, projection rebuild, reopen, read-only open and top-K recall, with hard isolation, exact-ref, tie-order and parity checks (timing and memory informational only; no Forge, network or provider required). Options via --args, e.g. --args=\"--sizes=25,250,1000 --iterations=10\""
    group = "application"
    mainClass.set("com.monada.evaluation.execution.ExecutionScaleSweepMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.named<Test>("test") {
    // The cross-JVM determinism test starts child JVMs that run the evaluation command on this runtime classpath.
    doFirst {
        systemProperty("forge.runtime.classpath", sourceSets["main"].runtimeClasspath.asPath)
    }
}
