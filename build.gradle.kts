plugins {
    java
}

group = "com.monada"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

subprojects {

    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    }

    tasks.test {
        useJUnitPlatform()
    }
}