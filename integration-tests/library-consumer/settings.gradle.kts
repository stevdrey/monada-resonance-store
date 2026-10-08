// Isolated consumer build for the published Monada library artifacts.
// It is NOT part of the root multi-project build: no includeBuild, no project(...)
// substitution and no manually supplied jars. Dependencies resolve only from the
// temporary verification repository produced by
// `./gradlew publishLibrariesToVerificationRepository` in the repository root.

val monadaRepo = providers.gradleProperty("monadaRepo").orNull
    ?: error("Missing -PmonadaRepo=<absolute path to the verification repository>")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Only the temporary local repository: any undeclared or third-party
        // dependency in the published metadata fails resolution.
        maven {
            name = "monadaVerification"
            url = uri(file(monadaRepo))
        }
    }
}

rootProject.name = "monada-library-consumer"

include("classpath-consumer", "module-consumer")
