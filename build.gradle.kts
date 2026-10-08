plugins {
    java
}

group = "com.monada"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(27))
    }
}

subprojects {

    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(27))
        }
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
    }
}

// Production library graph published as Maven artifacts (see docs/specs/library-consumption.md).
// Evaluation and speech modules are intentionally not published.
val publishedLibraries = mapOf(
    "monada-core" to "com.monada.core",
    "monada-encoder" to "com.monada.encoder",
    "monada-storage" to "com.monada.storage",
    "monada-index" to "com.monada.index",
    "monada-learning" to "com.monada.learning",
    "monada-api" to "com.monada.api"
)

val verificationRepository = layout.buildDirectory.dir("verification-repo")

configure(subprojects.filter { it.name in publishedLibraries }) {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    val automaticModuleName = publishedLibraries.getValue(name)

    java {
        withSourcesJar()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
    }

    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Automatic-Module-Name" to automaticModuleName,
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        }
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("library") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(provider { project.description })
                    url.set("https://github.com/stevdrey/monada-resonance-store")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    scm {
                        url.set("https://github.com/stevdrey/monada-resonance-store")
                        connection.set("scm:git:https://github.com/stevdrey/monada-resonance-store.git")
                    }
                }
            }
        }
        repositories {
            // Local, temporary verification repository only. No remote registry is configured.
            maven {
                name = "verification"
                url = uri(verificationRepository)
            }
        }
    }
}

val cleanVerificationRepository = tasks.register<Delete>("cleanVerificationRepository") {
    description = "Delete the temporary local Maven verification repository"
    group = "publishing"
    delete(verificationRepository)
}

val publishLibrariesToVerificationRepository = tasks.register("publishLibrariesToVerificationRepository") {
    description = "Publish the six production library artifacts into the temporary local verification repository"
    group = "publishing"
    dependsOn(cleanVerificationRepository)
    dependsOn(publishedLibraries.keys.map { ":$it:publishAllPublicationsToVerificationRepository" })
}

// Order every concrete publication task (not just the aggregate) after cleanup so that
// parallel execution cannot erase freshly published artifacts.
publishedLibraries.keys.forEach { libraryName ->
    project(":$libraryName").tasks.withType<PublishToMavenRepository>()
        .matching { it.name.endsWith("ToVerificationRepository") }
        .configureEach { mustRunAfter(cleanVerificationRepository) }
}

tasks.register<GradleBuild>("verifyLibraryConsumer") {
    description = "Run the isolated classpath and JPMS consumer build against the verification repository"
    group = "verification"
    dependsOn(publishLibrariesToVerificationRepository)
    dir = file("integration-tests/library-consumer")
    tasks = listOf("verifyConsumer")
    startParameter.projectProperties = mapOf(
        "monadaRepo" to verificationRepository.get().asFile.absolutePath,
        "monadaVersion" to version.toString()
    )
}
