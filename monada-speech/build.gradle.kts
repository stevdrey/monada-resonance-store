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
