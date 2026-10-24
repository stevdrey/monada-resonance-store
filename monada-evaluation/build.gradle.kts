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

tasks.register<JavaExec>("runLedgerGrowthBenchmark") {
    description = "Measure the execution ledger as it grows: append, writable reopen, lock-free read-only open, history replay, exact loadEvent and pinned-cutoff sample-export paging at N=100/1000/5000 events, with hard idempotency, replay-fidelity, exact-ref, page-equality and cutoff/cursor-stability checks (timing and memory informational only; no Forge, network or provider required). Options via --args, e.g. --args=\"--sizes=100,1000,5000 --exportPageSizes=1,50,100 --iterations=10\""
    group = "application"
    mainClass.set("com.monada.evaluation.execution.LedgerGrowthBenchmarkMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runOpenCostBreakdownBenchmark") {
    description = "Split ExecutionMemory.open / openReadOnly into ledger scan, history index build and projection load/verification at N=1000/5000/20000/50000 events, by difference against the storage-level open and a projection-less copy of the root, with hard projection-state, replay-fidelity and no-write checks (timing and memory informational only; no Forge, network or provider required). Options via --args, e.g. --args=\"--sizes=1000,5000,20000,50000 --iterations=10\""
    group = "application"
    mainClass.set("com.monada.evaluation.execution.OpenCostBreakdownMain")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runBookChunking") {
    description = "Chunk the verified book-corpus-v1 normalized body into bounded, chapter-aware chunks plus a versioned provenance sidecar (policy book-chunking-v1; offline, no network). Options via --args, e.g. --args=\"--input <body.txt> --out <dir>\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookChunkingMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookStoreReadback") {
    description = "Seed a disposable Store from the verified book-corpus-v1 chunks, reopen it and verify exact content readback, sidecar mapping and structural audit (evaluation only; offline). Options via --args, e.g. --args=\"seed --input <body.txt> --work <new dir>\" or --args=\"verify --work <dir>\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookStoreReadbackMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookRecall") {
    description = "Measure exploratory recall quality of a seeded book Store against the judged book-queries-v1 evidence: per-partition P@K/R@K/Hit@K/MRR, negative-query false hits, failure taxonomy and full per-query trace (evaluation only; offline). Options via --args, e.g. --args=\"--work <seeded dir> [--out <report.txt>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookRecallMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookHubDiagnostics") {
    description = "Diagnose top-K hub chunks of the frozen book recall baseline: top-1/3/10 frequency by DEV/HOLDOUT/ALL, predeclared DEV controls, length/token/vector metrics and an exact per-bucket score decomposition with production score parity (evaluation only; offline; read-only). Options via --args, e.g. --args=\"--work <seeded dir> --baseline docs/experiments/book-corpus-v1/book-recall-v1-report.txt [--out <report.txt>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookHubDiagnosticsMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookUnjudgedReview") {
    description = "Review formerly unjudged DEV book hits for book-queries-v2: re-derive the predeclared selection from the frozen book-recall-v1 report, write a local score-blind worksheet (outside Git), apply human decisions to review.psv, and render the label-coverage report (evaluation only; offline; no model). Options via --args, e.g. --args=\"select\", --args=\"worksheet --input <body.txt> --out <sheet.md>\", --args=\"apply --input <body.txt> --worksheet <sheet.md> --dataset <dir>\" or --args=\"report [--out <file>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookUnjudgedReviewMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookLatency") {
    description = "Benchmark real-text ingest (encode + persist), reopen and recall latency of the verified book-corpus-v1 chunks over a declared data-size ladder (25%/50%/100% real, x4/x16 replica-tagged derived), in process and in sequential fresh child JVMs, with host metadata, nearest-rank p50/p95/p99 only where sample counts allow and a hard top-K result-id parity check (evaluation only; offline; timings informational; OS cold cache never measured). Options via --args, e.g. --args=\"--input <body.txt> --scratch <new dir outside Git> [--out <report.txt>] [--summary <summary.md>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookLatencyMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookFootprint") {
    description = "Profile on-disk growth, memory footprint and repeat-import behaviour of book Stores over the latency data-size ladder: per-phase (empty, fresh import, reopen, repeat import) audit counts and per-file bytes, overlap and intra-batch duplicate experiments, hard structural checks, and labelled heap/allocation/RSS samples (evaluation only; offline; memory informational). Options via --args, e.g. --args=\"--input <body.txt> --scratch <new dir outside Git> [--out <report.txt>] [--summary <summary.md>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookFootprintMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runBookFeedback") {
    description = "Replay predeclared explicit DEV-oracle feedback on book Stores and add one explicit verified note: isolated control/treatment/note Stores from the same frozen chunks, per-query control vs treatment (after reopen) P@K/R@K/Hit@K/MRR, regressions, HOLDOUT/confusable/NO_ANSWER contamination checks, note persistence after reopen, repeat-run determinism (evaluation only; offline; no autonomous learning). Options via --args, e.g. --args=\"--input <body.txt> --scratch <new dir outside Git> [--out <report.txt>]\""
    group = "application"
    mainClass.set("com.monada.evaluation.book.BookFeedbackMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.named<Test>("test") {
    // The cross-JVM determinism test starts child JVMs that run the evaluation command on this runtime classpath.
    doFirst {
        systemProperty("forge.runtime.classpath", sourceSets["main"].runtimeClasspath.asPath)
    }
}
