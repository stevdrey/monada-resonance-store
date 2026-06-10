plugins {
    java
}

dependencies {
    implementation(project(":monada-core"))
    testImplementation(project(":monada-storage"))
    testImplementation(project(":monada-encoder"))
}
