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
