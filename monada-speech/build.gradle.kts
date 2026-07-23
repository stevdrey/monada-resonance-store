plugins {
    java
}

dependencies {
    implementation(project(":monada-core"))
    testImplementation(project(":monada-storage"))
    testImplementation(project(":monada-encoder"))
}

tasks.register<JavaExec>("runSpeechBenchmark") {
    description = "Run the exploratory local real-data speech benchmark (NOT a protected CI baseline)"
    group = "application"
    mainClass.set("com.monada.speech.evaluation.SpeechBenchmarkMain")
    classpath = sourceSets["main"].runtimeClasspath
    // Forward JVM system properties (e.g. -Dmonada.speech.benchmark.dir=...) and let the
    // process inherit the environment so MONADA_SPEECH_BENCHMARK_* variables are visible.
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.speech.benchmark.") }
        .associateWith { System.getProperty(it) }
}

tasks.register<JavaExec>("runSpeechEvaluation") {
    description = "Evaluate an existing local speech store (NOT a protected CI baseline)"
    group = "application"
    mainClass.set("com.monada.speech.evaluation.SpeechEvaluationMain")
    classpath = sourceSets["main"].runtimeClasspath
    // Forward JVM system properties and inherit MONADA_SPEECH_EVALUATION_* variables.
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.speech.evaluation.") }
        .associateWith { System.getProperty(it) }
}

tasks.register<JavaExec>("runSpeechImport") {
    description = "Import a local TORGO-style corpus into a persistent speech store"
    group = "application"
    mainClass.set("com.monada.speech.importer.SpeechImportMain")
    classpath = sourceSets["main"].runtimeClasspath
    // Forward JVM system properties and inherit MONADA_SPEECH_IMPORT_* variables.
    systemProperties = System.getProperties()
        .stringPropertyNames()
        .filter { it.startsWith("monada.speech.import.") }
        .associateWith { System.getProperty(it) }
}
