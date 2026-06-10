pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "monada-resonance-store"

include(
    "monada-core",
    "monada-encoder",
    "monada-storage",
    "monada-index",
    "monada-learning",
    "monada-api",
    "monada-evaluation",
    "monada-speech"
)
