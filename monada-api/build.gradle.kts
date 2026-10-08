plugins {
    application
}

description = "Monada Resonance Store developer-facing embedded memory API."

dependencies {
    // MonadaMemory/MonadaMemoryOptions expose core, encoder and storage types in public signatures.
    api(project(":monada-core"))
    api(project(":monada-encoder"))
    api(project(":monada-storage"))
    implementation(project(":monada-index"))
    implementation(project(":monada-learning"))
}

application {
    mainClass.set("com.monada.api.Main")
}
