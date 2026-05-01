plugins {
    application
}

dependencies {
    implementation(project(":monada-core"))
    implementation(project(":monada-api"))
    implementation(project(":monada-storage"))
}

application {
    mainClass.set("com.monada.evaluation.Main")
}
