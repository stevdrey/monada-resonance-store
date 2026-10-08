description = "Monada Resonance Store similarity search and deterministic ranking."

dependencies {
    // Public index signatures expose core vectors/results and storage stores.
    api(project(":monada-core"))
    api(project(":monada-storage"))
}
