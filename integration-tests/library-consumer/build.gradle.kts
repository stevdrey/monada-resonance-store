val monadaVersion = providers.gradleProperty("monadaVersion").orNull
    ?: error("Missing -PmonadaVersion=<published Monada version>")

val apiCoordinate = "com.monada:monada-api:$monadaVersion"

// Modules a consumer must see at compile time (exported through `api` metadata).
val expectedCompileModules = setOf("monada-api", "monada-core", "monada-encoder", "monada-storage")

// Full production runtime graph pulled transitively from the single API coordinate.
val expectedRuntimeModules = expectedCompileModules + setOf("monada-index", "monada-learning")

subprojects {
    apply(plugin = "application")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(27))
        }
    }

    dependencies {
        "implementation"(apiCoordinate)
    }

    // Always re-read the freshly published SNAPSHOT instead of a cached copy.
    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }

    val printResolution = tasks.register("printResolution") {
        description = "Print and verify the resolved Monada dependency graph"
        group = "verification"
        val consumerName = project.name
        val compileArtifacts = configurations.getByName("compileClasspath").incoming.artifacts.resolvedArtifacts
        val runtimeArtifacts = configurations.getByName("runtimeClasspath").incoming.artifacts.resolvedArtifacts
        doLast {
            fun verify(label: String, artifacts: Set<ResolvedArtifactResult>, expected: Set<String>) {
                println("[$label] declared: $apiCoordinate")
                val resolved = artifacts.map { artifact ->
                    val id = artifact.id.componentIdentifier
                    println("[$label] $id -> ${artifact.file.name}")
                    check(id is ModuleComponentIdentifier) {
                        "Unexpected non-repository component in $label: $id"
                    }
                    check(id.group == "com.monada" && id.version == monadaVersion) {
                        "Unexpected component in $label: $id"
                    }
                    id.module
                }.toSet()
                check(resolved == expected) {
                    "[$label] expected $expected but resolved $resolved"
                }
            }
            verify("$consumerName compileClasspath", compileArtifacts.get(), expectedCompileModules)
            verify("$consumerName runtimeClasspath", runtimeArtifacts.get(), expectedRuntimeModules)
        }
    }

    tasks.named("run") {
        mustRunAfter(printResolution)
    }
}

tasks.register("verifyConsumer") {
    description = "Verify dependency resolution and run the classpath and JPMS persistence smoke tests"
    group = "verification"
    subprojects.forEach { consumer ->
        dependsOn(consumer.tasks.named("printResolution"), consumer.tasks.named("run"))
    }
}
