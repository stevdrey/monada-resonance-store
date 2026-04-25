plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-encoder"))
    implementation(project(":monada-storage"))
    implementation(project(":monada-index"))
    implementation(project(":monada-learning"))
}

application {
    mainClass.set("com.monada.api.Main")
}